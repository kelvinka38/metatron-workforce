import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-oauth-resilience-g10-patch.mjs <auth-proxy.mjs>');
let s = fs.readFileSync(target, 'utf8');
const MARKER = 'METATRON_MCP_OAUTH_RESILIENCE_G10';
if (s.includes(MARKER)) {
  console.log('AUTH_OAUTH_RESILIENCE_G10_ALREADY_APPLIED');
  process.exit(0);
}
for (const required of ['METATRON_CLIENT_AGNOSTIC_OAUTH_V1','METATRON_CLIENT_AGNOSTIC_POST_PATCH_V2','METATRON_MCP_CONTROL_STATE_V1','METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1']) {
  if (!s.includes(required)) throw new Error('missing prerequisite marker: ' + required);
}

function replaceOnce(oldText, newText, label) {
  const first = s.indexOf(oldText);
  if (first < 0) throw new Error(label + ': anchor missing');
  if (s.indexOf(oldText, first + oldText.length) >= 0) throw new Error(label + ': anchor not unique');
  s = s.slice(0, first) + newText + s.slice(first + oldText.length);
}
function replaceRange(startNeedle, endNeedle, replacement, label) {
  const start = s.indexOf(startNeedle);
  const end = s.indexOf(endNeedle, start + startNeedle.length);
  if (start < 0 || end < 0 || end <= start) throw new Error(label + ': boundaries missing');
  s = s.slice(0, start) + replacement + s.slice(end);
}

replaceOnce(
  "let hostControlStateLoaded = false;",
  "let hostControlStateLoaded = false;\nlet hostControlStateVersion = '';\nlet authorityLastReloadMs = 0;",
  'authority-version-state'
);

replaceRange('function readHostControlState() {', 'function hostControlStateReady() {', `// ${MARKER}\nfunction readHostControlStateSnapshot() {
  const response = hostBrokerRequest('auth_state_read', {});
  if (!response.ok) throw new Error('host_control_state_read_failed');
  let meta;
  try { meta = JSON.parse(String(response.stdout || '{}')); }
  catch { throw new Error('host_control_state_read_metadata_invalid'); }
  if (!meta.exists) return { exists: false, version: '', plain: '' };
  if (typeof meta.sha256 !== 'string' || !/^[0-9a-f]{64}$/.test(meta.sha256) || typeof meta.content_b64 !== 'string') {
    throw new Error('host_control_state_read_metadata_invalid');
  }
  const encrypted = Buffer.from(meta.content_b64, 'base64').toString('utf8');
  if (sha256(encrypted) !== meta.sha256) throw new Error('host_control_state_hash_mismatch');
  return { exists: true, version: meta.sha256, plain: decryptControlState(encrypted) };
}
function readHostControlState() {
  const snapshot = readHostControlStateSnapshot();
  hostControlStateVersion = snapshot.version;
  return snapshot.plain;
}
function compareAndSwapHostControlState(plain, expectedVersion) {
  const encrypted = encryptControlState(plain);
  const response = hostBrokerRequest('auth_state_compare_and_swap', {
    expected_sha256: String(expectedVersion || ''),
    content_b64: Buffer.from(encrypted, 'utf8').toString('base64'),
  });
  let meta = {};
  try { meta = JSON.parse(String(response.stdout || '{}')); } catch {}
  if (response.ok && meta.status === 'written' && typeof meta.sha256 === 'string') {
    return { ok: true, version: meta.sha256 };
  }
  if (Number(response.exitCode) === 75 || meta.status === 'conflict') {
    return { ok: false, conflict: true, version: typeof meta.current_sha256 === 'string' ? meta.current_sha256 : '' };
  }
  throw new Error('host_control_state_cas_failed');
}
`, 'host-authority-io');

replaceOnce(
`        digestPrefix: typeof session.digestPrefix === 'string' ? session.digestPrefix : '',
        revoked: !!session.revoked,
      });`,
`        digestPrefix: typeof session.digestPrefix === 'string' ? session.digestPrefix : '',
        revoked: !!session.revoked,
        kind: session.kind === 'refresh' ? 'refresh' : (session.kind === 'code' ? 'code' : 'access'),
        family: typeof session.family === 'string' ? session.family : '',
      });`,
  'session-deserialization'
);

