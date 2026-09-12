#!/bin/sh
set -eu

BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
KEY=/opt/metatron/ssh-mcp/id_ed25519
TMP=$(mktemp -d /tmp/metatron-broker-v2.XXXXXX)
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker.gz.b64 broker-resilience-v2-patch.py broker-resilient-release-v2.sh; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V2_INSTALL_MISSING $BUILD/$f" >&2; exit 3; }
done
[ -r "$KEY" ] || { echo 'BROKER_V2_INSTALL_MISSING_TRANSPORT_KEY' >&2; exit 4; }

base64 -d "$BUILD/broker.gz.b64" | gzip -d > "$TMP/broker-v1.py"
cp "$BUILD/broker-resilience-v2-patch.py" "$TMP/broker-resilience-v2-patch.py"
cp "$BUILD/broker-resilient-release-v2.sh" "$TMP/broker-resilient-release-v2.sh"
python3 "$TMP/broker-resilience-v2-patch.py" "$TMP/broker-v1.py" "$TMP/broker-v2.py"
python3 -m py_compile "$TMP/broker-v2.py" "$TMP/broker-resilience-v2-patch.py"
/bin/sh -n "$TMP/broker-resilient-release-v2.sh"
grep -q 'VERSION = "2.0.0"' "$TMP/broker-v2.py"
grep -q 'METATRON_MCP_RESILIENT_RELEASE_V2' "$TMP/broker-v2.py"
grep -q 'METATRON_MCP_CONTROL_STATE_ATOMIC_V2' "$TMP/broker-v2.py"

echo 'BROKER_V2_INSTALL_ARTIFACT_PASS'

# Discover the actual installed root broker by exact source match first. This avoids guessing the
# forced-command path and avoids changing sshd/sudo policy.
candidates="$TMP/candidates"
: > "$candidates"
for root in /usr/local/sbin /usr/local/bin /opt/metatron/ssh-mcp; do
  [ -d "$root" ] || continue
  find "$root" -maxdepth 3 -type f -size -512k 2>/dev/null | while IFS= read -r p; do
    [ "$p" = "$BUILD/broker.gz.b64" ] && continue
    [ "$p" = "$BUILD/broker-resilience-v2-patch.py" ] && continue
    [ "$p" = "$BUILD/install-broker-resilience-v2.sh" ] && continue
    if cmp -s "$p" "$TMP/broker-v1.py"; then printf '%s\n' "$p"; fi
  done >> "$candidates"
done

# If the installed copy has harmless drift, fall back to broker invariants, but require uniqueness.
if [ ! -s "$candidates" ]; then
  for root in /usr/local/sbin /usr/local/bin /opt/metatron/ssh-mcp; do
    [ -d "$root" ] || continue
    find "$root" -maxdepth 3 -type f -size -512k 2>/dev/null | while IFS= read -r p; do
      case "$p" in "$BUILD"/*) continue;; esac
      grep -q 'def op_server_status(a):' "$p" 2>/dev/null || continue
      grep -q 'def start_self_upgrade():' "$p" 2>/dev/null || continue
      grep -q 'VERSION = "1.0.0"' "$p" 2>/dev/null || continue
      printf '%s\n' "$p"
    done
  done >> "$candidates"
fi
sort -u "$candidates" -o "$candidates"
count=$(wc -l < "$candidates" | tr -d ' ')
[ "$count" = 1 ] || {
  echo "BROKER_V2_INSTALL_DISCOVERY_FAILED candidates=$count" >&2
  sed 's/^/candidate: /' "$candidates" >&2 || true
  exit 5
}
target=$(cat "$candidates")
[ -f "$target" ] || { echo 'BROKER_V2_INSTALL_TARGET_NOT_FILE' >&2; exit 6; }

echo "BROKER_V2_INSTALL_TARGET=$target"
owner=$(stat -c '%u' "$target")
group=$(stat -c '%g' "$target")
mode=$(stat -c '%a' "$target")
mkdir -p "$BACKUPS"
chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$target").v1.$stamp"
cp -a "$target" "$backup"
chmod 600 "$backup"

rollback() {
  echo 'BROKER_V2_INSTALL_ROLLBACK' >&2
  cp "$backup" "$target.rollback"
  chown "$owner:$group" "$target.rollback"
  chmod "$mode" "$target.rollback"
  mv -f "$target.rollback" "$target"
}

install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v2.py" "$target.new"
mv -f "$target.new" "$target"

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$target" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"2.0.0"' || { rollback; echo 'BROKER_V2_INSTALL_DIRECT_SMOKE_FAILED' >&2; exit 7; }
printf '%s' "$direct" | grep -q 'BROKER_VERSION=2.0.0' || { rollback; echo 'BROKER_V2_INSTALL_DIRECT_VERSION_FAILED' >&2; exit 8; }

echo 'BROKER_V2_INSTALL_DIRECT_SMOKE_PASS'

SSH_OPTS="-i $KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"2.0.0"' || { rollback; echo 'BROKER_V2_INSTALL_RESTRICTED_SMOKE_FAILED' >&2; exit 9; }
printf '%s' "$restricted" | grep -q 'BROKER_VERSION=2.0.0' || { rollback; echo 'BROKER_V2_INSTALL_RESTRICTED_VERSION_FAILED' >&2; exit 10; }

echo 'BROKER_V2_INSTALL_RESTRICTED_SMOKE_PASS'
echo "BROKER_V2_INSTALL_PASS target=$target backup=$backup"
