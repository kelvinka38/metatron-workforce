import fs from 'node:fs';

const path = process.argv[2];
if (!path) throw new Error('server path required');
let s = fs.readFileSync(path, 'utf8');
const marker = 'METATRON_HOST_COMMANDER_RUNTIME_G18';
if (s.includes(marker)) {
  console.log('MCP_G18_COMMANDER_RUNTIME_ALREADY_PATCHED');
  process.exit(0);
}

for (const required of [
  'function directClient(request)',
  'function clientBinding(client)',
  'async function brokerResult(op,args={},timeout=30000)',
  'const READ_ONLY_TOOLS=new Set(',
  'const LOCAL_WORLD_TOOLS=new Set(',
  'const NON_DESTRUCTIVE_WRITE_TOOLS=new Set(',
  'const IDEMPOTENT_WRITE_TOOLS=new Set(',
  'async function legacyWorkspaceGitCommitPush(a){',
]) {
  if (!s.includes(required)) throw new Error('g18 runtime anchor missing: ' + required);
}

const helperAnchor = 'async function brokerResult(op,args={},timeout=30000){\n  const r=await broker(op,args,timeout);\n  return result(r,r.ok===false);\n}\n';
if (!s.includes(helperAnchor)) throw new Error('g18 brokerResult exact anchor missing');
const helpers = `${helperAnchor}
// ${marker}
function commanderIdentity(request){
  const client=directClient(request);
  const principal=request?.params?._meta?.["metatron/authenticatedPrincipal"];
  if(principal!=="founder") throw new Error("commander_founder_required");
  return {principal,client_binding:clientBinding(client)};
}
function commanderSessionArgs(a,request){
  return {...commanderIdentity(request),session_id:strArg(a,"session_id",{required:true,max:80}),fencing_token:strArg(a,"fencing_token",{required:true,max:80})};
}
function commanderStringArray(a,key,maxItems=16){
  if(!(key in a)) return [];
  if(!Array.isArray(a[key])||a[key].length>maxItems||a[key].some(v=>typeof v!=="string"||v.length>2048||/[\\0\\r\\n]/.test(v))) throw new Error("invalid_string_array:"+key);
  return a[key];
}
async function commanderBrokerResult(request,op,args={},timeout=300000){
  const r=await broker(op,{...commanderIdentity(request),...args},timeout);
  return result(r,r.ok===false);
}
`;
s = s.replace(helperAnchor, helpers);

