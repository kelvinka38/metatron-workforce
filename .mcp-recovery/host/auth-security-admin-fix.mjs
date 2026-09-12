import fs from 'node:fs';
const target = process.argv[2];
if (!target) throw new Error('usage: node auth-security-admin-fix.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');

source = source.replaceAll('METATRON_runtimeFounderPasswordScrypt', 'METATRON_FOUNDER_PASSWORD_SCRYPT');

// The professional auth patch replaces authorizePage through the parseForm boundary. In the
// baseline auth-proxy, validRedirect historically lived inside that slice, so the patch could
// accidentally remove the DCR/OAuth redirect validator while leaving handleRegister references
// intact. That is a runtime-only ReferenceError which kills port 3002 on POST /oauth/register.
// Repair the helper after all auth UI/security patches and keep a self-test invariant so this
// cannot regress silently again.
if (!source.includes('function validRedirect(uri) {')) {
  const anchor = 'function parseForm(raw) {';
  if (!source.includes(anchor)) throw new Error('parseForm anchor missing for validRedirect repair');
  const helper = `function validRedirect(uri) {\n  if (typeof uri !== 'string' || uri.length > 2048) return false;\n  try {\n    const u = new URL(uri);\n    return u.protocol === 'https:' || u.hostname === 'localhost' || u.hostname === '127.0.0.1';\n  } catch { return false; }\n}\n`;
  source = source.replace(anchor, helper + anchor);
}

const oldFixture = "const oauth = mintSigned({ kind: 'access_token', sub: 'claude', iat: now, exp: now + 60 }, assertion);";
const newFixture = "const oauthPayload = { kind: 'access_token', sub: 'claude', scope: 'mcp:tools', iat: now, exp: now + 60, jti: 'unit-oauth-session' };\n  const oauth = mintSigned(oauthPayload, assertion);\n  recordAuthSession(oauthPayload, oauth);";
if (source.includes(oldFixture)) source = source.replace(oldFixture, newFixture);

const selfTestLog = "console.log('AUTH_PROXY_ACCEPTANCE_PASS credential_forms=4 oauth=enabled founder_admin=enabled revocation=enabled');";
if (source.includes(selfTestLog) && !source.includes('dcr_redirect_validation_failed')) {
  source = source.replace(selfTestLog,
    "if (!validRedirect('https://claude.ai/oauth/callback') || !validRedirect('http://localhost/callback') || validRedirect('javascript:alert(1)')) throw new Error('dcr_redirect_validation_failed');\n  " + selfTestLog);
}

if (!source.includes('METATRON_FOUNDER_PASSWORD_SCRYPT')) throw new Error('founder password env binding missing');
if (source.includes('METATRON_runtimeFounderPasswordScrypt')) throw new Error('founder password env binding not repaired');
if (!source.includes("jti: 'unit-oauth-session'")) throw new Error('oauth self-test session identity missing');
if (!source.includes('function validRedirect(uri) {')) throw new Error('validRedirect helper missing after repair');
if (!source.includes('dcr_redirect_validation_failed')) throw new Error('DCR redirect self-test missing');

fs.writeFileSync(target, source);
console.log('AUTH_SECURITY_ADMIN_ENV_DCR_FIX_PASS');
