import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-client-agnostic-oauth-patch.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');
if (source.includes('METATRON_CLIENT_AGNOSTIC_OAUTH_V1')) {
  console.log('CLIENT_AGNOSTIC_OAUTH_PATCH_ALREADY_APPLIED');
  process.exit(0);
}
for (const required of ['METATRON_MCP_CONTROL_STATE_INIT_V2','METATRON_SECURE_AUTH_UI_V2','function adminSession(req, secret)','function accessTokenAllowed(payload)','function recordAuthSession(payload, token)']) {
  if (!source.includes(required)) throw new Error('required auth substrate missing: ' + required);
}

function replaceBlock(startNeedle, endNeedle, replacement) {
  const start = source.indexOf(startNeedle);
  const end = source.indexOf(endNeedle, start + startNeedle.length);
  if (start < 0 || end < 0) throw new Error('function/block boundary missing: ' + startNeedle);
  source = source.slice(0, start) + replacement + source.slice(end + endNeedle.length);
}

// Remove the vendor-specific confidential OAuth specialization if present.
source = source.replace(/\/\/ METATRON_CLAUDE_CONFIDENTIAL_OAUTH_V1\nconst CLAUDE_OAUTH_CLIENT_ID = 'metatron-claude';\nconst CLAUDE_OAUTH_REDIRECT_URI = 'https:\/\/claude\.ai\/api\/mcp\/auth_callback';\nconst CLAUDE_OAUTH_CLIENT_SECRET_SHA256 = '[0-9a-f]{64}';/, '// METATRON_CLIENT_AGNOSTIC_OAUTH_V1');
if (!source.includes('METATRON_CLIENT_AGNOSTIC_OAUTH_V1')) {
  const anchor = "const RESOURCE_METADATA_URL = `${PUBLIC_ORIGIN}/.well-known/oauth-protected-resource`;";
  if (!source.includes(anchor)) throw new Error('resource metadata anchor missing');
  source = source.replace(anchor, `${anchor}\n// METATRON_CLIENT_AGNOSTIC_OAUTH_V1`);
}

const metaAnchor = "const ASSERTION_META_KEY = 'metatron/proxyAssertion';";
if (!source.includes(metaAnchor)) throw new Error('assertion meta anchor missing');
source = source.replace(metaAnchor, `${metaAnchor}\nconst PRINCIPAL_META_KEY = 'metatron/authenticatedPrincipal';\nconst SCOPES_META_KEY = 'metatron/oauthScopes';\nconst OWNER_CONSENT_COOKIE = 'metatron_owner_consent';\nconst OWNER_CONSENT_TTL_SECONDS = 12 * 60 * 60;\nconst WORKFORCE_AUTH_HOST = 'workforce-production';\nconst WORKFORCE_AUTH_PORT = 8080;`);

replaceBlock('function oauthMetadata(res) {', '\nfunction protectedResourceMetadata(res) {', `function oauthMetadata(res) {
  json(res, 200, {
    issuer: PUBLIC_ORIGIN,
    authorization_endpoint: \`\${PUBLIC_ORIGIN}/oauth/authorize\`,
    token_endpoint: \`\${PUBLIC_ORIGIN}/oauth/token\`,
    registration_endpoint: \`\${PUBLIC_ORIGIN}/oauth/register\`,
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none'],
    scopes_supported: ['mcp:tools'],
  }, { 'access-control-allow-origin': '*' });
}
function protectedResourceMetadata(res) {`);

