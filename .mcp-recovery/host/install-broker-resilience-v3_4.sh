#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
TARGET=/usr/local/sbin/metatron-mcp-broker
TMP=$(mktemp -d /tmp/metatron-broker-v3_4.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V3_4_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v3_4-release-patch.py broker-resilience-v3_4-patch.py broker-resilient-release-v3_3.sh; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V3_4_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V3_4_TARGET_MISSING' >&2; exit 4; }
grep -q 'VERSION = "3.3.0"' "$TARGET" || { echo 'BROKER_V3_4_EXPECTED_V3_3_NOT_FOUND' >&2; exit 5; }

if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then
  echo 'BROKER_V3_4_RELEASE_LOCK_BUSY' >&2
  exit 6
fi
echo 'BROKER_V3_4_RELEASE_LOCK_FREE'

python3 "$BUILD/broker-resilience-v3_4-release-patch.py" \
  "$BUILD/broker-resilient-release-v3_3.sh" \
  "$BUILD/broker-resilient-release-v3_4.sh"
/bin/sh -n "$BUILD/broker-resilient-release-v3_4.sh"
python3 -m py_compile \
  "$BUILD/broker-resilience-v3_4-release-patch.py" \
  "$BUILD/broker-resilience-v3_4-patch.py"

for marker in \
  'METATRON_MCP_SIMPLE_RELEASE_V1' \
  'PROMOTION_STABLE_PASS' \
  'HOMOGENEOUS_STANDBY_PASS' \
  'TUNNEL_BASELINE_ACCEPTANCE_PASS' \
  'AUTH_CROSS_REPLICA_CONTROL_STATE_ACCEPTANCE_PASS' \
  'DONE_RESILIENT_V3_4'
do
  grep -q "$marker" "$BUILD/broker-resilient-release-v3_4.sh" || { echo "BROKER_V3_4_RELEASE_INVARIANT_MISSING $marker" >&2; exit 7; }
done
for forbidden in \
  'RUNTIME_FAILOVER_ACCEPTANCE_PASS' \
  'TUNNEL_FAILOVER_ACCEPTANCE_PASS' \
  'docker stop -t 1 "$candidate"' \
  'docker stop -t 5 source-cloudflared-1' \
  'docker stop -t 5 metatron-cloudflared-ha'
do
  if grep -Fq "$forbidden" "$BUILD/broker-resilient-release-v3_4.sh"; then
    echo "BROKER_V3_4_DESTRUCTIVE_NORMAL_RELEASE_BEHAVIOR_REMAINS $forbidden" >&2
    exit 8
  fi
done
echo 'BROKER_V3_4_RELEASE_STATIC_PASS'

python3 "$BUILD/broker-resilience-v3_4-patch.py" "$TARGET" "$TMP/broker-v3_4.py"
python3 -m py_compile "$TMP/broker-v3_4.py"
for marker in \
  'VERSION = "3.4.0"' \
  'METATRON_MCP_RESILIENT_RELEASE_V3_4' \
  'METATRON_MCP_SIMPLE_RELEASE_V1' \
  'PROMOTION_STABLE_PASS' \
  'HOMOGENEOUS_STANDBY_PASS'
do
  grep -q "$marker" "$TMP/broker-v3_4.py" || { echo "BROKER_V3_4_ARTIFACT_MISSING $marker" >&2; exit 9; }
done
echo 'BROKER_V3_4_ARTIFACT_PASS'

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v3.3.$stamp"
cp -a "$TARGET" "$backup"; chmod 600 "$backup"
owner=$(stat -c '%u' "$TARGET"); group=$(stat -c '%g' "$TARGET"); mode=$(stat -c '%a' "$TARGET")
rollback(){
  cp "$backup" "$TARGET.rollback"; chown "$owner:$group" "$TARGET.rollback"; chmod "$mode" "$TARGET.rollback"; mv -f "$TARGET.rollback" "$TARGET"
}

install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v3_4.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"3.4.0"' || { rollback; echo 'BROKER_V3_4_DIRECT_SMOKE_FAILED' >&2; exit 10; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"3.4.0"' || { rollback; echo 'BROKER_V3_4_RESTRICTED_SMOKE_FAILED' >&2; exit 11; }

if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then
  rollback; echo 'BROKER_V3_4_RELEASE_STARTED_DURING_INSTALL' >&2; exit 12
fi

echo "BROKER_V3_4_INSTALL_PASS target=$TARGET backup=$backup release_not_started=true strategy=simple-normal-release"