replaceRange('function loadSecurityState() {', 'loadSecurityState();', `function securityStateObject() {
  return {
    version: 3,
    founderPasswordScrypt: runtimeFounderPasswordScrypt,
    bootstrapEnabled: runtimeBootstrapEnabled,
    clientRevokedBefore: Object.fromEntries(clientRevokedBefore),
    authSessions: [...authSessions.values()],
    updatedAt: new Date().toISOString(),
  };
}
function writeLocalSecurityMirror(serialized) {
  try {
    const slash = AUTH_SECURITY_STATE_PATH.lastIndexOf('/');
    if (slash > 0) fs.mkdirSync(AUTH_SECURITY_STATE_PATH.slice(0, slash), { recursive: true, mode: 0o700 });
    const temp = AUTH_SECURITY_STATE_PATH + '.tmp';
    fs.writeFileSync(temp, serialized, { mode: 0o600 });
    fs.renameSync(temp, AUTH_SECURITY_STATE_PATH);
    try { fs.chmodSync(AUTH_SECURITY_STATE_PATH, 0o600); } catch {}
  } catch (error) {
    console.warn('AUTH_CONTROL_STATE_LOCAL_MIRROR_FAILED', error.message);
  }
}
function resetSecurityStateMaps() {
  authSessions.clear();
  clientRevokedBefore.clear();
  runtimeFounderPasswordScrypt = ENV_FOUNDER_PASSWORD_SCRYPT;
  runtimeBootstrapEnabled = ENV_ALLOW_BOOTSTRAP_CODE;
}
function loadSecurityState() {
  let loaded = false;
  if (fs.existsSync(HOST_SSH_KEY_PATH)) {
    try {
      const snapshot = readHostControlStateSnapshot();
      if (snapshot.exists && snapshot.plain) {
        resetSecurityStateMaps();
        applySecurityState(JSON.parse(snapshot.plain));
        hostControlStateVersion = snapshot.version;
        authorityLastReloadMs = Date.now();
        loaded = true;
        hostControlStateLoaded = true;
        hostControlStateHealthy = true;
        console.log('AUTH_CONTROL_STATE_HOST_LOAD_PASS');
      }
    } catch (error) {
      hostControlStateHealthy = false;
      hostControlStateLastError = error.message;
      console.error('AUTH_CONTROL_STATE_HOST_LOAD_FAILED', error.message);
    }
  }
  if (loaded) return;
  try {
    if (!fs.existsSync(AUTH_SECURITY_STATE_PATH)) return;
    resetSecurityStateMaps();
    applySecurityState(JSON.parse(fs.readFileSync(AUTH_SECURITY_STATE_PATH, 'utf8')));
    console.warn('AUTH_CONTROL_STATE_LOCAL_FALLBACK_LOAD');
  } catch (error) {
    console.error('AUTH_SECURITY_STATE_LOAD_FAILED', error.message);
  }
}
function reloadSecurityStateFromAuthority(force = false) {
  if (process.env.AUTH_PROXY_SELF_TEST === '1') return true;
  if (!fs.existsSync(HOST_SSH_KEY_PATH)) return false;
  const now = Date.now();
  if (!force && hostControlStateHealthy && now - authorityLastReloadMs < 750) return true;
  try {
    const snapshot = readHostControlStateSnapshot();
    if (!snapshot.exists || !snapshot.plain) throw new Error('host_control_state_missing');
    resetSecurityStateMaps();
    applySecurityState(JSON.parse(snapshot.plain));
    hostControlStateVersion = snapshot.version;
    authorityLastReloadMs = now;
    hostControlStateLoaded = true;
    hostControlStateHealthy = true;
    hostControlStateLastError = '';
    return true;
  } catch (error) {
    hostControlStateHealthy = false;
    hostControlStateLastError = error.message;
    console.error('AUTH_CONTROL_STATE_LIVE_RELOAD_FAILED', error.message);
    return false;
  }
}
function saveSecurityState() {
  const serialized = JSON.stringify(securityStateObject(), null, 2);
  if (fs.existsSync(HOST_SSH_KEY_PATH)) {
    const result = compareAndSwapHostControlState(serialized, hostControlStateVersion);
    if (!result.ok) throw new Error('security_state_conflict');
    hostControlStateVersion = result.version;
    authorityLastReloadMs = Date.now();
    hostControlStateLoaded = true;
    hostControlStateHealthy = true;
    console.log('AUTH_CONTROL_STATE_HOST_SAVE_PASS');
  }
  writeLocalSecurityMirror(serialized);
}
function commitSecurityMutation(mutator) {
  if (process.env.AUTH_PROXY_SELF_TEST === '1') return mutator();
  if (!fs.existsSync(HOST_SSH_KEY_PATH)) {
    const value = mutator();
    saveSecurityState();
    return value;
  }
  for (let attempt = 0; attempt < 6; attempt++) {
    if (!reloadSecurityStateFromAuthority(true)) throw new Error('security_authority_unavailable');
    const value = mutator();
    const serialized = JSON.stringify(securityStateObject(), null, 2);
    const result = compareAndSwapHostControlState(serialized, hostControlStateVersion);
    if (result.ok) {
      hostControlStateVersion = result.version;
      authorityLastReloadMs = Date.now();
      hostControlStateHealthy = true;
      hostControlStateLastError = '';
      writeLocalSecurityMirror(serialized);
      console.log('AUTH_CONTROL_STATE_HOST_CAS_SAVE_PASS');
      return value;
    }
    if (!result.conflict) throw new Error('security_state_persistence_failed');
  }
  throw new Error('security_state_conflict_exhausted');
}
`, 'security-state-runtime');

