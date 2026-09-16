#!/usr/bin/env bash
set -euo pipefail

EXPECTED_SOURCE_SHA="${1:-${GITHUB_SHA:-}}"
[[ "$EXPECTED_SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'MCP_G20_INVALID_SOURCE_SHA' >&2; exit 2; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RUNTIME_PATCH="$SCRIPT_DIR/patch-release-authority-runtime-g20.mjs"
BROKER_PATCH="$SCRIPT_DIR/patch-release-authority-broker-g20.py"
SOURCE_TEST="$SCRIPT_DIR/test-release-authority-g20.py"
BROKER=/usr/local/sbin/metatron-mcp-broker
BASE_IMAGE=metatron-ssh-mcp-runtime:g18
TARGET_IMAGE=metatron-ssh-mcp-runtime:g20
RELEASE_LABEL=g20-release-authority-boundary
TMP="$(mktemp -d)"
BROKER_BACKUP=""
BROKER_INSTALLED=0
RUNTIME_PROMOTED=0

run_root(){
  if [ "$(id -u)" -eq 0 ]; then "$@"; else sudo -n "$@"; fi
}
broker_call(){
  local payload="$1"
  if [ "$(id -u)" -eq 0 ]; then
    printf '%s' "$payload" | env SUDO_USER=metatron-mcp "$BROKER"
  else
    printf '%s' "$payload" | sudo -n env SUDO_USER=metatron-mcp "$BROKER"
  fi
}
router_state(){ docker exec metatron-mcp-router-a cat /state/router.json; }
current_generation(){ router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["generation"])'; }
current_active(){ router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["active"])'; }
current_standby(){ router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["standby"])'; }

cleanup(){
  code=$?
  set +e
  if [ "$code" -ne 0 ] && [ "$BROKER_INSTALLED" -eq 1 ] && [ "$RUNTIME_PROMOTED" -eq 0 ] && [ -n "$BROKER_BACKUP" ] && [ -f "$BROKER_BACKUP" ]; then
    run_root install -o root -g root -m 0755 "$BROKER_BACKUP" "$BROKER" >/dev/null 2>&1 || true
    echo 'MCP_G20_PREPROMOTION_BROKER_ROLLBACK_ATTEMPTED' >&2
  fi
  rm -rf "$TMP"
  trap - EXIT
  exit "$code"
}
trap cleanup EXIT

for required in "$RUNTIME_PATCH" "$BROKER_PATCH" "$SOURCE_TEST"; do
  [ -r "$required" ] || { echo "MCP_G20_SOURCE_MISSING=$required" >&2; exit 3; }
done

ACTUAL_SOURCE_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
[ "$ACTUAL_SOURCE_SHA" = "$EXPECTED_SOURCE_SHA" ] || {
  echo "MCP_G20_SOURCE_SHA_MISMATCH expected=$EXPECTED_SOURCE_SHA actual=$ACTUAL_SOURCE_SHA" >&2; exit 4;
}
GEN="$(current_generation)"
[ "$GEN" -eq 19 ] || { echo "MCP_G20_REQUIRES_GENERATION_19 actual=$GEN" >&2; exit 5; }

echo '=== G20 SOURCE ACCEPTANCE ==='
python3 "$SOURCE_TEST"
echo 'MCP_G20_SOURCE_ACCEPTANCE=PASS'

echo '=== BUILD / VERIFY IMMUTABLE G20 RUNTIME ==='
if docker image inspect "$TARGET_IMAGE" >/dev/null 2>&1; then
  EXISTING_SOURCE_SHA="$(docker image inspect "$TARGET_IMAGE" --format '{{index .Config.Labels "metatron.mcp.source_sha"}}')"
  EXISTING_RELEASE="$(docker image inspect "$TARGET_IMAGE" --format '{{index .Config.Labels "metatron.mcp.release"}}')"
  [ "$EXISTING_SOURCE_SHA" = "$EXPECTED_SOURCE_SHA" ] || { echo 'MCP_G20_EXISTING_IMAGE_SOURCE_MISMATCH' >&2; exit 6; }
  [ "$EXISTING_RELEASE" = "$RELEASE_LABEL" ] || { echo 'MCP_G20_EXISTING_IMAGE_RELEASE_MISMATCH' >&2; exit 6; }
else
  docker image inspect "$BASE_IMAGE" >/dev/null
  CID="$(docker create "$BASE_IMAGE")"
  docker cp "$CID:/app/server.mjs" "$TMP/server.mjs"
  docker rm -f "$CID" >/dev/null
  node "$RUNTIME_PATCH" "$TMP/server.mjs"
  node --check "$TMP/server.mjs"
  grep -q 'METATRON_RELEASE_AUTHORITY_BOUNDARY_G20' "$TMP/server.mjs"
  grep -q 'release_authority_required' "$TMP/server.mjs"
  cat > "$TMP/Dockerfile" <<'DOCKER'
FROM metatron-ssh-mcp-runtime:g18
COPY server.mjs /app/server.mjs
RUN node --check /app/server.mjs \
 && grep -q 'METATRON_RELEASE_AUTHORITY_BOUNDARY_G20' /app/server.mjs \
 && grep -q 'release_authority_required' /app/server.mjs \
 && grep -q 'register("workforce_verify_production"' /app/server.mjs \
 && grep -q 'register("production_identity"' /app/server.mjs
DOCKER
  docker build --pull=false \
    --label "metatron.mcp.source_sha=$EXPECTED_SOURCE_SHA" \
    --label "metatron.mcp.release=$RELEASE_LABEL" \
    -t "$TARGET_IMAGE" "$TMP"
fi
TARGET_IMAGE_ID="$(docker image inspect "$TARGET_IMAGE" --format '{{.Id}}')"
TARGET_SOURCE_SHA="$(docker image inspect "$TARGET_IMAGE" --format '{{index .Config.Labels "metatron.mcp.source_sha"}}')"
TARGET_RELEASE="$(docker image inspect "$TARGET_IMAGE" --format '{{index .Config.Labels "metatron.mcp.release"}}')"
[ "$TARGET_SOURCE_SHA" = "$EXPECTED_SOURCE_SHA" ] || exit 6
[ "$TARGET_RELEASE" = "$RELEASE_LABEL" ] || exit 6
echo "MCP_G20_IMAGE=PASS id=$TARGET_IMAGE_ID source_sha=$TARGET_SOURCE_SHA"

echo '=== PATCH HOST BROKER IDENTITY CONTRACT ==='
run_root cp "$BROKER" "$TMP/broker.current.py"
python3 "$BROKER_PATCH" "$TMP/broker.current.py" "$TMP/broker.g20.py"
python3 -m py_compile "$TMP/broker.g20.py"
grep -q 'METATRON_RELEASE_AUTHORITY_BROKER_G20' "$TMP/broker.g20.py"
BACKUP_DIR=/opt/metatron/ssh-mcp/backups
run_root mkdir -p "$BACKUP_DIR"
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BROKER_BACKUP="$BACKUP_DIR/metatron-mcp-broker.pre-g20.$STAMP"
run_root cp "$BROKER" "$BROKER_BACKUP"
run_root install -o root -g root -m 0755 "$TMP/broker.g20.py" "$BROKER"
BROKER_INSTALLED=1
run_root grep -q 'METATRON_RELEASE_AUTHORITY_BROKER_G20' "$BROKER"
broker_call '{"op":"server_status","args":{}}' | grep -q '"ok":true'
if broker_call '{"op":"workforce_deploy_local_sha","args":{"sha":"invalid"}}' > "$TMP/internal-deploy-probe.json" 2>&1; then
  echo 'MCP_G20_INTERNAL_DEPLOY_INVALID_PROBE_UNEXPECTED_SUCCESS' >&2; exit 7
fi
grep -q 'invalid_sha' "$TMP/internal-deploy-probe.json"
echo 'MCP_G20_INTERNAL_RELEASE_PLANE=PASS'

echo '=== FENCED G20 MCP PROMOTION ==='
broker_call '{"op":"ssh_mcp_self_upgrade","args":{}}' | grep -Eq 'SCHEDULED|scheduled|"ok":true'
for _ in $(seq 1 420); do
  NOW="$(current_generation 2>/dev/null || echo 0)"
  [ "$NOW" -eq 20 ] && break
  sleep 2
done
[ "$(current_generation)" -eq 20 ] || { echo 'MCP_G20_PROMOTION_TIMEOUT' >&2; exit 8; }
RUNTIME_PROMOTED=1
ACTIVE="$(current_active)"
STANDBY="$(current_standby)"
[ "$ACTIVE" = metatron-mcp-runtime-g20 ] || { echo "MCP_G20_ACTIVE_UNEXPECTED=$ACTIVE" >&2; exit 8; }
[ "$STANDBY" = metatron-mcp-runtime-g20-standby ] || { echo "MCP_G20_STANDBY_UNEXPECTED=$STANDBY" >&2; exit 8; }
for name in "$ACTIVE" "$STANDBY"; do
  docker inspect "$name" --format '{{.State.Status}}/{{.State.Health.Status}}' | grep -qx 'running/healthy'
  docker exec "$name" grep -q 'METATRON_RELEASE_AUTHORITY_BOUNDARY_G20' /app/server.mjs
  [ "$(docker inspect "$name" --format '{{index .Config.Labels "metatron.mcp.source_sha"}}')" = "$EXPECTED_SOURCE_SHA" ]
  [ "$(docker inspect "$name" --format '{{index .Config.Labels "metatron.mcp.release"}}')" = "$RELEASE_LABEL" ]
done
curl -fsS --max-time 10 https://ssh.metatron.vn/ready >/dev/null
echo 'MCP_G20_PROMOTION=PASS'

echo '=== REMOTE RELEASE SURFACE NEGATIVE ACCEPTANCE ==='
docker exec "$ACTIVE" node --input-type=module - <<'NODE'
import fs from 'node:fs';
function assertion(){
  for(const ent of fs.readdirSync('/proc')){
    if(!/^\d+$/.test(ent)) continue;
    try{
      const cmd=fs.readFileSync(`/proc/${ent}/cmdline`).toString().replaceAll('\0',' ').trim();
      if(cmd!=="node /app/server.mjs") continue;
      const env=fs.readFileSync(`/proc/${ent}/environ`).toString().split('\0');
      const hit=env.find(x=>x.startsWith('METATRON_PROXY_ASSERTION='));
      if(hit) return hit.slice('METATRON_PROXY_ASSERTION='.length);
    }catch{}
  }
  throw new Error('server_assertion_unavailable');
}
async function rpc(method,params={}){
  const body={jsonrpc:'2.0',id:Math.floor(Math.random()*1e9),method,params};
  const r=await fetch('http://127.0.0.1:3003/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2025-11-25'},body:JSON.stringify(body)});
  const text=await r.text();
  const line=text.split(/\n/).find(v=>v.startsWith('data: '));
  return line?JSON.parse(line.slice(6)):JSON.parse(text);
}
const listed=await rpc('tools/list',{});
const names=(listed.result?.tools||[]).map(x=>x.name);
for(const denied of ['workforce_deploy_local_sha','workforce_rollback','ssh_mcp_self_upgrade']){
  if(names.includes(denied)) throw new Error('remote_release_tool_exposed:'+denied);
}
for(const allowed of ['workforce_verify_production','production_identity']){
  if(!names.includes(allowed)) throw new Error('read_only_evidence_tool_missing:'+allowed);
}
console.log('MCP_G20_REMOTE_RELEASE_LIST_DENY=PASS');
console.log('MCP_G20_READ_ONLY_EVIDENCE_SURFACE=PASS');
const meta={'metatron/authenticatedPrincipal':'founder','metatron/verifiedClient':'g20-release-negative','metatron/proxyAssertion':assertion()};
const denied=await rpc('tools/call',{name:'workforce_deploy_local_sha',arguments:{},_meta:meta});
const result=denied.result||{};
let outer={}; try{outer=JSON.parse(result.content?.[0]?.text||'{}')}catch{}
if(!result.isError||outer.error!=='release_authority_required'||outer.required_plane!=='management_release_control'){
  throw new Error('remote_deploy_handler_not_denied:'+JSON.stringify(denied));
}
console.log('MCP_G20_REMOTE_DEPLOY_CALL_DENY=PASS');
const identity=await rpc('tools/call',{name:'production_identity',arguments:{},_meta:meta});
if(identity.result?.isError) throw new Error('production_identity_regressed');
NODE

