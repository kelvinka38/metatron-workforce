import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-security-recovery-patch.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');
if (source.includes('METATRON_FOUNDER_SECURITY_RECOVERY_V1')) {
  console.log('AUTH_SECURITY_RECOVERY_PATCH_ALREADY_APPLIED');
  process.exit(0);
}
if (!source.includes('METATRON_FOUNDER_SECURITY_ADMIN_V1')) throw new Error('founder security admin patch must run first');

source = source.replace(
  "const AUTH_SECURITY_STATE_PATH = String(process.env.METATRON_AUTH_SECURITY_STATE_PATH || '/app/metatron-auth-security.json').trim();",
  "const AUTH_SECURITY_STATE_PATH = String(process.env.METATRON_AUTH_SECURITY_STATE_PATH || '/var/lib/metatron-auth/security.json').trim();"
);
if (!source.includes("'/var/lib/metatron-auth/security.json'")) throw new Error('durable auth state path patch failed');

const saveAnchor = "    const temp = AUTH_SECURITY_STATE_PATH + '.tmp';";
if (!source.includes(saveAnchor)) throw new Error('security state save anchor missing');
source = source.replace(saveAnchor, `    const slash = AUTH_SECURITY_STATE_PATH.lastIndexOf('/');
    if (slash > 0) fs.mkdirSync(AUTH_SECURITY_STATE_PATH.slice(0, slash), { recursive: true, mode: 0o700 });
    const temp = AUTH_SECURITY_STATE_PATH + '.tmp';`);

const loadAnchor = 'loadSecurityState();';
if (!source.includes(loadAnchor)) throw new Error('security state load anchor missing');
source = source.replace(loadAnchor, `${loadAnchor}
// METATRON_FOUNDER_SECURITY_RECOVERY_V1
// Never allow a container replacement or missing state file to strand the Founder with no
// usable verifier. Environment policy can still explicitly disable bootstrap recovery.
if (!runtimeFounderPasswordScrypt && !runtimeBootstrapEnabled && ENV_ALLOW_BOOTSTRAP_CODE) {
  runtimeBootstrapEnabled = true;
  console.warn('AUTH_SECURITY_RECOVERY_BOOTSTRAP_ENABLED reason=no_founder_password');
}`);

const bootstrapVerify = "  return runtimeBootstrapEnabled && safeHexEqual(sha256(candidate), ACCESS_CODE_HASH);";
if (!source.includes(bootstrapVerify)) throw new Error('bootstrap verifier anchor missing');
source = source.replace(bootstrapVerify,
  "  return runtimeBootstrapEnabled && safeHexEqual(sha256(candidate.trim()), ACCESS_CODE_HASH);");

const statusLine = "const bootstrap = runtimeBootstrapEnabled ? 'Enabled' : 'Disabled';";
if (!source.includes(statusLine)) throw new Error('security dashboard status anchor missing');
source = source.replace(statusLine,
  "const bootstrap = runtimeBootstrapEnabled ? 'Enabled' : 'Disabled';\n  const stateStore = AUTH_SECURITY_STATE_PATH.startsWith('/var/lib/metatron-auth/') ? 'Durable auth store' : 'Runtime-local auth store';");
const statNeedle = "<div class=\"stat\"><span>Admin session</span><strong>15 minute protected session</strong></div>";
if (!source.includes(statNeedle)) throw new Error('dashboard stat anchor missing');
source = source.replace(statNeedle, statNeedle + "<div class=\"stat\"><span>Security state</span><strong>'+esc(stateStore)+'</strong></div>");

if (!source.includes('METATRON_FOUNDER_SECURITY_RECOVERY_V1')) throw new Error('recovery marker missing');
if (!source.includes("sha256(candidate.trim())")) throw new Error('bootstrap paste normalization missing');
fs.writeFileSync(target, source);
console.log('AUTH_SECURITY_RECOVERY_PATCH_PASS');
