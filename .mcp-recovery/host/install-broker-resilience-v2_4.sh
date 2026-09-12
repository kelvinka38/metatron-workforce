#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
TARGET=/usr/local/sbin/metatron-mcp-broker
TMP=$(mktemp -d /tmp/metatron-broker-v2_4.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_4_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v2_4-patch.py broker-resilient-release-v2_4.sh; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V2_4_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V2_4_TARGET_MISSING' >&2; exit 4; }
/bin/sh -n "$BUILD/broker-resilient-release-v2_4.sh"
python3 -m py_compile "$BUILD/broker-resilience-v2_4-patch.py"
grep -q 'VERSION = "2.2.0"' "$TARGET" || { echo 'BROKER_V2_4_EXPECTED_V2_2_NOT_FOUND' >&2; exit 5; }

echo 'BROKER_V2_4_STATIC_SYNTAX_PASS'

public_transport_ok(){
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 6 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-broker-bootstrap/2.4' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}

i=0
while [ "$i" -lt 10 ]; do
  public_transport_ok || { echo "BROKER_V2_4_BASELINE_TRANSPORT_FAILED sample=$i" >&2; exit 6; }
  i=$((i+1)); sleep 0.15
done
echo 'BROKER_V2_4_BASELINE_PASS'

cp "$BUILD/broker-resilience-v2_4-patch.py" "$TMP/"
cp "$BUILD/broker-resilient-release-v2_4.sh" "$TMP/"
python3 "$TMP/broker-resilience-v2_4-patch.py" "$TARGET" "$TMP/broker-v2_4.py"
python3 -m py_compile "$TMP/broker-v2_4.py"
grep -q 'VERSION = "2.4.0"' "$TMP/broker-v2_4.py"
grep -q 'METATRON_MCP_RESILIENT_RELEASE_V2_4' "$TMP/broker-v2_4.py"
grep -q 'CANDIDATE_PREWARM_PASS' "$TMP/broker-v2_4.py"
grep -q 'mcp_release_in_progress' "$TMP/broker-v2_4.py"
echo 'BROKER_V2_4_ARTIFACT_PASS'

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v2.2.$stamp"
cp -a "$TARGET" "$backup"; chmod 600 "$backup"
owner=$(stat -c '%u' "$TARGET"); group=$(stat -c '%g' "$TARGET"); mode=$(stat -c '%a' "$TARGET")
rollback(){
  echo 'BROKER_V2_4_INSTALL_ROLLBACK' >&2
  cp "$backup" "$TARGET.rollback"
  chown "$owner:$group" "$TARGET.rollback"; chmod "$mode" "$TARGET.rollback"
  mv -f "$TARGET.rollback" "$TARGET"
}
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v2_4.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"2.4.0"' || { rollback; echo 'BROKER_V2_4_DIRECT_SMOKE_FAILED' >&2; exit 7; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"2.4.0"' || { rollback; echo 'BROKER_V2_4_RESTRICTED_SMOKE_FAILED' >&2; exit 8; }
public_transport_ok || { rollback; echo 'BROKER_V2_4_POST_INSTALL_TRANSPORT_FAILED' >&2; exit 9; }
echo 'BROKER_V2_4_BROKER_SMOKE_PASS'
echo "BROKER_V2_4_INSTALL_PASS target=$TARGET backup=$backup routers_untouched=true"