echo '=== RUNTIME IDENTITY ==='
BINDING=0123456789abcdef0123456789abcdef
OPEN="$(broker_call "{\"op\":\"commander_open\",\"args\":{\"principal\":\"founder\",\"client_binding\":\"$BINDING\",\"purpose\":\"g20-runtime-identity\",\"scope\":\"read_only\",\"ttl_seconds\":300}}")"
printf '%s' "$OPEN" > "$TMP/open.json"
read -r SESSION FENCE < <(python3 - "$TMP/open.json" <<'PY'
import json,sys
outer=json.load(open(sys.argv[1])); assert outer.get('ok') is True, outer
inner=json.loads(outer['stdout']); print(inner['sessionId'],inner['fencingToken'])
PY
)
AUTH="\"principal\":\"founder\",\"client_binding\":\"$BINDING\",\"session_id\":\"$SESSION\",\"fencing_token\":\"$FENCE\""
IDENTITY="$(broker_call "{\"op\":\"commander_runtime_identity\",\"args\":{$AUTH}}")"
printf '%s' "$IDENTITY" > "$TMP/identity.json"
python3 - "$TMP/identity.json" "$EXPECTED_SOURCE_SHA" <<'PY'
import json,sys
outer=json.load(open(sys.argv[1])); assert outer.get('ok') is True, outer
inner=json.loads(outer['stdout'])
assert inner.get('verified') is True, inner
assert inner.get('generation') == 20, inner
assert inner.get('sourceSha') == sys.argv[2], inner
assert inner.get('release') == 'g20-release-authority-boundary', inner
print('MCP_G20_RUNTIME_IDENTITY=PASS source_sha='+inner['sourceSha'])
PY
broker_call "{\"op\":\"commander_close\",\"args\":{$AUTH}}" >/dev/null

HDR="$TMP/public.headers"
CODE="$(curl -sS -D "$HDR" -o /dev/null -w '%{http_code}' --max-time 8 -H 'content-type: application/json' -H 'accept: application/json, text/event-stream' --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp || true)"
[ "$CODE" = 401 ]
grep -qi '^www-authenticate:.*Bearer' "$HDR"
echo 'MCP_G20_PUBLIC_OAUTH_BOUNDARY=PASS'

echo "MCP_G20_COMPLETE active=$ACTIVE standby=$STANDBY image=$TARGET_IMAGE_ID source_sha=$EXPECTED_SOURCE_SHA release=$RELEASE_LABEL broker_backup=$BROKER_BACKUP"
