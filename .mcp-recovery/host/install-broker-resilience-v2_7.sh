#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
TARGET=/usr/local/sbin/metatron-mcp-broker
TMP=$(mktemp -d /tmp/metatron-broker-v2_7.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_7_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v2_7-patch.py broker-resilient-release-v2_7.sh broker-resilience-v2_7-probe-safety-patch.py; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V2_7_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V2_7_TARGET_MISSING' >&2; exit 4; }

# Normalize the release template before embedding it into the broker. This is idempotent and
# removes the v2.6-style MCP probe pressure that can exhaust stateless stdio child processes.
python3 "$BUILD/broker-resilience-v2_7-probe-safety-patch.py" "$BUILD/broker-resilient-release-v2_7.sh"
/bin/sh -n "$BUILD/broker-resilient-release-v2_7.sh"
python3 -m py_compile "$BUILD/broker-resilience-v2_7-patch.py" "$BUILD/broker-resilience-v2_7-probe-safety-patch.py"
grep -q 'METATRON_MCP_READINESS_CONTINUITY_PROBE_V1' "$BUILD/broker-resilient-release-v2_7.sh" || { echo 'BROKER_V2_7_PROBE_SAFETY_MISSING' >&2; exit 5; }
grep -q 'VERSION = "2.6.0"' "$TARGET" || { echo 'BROKER_V2_7_EXPECTED_V2_6_NOT_FOUND' >&2; exit 5; }
echo 'BROKER_V2_7_STATIC_SYNTAX_PASS'

if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then
  echo 'BROKER_V2_7_RELEASE_LOCK_BUSY' >&2
  exit 6
fi
echo 'BROKER_V2_7_RELEASE_LOCK_FREE'

router_version(){
  docker exec "$1" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.stdout.write(String(j.version||''))).catch(()=>process.exit(3))" 2>/dev/null || true
}
[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { echo 'BROKER_V2_7_ROUTER_A_V23_REQUIRED' >&2; exit 7; }
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { echo 'BROKER_V2_7_ROUTER_B_V23_REQUIRED' >&2; exit 8; }
echo 'BROKER_V2_7_ROUTER_PAIR_PASS'

public_ok(){
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-broker-install-functional-probe/2.7' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
i=0; baseline=0
while [ "$i" -lt 5 ]; do
  if public_ok; then baseline=1; break; fi
  i=$((i+1)); sleep 1
done
[ "$baseline" -eq 1 ] || { echo 'BROKER_V2_7_BASELINE_PUBLIC_FAILED' >&2; exit 9; }
echo 'BROKER_V2_7_BASELINE_PASS'

cp "$BUILD/broker-resilience-v2_7-patch.py" "$TMP/"
cp "$BUILD/broker-resilient-release-v2_7.sh" "$TMP/"
python3 "$TMP/broker-resilience-v2_7-patch.py" "$TARGET" "$TMP/broker-v2_7.py"
python3 -m py_compile "$TMP/broker-v2_7.py"
for marker in \
  'VERSION = "2.7.0"' \
  'METATRON_MCP_RESILIENT_RELEASE_V2_7' \
  'METATRON_MCP_READINESS_CONTINUITY_PROBE_V1' \
  'CANDIDATE_PREWARM_PASS' \
  'ZERO_DOWNTIME_CUTOVER_PASS' \
  'PRIOR_STANDBY_NOT_DRAINED' \
  'RUNTIME_FAILOVER_ACCEPTANCE_PASS' \
  'TUNNEL_FAILOVER_ACCEPTANCE_PASS'
do
  grep -q "$marker" "$TMP/broker-v2_7.py" || { echo "BROKER_V2_7_ARTIFACT_MISSING $marker" >&2; exit 10; }
done
echo 'BROKER_V2_7_ARTIFACT_PASS'

mkdir -p "$BACKUPS"
chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v2.6.$stamp"
cp -a "$TARGET" "$backup"
chmod 600 "$backup"
owner=$(stat -c '%u' "$TARGET")
group=$(stat -c '%g' "$TARGET")
mode=$(stat -c '%a' "$TARGET")
rollback(){
  cp "$backup" "$TARGET.rollback"
  chown "$owner:$group" "$TARGET.rollback"
  chmod "$mode" "$TARGET.rollback"
  mv -f "$TARGET.rollback" "$TARGET"
}
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v2_7.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"2.7.0"' || { rollback; echo 'BROKER_V2_7_DIRECT_SMOKE_FAILED' >&2; exit 11; }

SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"2.7.0"' || { rollback; echo 'BROKER_V2_7_RESTRICTED_SMOKE_FAILED' >&2; exit 12; }
echo 'BROKER_V2_7_BROKER_SMOKE_PASS'

public_ok || { rollback; echo 'BROKER_V2_7_POST_INSTALL_PUBLIC_FAILED' >&2; exit 13; }
echo "BROKER_V2_7_INSTALL_PASS target=$TARGET backup=$backup routers_untouched=true release_not_started=true probe_safety=ready-window"
