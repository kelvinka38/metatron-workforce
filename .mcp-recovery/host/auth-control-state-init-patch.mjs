import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-control-state-init-patch.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');
if (source.includes('METATRON_MCP_CONTROL_STATE_INIT_V2') && source.includes('METATRON_MCP_CONTROL_STATE_SINGLE_WRITER_V2')) {
  console.log('AUTH_CONTROL_STATE_INIT_PATCH_ALREADY_APPLIED');
  process.exit(0);
}
if (!source.includes('METATRON_MCP_CONTROL_STATE_V1')) throw new Error('host control-state patch must run first');

const healthAnchor = `let hostControlStateHealthy = false;\nlet hostControlStateLastError = '';`;
if (!source.includes(healthAnchor)) throw new Error('host control-state health anchor missing');
if (!source.includes('let hostControlStateLoaded = false;')) {
  source = source.replace(healthAnchor, `${healthAnchor}\nlet hostControlStateLoaded = false;`);
}

const loadAnchor = `if (raw) { applySecurityState(JSON.parse(raw)); loaded = true; console.log('AUTH_CONTROL_STATE_HOST_LOAD_PASS'); }`;
const loadAnchorV2 = `if (raw) { applySecurityState(JSON.parse(raw)); loaded = true; hostControlStateLoaded = true; console.log('AUTH_CONTROL_STATE_HOST_LOAD_PASS'); }`;
if (source.includes(loadAnchor)) {
  source = source.replace(loadAnchor, loadAnchorV2);
} else if (!source.includes(loadAnchorV2)) {
  throw new Error('host load success anchor missing');
}

const loadCall = 'loadSecurityState();';
const recoveryMarker = '// METATRON_FOUNDER_SECURITY_RECOVERY_V1';
const loadIdx = source.indexOf(loadCall);
if (loadIdx < 0) throw new Error('security state load call missing');
const recoveryIdx = source.indexOf(recoveryMarker, loadIdx + loadCall.length);
if (recoveryIdx < 0) throw new Error('founder recovery marker missing after state load');

const betweenStart = loadIdx + loadCall.length;
const between = source.slice(betweenStart, recoveryIdx);
const hasLegacyInit = between.includes('METATRON_MCP_CONTROL_STATE_INIT_V1');
if (!hasLegacyInit && between.trim()) {
  const executable = between
    .split('\n')
    .map(line => line.trim())
    .filter(Boolean)
    .filter(line => !line.startsWith('//'));
  if (executable.length) throw new Error('unexpected initialization block between load and recovery markers');
}

const initV2 = `\n// METATRON_MCP_CONTROL_STATE_INIT_V2\n// METATRON_MCP_CONTROL_STATE_SINGLE_WRITER_V2\n// A replica that loaded the encrypted host authority is startup-read-only. Only a first-run\n// migration with no host authority may initialize it; runtime mutations persist through the\n// ACTIVE authorization path.\nif (fs.existsSync(HOST_SSH_KEY_PATH) && !hostControlStateLoaded) {\n  try {\n    saveSecurityState();\n    console.log('AUTH_CONTROL_STATE_HOST_INITIALIZED');\n  } catch (error) {\n    hostControlStateHealthy = false;\n    hostControlStateLastError = error.message;\n    console.error('AUTH_CONTROL_STATE_HOST_INITIALIZE_FAILED', error.message);\n  }\n} else if (hostControlStateLoaded) {\n  console.log('AUTH_CONTROL_STATE_HOST_REUSE_NO_WRITE');\n}\n`;
source = source.slice(0, betweenStart) + initV2 + source.slice(recoveryIdx);

for (const marker of [
  'METATRON_MCP_CONTROL_STATE_INIT_V2',
  'METATRON_MCP_CONTROL_STATE_SINGLE_WRITER_V2',
  'hostControlStateLoaded = true',
  'AUTH_CONTROL_STATE_HOST_INITIALIZED',
  'AUTH_CONTROL_STATE_HOST_REUSE_NO_WRITE',
]) {
  if (!source.includes(marker)) throw new Error('control-state initialization invariant missing: ' + marker);
}
if (source.includes('METATRON_MCP_CONTROL_STATE_INIT_V1')) throw new Error('legacy control-state init v1 still present');
fs.writeFileSync(target, source);
console.log('AUTH_CONTROL_STATE_INIT_PATCH_PASS');
