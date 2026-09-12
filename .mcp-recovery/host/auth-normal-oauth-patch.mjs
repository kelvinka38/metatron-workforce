import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-normal-oauth-patch.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');
if (source.includes('METATRON_CLAUDE_NORMAL_OAUTH_V1')) {
  console.log('AUTH_NORMAL_OAUTH_PATCH_ALREADY_APPLIED');
  process.exit(0);
}
if (!source.includes('METATRON_MCP_CONTROL_STATE_INIT_V2')) throw new Error('control-state init v2 required');
if (!source.includes('METATRON_SECURE_AUTH_UI_V2')) throw new Error('secure auth UI v2 required');

// Remove the confidential-client specialization if present. Standard Claude remote MCP
// uses dynamic client registration + OAuth public client + PKCE, with no Metatron password,
// bootstrap code, client secret, or API key required from the user.
source = source.replace(/\/\/ METATRON_CLAUDE_CONFIDENTIAL_OAUTH_V1\nconst CLAUDE_OAUTH_CLIENT_ID = 'metatron-claude';\nconst CLAUDE_OAUTH_REDIRECT_URI = 'https:\/\/claude\.ai\/api\/mcp\/auth_callback';\nconst CLAUDE_OAUTH_CLIENT_SECRET_SHA256 = '[0-9a-f]{64}';/, '// METATRON_CLAUDE_NORMAL_OAUTH_V1');
if (!source.includes('METATRON_CLAUDE_NORMAL_OAUTH_V1')) {
  const anchor = "const RESOURCE_METADATA_URL = `${PUBLIC_ORIGIN}/.well-known/oauth-protected-resource`;";
  if (!source.includes(anchor)) throw new Error('resource metadata anchor missing');
  source = source.replace(anchor, `${anchor}\n// METATRON_CLAUDE_NORMAL_OAUTH_V1`);
}

// OAuth metadata: standard public-client flow with DCR + PKCE.
if (!source.includes('registration_endpoint:')) {
  const tokenEndpoint = "    token_endpoint: `${PUBLIC_ORIGIN}/oauth/token`,\n";
  if (!source.includes(tokenEndpoint)) throw new Error('token endpoint metadata anchor missing');
  source = source.replace(tokenEndpoint, tokenEndpoint + "    registration_endpoint: `${PUBLIC_ORIGIN}/oauth/register`,\n");
}
source = source.replace("    token_endpoint_auth_methods_supported: ['client_secret_basic', 'client_secret_post'],", "    token_endpoint_auth_methods_supported: ['none'],");

function replaceFunctionBlock(startNeedle, nextNeedle, replacement) {
  const start = source.indexOf(startNeedle);
  const end = source.indexOf(nextNeedle, start);
  if (start < 0 || end < 0) throw new Error('function boundary missing: ' + startNeedle);
  source = source.slice(0, start) + replacement + source.slice(end + (nextNeedle.startsWith('\n') ? 1 : 0));
}

replaceFunctionBlock(
  'function handleRegister(req, res, raw) {',
  '\nfunction handleAuthorizeGet(req, res) {',
  `function handleRegister(req, res, raw) {\n  let body = {};\n  try { body = JSON.parse(raw || '{}'); } catch { return json(res, 400, { error: 'invalid_client_metadata' }); }\n  const redirects = Array.isArray(body.redirect_uris) ? body.redirect_uris.filter(validRedirect) : [];\n  if (!redirects.length) return json(res, 400, { error: 'invalid_redirect_uri' });\n  const clientId = \`metatron-\${sha256(JSON.stringify(redirects)).slice(0, 24)}\`;\n  json(res, 201, {\n    client_id: clientId,\n    client_id_issued_at: Math.floor(Date.now() / 1000),\n    redirect_uris: redirects,\n    token_endpoint_auth_method: 'none',\n    grant_types: ['authorization_code'],\n    response_types: ['code'],\n  });\n}\n`
);

replaceFunctionBlock(
  'function handleAuthorizeGet(req, res) {',
  '\nfunction handleAuthorizePost(req, res, raw, secret) {',
  `function handleAuthorizeGet(req, res) {\n  const u = new URL(req.url, PUBLIC_ORIGIN);\n  const p = u.searchParams;\n  if (p.get('response_type') !== 'code') return html(res, 400, authorizePage(p, 'Unsupported response_type'));\n  if (!p.get('client_id')) return html(res, 400, authorizePage(p, 'Missing client_id'));\n  if (!validRedirect(p.get('redirect_uri'))) return html(res, 400, authorizePage(p, 'Invalid redirect_uri'));\n  if (!p.get('code_challenge') || (p.get('code_challenge_method') || 'S256') !== 'S256') return html(res, 400, authorizePage(p, 'PKCE S256 is required'));\n  html(res, 200, authorizePage(p));\n}\n`
);

