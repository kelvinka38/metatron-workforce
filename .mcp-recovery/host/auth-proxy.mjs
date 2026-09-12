import http from 'node:http';
import { createHash, createHmac, timingSafeEqual, randomBytes } from 'node:crypto';

const LISTEN_PORT = 3002;
const UPSTREAM_HOST = '127.0.0.1';
const UPSTREAM_PORT = 3003;
const MAX_BODY_BYTES = 2 * 1024 * 1024;
const VERIFIED_META_KEY = 'metatron/verifiedClient';
const ASSERTION_META_KEY = 'metatron/proxyAssertion';
const BOUNDED_CLIENT_PROTOCOL = '2025-11-25';
const PUBLIC_ORIGIN = 'https://ssh.metatron.vn';
const RESOURCE_URL = `${PUBLIC_ORIGIN}/mcp`;
const RESOURCE_METADATA_URL = `${PUBLIC_ORIGIN}/.well-known/oauth-protected-resource`;
const ACCESS_CODE_HASH = 'e4ccda459f5d0ee60fb9e8998b084cbf5979699134dfa81013de07af75a465b1';
const ACCESS_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60;
const AUTH_CODE_TTL_SECONDS = 5 * 60;
const ATTEMPT_WINDOW_MS = 15 * 60 * 1000;
const MAX_ATTEMPTS_PER_WINDOW = 5;
const authAttempts = new Map();

// Legacy/static credentials remain supported. Raw credentials are never stored.
const CLIENT_TOKEN_HASHES = new Map([
  ['gemini', 'eb359965b43cee6949e4180b6ab6db67a0d56a3c2742ca251b131fac56b3f724'],
  ['claude', '2ee64b11b4e0265185b612fb29f3ebdd4cb13594de8f503eba0b47f441d685ba'],
]);

