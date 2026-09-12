import { createHash, createHmac, randomBytes } from 'node:crypto';

const BASE = 'http://127.0.0.1:3002';
const PUBLIC_ORIGIN = 'https://ssh.metatron.vn';
const RESOURCE_URL = `${PUBLIC_ORIGIN}/mcp`;
const ASSERTION = String(process.env.METATRON_PROXY_ASSERTION || '');
if (ASSERTION.length < 32) throw new Error('isolated_test_assertion_missing');

function b64url(input) { return Buffer.from(input).toString('base64url'); }
function hmac(value) { return createHmac('sha256', ASSERTION).update(value, 'utf8').digest('base64url'); }
function mintSigned(payload) {
  const encoded = b64url(JSON.stringify(payload));
  return `${encoded}.${hmac(encoded)}`;
}
async function request(path, options = {}) {
  const r = await fetch(BASE + path, options);
  const text = await r.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return {r, body, text};
}
async function bootstrap() {
  const redirectUri = 'https://client.example/g10-callback';
  const dcr = await request('/oauth/register', {method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({client_name:'Metatron G10 Acceptance Client',redirect_uris:[redirectUri],application_type:'web',token_endpoint_auth_method:'none',grant_types:['authorization_code','refresh_token'],response_types:['code']})});
  if (dcr.r.status !== 201 || !dcr.body?.client_id) throw new Error('dcr_failed');
  const clientId = dcr.body.client_id;
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier, 'utf8').digest('base64url');
  const now = Math.floor(Date.now()/1000);
  const code = mintSigned({kind:'auth_code',sub:'founder',client_id:clientId,client_name:'Metatron G10 Acceptance Client',redirect_uri:redirectUri,code_challenge:challenge,scope:'mcp:tools offline_access',aud:RESOURCE_URL,iss:PUBLIC_ORIGIN,iat:now,exp:now+300,jti:randomBytes(12).toString('base64url')});
  const token = await request('/oauth/token', {method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'authorization_code',code,client_id:clientId,redirect_uri:redirectUri,code_verifier:verifier}).toString()});
  if (token.r.status !== 200 || !token.body?.access_token || !token.body?.refresh_token) throw new Error('token_exchange_failed');
  const csrf = randomBytes(18).toString('base64url');
  const adminCookie = mintSigned({kind:'founder_admin',sub:'founder',csrf,iat:now,exp:now+900,jti:randomBytes(12).toString('base64url')});
  process.stdout.write(JSON.stringify({clientId,redirectUri,accessToken:token.body.access_token,refreshToken:token.body.refresh_token,adminCookie,csrf}));
}
async function mcp() {
  const token = process.argv[3];
  const method = process.argv[4] || 'tools/list';
  const payload = method === 'initialize'
    ? {jsonrpc:'2.0',id:1,method:'initialize',params:{protocolVersion:'2025-11-25',capabilities:{},clientInfo:{name:'g10-acceptance',version:'1'}}}
    : {jsonrpc:'2.0',id:2,method:'tools/list',params:{}};
  const {r,text} = await request('/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2025-11-25','authorization':'Bearer '+token},body:JSON.stringify(payload)});
  process.stdout.write(JSON.stringify({status:r.status,ok:r.ok,hasServerStatus:text.includes('server_status'),hasJsonRpc:text.includes('jsonrpc')}));
}
async function refresh() {
  const refreshToken=process.argv[3], clientId=process.argv[4], redirectUri=process.argv[5];
  const {r,body}=await request('/oauth/token',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams({grant_type:'refresh_token',refresh_token:refreshToken,client_id:clientId,redirect_uri:redirectUri}).toString()});
  process.stdout.write(JSON.stringify({status:r.status,body}));
}
async function revoke() {
  const adminCookie=process.argv[3], csrf=process.argv[4], clientId=process.argv[5];
  const r=await fetch(BASE+'/founder/security/revoke-client',{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded','cookie':'metatron_founder_admin='+encodeURIComponent(adminCookie)},body:new URLSearchParams({csrf,client:clientId}).toString()});
  await r.arrayBuffer();
  process.stdout.write(JSON.stringify({status:r.status}));
}
async function metadata() {
  const a=await request('/.well-known/oauth-authorization-server');
  const p=await request('/.well-known/oauth-protected-resource');
  process.stdout.write(JSON.stringify({authStatus:a.r.status,protectedStatus:p.r.status,refresh:a.body?.grant_types_supported?.includes('refresh_token')===true,authCode:a.body?.grant_types_supported?.includes('authorization_code')===true,pkce:a.body?.code_challenge_methods_supported?.includes('S256')===true,dcr:typeof a.body?.registration_endpoint==='string',resource:p.body?.resource}));
}
async function unauth() {
  const r=await fetch(BASE+'/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2025-11-25'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}})});
  await r.arrayBuffer();
  process.stdout.write(JSON.stringify({status:r.status,wwwAuthenticate:String(r.headers.get('www-authenticate')||'')}));
}

const mode=process.argv[2];
if(mode==='bootstrap') await bootstrap();
else if(mode==='mcp') await mcp();
else if(mode==='refresh') await refresh();
else if(mode==='revoke') await revoke();
else if(mode==='metadata') await metadata();
else if(mode==='unauth') await unauth();
else throw new Error('unknown_mode');