replaceBlock('function identifyRequest(headers, secret, hashes = CLIENT_TOKEN_HASHES) {', '\nfunction authorizeBody', `function identifyRequest(headers, secret, hashes = CLIENT_TOKEN_HASHES) {
  const candidate = credentialCandidate(headers);
  if (!candidate.token) return { client: null, principal: null, scopes: [], source: candidate.source, digestPrefix: 'none', oauth: false };
  const staticClient = identifyStaticToken(candidate.token, hashes);
  if (staticClient) return { client: 'legacy:' + staticClient, principal: 'legacy-client:' + staticClient, scopes: ['mcp:tools'], source: candidate.source, digestPrefix: sha256(candidate.token).slice(0, 12), oauth: false };
  if (candidate.source === 'authorization') {
    const access = verifySigned(candidate.token, secret, 'access_token');
    if (access && accessTokenAllowed(access)) {
      const genericClient = typeof access.client_id === 'string' && access.client_id ? access.client_id : (access.sub === 'claude' ? 'legacy-oauth:claude' : '');
      const principal = typeof access.sub === 'string' && access.sub ? (access.sub === 'claude' ? 'founder' : access.sub) : '';
      const scope = typeof access.scope === 'string' ? access.scope : '';
      const audienceOk = !access.aud || access.aud === RESOURCE_URL;
      const issuerOk = !access.iss || access.iss === PUBLIC_ORIGIN;
      if (genericClient && principal && scope.split(/\\s+/).includes('mcp:tools') && audienceOk && issuerOk) {
        return { client: genericClient, principal, scopes: scope.split(/\\s+/).filter(Boolean), source: 'oauth-bearer', digestPrefix: sha256(candidate.token).slice(0, 12), oauth: true };
      }
    }
  }
  return { client: null, principal: null, scopes: [], source: candidate.source, digestPrefix: sha256(candidate.token).slice(0, 12), oauth: false };
}
function authorizeBody`);

replaceBlock('function authorizeBody(raw, identity, assertion) {', '\nfunction filteredHeaders', `function authorizeBody(raw, identity, assertion) {
  let parsed;
  try { parsed = JSON.parse(raw); }
  catch { return { body: raw, client: identity.client }; }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return { body: raw, client: identity.client };
  if (parsed.params && typeof parsed.params === 'object' && !Array.isArray(parsed.params)) {
    const meta = parsed.params._meta && typeof parsed.params._meta === 'object' && !Array.isArray(parsed.params._meta)
      ? { ...parsed.params._meta }
      : {};
    delete meta[VERIFIED_META_KEY];
    delete meta[ASSERTION_META_KEY];
    delete meta[PRINCIPAL_META_KEY];
    delete meta[SCOPES_META_KEY];
    if (identity.client) {
      if (typeof assertion !== 'string' || assertion.length < 32) throw new Error('proxy_assertion_unavailable');
      meta[VERIFIED_META_KEY] = identity.client;
      meta[ASSERTION_META_KEY] = assertion;
      meta[PRINCIPAL_META_KEY] = identity.principal || ('legacy-client:' + identity.client);
      meta[SCOPES_META_KEY] = Array.isArray(identity.scopes) ? identity.scopes.join(' ') : 'mcp:tools';
    }
    parsed.params = { ...parsed.params, _meta: meta };
  }
  return { body: JSON.stringify(parsed), client: identity.client };
}
function filteredHeaders`);

replaceBlock('function accessTokenAllowed(payload) {', '\nfunction recordAuthSession(payload, token) {', `function accessTokenAllowed(payload) {
  if (!payload || typeof payload.jti !== 'string') return false;
  const clientKey = String(payload.client_id || payload.sub || '');
  if (!clientKey) return false;
  const revokedBefore = clientRevokedBefore.get(clientKey) || 0;
  if ((Number(payload.iat) || 0) <= revokedBefore) return false;
  const session = authSessions.get(payload.jti);
  if (!session) return process.env.AUTH_PROXY_SELF_TEST === '1' && payload.sub === 'claude';
  return !session.revoked && session.client === clientKey && session.expiresAt > Math.floor(Date.now()/1000);
}
function recordAuthSession(payload, token) {`);

