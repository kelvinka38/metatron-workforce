#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
ROUTER_STATE=/var/lib/metatron-mcp/router
TARGET=/usr/local/sbin/metatron-mcp-broker
TMP=$(mktemp -d /tmp/metatron-broker-v2_3.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_3_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v2_3-patch.py broker-resilient-release-v2_3.sh mcp-router.mjs Dockerfile.router; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V2_3_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V2_3_TARGET_MISSING' >&2; exit 4; }
/bin/sh -n "$BUILD/broker-resilient-release-v2_3.sh"
python3 -m py_compile "$BUILD/broker-resilience-v2_3-patch.py"
grep -q 'VERSION = "2.2.0"' "$TARGET" || { echo 'BROKER_V2_3_EXPECTED_V2_2_NOT_FOUND' >&2; exit 5; }

public_transport_ok(){
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 6 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-router-bootstrap/2.3' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
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
probe_window(){
  out="$1"; : > "$out"
  (
    i=0
    while [ "$i" -lt 120 ]; do
      if ! public_transport_ok; then printf 'FAIL %s\n' "$(date -Is)" >> "$out"; fi
      i=$((i+1)); sleep 0.15
    done
  ) & echo $!
}
count_failures(){
  n=$(grep -c '^FAIL ' "$1" 2>/dev/null || true)
  case "$n" in ''|*[!0-9]*) n=999;; esac
  printf '%s' "$n"
}

# Baseline must already be stable before a router bootstrap is attempted.
i=0
while [ "$i" -lt 12 ]; do public_transport_ok || { echo "BROKER_V2_3_BASELINE_TRANSPORT_FAILED sample=$i" >&2; exit 6; }; i=$((i+1)); sleep 0.2; done
echo 'BROKER_V2_3_BASELINE_PASS'

# Build performs node --check inside the image before any serving router is touched.
docker build -f "$BUILD/Dockerfile.router" -t metatron-mcp-router:v2.3 "$BUILD" >/tmp/metatron-router-v23-build.log 2>&1 || {
  tail -80 /tmp/metatron-router-v23-build.log >&2 || true; echo 'BROKER_V2_3_ROUTER_BUILD_FAILED' >&2; exit 7; }
echo 'BROKER_V2_3_ROUTER_BUILD_PASS'

old_a=$(docker inspect metatron-mcp-router-a --format '{{.Image}}' 2>/dev/null || true)
old_b=$(docker inspect metatron-mcp-router-b --format '{{.Image}}' 2>/dev/null || true)
[ -n "$old_a" ] && [ -n "$old_b" ] || { echo 'BROKER_V2_3_OLD_ROUTER_IDENTITY_MISSING' >&2; exit 8; }
rollback_routers(){
  echo 'BROKER_V2_3_ROUTER_ROLLBACK' >&2
  docker rm -f metatron-mcp-router-v23-canary >/dev/null 2>&1 || true
  start_router metatron-mcp-router-a "$old_a" >/dev/null 2>&1 || true
  start_router metatron-mcp-router-b "$old_b" >/dev/null 2>&1 || true
}

# Add a third healthy v2.3 router before replacing either existing router.
start_router metatron-mcp-router-v23-canary metatron-mcp-router:v2.3 || { echo 'BROKER_V2_3_CANARY_NOT_HEALTHY' >&2; exit 9; }
[ "$(router_version metatron-mcp-router-v23-canary)" = '2.3.0' ] || { docker rm -f metatron-mcp-router-v23-canary >/dev/null 2>&1 || true; echo 'BROKER_V2_3_CANARY_VERSION_FAILED' >&2; exit 10; }
public_transport_ok || { docker rm -f metatron-mcp-router-v23-canary >/dev/null 2>&1 || true; echo 'BROKER_V2_3_CANARY_PUBLIC_FAILED' >&2; exit 11; }
echo 'BROKER_V2_3_ROUTER_CANARY_PASS'

rolling_log="$TMP/router-rolling-probe.log"
probe_pid=$(probe_window "$rolling_log")
if ! start_router metatron-mcp-router-a metatron-mcp-router:v2.3; then kill "$probe_pid" 2>/dev/null || true; rollback_routers; echo 'BROKER_V2_3_ROUTER_A_FAILED' >&2; exit 12; fi
[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { kill "$probe_pid" 2>/dev/null || true; rollback_routers; echo 'BROKER_V2_3_ROUTER_A_VERSION_FAILED' >&2; exit 13; }
if ! start_router metatron-mcp-router-b metatron-mcp-router:v2.3; then kill "$probe_pid" 2>/dev/null || true; rollback_routers; echo 'BROKER_V2_3_ROUTER_B_FAILED' >&2; exit 14; fi
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { kill "$probe_pid" 2>/dev/null || true; rollback_routers; echo 'BROKER_V2_3_ROUTER_B_VERSION_FAILED' >&2; exit 15; }
docker rm -f metatron-mcp-router-v23-canary >/dev/null 2>&1 || true
wait "$probe_pid" 2>/dev/null || true
rolling_failures=$(count_failures "$rolling_log")
echo "BROKER_V2_3_ROUTER_ROLLING_TRANSPORT_FAILURES=$rolling_failures"
if [ "$rolling_failures" -ne 0 ]; then rollback_routers; echo 'BROKER_V2_3_ROUTER_ROLLING_ACCEPTANCE_FAILED' >&2; exit 16; fi
public_transport_ok || { rollback_routers; echo 'BROKER_V2_3_ROUTER_FINAL_PUBLIC_FAILED' >&2; exit 17; }
echo 'BROKER_V2_3_ROUTER_ROLLING_PASS'

# Produce broker v2.3 only after router v2.3 is live and proven.
cp "$BUILD/broker-resilience-v2_3-patch.py" "$TMP/"
cp "$BUILD/broker-resilient-release-v2_3.sh" "$TMP/"
python3 "$TMP/broker-resilience-v2_3-patch.py" "$TARGET" "$TMP/broker-v2_3.py"
python3 -m py_compile "$TMP/broker-v2_3.py"
grep -q 'VERSION = "2.3.0"' "$TMP/broker-v2_3.py"
grep -q 'METATRON_MCP_RESILIENT_RELEASE_V2_3' "$TMP/broker-v2_3.py"
echo 'BROKER_V2_3_ARTIFACT_PASS'

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v2.2.$stamp"
cp -a "$TARGET" "$backup"; chmod 600 "$backup"
owner=$(stat -c '%u' "$TARGET"); group=$(stat -c '%g' "$TARGET"); mode=$(stat -c '%a' "$TARGET")
rollback_broker(){ cp "$backup" "$TARGET.rollback"; chown "$owner:$group" "$TARGET.rollback"; chmod "$mode" "$TARGET.rollback"; mv -f "$TARGET.rollback" "$TARGET"; }
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v2_3.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"
request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"2.3.0"' || { rollback_broker; echo 'BROKER_V2_3_DIRECT_SMOKE_FAILED' >&2; exit 18; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"2.3.0"' || { rollback_broker; echo 'BROKER_V2_3_RESTRICTED_SMOKE_FAILED' >&2; exit 19; }
echo 'BROKER_V2_3_BROKER_SMOKE_PASS'
public_transport_ok || { rollback_broker; echo 'BROKER_V2_3_POST_INSTALL_PUBLIC_FAILED' >&2; exit 20; }
echo "BROKER_V2_3_INSTALL_PASS target=$TARGET backup=$backup router_a=2.3.0 router_b=2.3.0"