replaceOnce(
  'const ACCESS_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60;\nconst AUTH_CODE_TTL_SECONDS = 5 * 60;',
  'const ACCESS_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60;\nconst REFRESH_TOKEN_TTL_SECONDS = 90 * 24 * 60 * 60;\nconst AUTH_CODE_TTL_SECONDS = 5 * 60;',
  'token-ttl'
);

replaceRange('function accessTokenAllowed(payload) {', 'function adminCookie(req) {', `function accessTokenAllowed(payload) {
  if (!payload || typeof payload.jti !== 'string') return false;
  const clientKey = String(payload.client_id || payload.sub || '');
  if (!clientKey) return false;
  const revokedBefore = clientRevokedBefore.get(clientKey) || 0;
  if ((Number(payload.iat) || 0) <= revokedBefore) return false;
  const session = authSessions.get(payload.jti);
  if (!session) return process.env.AUTH_PROXY_SELF_TEST === '1' && payload.sub === 'claude';
  return session.kind !== 'refresh' && session.kind !== 'code' && !session.revoked && session.client === clientKey && session.expiresAt > Math.floor(Date.now()/1000);
}
function refreshTokenAllowed(payload) {
  if (!payload || typeof payload.jti !== 'string' || typeof payload.client_id !== 'string') return false;
  const revokedBefore = clientRevokedBefore.get(payload.client_id) || 0;
  if ((Number(payload.iat) || 0) <= revokedBefore) return false;
  const session = authSessions.get(payload.jti);
  return !!session && session.kind === 'refresh' && !session.revoked && session.client === payload.client_id && session.expiresAt > Math.floor(Date.now()/1000);
}
function putAuthSession(payload, token, kind = 'access', family = '') {
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
    kind: kind === 'refresh' ? 'refresh' : (kind === 'code' ? 'code' : 'access'),
    family: typeof family === 'string' ? family : '',
  });
}
function recordAuthSession(payload, token, kind = 'access', family = '') {
  return commitSecurityMutation(() => putAuthSession(payload, token, kind, family));
}
`, 'token-session-policy');

replaceOnce(
`  if (candidate.source === 'authorization') {
    const access = verifySigned(candidate.token, secret, 'access_token');`,
`  if (candidate.source === 'authorization') {
    if (process.env.AUTH_PROXY_SELF_TEST !== '1' && !reloadSecurityStateFromAuthority(false)) {
      return { client: null, principal: null, scopes: [], source: candidate.source, digestPrefix: sha256(candidate.token).slice(0, 12), oauth: false };
    }
    const access = verifySigned(candidate.token, secret, 'access_token');`,
  'oauth-read-through'
);

replaceOnce(
`    grant_types_supported: ['authorization_code'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none'],
    scopes_supported: ['mcp:tools'],`,
`    grant_types_supported: ['authorization_code', 'refresh_token'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none'],
    scopes_supported: ['mcp:tools', 'offline_access'],
    authorization_response_iss_parameter_supported: true,`,
  'oauth-metadata'
);

