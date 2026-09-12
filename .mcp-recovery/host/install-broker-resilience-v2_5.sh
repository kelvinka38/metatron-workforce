#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
ROUTER_STATE=/var/lib/metatron-mcp/router
TARGET=/usr/local/sbin/metatron-mcp-broker
TMP=$(mktemp -d /tmp/metatron-broker-v2_5.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_5_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v2_5-patch.py broker-resilient-release-v2_5.sh mcp-router.mjs Dockerfile.router; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V2_5_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V2_5_TARGET_MISSING' >&2; exit 4; }
/bin/sh -n "$BUILD/broker-resilient-release-v2_5.sh"
python3 -m py_compile "$BUILD/broker-resilience-v2_5-patch.py"
grep -q 'VERSION = "2.4.0"' "$TARGET" || { echo 'BROKER_V2_5_EXPECTED_V2_4_NOT_FOUND' >&2; exit 5; }
echo 'BROKER_V2_5_STATIC_SYNTAX_PASS'

public_transport_ok(){
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 6 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-router-migration/2.5' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
wait_public(){ i=0; while [ "$i" -lt 60 ]; do public_transport_ok && return 0; i=$((i+1)); sleep 1; done; return 1; }
wait_health(){
  c="$1"; limit="${2:-60}"; i=0
  while [ "$i" -lt "$limit" ]; do
    s=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
    case "$s" in running/healthy) return 0;; exited/*|dead/*) return 1;; esac
    i=$((i+1)); sleep 1
  done
  return 1
}
start_router(){
  name="$1"; image="$2"
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" --restart unless-stopped \
    --label metatron.mcp.role=router \
    --network metatron-gateway-online --network-alias metatron-ssh-mcp \
    --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m \
    --security-opt no-new-privileges:true --cap-drop ALL \
    -v "$ROUTER_STATE:/state:rw" "$image" >/dev/null
  wait_health "$name" 60
}
router_version(){
  docker exec "$1" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.stdout.write(String(j.version||''))).catch(()=>process.exit(3))" 2>/dev/null || true
}

# SELF-HEALING PREFLIGHT: remove only runtime containers that are not referenced by
# canonical router state. Images are intentionally preserved; g2 is the prebuilt artifact.
authoritative=$(python3 - "$ROUTER_STATE/router.json" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1]))
except Exception: d={}
vals=[]
for k in ('active','standby'):
    v=d.get(k)
    if isinstance(v,str) and v: vals.append(v)
for v in d.get('draining',[]) if isinstance(d.get('draining',[]),list) else []:
    if isinstance(v,str) and v: vals.append(v)
print(' '.join(dict.fromkeys(vals)))
PY
)
[ -n "$authoritative" ] || { echo 'BROKER_V2_5_ROUTER_STATE_EMPTY' >&2; exit 6; }
echo "BROKER_V2_5_AUTHORITATIVE_RUNTIMES=$authoritative"
for c in $(docker ps -a --format '{{.Names}}' | grep '^metatron-mcp-runtime-' || true); do
  keep=0
  for a in $authoritative; do [ "$c" = "$a" ] && keep=1; done
  if [ "$keep" -eq 0 ]; then
    echo "BROKER_V2_5_ORPHAN_RUNTIME_REMOVED=$c"
    docker rm -f "$c" >/dev/null 2>&1 || true
  fi
done
# Let the kernel reclaim memory/swap pressure after orphan cleanup.
i=0
while [ "$i" -lt 45 ]; do
  mem=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
  swap=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
  echo "BROKER_V2_5_PREFLIGHT_HEADROOM attempt=$i mem_available_kb=${mem:-0} swap_free_kb=${swap:-0}"
  if [ "${mem:-0}" -ge 262144 ] && [ "${swap:-0}" -ge 1048576 ]; then break; fi
  i=$((i+1)); sleep 2
done
[ "${mem:-0}" -ge 262144 ] || { echo 'BROKER_V2_5_PREFLIGHT_HEADROOM_FAILED' >&2; exit 7; }
# Require consecutive baseline success, not a one-shot sample.
i=0
while [ "$i" -lt 12 ]; do public_transport_ok || { echo "BROKER_V2_5_BASELINE_RETRY sample=$i"; sleep 1; continue; }; i=$((i+1)); sleep 0.2; done
[ "$i" -ge 12 ] || { echo 'BROKER_V2_5_BASELINE_TRANSPORT_FAILED' >&2; exit 8; }
echo 'BROKER_V2_5_BASELINE_PASS'

docker image inspect metatron-mcp-router:v2.3 >/dev/null 2>&1 || { echo 'BROKER_V2_5_ROUTER_V23_IMAGE_MISSING' >&2; exit 9; }
old_a=$(docker inspect metatron-mcp-router-a --format '{{.Image}}' 2>/dev/null || true)
old_b=$(docker inspect metatron-mcp-router-b --format '{{.Image}}' 2>/dev/null || true)
[ -n "$old_a" ] && [ -n "$old_b" ] || { echo 'BROKER_V2_5_ROUTER_CURRENT_IDENTITY_MISSING' >&2; exit 10; }

rollback_routers(){
  echo 'BROKER_V2_5_ROUTER_MIGRATION_ROLLBACK' >&2
  docker rm -f metatron-mcp-router-a metatron-mcp-router-b >/dev/null 2>&1 || true
  start_router metatron-mcp-router-a "$old_a" >/dev/null 2>&1 || true
  start_router metatron-mcp-router-b "$old_b" >/dev/null 2>&1 || true
}

echo 'BROKER_V2_5_ROUTER_MAINTENANCE_WINDOW_BEGIN'
docker rm -f metatron-mcp-router-a metatron-mcp-router-b >/dev/null 2>&1 || true
if ! start_router metatron-mcp-router-a metatron-mcp-router:v2.3; then rollback_routers; echo 'BROKER_V2_5_ROUTER_A_MIGRATION_FAILED' >&2; exit 11; fi
if ! start_router metatron-mcp-router-b metatron-mcp-router:v2.3; then rollback_routers; echo 'BROKER_V2_5_ROUTER_B_MIGRATION_FAILED' >&2; exit 12; fi
[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { rollback_routers; echo 'BROKER_V2_5_ROUTER_A_VERSION_FAILED' >&2; exit 13; }
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { rollback_routers; echo 'BROKER_V2_5_ROUTER_B_VERSION_FAILED' >&2; exit 14; }
if ! wait_public; then rollback_routers; echo 'BROKER_V2_5_ROUTER_PUBLIC_RECOVERY_FAILED' >&2; exit 15; fi
echo 'BROKER_V2_5_ROUTER_MAINTENANCE_WINDOW_END'
echo 'BROKER_V2_5_ROUTER_V23_PAIR_PASS'

cp "$BUILD/broker-resilience-v2_5-patch.py" "$TMP/"
cp "$BUILD/broker-resilient-release-v2_5.sh" "$TMP/"
python3 "$TMP/broker-resilience-v2_5-patch.py" "$TARGET" "$TMP/broker-v2_5.py"
python3 -m py_compile "$TMP/broker-v2_5.py"
grep -q 'VERSION = "2.5.0"' "$TMP/broker-v2_5.py"
grep -q 'METATRON_MCP_RESILIENT_RELEASE_V2_5' "$TMP/broker-v2_5.py"
echo 'BROKER_V2_5_ARTIFACT_PASS'

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v2.4.$stamp"
cp -a "$TARGET" "$backup"; chmod 600 "$backup"
owner=$(stat -c '%u' "$TARGET"); group=$(stat -c '%g' "$TARGET"); mode=$(stat -c '%a' "$TARGET")
rollback_broker(){ cp "$backup" "$TARGET.rollback"; chown "$owner:$group" "$TARGET.rollback"; chmod "$mode" "$TARGET.rollback"; mv -f "$TARGET.rollback" "$TARGET"; }
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v2_5.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"
request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"2.5.0"' || { rollback_broker; echo 'BROKER_V2_5_DIRECT_SMOKE_FAILED' >&2; exit 16; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"2.5.0"' || { rollback_broker; echo 'BROKER_V2_5_RESTRICTED_SMOKE_FAILED' >&2; exit 17; }
public_transport_ok || { rollback_broker; echo 'BROKER_V2_5_POST_INSTALL_PUBLIC_FAILED' >&2; exit 18; }
echo 'BROKER_V2_5_BROKER_SMOKE_PASS'
echo "BROKER_V2_5_INSTALL_PASS target=$TARGET backup=$backup router_a=2.3.0 router_b=2.3.0 promote_only=true"
