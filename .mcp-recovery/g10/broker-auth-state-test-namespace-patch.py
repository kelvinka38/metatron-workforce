#!/usr/bin/env python3
import pathlib, sys

if len(sys.argv) != 2:
    raise SystemExit('usage: broker-auth-state-test-namespace-patch.py <g10-broker>')
p = pathlib.Path(sys.argv[1])
s = p.read_text()
MARKER = 'METATRON_MCP_AUTH_STATE_TEST_NAMESPACE_G10'
if MARKER in s:
    print('BROKER_AUTH_STATE_TEST_NAMESPACE_ALREADY_APPLIED')
    raise SystemExit(0)
if 'METATRON_MCP_AUTH_STATE_CAS_G10' not in s or 'VERSION = "3.7.0-g10"' not in s:
    raise SystemExit('requires production g10 CAS broker candidate')
s = s.replace('VERSION = "3.7.0-g10"', 'VERSION = "3.7.1-g10-test"', 1)
anchor = '\ndef op_workspace_search(a):\n'
if anchor not in s:
    raise SystemExit('workspace anchor missing')
block = r'''

# METATRON_MCP_AUTH_STATE_TEST_NAMESPACE_G10
AUTH_STATE_TEST_PATH = "/var/lib/metatron-mcp/g10-acceptance-auth-state.enc"
AUTH_STATE_TEST_LOCK = "/var/lock/metatron-mcp-auth-state.g10-acceptance.lock"

def _auth_state_test_lock(exclusive):
    fd = os.open(AUTH_STATE_TEST_LOCK, os.O_RDWR | os.O_CREAT, 0o600)
    fcntl.flock(fd, fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH)
    return fd

def _auth_state_test_unlock(fd):
    try:
        fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)

def _auth_state_test_bytes():
    try:
        with open(AUTH_STATE_TEST_PATH, "rb") as f:
            return True, f.read(MAX_INPUT + 1)
    except FileNotFoundError:
        return False, b""

def op_auth_state_read_test(a):
    check_keys(a, [])
    fd = _auth_state_test_lock(False)
    try:
        exists, data = _auth_state_test_bytes()
        if len(data) > MAX_INPUT:
            fail("auth_state_too_large")
        payload = {"exists": exists, "sha256": hashlib.sha256(data).hexdigest() if exists else "", "content_b64": base64.b64encode(data).decode("ascii") if exists else ""}
        return {"ok": True, "exitCode": 0, "stdout": json.dumps(payload, separators=(",", ":")), "stderr": ""}
    finally:
        _auth_state_test_unlock(fd)

def op_auth_state_compare_and_swap_test(a):
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
    fd = _auth_state_test_lock(True)
    try:
        exists, current = _auth_state_test_bytes()
        if len(current) > MAX_INPUT:
            fail("auth_state_too_large")
        current_sha = hashlib.sha256(current).hexdigest() if exists else ""
        if current_sha != expected:
            return {"ok": False, "exitCode": 75, "stdout": json.dumps({"status":"conflict","current_sha256":current_sha}, separators=(",", ":")), "stderr": "auth_state_conflict"}
        parent = os.path.dirname(AUTH_STATE_TEST_PATH)
        os.makedirs(parent, exist_ok=True)
        tmp = AUTH_STATE_TEST_PATH + f".cas.{os.getpid()}.{time.time_ns()}"
        out_fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            with os.fdopen(out_fd, "wb", closefd=True) as f:
                f.write(data); f.flush(); os.fsync(f.fileno())
            os.replace(tmp, AUTH_STATE_TEST_PATH); os.chmod(AUTH_STATE_TEST_PATH, 0o600)
        finally:
            try:
                if os.path.exists(tmp): os.unlink(tmp)
            except OSError:
                pass
        return {"ok": True, "exitCode": 0, "stdout": json.dumps({"status":"written","sha256":hashlib.sha256(data).hexdigest()}, separators=(",", ":")), "stderr": ""}
    finally:
        _auth_state_test_unlock(fd)
'''
s = s.replace(anchor, block + anchor, 1)
map_anchor = '"auth_state_read":op_auth_state_read,"auth_state_compare_and_swap":op_auth_state_compare_and_swap,"workspace_search":op_workspace_search,'
map_repl = '"auth_state_read":op_auth_state_read,"auth_state_compare_and_swap":op_auth_state_compare_and_swap,"auth_state_read_test":op_auth_state_read_test,"auth_state_compare_and_swap_test":op_auth_state_compare_and_swap_test,"workspace_search":op_workspace_search,'
if map_anchor not in s:
    raise SystemExit('OPS CAS anchor missing')
s = s.replace(map_anchor, map_repl, 1)
for item in [MARKER,'VERSION = "3.7.1-g10-test"','op_auth_state_read_test','op_auth_state_compare_and_swap_test']:
    if item not in s: raise SystemExit('test namespace invariant missing: '+item)
p.write_text(s)
print('BROKER_AUTH_STATE_TEST_NAMESPACE_PATCH_PASS')
