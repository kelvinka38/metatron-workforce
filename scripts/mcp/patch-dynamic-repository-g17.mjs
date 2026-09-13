import fs from 'node:fs';

const path = process.argv[2];
if (!path) throw new Error('server path required');
let source = fs.readFileSync(path, 'utf8');

const marker = 'METATRON_DYNAMIC_REPOSITORY_SCOPE_G17';
if (source.includes(marker)) {
  console.log('MCP_G17_PATCH_ALREADY_PRESENT');
  process.exit(0);
}

const oldScope = 'const CANONICAL_REPOSITORIES=new Set(["kelvinka38/universal","kelvinka38/metatron-institution","kelvinka38/metatron-workforce","kelvinka38/bios"]);';
if (!source.includes(oldScope)) throw new Error('g16 canonical repository scope anchor missing');
source = source.replace(oldScope, `// ${marker}\nconst SAFE_REPOSITORY=/^[a-z0-9][a-z0-9_.-]{0,99}\\/[a-z0-9][a-z0-9_.-]{0,99}$/;`);

const oldArg = 'function repositoryArg(a){ const repository=strArg(a,"repository",{required:true,max:160}).toLowerCase(); if(!CANONICAL_REPOSITORIES.has(repository)) throw new Error("repository_outside_canonical_scope"); return repository; }';
const newArg = 'function repositoryArg(a){ const repository=strArg(a,"repository",{required:true,max:200}).toLowerCase(); if(!SAFE_REPOSITORY.test(repository)||repository.includes("..")||repository.includes("//")) throw new Error("repository_identifier_invalid"); return repository; }';
if (!source.includes(oldArg)) throw new Error('g16 repositoryArg anchor missing');
source = source.replace(oldArg, newArg);

const oldDiscovery = 'canonicalRepositories:[...CANONICAL_REPOSITORIES],next:';
const newDiscovery = 'repositoryScope:"server-credential-authorized",staticAllowlist:false,next:';
if (!source.includes(oldDiscovery)) throw new Error('g16 repository discovery anchor missing');
source = source.replace(oldDiscovery, newDiscovery);

source = source
  .replaceAll('private canonical repository access', 'private repository access')
  .replaceAll('Supports the four canonical private repositories;', 'Supports any GitHub repository authorized to the server-side Repository Control Plane credential;')
  .replaceAll('one canonical repository', 'one repository authorized by the server credential')
  .replaceAll('private canonical repos such as kelvinka38/bios', 'private repositories authorized by the server credential');

for (const forbidden of ['CANONICAL_REPOSITORIES', 'repository_outside_canonical_scope', 'Supports the four canonical private repositories']) {
  if (source.includes(forbidden)) throw new Error(`stale g16 repository scope remains: ${forbidden}`);
}
for (const required of [marker, 'SAFE_REPOSITORY', 'staticAllowlist:false', 'server-credential-authorized', 'register("repository_open"', 'register("repository_read"']) {
  if (!source.includes(required)) throw new Error(`g17 requirement missing: ${required}`);
}

fs.writeFileSync(path, source);
console.log('MCP_G17_DYNAMIC_REPOSITORY_PATCH_PASS');
