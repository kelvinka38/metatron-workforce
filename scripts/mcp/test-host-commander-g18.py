#!/usr/bin/env python3
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parent
BROKER_PATCH = ROOT / "patch-host-commander-broker-g18.py"
RUNTIME_PATCH = ROOT / "patch-host-commander-runtime-g18.mjs"
SUPERVISOR = ROOT / "commander-supervisor-g18.py"

for path in (BROKER_PATCH, RUNTIME_PATCH, SUPERVISOR):
    if not path.is_file():
        raise SystemExit(f"missing {path.name}")

subprocess.run([sys.executable, "-m", "py_compile", str(BROKER_PATCH), str(SUPERVISOR)], check=True)
subprocess.run(["node", "--check", str(RUNTIME_PATCH)], check=True)

with tempfile.TemporaryDirectory(prefix="metatron-g18-test-") as tmp:
    td = Path(tmp)

    broker_in = td / "broker.py"
    broker_out = td / "broker.out.py"
    broker_fixture = (
        "#!/usr/bin/env python3\n"
        "import os,json,re,subprocess,time,fcntl\n"
        "def fail(x): raise RuntimeError(x)\n"
        "def check_keys(a,allowed,required): return None\n"
        "def _hm_run(argv, timeout=120): return (0,'','')\n"
        "def op_host_disk_audit(a): return {'ok':True,'exitCode':0,'stdout':'audit','stderr':''}\n"
        "def op_host_safe_cleanup(a): return {'ok':True,'exitCode':0,'stdout':'cleanup','stderr':''}\n"
        "def op_host_unused_volume_remove(a): return {'ok':True,'exitCode':0,'stdout':'vol','stderr':''}\n"
        "def start_self_upgrade(): return {}\n"
        "OPS={\"host_unused_volume_remove\":op_host_unused_volume_remove}\n"
    )
    broker_in.write_text(broker_fixture, encoding="utf-8")
    subprocess.run([sys.executable, str(BROKER_PATCH), str(broker_in), str(broker_out)], check=True)
    subprocess.run([sys.executable, "-m", "py_compile", str(broker_out)], check=True)
    broker_text = broker_out.read_text(encoding="utf-8")
    for required in (
        "METATRON_HOST_COMMANDER_BROKER_G18",
        "def op_commander_open(a):",
        "def op_commander_runtime_identity(a):",
        "def op_commander_exec(a):",
        "def op_commander_process_start(a):",
        "commander_canonical_source_mutation_denied",
        "commander_secret_path_denied",
        "host-mutation.lock",
        "_cm_terminate_session_processes",
        '"commander_runtime_identity":op_commander_runtime_identity',
        '"commander_storage_cleanup":op_commander_storage_cleanup',
    ):
        assert required in broker_text, required
    assert "op_commander_shell" not in broker_text

    runtime = td / "server.mjs"
    runtime_fixture = (
        "function result(v,e){return v;}\n"
        "async function broker(op,args={},timeout=30000){return {ok:true};}\n"
        "async function brokerResult(op,args={},timeout=30000){\n"
        "  const r=await broker(op,args,timeout);\n"
        "  return result(r,r.ok===false);\n"
        "}\n"
        "function directClient(request){return 'x';}\n"
        "function clientBinding(client){return '0'.repeat(32);}\n"
        "function strArg(a,k,o){return a[k]||'';}\n"
        "function intArg(a,k,d){return (k in a)?a[k]:d;}\n"
        "function assertKeys(){}\n"
        "function objectSchema(x,y){return x;}\n"
        "const READ_ONLY_TOOLS=new Set([]);\n"
        "const LOCAL_WORLD_TOOLS=new Set([]);\n"
        "const NON_DESTRUCTIVE_WRITE_TOOLS=new Set([]);\n"
        "const IDEMPOTENT_WRITE_TOOLS=new Set([]);\n"
        "function register(){}\n"
        "async function legacyWorkspaceGitCommitPush(a){return a;}\n"
    )
    runtime.write_text(runtime_fixture, encoding="utf-8")
    subprocess.run(["node", str(RUNTIME_PATCH), str(runtime)], check=True)
    subprocess.run(["node", "--check", str(runtime)], check=True)
    runtime_text = runtime.read_text(encoding="utf-8")
    for required in (
        "METATRON_HOST_COMMANDER_RUNTIME_G18",
        'register("commander_open"',
        'register("commander_runtime_identity"',
        'register("commander_exec"',
        'register("commander_process_start"',
        'register("commander_storage_cleanup"',
        "commander_founder_required",
    ):
        assert required in runtime_text, required
    assert 'register("commander_shell"' not in runtime_text

    state = td / "proc"
    supervisor = subprocess.Popen(
        [sys.executable, str(SUPERVISOR), str(state), "cat", "[]", str(td), "65536", "10"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True,
    )
    for _ in range(100):
        if (state / "meta.json").exists():
            break
        time.sleep(0.02)
    assert (state / "meta.json").exists(), "supervisor meta missing"
    with open(state / "input.jsonl", "a", encoding="utf-8") as fh:
        fh.write(json.dumps({"dataHex": "g18-commander\n".encode().hex()}) + "\n")
    for _ in range(100):
        if (state / "output.log").exists() and b"g18-commander" in (state / "output.log").read_bytes():
            break
        time.sleep(0.03)
    assert b"g18-commander" in (state / "output.log").read_bytes(), "interactive echo failed"
    (state / "control").write_text("TERMINATE\n", encoding="utf-8")
    supervisor.wait(timeout=5)
    meta = json.loads((state / "meta.json").read_text(encoding="utf-8"))
    assert meta["status"] in {"TERMINATED", "EXITED"}, meta

print("MCP_G18_COMMANDER_SOURCE_ACCEPTANCE_PASS")
