#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
BACKUPS=/opt/metatron/ssh-mcp/backups
TARGET=/usr/local/sbin/metatron-mcp-broker
RUNTIME_TAG=metatron-ssh-mcp-runtime:g2
HOTFIX_TAG=metatron-ssh-mcp-runtime:g2-readyfix-v2_8
TMP=$(mktemp -d /tmp/metatron-broker-v2_8.XXXXXX)
PAUSED_STANDBY=""
IMAGE_RETAGGED=0
BROKER_REPLACED=0
backup=""
image_backup=""
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_8_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v2_8-release-patch.py broker-resilience-v2_8-patch.py broker-resilient-release-v2_7.sh auth-readiness-cheap-hotfix.mjs Dockerfile.readiness-hotfix; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V2_8_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V2_8_TARGET_MISSING' >&2; exit 4; }
grep -q 'VERSION = "2.7.0"' "$TARGET" || { echo 'BROKER_V2_8_EXPECTED_V2_7_NOT_FOUND' >&2; exit 5; }
if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then echo 'BROKER_V2_8_RELEASE_LOCK_BUSY' >&2; exit 6; fi
echo 'BROKER_V2_8_RELEASE_LOCK_FREE'

python3 "$BUILD/broker-resilience-v2_8-release-patch.py" "$BUILD/broker-resilient-release-v2_7.sh" "$BUILD/broker-resilient-release-v2_8.sh"
/bin/sh -n "$BUILD/broker-resilient-release-v2_8.sh"
python3 -m py_compile "$BUILD/broker-resilience-v2_8-release-patch.py" "$BUILD/broker-resilience-v2_8-patch.py"
for marker in METATRON_MCP_HOST_SESSION_AFFINITY_COUNT_V1 METATRON_MCP_EDGE_ONLY_BASELINE_V1 METATRON_MCP_SAME_GENERATION_STANDBY_REPLACEMENT_V1; do grep -q "$marker" "$BUILD/broker-resilient-release-v2_8.sh" || { echo "BROKER_V2_8_RELEASE_MARKER_MISSING $marker" >&2; exit 7; }; done
! grep -q 'router_session_count() {' "$BUILD/broker-resilient-release-v2_8.sh"
! grep -q 'max_session_count() {' "$BUILD/broker-resilient-release-v2_8.sh"
echo 'BROKER_V2_8_STATIC_SYNTAX_PASS'

