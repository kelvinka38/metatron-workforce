import fs from 'node:fs';

const path = process.argv[2];
if (!path) throw new Error('server path required');
let s = fs.readFileSync(path, 'utf8');
const marker = 'METATRON_RELEASE_AUTHORITY_BOUNDARY_G20';
if (s.includes(marker)) {
  console.log('MCP_G20_RELEASE_AUTHORITY_RUNTIME_ALREADY_PATCHED');
  process.exit(0);
}

for (const required of [
  'METATRON_HOST_COMMANDER_RUNTIME_G18',
  'register("workforce_deploy_local_sha"',
  'register("workforce_verify_production"',
  'register("workforce_rollback"',
  'register("ssh_mcp_self_upgrade"',
  'const TOOL_REGISTRY=new Map();',
  'server.setRequestHandler(ListToolsRequestSchema',
  'server.setRequestHandler(CallToolRequestSchema',
]) {
  if (!s.includes(required)) throw new Error('g20 runtime anchor missing: ' + required);
}

const handlerAnchor = 'server.setRequestHandler(ListToolsRequestSchema,async()=>({tools:[...TOOL_REGISTRY.values()].filter(x=>x.public).map(x=>x.definition)}));';
if (!s.includes(handlerAnchor)) throw new Error('g20 list handler anchor missing');
const boundary = `// ${marker}\nconst RELEASE_AUTHORITY_TOOLS=new Set(["workforce_deploy_local_sha","workforce_rollback","ssh_mcp_self_upgrade"]);\nfor(const name of RELEASE_AUTHORITY_TOOLS){\n  const entry=TOOL_REGISTRY.get(name);\n  if(!entry) throw new Error(\`release_authority_tool_missing:\${name}\`);\n  entry.public=false;\n}\nfunction remoteReleaseAuthorityDenied(name){return RELEASE_AUTHORITY_TOOLS.has(name);}\nfunction remoteReleaseAuthorityDenial(name){return result({ok:false,error:"release_authority_required",tool:name,required_plane:"management_release_control"},true);}\n\n${handlerAnchor}`;
s = s.replace(handlerAnchor, boundary, 1);

const callAnchor = 'const name=request.params.name;const a=request.params.arguments??{};try{const entry=TOOL_REGISTRY.get(name);';
if (!s.includes(callAnchor)) throw new Error('g20 call handler anchor missing');
const callGuard = 'const name=request.params.name;const a=request.params.arguments??{};try{if(remoteReleaseAuthorityDenied(name))return remoteReleaseAuthorityDenial(name);const entry=TOOL_REGISTRY.get(name);';
s = s.replace(callAnchor, callGuard, 1);

for (const required of [
  marker,
  'RELEASE_AUTHORITY_TOOLS=new Set(["workforce_deploy_local_sha","workforce_rollback","ssh_mcp_self_upgrade"])',
  'entry.public=false',
  'release_authority_required',
  'required_plane:"management_release_control"',
  'if(remoteReleaseAuthorityDenied(name))return remoteReleaseAuthorityDenial(name);',
  'register("workforce_verify_production"',
  'register("production_identity"',
]) {
  if (!s.includes(required)) throw new Error('g20 runtime invariant missing: ' + required);
}

const guardPos=s.indexOf('if(remoteReleaseAuthorityDenied(name))');
const executePos=s.indexOf('return await entry.execute(a,request)',guardPos);
if(guardPos<0||executePos<0||guardPos>executePos) throw new Error('g20 handler guard ordering invalid');

fs.writeFileSync(path, s);
console.log('MCP_G20_RELEASE_AUTHORITY_RUNTIME_PATCH_PASS');