replaceRange('function handleRegister(req, res, raw, secret) {', 'function handleAuthorizeGet(req, res, secret) {', `function handleRegister(req, res, raw, secret) {
  let body = {};
  try { body = JSON.parse(raw || '{}'); } catch { return json(res, 400, { error: 'invalid_client_metadata' }); }
  const redirects = Array.isArray(body.redirect_uris) ? [...new Set(body.redirect_uris.filter(validRedirect))].slice(0, 10) : [];
  if (!redirects.length) return json(res, 400, { error: 'invalid_redirect_uri' });
  if (body.token_endpoint_auth_method && body.token_endpoint_auth_method !== 'none') return json(res, 400, { error: 'invalid_client_metadata' });
  const requested = Array.isArray(body.grant_types) ? [...new Set(body.grant_types)] : ['authorization_code', 'refresh_token'];
  if (!requested.includes('authorization_code') || requested.some(x => !['authorization_code','refresh_token'].includes(x))) return json(res, 400, { error: 'invalid_client_metadata' });
  const grants = requested.includes('refresh_token') ? ['authorization_code','refresh_token'] : ['authorization_code'];
  const applicationType = body.application_type === 'native' ? 'native' : 'web';
  const clientName = typeof body.client_name === 'string' && body.client_name.trim() ? body.client_name.trim().slice(0, 120) : 'MCP client';
  const now = Math.floor(Date.now()/1000);
  const clientId = mintSigned({kind:'oauth_client',client_name:clientName,redirect_uris:redirects,application_type:applicationType,grant_types:grants,iat:now,exp:now+(365*24*60*60),jti:randomBytes(12).toString('base64url')}, secret);
  json(res, 201, {client_id:clientId,client_id_issued_at:now,redirect_uris:redirects,client_name:clientName,application_type:applicationType,token_endpoint_auth_method:'none',grant_types:grants,response_types:['code']}, {'cache-control':'no-store'});
}
`, 'dcr-refresh');

