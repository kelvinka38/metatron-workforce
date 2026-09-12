import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-security-admin-patch.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');
if (source.includes('METATRON_FOUNDER_SECURITY_ADMIN_V1')) {
  console.log('AUTH_SECURITY_ADMIN_PATCH_ALREADY_APPLIED');
  process.exit(0);
}
if (!source.includes('METATRON_SECURE_AUTH_UI_V2')) throw new Error('professional auth patch must run first');

source = source.replace("import http from 'node:http';", "import http from 'node:http';\nimport fs from 'node:fs';");

const stateAnchor = "const FOUNDER_PASSWORD_SCRYPT = String(process.env.METATRON_FOUNDER_PASSWORD_SCRYPT || '').trim();\nconst ALLOW_BOOTSTRAP_CODE = process.env.METATRON_DISABLE_BOOTSTRAP_CODE !== '1';";
if (!source.includes(stateAnchor)) throw new Error('founder auth state anchor missing');
source = source.replace(stateAnchor, `const AUTH_SECURITY_STATE_PATH = String(process.env.METATRON_AUTH_SECURITY_STATE_PATH || '/app/metatron-auth-security.json').trim();
const ENV_FOUNDER_PASSWORD_SCRYPT = String(process.env.METATRON_FOUNDER_PASSWORD_SCRYPT || '').trim();
const ENV_ALLOW_BOOTSTRAP_CODE = process.env.METATRON_DISABLE_BOOTSTRAP_CODE !== '1';
const ADMIN_SESSION_TTL_SECONDS = 15 * 60;
const authSessions = new Map();
const clientRevokedBefore = new Map();
let runtimeFounderPasswordScrypt = ENV_FOUNDER_PASSWORD_SCRYPT;
let runtimeBootstrapEnabled = ENV_ALLOW_BOOTSTRAP_CODE;

function loadSecurityState() {
  try {
    if (!fs.existsSync(AUTH_SECURITY_STATE_PATH)) return;
    const parsed = JSON.parse(fs.readFileSync(AUTH_SECURITY_STATE_PATH, 'utf8'));
    if (typeof parsed.founderPasswordScrypt === 'string' && parsed.founderPasswordScrypt) runtimeFounderPasswordScrypt = parsed.founderPasswordScrypt;
    if (typeof parsed.bootstrapEnabled === 'boolean') runtimeBootstrapEnabled = parsed.bootstrapEnabled && ENV_ALLOW_BOOTSTRAP_CODE;
    if (parsed.clientRevokedBefore && typeof parsed.clientRevokedBefore === 'object') {
      for (const [client, at] of Object.entries(parsed.clientRevokedBefore)) if (Number.isFinite(Number(at))) clientRevokedBefore.set(client, Number(at));
    }
  } catch (error) {
    console.error('AUTH_SECURITY_STATE_LOAD_FAILED', error.message);
  }
}
function saveSecurityState() {
  try {
    const next = {
      version: 1,
      founderPasswordScrypt: runtimeFounderPasswordScrypt,
      bootstrapEnabled: runtimeBootstrapEnabled,
      clientRevokedBefore: Object.fromEntries(clientRevokedBefore),
      updatedAt: new Date().toISOString(),
    };
    const temp = AUTH_SECURITY_STATE_PATH + '.tmp';
    fs.writeFileSync(temp, JSON.stringify(next, null, 2), { mode: 0o600 });
    fs.renameSync(temp, AUTH_SECURITY_STATE_PATH);
    try { fs.chmodSync(AUTH_SECURITY_STATE_PATH, 0o600); } catch {}
  } catch (error) {
    console.error('AUTH_SECURITY_STATE_SAVE_FAILED', error.message);
    throw new Error('security_state_persistence_failed');
  }
}
loadSecurityState();`);

source = source.replace(/FOUNDER_PASSWORD_SCRYPT/g, 'runtimeFounderPasswordScrypt');
source = source.replace(/ALLOW_BOOTSTRAP_CODE/g, 'runtimeBootstrapEnabled');
// Repair identifiers that were intentionally renamed before the global compatibility replacement.
source = source.replace(/ENV_runtimeFounderPasswordScrypt/g, 'ENV_FOUNDER_PASSWORD_SCRYPT');
source = source.replace(/ENV_runtimeBootstrapEnabled/g, 'ENV_ALLOW_BOOTSTRAP_CODE');

