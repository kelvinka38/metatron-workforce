#!/bin/sh
set -eu

BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
TARGET=/usr/local/sbin/metatron-mcp-broker
RELEASE=$BUILD/broker-resilient-release-v3_4.sh
PATCH=$BUILD/broker-auth-aware-v3_6-patch.py
TMP=$(mktemp -d /tmp/metatron-broker-v3_6.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_AUTH_AWARE_V3_6_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in "$TARGET" "$RELEASE" "$PATCH"; do
  [ -r "$f" ] || { echo "BROKER_AUTH_AWARE_V3_6_MISSING $f" >&2; exit 3; }
done

if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then
  echo 'BROKER_AUTH_AWARE_V3_6_RELEASE_LOCK_BUSY' >&2
  exit 4
fi

grep -q 'VERSION = "3.5.0"' "$TARGET" || { echo 'BROKER_AUTH_AWARE_V3_6_EXPECTED_3_5_NOT_FOUND' >&2; exit 5; }
grep -q 'METATRON_HOST_MAINTENANCE_V1' "$TARGET" || { echo 'BROKER_AUTH_AWARE_V3_6_MAINTENANCE_SUBSTRATE_MISSING' >&2; exit 6; }
for marker in \
  'METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1' \
  'METATRON_MCP_DOCKER_EXEC_STDIN_V1' \
  'public_oauth_boundary_once()' \
  'router_transport_ok()' \
  'METATRON_MCP_SIMPLE_RELEASE_V1' \
  'DONE_RESILIENT_V3_4'
do
  grep -Fq "$marker" "$RELEASE" || { echo "BROKER_AUTH_AWARE_V3_6_RELEASE_MARKER_MISSING $marker" >&2; exit 7; }
done

python3 -m py_compile "$PATCH"
python3 "$PATCH" "$TARGET" "$RELEASE" "$TMP/broker-v3_6.py"
python3 -m py_compile "$TMP/broker-v3_6.py"

for marker in \
  'VERSION = "3.6.0"' \
  'METATRON_HOST_MAINTENANCE_V1' \
  'METATRON_MCP_AUTH_AWARE_EMBEDDED_RELEASE_V1' \
  'METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1' \
  'METATRON_MCP_DOCKER_EXEC_STDIN_V1' \
  'public_oauth_boundary_once()' \
  'router_transport_ok()' \
  'def op_host_disk_audit(a):' \
  'def op_host_safe_cleanup(a):' \
  'def op_host_unused_volume_remove(a):'
do
  grep -Fq "$marker" "$TMP/broker-v3_6.py" || { echo "BROKER_AUTH_AWARE_V3_6_ARTIFACT_MARKER_MISSING $marker" >&2; exit 8; }
done

echo 'BROKER_AUTH_AWARE_V3_6_ARTIFACT_PASS'

mkdir -p "$BACKUPS"
chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v3.5.$stamp"
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

install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v3_6.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"3.6.0"' || { rollback; echo 'BROKER_AUTH_AWARE_V3_6_DIRECT_SMOKE_FAILED' >&2; exit 9; }

SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"3.6.0"' || { rollback; echo 'BROKER_AUTH_AWARE_V3_6_RESTRICTED_SMOKE_FAILED' >&2; exit 10; }

if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then
  rollback
  echo 'BROKER_AUTH_AWARE_V3_6_RELEASE_STARTED_DURING_INSTALL' >&2
  exit 11
fi

echo "BROKER_AUTH_AWARE_V3_6_INSTALL_PASS target=$TARGET backup=$backup release_not_started=true embedded_release=auth-aware-v3_4"