// Replace the recordAuthSession body while preserving the next helper boundary.
const recordStart = source.indexOf('function recordAuthSession(payload, token) {');
const recordEnd = source.indexOf('\nfunction ', recordStart + 10);
if (recordStart < 0 || recordEnd < 0) throw new Error('recordAuthSession boundary missing');
source = source.slice(0, recordStart) + `function recordAuthSession(payload, token) {
  const clientKey = String(payload.client_id || payload.sub || '');
  if (!clientKey) throw new Error('oauth_client_identity_missing');
  authSessions.set(payload.jti, {
    jti: payload.jti,
    client: clientKey,
    scope: payload.scope || 'mcp:tools',
    issuedAt: payload.iat,
    expiresAt: payload.exp,
    digestPrefix: sha256(token).slice(0, 12),
    revoked: false,
  });
  saveSecurityState();
}
` + source.slice(recordEnd + 1);

// Generic owner-consent and private Workforce Telegram OTP bridge.
const parseAnchor = 'function parseForm(raw) {';
const parseIndex = source.indexOf(parseAnchor);
if (parseIndex < 0) throw new Error('parseForm anchor missing');
const ownerHelpers = `function cookieValue(req, name) {
  const raw = String(req.headers.cookie || '');
  for (const part of raw.split(';')) {
    const trimmed = part.trim();
    const eq = trimmed.indexOf('=');
    if (eq <= 0) continue;
    if (trimmed.slice(0, eq) === name) return decodeURIComponent(trimmed.slice(eq + 1));
  }
  return '';
}
function ownerConsentSession(req, secret) {
  const payload = verifySigned(cookieValue(req, OWNER_CONSENT_COOKIE), secret, 'owner_consent');
  return payload?.sub === 'founder' ? payload : null;
}
function setOwnerConsentCookie(res, secret) {
  const now = Math.floor(Date.now()/1000);
  const token = mintSigned({kind:'owner_consent',sub:'founder',iat:now,exp:now+OWNER_CONSENT_TTL_SECONDS,jti:randomBytes(12).toString('base64url')}, secret);
  res.setHeader('set-cookie', OWNER_CONSENT_COOKIE + '=' + encodeURIComponent(token) + '; Max-Age=' + OWNER_CONSENT_TTL_SECONDS + '; Path=/oauth/; HttpOnly; Secure; SameSite=Lax');
}
function ownerAlreadyAuthenticated(req, secret) {
  return !!ownerConsentSession(req, secret) || !!adminSession(req, secret);
}
function workforceAuthPost(path, body, callback) {
  const payload = Buffer.from(JSON.stringify(body || {}), 'utf8');
  const request = http.request({hostname:WORKFORCE_AUTH_HOST,port:WORKFORCE_AUTH_PORT,path,method:'POST',headers:{'content-type':'application/json','content-length':String(payload.length)}}, response => {
    const chunks=[];
    response.on('data', c=>chunks.push(c));
    response.on('end', ()=>callback(null, response.statusCode || 500, Buffer.concat(chunks).toString('utf8')));
  });
  request.setTimeout(5000, ()=>request.destroy(new Error('workforce_auth_timeout')));
  request.on('error', error=>callback(error, 503, ''));
  request.write(payload); request.end();
}
function registeredClient(clientId, redirectUri, secret) {
  const payload = verifySigned(clientId, secret, 'oauth_client');
  if (!payload || !Array.isArray(payload.redirect_uris) || !payload.redirect_uris.includes(redirectUri)) return null;
  return payload;
}

`;
source = source.slice(0, parseIndex) + ownerHelpers + source.slice(parseIndex);