replaceFunctionBlock(
  'function handleAuthorizePost(req, res, raw, secret) {',
  '\nfunction ',
  `function handleAuthorizePost(req, res, raw, secret) {\n  const p = parseForm(raw);\n  if (!checkRateLimit(clientIp(req))) return html(res, 429, authorizePage(p, 'Too many attempts. Try again later.'));\n  const redirectUri = p.get('redirect_uri');\n  if (!validRedirect(redirectUri) || !p.get('client_id') || !p.get('code_challenge')) return html(res, 400, authorizePage(p, 'Invalid authorization request.'));\n  if ((p.get('code_challenge_method') || 'S256') !== 'S256') return html(res, 400, authorizePage(p, 'PKCE S256 is required'));\n  const now = Math.floor(Date.now() / 1000);\n  const code = mintSigned({\n    kind: 'auth_code',\n    sub: 'claude',\n    client_id: p.get('client_id'),\n    redirect_uri: redirectUri,\n    code_challenge: p.get('code_challenge'),\n    scope: p.get('scope') || 'mcp:tools',\n    iat: now,\n    exp: now + AUTH_CODE_TTL_SECONDS,\n    jti: randomBytes(12).toString('base64url'),\n  }, secret);\n  const dest = new URL(redirectUri);\n  dest.searchParams.set('code', code);\n  if (p.get('state')) dest.searchParams.set('state', p.get('state'));\n  dest.searchParams.set('iss', PUBLIC_ORIGIN);\n  res.writeHead(302, { location: dest.toString(), 'cache-control': 'no-store' });\n  res.end();\n}\n`
);

// Replace any confidential-client token handler pair with a standard PKCE token exchange.
let tokenStart = source.indexOf('function claudeClientCredentials(req, p) {');
if (tokenStart < 0) tokenStart = source.indexOf('function handleToken(');
const unauthorizedStart = source.indexOf('\nfunction unauthorizedForOAuth(res) {', tokenStart);
if (tokenStart < 0 || unauthorizedStart < 0) throw new Error('token handler boundary missing');
const tokenReplacement = `function handleToken(req, res, raw, secret) {\n  const p = parseForm(raw);\n  if (p.get('grant_type') !== 'authorization_code') return json(res, 400, { error: 'unsupported_grant_type' });\n  const payload = verifySigned(p.get('code'), secret, 'auth_code');\n  if (!payload) return json(res, 400, { error: 'invalid_grant' });\n  if (payload.client_id !== p.get('client_id') || payload.redirect_uri !== p.get('redirect_uri')) return json(res, 400, { error: 'invalid_grant' });\n  const verifier = p.get('code_verifier') || '';\n  const challenge = createHash('sha256').update(verifier, 'utf8').digest('base64url');\n  if (!safeStringEqual(challenge, payload.code_challenge)) return json(res, 400, { error: 'invalid_grant' });\n  const now = Math.floor(Date.now() / 1000);\n  const tokenPayload = { kind: 'access_token', sub: 'claude', scope: payload.scope || 'mcp:tools', iat: now, exp: now + ACCESS_TOKEN_TTL_SECONDS, jti: randomBytes(12).toString('base64url') };\n  const accessToken = mintSigned(tokenPayload, secret);\n  recordAuthSession(tokenPayload, accessToken);\n  json(res, 200, { access_token: accessToken, token_type: 'Bearer', expires_in: ACCESS_TOKEN_TTL_SECONDS, scope: payload.scope || 'mcp:tools' }, { 'cache-control': 'no-store' });\n}\n`;
source = source.slice(0, tokenStart) + tokenReplacement + source.slice(unauthorizedStart + 1);

// Route token exchange with request context. This is compatible with the standard public client.
source = source.replace("if (req.method === 'POST' && url.pathname === '/oauth/token') return handleToken(res, raw, assertion);", "if (req.method === 'POST' && url.pathname === '/oauth/token') return handleToken(req, res, raw, assertion);");

// Passwordless authorization page: keep a deliberate consent click, remove all Metatron secrets.
source = source.replace('Claude authenticates as a confidential OAuth client. No Metatron password or bootstrap code is required.', 'No Metatron password, bootstrap code, API key, or client secret is required. Review the request and authorize Claude.');
source = source.replace('Claude is authenticated by a dedicated confidential OAuth client. No Metatron password or bootstrap code is shared with Claude.', 'Claude uses standard OAuth 2.0 with PKCE. No Metatron password, bootstrap code, API key, or client secret is required.');

const labelStart = source.indexOf('<label for="founder-secret">');
if (labelStart >= 0) {
  const buttonStart = source.indexOf('<button class="primary"', labelStart);
  if (buttonStart < 0) throw new Error('authorize button boundary missing');
  source = source.slice(0, labelStart) + '<p class="help">No password is required. Confirm this request to authorize Claude.</p>\\n        ' + source.slice(buttonStart);
}

// HTTP boundary: every unauthenticated MCP request receives OAuth challenge, regardless of UA.
const uaGate = "if (!identity.client && /Claude-User/i.test(ua)) return unauthorizedForOAuth(res);";
const strictGate = "if (!identity.client) return unauthorizedForOAuth(res);";
if (source.includes(uaGate)) source = source.replace(uaGate, strictGate);
if (!source.includes(strictGate)) throw new Error('strict oauth boundary missing');

for (const marker of [
  'METATRON_CLAUDE_NORMAL_OAUTH_V1',
  'registration_endpoint:',
  "token_endpoint_auth_methods_supported: ['none']",
  "token_endpoint_auth_method: 'none'",
  'function handleRegister(req, res, raw)',
  'function handleToken(req, res, raw, secret)',
  strictGate,
]) {
  if (!source.includes(marker)) throw new Error('normal OAuth invariant missing: ' + marker);
}
if (source.includes('id="founder-secret"')) throw new Error('founder secret input still present');
if (source.includes("token_endpoint_auth_methods_supported: ['client_secret_basic'")) throw new Error('confidential token auth still advertised');
fs.writeFileSync(target, source);
console.log('AUTH_NORMAL_OAUTH_PATCH_PASS');