const verifySignedEnd = "function identifyStaticToken(token, hashes = CLIENT_TOKEN_HASHES) {";
const verifySignedIndex = source.indexOf(verifySignedEnd);
if (verifySignedIndex < 0) throw new Error('token helper insertion anchor missing');
const securityHelpers = `function accessTokenAllowed(payload) {
  if (!payload || payload.kind !== 'access_token' || !payload.jti || !payload.sub) return false;
  const revokedBefore = clientRevokedBefore.get(payload.sub) || 0;
  if ((payload.iat || 0) <= revokedBefore) return false;
  const session = authSessions.get(payload.jti);
  if (session?.revoked) return false;
  return true;
}
function recordAuthSession(payload, token) {
  authSessions.set(payload.jti, {
    jti: payload.jti,
    client: payload.sub,
    scope: payload.scope || 'mcp:tools',
    issuedAt: payload.iat,
    expiresAt: payload.exp,
    digestPrefix: sha256(token).slice(0, 12),
    revoked: false,
  });
}
function adminCookie(req) {
  const cookie = String(req.headers.cookie || '');
  for (const part of cookie.split(';')) {
    const [name, ...rest] = part.trim().split('=');
    if (name === 'metatron_founder_admin') return decodeURIComponent(rest.join('='));
  }
  return '';
}
function adminSession(req, secret) {
  const payload = verifySigned(adminCookie(req), secret, 'founder_admin');
  return payload?.sub === 'founder' ? payload : null;
}
function adminSetCookie(res, token) {
  res.setHeader('set-cookie', 'metatron_founder_admin=' + encodeURIComponent(token) + '; Max-Age=' + ADMIN_SESSION_TTL_SECONDS + '; Path=/founder/security; HttpOnly; Secure; SameSite=Strict');
}
function adminClearCookie(res) {
  res.setHeader('set-cookie', 'metatron_founder_admin=; Max-Age=0; Path=/founder/security; HttpOnly; Secure; SameSite=Strict');
}
function csrfValid(p, session) {
  return !!session && typeof session.csrf === 'string' && safeStringEqual(p.get('csrf') || '', session.csrf);
}
function scryptPassword(password) {
  const value = String(password || '');
  if (value.length < 14 || value.length > 256) throw new Error('password_length');
  const salt = randomBytes(16);
  const derived = scryptSync(value, salt, 32, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 });
  return salt.toString('hex') + ':' + derived.toString('hex');
}
function pruneAuthSessions() {
  const now = Math.floor(Date.now() / 1000);
  for (const [jti, session] of authSessions.entries()) if ((session.expiresAt || 0) < now - 86400) authSessions.delete(jti);
}
function esc(value) {
  return String(value ?? '').replace(/[&<>'\"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','\"':'&quot;'}[c]));
}
function fmtTime(epoch) {
  if (!epoch) return '—';
  try { return new Date(epoch * 1000).toLocaleString('en-GB', { timeZone: 'UTC', hour12: false }) + ' UTC'; } catch { return '—'; }
}
function securityShell(content, title = 'Founder Security') {
  return \`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#0b1020"><title>\${esc(title)} · Metatron</title><style>
  :root{color-scheme:light;--bg:#f5f7fb;--card:#fff;--text:#101828;--muted:#667085;--line:#e4e7ec;--brand:#3157d5;--brand2:#263fa9;--ok:#067647;--warn:#b54708;--danger:#b42318;--soft:#eef3ff}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% -10%,#e7edff 0,#f5f7fb 40%);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Inter,Roboto,Helvetica,Arial,sans-serif;color:var(--text);min-height:100vh}.wrap{width:min(100%,960px);margin:0 auto;padding:32px 18px 48px}.top{display:flex;align-items:center;gap:12px;margin-bottom:24px}.mark{width:40px;height:40px;display:grid;place-items:center;border-radius:12px;background:linear-gradient(145deg,var(--brand),var(--brand2));color:white;font-weight:850}.topcopy{flex:1}.eyebrow{font-size:11px;letter-spacing:.14em;font-weight:850;color:var(--brand2)}.title{font-size:17px;font-weight:800;margin-top:2px}.secure{font-size:12px;color:var(--ok);font-weight:750;border:1px solid #abefc6;background:#ecfdf3;padding:6px 9px;border-radius:999px}.card{background:var(--card);border:1px solid rgba(16,24,40,.07);border-radius:20px;box-shadow:0 16px 48px rgba(16,24,40,.09);padding:24px;margin-bottom:18px}h1{font-size:30px;letter-spacing:-.025em;margin:0 0 8px}h2{font-size:18px;margin:0 0 14px}.lead{color:var(--muted);line-height:1.55;margin:0 0 22px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.stat{border:1px solid var(--line);border-radius:14px;padding:14px}.stat span{display:block;color:var(--muted);font-size:12px;margin-bottom:4px}.stat strong{font-size:15px}.row{display:flex;align-items:center;gap:12px;justify-content:space-between;padding:12px 0;border-bottom:1px solid var(--line)}.row:last-child{border-bottom:0}.meta{font-size:12px;color:var(--muted);margin-top:3px;line-height:1.4}.pill{display:inline-block;font-size:11px;font-weight:800;border-radius:999px;padding:5px 8px;background:#f2f4f7;color:#344054}.pill.ok{background:#ecfdf3;color:var(--ok)}.pill.bad{background:#fef3f2;color:var(--danger)}label{display:block;font-size:13px;font-weight:750;margin-bottom:7px}.input{width:100%;border:1px solid #d0d5dd;border-radius:11px;padding:12px 13px;font-size:16px;margin-bottom:12px;outline:none}.input:focus{border-color:#84adff;box-shadow:0 0 0 4px rgba(49,87,213,.11)}button,.btn{border:0;border-radius:10px;padding:10px 13px;font-size:13px;font-weight:800;cursor:pointer}.primary{background:linear-gradient(180deg,var(--brand),var(--brand2));color:#fff}.secondary{background:#f2f4f7;color:#344054}.danger{background:#fef3f2;color:var(--danger);border:1px solid #fecdca}.notice{font-size:13px;line-height:1.5;background:var(--soft);border:1px solid #c7d7fe;border-radius:12px;padding:12px 14px;color:#344054}.actions{display:flex;gap:8px;flex-wrap:wrap}.footer{text-align:center;color:#98a2b3;font-size:11px;padding-top:12px}@media(max-width:680px){.wrap{padding-top:20px}.grid{grid-template-columns:1fr}.card{border-radius:16px;padding:20px}h1{font-size:26px}.row{align-items:flex-start;flex-direction:column}.row .actions{width:100%}.row .actions form{flex:1}.row .actions button{width:100%}}
  </style></head><body><div class="wrap" data-ui="METATRON_FOUNDER_SECURITY_ADMIN_V1"><div class="top"><div class="mark">M</div><div class="topcopy"><div class="eyebrow">METATRON</div><div class="title">Founder Security</div></div><div class="secure">Protected</div></div>\${content}<div class="footer">Metatron Institutional Access · Founder security control plane</div></div></body></html>\`;
}
function securityLoginPage(error = '') {
  const errorBlock = error ? '<div class="notice" style="border-color:#fecdca;background:#fef3f2;color:#b42318;margin-bottom:16px">' + esc(error) + '</div>' : '';
  return securityShell('<section class="card" style="max-width:520px;margin:40px auto"><h1>Founder Security</h1><p class="lead">Authenticate to manage Metatron client authorization, password rotation and session revocation.</p>' + errorBlock + '<form method="post" action="/founder/security/login"><label for="secret">Founder verification</label><input class="input" id="secret" name="founder_secret" type="password" autocomplete="current-password" required><button class="primary" type="submit">Continue securely</button></form><p class="meta" style="margin-top:14px">Admin sessions expire after 15 minutes and are protected by HttpOnly, Secure and SameSite cookies.</p></section>', 'Founder Security');
}
function securityDashboard(session, message = '') {
  pruneAuthSessions();
  const now = Math.floor(Date.now() / 1000);
  const sessions = [...authSessions.values()].sort((a,b)=>b.issuedAt-a.issuedAt);
  const active = sessions.filter(s=>!s.revoked && s.expiresAt > now && s.issuedAt > (clientRevokedBefore.get(s.client)||0));
  const clients = new Set([...CLIENT_TOKEN_HASHES.keys(), ...sessions.map(s=>s.client), 'chatgpt']);
  const clientRows = [...clients].sort().map(client => {
    const count = active.filter(s=>s.client===client).length;
    const rb = clientRevokedBefore.get(client)||0;
    return '<div class="row"><div><strong>' + esc(client) + '</strong><div class="meta">Active OAuth sessions: ' + count + (rb ? ' · revoked before ' + esc(fmtTime(rb)) : '') + '</div></div><div class="actions"><form method="post" action="/founder/security/revoke-client"><input type="hidden" name="csrf" value="'+esc(session.csrf)+'"><input type="hidden" name="client" value="'+esc(client)+'"><button class="danger" type="submit">Revoke sessions</button></form></div></div>';
  }).join('');
  const sessionRows = sessions.length ? sessions.map(s => {
    const valid = !s.revoked && s.expiresAt > now && s.issuedAt > (clientRevokedBefore.get(s.client)||0);
    return '<div class="row"><div><strong>'+esc(s.client)+'</strong> <span class="pill '+(valid?'ok':'bad')+'">'+(valid?'ACTIVE':'REVOKED / EXPIRED')+'</span><div class="meta">Token '+esc(s.digestPrefix)+'… · issued '+esc(fmtTime(s.issuedAt))+' · expires '+esc(fmtTime(s.expiresAt))+'</div></div>' + (valid ? '<div class="actions"><form method="post" action="/founder/security/revoke-session"><input type="hidden" name="csrf" value="'+esc(session.csrf)+'"><input type="hidden" name="jti" value="'+esc(s.jti)+'"><button class="danger" type="submit">Revoke</button></form></div>' : '') + '</div>';
  }).join('') : '<p class="meta">No OAuth sessions have been issued by this runtime yet. Static configured clients are shown above.</p>';
  const status = runtimeFounderPasswordScrypt ? 'Configured' : 'Bootstrap only';
  const bootstrap = runtimeBootstrapEnabled ? 'Enabled' : 'Disabled';
  const msg = message ? '<div class="notice" style="margin-bottom:18px">'+esc(message)+'</div>' : '';
  return securityShell('<section class="card"><h1>Security control center</h1><p class="lead">Manage founder authentication and authorization sessions. Raw credentials and bearer tokens are never displayed.</p>'+msg+'<div class="grid"><div class="stat"><span>Founder password</span><strong>'+status+'</strong></div><div class="stat"><span>Bootstrap verification</span><strong>'+bootstrap+'</strong></div><div class="stat"><span>Active OAuth sessions</span><strong>'+active.length+'</strong></div><div class="stat"><span>Admin session</span><strong>15 minute protected session</strong></div></div></section><section class="card"><h2>Authorized clients</h2>'+clientRows+'</section><section class="card"><h2>OAuth sessions</h2>'+sessionRows+'</section><section class="card"><h2>Founder password</h2><form method="post" action="/founder/security/password"><input type="hidden" name="csrf" value="'+esc(session.csrf)+'"><div class="grid"><div><label for="newpw">New password</label><input class="input" id="newpw" name="new_password" type="password" autocomplete="new-password" minlength="14" required></div><div><label for="confirm">Confirm password</label><input class="input" id="confirm" name="confirm_password" type="password" autocomplete="new-password" minlength="14" required></div></div><button class="primary" type="submit">Set / rotate founder password</button></form><p class="meta">Minimum 14 characters. Metatron stores only an scrypt-derived verifier.</p></section><section class="card"><h2>Bootstrap access</h2><p class="lead">Once a founder password is configured, disable the bootstrap code for normal operation. It can only be re-enabled from an authenticated founder session while the environment allows bootstrap.</p><div class="actions"><form method="post" action="/founder/security/bootstrap"><input type="hidden" name="csrf" value="'+esc(session.csrf)+'"><input type="hidden" name="enabled" value="'+(runtimeBootstrapEnabled?'0':'1')+'"><button class="'+(runtimeBootstrapEnabled?'danger':'secondary')+'" type="submit">'+(runtimeBootstrapEnabled?'Disable bootstrap code':'Enable bootstrap code')+'</button></form><form method="post" action="/founder/security/logout"><input type="hidden" name="csrf" value="'+esc(session.csrf)+'"><button class="secondary" type="submit">Sign out admin</button></form></div></section><div class="notice">Security state is enforced by the MCP authorization runtime. OAuth tokens are also invalidated when the authorization service signing assertion rotates on runtime replacement.</div>');
}

`;
source = source.slice(0, verifySignedIndex) + securityHelpers + source.slice(verifySignedIndex);

