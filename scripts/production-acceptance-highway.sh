#!/usr/bin/env bash
set -euo pipefail

: "${TARGET_SHA:?TARGET_SHA required}"
BASE=/opt/metatron/metatron-workforce
COMPOSE="${GITHUB_WORKSPACE:?}/deploy/docker-compose.yml"
OUT="/tmp/metatron-production-highway-${GITHUB_RUN_ID:?}"
mkdir -p "$OUT"
test -r "$BASE/.env"; test -f "$COMPOSE"; command -v flock >/dev/null
set -a; source "$BASE/.env"; set +a
test -n "${TELEGRAM_WEBHOOK_SECRET:-}"; test -n "${TELEGRAM_ALLOWED_USER_ID:-}"; test -n "${METATRON_ORGANIZATION_ID:-}"
test -n "${OPENAI_API_KEY:-}${GEMINI_API_KEY:-}${ANTHROPIC_API_KEY:-}"

LIVE_CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1); test -n "$LIVE_CID"
LIVE_SHA=$(docker inspect "$LIVE_CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$LIVE_SHA" = "$TARGET_SHA"; test "$(docker inspect "$LIVE_CID" --format '{{.State.Health.Status}}')" = healthy
docker image inspect "metatron-workforce:$TARGET_SHA" >/dev/null
echo "HIGHWAY_TARGET_SHA=$TARGET_SHA"; echo "HIGHWAY_LIVE_SHA=$LIVE_SHA"

reclaim_stale_highway() {
  local project cids networks volumes
  while IFS= read -r project; do
    [ -n "$project" ] || continue
    [[ "$project" =~ ^highway-[0-9]+-lane-[ab]$ ]] || continue
    echo "HIGHWAY_RECLAIM_STALE_PROJECT=$project"
    cids=$(docker ps -aq --filter "label=com.docker.compose.project=$project" || true)
    [ -z "$cids" ] || docker rm -f $cids >/dev/null 2>&1 || true
    networks=$(docker network ls -q --filter "label=com.docker.compose.project=$project" || true)
    [ -z "$networks" ] || docker network rm $networks >/dev/null 2>&1 || true
    volumes=$(docker volume ls -q --filter "label=com.docker.compose.project=$project" || true)
    [ -z "$volumes" ] || docker volume rm -f $volumes >/dev/null 2>&1 || true
  done < <(docker ps -a --filter label=com.docker.compose.project --format '{{.Label "com.docker.compose.project"}}' | sort -u)
}
reclaim_stale_highway

read -r SINK_PORT PORT_A PORT_B < <(python3 <<'PY'
import socket
sockets=[]
try:
    for _ in range(3):
        s=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
        s.bind(('127.0.0.1',0))
        sockets.append(s)
    print(*(s.getsockname()[1] for s in sockets))
finally:
    for s in sockets:
        s.close()
PY
)
test -n "$SINK_PORT"; test -n "$PORT_A"; test -n "$PORT_B"
test "$SINK_PORT" != "$PORT_A"; test "$SINK_PORT" != "$PORT_B"; test "$PORT_A" != "$PORT_B"
echo "HIGHWAY_DYNAMIC_PORTS=$SINK_PORT,$PORT_A,$PORT_B"

PROJECT_A="highway-${GITHUB_RUN_ID}-lane-a"; PROJECT_B="highway-${GITHUB_RUN_ID}-lane-b"
python3 - "$SINK_PORT" "$OUT/telegram-sink.log" <<'PY' &
import json,sys
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
port=int(sys.argv[1]); log=sys.argv[2]
class H(BaseHTTPRequestHandler):
    counter=1000
    def do_POST(self):
        n=int(self.headers.get('content-length','0')); body=self.rfile.read(n); H.counter+=1
        with open(log,'ab') as f: f.write(self.path.encode()+b' '+body+b'\n')
        raw=json.dumps({'ok':True,'result':{'message_id':H.counter}}).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def log_message(self,*args): pass
ThreadingHTTPServer(('0.0.0.0',port),H).serve_forever()
PY
SINK_PID=$!

down_lane() {
  local project="$1" port="$2" lane="$3"
  METATRON_HOST_PORT="$port" METATRON_STATE_VOLUME_NAME="${project}-state" METATRON_WORKFORCE_NETWORK_NAME="${project}-network" \
  METATRON_WORKFORCE_GATEWAY_ALIAS="workforce-${lane}-${GITHUB_RUN_ID}" TELEGRAM_API_BASE_URL="http://host.docker.internal:$SINK_PORT" \
  docker compose -p "$project" --env-file "$BASE/.env" -f "$COMPOSE" down -v --remove-orphans >/dev/null 2>&1 || true
}
cleanup() { set +e; down_lane "$PROJECT_A" "$PORT_A" lane-a; down_lane "$PROJECT_B" "$PORT_B" lane-b; kill "$SINK_PID" >/dev/null 2>&1 || true; }
trap cleanup EXIT
sink_ready=0
for _ in $(seq 1 30); do
  if curl -fsS --max-time 1 -X POST "http://127.0.0.1:$SINK_PORT/bottest/sendMessage" -H 'Content-Type: application/json' -d '{}' >/dev/null; then sink_ready=1; break; fi
  kill -0 "$SINK_PID" >/dev/null 2>&1 || break
  sleep 1
done
test "$sink_ready" = 1; kill -0 "$SINK_PID" >/dev/null 2>&1
echo 'HIGHWAY_TELEGRAM_SINK=READY'

telegram_body() {
  python3 - "$1" "$2" "$TELEGRAM_ALLOWED_USER_ID" <<'PY'
import json,sys
u=int(sys.argv[1]); uid=int(sys.argv[3]); print(json.dumps({'update_id':u,'message':{'message_id':u%2000000000,'from':{'id':uid,'is_bot':False,'first_name':'Founder'},'chat':{'id':uid,'type':'private'},'date':0,'text':sys.argv[2]}},ensure_ascii=False))
PY
}
send_local_update() {
  local port="$1" update="$2" text="$3" output="$4" body status attempt
  body=$(telegram_body "$update" "$text")
  for attempt in 1 2 3 4 5; do
    status=$(curl -sS -o "$output" -w '%{http_code}' --connect-timeout 2 --max-time 20 \
      -X POST "http://127.0.0.1:$port/telegram/webhook" \
      -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
      -H 'Content-Type: application/json' --data-binary "$body" || true)
    echo "ISOLATED_INGRESS_ATTEMPT port=$port update_id=$update attempt=$attempt status=$status"
    [ "$status" = 200 ] && return 0
    sleep "$attempt"
  done
  echo "ISOLATED_INGRESS_FAILED port=$port update_id=$update" >&2
  return 1
}
wait_lane_ready() {
  local port="$1" cid="$2" http_ok state health
  for _ in $(seq 1 60); do
    http_ok=0
    curl -fsS --max-time 2 "http://127.0.0.1:$port/actuator/health" | grep -q '"status"[[:space:]]*:[[:space:]]*"UP"' && http_ok=1 || true
    state=$(docker inspect "$cid" --format '{{.State.Status}}' 2>/dev/null || true)
    health=$(docker inspect "$cid" --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' 2>/dev/null || true)
    if [ "$http_ok" = 1 ] && [ "$state" = running ] && [ "$health" = healthy ]; then return 0; fi
    sleep 2
  done
  return 1
}
port_is_free() {
  python3 - "$1" <<'PY'
import socket,sys
s=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
try:
    s.bind(('127.0.0.1',int(sys.argv[1])))
except OSError:
    raise SystemExit(1)
finally:
    s.close()
PY
}
start_lane() {
  local project="$1" lane="$2" port="$3" cid
  if ! port_is_free "$port"; then echo "HIGHWAY_PORT_NOT_FREE lane=$lane port=$port" >&2; return 1; fi
  if ! METATRON_VERSION=0.1.0 METATRON_ENVIRONMENT=production METATRON_IMAGE_TAG="$TARGET_SHA" METATRON_COMMIT_SHA="$TARGET_SHA" \
    METATRON_HOST_PORT="$port" METATRON_STATE_VOLUME_NAME="${project}-state" METATRON_WORKFORCE_NETWORK_NAME="${project}-network" \
    METATRON_WORKFORCE_GATEWAY_ALIAS="workforce-${lane}-${GITHUB_RUN_ID}" METATRON_CONTAINER_MEM_LIMIT=512m METATRON_CONTAINER_MEM_RESERVATION=256m METATRON_CONTAINER_CPUS=0.75 \
    TELEGRAM_API_BASE_URL="http://host.docker.internal:$SINK_PORT" docker compose -p "$project" --env-file "$BASE/.env" -f "$COMPOSE" up -d --no-build --force-recreate >&2; then
    echo "HIGHWAY_LANE_START_FAILED lane=$lane project=$project port=$port" >&2
    return 1
  fi
  cid=$(METATRON_HOST_PORT="$port" METATRON_STATE_VOLUME_NAME="${project}-state" METATRON_WORKFORCE_NETWORK_NAME="${project}-network" METATRON_WORKFORCE_GATEWAY_ALIAS="workforce-${lane}-${GITHUB_RUN_ID}" docker compose -p "$project" --env-file "$BASE/.env" -f "$COMPOSE" ps -q workforce) || return 1
  if [ -z "$cid" ]; then echo "HIGHWAY_LANE_CID_MISSING lane=$lane" >&2; return 1; fi
  if ! wait_lane_ready "$port" "$cid"; then echo "HIGHWAY_LANE_HEALTH_TIMEOUT lane=$lane cid=$cid port=$port" >&2; docker logs "$cid" >&2 || true; return 1; fi
  test "$(docker inspect "$cid" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)" = "$TARGET_SHA" || return 1
  echo "$cid"
}
find_objective() {
  local port="$1" update="$2" payload oid
  for _ in $(seq 1 90); do
    payload=$(curl -fsS --max-time 3 "http://127.0.0.1:$port/workforce/management/objectives" || true)
    oid=$(python3 - "$payload" "$update" <<'PY'
import json,sys
try: data=json.loads(sys.argv[1])
except Exception: raise SystemExit(1)
needle=f'telegram:update:{sys.argv[2]}'; found=[]
for row in data if isinstance(data,list) else []:
    if needle in json.dumps(row,sort_keys=True):
        o=row.get('objective') or {}; oid=o.get('objectiveId') or o.get('objective_id')
        if oid: found.append(oid)
found=list(dict.fromkeys(found))
if len(found)!=1: raise SystemExit(1)
print(found[0])
PY
    ) || true
    [ -n "${oid:-}" ] && { echo "$oid"; return 0; }; sleep 1
  done
  return 1
}
wait_planned() {
  local port="$1" oid="$2" payload result
  for _ in $(seq 1 120); do
    payload=$(curl -fsS --max-time 3 "http://127.0.0.1:$port/workforce/management/objectives/$oid" || true)
    result=$(python3 - "$payload" <<'PY'
import json,sys
try:
    d=json.loads(sys.argv[1]); o=d.get('objective') or {}; w=d.get('autonomousWork') or {}
    state=o.get('status',''); planned=w.get('plannedWork') or []
except Exception:
    print('WAIT'); raise SystemExit
if state in ('BLOCKED','ESCALATED','CANCELLED'):
    print('FAILED:'+state)
elif planned:
    print('PLANNED')
else:
    print('WAIT')
PY
    )
    case "$result" in
      PLANNED) echo 'OBJECTIVE_PLAN_DURABLE=PASS'; return 0;;
      FAILED:*) echo "OBJECTIVE_PLAN_STATE=$result"; echo "$payload"; return 2;;
    esac
    sleep 1
  done
  echo 'OBJECTIVE_PLAN_STATE=TIMEOUT'; echo "$payload"; return 1
}
wait_terminal() {
  local port="$1" oid="$2" payload state
  for _ in $(seq 1 240); do
    payload=$(curl -fsS --max-time 3 "http://127.0.0.1:$port/workforce/management/objectives/$oid" || true)
    state=$(python3 - "$payload" <<'PY'
import json,sys
try: d=json.loads(sys.argv[1]); print((d.get('objective') or {}).get('status',''))
except Exception: print('')
PY
    )
    case "$state" in
      COMPLETED|DELIVERED) echo "OBJECTIVE_TERMINAL_STATE=$state"; return 0;;
      BLOCKED|ESCALATED|CANCELLED) echo "OBJECTIVE_TERMINAL_STATE=$state"; echo "$payload"; return 2;;
    esac
    sleep 2
  done
  echo 'OBJECTIVE_TERMINAL_STATE=TIMEOUT'; echo "$payload"; return 1
}
crash_lane() {
  local project="$1" lane="$2" port="$3" repo="$4" update="$5" cid oid text evidence restarts_before host_pid state restarts
  set -euo pipefail
  echo "${lane}=START repository=$repo"
  if ! cid=$(start_lane "$project" "$lane" "$port"); then echo "${lane}_START=FAILED"; return 1; fi
  text="Take ownership of one Objective: perform a governed single-repository read-only audit of $repo using the available repository audit capability, verify it through Observation, and deliver the resulting evidence. Do not mutate anything and do not perform cross-repository analysis."

  exec 9>"$OUT/execution-planning.lock"; flock 9
  echo "${lane}_PLANNING_ADMISSION=ACQUIRED"
  send_local_update "$port" "$update" "$text" "$OUT/${lane}-ingress.json"
  oid=$(find_objective "$port" "$update"); test -n "$oid"; echo "${lane}_OBJECTIVE_ID=$oid"
  wait_planned "$port" "$oid"
  flock -u 9; echo "${lane}_PLANNING_ADMISSION=RELEASED"

  restarts_before=$(docker inspect "$cid" --format '{{.RestartCount}}'); host_pid=$(docker inspect "$cid" --format '{{.State.Pid}}'); test "$host_pid" -gt 1
  docker run --rm --pid=host --network=none alpine:3.20@sha256:d9e853e87e55526f6b2917df91a2115c36dd7c696a35be12163d44e6e2a4b6bc sh -c "kill -9 $host_pid"
  for _ in $(seq 1 60); do
    state=$(docker inspect "$cid" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{end}}' 2>/dev/null || true); restarts=$(docker inspect "$cid" --format '{{.RestartCount}}' 2>/dev/null || echo 0)
    if [ "$state" = 'running/healthy' ] && [ "$restarts" -gt "$restarts_before" ]; then break; fi; sleep 2
  done
  test "$(docker inspect "$cid" --format '{{.State.Status}}')" = running; test "$(docker inspect "$cid" --format '{{.State.Health.Status}}')" = healthy; test "$(docker inspect "$cid" --format '{{.RestartCount}}')" -gt "$restarts_before"
  wait_terminal "$port" "$oid"
  docker exec "$cid" sh -c "grep -q '$oid' /var/lib/metatron-workforce/management-state.json"
  evidence=$(docker exec "$cid" sh -c "grep -R -l -F 'repository=$repo' /var/lib/metatron-workforce/runtime-evidence 2>/dev/null | tail -1"); test -n "$evidence"
  docker exec "$cid" sh -c "grep -q 'source=gateway-egress/github-api' '$evidence' && grep -q 'verdict=PASS' '$evidence'"
  echo "${lane}_CRASH_RECOVERY=PASS"; echo "${lane}_REAL_TOOL_EVIDENCE=PASS"; echo "${lane}=PASS"
}
live_gateway() { curl -fsS --proto '=https' --tlsv1.2 --max-time 10 https://gate.metatron.vn/telegram/health | grep -q '"status":"UP"'; test "$(docker inspect "$LIVE_CID" --format '{{.State.Health.Status}}')" = healthy; echo 'LIVE_PUBLIC_GATEWAY=PASS'; }
live_observability() {
  local api_file="$OUT/live-observability-api.json"
  curl -fsS --max-time 10 http://127.0.0.1:8080/workforce/monitor > "$OUT/live-monitor.html"; grep -q 'METATRON WORKFORCE' "$OUT/live-monitor.html"; grep -q 'canonical management projection' "$OUT/live-monitor.html"
  curl -fsS --max-time 10 -H 'X-Metatron-Actor: human-primary' http://127.0.0.1:8080/workforce/monitor/api/objectives > "$api_file"
  python3 - "$api_file" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1],encoding='utf-8')); assert isinstance(rows,list)
