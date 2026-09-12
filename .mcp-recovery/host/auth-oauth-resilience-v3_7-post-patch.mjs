import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('target_required');

let s = fs.readFileSync(target, 'utf8');

function occurrences(haystack, needle) {
  let n = 0, p = 0;
  while ((p = haystack.indexOf(needle, p)) !== -1) {
    n++;
    p += needle.length;
  }
  return n;
}

function replaceOne(oldText, newText, label) {
  const n = occurrences(s, oldText);
  if (n !== 1) {
    throw new Error(`${label}: expected exactly 1 occurrence, got ${n}`);
  }
  s = s.replace(oldText, newText);
}

function requireMarker(marker) {
  if (!s.includes(marker)) throw new Error(`missing prerequisite marker: ${marker}`);
}

requireMarker('METATRON_CLIENT_AGNOSTIC_OAUTH_V1');
requireMarker('METATRON_CLIENT_AGNOSTIC_POST_PATCH_V2');
requireMarker('METATRON_MCP_CONTROL_STATE_V1');
requireMarker('METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1');

if (s.includes('METATRON_MCP_OAUTH_RESILIENCE_V3_7')) {
  throw new Error('v3.7 already applied');
}

/* ---------------------------------------------------------------
 * 1. Token lifetime
 * ------------------------------------------------------------- */
replaceOne(
`const ACCESS_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60;
const AUTH_CODE_TTL_SECONDS = 5 * 60;`,
`const ACCESS_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60;
const REFRESH_TOKEN_TTL_SECONDS = 90 * 24 * 60 * 60;
const AUTH_CODE_TTL_SECONDS = 5 * 60;`,
'token-ttl'
);

/* ---------------------------------------------------------------
 * 2. Persist access vs refresh session type/family.
 * Existing v2 sessions deserialize as access sessions.
 * ------------------------------------------------------------- */
replaceOne(
`          digestPrefix: typeof session.digestPrefix === 'string' ? session.digestPrefix : '',
          revoked: !!session.revoked,
        });`,
`          digestPrefix: typeof session.digestPrefix === 'string' ? session.digestPrefix : '',
          revoked: !!session.revoked,
          kind: session.kind === 'refresh' ? 'refresh' : 'access',
          family: typeof session.family === 'string' ? session.family : '',
        });`,
'session-deserialization'
);

/* ---------------------------------------------------------------
 * 3. Live read-through of encrypted host authority.
 * Local security.json remains mirror/fallback, not HA authority.
 * ------------------------------------------------------------- */
replaceOne(
`function saveSecurityState() {`,
`// METATRON_MCP_CONTROL_STATE_LIVE_RELOAD_V1
let authorityLastReloadMs = 0;

function reloadSecurityStateFromAuthority(force = false) {
  if (!fs.existsSync(HOST_SSH_KEY_PATH)) return true;

  const now = Date.now();
  if (!force && now - authorityLastReloadMs < 1000) return true;

  try {
    const raw = readHostControlState();
    if (!raw) throw new Error('host_control_state_empty');

    const parsed = JSON.parse(raw);

    authSessions.clear();
    clientRevokedBefore.clear();

    runtimeFounderPasswordScrypt = ENV_FOUNDER_PASSWORD_SCRYPT;
    runtimeBootstrapEnabled = ENV_ALLOW_BOOTSTRAP_CODE;

    applySecurityState(parsed);

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

function saveSecurityState() {`,
'authority-live-reload'
);

/* ---------------------------------------------------------------
 * 4. Access + refresh session policy.
 * ------------------------------------------------------------- */