replaceRange('function handleToken(req, res, raw, secret) {', 'function unauthorizedForOAuth(res) {', `function handleToken(req, res, raw, secret) {
  const p = parseForm(raw);
  const grantType = p.get('grant_type') || '';
  if (grantType === 'refresh_token') {
    const presented = p.get('refresh_token') || '';
    const refreshPayload = verifySigned(presented, secret, 'refresh_token');
    if (!refreshPayload) return json(res, 400, {error:'invalid_grant'});
    const clientId = p.get('client_id') || refreshPayload.client_id || '';
    let responseBody;
    try {
      responseBody = commitSecurityMutation(() => {
        if (!refreshTokenAllowed(refreshPayload)) throw new Error('invalid_grant');
        const registration = registeredClient(clientId, refreshPayload.redirect_uri, secret);
        if (!registration || clientId !== refreshPayload.client_id || !Array.isArray(registration.grant_types) || !registration.grant_types.includes('refresh_token')) throw new Error('invalid_grant');
        const previous = authSessions.get(refreshPayload.jti);
        if (!previous || previous.revoked || previous.kind !== 'refresh') throw new Error('invalid_grant');
        previous.revoked = true;
        authSessions.set(refreshPayload.jti, previous);
        const now = Math.floor(Date.now()/1000);
        const family = refreshPayload.family || refreshPayload.jti;
        const scope = String(refreshPayload.scope || 'mcp:tools');
        const accessPayload = {kind:'access_token',iss:PUBLIC_ORIGIN,aud:RESOURCE_URL,sub:'founder',client_id:refreshPayload.client_id,scope,iat:now,exp:now+ACCESS_TOKEN_TTL_SECONDS,jti:randomBytes(12).toString('base64url')};
        const accessToken = mintSigned(accessPayload, secret);
        const nextRefreshPayload = {kind:'refresh_token',iss:PUBLIC_ORIGIN,aud:RESOURCE_URL,sub:'founder',client_id:refreshPayload.client_id,redirect_uri:refreshPayload.redirect_uri,scope,family,iat:now,exp:now+REFRESH_TOKEN_TTL_SECONDS,jti:randomBytes(12).toString('base64url')};
        const nextRefreshToken = mintSigned(nextRefreshPayload, secret);
        putAuthSession(accessPayload, accessToken, 'access', family);
        putAuthSession(nextRefreshPayload, nextRefreshToken, 'refresh', family);
        return {access_token:accessToken,token_type:'Bearer',expires_in:ACCESS_TOKEN_TTL_SECONDS,refresh_token:nextRefreshToken,scope};
      });
    } catch (error) {
      if (error.message === 'invalid_grant') return json(res, 400, {error:'invalid_grant'});
      return json(res, 503, {error:'temporarily_unavailable'}, {'cache-control':'no-store'});
    }
    return json(res, 200, responseBody, {'cache-control':'no-store'});
  }
  if (grantType !== 'authorization_code') return json(res, 400, {error:'unsupported_grant_type'});
  const payload = verifySigned(p.get('code'), secret, 'auth_code');
  if (!payload) return json(res, 400, {error:'invalid_grant'});
  const registration = registeredClient(p.get('client_id'), p.get('redirect_uri'), secret);
  if (!registration || payload.client_id !== p.get('client_id') || payload.redirect_uri !== p.get('redirect_uri')) return json(res, 400, {error:'invalid_grant'});
  const verifier = p.get('code_verifier') || '';
  const challenge = createHash('sha256').update(verifier, 'utf8').digest('base64url');
  if (!safeStringEqual(challenge, payload.code_challenge)) return json(res, 400, {error:'invalid_grant'});
  let responseBody;
  try {
    responseBody = commitSecurityMutation(() => {
      const codeKey = 'code:' + payload.jti;
      if (authSessions.has(codeKey)) throw new Error('invalid_grant');
      const now = Math.floor(Date.now()/1000);
      authSessions.set(codeKey, {jti:codeKey,client:payload.client_id,scope:'',issuedAt:now,expiresAt:payload.exp,digestPrefix:'',revoked:true,kind:'code',family:''});
      const scopes = [...new Set(String(payload.scope || 'mcp:tools').split(/\\s+/).filter(Boolean))];
      if (!scopes.includes('mcp:tools')) scopes.unshift('mcp:tools');
      const resourceScope = scopes.filter(x => x !== 'offline_access').join(' ');
      const accessPayload = {kind:'access_token',iss:PUBLIC_ORIGIN,aud:RESOURCE_URL,sub:'founder',client_id:payload.client_id,scope:resourceScope,iat:now,exp:now+ACCESS_TOKEN_TTL_SECONDS,jti:randomBytes(12).toString('base64url')};
      const accessToken = mintSigned(accessPayload, secret);
      const body = {access_token:accessToken,token_type:'Bearer',expires_in:ACCESS_TOKEN_TTL_SECONDS,scope:resourceScope};
      const canRefresh = Array.isArray(registration.grant_types) && registration.grant_types.includes('refresh_token');
      if (canRefresh) {
        const family = randomBytes(16).toString('base64url');
        const refreshPayload = {kind:'refresh_token',iss:PUBLIC_ORIGIN,aud:RESOURCE_URL,sub:'founder',client_id:payload.client_id,redirect_uri:payload.redirect_uri,scope:resourceScope,family,iat:now,exp:now+REFRESH_TOKEN_TTL_SECONDS,jti:randomBytes(12).toString('base64url')};
        const refreshToken = mintSigned(refreshPayload, secret);
        putAuthSession(accessPayload, accessToken, 'access', family);
        putAuthSession(refreshPayload, refreshToken, 'refresh', family);
        body.refresh_token = refreshToken;
      } else {
        putAuthSession(accessPayload, accessToken, 'access');
      }
      return body;
    });
  } catch (error) {
    if (error.message === 'invalid_grant') return json(res, 400, {error:'invalid_grant'});
    return json(res, 503, {error:'temporarily_unavailable'}, {'cache-control':'no-store'});
  }
  return json(res, 200, responseBody, {'cache-control':'no-store'});
}
`, 'token-endpoint');

replaceOnce(
  'function founderSecurityRoute(req, res, assertion, raw, url) {',
  `function founderSecurityRoute(req, res, assertion, raw, url) {\n  if (process.env.AUTH_PROXY_SELF_TEST !== '1' && !reloadSecurityStateFromAuthority(false)) return json(res, 503, {error:'temporarily_unavailable'}, {'cache-control':'no-store'});`,
  'founder-route-read-through'
);
replaceOnce(
  `try { runtimeFounderPasswordScrypt = scryptPassword(password); saveSecurityState(); }`,
  `try { commitSecurityMutation(() => { runtimeFounderPasswordScrypt = scryptPassword(password); }); }`,
  'founder-password-cas'
);
replaceOnce(
  `runtimeBootstrapEnabled = next; saveSecurityState();`,
  `try { commitSecurityMutation(() => { runtimeBootstrapEnabled = next; }); } catch { return html(res, 503, securityDashboard(session, 'Security authority is temporarily unavailable.')); }`,
  'founder-bootstrap-cas'
);
replaceOnce(
  `if (s) { s.revoked = true; authSessions.set(jti, s); saveSecurityState(); }`,
  `if (s) { try { commitSecurityMutation(() => { const current = authSessions.get(jti); if (current) { current.revoked = true; authSessions.set(jti, current); } }); } catch { return html(res, 503, securityDashboard(session, 'Security authority is temporarily unavailable.')); } }`,
  'session-revoke-cas'
);
replaceOnce(
  `const now = Math.floor(Date.now()/1000); clientRevokedBefore.set(client, now); saveSecurityState();
    for (const s of authSessions.values()) if (s.client === client && s.issuedAt <= now) s.revoked = true;`,
  `const now = Math.floor(Date.now()/1000);
    try { commitSecurityMutation(() => { clientRevokedBefore.set(client, now); for (const item of authSessions.values()) if (item.client === client && item.issuedAt <= now) item.revoked = true; }); }
    catch { return html(res, 503, securityDashboard(session, 'Security authority is temporarily unavailable.')); }`,
  'client-revoke-cas'
);

