#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
TARGET=/usr/local/sbin/metatron-mcp-broker
TMP=$(mktemp -d /tmp/metatron-broker-v2_6.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_6_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v2_6-patch.py broker-resilient-release-v2_6.sh; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V2_6_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V2_6_TARGET_MISSING' >&2; exit 4; }
/bin/sh -n "$BUILD/broker-resilient-release-v2_6.sh"
python3 -m py_compile "$BUILD/broker-resilience-v2_6-patch.py"
grep -q 'VERSION = "2.5.0"' "$TARGET" || { echo 'BROKER_V2_6_EXPECTED_V2_5_NOT_FOUND' >&2; exit 5; }
echo 'BROKER_V2_6_STATIC_SYNTAX_PASS'

# Never replace the broker while a release still owns the lock.
i=0
while ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; do
  i=$((i+1)); [ "$i" -lt 90 ] || { echo 'BROKER_V2_6_RELEASE_LOCK_TIMEOUT' >&2; exit 6; }
  sleep 1
done
echo 'BROKER_V2_6_RELEASE_LOCK_FREE'

router_version(){
  docker exec "$1" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.stdout.write(String(j.version||''))).catch(()=>process.exit(3))" 2>/dev/null || true
}
[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { echo 'BROKER_V2_6_ROUTER_A_V23_REQUIRED' >&2; exit 7; }
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { echo 'BROKER_V2_6_ROUTER_B_V23_REQUIRED' >&2; exit 8; }
echo 'BROKER_V2_6_ROUTER_PAIR_PASS'

public_ok(){
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
i=0; baseline=0
while [ "$i" -lt 3 ]; do if public_ok; then baseline=1; break; fi; i=$((i+1)); sleep 0.2; done
[ "$baseline" -eq 1 ] || { echo 'BROKER_V2_6_BASELINE_PUBLIC_FAILED' >&2; exit 9; }
echo 'BROKER_V2_6_BASELINE_PASS'

cp "$BUILD/broker-resilience-v2_6-patch.py" "$TMP/"
cp "$BUILD/broker-resilient-release-v2_6.sh" "$TMP/"
python3 "$TMP/broker-resilience-v2_6-patch.py" "$TARGET" "$TMP/broker-v2_6.py"
python3 -m py_compile "$TMP/broker-v2_6.py"
grep -q 'VERSION = "2.6.0"' "$TMP/broker-v2_6.py"
grep -q 'METATRON_MCP_RESILIENT_RELEASE_V2_6' "$TMP/broker-v2_6.py"
grep -q 'FAST_HANDOFF_PASS' "$TMP/broker-v2_6.py"
grep -q 'RUNTIME_FAILOVER_ACCEPTANCE_PASS' "$TMP/broker-v2_6.py"
grep -q 'TUNNEL_FAILOVER_ACCEPTANCE_PASS' "$TMP/broker-v2_6.py"
echo 'BROKER_V2_6_ARTIFACT_PASS'

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v2.5.$stamp"
cp -a "$TARGET" "$backup"; chmod 600 "$backup"
owner=$(stat -c '%u' "$TARGET"); group=$(stat -c '%g' "$TARGET"); mode=$(stat -c '%a' "$TARGET")
rollback(){ cp "$backup" "$TARGET.rollback"; chown "$owner:$group" "$TARGET.rollback"; chmod "$mode" "$TARGET.rollback"; mv -f "$TARGET.rollback" "$TARGET"; }
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v2_6.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"
request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"2.6.0"' || { rollback; echo 'BROKER_V2_6_DIRECT_SMOKE_FAILED' >&2; exit 10; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"2.6.0"' || { rollback; echo 'BROKER_V2_6_RESTRICTED_SMOKE_FAILED' >&2; exit 11; }
echo 'BROKER_V2_6_BROKER_SMOKE_PASS'
public_ok || { rollback; echo 'BROKER_V2_6_POST_INSTALL_PUBLIC_FAILED' >&2; exit 12; }
echo "BROKER_V2_6_INSTALL_PASS target=$TARGET backup=$backup routers_untouched=true fast_handoff=true"
