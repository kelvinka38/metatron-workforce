#!/usr/bin/env bash
set -euo pipefail

: "${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}"
: "${HIGHWAY_PARENT_TASK_ID:?HIGHWAY_PARENT_TASK_ID required}"
: "${METATRON_HIGHWAY_TOKEN:?METATRON_HIGHWAY_TOKEN required}"

python3 - <<'PY'
import json, os, subprocess, time, urllib.request
from datetime import datetime, timezone

target=os.environ["HIGHWAY_SOURCE_SHA"]
parent_id=os.environ["HIGHWAY_PARENT_TASK_ID"]
token=os.environ["METATRON_HIGHWAY_TOKEN"]
port=os.environ.get("METATRON_HIGHWAY_PORT","18090")
base=f"http://127.0.0.1:{port}"
required=("production-highway-fast","work-observability","control-room-fast")
terminal={"SUCCEEDED","FAILED","DEAD_LETTERED","BLOCKED","CANCELLED"}

def get(path):
    req=urllib.request.Request(
        base+path,
        headers={"Authorization":f"Bearer {token}","Accept":"application/json"},
    )
    with urllib.request.urlopen(req,timeout=5) as response:
        return json.loads(response.read() or b"{}")

def ts(value):
    return datetime.fromisoformat(value.replace("Z","+00:00")).timestamp()

health=get("/health")
assert health.get("status")=="UP", health
assert int(health.get("executors",0)) >= 4, health

deadline=time.monotonic()+60
selected={}
parent=None
while time.monotonic() < deadline:
    parent=get(f"/tasks/{parent_id}")
    rows=get("/tasks")
    selected={}
    for kind in required:
        matches=[
            row for row in rows
            if row.get("source_sha")==target
            and row.get("kind")==kind
            and (row.get("env") or {}).get("HIGHWAY_PARENT_TASK_ID")==parent_id
        ]
        if matches:
            matches.sort(key=lambda row:row.get("created_at") or "")
            selected[kind]=matches[-1]
    if len(selected)==len(required):
        failed=[(kind,row.get("state"),row.get("failure","")) for kind,row in selected.items()
                if row.get("state") in terminal and row.get("state")!="SUCCEEDED"]
        if failed:
            raise AssertionError(f"fanout failure: {failed}")
        if all(row.get("state")=="SUCCEEDED" for row in selected.values()):
            break
    time.sleep(.2)
else:
    snapshot={kind:(row.get("state"),row.get("failure","")) for kind,row in selected.items()}
    raise AssertionError(f"fanout timeout: {snapshot}")

assert parent is not None and parent.get("state")=="SUCCEEDED", parent
deps=parent.get("dependencies") or []
assert len(deps)==1, f"deploy must have exactly one build dependency: {deps}"
build=get(f"/tasks/{deps[0]}")
assert build.get("kind")=="workforce-build", build
assert build.get("state")=="SUCCEEDED", build
assert build.get("source_sha")==target and parent.get("source_sha")==target

build_finish=ts(build["finished_at"])
deploy_start=ts(parent["started_at"])
deploy_finish=ts(parent["finished_at"])
assert deploy_start >= build_finish-0.05, (build_finish,deploy_start)

starts={kind:ts(row["started_at"]) for kind,row in selected.items()}
finishes={kind:ts(row["finished_at"]) for kind,row in selected.items()}
start_spread=max(starts.values())-min(starts.values())
dispatch_lag=max(starts.values())-deploy_finish
assert start_spread < 2.0, f"fanout dispatch spread too large: {start_spread}"
assert dispatch_lag < 2.0, f"fanout dispatch lag too large: {dispatch_lag}"

pairs=[]
kinds=list(required)
for i in range(len(kinds)):
    for j in range(i+1,len(kinds)):
        a,b=kinds[i],kinds[j]
        if max(starts[a],starts[b]) < min(finishes[a],finishes[b]):
            pairs.append((a,b))
assert pairs, f"independent fanout showed no execution overlap: starts={starts} finishes={finishes}"

cid=subprocess.check_output(
    ["docker","ps","--filter","name=deploy-workforce-1","--format","{{.ID}}"],
    text=True,
).strip().splitlines()
assert cid, "live Workforce container not found"
cid=cid[0]
env=subprocess.check_output(
    ["docker","inspect",cid,"--format","{{range .Config.Env}}{{println .}}{{end}}"],
    text=True,
)
live_sha=""
for line in env.splitlines():
    if line.startswith("METATRON_COMMIT_SHA="):
        live_sha=line.split("=",1)[1].strip()
        break
health_state=subprocess.check_output(
    ["docker","inspect",cid,"--format","{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}"],
    text=True,
).strip()
assert live_sha==target, (live_sha,target)
assert health_state=="healthy", health_state

print(f"HIGHWAY_RUNTIME_CLOSURE_PARENT={parent_id}")
print(f"HIGHWAY_RUNTIME_CLOSURE_BUILD={build['task_id']}")
for kind in required:
    print(f"HIGHWAY_RUNTIME_CLOSURE_CHILD kind={kind} id={selected[kind]['task_id']} state=SUCCEEDED")
print(f"HIGHWAY_RUNTIME_CLOSURE_START_SPREAD_SECONDS={start_spread:.3f}")
print(f"HIGHWAY_RUNTIME_CLOSURE_DISPATCH_LAG_SECONDS={dispatch_lag:.3f}")
print(f"HIGHWAY_RUNTIME_CLOSURE_OVERLAP_PAIRS={len(pairs)}")
print(f"HIGHWAY_RUNTIME_CLOSURE_LIVE_SHA={live_sha}")
print("HIGHWAY_BUILD_DEPLOY_DAG=PASS")
print("HIGHWAY_POST_DEPLOY_PARALLEL_FANOUT=PASS")
print("HIGHWAY_LIVE_EXACT_SHA=PASS")
print("HIGHWAY_RUNTIME_CLOSURE=PASS")
PY
