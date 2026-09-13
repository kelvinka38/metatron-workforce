#!/usr/bin/env bash
set -euo pipefail

EXPECTED_WORKFORCE_SHA="${1:-${GITHUB_SHA:-}}"
[[ "$EXPECTED_WORKFORCE_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo 'MCP_G17_INVALID_WORKFORCE_SHA' >&2; exit 2; }
REPOSITORY_ACCEPTANCE="${MCP_DYNAMIC_REPOSITORY_ACCEPTANCE:-kelvinka38/archive}"
[[ "$REPOSITORY_ACCEPTANCE" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo 'MCP_G17_INVALID_ACCEPTANCE_REPOSITORY' >&2; exit 2; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCH="$SCRIPT_DIR/patch-dynamic-repository-g17.mjs"
BROKER=/usr/local/sbin/metatron-mcp-broker
BASE_IMAGE=metatron-ssh-mcp-runtime:g16
TARGET_IMAGE=metatron-ssh-mcp-runtime:g17
TMP="$(mktemp -d)"
CID=''
cleanup(){ [ -z "$CID" ] || docker rm -f "$CID" >/dev/null 2>&1 || true; rm -rf "$TMP"; }
trap cleanup EXIT

router_state(){ docker exec metatron-mcp-router-a cat /state/router.json; }
current_generation(){ router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["generation"])'; }
current_active(){ router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["active"])'; }

GEN="$(current_generation)"
ALREADY_ACTIVE=0
if [ "$GEN" -gt 17 ]; then
  echo "MCP_G17_REFUSE_NEWER_GENERATION generation=$GEN" >&2
  exit 3
fi
if [ "$GEN" -eq 17 ]; then
  ACTIVE="$(current_active)"
  docker exec "$ACTIVE" grep -q 'METATRON_DYNAMIC_REPOSITORY_SCOPE_G17' /app/server.mjs
  ALREADY_ACTIVE=1
  echo "MCP_G17_ALREADY_ACTIVE active=$ACTIVE; continuing acceptance"
elif [ "$GEN" -ne 16 ]; then
  echo "MCP_G17_UNEXPECTED_BASE_GENERATION generation=$GEN" >&2
  exit 3
fi

echo '=== WAIT FOR WORKFORCE EXACT SHA ==='
for _ in $(seq 1 600); do
  RUNNING_SHA="$(docker inspect deploy-workforce-1 --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1 || true)"
  [ "$RUNNING_SHA" = "$EXPECTED_WORKFORCE_SHA" ] && break
  sleep 2
done
RUNNING_SHA="$(docker inspect deploy-workforce-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)"
[ "$RUNNING_SHA" = "$EXPECTED_WORKFORCE_SHA" ] || { echo "MCP_G17_WORKFORCE_SHA_NOT_READY expected=$EXPECTED_WORKFORCE_SHA actual=$RUNNING_SHA" >&2; exit 4; }
echo "MCP_G17_WORKFORCE_SHA_READY=$RUNNING_SHA"

if [ "$ALREADY_ACTIVE" -eq 0 ]; then
  echo '=== BUILD IMMUTABLE G17 CANDIDATE FROM G16 ==='
  docker image inspect "$BASE_IMAGE" >/dev/null
  CID="$(docker create "$BASE_IMAGE")"
  docker cp "$CID:/app/server.mjs" "$TMP/server.mjs"
  docker rm -f "$CID" >/dev/null
  CID=''
  node "$PATCH" "$TMP/server.mjs"
  node --check "$TMP/server.mjs"
  cat > "$TMP/Dockerfile" <<'DOCKER'
FROM metatron-ssh-mcp-runtime:g16
COPY server.mjs /app/server.mjs
RUN node --check /app/server.mjs \
 && grep -q 'METATRON_DYNAMIC_REPOSITORY_SCOPE_G17' /app/server.mjs \
 && grep -q 'staticAllowlist:false' /app/server.mjs \
 && grep -q 'server-credential-authorized' /app/server.mjs \
 && ! grep -q 'CANONICAL_REPOSITORIES' /app/server.mjs \
 && ! grep -q 'repository_outside_canonical_scope' /app/server.mjs
DOCKER
  docker build --pull=false -t "$TARGET_IMAGE" "$TMP"
  docker image inspect "$TARGET_IMAGE" >/dev/null
  CID="$(docker create "$TARGET_IMAGE")"
  docker cp "$CID:/app/server.mjs" "$TMP/verify-server.mjs"
  docker rm -f "$CID" >/dev/null
  CID=''
  grep -q 'METATRON_DYNAMIC_REPOSITORY_SCOPE_G17' "$TMP/verify-server.mjs"
  ! grep -q 'CANONICAL_REPOSITORIES' "$TMP/verify-server.mjs"
  echo 'MCP_G17_IMAGE_ACCEPTANCE=PASS'

  echo '=== FENCED MCP RELEASE THROUGH CANONICAL BROKER ==='
  [ -x "$BROKER" ] || { echo 'MCP_G17_BROKER_MISSING' >&2; exit 5; }
  if [ "$(id -u)" -eq 0 ]; then
    printf '%s' '{"op":"ssh_mcp_self_upgrade","args":{}}' | env SUDO_USER=metatron-mcp "$BROKER"
  elif sudo -n true >/dev/null 2>&1; then
    printf '%s' '{"op":"ssh_mcp_self_upgrade","args":{}}' | sudo -n env SUDO_USER=metatron-mcp "$BROKER"
  else
    echo 'MCP_G17_ROOT_BROKER_AUTHORITY_UNAVAILABLE' >&2
    exit 6
  fi

  for _ in $(seq 1 240); do
    GEN="$(current_generation 2>/dev/null || echo 0)"
    [ "$GEN" -eq 17 ] && break
    sleep 2
  done
fi

[ "$(current_generation)" -eq 17 ] || { echo 'MCP_G17_PROMOTION_TIMEOUT' >&2; exit 7; }
ACTIVE="$(current_active)"
STANDBY="$(router_state | python3 -c 'import json,sys; print(json.load(sys.stdin)["standby"])')"
[ "$ACTIVE" = metatron-mcp-runtime-g17 ] || { echo "MCP_G17_ACTIVE_UNEXPECTED=$ACTIVE" >&2; exit 7; }
[ "$STANDBY" = metatron-mcp-runtime-g17-standby ] || { echo "MCP_G17_STANDBY_UNEXPECTED=$STANDBY" >&2; exit 7; }
for name in "$ACTIVE" "$STANDBY"; do
  docker inspect "$name" --format '{{.State.Status}}/{{.State.Health.Status}}' | grep -qx 'running/healthy'
  docker exec "$name" grep -q 'METATRON_DYNAMIC_REPOSITORY_SCOPE_G17' /app/server.mjs
done
curl -fsS --max-time 10 https://ssh.metatron.vn/ready >/dev/null
echo 'MCP_G17_PROMOTION=PASS'

echo '=== LIVE MCP REGISTRY ACCEPTANCE ==='
docker exec "$ACTIVE" node --input-type=module -e '
const body={jsonrpc:"2.0",id:1,method:"tools/list",params:{}};
const r=await fetch("http://127.0.0.1:3003/mcp",{method:"POST",headers:{"content-type":"application/json","accept":"application/json, text/event-stream","mcp-protocol-version":"2025-11-25"},body:JSON.stringify(body)});
const text=await r.text(); if(!r.ok) throw new Error(`HTTP ${r.status}`);
const line=text.split(/\n/).find(v=>v.startsWith("data: ")); if(!line) throw new Error("missing MCP SSE data");
const payload=JSON.parse(line.slice(6)); const names=(payload.result?.tools||[]).map(x=>x.name);
for(const n of ["repository_access","repository_open","repository_read"]) if(!names.includes(n)) throw new Error(`missing ${n}`);
console.log(`MCP_G17_TOOLS_LIST=PASS tools=${names.length}`);'

echo '=== NON-CANONICAL PRIVATE REPOSITORY LIVE ACCEPTANCE ==='
CLIENT=mcp-release-acceptance
BINDING="$(python3 - <<'PY'
import hashlib
print(hashlib.sha256(b'mcp-release-acceptance').hexdigest()[:32])
PY
)"
UUID="$(python3 - <<'PY'
import uuid
print(uuid.uuid4())
PY
)"
OBJECTIVE="direct-mcp:${BINDING}:${UUID}"
post_action(){
  local action="$1" inputs="$2"
  python3 - "$OBJECTIVE" "$REPOSITORY_ACCEPTANCE" "$action" "$inputs" <<'PY' | curl -fsS --max-time 330 -H 'content-type: application/json' -H 'X-Metatron-Direct-Client: mcp-release-acceptance' --data-binary @- http://127.0.0.1:8080/internal/metatron/direct-coding/action
import json,sys,uuid
objective,repo,action,inputs=sys.argv[1:]
print(json.dumps({"objectiveId":objective,"repository":repo,"actionRef":action,"idempotencyKey":"mcp-g17-acceptance:"+str(uuid.uuid4()),"inputs":json.loads(inputs)}))
PY
}
OPEN="$(post_action workspace.repository.materialize "{\"repository\":\"$REPOSITORY_ACCEPTANCE\",\"ref\":\"main\"}")"
printf '%s' "$OPEN" > "$TMP/open.json"
python3 - "$TMP/open.json" "$REPOSITORY_ACCEPTANCE" <<'PY'
import json,sys
x=json.load(open(sys.argv[1])); repo=sys.argv[2]
assert x.get('ok') is True, x
assert x.get('repository') == repo, x
sha=x.get('outputs',{}).get('sourceCommitSha','')
assert len(sha)==40, x
print('MCP_G17_DYNAMIC_MATERIALIZE=PASS repository='+repo+' sha='+sha)
PY
READ="$(post_action workspace.file.read '{"path":".gitignore"}')"
printf '%s' "$READ" > "$TMP/read.json"
python3 - "$TMP/read.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1])); assert x.get('ok') is True, x
out=x.get('outputs',{}); assert out.get('path')=='.gitignore', x
assert isinstance(out.get('content'),str), x
print('MCP_G17_DYNAMIC_READ=PASS path=.gitignore bytes='+str(len(out.get('content','').encode())))
PY

echo "MCP_G17_COMPLETE active=$ACTIVE standby=$STANDBY repository=$REPOSITORY_ACCEPTANCE workforce_sha=$EXPECTED_WORKFORCE_SHA"
