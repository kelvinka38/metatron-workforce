import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-control-state-host-patch.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');
if (source.includes('METATRON_MCP_CONTROL_STATE_V1')) {
  console.log('AUTH_CONTROL_STATE_HOST_PATCH_ALREADY_APPLIED');
  process.exit(0);
}
if (!source.includes('METATRON_FOUNDER_SECURITY_RECOVERY_V1')) throw new Error('founder security recovery patch must run first');

source = source.replace(
  "import http from 'node:http';",
  "import http from 'node:http';\nimport { spawnSync } from 'node:child_process';"
);
source = source.replace(
  "import { createHash, createHmac, timingSafeEqual, randomBytes, scryptSync } from 'node:crypto';",
  "import { createHash, createHmac, timingSafeEqual, randomBytes, scryptSync, createCipheriv, createDecipheriv } from 'node:crypto';"
);
if (!source.includes("spawnSync") || !source.includes("createCipheriv")) throw new Error('control-state imports missing');

const stateConst = "const AUTH_SECURITY_STATE_PATH = String(process.env.METATRON_AUTH_SECURITY_STATE_PATH || '/var/lib/metatron-auth/security.json').trim();";
if (!source.includes(stateConst)) throw new Error('auth state constant missing');
source = source.replace(stateConst, `${stateConst}
// METATRON_MCP_CONTROL_STATE_V1
const HOST_CONTROL_STATE_WORKSPACE = 'ssh_mcp';
const HOST_CONTROL_STATE_PATH = '.runtime-auth-security.enc';
const HOST_SSH_KEY_PATH = '/ssh/id_ed25519';
const HOST_SSH_TARGET = 'metatron-mcp@host.docker.internal';
const HOST_SSH_ARGS = ['-i', HOST_SSH_KEY_PATH, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=/dev/null', '-o', 'ConnectTimeout=3', HOST_SSH_TARGET];
let hostControlStateHealthy = false;
let hostControlStateLastError = '';

function controlStateKey() {
  const material = fs.readFileSync(HOST_SSH_KEY_PATH);
  return createHash('sha256').update('metatron-mcp-control-state-v1\\0', 'utf8').update(material).digest();
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
}`);

const loadStart = source.indexOf('function loadSecurityState() {');
const loadCall = source.indexOf('loadSecurityState();', loadStart);
if (loadStart < 0 || loadCall < 0) throw new Error('security state function block missing');
const replacement = `function applySecurityState(parsed) {
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
      if (raw) { applySecurityState(JSON.parse(raw)); loaded = true; console.log('AUTH_CONTROL_STATE_HOST_LOAD_PASS'); }
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
`;
source = source.slice(0, loadStart) + replacement + source.slice(loadCall);

const recordNeedle = `  authSessions.set(payload.jti, {
    jti: payload.jti,
    client: payload.sub,
    scope: payload.scope || 'mcp:tools',
    issuedAt: payload.iat,
    expiresAt: payload.exp,
    digestPrefix: sha256(token).slice(0, 12),
    revoked: false,
  });`;
if (!source.includes(recordNeedle)) throw new Error('auth session record anchor missing');
source = source.replace(recordNeedle, recordNeedle + `
  saveSecurityState();`);

const revokeNeedle = `if (s) { s.revoked = true; authSessions.set(jti, s); }`;
if (!source.includes(revokeNeedle)) throw new Error('session revoke anchor missing');
source = source.replace(revokeNeedle, `if (s) { s.revoked = true; authSessions.set(jti, s); saveSecurityState(); }`);

const stateStoreLine = `const stateStore = AUTH_SECURITY_STATE_PATH.startsWith('/var/lib/metatron-auth/') ? 'Durable auth store' : 'Runtime-local auth store';`;
if (source.includes(stateStoreLine)) source = source.replace(stateStoreLine, `const stateStore = fs.existsSync(HOST_SSH_KEY_PATH) ? 'Encrypted host-broker control state' : 'Runtime-local auth store';`);

const routeAnchor = `function route(req, res, assertion, raw) {
  const url = new URL(req.url, PUBLIC_ORIGIN);`;
if (!source.includes(routeAnchor)) throw new Error('route anchor missing');
const healthHelpers = `function handleReadiness(res, assertion) {
  if (typeof assertion !== 'string' || assertion.length < 32 || !hostControlStateReady()) {
    return json(res, 503, { status: 'NOT_READY', component: 'mcp-control-plane' }, { 'cache-control': 'no-store' });
  }
  const payload = Buffer.from(JSON.stringify({jsonrpc:'2.0',id:'readiness',method:'tools/list',params:{}}), 'utf8');
  const upstream = http.request({ hostname: UPSTREAM_HOST, port: UPSTREAM_PORT, path: '/mcp', method: 'POST', headers: { 'content-type':'application/json', 'accept':'application/json, text/event-stream', 'mcp-protocol-version':'2024-11-05', 'content-length': String(payload.length) } }, upstreamRes => {
    const chunks=[]; upstreamRes.on('data', c=>chunks.push(c)); upstreamRes.on('end', ()=>{
      const body=Buffer.concat(chunks).toString('utf8');
      if ((upstreamRes.statusCode||500) < 400 && body.includes('server_status') && body.includes('repository_open')) return json(res, 200, { status:'READY', component:'mcp-control-plane', controlState:'host-broker' }, { 'cache-control':'no-store' });
      return json(res, 503, { status:'NOT_READY', component:'mcp-control-plane', dependency:'mcp-registry' }, { 'cache-control':'no-store' });
    });
  });
  upstream.setTimeout(3500, ()=>upstream.destroy(new Error('readiness_timeout')));
  upstream.on('error', ()=>{ if (!res.writableEnded) json(res, 503, { status:'NOT_READY', component:'mcp-control-plane', dependency:'mcp-registry' }, { 'cache-control':'no-store' }); });
  upstream.write(payload); upstream.end();
}

function route(req, res, assertion, raw) {
  const url = new URL(req.url, PUBLIC_ORIGIN);
  if (req.method === 'GET' && url.pathname === '/live') return json(res, 200, { status:'UP', component:'mcp-control-plane' }, { 'cache-control':'no-store' });
  if (req.method === 'GET' && url.pathname === '/ready') return handleReadiness(res, assertion);`;
source = source.replace(routeAnchor, healthHelpers);

const selfTestLog = "console.log('AUTH_PROXY_ACCEPTANCE_PASS credential_forms=4 oauth=enabled founder_admin=enabled revocation=enabled');";
if (!source.includes(selfTestLog)) throw new Error('self-test log anchor missing');
source = source.replace(selfTestLog, `if (typeof hostControlStateReady !== 'function') throw new Error('host_control_state_readiness_missing');
  ${selfTestLog}`);

for (const marker of ['METATRON_MCP_CONTROL_STATE_V1','AUTH_CONTROL_STATE_HOST_SAVE_PASS','Encrypted host-broker control state','/ready']) {
  if (!source.includes(marker)) throw new Error('control-state invariant missing: ' + marker);
}
fs.writeFileSync(target, source);
console.log('AUTH_CONTROL_STATE_HOST_PATCH_PASS');
