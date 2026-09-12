#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
BACKUPS=/opt/metatron/ssh-mcp/backups
TARGET=/usr/local/sbin/metatron-mcp-broker
TMP=$(mktemp -d /tmp/metatron-broker-v3_2.XXXXXX)
cleanup(){ rm -rf "$TMP"; }
trap cleanup EXIT INT TERM

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V3_2_INSTALL_REQUIRES_ROOT' >&2; exit 2; }
for f in broker-resilience-v3_2-release-patch.py broker-resilience-v3_2-patch.py broker-resilient-release-v3_1.sh Dockerfile.auth-convergence auth-control-state-init-patch.mjs auth-replica-selftest-patch.mjs; do
  [ -r "$BUILD/$f" ] || { echo "BROKER_V3_2_MISSING $f" >&2; exit 3; }
done
[ -r "$TARGET" ] || { echo 'BROKER_V3_2_TARGET_MISSING' >&2; exit 4; }
grep -q 'VERSION = "3.1.0"' "$TARGET" || { echo 'BROKER_V3_2_EXPECTED_V3_1_NOT_FOUND' >&2; exit 5; }

if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then
  echo 'BROKER_V3_2_RELEASE_LOCK_BUSY' >&2
  exit 6
fi
echo 'BROKER_V3_2_RELEASE_LOCK_FREE'

authority="$BUILD/.runtime-auth-security.enc"
[ -s "$authority" ] || { echo 'BROKER_V3_2_AUTHORITY_MISSING' >&2; exit 7; }
authority_hash=$(sha256sum "$authority" | awk '{print $1}')
[ -n "$authority_hash" ] || { echo 'BROKER_V3_2_AUTHORITY_HASH_FAILED' >&2; exit 8; }
echo "BROKER_V3_2_AUTHORITY_BASELINE_PASS hash=$authority_hash"