router_version(){ docker exec "$1" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.stdout.write(String(j.version||''))).catch(()=>process.exit(3))" 2>/dev/null || true; }
[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { echo 'BROKER_V2_8_ROUTER_A_V23_REQUIRED' >&2; exit 8; }
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { echo 'BROKER_V2_8_ROUTER_B_V23_REQUIRED' >&2; exit 9; }
echo 'BROKER_V2_8_ROUTER_PAIR_PASS'

edge_live(){ code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 https://ssh.metatron.vn/live 2>/dev/null || true); [ "$code" = 200 ]; }
i=0; edge=0
while [ "$i" -lt 5 ]; do if edge_live; then edge=1; break; fi; i=$((i+1)); sleep 0.5; done
[ "$edge" -eq 1 ] || { echo 'BROKER_V2_8_BASELINE_EDGE_FAILED' >&2; exit 10; }
echo 'BROKER_V2_8_BASELINE_EDGE_PASS'

host_session_count(){
  python3 - "$ROUTER_STATE/sessions" "$1" <<'PY'
import json,os,sys,time
root,backend=sys.argv[1:]; cutoff=int(time.time()*1000)-30*60*1000; n=0
try: entries=os.listdir(root)[:10000]
except Exception: print(999999); raise SystemExit
for e in entries:
    if not e.endswith('.json'): continue
    try:
        x=json.load(open(os.path.join(root,e)))
        if x.get('backend')==backend and int(x.get('updatedAt',0))>=cutoff: n+=1
    except Exception: pass
print(n)
PY
}
old_standby=$(python3 - "$ROUTER_STATE/router.json" <<'PY'
import json,sys
try: print(json.load(open(sys.argv[1])).get('standby',''))
except Exception: print('')
PY
)
if [ -n "$old_standby" ] && docker inspect "$old_standby" >/dev/null 2>&1; then
  sessions=$(host_session_count "$old_standby")
  echo "BROKER_V2_8_PRIOR_STANDBY_SESSIONS backend=$old_standby count=$sessions"
  [ "$sessions" -eq 0 ] || { echo 'BROKER_V2_8_PRIOR_STANDBY_NOT_DRAINED' >&2; exit 11; }
  st=$(docker inspect "$old_standby" --format '{{.State.Status}}' 2>/dev/null || true)
  if [ "$st" = running ]; then docker stop -t 5 "$old_standby" >/dev/null; PAUSED_STANDBY="$old_standby"; fi
fi
edge_live || { [ -z "$PAUSED_STANDBY" ] || docker start "$PAUSED_STANDBY" >/dev/null 2>&1 || true; echo 'BROKER_V2_8_EDGE_FAILED_AFTER_STANDBY_PAUSE' >&2; exit 12; }
echo "BROKER_V2_8_RESOURCE_RELIEF_PASS paused_standby=${PAUSED_STANDBY:-none}"

rollback_all(){
  if [ "$BROKER_REPLACED" -eq 1 ] && [ -n "$backup" ]; then cp "$backup" "$TARGET.rollback"; chown "$owner:$group" "$TARGET.rollback"; chmod "$mode" "$TARGET.rollback"; mv -f "$TARGET.rollback" "$TARGET"; fi
  if [ "$IMAGE_RETAGGED" -eq 1 ] && [ -n "$image_backup" ]; then docker tag "$image_backup" "$RUNTIME_TAG" >/dev/null 2>&1 || true; fi
  if [ -n "$PAUSED_STANDBY" ]; then docker start "$PAUSED_STANDBY" >/dev/null 2>&1 || true; fi
}

# Build a minimal layer over the accepted g2 image. No npm install or full MCP rebuild occurs.
docker image inspect "$RUNTIME_TAG" >/dev/null 2>&1 || { rollback_all; echo 'BROKER_V2_8_RUNTIME_G2_IMAGE_MISSING' >&2; exit 13; }
base_image_id=$(docker image inspect "$RUNTIME_TAG" --format '{{.Id}}')
docker build --pull=false -f "$BUILD/Dockerfile.readiness-hotfix" -t "$HOTFIX_TAG" "$BUILD" >/dev/null || { rollback_all; echo 'BROKER_V2_8_HOTFIX_BUILD_FAILED' >&2; exit 14; }
hotfix_image_id=$(docker image inspect "$HOTFIX_TAG" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$hotfix_image_id" ] || { rollback_all; echo 'BROKER_V2_8_HOTFIX_IMAGE_ID_MISSING' >&2; exit 15; }
[ "$hotfix_image_id" != "$base_image_id" ] || { rollback_all; echo 'BROKER_V2_8_HOTFIX_IMAGE_NOT_DISTINCT' >&2; exit 16; }
docker run --rm --entrypoint sh "$HOTFIX_TAG" -c "node --check /app/auth-proxy.mjs && grep -q METATRON_MCP_CHEAP_READINESS_V2 /app/auth-proxy.mjs && grep -q METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1 /app/auth-proxy.mjs && ! grep -q \"body.includes('server_status') && body.includes('repository_open')\" /app/auth-proxy.mjs" >/dev/null || { rollback_all; echo 'BROKER_V2_8_HOTFIX_STATIC_FAILED' >&2; exit 17; }
echo "BROKER_V2_8_CHEAP_READINESS_IMAGE_PASS base=$base_image_id hotfix=$hotfix_image_id"

cp "$BUILD/broker-resilience-v2_8-patch.py" "$TMP/"; cp "$BUILD/broker-resilient-release-v2_8.sh" "$TMP/"
python3 "$TMP/broker-resilience-v2_8-patch.py" "$TARGET" "$TMP/broker-v2_8.py"
python3 -m py_compile "$TMP/broker-v2_8.py"
for marker in 'VERSION = "2.8.0"' METATRON_MCP_RESILIENT_RELEASE_V2_8 METATRON_MCP_HOST_SESSION_AFFINITY_COUNT_V1 METATRON_MCP_EDGE_ONLY_BASELINE_V1 METATRON_MCP_SAME_GENERATION_STANDBY_REPLACEMENT_V1 ZERO_DOWNTIME_CUTOVER_PASS RUNTIME_FAILOVER_ACCEPTANCE_PASS TUNNEL_FAILOVER_ACCEPTANCE_PASS; do grep -q "$marker" "$TMP/broker-v2_8.py" || { rollback_all; echo "BROKER_V2_8_ARTIFACT_MISSING $marker" >&2; exit 18; }; done
echo 'BROKER_V2_8_ARTIFACT_PASS'

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"; stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v2.7.$stamp"; image_backup="metatron-ssh-mcp-runtime:g2-pre-readyfix-$stamp"
cp -a "$TARGET" "$backup"; chmod 600 "$backup"; docker tag "$RUNTIME_TAG" "$image_backup"
owner=$(stat -c '%u' "$TARGET"); group=$(stat -c '%g' "$TARGET"); mode=$(stat -c '%a' "$TARGET")
docker tag "$HOTFIX_TAG" "$RUNTIME_TAG"; IMAGE_RETAGGED=1
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v2_8.py" "$TARGET.new"; mv -f "$TARGET.new" "$TARGET"; BROKER_REPLACED=1

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"2.8.0"' || { rollback_all; echo 'BROKER_V2_8_DIRECT_SMOKE_FAILED' >&2; exit 19; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"2.8.0"' || { rollback_all; echo 'BROKER_V2_8_RESTRICTED_SMOKE_FAILED' >&2; exit 20; }
echo 'BROKER_V2_8_BROKER_SMOKE_PASS'
edge_live || { rollback_all; echo 'BROKER_V2_8_POST_INSTALL_EDGE_FAILED' >&2; exit 21; }
echo "BROKER_V2_8_INSTALL_PASS target=$TARGET backup=$backup image_backup=$image_backup release_not_started=true paused_legacy_standby=${PAUSED_STANDBY:-none} readiness=cheap-cached-host-plus-tcp"