replaceBlock("function authorizePage(params, error = '') {", '\nfunction validRedirect(uri) {', `function authorizePage(params, error = '', notice = '', ownerAuthenticated = false, clientLabel = 'MCP client') {
  const hidden = ['client_id','redirect_uri','state','code_challenge','code_challenge_method','scope','response_type'].map(k => \`<input type="hidden" name="\${k}" value="\${esc(params.get(k) || '')}">\`).join('');
  const errorBlock = error ? \`<p style="color:#b42318"><strong>Authorization failed:</strong> \${esc(error)}</p>\` : '';
  const noticeBlock = notice ? \`<p style="color:#067647">\${esc(notice)}</p>\` : '';
  const ownerBlock = ownerAuthenticated
    ? '<p>Owner identity already verified for this browser session.</p>'
    : \`<form method="post" action="/oauth/owner/request-code">\${hidden}<button type="submit">Send Telegram code</button></form><form method="post" action="/oauth/authorize">\${hidden}<label style="display:block;margin-top:16px">Telegram verification code</label><input name="owner_code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required style="display:block;width:100%;box-sizing:border-box;padding:12px;margin:8px 0 16px"><button type="submit">Authorize MCP client</button></form>\`;
  const authorizeOnly = ownerAuthenticated ? \`<form method="post" action="/oauth/authorize">\${hidden}<button type="submit">Authorize MCP client</button></form>\` : '';
  return \`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Metatron Authorization</title></head><body style="font-family:-apple-system,BlinkMacSystemFont,sans-serif;max-width:560px;margin:48px auto;padding:0 20px"><h2>Authorize \${esc(clientLabel)}</h2><p>Grant this MCP client access to protected Metatron tools.</p><p><strong>Flow:</strong> OAuth 2.0 authorization code + PKCE S256</p>\${errorBlock}\${noticeBlock}\${ownerBlock}\${authorizeOnly}<p style="color:#667085;font-size:13px">Owner verification uses the existing Metatron Telegram identity and is independent of the MCP client vendor.</p></body></html>\`;
}
function validRedirect(uri) {`);

replaceBlock('function handleRegister(req, res, raw) {', '\nfunction handleAuthorizeGet', `function handleRegister(req, res, raw, secret) {
  let body = {};
  try { body = JSON.parse(raw || '{}'); } catch { return json(res, 400, { error: 'invalid_client_metadata' }); }
  const redirects = Array.isArray(body.redirect_uris) ? [...new Set(body.redirect_uris.filter(validRedirect))].slice(0, 10) : [];
  if (!redirects.length) return json(res, 400, { error: 'invalid_redirect_uri' });
  if (body.token_endpoint_auth_method && body.token_endpoint_auth_method !== 'none') return json(res, 400, { error: 'invalid_client_metadata' });
  const applicationType = body.application_type === 'native' ? 'native' : 'web';
  const clientName = typeof body.client_name === 'string' && body.client_name.trim() ? body.client_name.trim().slice(0, 120) : 'MCP client';
  const now = Math.floor(Date.now()/1000);
  const clientId = mintSigned({kind:'oauth_client',client_name:clientName,redirect_uris:redirects,application_type:applicationType,iat:now,exp:now+(365*24*60*60),jti:randomBytes(12).toString('base64url')}, secret);
  json(res, 201, {client_id:clientId,client_id_issued_at:now,redirect_uris:redirects,client_name:clientName,application_type:applicationType,token_endpoint_auth_method:'none',grant_types:['authorization_code'],response_types:['code']}, {'cache-control':'no-store'});
}
function handleAuthorizeGet`);

replaceBlock('function handleAuthorizeGet(req, res) {', '\nfunction handleAuthorizePost', `function handleAuthorizeGet(req, res, secret) {
  const u = new URL(req.url, PUBLIC_ORIGIN);
  const p = u.searchParams;
  const redirectUri = p.get('redirect_uri');
  if (p.get('response_type') !== 'code') return html(res, 400, authorizePage(p, 'Unsupported response_type'));
  if (!p.get('client_id')) return html(res, 400, authorizePage(p, 'Missing client_id'));
  const registration = registeredClient(p.get('client_id'), redirectUri, secret);
  if (!registration) return html(res, 400, authorizePage(p, 'Unknown client or redirect URI'));
  if (!p.get('code_challenge') || (p.get('code_challenge_method') || 'S256') !== 'S256') return html(res, 400, authorizePage(p, 'PKCE S256 is required'));
  html(res, 200, authorizePage(p, '', '', ownerAlreadyAuthenticated(req, secret), registration.client_name || 'MCP client'));
}
function handleAuthorizePost`);