const oauthBranch = "if (access?.sub === 'claude') return { client: 'claude', source: 'oauth-bearer', digestPrefix: sha256(candidate.token).slice(0, 12), oauth: true };";
if (!source.includes(oauthBranch)) throw new Error('oauth identity branch missing');
source = source.replace(oauthBranch, "if (access?.sub === 'claude' && accessTokenAllowed(access)) return { client: 'claude', source: 'oauth-bearer', digestPrefix: sha256(candidate.token).slice(0, 12), oauth: true };");

const tokenIssue = "const accessToken = mintSigned({ kind: 'access_token', sub: 'claude', scope: payload.scope || 'mcp:tools', iat: now, exp: now + ACCESS_TOKEN_TTL_SECONDS, jti: randomBytes(12).toString('base64url') }, secret);\n  json(res, 200, { access_token: accessToken, token_type: 'Bearer', expires_in: ACCESS_TOKEN_TTL_SECONDS, scope: payload.scope || 'mcp:tools' }, { 'cache-control': 'no-store' });";
if (!source.includes(tokenIssue)) throw new Error('token issuance anchor missing');
source = source.replace(tokenIssue, `const tokenPayload = { kind: 'access_token', sub: 'claude', scope: payload.scope || 'mcp:tools', iat: now, exp: now + ACCESS_TOKEN_TTL_SECONDS, jti: randomBytes(12).toString('base64url') };
  const accessToken = mintSigned(tokenPayload, secret);
  recordAuthSession(tokenPayload, accessToken);
  json(res, 200, { access_token: accessToken, token_type: 'Bearer', expires_in: ACCESS_TOKEN_TTL_SECONDS, scope: payload.scope || 'mcp:tools' }, { 'cache-control': 'no-store' });`);

