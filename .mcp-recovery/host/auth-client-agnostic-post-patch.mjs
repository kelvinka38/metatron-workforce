import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-client-agnostic-post-patch.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');
if (source.includes('METATRON_CLIENT_AGNOSTIC_POST_PATCH_V2')) {
  console.log('CLIENT_AGNOSTIC_POST_PATCH_ALREADY_APPLIED');
  process.exit(0);
}
if (!source.includes('METATRON_CLIENT_AGNOSTIC_OAUTH_V1')) throw new Error('client-agnostic OAuth patch must run first');

// The auth boundary must not rewrite the client-negotiated MCP protocol version.
const forcedProtocol = "  if (verifiedClient) out['mcp-protocol-version'] = BOUNDED_CLIENT_PROTOCOL;\n";
if (source.includes(forcedProtocol)) {
  source = source.replace(forcedProtocol, "  // METATRON_MCP_PROTOCOL_TRANSPARENT_V1\n  // Preserve the client-negotiated MCP-Protocol-Version header unchanged.\n");
} else if (!source.includes('METATRON_MCP_PROTOCOL_TRANSPARENT_V1')) {
  throw new Error('protocol rewrite anchor missing');
}

// Never let one malformed OAuth/MCP request crash the auth-proxy process and tear down a keep-alive socket.
const oldEndHandler = `  req.on('end', () => {\n    if (!res.writableEnded) route(req, res, assertion, Buffer.concat(chunks).toString('utf8'));\n  });`;
const guardedEndHandler = `  req.on('end', () => {\n    if (res.writableEnded) return;\n    try {\n      route(req, res, assertion, Buffer.concat(chunks).toString('utf8'));\n    } catch (error) {\n      console.error('AUTH_PROXY_ROUTE_EXCEPTION', error?.stack || error?.message || String(error));\n      if (!res.headersSent) json(res, 500, { error: 'auth_proxy_request_failed' }, { 'cache-control': 'no-store' });\n      else if (!res.writableEnded) res.end();\n    }\n  });\n  // METATRON_AUTH_REQUEST_EXCEPTION_GUARD_V1`;
if (source.includes(oldEndHandler)) source = source.replace(oldEndHandler, guardedEndHandler);
else if (!source.includes('METATRON_AUTH_REQUEST_EXCEPTION_GUARD_V1')) throw new Error('request exception guard anchor missing');

// Replace the legacy vendor-shaped self test with canonical client-agnostic acceptance.
const selfStart = source.indexOf('function selfTest() {');
const selfEnd = source.indexOf('\n\nif (process.env.AUTH_PROXY_SELF_TEST', selfStart);
if (selfStart < 0 || selfEnd < 0) throw new Error('selfTest boundary missing');
const genericSelfTest = `function selfTest() {
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
}`;
source = source.slice(0, selfStart) + genericSelfTest + source.slice(selfEnd);

for (const marker of [
  'METATRON_CLIENT_AGNOSTIC_POST_PATCH_V2',
  'METATRON_MCP_PROTOCOL_TRANSPARENT_V1',
  'METATRON_AUTH_REQUEST_EXCEPTION_GUARD_V1',
  'CLIENT_AGNOSTIC_DCR_ACCEPTANCE_PASS',
  'CLIENT_AGNOSTIC_OAUTH_ACCEPTANCE_PASS',
  'protocol_transparency_failed',
]) {
  if (!source.includes(marker)) throw new Error('post-patch invariant missing: ' + marker);
}

fs.writeFileSync(target, source);
console.log('CLIENT_AGNOSTIC_POST_PATCH_PASS');
