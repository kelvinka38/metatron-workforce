#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
IMAGE=metatron-ssh-mcp-runtime:g9
PREFLIGHT=metatron-mcp-g9-normal-final-verify
RELEASE=$BUILD/broker-resilient-release-v3_4.sh

[ "$(id -u)" -eq 0 ] || { echo 'NORMAL_OAUTH_G9_FINAL_VERIFY_REQUIRES_ROOT' >&2; exit 2; }
[ "$(cat "$STATE/generation" 2>/dev/null || true)" = 8 ] || { echo 'NORMAL_OAUTH_G9_FINAL_VERIFY_REQUIRES_GENERATION_8' >&2; exit 3; }
docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo 'NORMAL_OAUTH_G9_FINAL_IMAGE_MISSING' >&2; exit 4; }
grep -Fq 'METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1' "$RELEASE" || { echo 'NORMAL_OAUTH_G9_AUTH_AWARE_RELEASE_MISSING' >&2; exit 5; }

docker run --rm --entrypoint sh "$IMAGE" -lc '
  grep -Fq "METATRON_CLAUDE_NORMAL_OAUTH_V1" /app/auth-proxy.mjs &&
  grep -Fq "METATRON_MCP_CHEAP_READINESS_V2" /app/auth-proxy.mjs &&
  grep -Fq "registration_endpoint:" /app/auth-proxy.mjs &&
  grep -Fq "token_endpoint_auth_methods_supported: ['"'"'none'"'"']" /app/auth-proxy.mjs &&
  grep -Fq "if (!identity.client) return unauthorizedForOAuth(res);" /app/auth-proxy.mjs &&
  ! grep -Fq "id=\"founder-secret\"" /app/auth-proxy.mjs &&
  ! grep -Fq "client_secret_basic" /app/auth-proxy.mjs &&
  node --check /app/auth-proxy.mjs
'
echo 'NORMAL_OAUTH_G9_FINAL_STATIC_PASS'

docker run --rm -e AUTH_PROXY_SELF_TEST=1 --entrypoint node "$IMAGE" /app/auth-proxy.mjs >/tmp/metatron-g9-auth-selftest.log 2>&1
cat /tmp/metatron-g9-auth-selftest.log
grep -q 'AUTH_PROXY_ACCEPTANCE_PASS' /tmp/metatron-g9-auth-selftest.log || { echo 'NORMAL_OAUTH_G9_AUTH_SELFTEST_FAILED' >&2; exit 6; }
rm -f /tmp/metatron-g9-auth-selftest.log
echo 'NORMAL_OAUTH_G9_FINAL_SELFTEST_PASS'

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
[ "$i" -lt 60 ] || { echo 'NORMAL_OAUTH_G9_FINAL_PREFLIGHT_NOT_LIVE' >&2; exit 7; }

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
for(const [k,v] of Object.entries({response_type:'code',client_id:rj.client_id,redirect_uri:'https://claude.ai/api/mcp/auth_callback',state:'finalverify',code_challenge:'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',code_challenge_method:'S256',scope:'mcp:tools'})) u.searchParams.set(k,v);
const ar=await fetch(u,{redirect:'manual'}); const html=await ar.text();
if(ar.status!==200) throw new Error('authorize_page_'+ar.status);
if(/founder-secret|bootstrap code|client secret/i.test(html)) throw new Error('secret_prompt_present');
if(!/Authorize Claude/i.test(html)) throw new Error('authorize_button_missing');
console.log('NORMAL_OAUTH_G9_FINAL_CLAUDE_PREFLIGHT_PASS');
NODE

docker rm -f "$PREFLIGHT" >/dev/null 2>&1 || true
trap - EXIT INT TERM
id=$(docker image inspect "$IMAGE" --format '{{.Id}}')
echo "NORMAL_OAUTH_G9_FINAL_VERIFY_PASS image=$IMAGE id=$id generation=8 release_not_started=true flow=standard-oauth-dcr-pkce-passwordless"
