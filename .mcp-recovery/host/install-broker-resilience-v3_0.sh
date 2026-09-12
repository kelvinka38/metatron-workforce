#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
TARGET=/usr/local/sbin/metatron-mcp-broker
TMP=$(mktemp -d /tmp/metatron-broker-v3_0.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V3_0_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v3_0-release-patch.py broker-resilience-v3_0-patch.py broker-resilient-release-v2_9.sh; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V3_0_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V3_0_TARGET_MISSING' >&2; exit 4; }
grep -q 'VERSION = "2.9.0"' "$TARGET" || { echo 'BROKER_V3_0_EXPECTED_V2_9_NOT_FOUND' >&2; exit 5; }

if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then
  echo 'BROKER_V3_0_RELEASE_LOCK_BUSY' >&2
  exit 6
fi
echo 'BROKER_V3_0_RELEASE_LOCK_FREE'

python3 "$BUILD/broker-resilience-v3_0-release-patch.py" "$BUILD/broker-resilient-release-v2_9.sh" "$BUILD/broker-resilient-release-v3_0.sh"
/bin/sh -n "$BUILD/broker-resilient-release-v3_0.sh"
python3 -m py_compile "$BUILD/broker-resilience-v3_0-release-patch.py" "$BUILD/broker-resilience-v3_0-patch.py"
for marker in \
  'METATRON_MCP_BOUNDED_SAFE_READINESS_RETRY_V1' \
  'CUTOVER_SOFT_RETRIES=' \
  'RUNTIME_FAILOVER_SOFT_RETRIES=' \
  'TUNNEL_FAILOVER_SOFT_RETRIES=' \
  'PRIMARY_TUNNEL_RESTABILIZATION_PASS' \
  'HA_TUNNEL_RESTABILIZATION_PASS' \
  'RUNTIME_FAILOVER_ACCEPTANCE_PASS' \
  'TUNNEL_FAILOVER_ACCEPTANCE_PASS' \
  'DONE_RESILIENT_V3_0'
do
  grep -q "$marker" "$BUILD/broker-resilient-release-v3_0.sh" || { echo "BROKER_V3_0_RELEASE_INVARIANT_MISSING $marker" >&2; exit 7; }
done
echo 'BROKER_V3_0_STATIC_SYNTAX_PASS'

router_version(){
  docker exec "$1" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.stdout.write(String(j.version||''))).catch(()=>process.exit(3))" 2>/dev/null || true
}
[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { echo 'BROKER_V3_0_ROUTER_A_V23_REQUIRED' >&2; exit 8; }
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { echo 'BROKER_V3_0_ROUTER_B_V23_REQUIRED' >&2; exit 9; }
echo 'BROKER_V3_0_ROUTER_PAIR_PASS'

edge_ok(){
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 https://ssh.metatron.vn/live 2>/dev/null || true)
  [ "$code" = 200 ]
}
i=0; edge=0
while [ "$i" -lt 5 ]; do
  if edge_ok; then edge=1; break; fi
  i=$((i+1)); sleep 0.5
done
[ "$edge" -eq 1 ] || { echo 'BROKER_V3_0_BASELINE_EDGE_FAILED' >&2; exit 10; }
echo 'BROKER_V3_0_BASELINE_EDGE_PASS'

image=metatron-ssh-mcp-runtime:g2
artifact_id=$(docker image inspect "$image" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$artifact_id" ] || { echo 'BROKER_V3_0_HOTFIX_IMAGE_MISSING' >&2; exit 11; }
legacy_id=''
for tag in $(docker image ls --format '{{.Repository}}:{{.Tag}}' 2>/dev/null | grep '^metatron-ssh-mcp-runtime:g2-pre-readyfix-' || true); do
  legacy_id=$(docker image inspect "$tag" --format '{{.Id}}' 2>/dev/null || true)
  [ -n "$legacy_id" ] && break
done
if [ -n "$legacy_id" ] && [ "$legacy_id" = "$artifact_id" ]; then
  echo 'BROKER_V3_0_HOTFIX_IMAGE_POINTS_TO_LEGACY' >&2
  exit 12
fi
echo "BROKER_V3_0_HOTFIX_IMAGE_PASS id=$artifact_id"

cp "$BUILD/broker-resilience-v3_0-patch.py" "$TMP/"
cp "$BUILD/broker-resilient-release-v3_0.sh" "$TMP/"
python3 "$TMP/broker-resilience-v3_0-patch.py" "$TARGET" "$TMP/broker-v3_0.py"
python3 -m py_compile "$TMP/broker-v3_0.py"
for marker in \
  'VERSION = "3.0.0"' \
  'METATRON_MCP_RESILIENT_RELEASE_V3_0' \
  'METATRON_MCP_BOUNDED_SAFE_READINESS_RETRY_V1' \
  'TUNNEL_FAILOVER_SOFT_RETRIES=' \
  'PRIMARY_TUNNEL_RESTABILIZATION_PASS' \
  'HA_TUNNEL_RESTABILIZATION_PASS' \
  'RUNTIME_FAILOVER_ACCEPTANCE_PASS' \
  'TUNNEL_FAILOVER_ACCEPTANCE_PASS'
do
  grep -q "$marker" "$TMP/broker-v3_0.py" || { echo "BROKER_V3_0_ARTIFACT_MISSING $marker" >&2; exit 13; }
done
echo 'BROKER_V3_0_ARTIFACT_PASS'

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v2.9.$stamp"
cp -a "$TARGET" "$backup"; chmod 600 "$backup"
owner=$(stat -c '%u' "$TARGET"); group=$(stat -c '%g' "$TARGET"); mode=$(stat -c '%a' "$TARGET")
rollback(){ cp "$backup" "$TARGET.rollback"; chown "$owner:$group" "$TARGET.rollback"; chmod "$mode" "$TARGET.rollback"; mv -f "$TARGET.rollback" "$TARGET"; }
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v3_0.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"3.0.0"' || { rollback; echo 'BROKER_V3_0_DIRECT_SMOKE_FAILED' >&2; exit 14; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"3.0.0"' || { rollback; echo 'BROKER_V3_0_RESTRICTED_SMOKE_FAILED' >&2; exit 15; }
echo 'BROKER_V3_0_BROKER_SMOKE_PASS'

edge_ok || { rollback; echo 'BROKER_V3_0_POST_INSTALL_EDGE_FAILED' >&2; exit 16; }
echo "BROKER_V3_0_INSTALL_PASS target=$TARGET backup=$backup release_not_started=true hotfix_image=$artifact_id strategy=bounded-safe-ready-retry-plus-connector-restabilization"
