#!/usr/bin/env python3
from pathlib import Path
import subprocess, sys, tempfile

ROOT = Path(__file__).resolve().parent
runtime_patch = ROOT / "patch-release-authority-runtime-g20.mjs"
broker_patch = ROOT / "patch-release-authority-broker-g20.py"
release = ROOT / "release-broker-authority-g20.sh"
for path in (runtime_patch, broker_patch, release):
    assert path.is_file(), path

with tempfile.TemporaryDirectory(prefix="metatron-g20-test-") as raw:
    td = Path(raw)
    runtime = td / "server.mjs"
    runtime.write_text("""// METATRON_HOST_COMMANDER_RUNTIME_G18
const TOOL_REGISTRY=new Map();
function register(name,description,inputSchema,execute){TOOL_REGISTRY.set(name,{public:true,definition:{name},execute});}
function result(x){return x;}
register("production_identity","",{},async()=>{});
register("workforce_deploy_local_sha","",{},async()=>{});
register("workforce_verify_production","",{},async()=>{});
register("workforce_rollback","",{},async()=>{});
register("ssh_mcp_self_upgrade","",{},async()=>{});
server.setRequestHandler(ListToolsRequestSchema,async()=>({tools:[...TOOL_REGISTRY.values()].filter(x=>x.public).map(x=>x.definition)}));
server.setRequestHandler(CallToolRequestSchema,async request=>{if(true){}const name=request.params.name;const a=request.params.arguments??{};try{const entry=TOOL_REGISTRY.get(name);if(entry){return await entry.execute(a,request);}return result({ok:false});}catch(e){return result({ok:false});}});
""")
    subprocess.run(["node", str(runtime_patch), str(runtime)], check=True)
    subprocess.run(["node", "--check", str(runtime)], check=True)
    text = runtime.read_text()
    assert "METATRON_RELEASE_AUTHORITY_BOUNDARY_G20" in text
    for name in ("workforce_deploy_local_sha", "workforce_rollback", "ssh_mcp_self_upgrade"):
        assert name in text
    assert 'entry.public=false' in text
    assert 'release_authority_required' in text
    assert 'management_release_control' in text
    guard = text.index('if(remoteReleaseAuthorityDenied(name))')
    execute = text.index('return await entry.execute(a,request)', guard)
    assert guard < execute
    assert 'register("workforce_verify_production"' in text
    assert 'register("production_identity"' in text

    broker_in = td / "broker.py"
    broker_out = td / "broker-g20.py"
    broker_in.write_text("""# METATRON_HOST_COMMANDER_BROKER_G19
def identity(generation,source_sha,release):
    payload={"verified": bool(generation >= 19 and source_sha and release == "g19-host-commander"), "brokerCapability": "g19-host-commander"}
    return payload
def op_workforce_deploy_local_sha(a): pass
def op_workforce_rollback(a): pass
OPS={"workforce_deploy_local_sha":op_workforce_deploy_local_sha,"workforce_rollback":op_workforce_rollback}
""")
    subprocess.run([sys.executable, str(broker_patch), str(broker_in), str(broker_out)], check=True)
    subprocess.run([sys.executable, "-m", "py_compile", str(broker_out)], check=True)
    broker = broker_out.read_text()
    assert "METATRON_RELEASE_AUTHORITY_BROKER_G20" in broker
    assert 'release == "g20-release-authority-boundary"' in broker
    assert '"brokerCapability": "g20-release-authority-boundary"' in broker
    assert '"workforce_deploy_local_sha":op_workforce_deploy_local_sha' in broker
    assert '"workforce_rollback":op_workforce_rollback' in broker

subprocess.run(["bash", "-n", str(release)], check=True)
release_text = release.read_text()
for required in (
    "BASE_IMAGE=metatron-ssh-mcp-runtime:g18",
    "TARGET_IMAGE=metatron-ssh-mcp-runtime:g20",
    "g20-release-authority-boundary",
    "MCP_G20_REMOTE_RELEASE_LIST_DENY=PASS",
    "MCP_G20_REMOTE_DEPLOY_CALL_DENY=PASS",
    "MCP_G20_READ_ONLY_EVIDENCE_SURFACE=PASS",
    '[ "$NOW" -eq 20 ] && break',
    "MCP_G20_COMPLETE",
):
    assert required in release_text, required

print("MCP_G20_RELEASE_AUTHORITY_SOURCE_ACCEPTANCE_PASS")
