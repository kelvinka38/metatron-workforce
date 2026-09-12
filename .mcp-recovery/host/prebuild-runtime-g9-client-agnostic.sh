#!/bin/sh
set -eu

BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
IMAGE=metatron-ssh-mcp-runtime:g9
BASE=metatron-ssh-mcp-runtime:g8
PREFLIGHT=metatron-mcp-g9-client-agnostic-preflight
RELEASE=$BUILD/broker-resilient-release-v3_4.sh
LOCK=/var/lock/metatron-g9-client-agnostic-prebuild.lock

[ "$(id -u)" -eq 0 ] || { echo 'CLIENT_AGNOSTIC_G9_REQUIRES_ROOT' >&2; exit 2; }
exec 9>"$LOCK"
flock -n 9 || { echo 'CLIENT_AGNOSTIC_G9_BUSY' >&2; exit 3; }

for f in \
  auth-client-agnostic-oauth-patch.mjs \
  server-client-agnostic-auth-guard-patch.mjs \
  fix-client-agnostic-patch-source.py \
  Dockerfile.client-agnostic-oauth \
  release-heredoc-stdin-fix.py \
  release-auth-aware-acceptance-patch.py \
  broker-resilient-release-v3_4.sh \
  CLIENT_AGNOSTIC_REMOTE_MCP_SOT.md
do
  [ -r "$BUILD/$f" ] || { echo "CLIENT_AGNOSTIC_G9_MISSING $f" >&2; exit 4; }
done

[ "$(cat "$STATE/generation" 2>/dev/null || true)" = 8 ] || { echo 'CLIENT_AGNOSTIC_G9_GENERATION_8_REQUIRED' >&2; exit 5; }
for c in metatron-mcp-runtime-g8 metatron-mcp-runtime-g8-standby metatron-mcp-router-a metatron-mcp-router-b; do
  st=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
  [ "$st" = running/healthy ] || { echo "CLIENT_AGNOSTIC_G9_BASELINE_UNHEALTHY container=$c status=$st" >&2; exit 6; }
done

authority_before=$(sha256sum "$BUILD/.runtime-auth-security.enc" 2>/dev/null | awk '{print $1}' || true)
[ -n "$authority_before" ] || { echo 'CLIENT_AGNOSTIC_G9_AUTHORITY_MISSING' >&2; exit 7; }

cd "$BUILD"
python3 fix-client-agnostic-patch-source.py
python3 -m py_compile fix-client-agnostic-patch-source.py release-heredoc-stdin-fix.py release-auth-aware-acceptance-patch.py
node --check auth-client-agnostic-oauth-patch.mjs
node --check server-client-agnostic-auth-guard-patch.mjs
echo 'CLIENT_AGNOSTIC_G9_PATCH_SYNTAX_PASS'

if ! grep -Fq 'METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1' "$RELEASE"; then
  python3 release-auth-aware-acceptance-patch.py "$RELEASE"
fi
python3 release-heredoc-stdin-fix.py "$RELEASE"
grep -Fq 'METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1' "$RELEASE" || { echo 'CLIENT_AGNOSTIC_G9_AUTH_AWARE_RELEASE_MISSING' >&2; exit 8; }
grep -Fq 'METATRON_MCP_DOCKER_EXEC_STDIN_V1' "$RELEASE" || { echo 'CLIENT_AGNOSTIC_G9_RELEASE_STDIN_FIX_MISSING' >&2; exit 9; }
if grep -Eq "docker exec (?!-i )" "$RELEASE" 2>/dev/null; then :; fi
echo 'CLIENT_AGNOSTIC_G9_RELEASE_ACCEPTANCE_PASS'

mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
swap_free_kb=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
[ "${mem_avail_kb:-0}" -ge 524288 ] || { echo "CLIENT_AGNOSTIC_G9_LOW_MEMORY mem_available_kb=${mem_avail_kb:-0}" >&2; exit 10; }
[ "${swap_free_kb:-0}" -ge 1048576 ] || { echo "CLIENT_AGNOSTIC_G9_LOW_SWAP swap_free_kb=${swap_free_kb:-0}" >&2; exit 11; }