replaceOnce(
  `const sessions = [...authSessions.values()].sort((a,b)=>b.issuedAt-a.issuedAt);`,
  `const sessions = [...authSessions.values()].filter(s => s.kind !== 'code').sort((a,b)=>b.issuedAt-a.issuedAt);`,
  'dashboard-code-filter'
);
replaceOnce(
  `const clients = new Set([...CLIENT_TOKEN_HASHES.keys(), ...sessions.map(s=>s.client), 'chatgpt']);`,
  `const clients = new Set([...CLIENT_TOKEN_HASHES.keys(), ...sessions.map(s=>s.client)]);`,
  'dashboard-vendor-label-removal'
);

s = s.replace(
  `  // Claude Connectors must authenticate at the HTTP boundary so the host can discover OAuth.\n  // User-Agent only decides whether to issue the OAuth challenge; it never grants identity.`,
  `  // Every unauthenticated MCP request receives the same standards-based OAuth challenge.\n  // User-Agent is observational only and never participates in authentication.`
);

replaceOnce(
  `setInterval(() => { try { hostControlStateReady(); } catch {} }, 60000).unref();`,
  `setInterval(() => { try { hostControlStateReady(); reloadSecurityStateFromAuthority(true); } catch {} }, 60000).unref();`,
  'background-authority-refresh'
);

const logAnchor = `  console.log('CLIENT_AGNOSTIC_DCR_ACCEPTANCE_PASS');`;
if (!s.includes(logAnchor)) throw new Error('self-test log anchor missing');
s = s.replace(logAnchor, `  const refreshPayload = {kind:'refresh_token',iss:PUBLIC_ORIGIN,aud:RESOURCE_URL,sub:'founder',client_id:clientId,redirect_uri:'https://client.example/callback',scope:'mcp:tools',family:'selftest-family',iat:now,exp:now+60,jti:'refresh-self-test'};
  const refreshToken = mintSigned(refreshPayload, assertion);
  putAuthSession(refreshPayload, refreshToken, 'refresh', 'selftest-family');
  if (!refreshTokenAllowed(refreshPayload)) throw new Error('refresh_session_policy_failed');
  authSessions.get(refreshPayload.jti).revoked = true;
  if (refreshTokenAllowed(refreshPayload)) throw new Error('refresh_replay_policy_failed');
  authSessions.delete(refreshPayload.jti);
  console.log('AUTH_OAUTH_RESILIENCE_G10_ACCEPTANCE_PASS');
${logAnchor}`);

s = `// ${MARKER}\n` + s;
for (const required of [MARKER,'auth_state_read','auth_state_compare_and_swap','AUTH_CONTROL_STATE_HOST_CAS_SAVE_PASS','REFRESH_TOKEN_TTL_SECONDS',"grant_types_supported: ['authorization_code', 'refresh_token']",'refreshTokenAllowed','AUTH_OAUTH_RESILIENCE_G10_ACCEPTANCE_PASS','reloadSecurityStateFromAuthority(true)']) {
  if (!s.includes(required)) throw new Error('G10 invariant missing: ' + required);
}
if (s.includes("new Set([...CLIENT_TOKEN_HASHES.keys(), ...sessions.map(s=>s.client), 'chatgpt'])")) throw new Error('vendor dashboard label remains');

fs.writeFileSync(target, s);
console.log('AUTH_OAUTH_RESILIENCE_G10_PATCH_PASS');