router_version(){
  docker exec "$1" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.stdout.write(String(j.version||''))).catch(()=>process.exit(3))" 2>/dev/null || true
}
[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { echo 'BROKER_V3_2_ROUTER_A_V23_REQUIRED' >&2; exit 9; }
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { echo 'BROKER_V3_2_ROUTER_B_V23_REQUIRED' >&2; exit 10; }
for c in metatron-mcp-runtime-g2 metatron-mcp-runtime-g2-standby; do
  s=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
  [ "$s" = 'running/healthy' ] || { echo "BROKER_V3_2_BASELINE_RUNTIME_UNHEALTHY container=$c status=$s" >&2; exit 11; }
done
echo 'BROKER_V3_2_BASELINE_TOPOLOGY_PASS'

# Build the minimal auth-convergence layer over the already-proven cheap-readiness g2 artifact.
# A failed build cannot mutate the running topology or broker.
G3_TAG=metatron-ssh-mcp-runtime:g3
G3_BUILD_TAG=metatron-ssh-mcp-runtime:g3-auth-v3_2
docker build -f "$BUILD/Dockerfile.auth-convergence" -t "$G3_BUILD_TAG" "$BUILD"
g3_id=$(docker image inspect "$G3_BUILD_TAG" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$g3_id" ] || { echo 'BROKER_V3_2_G3_IMAGE_MISSING' >&2; exit 12; }
base_id=$(docker image inspect metatron-ssh-mcp-runtime:g2 --format '{{.Id}}' 2>/dev/null || true)
[ -n "$base_id" ] || { echo 'BROKER_V3_2_G2_IMAGE_MISSING' >&2; exit 13; }
[ "$g3_id" != "$base_id" ] || { echo 'BROKER_V3_2_G3_IMAGE_NOT_DISTINCT' >&2; exit 14; }

# Static inspection inside the built artifact; no network, no host key, no production mutation.
docker run --rm --entrypoint sh "$G3_BUILD_TAG" -lc "
  node --check /app/auth-proxy.mjs &&
  grep -q 'METATRON_MCP_CONTROL_STATE_INIT_V2' /app/auth-proxy.mjs &&
  grep -q 'METATRON_MCP_CONTROL_STATE_SINGLE_WRITER_V2' /app/auth-proxy.mjs &&
  grep -q 'AUTH_CONTROL_STATE_HOST_REUSE_NO_WRITE' /app/auth-proxy.mjs &&
  ! grep -q 'METATRON_MCP_CONTROL_STATE_INIT_V1' /app/auth-proxy.mjs &&
  grep -q 'METATRON_MCP_AUTH_REPLICA_MODEL_ACCEPTANCE_V1' /app/auth-proxy.mjs &&
  grep -q 'METATRON_MCP_CHEAP_READINESS_V2' /app/auth-proxy.mjs &&
  grep -q 'METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1' /app/auth-proxy.mjs
" >/dev/null

echo "BROKER_V3_2_G3_AUTH_IMAGE_PASS id=$g3_id base=$base_id"

python3 "$BUILD/broker-resilience-v3_2-release-patch.py" "$BUILD/broker-resilient-release-v3_1.sh" "$BUILD/broker-resilient-release-v3_2.sh"
/bin/sh -n "$BUILD/broker-resilient-release-v3_2.sh"
python3 -m py_compile "$BUILD/broker-resilience-v3_2-release-patch.py" "$BUILD/broker-resilience-v3_2-patch.py"
for marker in \
  'METATRON_MCP_AUTH_CROSS_REPLICA_ACCEPTANCE_V1' \
  'AUTH_REPLICA_STARTUP_READ_ONLY_PASS' \
  'AUTH_CANDIDATE_AUTHORITY_HASH_STABLE_PASS' \
  'AUTH_CROSS_REPLICA_CONTROL_STATE_ACCEPTANCE_PASS' \
  'RUNTIME_FAILOVER_ACCEPTANCE_PASS' \
  'TUNNEL_FAILOVER_ACCEPTANCE_PASS' \
  'DONE_RESILIENT_V3_2'
do
  grep -q "$marker" "$BUILD/broker-resilient-release-v3_2.sh" || { echo "BROKER_V3_2_RELEASE_INVARIANT_MISSING $marker" >&2; exit 15; }
done
echo 'BROKER_V3_2_STATIC_SYNTAX_PASS'

cp "$BUILD/broker-resilience-v3_2-patch.py" "$TMP/"
cp "$BUILD/broker-resilient-release-v3_2.sh" "$TMP/"
python3 "$TMP/broker-resilience-v3_2-patch.py" "$TARGET" "$TMP/broker-v3_2.py"
python3 -m py_compile "$TMP/broker-v3_2.py"
for marker in \
  'VERSION = "3.2.0"' \
  'METATRON_MCP_RESILIENT_RELEASE_V3_2' \
  'METATRON_MCP_AUTH_CROSS_REPLICA_ACCEPTANCE_V1' \
  'AUTH_CROSS_REPLICA_CONTROL_STATE_ACCEPTANCE_PASS' \
  'RUNTIME_FAILOVER_ACCEPTANCE_PASS' \
  'TUNNEL_FAILOVER_ACCEPTANCE_PASS'
do
  grep -q "$marker" "$TMP/broker-v3_2.py" || { echo "BROKER_V3_2_ARTIFACT_MISSING $marker" >&2; exit 16; }
done
echo 'BROKER_V3_2_ARTIFACT_PASS'

mkdir -p "$BACKUPS"; chmod 700 "$BACKUPS"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$BACKUPS/$(basename "$TARGET").v3.1.$stamp"
cp -a "$TARGET" "$backup"; chmod 600 "$backup"
owner=$(stat -c '%u' "$TARGET"); group=$(stat -c '%g' "$TARGET"); mode=$(stat -c '%a' "$TARGET")
rollback(){
  cp "$backup" "$TARGET.rollback"; chown "$owner:$group" "$TARGET.rollback"; chmod "$mode" "$TARGET.rollback"; mv -f "$TARGET.rollback" "$TARGET"
  docker tag "$base_id" "$G3_TAG" >/dev/null 2>&1 || true
}

# Retag only after all build/static checks have passed. Running g2 containers remain pinned to old image id.
docker tag "$G3_BUILD_TAG" "$G3_TAG"
install -o "$owner" -g "$group" -m "$mode" "$TMP/broker-v3_2.py" "$TARGET.new"
mv -f "$TARGET.new" "$TARGET"

request='{"op":"server_status","args":{}}'
direct=$(printf '%s' "$request" | env SUDO_USER=metatron-mcp "$TARGET" 2>/dev/null || true)
printf '%s' "$direct" | grep -q '"broker_version":"3.2.0"' || { rollback; echo 'BROKER_V3_2_DIRECT_SMOKE_FAILED' >&2; exit 17; }
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
restricted=$(printf '%s' "$request" | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
printf '%s' "$restricted" | grep -q '"broker_version":"3.2.0"' || { rollback; echo 'BROKER_V3_2_RESTRICTED_SMOKE_FAILED' >&2; exit 18; }
echo 'BROKER_V3_2_BROKER_SMOKE_PASS'

# Broker install must not mutate authority or start a release.
after_hash=$(sha256sum "$authority" | awk '{print $1}')
[ "$authority_hash" = "$after_hash" ] || { rollback; echo 'BROKER_V3_2_AUTHORITY_CHANGED_DURING_INSTALL' >&2; exit 19; }
if ! flock -n /var/lock/metatron-mcp-release.lock -c true >/dev/null 2>&1; then rollback; echo 'BROKER_V3_2_RELEASE_STARTED_DURING_INSTALL' >&2; exit 20; fi
echo "BROKER_V3_2_INSTALL_PASS target=$TARGET backup=$backup release_not_started=true g3_image=$g3_id authority_hash=$after_hash strategy=auth-single-writer-cross-replica-acceptance"