// Replace authorize POST through the token handler boundary. This also removes any confidential-client helper.
let postStart = source.indexOf('function handleAuthorizePost');
let tokenBoundary = source.indexOf('\nfunction claudeClientCredentials', postStart);
if (tokenBoundary < 0) tokenBoundary = source.indexOf('\nfunction handleToken', postStart);
if (postStart < 0 || tokenBoundary < 0) throw new Error('authorize/token boundary missing');
const authorizePost = `function grantAuthorization(p, res, secret, registration) {
  const redirectUri = p.get('redirect_uri');
  const now = Math.floor(Date.now()/1000);
  const code = mintSigned({kind:'auth_code',sub:'founder',client_id:p.get('client_id'),client_name:registration.client_name || 'MCP client',redirect_uri:redirectUri,code_challenge:p.get('code_challenge'),scope:p.get('scope') || 'mcp:tools',aud:RESOURCE_URL,iss:PUBLIC_ORIGIN,iat:now,exp:now+AUTH_CODE_TTL_SECONDS,jti:randomBytes(12).toString('base64url')}, secret);
  const dest = new URL(redirectUri);
  dest.searchParams.set('code', code);
  if (p.get('state')) dest.searchParams.set('state', p.get('state'));
  dest.searchParams.set('iss', PUBLIC_ORIGIN);
  res.writeHead(302, {location:dest.toString(),'cache-control':'no-store'}); res.end();
}
function handleOwnerRequestCode(req, res, raw, secret) {
  const p = parseForm(raw);
  if (!checkRateLimit(clientIp(req))) return html(res, 429, authorizePage(p, 'Too many verification requests. Try again later.'));
  const registration = registeredClient(p.get('client_id'), p.get('redirect_uri'), secret);
  if (!registration || !p.get('code_challenge') || (p.get('code_challenge_method') || 'S256') !== 'S256') return html(res, 400, authorizePage(p, 'Invalid authorization request'));
  workforceAuthPost('/workplace/api/auth/request-code', {}, (error, status) => {
    if (error || (status !== 202 && status !== 200)) return html(res, status === 429 ? 429 : 503, authorizePage(p, status === 429 ? 'A code was requested too recently' : 'Telegram verification is temporarily unavailable', '', false, registration.client_name || 'MCP client'));
    return html(res, 200, authorizePage(p, '', 'Telegram code sent. It expires in 5 minutes.', false, registration.client_name || 'MCP client'));
  });
}
function handleAuthorizePost(req, res, raw, secret) {
  const p = parseForm(raw);
  if (!checkRateLimit(clientIp(req))) return html(res, 429, authorizePage(p, 'Too many verification attempts. Try again later.'));
  const redirectUri = p.get('redirect_uri');
  const registration = registeredClient(p.get('client_id'), redirectUri, secret);
  if (!registration || !p.get('code_challenge') || (p.get('code_challenge_method') || 'S256') !== 'S256') return html(res, 400, authorizePage(p, 'Invalid authorization request'));
  if (ownerAlreadyAuthenticated(req, secret)) return grantAuthorization(p, res, secret, registration);
  const ownerCode = String(p.get('owner_code') || '').trim();
  if (!/^\\d{6}$/.test(ownerCode)) return html(res, 401, authorizePage(p, 'Telegram verification required', '', false, registration.client_name || 'MCP client'));
  workforceAuthPost('/workplace/api/auth/verify', {code:ownerCode}, (error, status) => {
    if (error || status !== 200) return html(res, 401, authorizePage(p, 'Invalid or expired Telegram code', '', false, registration.client_name || 'MCP client'));
    setOwnerConsentCookie(res, secret);
    return grantAuthorization(p, res, secret, registration);
  });
}
`;
source = source.slice(0, postStart) + authorizePost + source.slice(tokenBoundary + 1);

