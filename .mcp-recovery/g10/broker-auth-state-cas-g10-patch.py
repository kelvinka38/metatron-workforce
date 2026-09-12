#!/usr/bin/env python3
import pathlib, sys

if len(sys.argv) != 2:
    raise SystemExit('usage: broker-auth-state-cas-g10-patch.py <broker>')
p = pathlib.Path(sys.argv[1])
s = p.read_text()
MARKER = 'METATRON_MCP_AUTH_STATE_CAS_G10'
if MARKER in s:
    print('BROKER_AUTH_STATE_CAS_G10_ALREADY_APPLIED')
    raise SystemExit(0)

old_import = 'import base64, concurrent.futures, fcntl, json, os, re, subprocess, sys, time, urllib.error, urllib.request'
new_import = 'import base64, concurrent.futures, fcntl, hashlib, json, os, re, subprocess, sys, time, urllib.error, urllib.request'
if old_import not in s:
    raise SystemExit('broker import anchor missing')
s = s.replace(old_import, new_import, 1)

if 'VERSION = "3.6.0"' not in s:
    raise SystemExit('broker version anchor missing')
s = s.replace('VERSION = "3.6.0"', 'VERSION = "3.7.0-g10"', 1)

anchor = '\ndef op_workspace_search(a):\n'
if anchor not in s:
    raise SystemExit('workspace search anchor missing')
block = r'''

# METATRON_MCP_AUTH_STATE_CAS_G10
AUTH_STATE_PATH = os.environ.get("METATRON_MCP_AUTH_STATE_PATH", "/opt/metatron/ssh-mcp/build/.runtime-auth-security.enc")
AUTH_STATE_LOCK = os.environ.get("METATRON_MCP_AUTH_STATE_LOCK", "/var/lock/metatron-mcp-auth-state.lock")

def _auth_state_lock(exclusive):
    os.makedirs(os.path.dirname(AUTH_STATE_LOCK), exist_ok=True)
    fd = os.open(AUTH_STATE_LOCK, os.O_RDWR | os.O_CREAT, 0o600)
    fcntl.flock(fd, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
    return fd

def _auth_state_unlock(fd):
    try:
        fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)

def _auth_state_bytes():
    try:
        with open(AUTH_STATE_PATH, "rb") as f:
            return True, f.read(MAX_INPUT + 1)
    except FileNotFoundError:
        return False, b""

def _auth_state_sha(data, exists=True):
    return hashlib.sha256(data).hexdigest() if exists else ""

def op_auth_state_read(a):
    check_keys(a, [])
    fd = _auth_state_lock(False)
    try:
        exists, data = _auth_state_bytes()
        if len(data) > MAX_INPUT:
            fail("auth_state_too_large")
        payload = {
            "exists": exists,
            "sha256": _auth_state_sha(data, exists),
            "content_b64": base64.b64encode(data).decode("ascii") if exists else "",
        }
        return {"ok": True, "exitCode": 0, "stdout": json.dumps(payload, separators=(",", ":")), "stderr": ""}
    finally:
        _auth_state_unlock(fd)

def op_auth_state_compare_and_swap(a):
    check_keys(a, ["expected_sha256", "content_b64"], ["expected_sha256", "content_b64"])
    expected = a.get("expected_sha256")
    encoded = a.get("content_b64")
    if not isinstance(expected, str) or (expected and not re.fullmatch(r"[0-9a-f]{64}", expected)):
        fail("invalid_expected_sha256")
    if not isinstance(encoded, str):
        fail("invalid_content")
    try:
        data = base64.b64decode(encoded, validate=True)
    except Exception:
        fail("invalid_content")
    if len(data) > MAX_INPUT:
        fail("content_too_large")

    fd = _auth_state_lock(True)
    try:
        exists, current = _auth_state_bytes()
        if len(current) > MAX_INPUT:
            fail("auth_state_too_large")
        current_sha = _auth_state_sha(current, exists)
        if current_sha != expected:
            payload = {"status": "conflict", "current_sha256": current_sha}
            return {"ok": False, "exitCode": 75, "stdout": json.dumps(payload, separators=(",", ":")), "stderr": "auth_state_conflict"}

        parent = os.path.dirname(AUTH_STATE_PATH)
        os.makedirs(parent, exist_ok=True)
        tmp = AUTH_STATE_PATH + f".cas.{os.getpid()}.{time.time_ns()}"
        out_fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(out_fd, "wb", closefd=True) as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, AUTH_STATE_PATH)
            os.chmod(AUTH_STATE_PATH, 0o600)
            try:
                dir_fd = os.open(parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
                try:
                    os.fsync(dir_fd)
                finally:
                    os.close(dir_fd)
            except OSError:
                pass
        finally:
            try:
                if os.path.exists(tmp):
                    os.unlink(tmp)
            except OSError:
                pass

        new_sha = hashlib.sha256(data).hexdigest()
        payload = {"status": "written", "sha256": new_sha}
        return {"ok": True, "exitCode": 0, "stdout": json.dumps(payload, separators=(",", ":")), "stderr": ""}
    finally:
        _auth_state_unlock(fd)
'''
s = s.replace(anchor, block + anchor, 1)

map_anchor = '"workspace_read_file":op_workspace_read_file,"workspace_write_file":op_workspace_write_file,"workspace_search":op_workspace_search,'
map_repl = '"workspace_read_file":op_workspace_read_file,"workspace_write_file":op_workspace_write_file,"auth_state_read":op_auth_state_read,"auth_state_compare_and_swap":op_auth_state_compare_and_swap,"workspace_search":op_workspace_search,'
if map_anchor not in s:
    raise SystemExit('OPS map anchor missing')
s = s.replace(map_anchor, map_repl, 1)

for required in [MARKER, 'VERSION = "3.7.0-g10"', 'def op_auth_state_read', 'def op_auth_state_compare_and_swap', 'fcntl.LOCK_EX', 'auth_state_compare_and_swap":op_auth_state_compare_and_swap']:
    if required not in s:
        raise SystemExit('broker CAS invariant missing: ' + required)

p.write_text(s)
print('BROKER_AUTH_STATE_CAS_G10_PATCH_PASS')
