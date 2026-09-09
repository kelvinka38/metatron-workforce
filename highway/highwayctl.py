#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, os, sys, time, urllib.request, urllib.error

PORT=int(os.environ.get("METATRON_HIGHWAY_PORT","18090"))
BASE=os.environ.get("METATRON_HIGHWAY_URL",f"http://127.0.0.1:{PORT}")
TOKEN=os.environ.get("METATRON_HIGHWAY_TOKEN","").strip()

def request(method,path,payload=None,auth=True):
    headers={"Accept":"application/json"}
    if auth:
        if not TOKEN:
            raise SystemExit("METATRON_HIGHWAY_TOKEN is required")
        headers["Authorization"]=f"Bearer {TOKEN}"
    data=None
    if payload is not None:
        data=json.dumps(payload).encode()
        headers["Content-Type"]="application/json"
    req=urllib.request.Request(BASE+path,data=data,method=method,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=15) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        body=e.read().decode(errors="replace")
        raise SystemExit(f"highway API {e.code}: {body}")

def parse_resource(value):
    if ":" not in value:
        raise argparse.ArgumentTypeError("resource must be NAME:READ or NAME:WRITE")
    name,mode=value.rsplit(":",1)
    mode=mode.upper()
    if not name or mode not in ("READ","WRITE"):
        raise argparse.ArgumentTypeError("resource must be NAME:READ or NAME:WRITE")
    return {"name":name,"mode":mode}

def parse_env(value):
    if "=" not in value:
        raise argparse.ArgumentTypeError("env must be KEY=VALUE")
    k,v=value.split("=",1)
    if not k:
        raise argparse.ArgumentTypeError("env key required")
    return k,v

def main():
    p=argparse.ArgumentParser(prog="highwayctl")
    sub=p.add_subparsers(dest="cmd",required=True)
    sub.add_parser("health")
    sub.add_parser("stats")
    ls=sub.add_parser("list"); ls.add_argument("--limit",type=int,default=100)
    get=sub.add_parser("get"); get.add_argument("task_id")
    wait=sub.add_parser("wait"); wait.add_argument("task_id"); wait.add_argument("--timeout",type=int,default=900); wait.add_argument("--poll",type=float,default=.5)
    submit=sub.add_parser("submit")
    submit.add_argument("--kind",required=True)
    submit.add_argument("--source-sha",required=True)
    submit.add_argument("--name")
    submit.add_argument("--correlation-id")
    submit.add_argument("--priority",type=int)
    submit.add_argument("--max-attempts",type=int)
    submit.add_argument("--timeout-seconds",type=int)
    submit.add_argument("--dedupe-key")
    submit.add_argument("--dependency",action="append",default=[])
    submit.add_argument("--resource",action="append",type=parse_resource,default=[])
    submit.add_argument("--env",action="append",type=parse_env,default=[])

    a=p.parse_args()
    if a.cmd=="health":
        print(json.dumps(request("GET","/health",auth=False),sort_keys=True)); return
    if a.cmd=="stats":
        print(json.dumps(request("GET","/stats"),sort_keys=True)); return
    if a.cmd=="list":
        print(json.dumps(request("GET",f"/tasks?limit={a.limit}"),sort_keys=True)); return
    if a.cmd=="get":
        print(json.dumps(request("GET",f"/tasks/{a.task_id}"),sort_keys=True)); return
    if a.cmd=="wait":
        deadline=time.monotonic()+a.timeout
        while True:
            task=request("GET",f"/tasks/{a.task_id}")
            state=task["state"]
            if state in ("SUCCEEDED","FAILED","DEAD_LETTERED","BLOCKED","CANCELLED"):
                print(json.dumps(task,sort_keys=True))
                if state!="SUCCEEDED": raise SystemExit(1)
                return
            if time.monotonic()>=deadline:
                raise SystemExit(f"timeout waiting for {a.task_id}; last state={state}")
            time.sleep(a.poll)
    if a.cmd=="submit":
        payload={
            "kind":a.kind,
            "source_sha":a.source_sha,
            "dependencies":a.dependency,
            "resources":a.resource,
            "env":dict(a.env),
        }
        for k in ("name","correlation_id","priority","max_attempts","timeout_seconds","dedupe_key"):
            v=getattr(a,k)
            if v is not None: payload[k]=v
        task=request("POST","/tasks",payload)
        print(json.dumps(task,sort_keys=True))
        return

if __name__=="__main__":
    main()
