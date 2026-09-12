#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
TMP=$(mktemp -d /tmp/metatron-broker-v2_1.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_1_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v2_1-patch.py broker-resilient-release-v2_1.sh; do [ -r "$BUILD/$f" ] || { echo "BROKER_V2_1_MISSING $f" >&2; exit 3; }; done
/bin/sh -n "$BUILD/broker-resilient-release-v2_1.sh"
python3 -m py_compile "$BUILD/broker-resilience-v2_1-patch.py"

candidates="$TMP/candidates"; : > "$candidates"
for root in /usr/local/sbin /usr/local/bin /opt/metatron/ssh-mcp; do
  [ -d "$root" ] || continue
  find "$root" -maxdepth 3 -type f -size -1024k 2>/dev/null | while IFS= read -r p; do
    case "$p" in "$BUILD"/*|"$BACKUPS"/*) continue;; esac
    grep -q 'VERSION = "2.0.0"' "$p" 2>/dev/null || continue
    grep -q 'METATRON_MCP_RESILIENT_RELEASE_V2' "$p" 2>/dev/null || continue
    grep -q 'def start_self_upgrade():' "$p" 2>/dev/null || continue
    printf '%s\n' "$p"
  done
done >> "$candidates"
sort -u "$candidates" -o "$candidates"
count=$(wc -l < "$candidates" | tr -d ' ')
[ "$count" = 1 ] || { echo "BROKER_V2_1_DISCOVERY_FAILED candidates=$count" >&2; sed 's/^/candidate: /' "$candidates" >&2 || true; exit 4; }
target=$(cat "$candidates")
owner=$(stat -c '%u' "$target"); group=$(stat -c '%g' "$target"); mode=$(stat -c '%a' "$target")
cp "$BUILD/broker-resilience-v2_1-patch.py" "$TMP/"
cp "$BUILD/broker-resilient-release-v2_1.sh" "$TMP/"
python3 "$TMP/broker-resilience-v2_1-patch.py" "$target" "$TMP/broker-v2_1.py"
python3 -m py_compile "$TMP/broker-v2_1.py"
grep -q 'VERSION = "2.1.0"' "$TMP/broker-v2_1.py"
grep -q 'METATRON_MCP_RESILIENT_RELEASE_V2_1' "$TMP/broker-v2_1.py"
echo "BROKER_V2_1_ARTIFACT_PASS target=$target"

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$target").v2.0.$stamp"
cp -a "$target" "$backup"; chmod 600 "$backup"
rollback(){ cp "$backup" "$target.rollback"; chown "$owner:$group" "$target.rollback"; chmod "$mode" "$target.rollback"; mv -f "$target.rollback" "$target"; }
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v2_1.py" "$target.new"
mv -f "$target.new" "$target"

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$target" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"2.1.0"' || { rollback; echo 'BROKER_V2_1_DIRECT_SMOKE_FAILED' >&2; exit 5; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"2.1.0"' || { rollback; echo 'BROKER_V2_1_RESTRICTED_SMOKE_FAILED' >&2; exit 6; }
echo 'BROKER_V2_1_BROKER_SMOKE_PASS'

ROUTER_STATE=/var/lib/metatron-mcp/router/router.json
[ -r "$ROUTER_STATE" ] || { echo 'BROKER_V2_1_ROUTER_STATE_MISSING' >&2; exit 7; }
active=$(python3 - "$ROUTER_STATE" <<'PY'
import json,sys
print(json.load(open(sys.argv[1])).get('active',''))
PY
)
standby=$(python3 - "$ROUTER_STATE" <<'PY'
import json,sys
print(json.load(open(sys.argv[1])).get('standby',''))
PY
)
[ -n "$active" ] || { echo 'BROKER_V2_1_ACTIVE_MISSING' >&2; exit 8; }
health=$(docker inspect "$active" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
[ "$health" = healthy ] || { echo "BROKER_V2_1_ACTIVE_NOT_HEALTHY active=$active health=$health" >&2; exit 9; }
code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2024-11-05' \
  --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
[ "$code" = 200 ] || { echo "BROKER_V2_1_PUBLIC_TRANSPORT_FAILED code=$code" >&2; exit 10; }
if [ -n "$standby" ] && [ "$standby" != "$active" ] && docker inspect "$standby" >/dev/null 2>&1; then
  docker stop -t 15 "$standby" >/dev/null
  echo "BROKER_V2_1_RESOURCE_RELIEF_STANDBY_STOPPED=$standby"
fi

echo "BROKER_V2_1_INSTALL_PASS target=$target backup=$backup active=$active standby=$standby"
