import fs from 'node:fs';

const path = process.argv[2];
if (!path) throw new Error('server path required');
let s = fs.readFileSync(path, 'utf8');
const marker = 'METATRON_HOST_COMMANDER_RUNTIME_G19';
if (s.includes(marker)) {
  console.log('MCP_G19_COMMANDER_RUNTIME_ALREADY_PATCHED');
  process.exit(0);
}

for (const required of [
  'METATRON_HOST_COMMANDER_RUNTIME_G18',
  'const COMMANDER_LOCAL_TOOLS=[',
  'register("commander_file_patch"',
  'register("commander_exec"',
  'register("commander_runtime_identity"',
]) {
  if (!s.includes(required)) throw new Error('g19 runtime anchor missing: ' + required);
}

s = s.replace(
  '// METATRON_HOST_COMMANDER_RUNTIME_G18\n',
  '// METATRON_HOST_COMMANDER_RUNTIME_G18\n// METATRON_HOST_COMMANDER_RUNTIME_G19\n',
);

const toolsOld = '"commander_file_write","commander_file_patch","commander_process_start"';
const toolsNew = '"commander_file_write","commander_file_patch","commander_file_remove","commander_process_start"';
if (!s.includes(toolsOld)) throw new Error('g19 commander local tools anchor missing');
s = s.replace(toolsOld, toolsNew, 1);

const execAnchor = 'register("commander_exec",';
const removeRegistration = `register("commander_file_remove","Remove one regular file only inside Commander-owned disposable host roots; missing files are treated as an idempotent verified no-op.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},path:{type:"string"}},["session_id","fencing_token","path"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","path"],["session_id","fencing_token","path"]);return commanderBrokerResult(request,"commander_file_remove",{...commanderSessionArgs(a,request),path:strArg(a,"path",{required:true,max:4096})},30000);});\n`;
if (!s.includes(execAnchor)) throw new Error('g19 exec registration anchor missing');
s = s.replace(execAnchor, removeRegistration + execAnchor, 1);

for (const required of [
  marker,
  '"commander_file_remove"',
  'register("commander_file_remove"',
  'commanderBrokerResult(request,"commander_file_remove"',
]) {
  if (!s.includes(required)) throw new Error('g19 runtime invariant missing: ' + required);
}
if (s.includes('register("commander_shell"')) throw new Error('raw commander shell must remain absent');

fs.writeFileSync(path, s);
console.log('MCP_G19_COMMANDER_RUNTIME_PATCH_PASS');