const registrationAnchor = 'async function legacyWorkspaceGitCommitPush(a){';
const registrations = `const COMMANDER_READ_ONLY_TOOLS=["commander_status","commander_file_list","commander_file_stat","commander_file_read","commander_file_search","commander_exec","commander_process_output","commander_process_list","commander_docker_inspect","commander_docker_logs","commander_storage_inspect","commander_network_inspect"];
const COMMANDER_LOCAL_TOOLS=["commander_open","commander_close",...COMMANDER_READ_ONLY_TOOLS,"commander_file_write","commander_file_patch","commander_process_start","commander_process_input","commander_process_terminate","commander_docker_action","commander_storage_cleanup"];
for(const n of COMMANDER_READ_ONLY_TOOLS)READ_ONLY_TOOLS.add(n);
for(const n of COMMANDER_LOCAL_TOOLS)LOCAL_WORLD_TOOLS.add(n);
for(const n of ["commander_open","commander_close","commander_file_write","commander_file_patch","commander_process_start","commander_process_input","commander_process_terminate","commander_docker_action","commander_storage_cleanup"])NON_DESTRUCTIVE_WRITE_TOOLS.add(n);
for(const n of ["commander_close","commander_file_write","commander_file_patch","commander_process_input","commander_process_terminate","commander_storage_cleanup"])IDEMPOTENT_WRITE_TOOLS.add(n);

register("commander_open","Open a bounded founder-only Host Commander session on the actual Metatron host. This is governed privileged operations, not raw root shell access.",objectSchema({purpose:{type:"string"},scope:{type:"string"},ttl_seconds:{type:"integer",minimum:60,maximum:1800}},["purpose"]),async(a,request)=>{assertKeys(a,["purpose","scope","ttl_seconds"],["purpose"]);const args={purpose:strArg(a,"purpose",{required:true,max:500})};if("scope" in a)args.scope=strArg(a,"scope",{required:true,max:32});if("ttl_seconds" in a)args.ttl_seconds=intArg(a,"ttl_seconds",900,60,1800);return commanderBrokerResult(request,"commander_open",args,30000);});
register("commander_status","Read the state of one Host Commander session.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"}},["session_id","fencing_token"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token"],["session_id","fencing_token"]);return commanderBrokerResult(request,"commander_status",commanderSessionArgs(a,request),30000);});
register("commander_close","Close one Host Commander session.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"}},["session_id","fencing_token"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token"],["session_id","fencing_token"]);return commanderBrokerResult(request,"commander_close",commanderSessionArgs(a,request),30000);});
register("commander_file_list","List a bounded non-secret host directory through Commander.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},path:{type:"string"},max_entries:{type:"integer",minimum:1,maximum:500}},["session_id","fencing_token","path"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","path","max_entries"],["session_id","fencing_token","path"]);const args={...commanderSessionArgs(a,request),path:strArg(a,"path",{required:true,max:4096})};if("max_entries" in a)args.max_entries=intArg(a,"max_entries",200,1,500);return commanderBrokerResult(request,"commander_file_list",args,30000);});
register("commander_file_stat","Read bounded host file metadata through Commander.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},path:{type:"string"}},["session_id","fencing_token","path"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","path"],["session_id","fencing_token","path"]);return commanderBrokerResult(request,"commander_file_stat",{...commanderSessionArgs(a,request),path:strArg(a,"path",{required:true,max:4096})},30000);});
register("commander_file_read","Read bounded non-secret host text through Commander.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},path:{type:"string"},max_bytes:{type:"integer",minimum:1,maximum:131072}},["session_id","fencing_token","path"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","path","max_bytes"],["session_id","fencing_token","path"]);const args={...commanderSessionArgs(a,request),path:strArg(a,"path",{required:true,max:4096})};if("max_bytes" in a)args.max_bytes=intArg(a,"max_bytes",65536,1,131072);return commanderBrokerResult(request,"commander_file_read",args,30000);});
register("commander_file_search","Search bounded non-secret host text through Commander.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},path:{type:"string"},query:{type:"string"},max_matches:{type:"integer",minimum:1,maximum:200}},["session_id","fencing_token","path","query"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","path","query","max_matches"],["session_id","fencing_token","path","query"]);const args={...commanderSessionArgs(a,request),path:strArg(a,"path",{required:true,max:4096}),query:strArg(a,"query",{required:true,max:500})};if("max_matches" in a)args.max_matches=intArg(a,"max_matches",100,1,200);return commanderBrokerResult(request,"commander_file_search",args,120000);});
register("commander_file_write","Write one bounded file only inside Commander-owned disposable host roots.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},path:{type:"string"},content:{type:"string"}},["session_id","fencing_token","path","content"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","path","content"],["session_id","fencing_token","path","content"]);if(typeof a.content!=="string")throw new Error("invalid_content");return commanderBrokerResult(request,"commander_file_write",{...commanderSessionArgs(a,request),path:strArg(a,"path",{required:true,max:4096}),content:a.content},30000);});
register("commander_file_patch","Apply an occurrence-checked patch only inside Commander-owned disposable host roots.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},path:{type:"string"},old_text:{type:"string"},new_text:{type:"string"},expected_occurrences:{type:"integer",minimum:1,maximum:100}},["session_id","fencing_token","path","old_text"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","path","old_text","new_text","expected_occurrences"],["session_id","fencing_token","path","old_text"]);if(typeof a.old_text!=="string"||!a.old_text.length||("new_text" in a&&typeof a.new_text!=="string"))throw new Error("invalid_patch_text");const args={...commanderSessionArgs(a,request),path:strArg(a,"path",{required:true,max:4096}),old_text:a.old_text,new_text:a.new_text??""};if("expected_occurrences" in a)args.expected_occurrences=intArg(a,"expected_occurrences",1,1,100);return commanderBrokerResult(request,"commander_file_patch",args,30000);});
register("commander_exec","Run one allowlisted, bounded diagnostic executable on the actual host. Shell text is not accepted.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},executable:{type:"string"},args:{type:"array",items:{type:"string"},maxItems:16},working_directory:{type:"string"},timeout_seconds:{type:"integer",minimum:1,maximum:120},max_output_bytes:{type:"integer",minimum:1024,maximum:131072}},["session_id","fencing_token","executable"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","executable","args","working_directory","timeout_seconds","max_output_bytes"],["session_id","fencing_token","executable"]);const args={...commanderSessionArgs(a,request),executable:strArg(a,"executable",{required:true,max:64}),args:commanderStringArray(a,"args",16)};if("working_directory" in a)args.working_directory=strArg(a,"working_directory",{required:true,max:4096});if("timeout_seconds" in a)args.timeout_seconds=intArg(a,"timeout_seconds",30,1,120);if("max_output_bytes" in a)args.max_output_bytes=intArg(a,"max_output_bytes",65536,1024,131072);return commanderBrokerResult(request,"commander_exec",args,150000);});
register("commander_process_start","Start a bounded interactive Host Commander process under session ownership.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},executable:{type:"string"},args:{type:"array",items:{type:"string"},maxItems:16},working_directory:{type:"string"},timeout_seconds:{type:"integer",minimum:5,maximum:1800},max_output_bytes:{type:"integer",minimum:1024,maximum:1048576}},["session_id","fencing_token","executable"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","executable","args","working_directory","timeout_seconds","max_output_bytes"],["session_id","fencing_token","executable"]);const args={...commanderSessionArgs(a,request),executable:strArg(a,"executable",{required:true,max:64}),args:commanderStringArray(a,"args",16)};if("working_directory" in a)args.working_directory=strArg(a,"working_directory",{required:true,max:4096});if("timeout_seconds" in a)args.timeout_seconds=intArg(a,"timeout_seconds",300,5,1800);if("max_output_bytes" in a)args.max_output_bytes=intArg(a,"max_output_bytes",262144,1024,1048576);return commanderBrokerResult(request,"commander_process_start",args,30000);});
register("commander_process_input","Send bounded UTF-8 input to a Commander-owned interactive process.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},process_id:{type:"string"},data:{type:"string"}},["session_id","fencing_token","process_id","data"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","process_id","data"],["session_id","fencing_token","process_id","data"]);if(typeof a.data!=="string")throw new Error("invalid_process_input");return commanderBrokerResult(request,"commander_process_input",{...commanderSessionArgs(a,request),process_id:strArg(a,"process_id",{required:true,max:80}),data:a.data},30000);});
register("commander_process_output","Read bounded output and state from a Commander-owned interactive process.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},process_id:{type:"string"},offset:{type:"integer",minimum:0},max_bytes:{type:"integer",minimum:1,maximum:131072}},["session_id","fencing_token","process_id"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","process_id","offset","max_bytes"],["session_id","fencing_token","process_id"]);const args={...commanderSessionArgs(a,request),process_id:strArg(a,"process_id",{required:true,max:80})};if("offset" in a)args.offset=intArg(a,"offset",0,0,2147483647);if("max_bytes" in a)args.max_bytes=intArg(a,"max_bytes",65536,1,131072);return commanderBrokerResult(request,"commander_process_output",args,30000);});
register("commander_process_list","List interactive processes owned by one Commander session.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"}},["session_id","fencing_token"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token"],["session_id","fencing_token"]);return commanderBrokerResult(request,"commander_process_list",commanderSessionArgs(a,request),30000);});
register("commander_process_terminate","Request termination of a Commander-owned interactive process.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},process_id:{type:"string"}},["session_id","fencing_token","process_id"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","process_id"],["session_id","fencing_token","process_id"]);return commanderBrokerResult(request,"commander_process_terminate",{...commanderSessionArgs(a,request),process_id:strArg(a,"process_id",{required:true,max:80})},30000);});
register("commander_docker_inspect","Inspect a production host container without returning its environment secrets.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},container:{type:"string"}},["session_id","fencing_token","container"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","container"],["session_id","fencing_token","container"]);return commanderBrokerResult(request,"commander_docker_inspect",{...commanderSessionArgs(a,request),container:strArg(a,"container",{required:true,max:128})},45000);});
register("commander_docker_logs","Read bounded redacted logs from one host container.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},container:{type:"string"},lines:{type:"integer",minimum:1,maximum:1000}},["session_id","fencing_token","container"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","container","lines"],["session_id","fencing_token","container"]);const args={...commanderSessionArgs(a,request),container:strArg(a,"container",{required:true,max:128})};if("lines" in a)args.lines=intArg(a,"lines",200,1,1000);return commanderBrokerResult(request,"commander_docker_logs",args,60000);});
register("commander_docker_action","Run a narrowly approved Docker action: restart approved production containers or control Commander acceptance fixtures.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},container:{type:"string"},action:{type:"string"}},["session_id","fencing_token","container","action"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","container","action"],["session_id","fencing_token","container","action"]);return commanderBrokerResult(request,"commander_docker_action",{...commanderSessionArgs(a,request),container:strArg(a,"container",{required:true,max:128}),action:strArg(a,"action",{required:true,max:16})},120000);});
register("commander_storage_inspect","Run the bounded production host storage audit inside one Commander session.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"}},["session_id","fencing_token"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token"],["session_id","fencing_token"]);return commanderBrokerResult(request,"commander_storage_inspect",commanderSessionArgs(a,request),210000);});
register("commander_storage_cleanup","Run only the existing bounded safe host cleanup inside a maintenance/recovery Commander session.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"},journal_max_mb:{type:"integer",minimum:200,maximum:2048},builder_until_hours:{type:"integer",minimum:24,maximum:720}},["session_id","fencing_token"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token","journal_max_mb","builder_until_hours"],["session_id","fencing_token"]);const args={...commanderSessionArgs(a,request)};if("journal_max_mb" in a)args.journal_max_mb=intArg(a,"journal_max_mb",500,200,2048);if("builder_until_hours" in a)args.builder_until_hours=intArg(a,"builder_until_hours",168,24,720);return commanderBrokerResult(request,"commander_storage_cleanup",args,330000);});
register("commander_network_inspect","Inspect bounded listening host sockets inside one Commander session.",objectSchema({session_id:{type:"string"},fencing_token:{type:"string"}},["session_id","fencing_token"]),async(a,request)=>{assertKeys(a,["session_id","fencing_token"],["session_id","fencing_token"]);return commanderBrokerResult(request,"commander_network_inspect",commanderSessionArgs(a,request),45000);});

${registrationAnchor}`;
if (!s.includes(registrationAnchor)) throw new Error('g18 registration anchor missing');
s = s.replace(registrationAnchor, registrations);

for (const required of [
  marker,
  'register("commander_open"',
  'register("commander_exec"',
  'register("commander_process_start"',
  'register("commander_docker_action"',
  'register("commander_storage_cleanup"',
  'commander_founder_required',
  'clientBinding(client)',
  'COMMANDER_READ_ONLY_TOOLS',
]) {
  if (!s.includes(required)) throw new Error('g18 runtime invariant missing: ' + required);
}

fs.writeFileSync(path, s);
console.log('MCP_G18_COMMANDER_RUNTIME_PATCH_PASS');
