#!/usr/bin/env python3
from pathlib import Path
import subprocess, sys, tempfile

ROOT = Path(__file__).resolve().parent
subprocess.run([sys.executable, str(ROOT / "test-host-commander-g18.py")], check=True)

broker_patch = ROOT / "patch-host-commander-broker-g19.py"
runtime_patch = ROOT / "patch-host-commander-runtime-g19.mjs"
release = ROOT / "release-host-commander-g19.sh"
for p in (broker_patch, runtime_patch, release):
    assert p.is_file(), p

with tempfile.TemporaryDirectory(prefix="metatron-g19-test-") as td_raw:
    td = Path(td_raw)
    broker_in = td / "broker-g18.py"
    broker_out = td / "broker-g19.py"
    broker_in.write_text('''# METATRON_HOST_COMMANDER_BROKER_G18\nimport os\ndef op_commander_file_patch(a): pass\ndef identity(generation,source_sha,release):\n    payload={"verified": bool(generation >= 18 and source_sha and release == "g18-host-commander"), "brokerCapability": "g18-host-commander"}\n    return payload\ndef op_commander_exec(a): pass\nOPS={"commander_file_patch":op_commander_file_patch}\n''')
    subprocess.run([sys.executable, str(broker_patch), str(broker_in), str(broker_out)], check=True)
    subprocess.run([sys.executable, "-m", "py_compile", str(broker_out)], check=True)
    b = broker_out.read_text()
    for required in (
        "METATRON_HOST_COMMANDER_BROKER_G19",
        "def op_commander_file_remove(a):",
        '"commander_file_remove":op_commander_file_remove',
        'release == "g19-host-commander"',
        '"brokerCapability": "g19-host-commander"',
        "_cm_mutable_path",
        "_cm_mutation_lock",
    ):
        assert required in b, required

    runtime_in = td / "server-g18.mjs"
    runtime_in.write_text('''// METATRON_HOST_COMMANDER_RUNTIME_G18\nconst COMMANDER_READ_ONLY_TOOLS=[];\nconst COMMANDER_LOCAL_TOOLS=["commander_open","commander_close",...COMMANDER_READ_ONLY_TOOLS,"commander_file_write","commander_file_patch","commander_process_start"];\nfunction register(){} function objectSchema(){} function assertKeys(){} function commanderBrokerResult(){} function commanderSessionArgs(){} function strArg(){}\nregister("commander_runtime_identity",{},{});\nregister("commander_file_patch",{},{});\nregister("commander_exec",{},{});\n''')
    subprocess.run(["node", str(runtime_patch), str(runtime_in)], check=True)
    subprocess.run(["node", "--check", str(runtime_in)], check=True)
    r = runtime_in.read_text()
    assert "METATRON_HOST_COMMANDER_RUNTIME_G19" in r
    assert '"commander_file_remove"' in r
    assert 'register("commander_file_remove"' in r
    assert 'register("commander_shell"' not in r

subprocess.run(["bash", "-n", str(release)], check=True)
release_text = release.read_text()
for required in (
    "BASE_IMAGE=metatron-ssh-mcp-runtime:g18",
    "TARGET_IMAGE=metatron-ssh-mcp-runtime:g19",
    "metatron.mcp.release=g19-host-commander",
    "MCP_G19_FILE_REMOVE_ACCEPTANCE=PASS",
    "commander_file_remove",
    "PY_REMOVE",
    "inner.get('verified') is True and inner.get('removed') is True",
    '[ "$NOW" -eq 19 ] && break',
    "MCP_G19_RUNTIME_IDENTITY=PASS",
    "MCP_G19_COMPLETE",
):
    assert required in release_text, required

print("MCP_G19_COMMANDER_SOURCE_ACCEPTANCE_PASS")
