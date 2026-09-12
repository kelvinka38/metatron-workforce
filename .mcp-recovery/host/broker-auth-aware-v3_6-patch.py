#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 4:
    raise SystemExit('usage: broker-auth-aware-v3_6-patch.py <input-broker.py> <release.sh> <output-broker.py>')

src = Path(sys.argv[1])
release_path = Path(sys.argv[2])
out = Path(sys.argv[3])
source = src.read_text()
release = release_path.read_text()

if 'VERSION = "3.6.0"' in source and 'METATRON_MCP_AUTH_AWARE_EMBEDDED_RELEASE_V1' in source:
    out.write_text(source)
    print('BROKER_AUTH_AWARE_V3_6_ALREADY_APPLIED')
    raise SystemExit(0)

if 'VERSION = "3.5.0"' not in source:
    raise SystemExit('unsupported broker version; expected 3.5.0')
if 'METATRON_HOST_MAINTENANCE_V1' not in source:
    raise SystemExit('broker 3.5 maintenance substrate missing')
if 'def start_self_upgrade():' not in source or 'def op_ssh_mcp_self_upgrade(a):' not in source:
    raise SystemExit('self-upgrade function boundaries missing')

required_release_markers = [
    'METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1',
    'METATRON_MCP_DOCKER_EXEC_STDIN_V1',
    'public_oauth_boundary_once()',
    'router_transport_ok()',
    'METATRON_MCP_SIMPLE_RELEASE_V1',
    'DONE_RESILIENT_V3_4',
]
for marker in required_release_markers:
    if marker not in release:
        raise SystemExit('release invariant missing: ' + marker)

# Every heredoc Node probe inside docker exec must attach stdin.
for lineno, line in enumerate(release.splitlines(), 1):
    if 'docker exec ' in line and "<<'NODE'" in line and 'docker exec -i ' not in line:
        raise SystemExit(f'unsafe docker exec heredoc without -i at release line {lineno}')

source = source.replace('VERSION = "3.5.0"', 'VERSION = "3.6.0"', 1)
source = source.replace('BROKER_VERSION=3.5.0', 'BROKER_VERSION=3.6.0')

start = source.index('def start_self_upgrade():')
end = source.index('def op_ssh_mcp_self_upgrade(a):', start)
new = '''# METATRON_MCP_AUTH_AWARE_EMBEDDED_RELEASE_V1\ndef start_self_upgrade():\n    os.makedirs(os.path.dirname(MCP_RELEASE_LOCK), exist_ok=True)\n    os.makedirs(MCP_RELEASE_STATE, exist_ok=True)\n    lock_fd = os.open(MCP_RELEASE_LOCK, os.O_RDWR | os.O_CREAT, 0o600)\n    try:\n        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)\n    except BlockingIOError:\n        os.close(lock_fd)\n        return {"ok":False,"exitCode":75,"stdout":"","stderr":"mcp_release_in_progress"}\n    lease = f"release-{time.time_ns()}-{os.urandom(12).hex()}"\n    lease_path = os.path.join(MCP_RELEASE_STATE, "release-owner")\n    tmp = lease_path + f".tmp.{os.getpid()}"\n    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)\n    with os.fdopen(fd, "w") as f:\n        f.write(lease + "\\n")\n        f.flush()\n        os.fsync(f.fileno())\n    os.replace(tmp, lease_path)\n    os.chmod(lease_path, 0o600)\n    script = __RELEASE__\n    log = open("/var/log/metatron-ssh-mcp-upgrade.log", "ab", buffering=0)\n    try:\n        subprocess.Popen(\n            ["/bin/sh", "-lc", script],\n            stdin=subprocess.DEVNULL,\n            stdout=log,\n            stderr=log,\n            start_new_session=True,\n            close_fds=True,\n            pass_fds=(lock_fd,),\n        )\n    except Exception:\n        try:\n            fcntl.flock(lock_fd, fcntl.LOCK_UN)\n        finally:\n            os.close(lock_fd)\n        raise\n    os.close(lock_fd)\n    return {"ok":True,"exitCode":0,"stdout":"RESILIENT_RELEASE_V3_6_AUTH_AWARE_SCHEDULED\\n","stderr":""}\n\n'''.replace('__RELEASE__', repr(release))
source = source[:start] + new + source[end:]

for marker in [
    'VERSION = "3.6.0"',
    'METATRON_HOST_MAINTENANCE_V1',
    'METATRON_MCP_AUTH_AWARE_EMBEDDED_RELEASE_V1',
    'METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1',
    'METATRON_MCP_DOCKER_EXEC_STDIN_V1',
    'public_oauth_boundary_once()',
    'router_transport_ok()',
    'DONE_RESILIENT_V3_4',
    'def op_host_disk_audit(a):',
    'def op_host_safe_cleanup(a):',
    'def op_host_unused_volume_remove(a):',
]:
    if marker not in source:
        raise SystemExit('missing broker 3.6 invariant: ' + marker)

out.write_text(source)
print('BROKER_AUTH_AWARE_V3_6_PATCH_PASS')