replaceOne(
`function accessTokenAllowed(payload) {
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
}`,
`function accessTokenAllowed(payload) {
  if (!payload || typeof payload.jti !== 'string') return false;
  const clientKey = String(payload.client_id || payload.sub || '');
  if (!clientKey) return false;

  const revokedBefore = clientRevokedBefore.get(clientKey) || 0;
  if ((Number(payload.iat) || 0) <= revokedBefore) return false;

  const session = authSessions.get(payload.jti);
  if (!session) return process.env.AUTH_PROXY_SELF_TEST === '1' && payload.sub === 'claude';

  return session.kind !== 'refresh'
    && !session.revoked
    && session.client === clientKey
    && session.expiresAt > Math.floor(Date.now()/1000);
}

function refreshTokenAllowed(payload) {
  if (!payload || typeof payload.jti !== 'string') return false;

  const clientKey = String(payload.client_id || '');
  if (!clientKey) return false;

  const revokedBefore = clientRevokedBefore.get(clientKey) || 0;
  if ((Number(payload.iat) || 0) <= revokedBefore) return false;

  const session = authSessions.get(payload.jti);

  return !!session
    && session.kind === 'refresh'
    && !session.revoked
    && session.client === clientKey
    && session.expiresAt > Math.floor(Date.now()/1000);
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
    kind: kind === 'refresh' ? 'refresh' : 'access',
    family: typeof family === 'string' ? family : '',
  });
}

function recordAuthSession(payload, token, kind = 'access', family = '') {
  putAuthSession(payload, token, kind, family);
  saveSecurityState();
}`,
'session-policy'
);

/* ---------------------------------------------------------------
 * 5. Every OAuth bearer gets bounded authority read-through.
 * This closes active -> standby/replacement state visibility.
 * ------------------------------------------------------------- */
replaceOne(
`  if (candidate.source === 'authorization') {
    const access = verifySigned(candidate.token, secret, 'access_token');`,
`  if (candidate.source === 'authorization') {
    // METATRON_MCP_OAUTH_AUTHORITY_READ_THROUGH_V1
    if (process.env.AUTH_PROXY_SELF_TEST !== '1'
        && !reloadSecurityStateFromAuthority(false)) {
      return {
        client: null,
        principal: null,
        scopes: [],
        source: candidate.source,
        digestPrefix: sha256(candidate.token).slice(0, 12),
        oauth: false
      };
    }

    const access = verifySigned(candidate.token, secret, 'access_token');`,
'oauth-read-through'
);

/* Founder security mutations also begin from current authority. */
replaceOne(
`function founderSecurityRoute(req, res, assertion, raw, url) {`,
`function founderSecurityRoute(req, res, assertion, raw, url) {
  if (process.env.AUTH_PROXY_SELF_TEST !== '1'
      && !reloadSecurityStateFromAuthority(false)) {
    return json(res, 503, { error: 'temporarily_unavailable' }, {
      'cache-control': 'no-store'
    });
  }`,
'founder-security-read-through'
);

/* ---------------------------------------------------------------
 * 6. Discovery metadata.
 * Resource metadata remains mcp:tools only.
 * ------------------------------------------------------------- */
