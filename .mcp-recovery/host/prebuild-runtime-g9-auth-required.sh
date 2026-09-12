#!/bin/sh
set -eu

BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
IMAGE=metatron-ssh-mcp-runtime:g9
BASE=metatron-ssh-mcp-runtime:g8
LOCK=/var/lock/metatron-auth-required-g9-prebuild.lock

[ "$(id -u)" -eq 0 ] || { echo 'AUTH_REQUIRED_G9_PREBUILD_REQUIRES_ROOT' >&2; exit 2; }
exec 9>"$LOCK"
flock -n 9 || { echo 'AUTH_REQUIRED_G9_PREBUILD_BUSY' >&2; exit 3; }

for f in auth-required-boundary-patch.mjs Dockerfile.auth-required-boundary auth-proxy.mjs; do
  [ -r "$BUILD/$f" ] || { echo "AUTH_REQUIRED_G9_MISSING $f" >&2; exit 4; }
done

gen=$(cat "$STATE/generation" 2>/dev/null || true)
[ "$gen" = '8' ] || { echo "AUTH_REQUIRED_G9_GENERATION_8_REQUIRED observed=${gen:-missing}" >&2; exit 5; }

for c in metatron-mcp-runtime-g8 metatron-mcp-runtime-g8-standby metatron-mcp-router-a metatron-mcp-router-b; do
  st=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
  [ "$st" = 'running/healthy' ] || { echo "AUTH_REQUIRED_G9_BASELINE_UNHEALTHY container=$c status=$st" >&2; exit 6; }
done

mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
swap_free_kb=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
[ "${mem_avail_kb:-0}" -ge 524288 ] || { echo "AUTH_REQUIRED_G9_LOW_MEMORY mem_available_kb=${mem_avail_kb:-0}" >&2; exit 7; }
[ "${swap_free_kb:-0}" -ge 1048576 ] || { echo "AUTH_REQUIRED_G9_LOW_SWAP swap_free_kb=${swap_free_kb:-0}" >&2; exit 8; }

# Patch canonical source so future full rebuilds preserve the auth-required boundary.
node "$BUILD/auth-required-boundary-patch.mjs" "$BUILD/auth-proxy.mjs"
node --check "$BUILD/auth-proxy.mjs"
grep -Fq 'METATRON_MCP_AUTH_REQUIRED_BOUNDARY_V1' "$BUILD/auth-proxy.mjs"
grep -Fq 'if (!identity.client) return unauthorizedForOAuth(res);' "$BUILD/auth-proxy.mjs"
! grep -Fq 'if (!identity.client && /Claude-User/i.test(ua))' "$BUILD/auth-proxy.mjs"
echo 'AUTH_REQUIRED_G9_CANONICAL_SOURCE_PASS'

cd "$BUILD"
echo "AUTH_REQUIRED_G9_BUILD_START $(date -Is)"
nice -n 10 docker build --pull=false -f Dockerfile.auth-required-boundary -t "$IMAGE" .

id=$(docker image inspect "$IMAGE" --format '{{.Id}}' 2>/dev/null || true)
base_id=$(docker image inspect "$BASE" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$id" ] || { echo 'AUTH_REQUIRED_G9_IMAGE_MISSING' >&2; exit 9; }
[ -n "$base_id" ] || { echo 'AUTH_REQUIRED_G9_BASE_IMAGE_MISSING' >&2; exit 10; }
[ "$id" != "$base_id" ] || { echo 'AUTH_REQUIRED_G9_IMAGE_NOT_DISTINCT' >&2; exit 11; }

docker run --rm --entrypoint sh "$IMAGE" -lc '
  grep -Fq "METATRON_MCP_AUTH_REQUIRED_BOUNDARY_V1" /app/auth-proxy.mjs &&
  grep -Fq "if (!identity.client) return unauthorizedForOAuth(res);" /app/auth-proxy.mjs &&
  ! grep -Fq "if (!identity.client && /Claude-User/i.test(ua))" /app/auth-proxy.mjs &&
  grep -Fq "METATRON_CLAUDE_CONFIDENTIAL_OAUTH_V1" /app/auth-proxy.mjs &&
  grep -Fq "CLAUDE_OAUTH_CLIENT_ID = '\''metatron-claude'\''" /app/auth-proxy.mjs &&
  grep -Fq "client_secret_basic" /app/auth-proxy.mjs &&
  grep -Fq "METATRON_MCP_CHEAP_READINESS_V2" /app/auth-proxy.mjs &&
  grep -Fq "METATRON_MCP_CONTROL_STATE_INIT_V2" /app/auth-proxy.mjs &&
  node --check /app/auth-proxy.mjs
'
echo 'AUTH_REQUIRED_G9_STATIC_ASSERTIONS_PASS'

docker run --rm --entrypoint sh -e AUTH_PROXY_SELF_TEST=1 "$IMAGE" -lc 'node /app/auth-proxy.mjs' | grep -Fq 'AUTH_PROXY_ACCEPTANCE_PASS'
echo 'AUTH_REQUIRED_G9_RUNTIME_SELFTEST_PASS'

echo "AUTH_REQUIRED_G9_PREBUILD_PASS image=$IMAGE id=$id base=$base_id release_not_started=true"