echo "CLIENT_AGNOSTIC_G9_BUILD_START $(date -Is)"
nice -n 10 docker build --pull=false -f Dockerfile.client-agnostic-oauth -t "$IMAGE" .
id=$(docker image inspect "$IMAGE" --format '{{.Id}}' 2>/dev/null || true)
base_id=$(docker image inspect "$BASE" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$id" ] && [ -n "$base_id" ] && [ "$id" != "$base_id" ] || { echo 'CLIENT_AGNOSTIC_G9_IMAGE_INVALID' >&2; exit 12; }

docker run --rm --entrypoint sh "$IMAGE" -lc '
  grep -Fq "METATRON_CLIENT_AGNOSTIC_OAUTH_V1" /app/auth-proxy.mjs &&
  grep -Fq "CLIENT_AGNOSTIC_OAUTH_ACCEPTANCE_PASS" /app/auth-proxy.mjs &&
  grep -Fq "metatron/authenticatedPrincipal" /app/auth-proxy.mjs &&
  grep -Fq "WORKFORCE_AUTH_HOST" /app/auth-proxy.mjs &&
  grep -Fq "/workplace/api/auth/request-code" /app/auth-proxy.mjs &&
  grep -Fq "token_endpoint_auth_methods_supported: ['"'"'none'"'"']" /app/auth-proxy.mjs &&
  grep -Fq "if (!identity.client) return unauthorizedForOAuth(res);" /app/auth-proxy.mjs &&
  ! grep -Fq "CLAUDE_OAUTH_CLIENT_ID" /app/auth-proxy.mjs &&
  ! grep -Fq "client_secret_basic" /app/auth-proxy.mjs &&
  grep -Fq "METATRON_CLIENT_AGNOSTIC_PROXY_GUARD_V1" /app/server.mjs &&
  grep -Fq "metatron/authenticatedPrincipal" /app/server.mjs &&
  ! grep -Fq "[\"gemini\",\"claude\"].includes(request?.params?._meta?.[\"metatron/verifiedClient\"])" /app/server.mjs &&
  grep -Fq "METATRON_MCP_CHEAP_READINESS_V2" /app/auth-proxy.mjs &&
  node --check /app/auth-proxy.mjs && node --check /app/server.mjs
'
echo 'CLIENT_AGNOSTIC_G9_STATIC_ASSERTIONS_PASS'

# Unit-level generic token/principal acceptance without any production credential.
# CLIENT_AGNOSTIC_G9_SELFTEST_DIAGNOSTICS_V1
if ! docker run --rm -e AUTH_PROXY_SELF_TEST=1 --entrypoint node "$IMAGE" /app/auth-proxy.mjs > /tmp/metatron-g9-client-agnostic-selftest.log 2>&1; then
  cat /tmp/metatron-g9-client-agnostic-selftest.log >&2 || true
  echo 'CLIENT_AGNOSTIC_G9_RUNTIME_SELFTEST_FAILED' >&2
  exit 13
fi
cat /tmp/metatron-g9-client-agnostic-selftest.log
grep -q 'CLIENT_AGNOSTIC_OAUTH_ACCEPTANCE_PASS' /tmp/metatron-g9-client-agnostic-selftest.log || { echo 'CLIENT_AGNOSTIC_G9_SELFTEST_GENERIC_IDENTITY_MISSING' >&2; exit 13; }
grep -q 'AUTH_PROXY_ACCEPTANCE_PASS' /tmp/metatron-g9-client-agnostic-selftest.log || { echo 'CLIENT_AGNOSTIC_G9_SELFTEST_BASELINE_MISSING' >&2; exit 14; }
rm -f /tmp/metatron-g9-client-agnostic-selftest.log
echo 'CLIENT_AGNOSTIC_G9_RUNTIME_SELFTEST_PASS'

# Runtime preflight on the private Docker network; never route public traffic to this container.
docker rm -f "$PREFLIGHT" >/dev/null 2>&1 || true
# CLIENT_AGNOSTIC_G9_PREFLIGHT_DIAGNOSTICS_V2
cleanup(){
  rc=$?
  trap - EXIT INT TERM
  if [ "$rc" -ne 0 ]; then
    echo "CLIENT_AGNOSTIC_G9_PREFLIGHT_FAILURE rc=$rc" >&2
    docker inspect "$PREFLIGHT" --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} error={{.State.Error}}' >&2 2>/dev/null || true
    docker logs --tail 200 "$PREFLIGHT" >&2 2>/dev/null || true
  fi
  docker rm -f "$PREFLIGHT" >/dev/null 2>&1 || true
  exit "$rc"
}
trap cleanup EXIT INT TERM
docker run -d --name "$PREFLIGHT" --restart no --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$IMAGE" >/dev/null

