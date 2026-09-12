#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
IMAGE=metatron-ssh-mcp-runtime:g9
BASE=metatron-ssh-mcp-runtime:g8
RELEASE=$BUILD/broker-resilient-release-v3_4.sh
LOCK=/var/lock/metatron-g9-normal-oauth-final.lock
PREFLIGHT=metatron-mcp-g9-normal-preflight

[ "$(id -u)" -eq 0 ] || { echo 'NORMAL_OAUTH_G9_REQUIRES_ROOT' >&2; exit 2; }
exec 9>"$LOCK"
flock -n 9 || { echo 'NORMAL_OAUTH_G9_BUSY' >&2; exit 3; }
for f in auth-normal-oauth-patch.mjs Dockerfile.normal-oauth release-auth-aware-acceptance-patch.py "$RELEASE"; do
  [ -r "$f" ] || [ -r "$BUILD/$f" ] || { echo "NORMAL_OAUTH_G9_MISSING $f" >&2; exit 4; }
done

gen=$(cat "$STATE/generation" 2>/dev/null || true)
[ "$gen" = 8 ] || { echo "NORMAL_OAUTH_G9_GENERATION_8_REQUIRED observed=${gen:-missing}" >&2; exit 5; }
for c in metatron-mcp-runtime-g8 metatron-mcp-runtime-g8-standby metatron-mcp-router-a metatron-mcp-router-b; do
  st=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
  [ "$st" = running/healthy ] || { echo "NORMAL_OAUTH_G9_BASELINE_UNHEALTHY container=$c status=$st" >&2; exit 6; }
done

cd "$BUILD"
node --check auth-normal-oauth-patch.mjs
python3 -m py_compile release-auth-aware-acceptance-patch.py
python3 release-auth-aware-acceptance-patch.py "$RELEASE"
grep -Fq 'METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1' "$RELEASE" || { echo 'NORMAL_OAUTH_G9_RELEASE_ACCEPTANCE_MISSING' >&2; exit 7; }
echo 'NORMAL_OAUTH_G9_RELEASE_ACCEPTANCE_PASS'

# Abandon the special confidential-client path completely.
rm -f "$STATE/claude-oauth-client-secret" "$STATE/claude-oauth-client-credentials.txt" "$BUILD/claude-oauth-client-secret.sha256"
echo 'NORMAL_OAUTH_G9_SPECIAL_CREDENTIALS_REMOVED'

mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
swap_free_kb=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
[ "${mem_avail_kb:-0}" -ge 524288 ] || { echo 'NORMAL_OAUTH_G9_LOW_MEMORY' >&2; exit 8; }
[ "${swap_free_kb:-0}" -ge 1048576 ] || { echo 'NORMAL_OAUTH_G9_LOW_SWAP' >&2; exit 9; }

echo "NORMAL_OAUTH_G9_BUILD_START $(date -Is)"
nice -n 10 docker build --pull=false -f Dockerfile.normal-oauth -t "$IMAGE" .
id=$(docker image inspect "$IMAGE" --format '{{.Id}}' 2>/dev/null || true)
base_id=$(docker image inspect "$BASE" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$id" ] && [ -n "$base_id" ] && [ "$id" != "$base_id" ] || { echo 'NORMAL_OAUTH_G9_IMAGE_INVALID' >&2; exit 10; }

docker run --rm --entrypoint sh "$IMAGE" -lc '
  grep -Fq "METATRON_CLAUDE_NORMAL_OAUTH_V1" /app/auth-proxy.mjs &&
  grep -Fq "registration_endpoint:" /app/auth-proxy.mjs &&
  grep -Fq "token_endpoint_auth_methods_supported: ['"'"'none'"'"']" /app/auth-proxy.mjs &&
  grep -Fq "token_endpoint_auth_method: '"'"'none'"'"'" /app/auth-proxy.mjs &&
  grep -Fq "if (!identity.client) return unauthorizedForOAuth(res);" /app/auth-proxy.mjs &&
  ! grep -Fq "id=\"founder-secret\"" /app/auth-proxy.mjs &&
  ! grep -Fq "client_secret_basic" /app/auth-proxy.mjs &&
  grep -Fq "METATRON_MCP_CHEAP_READINESS_V2" /app/auth-proxy.mjs &&
  node --check /app/auth-proxy.mjs
'
echo 'NORMAL_OAUTH_G9_STATIC_ASSERTIONS_PASS'

# Exact Claude-facing runtime preflight before any release mutation.
docker rm -f "$PREFLIGHT" >/dev/null 2>&1 || true
cleanup(){ docker rm -f "$PREFLIGHT" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
docker run -d --name "$PREFLIGHT" --restart no --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$IMAGE" >/dev/null

i=0
while [ "$i" -lt 60 ]; do
  docker exec "$PREFLIGHT" node -e "fetch('http://127.0.0.1:3002/live',{signal:AbortSignal.timeout(1500)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2))" >/dev/null 2>&1 && break
  i=$((i+1)); sleep 1
done
[ "$i" -lt 60 ] || { echo 'NORMAL_OAUTH_G9_PREFLIGHT_NOT_LIVE' >&2; exit 11; }

docker exec "$PREFLIGHT" node - <<'NODE'
const base='http://127.0.0.1:3002';
const mcp=await fetch(base+'/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}})});
const wa=String(mcp.headers.get('www-authenticate')||'');
if(mcp.status!==401||!/Bearer/i.test(wa)||!/resource_metadata=/i.test(wa)) throw new Error('oauth_challenge_missing');
const meta=await fetch(base+'/.well-known/oauth-authorization-server');
if(!meta.ok) throw new Error('metadata_unavailable');
const mj=await meta.json();
if(!mj.registration_endpoint||!mj.token_endpoint_auth_methods_supported?.includes('none')) throw new Error('metadata_not_standard');
const reg=await fetch(base+'/oauth/register',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({redirect_uris:['https://claude.ai/api/mcp/auth_callback'],token_endpoint_auth_method:'none',grant_types:['authorization_code'],response_types:['code']})});
if(reg.status!==201) throw new Error('dcr_failed_'+reg.status);
const rj=await reg.json();
if(!rj.client_id||rj.token_endpoint_auth_method!=='none') throw new Error('dcr_invalid');
const u=new URL(base+'/oauth/authorize');
for(const [k,v] of Object.entries({response_type:'code',client_id:rj.client_id,redirect_uri:'https://claude.ai/api/mcp/auth_callback',state:'preflight',code_challenge:'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',code_challenge_method:'S256',scope:'mcp:tools'})) u.searchParams.set(k,v);
const ar=await fetch(u,{redirect:'manual'}); const html=await ar.text();
if(ar.status!==200) throw new Error('authorize_page_'+ar.status);
if(/founder-secret|bootstrap code|client secret/i.test(html)) throw new Error('secret_prompt_present');
if(!/Authorize Claude/i.test(html)) throw new Error('authorize_button_missing');
console.log('NORMAL_OAUTH_G9_CLAUDE_PREFLIGHT_PASS');
NODE

docker rm -f "$PREFLIGHT" >/dev/null 2>&1 || true
trap - EXIT INT TERM
docker run --rm -e AUTH_PROXY_SELF_TEST=1 "$IMAGE" >/dev/null
echo 'NORMAL_OAUTH_G9_RUNTIME_SELFTEST_PASS'
echo "NORMAL_OAUTH_G9_PREBUILD_PASS image=$IMAGE id=$id base=$base_id release_not_started=true flow=standard-oauth-dcr-pkce-passwordless"