let tokenStart = source.indexOf('function claudeClientCredentials');
if (tokenStart >= 0) {
  const handleTokenStart = source.indexOf('\nfunction handleToken(', tokenStart);
  if (handleTokenStart < 0) throw new Error('confidential token helper boundary missing');
  source = source.slice(0, tokenStart) + source.slice(handleTokenStart + 1);
}
replaceBlock('function handleToken(', '\nfunction unauthorizedForOAuth(res) {', `function handleToken(req, res, raw, secret) {
  const p = parseForm(raw);
  if (p.get('grant_type') !== 'authorization_code') return json(res, 400, {error:'unsupported_grant_type'});
  const payload = verifySigned(p.get('code'), secret, 'auth_code');
  if (!payload) return json(res, 400, {error:'invalid_grant'});
  const registration = registeredClient(p.get('client_id'), p.get('redirect_uri'), secret);
  if (!registration || payload.client_id !== p.get('client_id') || payload.redirect_uri !== p.get('redirect_uri')) return json(res, 400, {error:'invalid_grant'});
  const verifier = p.get('code_verifier') || '';
  const challenge = createHash('sha256').update(verifier, 'utf8').digest('base64url');
  if (!safeStringEqual(challenge, payload.code_challenge)) return json(res, 400, {error:'invalid_grant'});
  const now = Math.floor(Date.now()/1000);
  const tokenPayload = {kind:'access_token',iss:PUBLIC_ORIGIN,aud:RESOURCE_URL,sub:'founder',client_id:payload.client_id,scope:payload.scope || 'mcp:tools',iat:now,exp:now+ACCESS_TOKEN_TTL_SECONDS,jti:randomBytes(12).toString('base64url')};
  const accessToken = mintSigned(tokenPayload, secret);
  recordAuthSession(tokenPayload, accessToken);
  json(res, 200, {access_token:accessToken,token_type:'Bearer',expires_in:ACCESS_TOKEN_TTL_SECONDS,scope:tokenPayload.scope}, {'cache-control':'no-store'});
}
function unauthorizedForOAuth(res) {`);

// Make the MCP HTTP boundary authentication-required for every client, independent of User-Agent.
source = source.replace("if (!identity.client && /Claude-User/i.test(ua)) return unauthorizedForOAuth(res);", "if (!identity.client) return unauthorizedForOAuth(res);");
if (!source.includes('if (!identity.client) return unauthorizedForOAuth(res);')) throw new Error('strict oauth boundary missing');

// Generic client IDs are opaque signed Base64URL values; remove the legacy vendor-label restriction.
const legacyRevokeClient = `const client = String(p.get('client') || '').trim().toLowerCase();
    if (!/^[a-z0-9._-]{1,64}$/.test(client)) return html(res, 400, securityDashboard(session, 'Invalid client identifier.'));`;
const genericRevokeClient = `const client = String(p.get('client') || '').trim();
    if (!/^[A-Za-z0-9._-]{1,2048}$/.test(client)) return html(res, 400, securityDashboard(session, 'Invalid client identifier.'));`;
if (source.includes(legacyRevokeClient)) source = source.replace(legacyRevokeClient, genericRevokeClient);

// Route generic OAuth endpoints.
source = source.replace("if (req.method === 'POST' && url.pathname === '/oauth/register') return handleRegister(req, res, raw);", "if (req.method === 'POST' && url.pathname === '/oauth/register') return handleRegister(req, res, raw, assertion);");
source = source.replace("if (req.method === 'GET' && url.pathname === '/oauth/authorize') return handleAuthorizeGet(req, res);", "if (req.method === 'GET' && url.pathname === '/oauth/authorize') return handleAuthorizeGet(req, res, assertion);");
if (!source.includes("url.pathname === '/oauth/owner/request-code'")) {
  const authorizePostRoute = "if (req.method === 'POST' && url.pathname === '/oauth/authorize') return handleAuthorizePost(req, res, raw, assertion);";
  if (!source.includes(authorizePostRoute)) throw new Error('authorize POST route anchor missing');
  source = source.replace(authorizePostRoute, "if (req.method === 'POST' && url.pathname === '/oauth/owner/request-code') return handleOwnerRequestCode(req, res, raw, assertion);\n  " + authorizePostRoute);
}
source = source.replace("if (req.method === 'POST' && url.pathname === '/oauth/token') return handleToken(res, raw, assertion);", "if (req.method === 'POST' && url.pathname === '/oauth/token') return handleToken(req, res, raw, assertion);");