i=0
while [ "$i" -lt 90 ]; do
  st=$(docker inspect "$PREFLIGHT" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
  [ "$st" = running/healthy ] && break
  case "$st" in exited/*|dead/*) docker logs "$PREFLIGHT" >&2 || true; echo 'CLIENT_AGNOSTIC_G9_PREFLIGHT_EXITED' >&2; exit 15;; esac
  i=$((i+1)); sleep 1
done
[ "$i" -lt 90 ] || { docker logs "$PREFLIGHT" >&2 || true; echo 'CLIENT_AGNOSTIC_G9_PREFLIGHT_NOT_HEALTHY' >&2; exit 16; }

startup_log=/tmp/metatron-g9-client-agnostic-startup.log
docker logs "$PREFLIGHT" > "$startup_log" 2>&1 || true
grep -q 'AUTH_CONTROL_STATE_HOST_LOAD_PASS' "$startup_log" || { cat "$startup_log" >&2; echo 'CLIENT_AGNOSTIC_G9_HOST_STATE_LOAD_MISSING' >&2; exit 17; }
grep -q 'AUTH_CONTROL_STATE_HOST_REUSE_NO_WRITE' "$startup_log" || { cat "$startup_log" >&2; echo 'CLIENT_AGNOSTIC_G9_HOST_STATE_REUSE_MISSING' >&2; exit 18; }
! grep -q 'AUTH_CONTROL_STATE_HOST_SAVE_PASS' "$startup_log" || { cat "$startup_log" >&2; echo 'CLIENT_AGNOSTIC_G9_STARTUP_WROTE_AUTHORITY' >&2; exit 19; }
rm -f "$startup_log"

authority_after=$(sha256sum "$BUILD/.runtime-auth-security.enc" | awk '{print $1}')
[ "$authority_before" = "$authority_after" ] || { echo 'CLIENT_AGNOSTIC_G9_AUTHORITY_CHANGED_DURING_PREFLIGHT' >&2; exit 20; }
echo 'CLIENT_AGNOSTIC_G9_AUTHORITY_STABLE_PASS'

# Generic client-facing discovery and authorization preflight.
docker exec -i "$PREFLIGHT" node - <<'NODE'
const base='http://127.0.0.1:3002';
const unauth=await fetch(base+'/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2025-11-25','user-agent':'generic-mcp-client/1.0'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}})});
const challenge=String(unauth.headers.get('www-authenticate')||'');
const unauthBody=await unauth.text();
if(unauth.status!==401||!/Bearer/i.test(challenge)||!/resource_metadata=/i.test(challenge)||!unauthBody) throw new Error('generic_oauth_challenge_missing');
const metaRes=await fetch(base+'/.well-known/oauth-authorization-server');
if(!metaRes.ok) throw new Error('oauth_metadata_unavailable');
const meta=await metaRes.json();
if(!meta.registration_endpoint||!meta.code_challenge_methods_supported?.includes('S256')||!meta.token_endpoint_auth_methods_supported?.includes('none')) throw new Error('oauth_metadata_not_generic');
const regRes=await fetch(base+'/oauth/register',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({client_name:'Universal MCP Probe',redirect_uris:['https://client.example/callback'],application_type:'web',token_endpoint_auth_method:'none',grant_types:['authorization_code'],response_types:['code']})});
if(regRes.status!==201) throw new Error('dcr_failed_'+regRes.status);
const reg=await regRes.json();
if(!reg.client_id||reg.client_name!=='Universal MCP Probe'||reg.token_endpoint_auth_method!=='none') throw new Error('dcr_response_invalid');
const auth=new URL(base+'/oauth/authorize');
for(const [k,v] of Object.entries({response_type:'code',client_id:reg.client_id,redirect_uri:'https://client.example/callback',state:'generic-preflight',code_challenge:'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',code_challenge_method:'S256',scope:'mcp:tools'})) auth.searchParams.set(k,v);
const pageRes=await fetch(auth,{redirect:'manual'}); const page=await pageRes.text();
if(pageRes.status!==200) throw new Error('authorize_page_'+pageRes.status);
if(!/Universal MCP Probe/.test(page)||!/Send Telegram code/.test(page)) throw new Error('generic_owner_auth_ui_missing');
if(/Authorize Claude|CLAUDE_OAUTH_CLIENT_ID/i.test(page)) throw new Error('vendor_specific_ui_present');
const form=new URLSearchParams({response_type:'code',client_id:reg.client_id,redirect_uri:'https://client.example/callback',state:'generic-preflight',code_challenge:'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',code_challenge_method:'S256',scope:'mcp:tools'});
const denied=await fetch(base+'/oauth/authorize',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:form.toString(),redirect:'manual'});
if(denied.status!==401) throw new Error('owner_verification_not_enforced_'+denied.status);
const workforce=await fetch('http://workforce-production:8080/workplace/api/auth/status');
if(!workforce.ok) throw new Error('workforce_owner_auth_unreachable');
const w=await workforce.json(); if(w.authenticated!==false) throw new Error('unexpected_workforce_auth_state');
console.log('CLIENT_AGNOSTIC_G9_GENERIC_PREFLIGHT_PASS');
NODE

# Private functional registry is independent of public authorization boundary.
docker exec -i "$PREFLIGHT" node - <<'NODE'
const r=await fetch('http://127.0.0.1:3003/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2025-11-25'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(5000)});
const t=await r.text(); if(!r.ok||!t.includes('server_status')) throw new Error('private_registry_failed');
console.log('CLIENT_AGNOSTIC_G9_PRIVATE_REGISTRY_PASS');
NODE

docker rm -f "$PREFLIGHT" >/dev/null 2>&1 || true
trap - EXIT INT TERM

echo "CLIENT_AGNOSTIC_G9_PREBUILD_PASS image=$IMAGE id=$id base=$base_id generation=8 authority_hash=$authority_after flow=oauth-dcr-pkce-telegram-owner-auth release_not_started=true"