for row in rows:
    for key in ('objectiveId','humanStatus','ownerWorker','staffingState','workItems','executionProof'): assert key in row,(key,row)
print('LIVE_OBSERVABILITY=PASS')
PY
}

BASE_ID=$(date +%s%N | cut -c1-14)
( trap 'r=$?; echo $r >"'$OUT'/live-gateway.rc"' EXIT; live_gateway >"$OUT/live-gateway.log" 2>&1 ) & P1=$!
( trap 'r=$?; echo $r >"'$OUT'/live-observability.rc"' EXIT; live_observability >"$OUT/live-observability.log" 2>&1 ) & P2=$!
( trap 'r=$?; echo $r >"'$OUT'/lane-a.rc"' EXIT; crash_lane "$PROJECT_A" lane-a "$PORT_A" kelvinka38/bios "${BASE_ID}31" >"$OUT/lane-a.log" 2>&1 ) & P3=$!
( trap 'r=$?; echo $r >"'$OUT'/lane-b.rc"' EXIT; crash_lane "$PROJECT_B" lane-b "$PORT_B" kelvinka38/bios "${BASE_ID}32" >"$OUT/lane-b.log" 2>&1 ) & P4=$!
echo "HIGHWAY_LANE_PIDS=$P1,$P2,$P3,$P4"
rc=0
for pid in "$P1" "$P2" "$P3" "$P4"; do wait "$pid" || rc=1; done
for lane in live-gateway live-observability lane-a lane-b; do echo "===== $lane ====="; cat "$OUT/$lane.log" || true; test -f "$OUT/$lane.rc" || rc=1; [ -f "$OUT/$lane.rc" ] && test "$(cat "$OUT/$lane.rc")" = 0 || rc=1; done
FINAL_LIVE_CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1); test -n "$FINAL_LIVE_CID"; test "$(docker inspect "$FINAL_LIVE_CID" --format '{{.State.Health.Status}}')" = healthy
FINAL_LIVE_SHA=$(docker inspect "$FINAL_LIVE_CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1); echo "HIGHWAY_FINAL_LIVE_SHA=$FINAL_LIVE_SHA"; test "$FINAL_LIVE_SHA" = "$TARGET_SHA"; test "$rc" = 0
echo 'HIGHWAY_LIVE_PARALLEL_LANES=2'; echo 'HIGHWAY_ISOLATED_DESTRUCTIVE_LANES=2'; echo 'HIGHWAY_CONCURRENT_LANES=4'; echo 'HIGHWAY_STATE_ISOLATION=PASS'; echo 'HIGHWAY_DESTRUCTIVE_PARALLELISM=PASS'; echo 'HIGHWAY_PLANNING_CAPACITY_SERIALIZATION=PASS'; echo 'HIGHWAY_STALE_RESOURCE_RECLAMATION=PASS'; echo 'HIGHWAY_DYNAMIC_PORT_ISOLATION=PASS'; echo 'PRODUCTION_ACCEPTANCE_HIGHWAY=PASS'
