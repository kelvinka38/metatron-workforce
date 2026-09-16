#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: patch-release-authority-broker-g20.py <input-broker.py> <output-broker.py>")

src = Path(sys.argv[1])
out = Path(sys.argv[2])
source = src.read_text()
marker = "METATRON_RELEASE_AUTHORITY_BROKER_G20"

if marker in source:
    out.write_text(source)
    print("MCP_G20_RELEASE_AUTHORITY_BROKER_ALREADY_PATCHED")
    raise SystemExit(0)

for required in [
    "METATRON_HOST_COMMANDER_BROKER_G19",
    'bool(generation >= 19 and source_sha and release == "g19-host-commander")',
    '"brokerCapability": "g19-host-commander"',
    '"workforce_deploy_local_sha":op_workforce_deploy_local_sha',
    '"workforce_rollback":op_workforce_rollback',
]:
    if required not in source:
        raise SystemExit("g20 broker anchor missing: " + required)

source = source.replace(
    "# METATRON_HOST_COMMANDER_BROKER_G19",
    "# METATRON_HOST_COMMANDER_BROKER_G19\n# " + marker,
    1,
)
source = source.replace(
    'bool(generation >= 19 and source_sha and release == "g19-host-commander")',
    'bool(generation >= 20 and source_sha and release == "g20-release-authority-boundary")',
    1,
)
source = source.replace(
    '"brokerCapability": "g19-host-commander"',
    '"brokerCapability": "g20-release-authority-boundary"',
    1,
)

for required in [
    marker,
    'release == "g20-release-authority-boundary"',
    '"brokerCapability": "g20-release-authority-boundary"',
    '"workforce_deploy_local_sha":op_workforce_deploy_local_sha',
    '"workforce_rollback":op_workforce_rollback',
]:
    if required not in source:
        raise SystemExit("g20 broker invariant missing: " + required)

out.write_text(source)
print("MCP_G20_RELEASE_AUTHORITY_BROKER_PATCH_PASS")
