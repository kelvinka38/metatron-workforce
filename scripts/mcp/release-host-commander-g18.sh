#!/usr/bin/env bash
set -euo pipefail

EXPECTED_WORKFORCE_SHA="${1:-${GITHUB_SHA:-}}"
[[ "$EXPECTED_WORKFORCE_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'MCP_G18_INVALID_WORKFORCE_SHA' >&2; exit 2; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_PATCH="$SCRIPT_DIR/patch-host-commander-runtime-g18.mjs"
BROKER_PATCH="$SCRIPT_DIR/patch-host-commander-broker-g18.py"
SUPERVISOR_SOURCE="$SCRIPT_DIR/commander-supervisor-g18.py"
SOURCE_TEST="$SCRIPT_DIR/test-host-commander-g18.py"
BROKER=/usr/local/sbin/metatron-mcp-broker
SUPERVISOR_TARGET=/usr/local/lib/metatron-commander-supervisor.py
BASE_IMAGE=metatron-ssh-mcp-runtime:g17
TARGET_IMAGE=metatron-ssh-mcp-runtime:g18
TMP="$(mktemp -d)"
FIXTURE="metatron-commander-acceptance-$(date +%s)-$$"
BROKER_BACKUP=""
SUPERVISOR_BACKUP=""
BROKER_INSTALLED=0
RUNTIME_PROMOTED=0

run_root(){
  if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo -n "$@"; fi
}

router_state(){ docker exec metatron-mcp-router-a cat /state/router.json; }
current_generation(){ router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["generation"])'; }
current_active(){ router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["active"])'; }
current_standby(){ router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["standby"])'; }

cleanup(){
  code=$?
  set +e
  docker rm -f "$FIXTURE" >/dev/null 2>&1 || true
  rm -f /tmp/metatron-commander/g18-acceptance.txt >/dev/null 2>&1 || true
  if [ "$code" -ne 0 ] && [ "$BROKER_INSTALLED" -eq 1 ] && [ "$RUNTIME_PROMOTED" -eq 0 ] && [ -n "$BROKER_BACKUP" ] && [ -f "$BROKER_BACKUP" ]; then
    run_root install -o root -g root -m 0755 "$BROKER_BACKUP" "$BROKER" >/dev/null 2>&1 || true
    if [ -n "$SUPERVISOR_BACKUP" ] && [ -f "$SUPERVISOR_BACKUP" ]; then
      run_root install -o root -g root -m 0755 "$SUPERVISOR_BACKUP" "$SUPERVISOR_TARGET" >/dev/null 2>&1 || true
    else
      run_root rm -f "$SUPERVISOR_TARGET" >/dev/null 2>&1 || true
    fi
    echo 'MCP_G18_PREPROMOTION_BROKER_ROLLBACK_ATTEMPTED' >&2
  fi
  rm -rf "$TMP"
  trap - EXIT
  exit "$code"
}
trap cleanup EXIT

if [ "$(id -u)" -ne 0 ]; then sudo -n true >/dev/null 2>&1 || { echo 'MCP_G18_ROOT_AUTHORITY_UNAVAILABLE' >&2; exit 3; }; fi

for required in "$RUNTIME_PATCH" "$BROKER_PATCH" "$SUPERVISOR_SOURCE" "$SOURCE_TEST"; do
  [ -r "$required" ] || { echo "MCP_G18_SOURCE_MISSING=$required" >&2; exit 4; }
done

GEN="$(current_generation)"
if [ "$GEN" -gt 18 ]; then
  echo "MCP_G18_REFUSE_NEWER_GENERATION generation=$GEN" >&2
  exit 5
fi
if [ "$GEN" -lt 17 ]; then
  echo "MCP_G18_UNEXPECTED_BASE_GENERATION generation=$GEN" >&2
  exit 5
fi

echo '=== SOURCE ACCEPTANCE ==='
python3 "$SOURCE_TEST"
echo 'MCP_G18_SOURCE_ACCEPTANCE=PASS'

echo '=== WAIT FOR WORKFORCE EXACT SHA ==='
for _ in $(seq 1 600); do
  RUNNING_SHA="$(docker inspect deploy-workforce-1 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1 || true)"
  [ "$RUNNING_SHA" = "$EXPECTED_WORKFORCE_SHA" ] && break
  sleep 2
done
RUNNING_SHA="$(docker inspect deploy-workforce-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)"
[ "$RUNNING_SHA" = "$EXPECTED_WORKFORCE_SHA" ] || { echo "MCP_G18_WORKFORCE_SHA_NOT_READY expected=$EXPECTED_WORKFORCE_SHA actual=$RUNNING_SHA" >&2; exit 6; }
echo "MCP_G18_WORKFORCE_SHA_READY=$RUNNING_SHA"

echo '=== BUILD / VERIFY IMMUTABLE G18 RUNTIME ==='
if docker image inspect "$TARGET_IMAGE" >/dev/null 2>&1; then
  CID="$(docker create "$TARGET_IMAGE")"
  docker cp "$CID:/app/server.mjs" "$TMP/existing-server.mjs"
  docker rm -f "$CID" >/dev/null
  grep -q 'METATRON_HOST_COMMANDER_RUNTIME_G18' "$TMP/existing-server.mjs" || { echo 'MCP_G18_EXISTING_IMAGE_MARKER_MISSING' >&2; exit 7; }
  echo 'MCP_G18_IMAGE_ALREADY_PRESENT=PASS'
else
  docker image inspect "$BASE_IMAGE" >/dev/null
  CID="$(docker create "$BASE_IMAGE")"
  docker cp "$CID:/app/server.mjs" "$TMP/server.mjs"
  docker rm -f "$CID" >/dev/null
  node "$RUNTIME_PATCH" "$TMP/server.mjs"
  node --check "$TMP/server.mjs"
  grep -q 'METATRON_HOST_COMMANDER_RUNTIME_G18' "$TMP/server.mjs"
  grep -q 'register("commander_open"' "$TMP/server.mjs"
  grep -q 'register("commander_exec"' "$TMP/server.mjs"
  grep -q 'register("commander_process_start"' "$TMP/server.mjs"
  ! grep -q 'register("commander_shell"' "$TMP/server.mjs"
  cat > "$TMP/Dockerfile" <<'DOCKER'
FROM metatron-ssh-mcp-runtime:g17
COPY server.mjs /app/server.mjs
RUN node --check /app/server.mjs \
 && grep -q 'METATRON_HOST_COMMANDER_RUNTIME_G18' /app/server.mjs \
 && grep -q 'register("commander_open"' /app/server.mjs \
 && grep -q 'register("commander_exec"' /app/server.mjs \
 && grep -q 'register("commander_storage_cleanup"' /app/server.mjs \
 && ! grep -q 'register("commander_shell"' /app/server.mjs
DOCKER
  docker build --pull=false -t "$TARGET_IMAGE" "$TMP"
  echo 'MCP_G18_IMAGE_BUILD=PASS'
fi
TARGET_IMAGE_ID="$(docker image inspect "$TARGET_IMAGE" --format '{{.Id}}')"
[ -n "$TARGET_IMAGE_ID" ] || { echo 'MCP_G18_IMAGE_ID_MISSING' >&2; exit 7; }
echo "MCP_G18_IMAGE_ID=$TARGET_IMAGE_ID"

echo '=== PREPARE ATOMIC BROKER + SUPERVISOR INSTALL ==='
run_root cp "$BROKER" "$TMP/broker.current.py"
python3 "$BROKER_PATCH" "$TMP/broker.current.py" "$TMP/broker.g18.py"
python3 -m py_compile "$TMP/broker.g18.py" "$SUPERVISOR_SOURCE"
grep -q 'METATRON_HOST_COMMANDER_BROKER_G18' "$TMP/broker.g18.py"
grep -q 'def op_commander_open(a):' "$TMP/broker.g18.py"
grep -q 'def op_commander_storage_cleanup(a):' "$TMP/broker.g18.py"

BACKUP_DIR=/opt/metatron/ssh-mcp/backups
run_root mkdir -p "$BACKUP_DIR" /usr/local/lib
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BROKER_BACKUP="$BACKUP_DIR/metatron-mcp-broker.pre-g18.$STAMP"
run_root cp "$BROKER" "$BROKER_BACKUP"
if run_root test -f "$SUPERVISOR_TARGET"; then
  SUPERVISOR_BACKUP="$BACKUP_DIR/metatron-commander-supervisor.pre-g18.$STAMP.py"
  run_root cp "$SUPERVISOR_TARGET" "$SUPERVISOR_BACKUP"
fi
run_root install -o root -g root -m 0755 "$TMP/broker.g18.py" "$BROKER"
run_root install -o root -g root -m 0755 "$SUPERVISOR_SOURCE" "$SUPERVISOR_TARGET"
BROKER_INSTALLED=1
run_root grep -q 'METATRON_HOST_COMMANDER_BROKER_G18' "$BROKER"
run_root grep -q 'commander-supervisor-g18.py' "$SUPERVISOR_TARGET"
echo "MCP_G18_BROKER_INSTALL=PASS backup=$BROKER_BACKUP"

broker_call(){
  payload="$1"
  if [ "$(id -u)" -eq 0 ]; then
    printf '%s' "$payload" | env SUDO_USER=metatron-mcp "$BROKER"
  else
    printf '%s' "$payload" | sudo -n env SUDO_USER=metatron-mcp "$BROKER"
  fi
}

server_probe="$(broker_call '{"op":"server_status","args":{}}')"
printf '%s' "$server_probe" | grep -q '"ok": true\|"ok":true' || { echo 'MCP_G18_BROKER_BASELINE_PROBE_FAILED' >&2; exit 8; }

echo '=== BROKER COMMANDER ACCEPTANCE ==='
BINDING=0123456789abcdef0123456789abcdef
OPEN="$(broker_call "{\"op\":\"commander_open\",\"args\":{\"principal\":\"founder\",\"client_binding\":\"$BINDING\",\"purpose\":\"g18-production-acceptance\",\"scope\":\"maintenance\",\"ttl_seconds\":900}}")"
printf '%s' "$OPEN" > "$TMP/open.json"
read -r SESSION FENCE < <(python3 - "$TMP/open.json" <<'PY'
import json,sys
outer=json.load(open(sys.argv[1])); assert outer.get('ok') is True, outer
inner=json.loads(outer['stdout']); assert inner.get('verified') is True, inner
print(inner['sessionId'], inner['fencingToken'])
PY
)
[ -n "$SESSION" ] && [ -n "$FENCE" ] || { echo 'MCP_G18_COMMANDER_SESSION_FAILED' >&2; exit 9; }
echo "MCP_G18_COMMANDER_SESSION=PASS session=$SESSION"

if broker_call "{\"op\":\"commander_open\",\"args\":{\"principal\":\"not-founder\",\"client_binding\":\"$BINDING\",\"purpose\":\"must-deny\"}}" > "$TMP/deny-founder.json" 2>&1; then
  echo 'MCP_G18_FOUNDER_GUARD_FAILED' >&2; exit 9
fi
grep -q 'commander_founder_required' "$TMP/deny-founder.json"

auth="\"principal\":\"founder\",\"client_binding\":\"$BINDING\",\"session_id\":\"$SESSION\",\"fencing_token\":\"$FENCE\""
EXEC="$(broker_call "{\"op\":\"commander_exec\",\"args\":{$auth,\"executable\":\"uptime\",\"args\":[]}}")"
printf '%s' "$EXEC" | grep -q 'uptime\|durationMillis' || { echo 'MCP_G18_EXEC_ACCEPTANCE_FAILED' >&2; exit 10; }

if broker_call "{\"op\":\"commander_file_read\",\"args\":{$auth,\"path\":\"/etc/shadow\"}}" > "$TMP/deny-secret.json" 2>&1; then
  echo 'MCP_G18_SECRET_GUARD_FAILED' >&2; exit 10
fi
grep -q 'commander_secret_path_denied' "$TMP/deny-secret.json"

if broker_call "{\"op\":\"commander_file_write\",\"args\":{$auth,\"path\":\"/opt/metatron/metatron-workforce/COMMANDER-MUST-NOT-WRITE\",\"content\":\"deny\"}}" > "$TMP/deny-source.json" 2>&1; then
  echo 'MCP_G18_SOURCE_GUARD_FAILED' >&2; exit 10
fi
grep -q 'commander_write_scope_denied\|commander_canonical_source_mutation_denied' "$TMP/deny-source.json"

WRITE="$(broker_call "{\"op\":\"commander_file_write\",\"args\":{$auth,\"path\":\"/tmp/metatron-commander/g18-acceptance.txt\",\"content\":\"alpha\"}}")"
printf '%s' "$WRITE" | grep -q '"ok": true\|"ok":true'
PATCH="$(broker_call "{\"op\":\"commander_file_patch\",\"args\":{$auth,\"path\":\"/tmp/metatron-commander/g18-acceptance.txt\",\"old_text\":\"alpha\",\"new_text\":\"beta\",\"expected_occurrences\":1}}")"
printf '%s' "$PATCH" | grep -q '"ok": true\|"ok":true'
READ="$(broker_call "{\"op\":\"commander_file_read\",\"args\":{$auth,\"path\":\"/tmp/metatron-commander/g18-acceptance.txt\"}}")"
printf '%s' "$READ" | grep -q 'beta'
echo 'MCP_G18_FILE_PLANE_ACCEPTANCE=PASS'

PSTART="$(broker_call "{\"op\":\"commander_process_start\",\"args\":{$auth,\"executable\":\"cat\",\"args\":[],\"working_directory\":\"/tmp\",\"timeout_seconds\":30}}")"
printf '%s' "$PSTART" > "$TMP/pstart.json"
PROCESS="$(python3 - "$TMP/pstart.json" <<'PY'
import json,sys
outer=json.load(open(sys.argv[1])); assert outer.get('ok') is True, outer
print(json.loads(outer['stdout'])['processId'])
PY
)"
broker_call "{\"op\":\"commander_process_input\",\"args\":{$auth,\"process_id\":\"$PROCESS\",\"data\":\"hello-g18\\n\"}}" >/dev/null
PROCESS_OK=0
for _ in $(seq 1 80); do
  POUT="$(broker_call "{\"op\":\"commander_process_output\",\"args\":{$auth,\"process_id\":\"$PROCESS\",\"offset\":0,\"max_bytes\":65536}}")"
  if printf '%s' "$POUT" | grep -q 'hello-g18'; then PROCESS_OK=1; break; fi
  sleep 0.1
done
[ "$PROCESS_OK" -eq 1 ] || { echo 'MCP_G18_INTERACTIVE_OUTPUT_FAILED' >&2; exit 11; }
broker_call "{\"op\":\"commander_process_terminate\",\"args\":{$auth,\"process_id\":\"$PROCESS\"}}" >/dev/null
echo 'MCP_G18_INTERACTIVE_PROCESS_ACCEPTANCE=PASS'

docker run -d --name "$FIXTURE" --entrypoint node "$TARGET_IMAGE" -e 'setInterval(()=>{},1000)' >/dev/null
DINSPECT="$(broker_call "{\"op\":\"commander_docker_inspect\",\"args\":{$auth,\"container\":\"$FIXTURE\"}}")"
printf '%s' "$DINSPECT" | grep -q "$FIXTURE"
broker_call "{\"op\":\"commander_docker_action\",\"args\":{$auth,\"container\":\"$FIXTURE\",\"action\":\"restart\"}}" >/dev/null
docker inspect "$FIXTURE" --format '{{.State.Status}}' | grep -qx running
broker_call "{\"op\":\"commander_docker_action\",\"args\":{$auth,\"container\":\"$FIXTURE\",\"action\":\"remove\"}}" >/dev/null
if docker inspect "$FIXTURE" >/dev/null 2>&1; then echo 'MCP_G18_DOCKER_REMOVE_VERIFY_FAILED' >&2; exit 12; fi
echo 'MCP_G18_DOCKER_ACCEPTANCE=PASS'

broker_call "{\"op\":\"commander_network_inspect\",\"args\":{$auth}}" >/dev/null
broker_call "{\"op\":\"commander_storage_inspect\",\"args\":{$auth}}" >/dev/null
broker_call "{\"op\":\"commander_close\",\"args\":{$auth}}" >/dev/null
echo 'MCP_G18_BROKER_COMMANDER_ACCEPTANCE=PASS'

if [ "$GEN" -eq 17 ]; then
  echo '=== FENCED G18 MCP PROMOTION ==='
  broker_call '{"op":"ssh_mcp_self_upgrade","args":{}}' | grep -q 'SCHEDULED\|scheduled\|ok'
  for _ in $(seq 1 360); do
    NOW="$(current_generation 2>/dev/null || echo 0)"
    [ "$NOW" -eq 18 ] && break
    sleep 2
  done
fi

[ "$(current_generation)" -eq 18 ] || { echo 'MCP_G18_PROMOTION_TIMEOUT' >&2; exit 13; }
RUNTIME_PROMOTED=1
ACTIVE="$(current_active)"
STANDBY="$(current_standby)"
[ "$ACTIVE" = metatron-mcp-runtime-g18 ] || { echo "MCP_G18_ACTIVE_UNEXPECTED=$ACTIVE" >&2; exit 13; }
[ "$STANDBY" = metatron-mcp-runtime-g18-standby ] || { echo "MCP_G18_STANDBY_UNEXPECTED=$STANDBY" >&2; exit 13; }
for name in "$ACTIVE" "$STANDBY"; do
  docker inspect "$name" --format '{{.State.Status}}/{{.State.Health.Status}}' | grep -qx 'running/healthy'
  docker exec "$name" grep -q 'METATRON_HOST_COMMANDER_RUNTIME_G18' /app/server.mjs
done
curl -fsS --max-time 10 https://ssh.metatron.vn/ready >/dev/null
echo 'MCP_G18_PROMOTION=PASS'

echo '=== LIVE TOOL REGISTRY ACCEPTANCE ==='
docker exec "$ACTIVE" node --input-type=module -e '
const body={jsonrpc:"2.0",id:1,method:"tools/list",params:{}};
const r=await fetch("http://127.0.0.1:3003/mcp",{method:"POST",headers:{"content-type":"application/json","accept":"application/json, text/event-stream","mcp-protocol-version":"2025-11-25"},body:JSON.stringify(body)});
const text=await r.text(); if(!r.ok) throw new Error(`HTTP ${r.status}`);
const line=text.split(/\n/).find(v=>v.startsWith("data: ")); if(!line) throw new Error("missing MCP SSE data");
const payload=JSON.parse(line.slice(6)); const names=(payload.result?.tools||[]).map(x=>x.name);
for(const n of ["commander_open","commander_status","commander_file_read","commander_exec","commander_process_start","commander_docker_inspect","commander_storage_cleanup","commander_network_inspect"]) if(!names.includes(n)) throw new Error(`missing ${n}`);
if(names.includes("commander_shell")) throw new Error("raw commander shell must not exist");
console.log(`MCP_G18_TOOLS_LIST=PASS tools=${names.length}`);'

run_root grep -q 'METATRON_HOST_COMMANDER_BROKER_G18' "$BROKER"
run_root test -x "$SUPERVISOR_TARGET"
echo "MCP_G18_COMPLETE active=$ACTIVE standby=$STANDBY image=$TARGET_IMAGE_ID workforce_sha=$EXPECTED_WORKFORCE_SHA broker_backup=$BROKER_BACKUP"