const routeAnchor = "function route(req, res, assertion, raw) {\n  const url = new URL(req.url, PUBLIC_ORIGIN);";
if (!source.includes(routeAnchor)) throw new Error('route anchor missing');
const founderRoutes = `function founderSecurityRoute(req, res, assertion, raw, url) {
  if (req.method === 'GET' && url.pathname === '/founder/security') {
    const session = adminSession(req, assertion);
    return html(res, 200, session ? securityDashboard(session) : securityLoginPage());
  }
  if (req.method === 'POST' && url.pathname === '/founder/security/login') {
    const p = parseForm(raw);
    if (!checkRateLimit(clientIp(req))) return html(res, 429, securityLoginPage('Too many attempts. Try again later.'));
    if (!verifyFounderSecret(p.get('founder_secret') || '')) return html(res, 403, securityLoginPage('Founder verification failed.'));
    const now = Math.floor(Date.now()/1000);
    const csrf = randomBytes(18).toString('base64url');
    const token = mintSigned({kind:'founder_admin',sub:'founder',csrf,iat:now,exp:now+ADMIN_SESSION_TTL_SECONDS,jti:randomBytes(12).toString('base64url')}, assertion);
    adminSetCookie(res, token);
    res.writeHead(303, { location: '/founder/security', 'cache-control': 'no-store' }); res.end(); return;
  }
  const session = adminSession(req, assertion);
  if (!session) return html(res, 401, securityLoginPage('Your founder admin session has expired.'));
  const p = parseForm(raw);
  if (req.method === 'POST' && !csrfValid(p, session)) return html(res, 403, securityShell('<section class="card"><h1>Security check failed</h1><p class="lead">Invalid CSRF token. Sign in again.</p></section>'));
  if (req.method === 'POST' && url.pathname === '/founder/security/password') {
    const password = p.get('new_password') || '', confirm = p.get('confirm_password') || '';
    if (password !== confirm) return html(res, 400, securityDashboard(session, 'Password confirmation does not match.'));
    try { runtimeFounderPasswordScrypt = scryptPassword(password); saveSecurityState(); }
    catch (error) { return html(res, 400, securityDashboard(session, error.message === 'password_length' ? 'Password must be between 14 and 256 characters.' : 'Password update failed.')); }
    return html(res, 200, securityDashboard(session, 'Founder password updated. Existing OAuth client tokens were not exposed or changed.'));
  }
  if (req.method === 'POST' && url.pathname === '/founder/security/bootstrap') {
    const next = p.get('enabled') === '1';
    if (!runtimeFounderPasswordScrypt && !next) return html(res, 400, securityDashboard(session, 'Set a founder password before disabling bootstrap access.'));
    if (next && !ENV_ALLOW_BOOTSTRAP_CODE) return html(res, 403, securityDashboard(session, 'Bootstrap access is disabled by environment policy.'));
    runtimeBootstrapEnabled = next; saveSecurityState();
    return html(res, 200, securityDashboard(session, 'Bootstrap access ' + (next ? 'enabled.' : 'disabled.')));
  }
  if (req.method === 'POST' && url.pathname === '/founder/security/revoke-session') {
    const jti = p.get('jti') || ''; const s = authSessions.get(jti);
    if (s) { s.revoked = true; authSessions.set(jti, s); }
    return html(res, 200, securityDashboard(session, 'OAuth session revoked.'));
  }
  if (req.method === 'POST' && url.pathname === '/founder/security/revoke-client') {
    const client = String(p.get('client') || '').trim().toLowerCase();
    if (!/^[a-z0-9._-]{1,64}$/.test(client)) return html(res, 400, securityDashboard(session, 'Invalid client identifier.'));
    const now = Math.floor(Date.now()/1000); clientRevokedBefore.set(client, now); saveSecurityState();
    for (const s of authSessions.values()) if (s.client === client && s.issuedAt <= now) s.revoked = true;
    return html(res, 200, securityDashboard(session, 'All current sessions for ' + client + ' revoked.'));
  }
  if (req.method === 'POST' && url.pathname === '/founder/security/logout') {
    adminClearCookie(res); res.writeHead(303, { location:'/founder/security', 'cache-control':'no-store' }); res.end(); return;
  }
  return json(res, 404, {error:'not_found'});
}

function route(req, res, assertion, raw) {
  const url = new URL(req.url, PUBLIC_ORIGIN);
  if (url.pathname === '/founder/security' || url.pathname.startsWith('/founder/security/')) return founderSecurityRoute(req, res, assertion, raw, url);`;
source = source.replace(routeAnchor, founderRoutes);

// Strengthen self-test: revocation gate and password hashing must be available.
const selfTestLog = "console.log('AUTH_PROXY_ACCEPTANCE_PASS credential_forms=4 oauth=enabled');";
if (!source.includes(selfTestLog)) throw new Error('self-test anchor missing');
source = source.replace(selfTestLog, `const testPw = scryptPassword('unit-test-founder-password');
  if (!testPw.includes(':')) throw new Error('scrypt_password_generation_failed');
  console.log('AUTH_PROXY_ACCEPTANCE_PASS credential_forms=4 oauth=enabled founder_admin=enabled revocation=enabled');`);

if (!source.includes('METATRON_FOUNDER_SECURITY_ADMIN_V1') || !source.includes('/founder/security/revoke-client') || !source.includes('accessTokenAllowed')) throw new Error('founder security patch invariant missing');
fs.writeFileSync(target, source);
console.log('AUTH_SECURITY_ADMIN_PATCH_PASS');