replaceOne(
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

/* ---------------------------------------------------------------
 * 7. DCR. Interactive public clients default to auth-code + refresh.
 * Existing old signed client IDs remain valid but do not gain refresh
 * magically; they must re-register/recreate.
 * ------------------------------------------------------------- */
replaceOne(
`  if (body.token_endpoint_auth_method && body.token_endpoint_auth_method !== 'none') return json(res, 400, { error: 'invalid_client_metadata' });
  const applicationType = body.application_type === 'native' ? 'native' : 'web';
  const clientName = typeof body.client_name === 'string' && body.client_name.trim() ? body.client_name.trim().slice(0, 120) : 'MCP client';
  const now = Math.floor(Date.now()/1000);
  const clientId = mintSigned({kind:'oauth_client',client_name:clientName,redirect_uris:redirects,application_type:applicationType,iat:now,exp:now+(365*24*60*60),jti:randomBytes(12).toString('base64url')}, secret);
  json(res, 201, {client_id:clientId,client_id_issued_at:now,redirect_uris:redirects,client_name:clientName,application_type:applicationType,token_endpoint_auth_method:'none',grant_types:['authorization_code'],response_types:['code']}, {'cache-control':'no-store'});`,
`  if (body.token_endpoint_auth_method && body.token_endpoint_auth_method !== 'none') return json(res, 400, { error: 'invalid_client_metadata' });

  const requestedGrants = Array.isArray(body.grant_types)
    ? [...new Set(body.grant_types)]
    : ['authorization_code', 'refresh_token'];

  if (requestedGrants.some(g => !['authorization_code', 'refresh_token'].includes(g))
      || !requestedGrants.includes('authorization_code')) {
    return json(res, 400, { error: 'invalid_client_metadata' });
  }

  const grants = requestedGrants.includes('refresh_token')
    ? ['authorization_code', 'refresh_token']
    : ['authorization_code'];

  const applicationType = body.application_type === 'native' ? 'native' : 'web';
  const clientName = typeof body.client_name === 'string' && body.client_name.trim() ? body.client_name.trim().slice(0, 120) : 'MCP client';
  const now = Math.floor(Date.now()/1000);

  const clientId = mintSigned({
    kind:'oauth_client',
    client_name:clientName,
    redirect_uris:redirects,
    application_type:applicationType,
    grant_types:grants,
    iat:now,
    exp:now+(365*24*60*60),
    jti:randomBytes(12).toString('base64url')
  }, secret);

  json(res, 201, {
    client_id:clientId,
    client_id_issued_at:now,
    redirect_uris:redirects,
    client_name:clientName,
    application_type:applicationType,
    token_endpoint_auth_method:'none',
    grant_types:grants,
    response_types:['code']
  }, {'cache-control':'no-store'});`,
'dcr-refresh'
);

/* ---------------------------------------------------------------
 * 8. Authorization-code + rotating refresh-token endpoint.
 * ------------------------------------------------------------- */
const tokenStart = s.indexOf('function handleToken(req, res, raw, secret) {');
const tokenEnd = s.indexOf('function unauthorizedForOAuth(res) {', tokenStart);

if (tokenStart < 0 || tokenEnd < 0 || tokenEnd <= tokenStart) {
  throw new Error('handleToken boundaries not found');
}

const newTokenHandler = `function handleToken(req, res, raw, secret) {
  const p = parseForm(raw);
  const grantType = p.get('grant_type') || '';

  // METATRON_MCP_OAUTH_REFRESH_ROTATION_V1
  if (grantType === 'refresh_token') {
    if (!reloadSecurityStateFromAuthority(true)) {
      return json(res, 503, {error:'temporarily_unavailable'}, {'cache-control':'no-store'});
    }

    const presented = p.get('refresh_token') || '';
    const refreshPayload = verifySigned(presented, secret, 'refresh_token');

    if (!refreshPayload || !refreshTokenAllowed(refreshPayload)) {
      return json(res, 400, {error:'invalid_grant'});
    }

    const clientId = p.get('client_id') || refreshPayload.client_id || '';
    const registration = registeredClient(clientId, refreshPayload.redirect_uri, secret);

    if (!registration
        || clientId !== refreshPayload.client_id
        || !Array.isArray(registration.grant_types)
        || !registration.grant_types.includes('refresh_token')) {
      return json(res, 400, {error:'invalid_grant'});
    }

    const previous = authSessions.get(refreshPayload.jti);
    if (!previous || previous.revoked || previous.kind !== 'refresh') {
      return json(res, 400, {error:'invalid_grant'});
    }

    previous.revoked = true;
    authSessions.set(refreshPayload.jti, previous);

    const now = Math.floor(Date.now()/1000);
    const family = refreshPayload.family || refreshPayload.jti;
    const scope = String(refreshPayload.scope || 'mcp:tools');

    const accessPayload = {
      kind:'access_token',
      iss:PUBLIC_ORIGIN,
      aud:RESOURCE_URL,
      sub:'founder',
      client_id:refreshPayload.client_id,
      scope,
      iat:now,
      exp:now+ACCESS_TOKEN_TTL_SECONDS,
      jti:randomBytes(12).toString('base64url')
    };
    const accessToken = mintSigned(accessPayload, secret);

    const nextRefreshPayload = {
      kind:'refresh_token',
      iss:PUBLIC_ORIGIN,
      aud:RESOURCE_URL,
      sub:'founder',
      client_id:refreshPayload.client_id,
      redirect_uri:refreshPayload.redirect_uri,
      scope,
      family,
      iat:now,
      exp:now+REFRESH_TOKEN_TTL_SECONDS,
      jti:randomBytes(12).toString('base64url')
    };
    const nextRefreshToken = mintSigned(nextRefreshPayload, secret);

    putAuthSession(accessPayload, accessToken, 'access', family);
    putAuthSession(nextRefreshPayload, nextRefreshToken, 'refresh', family);

    // One authority write contains old-token revocation + both replacements.
    saveSecurityState();

    return json(res, 200, {
      access_token:accessToken,
      token_type:'Bearer',
      expires_in:ACCESS_TOKEN_TTL_SECONDS,
      refresh_token:nextRefreshToken,
      scope
    }, {'cache-control':'no-store'});
  }

  if (grantType !== 'authorization_code') {
    return json(res, 400, {error:'unsupported_grant_type'});
  }

  const payload = verifySigned(p.get('code'), secret, 'auth_code');
  if (!payload) return json(res, 400, {error:'invalid_grant'});

  const registration = registeredClient(
    p.get('client_id'),
    p.get('redirect_uri'),
    secret
  );

  if (!registration
      || payload.client_id !== p.get('client_id')
      || payload.redirect_uri !== p.get('redirect_uri')) {
    return json(res, 400, {error:'invalid_grant'});
  }

  const verifier = p.get('code_verifier') || '';
  const challenge = createHash('sha256')
    .update(verifier, 'utf8')
    .digest('base64url');

  if (!safeStringEqual(challenge, payload.code_challenge)) {
    return json(res, 400, {error:'invalid_grant'});
  }

  if (!reloadSecurityStateFromAuthority(true)) {
    return json(res, 503, {error:'temporarily_unavailable'}, {'cache-control':'no-store'});
  }

  const now = Math.floor(Date.now()/1000);

  const requestedScopes = [...new Set(
    String(payload.scope || 'mcp:tools')
      .split(/\\\\s+/)
      .filter(Boolean)
  )];

  if (!requestedScopes.includes('mcp:tools')) {
    requestedScopes.unshift('mcp:tools');
  }

  const wantsOffline = requestedScopes.includes('offline_access');

  // offline_access authorizes renewal; it is not forwarded as a resource scope.
  const resourceScope = requestedScopes
    .filter(x => x !== 'offline_access')
    .join(' ');

  const accessPayload = {
    kind:'access_token',
    iss:PUBLIC_ORIGIN,
    aud:RESOURCE_URL,
    sub:'founder',
    client_id:payload.client_id,
    scope:resourceScope,
    iat:now,
    exp:now+ACCESS_TOKEN_TTL_SECONDS,
    jti:randomBytes(12).toString('base64url')
  };
  const accessToken = mintSigned(accessPayload, secret);

  const response = {
    access_token:accessToken,
    token_type:'Bearer',
    expires_in:ACCESS_TOKEN_TTL_SECONDS,
    scope:resourceScope
  };

  const canRefresh = Array.isArray(registration.grant_types)
    && registration.grant_types.includes('refresh_token');

  if (canRefresh && wantsOffline) {
    const family = randomBytes(16).toString('base64url');

    const refreshPayload = {
      kind:'refresh_token',
      iss:PUBLIC_ORIGIN,
      aud:RESOURCE_URL,
      sub:'founder',
      client_id:payload.client_id,
      redirect_uri:payload.redirect_uri,
      scope:resourceScope,
      family,
      iat:now,
      exp:now+REFRESH_TOKEN_TTL_SECONDS,
      jti:randomBytes(12).toString('base64url')
    };

    const refreshToken = mintSigned(refreshPayload, secret);

    putAuthSession(accessPayload, accessToken, 'access', family);
    putAuthSession(refreshPayload, refreshToken, 'refresh', family);
    saveSecurityState();

    response.refresh_token = refreshToken;
  } else {
    recordAuthSession(accessPayload, accessToken, 'access');
  }

  return json(res, 200, response, {'cache-control':'no-store'});
}

`;

s = s.slice(0, tokenStart) + newTokenHandler + s.slice(tokenEnd);

/* ---------------------------------------------------------------
 * 9. Idle replicas periodically converge too.
 * Request path still provides <=1s read-through under traffic.
 * ------------------------------------------------------------- */
replaceOne(
`setInterval(() => { try { hostControlStateReady(); } catch {} }, 60000).unref();`,
`setInterval(() => {
  try {
    hostControlStateReady();
    reloadSecurityStateFromAuthority(true);
  } catch {}
}, 60000).unref();`,
'background-authority-refresh'
);

/* ---------------------------------------------------------------
 * 10. Canonical marker.
 * ------------------------------------------------------------- */
s = `// METATRON_MCP_OAUTH_RESILIENCE_V3_7
` + s;

fs.writeFileSync(target, s);
console.log('AUTH_OAUTH_RESILIENCE_V3_7_PATCH_PASS');
