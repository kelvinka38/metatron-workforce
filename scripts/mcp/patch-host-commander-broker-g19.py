#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: patch-host-commander-broker-g19.py <input-broker.py> <output-broker.py>")

src = Path(sys.argv[1])
out = Path(sys.argv[2])
source = src.read_text()
marker = "METATRON_HOST_COMMANDER_BROKER_G19"

if marker in source:
    out.write_text(source)
    print("MCP_G19_COMMANDER_BROKER_ALREADY_PATCHED")
    raise SystemExit(0)

for required in [
    "METATRON_HOST_COMMANDER_BROKER_G18",
    "def op_commander_file_patch(a):",
    "def op_commander_exec(a):",
    '"commander_file_patch":op_commander_file_patch',
    'release == "g18-host-commander"',
    '"brokerCapability": "g18-host-commander"',
]:
    if required not in source:
        raise SystemExit("g19 commander broker anchor missing: " + required)

file_remove = r'''
# METATRON_HOST_COMMANDER_BROKER_G19

def op_commander_file_remove(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "path"], ["principal", "client_binding", "session_id", "fencing_token", "path"])
    session = _cm_load_session(a, True)
    path = _cm_mutable_path(a.get("path"))
    lock = _cm_mutation_lock(session)
    try:
        existed = os.path.lexists(path)
        if existed:
            if os.path.isdir(path):
                fail("commander_file_remove_directory_denied")
            if os.path.islink(path):
                fail("commander_symlink_mutation_denied")
            os.unlink(path)
        verified = not os.path.lexists(path)
        if not verified:
            fail("commander_file_remove_verify_failed")
    finally:
        _cm_unlock(lock)
    _cm_audit("file_remove", session, {"path": path, "existed": existed})
    return _cm_response({
        "accepted": True,
        "executed": bool(existed),
        "mutated": bool(existed),
        "verified": True,
        "path": path,
        "existed": bool(existed),
        "removed": bool(existed),
    })

'''
source = source.replace("def op_commander_exec(a):", file_remove + "def op_commander_exec(a):", 1)
source = source.replace(
    '"commander_file_patch":op_commander_file_patch',
    '"commander_file_patch":op_commander_file_patch,\n    "commander_file_remove":op_commander_file_remove',
    1,
)
source = source.replace(
    'bool(generation >= 18 and source_sha and release == "g18-host-commander")',
    'bool(generation >= 19 and source_sha and release == "g19-host-commander")',
    1,
)
source = source.replace(
    '"brokerCapability": "g18-host-commander"',
    '"brokerCapability": "g19-host-commander"',
    1,
)

for required in [
    marker,
    "def op_commander_file_remove(a):",
    '"commander_file_remove":op_commander_file_remove',
    "commander_file_remove_directory_denied",
    "commander_file_remove_verify_failed",
    'release == "g19-host-commander"',
    '"brokerCapability": "g19-host-commander"',
    "_cm_mutable_path(a.get(\"path\"))",
    "_cm_mutation_lock(session)",
]:
    if required not in source:
        raise SystemExit("g19 commander broker invariant missing: " + required)

out.write_text(source)
print("MCP_G19_COMMANDER_BROKER_PATCH_PASS")