// Generic OAuth self-test: one arbitrary client_id and principal must pass without vendor names.
const selfTestLogCandidates = [
  "console.log('AUTH_PROXY_ACCEPTANCE_PASS credential_forms=4 oauth=enabled founder_admin=enabled revocation=enabled');",
  "console.log('AUTH_PROXY_ACCEPTANCE_PASS credential_forms=4 oauth=enabled');"
];
let selfTestLog = selfTestLogCandidates.find(x => source.includes(x));
if (!selfTestLog) throw new Error('self-test log anchor missing');
const genericSelfTest = `const genericNow = Math.floor(Date.now()/1000);
  const genericClientId = mintSigned({kind:'oauth_client',client_name:'Generic MCP Test Client',redirect_uris:['https://client.example/callback'],application_type:'web',iat:genericNow,exp:genericNow+300,jti:'generic-client'}, assertion);
  const genericPayload = {kind:'access_token',iss:PUBLIC_ORIGIN,aud:RESOURCE_URL,sub:'founder',client_id:genericClientId,scope:'mcp:tools',iat:genericNow,exp:genericNow+60,jti:'generic-oauth-test'};
  authSessions.set(genericPayload.jti,{jti:genericPayload.jti,client:genericClientId,scope:'mcp:tools',issuedAt:genericPayload.iat,expiresAt:genericPayload.exp,digestPrefix:'generic-test',revoked:false});
  const genericToken = mintSigned(genericPayload, assertion);
  const genericIdentity = identifyRequest({authorization:'Bearer '+genericToken}, assertion, hashes);
  if (genericIdentity.client !== genericClientId || genericIdentity.principal !== 'founder') throw new Error('generic_oauth_identity_failed');
  const genericBody = JSON.parse(authorizeBody(JSON.stringify({jsonrpc:'2.0',id:9,method:'tools/list',params:{_meta:{[VERIFIED_META_KEY]:'spoof',[PRINCIPAL_META_KEY]:'spoof',[ASSERTION_META_KEY]:'spoof'}}}), genericIdentity, assertion).body);
  if (genericBody.params._meta[VERIFIED_META_KEY] !== genericClientId || genericBody.params._meta[PRINCIPAL_META_KEY] !== 'founder' || genericBody.params._meta[ASSERTION_META_KEY] !== assertion) throw new Error('generic_proxy_meta_failed');
  authSessions.delete(genericPayload.jti);
  console.log('CLIENT_AGNOSTIC_OAUTH_ACCEPTANCE_PASS');
  `;
source = source.replace(selfTestLog, genericSelfTest + selfTestLog);

for (const marker of [
  'METATRON_CLIENT_AGNOSTIC_OAUTH_V1',
  "registration_endpoint: `${PUBLIC_ORIGIN}/oauth/register`",
  "token_endpoint_auth_methods_supported: ['none']",
  'WORKFORCE_AUTH_HOST',
  '/workplace/api/auth/request-code',
  '/workplace/api/auth/verify',
  'metatron/authenticatedPrincipal',
  'CLIENT_AGNOSTIC_OAUTH_ACCEPTANCE_PASS',
  'if (!identity.client) return unauthorizedForOAuth(res);'
]) if (!source.includes(marker)) throw new Error('client-agnostic invariant missing: ' + marker);
if (source.includes("token_endpoint_auth_methods_supported: ['client_secret_basic'")) throw new Error('confidential-client auth still advertised');
if (source.includes("CLAUDE_OAUTH_CLIENT_ID")) throw new Error('vendor-specific OAuth client constant remains');

fs.writeFileSync(target, source);
console.log('CLIENT_AGNOSTIC_OAUTH_PATCH_PASS');