function sha256(value) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}
function b64url(input) {
  return Buffer.from(input).toString('base64url');
}
function fromB64url(input) {
  return Buffer.from(input, 'base64url').toString('utf8');
}
function safeHexEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  try { return timingSafeEqual(Buffer.from(a, 'hex'), Buffer.from(b, 'hex')); }
  catch { return false; }
}
function safeStringEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const aa = Buffer.from(a);
  const bb = Buffer.from(b);
  if (aa.length !== bb.length) return false;
  return timingSafeEqual(aa, bb);
}
function hmac(value, secret) {
  return createHmac('sha256', secret).update(value, 'utf8').digest('base64url');
}
function mintSigned(payload, secret) {
  const encoded = b64url(JSON.stringify(payload));
  return `${encoded}.${hmac(encoded, secret)}`;
}
function verifySigned(token, secret, expectedKind) {
  if (typeof token !== 'string') return null;
  const parts = token.split('.');
  if (parts.length !== 2) return null;
  const [encoded, signature] = parts;
  if (!safeStringEqual(signature, hmac(encoded, secret))) return null;
  try {
    const payload = JSON.parse(fromB64url(encoded));
    if (!payload || payload.kind !== expectedKind || typeof payload.exp !== 'number') return null;
    if (payload.exp < Math.floor(Date.now() / 1000)) return null;
    return payload;
  } catch { return null; }
}
function identifyStaticToken(token, hashes = CLIENT_TOKEN_HASHES) {
  if (typeof token !== 'string' || token.length === 0) return null;
  const digest = sha256(token);
  for (const [client, expected] of hashes.entries()) {
    if (safeHexEqual(digest, expected)) return client;
  }
  return null;
}
function bearerToken(header) {
  if (typeof header !== 'string') return null;
  const match = /^Bearer\s+([^\s]+)$/i.exec(header.trim());
  return match ? match[1] : null;
}
function credentialCandidate(headers) {
  const bearer = bearerToken(headers.authorization);
  if (bearer) return { source: 'authorization', token: bearer };
  if (typeof headers['x-api-key'] === 'string' && headers['x-api-key']) return { source: 'x-api-key', token: headers['x-api-key'] };
  if (typeof headers['x-metatron-client-token'] === 'string' && headers['x-metatron-client-token']) return { source: 'x-metatron-client-token', token: headers['x-metatron-client-token'] };
  return { source: 'none', token: null };
}
function identifyRequest(headers, secret, hashes = CLIENT_TOKEN_HASHES) {
  const candidate = credentialCandidate(headers);
  if (!candidate.token) return { client: null, source: candidate.source, digestPrefix: 'none', oauth: false };
  const staticClient = identifyStaticToken(candidate.token, hashes);
  if (staticClient) return { client: staticClient, source: candidate.source, digestPrefix: sha256(candidate.token).slice(0, 12), oauth: false };
  if (candidate.source === 'authorization') {
    const access = verifySigned(candidate.token, secret, 'access_token');
    if (access?.sub === 'claude') return { client: 'claude', source: 'oauth-bearer', digestPrefix: sha256(candidate.token).slice(0, 12), oauth: true };
  }
  return { client: null, source: candidate.source, digestPrefix: sha256(candidate.token).slice(0, 12), oauth: false };
}
function authorizeBody(raw, identity, assertion) {
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
    if (identity.client) {
      if (typeof assertion !== 'string' || assertion.length < 32) throw new Error('proxy_assertion_unavailable');
      meta[VERIFIED_META_KEY] = identity.client;
      meta[ASSERTION_META_KEY] = assertion;
    }
    parsed.params = { ...parsed.params, _meta: meta };
  }
  return { body: JSON.stringify(parsed), client: identity.client };
}
function filteredHeaders(headers, bodyLength, verifiedClient) {
  const out = {};
  for (const [key, value] of Object.entries(headers)) {
    const lower = key.toLowerCase();
    if (['host', 'authorization', 'x-api-key', 'x-metatron-client-token', 'content-length', 'connection', 'transfer-encoding'].includes(lower)) continue;
    if (value !== undefined) out[key] = value;
  }
  out.host = `${UPSTREAM_HOST}:${UPSTREAM_PORT}`;
  out['content-length'] = String(bodyLength);
  if (verifiedClient) out['mcp-protocol-version'] = BOUNDED_CLIENT_PROTOCOL;
  return out;
}
function json(res, status, body, headers = {}) {
  const data = Buffer.from(JSON.stringify(body), 'utf8');
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': String(data.length), ...headers });
  res.end(data);
}
function html(res, status, body) {
  const data = Buffer.from(body, 'utf8');
  res.writeHead(status, { 'content-type': 'text/html; charset=utf-8', 'content-length': String(data.length), 'cache-control': 'no-store' });
  res.end(data);
}
function oauthMetadata(res) {
  json(res, 200, {
    issuer: PUBLIC_ORIGIN,
    authorization_endpoint: `${PUBLIC_ORIGIN}/oauth/authorize`,
    token_endpoint: `${PUBLIC_ORIGIN}/oauth/token`,
    registration_endpoint: `${PUBLIC_ORIGIN}/oauth/register`,
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none'],
    scopes_supported: ['mcp:tools'],
  }, { 'access-control-allow-origin': '*' });
}
function protectedResourceMetadata(res) {
  json(res, 200, {
    resource: RESOURCE_URL,
    authorization_servers: [PUBLIC_ORIGIN],
    scopes_supported: ['mcp:tools'],
    bearer_methods_supported: ['header'],
    resource_name: 'Metatron MCP',
  }, { 'access-control-allow-origin': '*' });
}
function clientIp(req) {
  const cf = req.headers['cf-connecting-ip'];
  return typeof cf === 'string' && cf ? cf : (req.socket.remoteAddress || 'unknown');
}
function checkRateLimit(ip) {
  const now = Date.now();
  const current = authAttempts.get(ip);
  if (!current || now - current.start > ATTEMPT_WINDOW_MS) {
    authAttempts.set(ip, { start: now, count: 1 });
    return true;
  }
  current.count += 1;
  return current.count <= MAX_ATTEMPTS_PER_WINDOW;
}
function authorizePage(params, error = '') {
  const esc = value => String(value ?? '').replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
  const hidden = ['client_id','redirect_uri','state','code_challenge','code_challenge_method','scope'].map(k => `<input type="hidden" name="${k}" value="${esc(params.get(k) || '')}">`).join('');
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Authorize Metatron</title></head><body style="font-family:-apple-system,BlinkMacSystemFont,sans-serif;max-width:560px;margin:48px auto;padding:0 20px"><h2>Authorize Claude → Metatron</h2><p>This grants Claude access to the protected Metatron MCP tools for your account.</p>${error ? `<p style="color:#b00020">${esc(error)}</p>` : ''}<form method="post" action="/oauth/authorize">${hidden}<label>Metatron access code</label><input name="access_code" type="password" autocomplete="one-time-code" required style="display:block;width:100%;box-sizing:border-box;padding:12px;margin:8px 0 16px"><button type="submit" style="padding:12px 18px">Authorize</button></form></body></html>`;
}
function validRedirect(uri) {
  if (typeof uri !== 'string' || uri.length > 2048) return false;
  try {
    const u = new URL(uri);
    return u.protocol === 'https:' || u.hostname === 'localhost' || u.hostname === '127.0.0.1';
  } catch { return false; }
}
function parseForm(raw) {
  return new URLSearchParams(raw);
}
function handleRegister(req, res, raw) {
  let body = {};
  try { body = JSON.parse(raw || '{}'); } catch { return json(res, 400, { error: 'invalid_client_metadata' }); }
  const redirects = Array.isArray(body.redirect_uris) ? body.redirect_uris.filter(validRedirect) : [];
  if (!redirects.length) return json(res, 400, { error: 'invalid_redirect_uri' });
  const clientId = `metatron-${sha256(JSON.stringify(redirects)).slice(0, 24)}`;
  json(res, 201, {
    client_id: clientId,
    client_id_issued_at: Math.floor(Date.now() / 1000),
    redirect_uris: redirects,
    token_endpoint_auth_method: 'none',
    grant_types: ['authorization_code'],
    response_types: ['code'],
  });
}
function handleAuthorizeGet(req, res) {
  const u = new URL(req.url, PUBLIC_ORIGIN);
  const p = u.searchParams;
  if (p.get('response_type') !== 'code') return html(res, 400, authorizePage(p, 'Unsupported response_type'));
  if (!p.get('client_id')) return html(res, 400, authorizePage(p, 'Missing client_id'));
  if (!validRedirect(p.get('redirect_uri'))) return html(res, 400, authorizePage(p, 'Invalid redirect_uri'));
  if (!p.get('code_challenge') || (p.get('code_challenge_method') || 'S256') !== 'S256') return html(res, 400, authorizePage(p, 'PKCE S256 is required'));
  html(res, 200, authorizePage(p));
}
function handleAuthorizePost(req, res, raw, secret) {
  const p = parseForm(raw);
  if (!checkRateLimit(clientIp(req))) return html(res, 429, authorizePage(p, 'Too many attempts. Try again later.'));
  if (!safeHexEqual(sha256(p.get('access_code') || ''), ACCESS_CODE_HASH)) return html(res, 403, authorizePage(p, 'Invalid access code.'));
  const redirectUri = p.get('redirect_uri');
  if (!validRedirect(redirectUri) || !p.get('client_id') || !p.get('code_challenge')) return html(res, 400, authorizePage(p, 'Invalid authorization request.'));
  const now = Math.floor(Date.now() / 1000);
  const code = mintSigned({
    kind: 'auth_code',
    sub: 'claude',
    client_id: p.get('client_id'),
    redirect_uri: redirectUri,
    code_challenge: p.get('code_challenge'),
    scope: p.get('scope') || 'mcp:tools',
    iat: now,
    exp: now + AUTH_CODE_TTL_SECONDS,
    jti: randomBytes(12).toString('base64url'),
  }, secret);
  const dest = new URL(redirectUri);
  dest.searchParams.set('code', code);
  if (p.get('state')) dest.searchParams.set('state', p.get('state'));
  dest.searchParams.set('iss', PUBLIC_ORIGIN);
  res.writeHead(302, { location: dest.toString(), 'cache-control': 'no-store' });
  res.end();
}
function handleToken(res, raw, secret) {
  const p = parseForm(raw);
  if (p.get('grant_type') !== 'authorization_code') return json(res, 400, { error: 'unsupported_grant_type' });
  const payload = verifySigned(p.get('code'), secret, 'auth_code');
  if (!payload) return json(res, 400, { error: 'invalid_grant' });
  if (payload.client_id !== p.get('client_id') || payload.redirect_uri !== p.get('redirect_uri')) return json(res, 400, { error: 'invalid_grant' });
  const verifier = p.get('code_verifier') || '';
  const challenge = createHash('sha256').update(verifier, 'utf8').digest('base64url');
  if (!safeStringEqual(challenge, payload.code_challenge)) return json(res, 400, { error: 'invalid_grant' });
  const now = Math.floor(Date.now() / 1000);
  const accessToken = mintSigned({ kind: 'access_token', sub: 'claude', scope: payload.scope || 'mcp:tools', iat: now, exp: now + ACCESS_TOKEN_TTL_SECONDS, jti: randomBytes(12).toString('base64url') }, secret);
  json(res, 200, { access_token: accessToken, token_type: 'Bearer', expires_in: ACCESS_TOKEN_TTL_SECONDS, scope: payload.scope || 'mcp:tools' }, { 'cache-control': 'no-store' });
}
function unauthorizedForOAuth(res) {
  json(res, 401, { error: 'unauthorized', error_description: 'OAuth authentication required' }, {
    'www-authenticate': `Bearer resource_metadata="${RESOURCE_METADATA_URL}", scope="mcp:tools"`,
    'cache-control': 'no-store',
  });
}
function proxyMcp(req, res, assertion, raw) {
  const identity = identifyRequest(req.headers, assertion);
  const ua = String(req.headers['user-agent'] || '');
  console.log('AUTH_PROXY_REQUEST', JSON.stringify({ method: req.method, path: req.url, credentialSource: identity.source, credentialDigestPrefix: identity.digestPrefix, verifiedClient: identity.client || 'none', userAgent: ua.slice(0, 80) }));
  // METATRON_MCP_AUTH_REQUIRED_BOUNDARY_V1
  // Every unauthenticated MCP request is challenged at the HTTP boundary. OAuth discovery
  // must never depend on User-Agent because connector preflights and non-Claude clients use
  // different UAs. Valid static credentials and valid OAuth bearer tokens still pass.
  if (!identity.client) return unauthorizedForOAuth(res);
  const transformed = req.method === 'POST' ? authorizeBody(raw, identity, assertion) : { body: raw, client: identity.client };
  const body = Buffer.from(transformed.body, 'utf8');
  const upstream = http.request({ hostname: UPSTREAM_HOST, port: UPSTREAM_PORT, path: req.url, method: req.method, headers: filteredHeaders(req.headers, body.length, transformed.client) }, upstreamRes => {
    const responseHeaders = { ...upstreamRes.headers };
    delete responseHeaders['transfer-encoding'];
    res.writeHead(upstreamRes.statusCode || 502, responseHeaders);
    upstreamRes.pipe(res);
  });
  upstream.on('error', error => {
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'application/json' });
    if (!res.writableEnded) res.end(JSON.stringify({ error: 'mcp_upstream_unavailable' }));
    console.error('AUTH_PROXY_UPSTREAM_ERROR', error.message);
  });
  if (body.length) upstream.write(body);
  upstream.end();
}
function route(req, res, assertion, raw) {
  const url = new URL(req.url, PUBLIC_ORIGIN);
  if (req.method === 'GET' && (url.pathname === '/.well-known/oauth-protected-resource' || url.pathname === '/.well-known/oauth-protected-resource/mcp')) return protectedResourceMetadata(res);
  if (req.method === 'GET' && (url.pathname === '/.well-known/oauth-authorization-server' || url.pathname === '/.well-known/openid-configuration')) return oauthMetadata(res);
  if (req.method === 'POST' && url.pathname === '/oauth/register') return handleRegister(req, res, raw);
  if (req.method === 'GET' && url.pathname === '/oauth/authorize') return handleAuthorizeGet(req, res);
  if (req.method === 'POST' && url.pathname === '/oauth/authorize') return handleAuthorizePost(req, res, raw, assertion);
  if (req.method === 'POST' && url.pathname === '/oauth/token') return handleToken(res, raw, assertion);
  if (url.pathname === '/mcp') return proxyMcp(req, res, assertion, raw);
  return json(res, 404, { error: 'not_found' });
}
function handler(req, res, assertion) {
  if (req.method === 'GET') return route(req, res, assertion, '');
  const chunks = [];
  let size = 0;
  req.on('data', chunk => {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) {
      res.writeHead(413, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: 'request_too_large' }));
      req.destroy();
      return;
    }
    chunks.push(chunk);
  });
  req.on('end', () => {
    if (!res.writableEnded) route(req, res, assertion, Buffer.concat(chunks).toString('utf8'));
  });
  req.on('error', error => {
    if (!res.headersSent) res.writeHead(400, { 'content-type': 'application/json' });
    if (!res.writableEnded) res.end(JSON.stringify({ error: 'bad_request' }));
    console.error('AUTH_PROXY_REQUEST_ERROR', error.message);
  });
}
function selfTest() {
  const token = 'unit-test-token';
  const assertion = 'a'.repeat(64);
  const hashes = new Map([['unit', sha256(token)]]);
  const staticIdentity = identifyRequest({ authorization: `Bearer ${token}` }, assertion, hashes);
  if (staticIdentity.client !== 'unit') throw new Error('bearer_acceptance_failed');
  const now = Math.floor(Date.now() / 1000);
  const oauth = mintSigned({ kind: 'access_token', sub: 'claude', iat: now, exp: now + 60 }, assertion);
  if (identifyRequest({ authorization: `Bearer ${oauth}` }, assertion, hashes).client !== 'claude') throw new Error('oauth_acceptance_failed');
  if (identifyRequest({ 'x-api-key': token }, assertion, hashes).client !== 'unit') throw new Error('x_api_key_acceptance_failed');
  if (identifyRequest({ 'x-metatron-client-token': token }, assertion, hashes).client !== 'unit') throw new Error('client_token_acceptance_failed');
  const spoofed = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/call', params: { _meta: { [VERIFIED_META_KEY]: 'claude', [ASSERTION_META_KEY]: 'spoof' } } });
  const stripped = JSON.parse(authorizeBody(spoofed, { client: null }, assertion).body);
  if (stripped.params._meta[VERIFIED_META_KEY] || stripped.params._meta[ASSERTION_META_KEY]) throw new Error('spoof_fields_not_stripped');
  const authorized = JSON.parse(authorizeBody(spoofed, { client: 'claude' }, assertion).body);
  if (authorized.params._meta[VERIFIED_META_KEY] !== 'claude' || authorized.params._meta[ASSERTION_META_KEY] !== assertion) throw new Error('verified_marker_not_injected');
  const headers = filteredHeaders({ 'mcp-protocol-version': '2026-07-28', authorization: `Bearer ${token}` }, 10, 'claude');
  if (headers['mcp-protocol-version'] !== BOUNDED_CLIENT_PROTOCOL) throw new Error('protocol_downgrade_failed');
  if ('authorization' in headers) throw new Error('authorization_forwarding_not_stripped');
  console.log('AUTH_PROXY_ACCEPTANCE_PASS credential_forms=4 oauth=enabled');
}

if (process.env.AUTH_PROXY_SELF_TEST === '1') selfTest();
else {
  const assertion = process.env.METATRON_PROXY_ASSERTION;
  if (typeof assertion !== 'string' || assertion.length < 32) throw new Error('METATRON_PROXY_ASSERTION missing');
  http.createServer((req, res) => handler(req, res, assertion)).listen(LISTEN_PORT, '0.0.0.0', () => console.log(`AUTH_PROXY_LISTENING port=${LISTEN_PORT} upstream=${UPSTREAM_HOST}:${UPSTREAM_PORT} oauth=enabled`));
}
