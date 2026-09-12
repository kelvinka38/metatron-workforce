import http from 'node:http';
import net from 'node:net';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import { createHash, createHmac, timingSafeEqual, randomBytes, scryptSync, createCipheriv, createDecipheriv } from 'node:crypto';

const LISTEN_PORT = 3002;
const UPSTREAM_HOST = '127.0.0.1';
const UPSTREAM_PORT = 3003;
const MAX_BODY_BYTES = 2 * 1024 * 1024;
const VERIFIED_META_KEY = 'metatron/verifiedClient';
const ASSERTION_META_KEY = 'metatron/proxyAssertion';
const PRINCIPAL_META_KEY = 'metatron/authenticatedPrincipal';
const SCOPES_META_KEY = 'metatron/oauthScopes';
const OWNER_CONSENT_COOKIE = 'metatron_owner_consent';
const OWNER_CONSENT_TTL_SECONDS = 12 * 60 * 60;
const WORKFORCE_AUTH_HOST = 'workforce-production';
const WORKFORCE_AUTH_PORT = 8080;
const BOUNDED_CLIENT_PROTOCOL = '2025-11-25';
const PUBLIC_ORIGIN = 'https://ssh.metatron.vn';
const RESOURCE_URL = `${PUBLIC_ORIGIN}/mcp`;
const RESOURCE_METADATA_URL = `${PUBLIC_ORIGIN}/.well-known/oauth-protected-resource`;
// METATRON_CLIENT_AGNOSTIC_OAUTH_V1
const ACCESS_CODE_HASH = String(process.env.METATRON_FOUNDER_AUTH_SHA256 || 'e4ccda459f5d0ee60fb9e8998b084cbf5979699134dfa81013de07af75a465b1').trim().toLowerCase();
const AUTH_SECURITY_STATE_PATH = String(process.env.METATRON_AUTH_SECURITY_STATE_PATH || '/var/lib/metatron-auth/security.json').trim();
// METATRON_MCP_CONTROL_STATE_V1
const HOST_CONTROL_STATE_WORKSPACE = 'ssh_mcp';
const HOST_CONTROL_STATE_PATH = '.runtime-auth-security.enc';
const HOST_SSH_KEY_PATH = '/ssh/id_ed25519';
const HOST_SSH_TARGET = 'metatron-mcp@host.docker.internal';
const HOST_SSH_ARGS = ['-i', HOST_SSH_KEY_PATH, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=/dev/null', '-o', 'ConnectTimeout=3', HOST_SSH_TARGET];
let hostControlStateHealthy = false;
let hostControlStateLastError = '';
let hostControlStateLoaded = false;

function controlStateKey() {
  const material = fs.readFileSync(HOST_SSH_KEY_PATH);
  return createHash('sha256').update('metatron-mcp-control-state-v1\0', 'utf8').update(material).digest();
}
function hostBrokerRequest(op, args) {
  if (!fs.existsSync(HOST_SSH_KEY_PATH)) throw new Error('host_control_state_key_missing');
  const request = JSON.stringify({ op, args });
  const result = spawnSync('ssh', HOST_SSH_ARGS, { input: request, encoding: 'utf8', timeout: 5000, maxBuffer: 5 * 1024 * 1024 });
  const output = String(result.stdout || '').trim();
  if (!output) throw new Error('host_control_state_no_response');
  let parsed;
  try { parsed = JSON.parse(output); } catch { throw new Error('host_control_state_invalid_response'); }
  if (parsed?.broker?.transport_user !== 'metatron-mcp' || parsed?.broker?.broker_euid !== 0) throw new Error('host_control_state_broker_identity_invalid');
  hostControlStateHealthy = true;
  hostControlStateLastError = '';
  return parsed;
}
function encryptControlState(plain) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', controlStateKey(), iv);
  const ciphertext = Buffer.concat([cipher.update(String(plain), 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return JSON.stringify({ version: 1, algorithm: 'aes-256-gcm', iv: iv.toString('base64'), tag: tag.toString('base64'), data: ciphertext.toString('base64') });
}
function decryptControlState(serialized) {
  const envelope = JSON.parse(serialized);
  if (envelope?.version !== 1 || envelope?.algorithm !== 'aes-256-gcm') throw new Error('host_control_state_envelope_invalid');
  const decipher = createDecipheriv('aes-256-gcm', controlStateKey(), Buffer.from(envelope.iv, 'base64'));
  decipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
  return Buffer.concat([decipher.update(Buffer.from(envelope.data, 'base64')), decipher.final()]).toString('utf8');
}
function readHostControlState() {
  const response = hostBrokerRequest('workspace_read_file', { workspace: HOST_CONTROL_STATE_WORKSPACE, path: HOST_CONTROL_STATE_PATH });
  if (!response.ok) return '';
  return decryptControlState(response.stdout || '');
}
function writeHostControlState(plain) {
  const encrypted = encryptControlState(plain);
  const response = hostBrokerRequest('workspace_write_file', {
    workspace: HOST_CONTROL_STATE_WORKSPACE,
    path: HOST_CONTROL_STATE_PATH,
    content_b64: Buffer.from(encrypted, 'utf8').toString('base64'),
  });
  if (!response.ok) throw new Error('host_control_state_write_failed');
}
function hostControlStateReady() {
  if (!fs.existsSync(HOST_SSH_KEY_PATH)) return process.env.AUTH_PROXY_SELF_TEST === '1';
  try {
    const response = hostBrokerRequest('server_status', {});
    return !!response.ok;
  } catch (error) {
    hostControlStateHealthy = false;
    hostControlStateLastError = error.message;
    return false;
  }
}
const ENV_FOUNDER_PASSWORD_SCRYPT = String(process.env.METATRON_FOUNDER_PASSWORD_SCRYPT || '').trim();
const ENV_ALLOW_BOOTSTRAP_CODE = process.env.METATRON_DISABLE_BOOTSTRAP_CODE !== '1';
const ADMIN_SESSION_TTL_SECONDS = 15 * 60;
const authSessions = new Map();
const clientRevokedBefore = new Map();
let runtimeFounderPasswordScrypt = ENV_FOUNDER_PASSWORD_SCRYPT;
let runtimeBootstrapEnabled = ENV_ALLOW_BOOTSTRAP_CODE;

function applySecurityState(parsed) {
  if (!parsed || typeof parsed !== 'object') return;
  if (typeof parsed.founderPasswordScrypt === 'string' && parsed.founderPasswordScrypt) runtimeFounderPasswordScrypt = parsed.founderPasswordScrypt;
  if (typeof parsed.bootstrapEnabled === 'boolean') runtimeBootstrapEnabled = parsed.bootstrapEnabled && ENV_ALLOW_BOOTSTRAP_CODE;
  if (parsed.clientRevokedBefore && typeof parsed.clientRevokedBefore === 'object') {
    for (const [client, at] of Object.entries(parsed.clientRevokedBefore)) if (Number.isFinite(Number(at))) clientRevokedBefore.set(client, Number(at));
  }
  if (Array.isArray(parsed.authSessions)) {
    for (const session of parsed.authSessions) {
      if (!session || typeof session.jti !== 'string' || typeof session.client !== 'string') continue;
      authSessions.set(session.jti, {
        jti: session.jti,
        client: session.client,
        scope: typeof session.scope === 'string' ? session.scope : 'mcp:tools',
        issuedAt: Number(session.issuedAt) || 0,
        expiresAt: Number(session.expiresAt) || 0,
        digestPrefix: typeof session.digestPrefix === 'string' ? session.digestPrefix : '',
        revoked: !!session.revoked,
      });
    }
  }
}
function loadSecurityState() {
  let loaded = false;
  if (fs.existsSync(HOST_SSH_KEY_PATH)) {
    try {
      const raw = readHostControlState();
      if (raw) { applySecurityState(JSON.parse(raw)); loaded = true; hostControlStateLoaded = true; console.log('AUTH_CONTROL_STATE_HOST_LOAD_PASS'); }
    } catch (error) {
      hostControlStateHealthy = false;
      hostControlStateLastError = error.message;
      console.error('AUTH_CONTROL_STATE_HOST_LOAD_FAILED', error.message);
    }
  }
  if (loaded) return;
  try {
    if (!fs.existsSync(AUTH_SECURITY_STATE_PATH)) return;
    applySecurityState(JSON.parse(fs.readFileSync(AUTH_SECURITY_STATE_PATH, 'utf8')));
    console.warn('AUTH_CONTROL_STATE_LOCAL_FALLBACK_LOAD');
  } catch (error) {
    console.error('AUTH_SECURITY_STATE_LOAD_FAILED', error.message);
  }
}
function saveSecurityState() {
  const next = {
    version: 2,
    founderPasswordScrypt: runtimeFounderPasswordScrypt,
    bootstrapEnabled: runtimeBootstrapEnabled,
    clientRevokedBefore: Object.fromEntries(clientRevokedBefore),
    authSessions: [...authSessions.values()],
    updatedAt: new Date().toISOString(),
  };
  const serialized = JSON.stringify(next, null, 2);
  try {
    if (fs.existsSync(HOST_SSH_KEY_PATH)) {
      writeHostControlState(serialized);
      console.log('AUTH_CONTROL_STATE_HOST_SAVE_PASS');
    }
    const slash = AUTH_SECURITY_STATE_PATH.lastIndexOf('/');
    if (slash > 0) fs.mkdirSync(AUTH_SECURITY_STATE_PATH.slice(0, slash), { recursive: true, mode: 0o700 });
    const temp = AUTH_SECURITY_STATE_PATH + '.tmp';
    fs.writeFileSync(temp, serialized, { mode: 0o600 });
    fs.renameSync(temp, AUTH_SECURITY_STATE_PATH);
    try { fs.chmodSync(AUTH_SECURITY_STATE_PATH, 0o600); } catch {}
  } catch (error) {
    console.error('AUTH_SECURITY_STATE_SAVE_FAILED', error.message);
    throw new Error('security_state_persistence_failed');
  }
}
loadSecurityState();
// METATRON_MCP_CONTROL_STATE_INIT_V2
// METATRON_MCP_CONTROL_STATE_SINGLE_WRITER_V2
// A replica that loaded the encrypted host authority is startup-read-only. Only a first-run
// migration with no host authority may initialize it; runtime mutations persist through the
// ACTIVE authorization path.
if (fs.existsSync(HOST_SSH_KEY_PATH) && !hostControlStateLoaded) {
  try {
    saveSecurityState();
    console.log('AUTH_CONTROL_STATE_HOST_INITIALIZED');
  } catch (error) {
    hostControlStateHealthy = false;
    hostControlStateLastError = error.message;
    console.error('AUTH_CONTROL_STATE_HOST_INITIALIZE_FAILED', error.message);
  }
} else if (hostControlStateLoaded) {
  console.log('AUTH_CONTROL_STATE_HOST_REUSE_NO_WRITE');
}
// METATRON_FOUNDER_SECURITY_RECOVERY_V1
// Never allow a container replacement or missing state file to strand the Founder with no
// usable verifier. Environment policy can still explicitly disable bootstrap recovery.
if (!runtimeFounderPasswordScrypt && !runtimeBootstrapEnabled && ENV_ALLOW_BOOTSTRAP_CODE) {
  runtimeBootstrapEnabled = true;
  console.warn('AUTH_SECURITY_RECOVERY_BOOTSTRAP_ENABLED reason=no_founder_password');
}
if (!/^[0-9a-f]{64}$/.test(ACCESS_CODE_HASH)) throw new Error('invalid_metatron_founder_auth_hash');
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
function verifyFounderSecret(value) {
  const candidate = String(value || '');
  if (runtimeFounderPasswordScrypt) {
    const parts = runtimeFounderPasswordScrypt.split(':');
    if (parts.length !== 2 || !/^[0-9a-f]{32,128}$/i.test(parts[0]) || !/^[0-9a-f]{64,256}$/i.test(parts[1]) || parts[1].length % 2 !== 0) {
      throw new Error('invalid_metatron_founder_password_scrypt');
    }
    const [saltHex, expectedHexRaw] = parts;
    const expectedHex = expectedHexRaw.toLowerCase();
    const derived = scryptSync(candidate, Buffer.from(saltHex, 'hex'), expectedHex.length / 2, { N: 16384, r: 8, p: 1, maxmem: 64 * 1024 * 1024 }).toString('hex');
    if (safeHexEqual(derived, expectedHex)) return true;
  }
  return runtimeBootstrapEnabled && safeHexEqual(sha256(candidate.trim()), ACCESS_CODE_HASH);
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
function accessTokenAllowed(payload) {
  if (!payload || typeof payload.jti !== 'string') return false;
  const clientKey = String(payload.client_id || payload.sub || '');
  if (!clientKey) return false;
  const revokedBefore = clientRevokedBefore.get(clientKey) || 0;
  if ((Number(payload.iat) || 0) <= revokedBefore) return false;
  const session = authSessions.get(payload.jti);
  if (!session) return process.env.AUTH_PROXY_SELF_TEST === '1' && payload.sub === 'claude';
  return !session.revoked && session.client === clientKey && session.expiresAt > Math.floor(Date.now()/1000);
}
function recordAuthSession(payload, token) {
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
  return String(value ?? '').replace(/[&<>'"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[c]));
}
function fmtTime(epoch) {
  if (!epoch) return '—';
  try { return new Date(epoch * 1000).toLocaleString('en-GB', { timeZone: 'UTC', hour12: false }) + ' UTC'; } catch { return '—'; }
}
function securityShell(content, title = 'Founder Security') {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#0b1020"><title>${esc(title)} · Metatron</title><style>
  :root{color-scheme:light;--bg:#f5f7fb;--card:#fff;--text:#101828;--muted:#667085;--line:#e4e7ec;--brand:#3157d5;--brand2:#263fa9;--ok:#067647;--warn:#b54708;--danger:#b42318;--soft:#eef3ff}*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 50% -10%,#e7edff 0,#f5f7fb 40%);font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Inter,Roboto,Helvetica,Arial,sans-serif;color:var(--text);min-height:100vh}.wrap{width:min(100%,960px);margin:0 auto;padding:32px 18px 48px}.top{display:flex;align-items:center;gap:12px;margin-bottom:24px}.mark{width:40px;height:40px;display:grid;place-items:center;border-radius:12px;background:linear-gradient(145deg,var(--brand),var(--brand2));color:white;font-weight:850}.topcopy{flex:1}.eyebrow{font-size:11px;letter-spacing:.14em;font-weight:850;color:var(--brand2)}.title{font-size:17px;font-weight:800;margin-top:2px}.secure{font-size:12px;color:var(--ok);font-weight:750;border:1px solid #abefc6;background:#ecfdf3;padding:6px 9px;border-radius:999px}.card{background:var(--card);border:1px solid rgba(16,24,40,.07);border-radius:20px;box-shadow:0 16px 48px rgba(16,24,40,.09);padding:24px;margin-bottom:18px}h1{font-size:30px;letter-spacing:-.025em;margin:0 0 8px}h2{font-size:18px;margin:0 0 14px}.lead{color:var(--muted);line-height:1.55;margin:0 0 22px}.grid{display:grid;grid-template-columns:1fr 1fr;gap:16px}.stat{border:1px solid var(--line);border-radius:14px;padding:14px}.stat span{display:block;color:var(--muted);font-size:12px;margin-bottom:4px}.stat strong{font-size:15px}.row{display:flex;align-items:center;gap:12px;justify-content:space-between;padding:12px 0;border-bottom:1px solid var(--line)}.row:last-child{border-bottom:0}.meta{font-size:12px;color:var(--muted);margin-top:3px;line-height:1.4}.pill{display:inline-block;font-size:11px;font-weight:800;border-radius:999px;padding:5px 8px;background:#f2f4f7;color:#344054}.pill.ok{background:#ecfdf3;color:var(--ok)}.pill.bad{background:#fef3f2;color:var(--danger)}label{display:block;font-size:13px;font-weight:750;margin-bottom:7px}.input{width:100%;border:1px solid #d0d5dd;border-radius:11px;padding:12px 13px;font-size:16px;margin-bottom:12px;outline:none}.input:focus{border-color:#84adff;box-shadow:0 0 0 4px rgba(49,87,213,.11)}button,.btn{border:0;border-radius:10px;padding:10px 13px;font-size:13px;font-weight:800;cursor:pointer}.primary{background:linear-gradient(180deg,var(--brand),var(--brand2));color:#fff}.secondary{background:#f2f4f7;color:#344054}.danger{background:#fef3f2;color:var(--danger);border:1px solid #fecdca}.notice{font-size:13px;line-height:1.5;background:var(--soft);border:1px solid #c7d7fe;border-radius:12px;padding:12px 14px;color:#344054}.actions{display:flex;gap:8px;flex-wrap:wrap}.footer{text-align:center;color:#98a2b3;font-size:11px;padding-top:12px}@media(max-width:680px){.wrap{padding-top:20px}.grid{grid-template-columns:1fr}.card{border-radius:16px;padding:20px}h1{font-size:26px}.row{align-items:flex-start;flex-direction:column}.row .actions{width:100%}.row .actions form{flex:1}.row .actions button{width:100%}}
  </style></head><body><div class="wrap" data-ui="METATRON_FOUNDER_SECURITY_ADMIN_V1"><div class="top"><div class="mark">M</div><div class="topcopy"><div class="eyebrow">METATRON</div><div class="title">Founder Security</div></div><div class="secure">Protected</div></div>${content}<div class="footer">Metatron Institutional Access · Founder security control plane</div></div></body></html>`;
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
  const stateStore = fs.existsSync(HOST_SSH_KEY_PATH) ? 'Encrypted host-broker control state' : 'Runtime-local auth store';
  const msg = message ? '<div class="notice" style="margin-bottom:18px">'+esc(message)+'</div>' : '';
  return securityShell('<section class="card"><h1>Security control center</h1><p class="lead">Manage founder authentication and authorization sessions. Raw credentials and bearer tokens are never displayed.</p>'+msg+'<div class="grid"><div class="stat"><span>Founder password</span><strong>'+status+'</strong></div><div class="stat"><span>Bootstrap verification</span><strong>'+bootstrap+'</strong></div><div class="stat"><span>Active OAuth sessions</span><strong>'+active.length+'</strong></div><div class="stat"><span>Admin session</span><strong>15 minute protected session</strong></div><div class="stat"><span>Security state</span><strong>'+esc(stateStore)+'</strong></div></div></section><section class="card"><h2>Authorized clients</h2>'+clientRows+'</section><section class="card"><h2>OAuth sessions</h2>'+sessionRows+'</section><section class="card"><h2>Founder password</h2><form method="post" action="/founder/security/password"><input type="hidden" name="csrf" value="'+esc(session.csrf)+'"><div class="grid"><div><label for="newpw">New password</label><input class="input" id="newpw" name="new_password" type="password" autocomplete="new-password" minlength="14" required></div><div><label for="confirm">Confirm password</label><input class="input" id="confirm" name="confirm_password" type="password" autocomplete="new-password" minlength="14" required></div></div><button class="primary" type="submit">Set / rotate founder password</button></form><p class="meta">Minimum 14 characters. Metatron stores only an scrypt-derived verifier.</p></section><section class="card"><h2>Bootstrap access</h2><p class="lead">Once a founder password is configured, disable the bootstrap code for normal operation. It can only be re-enabled from an authenticated founder session while the environment allows bootstrap.</p><div class="actions"><form method="post" action="/founder/security/bootstrap"><input type="hidden" name="csrf" value="'+esc(session.csrf)+'"><input type="hidden" name="enabled" value="'+(runtimeBootstrapEnabled?'0':'1')+'"><button class="'+(runtimeBootstrapEnabled?'danger':'secondary')+'" type="submit">'+(runtimeBootstrapEnabled?'Disable bootstrap code':'Enable bootstrap code')+'</button></form><form method="post" action="/founder/security/logout"><input type="hidden" name="csrf" value="'+esc(session.csrf)+'"><button class="secondary" type="submit">Sign out admin</button></form></div></section><div class="notice">Security state is enforced by the MCP authorization runtime. OAuth tokens are also invalidated when the authorization service signing assertion rotates on runtime replacement.</div>');
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
      if (genericClient && principal && scope.split(/\s+/).includes('mcp:tools') && audienceOk && issuerOk) {
        return { client: genericClient, principal, scopes: scope.split(/\s+/).filter(Boolean), source: 'oauth-bearer', digestPrefix: sha256(candidate.token).slice(0, 12), oauth: true };
      }
    }
  }
  return { client: null, principal: null, scopes: [], source: candidate.source, digestPrefix: sha256(candidate.token).slice(0, 12), oauth: false };
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
function filteredHeaders(headers, bodyLength, verifiedClient) {
  const out = {};
  for (const [key, value] of Object.entries(headers)) {
    const lower = key.toLowerCase();
    if (['host', 'authorization', 'x-api-key', 'x-metatron-client-token', 'content-length', 'connection', 'transfer-encoding'].includes(lower)) continue;
    if (value !== undefined) out[key] = value;
  }
  out.host = `${UPSTREAM_HOST}:${UPSTREAM_PORT}`;
  out['content-length'] = String(bodyLength);
  // METATRON_MCP_PROTOCOL_TRANSPARENT_V1
  // Preserve the client-negotiated MCP-Protocol-Version header unchanged.
  return out;
}
function json(res, status, body, headers = {}) {
  const data = Buffer.from(JSON.stringify(body), 'utf8');
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': String(data.length), ...headers });
  res.end(data);
}
function html(res, status, body) {
  const data = Buffer.from(body, 'utf8');
  res.writeHead(status, {
    'content-type': 'text/html; charset=utf-8',
    'content-length': String(data.length),
    'cache-control': 'no-store, max-age=0',
    'content-security-policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
    'x-content-type-options': 'nosniff',
    'x-frame-options': 'DENY',
    'referrer-policy': 'no-referrer',
    'permissions-policy': 'camera=(), microphone=(), geolocation=()'
  });
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
function authorizePage(params, error = '', notice = '', ownerAuthenticated = false, clientLabel = 'MCP client') {
  const hidden = ['client_id','redirect_uri','state','code_challenge','code_challenge_method','scope','response_type'].map(k => `<input type="hidden" name="${k}" value="${esc(params.get(k) || '')}">`).join('');
  const errorBlock = error ? `<p style="color:#b42318"><strong>Authorization failed:</strong> ${esc(error)}</p>` : '';
  const noticeBlock = notice ? `<p style="color:#067647">${esc(notice)}</p>` : '';
  const ownerBlock = ownerAuthenticated
    ? '<p>Owner identity already verified for this browser session.</p>'
    : `<form method="post" action="/oauth/owner/request-code">${hidden}<button type="submit">Send Telegram code</button></form><form method="post" action="/oauth/authorize">${hidden}<label style="display:block;margin-top:16px">Telegram verification code</label><input name="owner_code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" required style="display:block;width:100%;box-sizing:border-box;padding:12px;margin:8px 0 16px"><button type="submit">Authorize MCP client</button></form>`;
  const authorizeOnly = ownerAuthenticated ? `<form method="post" action="/oauth/authorize">${hidden}<button type="submit">Authorize MCP client</button></form>` : '';
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Metatron Authorization</title></head><body style="font-family:-apple-system,BlinkMacSystemFont,sans-serif;max-width:560px;margin:48px auto;padding:0 20px"><h2>Authorize ${esc(clientLabel)}</h2><p>Grant this MCP client access to protected Metatron tools.</p><p><strong>Flow:</strong> OAuth 2.0 authorization code + PKCE S256</p>${errorBlock}${noticeBlock}${ownerBlock}${authorizeOnly}<p style="color:#667085;font-size:13px">Owner verification uses the existing Metatron Telegram identity and is independent of the MCP client vendor.</p></body></html>`;
}
function validRedirect(uri) {
  if (typeof uri !== 'string' || uri.length > 2048) return false;
  try {
    const u = new URL(uri);
    return u.protocol === 'https:' || u.hostname === 'localhost' || u.hostname === '127.0.0.1';
  } catch { return false; }
}
function cookieValue(req, name) {
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

function parseForm(raw) {
  return new URLSearchParams(raw);
}
function handleRegister(req, res, raw, secret) {
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
function handleAuthorizeGet(req, res, secret) {
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
function grantAuthorization(p, res, secret, registration) {
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
  if (!/^\d{6}$/.test(ownerCode)) return html(res, 401, authorizePage(p, 'Telegram verification required', '', false, registration.client_name || 'MCP client'));
  workforceAuthPost('/workplace/api/auth/verify', {code:ownerCode}, (error, status) => {
    if (error || status !== 200) return html(res, 401, authorizePage(p, 'Invalid or expired Telegram code', '', false, registration.client_name || 'MCP client'));
    setOwnerConsentCookie(res, secret);
    return grantAuthorization(p, res, secret, registration);
  });
}
function handleToken(req, res, raw, secret) {
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
  // Claude Connectors must authenticate at the HTTP boundary so the host can discover OAuth.
  // User-Agent only decides whether to issue the OAuth challenge; it never grants identity.
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
function founderSecurityRoute(req, res, assertion, raw, url) {
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
    if (s) { s.revoked = true; authSessions.set(jti, s); saveSecurityState(); }
    return html(res, 200, securityDashboard(session, 'OAuth session revoked.'));
  }
  if (req.method === 'POST' && url.pathname === '/founder/security/revoke-client') {
    const client = String(p.get('client') || '').trim();
    if (!/^[A-Za-z0-9._-]{1,2048}$/.test(client)) return html(res, 400, securityDashboard(session, 'Invalid client identifier.'));
    const now = Math.floor(Date.now()/1000); clientRevokedBefore.set(client, now); saveSecurityState();
    for (const s of authSessions.values()) if (s.client === client && s.issuedAt <= now) s.revoked = true;
    return html(res, 200, securityDashboard(session, 'All current sessions for ' + client + ' revoked.'));
  }
  if (req.method === 'POST' && url.pathname === '/founder/security/logout') {
    adminClearCookie(res); res.writeHead(303, { location:'/founder/security', 'cache-control':'no-store' }); res.end(); return;
  }
  return json(res, 404, {error:'not_found'});
}

// METATRON_MCP_CHEAP_READINESS_V2
// High-frequency router/Docker health probes must never spawn SSH or a stateless MCP child.
// Host authority is refreshed out-of-band; /ready only checks cached authority state plus
// the local supergateway listener. Functional tools/list remains a release acceptance check.
function handleReadiness(res, assertion) {
  if (typeof assertion !== 'string' || assertion.length < 32 || hostControlStateHealthy !== true) {
    return json(res, 503, { status: 'NOT_READY', component: 'mcp-control-plane', dependency: 'host-control-state-cache' }, { 'cache-control': 'no-store' });
  }
  let settled = false;
  const socket = net.createConnection({ host: UPSTREAM_HOST, port: UPSTREAM_PORT });
  const finish = (ok) => {
    if (settled) return;
    settled = true;
    try { socket.destroy(); } catch {}
    if (ok) return json(res, 200, { status: 'READY', component: 'mcp-control-plane', controlState: 'host-broker-cached', dependency: 'supergateway-tcp' }, { 'cache-control': 'no-store' });
    return json(res, 503, { status: 'NOT_READY', component: 'mcp-control-plane', dependency: 'supergateway-tcp' }, { 'cache-control': 'no-store' });
  };
  socket.setTimeout(1200, () => finish(false));
  socket.once('connect', () => finish(true));
  socket.once('error', () => finish(false));
}

function route(req, res, assertion, raw) {
  const url = new URL(req.url, PUBLIC_ORIGIN);
  if (req.method === 'GET' && url.pathname === '/live') return json(res, 200, { status:'UP', component:'mcp-control-plane' }, { 'cache-control':'no-store' });
  if (req.method === 'GET' && url.pathname === '/ready') return handleReadiness(res, assertion);
  if (url.pathname === '/founder/security' || url.pathname.startsWith('/founder/security/')) return founderSecurityRoute(req, res, assertion, raw, url);
  if (req.method === 'GET' && (url.pathname === '/.well-known/oauth-protected-resource' || url.pathname === '/.well-known/oauth-protected-resource/mcp')) return protectedResourceMetadata(res);
  if (req.method === 'GET' && (url.pathname === '/.well-known/oauth-authorization-server' || url.pathname === '/.well-known/openid-configuration')) return oauthMetadata(res);
  if (req.method === 'POST' && url.pathname === '/oauth/register') return handleRegister(req, res, raw, assertion);
  if (req.method === 'GET' && url.pathname === '/oauth/authorize') return handleAuthorizeGet(req, res, assertion);
  if (req.method === 'POST' && url.pathname === '/oauth/owner/request-code') return handleOwnerRequestCode(req, res, raw, assertion);
  if (req.method === 'POST' && url.pathname === '/oauth/authorize') return handleAuthorizePost(req, res, raw, assertion);
  if (req.method === 'POST' && url.pathname === '/oauth/token') return handleToken(req, res, raw, assertion);
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
    if (res.writableEnded) return;
    try {
      route(req, res, assertion, Buffer.concat(chunks).toString('utf8'));
    } catch (error) {
      console.error('AUTH_PROXY_ROUTE_EXCEPTION', error?.stack || error?.message || String(error));
      if (!res.headersSent) json(res, 500, { error: 'auth_proxy_request_failed' }, { 'cache-control': 'no-store' });
      else if (!res.writableEnded) res.end();
    }
  });
  // METATRON_AUTH_REQUEST_EXCEPTION_GUARD_V1
  req.on('error', error => {
    if (!res.headersSent) res.writeHead(400, { 'content-type': 'application/json' });
    if (!res.writableEnded) res.end(JSON.stringify({ error: 'bad_request' }));
    console.error('AUTH_PROXY_REQUEST_ERROR', error.message);
  });
}
function selfTest() {
  // METATRON_CLIENT_AGNOSTIC_POST_PATCH_V2
  if (typeof authReplicaModelSelfTest === 'function') authReplicaModelSelfTest();

  const assertion = 'a'.repeat(64);
  const legacyToken = 'unit-test-token';
  const hashes = new Map([['unit', sha256(legacyToken)]]);
  const legacyIdentity = identifyRequest({ authorization: 'Bearer ' + legacyToken }, assertion, hashes);
  if (!legacyIdentity.client || legacyIdentity.principal !== 'legacy-client:unit') throw new Error('legacy_adapter_acceptance_failed');

  const now = Math.floor(Date.now() / 1000);
  const clientId = mintSigned({
    kind: 'oauth_client',
    client_name: 'Generic MCP Self-Test Client',
    redirect_uris: ['https://client.example/callback'],
    application_type: 'web',
    iat: now,
    exp: now + 300,
    jti: 'generic-client-self-test',
  }, assertion);

  // Exercise the actual DCR handler synchronously before any runtime/container preflight.
  let dcrStatus = 0;
  let dcrBody = '';
  const dcrHeaders = {};
  const fakeRes = {
    writeHead(status, headers = {}) { dcrStatus = status; Object.assign(dcrHeaders, headers); },
    end(data = '') { dcrBody = Buffer.isBuffer(data) ? data.toString('utf8') : String(data || ''); },
  };
  handleRegister({}, fakeRes, JSON.stringify({
    client_name: 'Universal MCP Self-Test',
    redirect_uris: ['https://client.example/callback'],
    application_type: 'web',
    token_endpoint_auth_method: 'none',
    grant_types: ['authorization_code'],
    response_types: ['code'],
  }), assertion);
  if (dcrStatus !== 201) throw new Error('dcr_handler_status_' + dcrStatus);
  const dcr = JSON.parse(dcrBody);
  if (!dcr.client_id || dcr.client_name !== 'Universal MCP Self-Test' || dcr.token_endpoint_auth_method !== 'none') throw new Error('dcr_handler_response_invalid');
  if (!registeredClient(dcr.client_id, 'https://client.example/callback', assertion)) throw new Error('dcr_handler_registration_not_verifiable');

  const payload = {
    kind: 'access_token',
    iss: PUBLIC_ORIGIN,
    aud: RESOURCE_URL,
    sub: 'founder',
    client_id: clientId,
    scope: 'mcp:tools',
    iat: now,
    exp: now + 60,
    jti: 'generic-oauth-self-test',
  };
  authSessions.set(payload.jti, {
    jti: payload.jti,
    client: clientId,
    scope: payload.scope,
    issuedAt: payload.iat,
    expiresAt: payload.exp,
    digestPrefix: 'generic-self-test',
    revoked: false,
  });
  const accessToken = mintSigned(payload, assertion);
  const identity = identifyRequest({ authorization: 'Bearer ' + accessToken }, assertion, hashes);
  if (identity.client !== clientId || identity.principal !== 'founder' || !identity.scopes.includes('mcp:tools')) throw new Error('generic_oauth_identity_failed');

  const spoofed = JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'tools/list', params: { _meta: {
    [VERIFIED_META_KEY]: 'spoof',
    [ASSERTION_META_KEY]: 'spoof',
    [PRINCIPAL_META_KEY]: 'spoof',
    [SCOPES_META_KEY]: 'spoof',
  } } });
  const transformed = JSON.parse(authorizeBody(spoofed, identity, assertion).body);
  if (transformed.params._meta[VERIFIED_META_KEY] !== clientId) throw new Error('verified_client_not_injected');
  if (transformed.params._meta[ASSERTION_META_KEY] !== assertion) throw new Error('proxy_assertion_not_injected');
  if (transformed.params._meta[PRINCIPAL_META_KEY] !== 'founder') throw new Error('principal_not_injected');
  if (transformed.params._meta[SCOPES_META_KEY] !== 'mcp:tools') throw new Error('scopes_not_injected');

  const headers = filteredHeaders({
    'mcp-protocol-version': '2026-07-28',
    authorization: 'Bearer ' + accessToken,
  }, 10, clientId);
  if (headers['mcp-protocol-version'] !== '2026-07-28') throw new Error('protocol_transparency_failed');
  if ('authorization' in headers) throw new Error('authorization_forwarding_not_stripped');

  const session = authSessions.get(payload.jti);
  session.revoked = true;
  authSessions.set(payload.jti, session);
  if (identifyRequest({ authorization: 'Bearer ' + accessToken }, assertion, hashes).client) throw new Error('session_revocation_not_enforced');
  authSessions.delete(payload.jti);

  console.log('CLIENT_AGNOSTIC_DCR_ACCEPTANCE_PASS');
  console.log('CLIENT_AGNOSTIC_OAUTH_ACCEPTANCE_PASS');
  console.log('AUTH_PROXY_ACCEPTANCE_PASS credential_forms=generic-oauth+legacy-adapter oauth=enabled founder_admin=enabled revocation=enabled protocol=transparent');
}

if (process.env.AUTH_PROXY_SELF_TEST === '1') selfTest();
else {
  const assertion = process.env.METATRON_PROXY_ASSERTION;
  if (typeof assertion !== 'string' || assertion.length < 32) throw new Error('METATRON_PROXY_ASSERTION missing');
  // METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1
// Refresh authority health at low frequency instead of on every readiness request.
if (process.env.AUTH_PROXY_SELF_TEST !== '1') {
  setInterval(() => { try { hostControlStateReady(); } catch {} }, 60000).unref();
}

http.createServer((req, res) => handler(req, res, assertion)).listen(LISTEN_PORT, '0.0.0.0', () => console.log(`AUTH_PROXY_LISTENING port=${LISTEN_PORT} upstream=${UPSTREAM_HOST}:${UPSTREAM_PORT} oauth=enabled`));
}
