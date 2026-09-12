import fs from 'node:fs';

const target = process.argv[2];
if (!target) throw new Error('usage: node auth-readiness-cheap-hotfix.mjs <auth-proxy.mjs>');
let source = fs.readFileSync(target, 'utf8');
if (source.includes('METATRON_MCP_CHEAP_READINESS_V2')) {
  console.log('AUTH_CHEAP_READINESS_ALREADY_APPLIED');
  process.exit(0);
}
if (!source.includes("import http from 'node:http';")) throw new Error('http import anchor missing');
source = source.replace("import http from 'node:http';", "import http from 'node:http';\nimport net from 'node:net';", 1);
const start = source.indexOf('function handleReadiness(res, assertion) {');
const end = source.indexOf('\nfunction route(req, res, assertion, raw) {', start);
if (start < 0 || end < 0) throw new Error('readiness function boundary missing');
const replacement = `// METATRON_MCP_CHEAP_READINESS_V2
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
`;
source = source.slice(0, start) + replacement + source.slice(end);

const listenNeedle = "http.createServer((req, res) => handler(req, res, assertion)).listen(LISTEN_PORT, '0.0.0.0', () =>";
const refresh = `// METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1
// Refresh authority health at low frequency instead of on every readiness request.
if (process.env.AUTH_PROXY_SELF_TEST !== '1') {
  setInterval(() => { try { hostControlStateReady(); } catch {} }, 60000).unref();
}

`;
if (!source.includes(listenNeedle)) throw new Error('server listen anchor missing');
source = source.replace(listenNeedle, refresh + listenNeedle, 1);
for (const marker of ['METATRON_MCP_CHEAP_READINESS_V2','host-broker-cached','supergateway-tcp','METATRON_MCP_CONTROL_STATE_BACKGROUND_REFRESH_V1']) {
  if (!source.includes(marker)) throw new Error('cheap readiness invariant missing: ' + marker);
}
if (source.includes("body.includes('server_status') && body.includes('repository_open')")) throw new Error('legacy deep readiness still present');
fs.writeFileSync(target, source);
console.log('AUTH_CHEAP_READINESS_PATCH_PASS');
