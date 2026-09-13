#!/usr/bin/env python3
from pathlib import Path
import sys

if len(sys.argv) != 3:
    raise SystemExit("usage: patch-host-commander-broker-g18.py <input-broker.py> <output-broker.py>")

src = Path(sys.argv[1])
out = Path(sys.argv[2])
source = src.read_text()
marker = "METATRON_HOST_COMMANDER_BROKER_G18"

if marker in source:
    out.write_text(source)
    print("MCP_G18_COMMANDER_BROKER_ALREADY_PATCHED")
    raise SystemExit(0)

for required in [
    "def op_host_disk_audit(a):",
    "def op_host_safe_cleanup(a):",
    "def op_host_unused_volume_remove(a):",
    "def start_self_upgrade():",
    '"host_unused_volume_remove":op_host_unused_volume_remove',
]:
    if required not in source:
        raise SystemExit("g18 commander broker anchor missing: " + required)

commander = r'''
# METATRON_HOST_COMMANDER_BROKER_G18
CM_ROOT = "/var/lib/metatron-commander"
CM_AUDIT = "/var/log/metatron-commander-audit.jsonl"
CM_SUPERVISOR = "/usr/local/lib/metatron-commander-supervisor.py"
CM_SESSION_RE = re.compile(r"^cmdr-[0-9a-f]{32}$")
CM_PROCESS_RE = re.compile(r"^proc-[0-9a-f]{24}$")
CM_CONTAINER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
CM_BINDING_RE = re.compile(r"^[0-9a-f]{32}$")
CM_SCOPES = {"read_only", "maintenance", "recovery"}
CM_MUTABLE_ROOTS = ("/tmp/metatron-commander", "/var/tmp/metatron-commander")
CM_SOURCE_WRITE_PREFIXES = (
    "/opt/metatron/metatron-workforce",
    "/opt/metatron/ssh-mcp",
    "/opt/metatron/gateway-g6",
    "/opt/metatron/telegram-agent",
    "/opt/metatron/telegram-bot",
)
CM_SECRET_PREFIXES = (
    "/root/.ssh",
    "/opt/metatron/keys",
    "/etc/ssh",
    "/etc/ssl/private",
    "/var/lib/metatron-auth",
    "/var/lib/docker/volumes",
)
CM_SECRET_EXACT = {"/etc/shadow", "/etc/gshadow", "/etc/sudoers"}
CM_PRODUCTION_RESTART = {"deploy-workforce-1", "source-envoy-1", "source-cloudflared-1"}
CM_ACCEPTANCE_PREFIX = "metatron-commander-acceptance-"


def _cm_ensure_root():
    os.makedirs(CM_ROOT, mode=0o700, exist_ok=True)
    os.chmod(CM_ROOT, 0o700)
    for path in CM_MUTABLE_ROOTS:
        os.makedirs(path, mode=0o700, exist_ok=True)
        os.chmod(path, 0o700)


def _cm_response(payload, ok=True, exit_code=0, stderr=""):
    return {
        "ok": bool(ok),
        "exitCode": int(exit_code),
        "stdout": json.dumps(payload, separators=(",", ":"), sort_keys=True) + "\n",
        "stderr": stderr,
    }


def _cm_clip(text, limit=65536):
    if not isinstance(text, str):
        text = str(text)
    if len(text.encode("utf-8", errors="replace")) <= limit:
        return text, False
    data = text.encode("utf-8", errors="replace")[:limit]
    return data.decode("utf-8", errors="replace"), True


def _cm_redact(text):
    value = text if isinstance(text, str) else str(text)
    value = re.sub(r"(?i)(authorization\s*[:=]\s*bearer\s+)[^\s]+", r"\1[REDACTED]", value)
    value = re.sub(r"(?i)((?:token|secret|password|passwd|api[_-]?key|private[_-]?key)\s*[:=]\s*)[^\s,;]+", r"\1[REDACTED]", value)
    return value


def _cm_audit(event, session=None, payload=None):
    _cm_ensure_root()
    row = {
        "ts": time.time(),
        "event": event,
        "sessionId": (session or {}).get("sessionId", ""),
        "principal": (session or {}).get("principal", ""),
        "clientBinding": (session or {}).get("clientBinding", ""),
        "scope": (session or {}).get("scope", ""),
        "payload": payload or {},
    }
    try:
        fd = os.open(CM_AUDIT, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as fh:
            fh.write(json.dumps(row, separators=(",", ":"), sort_keys=True) + "\n")
            fh.flush()
            os.fsync(fh.fileno())
    except Exception:
        pass


def _cm_identity(a):
    principal = a.get("principal", "")
    binding = a.get("client_binding", "")
    if principal != "founder":
        fail("commander_founder_required")
    if not isinstance(binding, str) or not CM_BINDING_RE.fullmatch(binding):
        fail("commander_client_binding_invalid")
    return principal, binding


def _cm_session_path(session_id):
    return os.path.join(CM_ROOT, session_id + ".json")


def _cm_atomic_json(path, value):
    tmp = path + ".tmp." + str(os.getpid()) + "." + os.urandom(4).hex()
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        json.dump(value, fh, separators=(",", ":"), sort_keys=True)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)
    os.chmod(path, 0o600)


def _cm_load_session(a, mutation=False):
    _cm_ensure_root()
    principal, binding = _cm_identity(a)
    session_id = a.get("session_id", "")
    fencing = a.get("fencing_token", "")
    if not isinstance(session_id, str) or not CM_SESSION_RE.fullmatch(session_id):
        fail("commander_session_invalid")
    if not isinstance(fencing, str) or not re.fullmatch(r"[0-9a-f]{32}", fencing):
        fail("commander_fencing_token_invalid")
    path = _cm_session_path(session_id)
    try:
        with open(path, "r", encoding="utf-8") as fh:
            session = json.load(fh)
    except Exception:
        fail("commander_session_not_found")
    if session.get("principal") != principal or session.get("clientBinding") != binding:
        fail("commander_session_binding_mismatch")
    if session.get("fencingToken") != fencing:
        fail("commander_session_fence_mismatch")
    if session.get("status") != "ACTIVE":
        fail("commander_session_not_active")
    if float(session.get("expiresAt", 0)) <= time.time():
        session["status"] = "EXPIRED"
        session["updatedAt"] = time.time()
        _cm_atomic_json(path, session)
        fail("commander_session_expired")
    if mutation and session.get("scope") not in {"maintenance", "recovery"}:
        fail("commander_mutation_scope_required")
    return session


def _cm_mutation_lock(session):
    lock_path = os.path.join(CM_ROOT, session["sessionId"] + ".mutation.lock")
    fd = os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        os.close(fd)
        fail("commander_mutation_in_progress")
    return fd


def _cm_unlock(fd):
    try:
        fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


def _cm_under(path, prefix):
    return path == prefix or path.startswith(prefix + "/")


def _cm_path(path):
    if not isinstance(path, str) or not path.startswith("/") or "\x00" in path or len(path) > 4096:
        fail("commander_path_invalid")
    return os.path.realpath(path)


def _cm_secret_path(path):
    lower = path.lower()
    base = os.path.basename(lower)
    if path in CM_SECRET_EXACT:
        return True
    if any(_cm_under(path, p) for p in CM_SECRET_PREFIXES):
        return True
    if lower.startswith("/proc/") and (lower.endswith("/environ") or lower.endswith("/cmdline")):
        return True
    sensitive_names = (".env", "id_rsa", "id_ed25519", ".runtime-auth-security.enc")
    if base in sensitive_names:
        return True
    if any(token in base for token in ("secret", "credential", "token", "private_key", "private-key")):
        return True
    return False


def _cm_read_path(raw):
    path = _cm_path(raw)
    if _cm_secret_path(path):
        fail("commander_secret_path_denied")
    return path


def _cm_mutable_path(raw):
    if not isinstance(raw, str) or not raw.startswith("/"):
        fail("commander_path_invalid")
    parent = os.path.realpath(os.path.dirname(raw))
    path = os.path.join(parent, os.path.basename(raw))
    if not any(_cm_under(path, root) for root in CM_MUTABLE_ROOTS):
        fail("commander_write_scope_denied")
    if any(_cm_under(path, p) for p in CM_SOURCE_WRITE_PREFIXES):
        fail("commander_canonical_source_mutation_denied")
    if _cm_secret_path(path):
        fail("commander_secret_path_denied")
    if os.path.lexists(path) and os.path.islink(path):
        fail("commander_symlink_mutation_denied")
    return path


def _cm_process_dir(session, process_id):
    if not isinstance(process_id, str) or not CM_PROCESS_RE.fullmatch(process_id):
        fail("commander_process_invalid")
    return os.path.join(CM_ROOT, "processes", session["sessionId"], process_id)


def _cm_process_meta(path):
    try:
        with open(os.path.join(path, "meta.json"), "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return {"status": "STARTING"}


def _cm_exec_validate(executable, args):
    if not isinstance(executable, str) or "/" in executable or len(executable) > 64:
        fail("commander_executable_invalid")
    if not isinstance(args, list) or len(args) > 16 or any(not isinstance(v, str) or len(v) > 1024 or "\x00" in v for v in args):
        fail("commander_exec_args_invalid")
    if executable == "uptime" and args == []:
        return [executable]
    if executable == "hostname" and args == []:
        return [executable]
    if executable == "free" and args in ([], ["-h"], ["-m"]):
        return [executable, *args]
    if executable == "uname" and args in ([], ["-a"], ["-r"], ["-m"]):
        return [executable, *args]
    if executable == "id" and args in ([], ["-u"], ["-g"]):
        return [executable, *args]
    if executable == "date" and args in ([], ["-Is"]):
        return [executable, *args]
    if executable == "ss" and args in (["-lntup"], ["-ltnp"], ["-lunp"], ["-s"]):
        return [executable, *args]
    if executable == "df":
        if args in ([], ["-h"], ["-P"]):
            return [executable, *args]
        if len(args) == 2 and args[0] in {"-h", "-P"}:
            return [executable, args[0], _cm_read_path(args[1])]
    if executable == "du" and len(args) == 2 and args[0] in {"-xhd1", "-xhd2", "-xhd3"}:
        return [executable, args[0], _cm_read_path(args[1])]
    fail("commander_exec_policy_denied")


def _cm_container(name):
    if not isinstance(name, str) or not CM_CONTAINER_RE.fullmatch(name):
        fail("commander_container_invalid")
    return name


def op_commander_open(a):
    check_keys(a, ["principal", "client_binding", "purpose", "scope", "ttl_seconds"], ["principal", "client_binding", "purpose"])
    principal, binding = _cm_identity(a)
    purpose = a.get("purpose", "")
    if not isinstance(purpose, str) or not purpose.strip() or len(purpose) > 500 or "\x00" in purpose:
        fail("commander_purpose_invalid")
    scope = a.get("scope", "read_only")
    if scope not in CM_SCOPES:
        fail("commander_scope_invalid")
    ttl = a.get("ttl_seconds", 900)
    if not isinstance(ttl, int) or ttl < 60 or ttl > 1800:
        fail("commander_ttl_invalid")
    _cm_ensure_root()
    session_id = "cmdr-" + os.urandom(16).hex()
    now = time.time()
    session = {
        "sessionId": session_id,
        "principal": principal,
        "clientBinding": binding,
        "purpose": purpose.strip(),
        "scope": scope,
        "fencingToken": os.urandom(16).hex(),
        "status": "ACTIVE",
        "createdAt": now,
        "updatedAt": now,
        "expiresAt": now + ttl,
    }
    _cm_atomic_json(_cm_session_path(session_id), session)
    _cm_audit("session_open", session, {"purpose": session["purpose"], "ttlSeconds": ttl})
    return _cm_response({"accepted": True, "executed": True, "mutated": True, "verified": True, **session})


def op_commander_status(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token"], ["principal", "client_binding", "session_id", "fencing_token"])
    session = _cm_load_session(a, False)
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": True, **session})


def op_commander_close(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token"], ["principal", "client_binding", "session_id", "fencing_token"])
    session = _cm_load_session(a, False)
    session["status"] = "COMPLETED"
    session["updatedAt"] = time.time()
    _cm_atomic_json(_cm_session_path(session["sessionId"]), session)
    _cm_audit("session_close", session)
    return _cm_response({"accepted": True, "executed": True, "mutated": True, "verified": True, "sessionId": session["sessionId"], "status": "COMPLETED"})


def op_commander_file_list(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "path", "max_entries"], ["principal", "client_binding", "session_id", "fencing_token", "path"])
    session = _cm_load_session(a, False)
    path = _cm_read_path(a.get("path"))
    limit = a.get("max_entries", 200)
    if not isinstance(limit, int) or limit < 1 or limit > 500:
        fail("commander_max_entries_invalid")
    if not os.path.isdir(path):
        fail("commander_directory_not_found")
    items = []
    for entry in sorted(os.scandir(path), key=lambda e: e.name)[:limit]:
        item_path = os.path.realpath(entry.path)
        items.append({"name": entry.name, "directory": entry.is_dir(follow_symlinks=False), "symlink": entry.is_symlink(), "path": item_path})
    _cm_audit("file_list", session, {"path": path, "count": len(items)})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": True, "path": path, "items": items})


def op_commander_file_stat(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "path"], ["principal", "client_binding", "session_id", "fencing_token", "path"])
    session = _cm_load_session(a, False)
    path = _cm_read_path(a.get("path"))
    st = os.stat(path, follow_symlinks=False)
    payload = {"path": path, "size": st.st_size, "mode": oct(st.st_mode & 0o7777), "mtime": st.st_mtime, "uid": st.st_uid, "gid": st.st_gid, "directory": os.path.isdir(path), "symlink": os.path.islink(path)}
    _cm_audit("file_stat", session, {"path": path})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": True, **payload})


def op_commander_file_read(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "path", "max_bytes"], ["principal", "client_binding", "session_id", "fencing_token", "path"])
    session = _cm_load_session(a, False)
    path = _cm_read_path(a.get("path"))
    max_bytes = a.get("max_bytes", 65536)
    if not isinstance(max_bytes, int) or max_bytes < 1 or max_bytes > 131072:
        fail("commander_max_bytes_invalid")
    if not os.path.isfile(path) or os.path.islink(path):
        fail("commander_file_not_regular")
    with open(path, "rb") as fh:
        data = fh.read(max_bytes + 1)
    truncated = len(data) > max_bytes
    data = data[:max_bytes]
    if b"\x00" in data:
        fail("commander_binary_file_denied")
    content = _cm_redact(data.decode("utf-8", errors="replace"))
    _cm_audit("file_read", session, {"path": path, "bytes": len(data), "truncated": truncated})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": True, "path": path, "content": content, "truncated": truncated})


def op_commander_file_search(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "path", "query", "max_matches"], ["principal", "client_binding", "session_id", "fencing_token", "path", "query"])
    session = _cm_load_session(a, False)
    root = _cm_read_path(a.get("path"))
    query = a.get("query", "")
    if not isinstance(query, str) or not query or len(query) > 500 or "\x00" in query:
        fail("commander_query_invalid")
    max_matches = a.get("max_matches", 100)
    if not isinstance(max_matches, int) or max_matches < 1 or max_matches > 200:
        fail("commander_max_matches_invalid")
    if not os.path.isdir(root):
        fail("commander_directory_not_found")
    matches = []
    scanned = 0
    for base, dirs, files in os.walk(root):
        rel = os.path.relpath(base, root)
        depth = 0 if rel == "." else rel.count(os.sep) + 1
        if depth >= 8:
            dirs[:] = []
        dirs[:] = [d for d in dirs if not _cm_secret_path(os.path.realpath(os.path.join(base, d)))]
        for name in files:
            if len(matches) >= max_matches or scanned >= 20 * 1024 * 1024:
                break
            path = os.path.realpath(os.path.join(base, name))
            if _cm_secret_path(path) or os.path.islink(path):
                continue
            try:
                size = os.path.getsize(path)
                if size > 1024 * 1024:
                    continue
                scanned += size
                with open(path, "r", encoding="utf-8", errors="strict") as fh:
                    for number, line in enumerate(fh, 1):
                        if query in line:
                            matches.append({"path": path, "line": number, "excerpt": _cm_redact(line.strip())[:500]})
                            if len(matches) >= max_matches:
                                break
            except (OSError, UnicodeError):
                continue
        if len(matches) >= max_matches or scanned >= 20 * 1024 * 1024:
            break
    _cm_audit("file_search", session, {"path": root, "query": query, "matches": len(matches), "scannedBytes": scanned})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": True, "path": root, "query": query, "matches": matches, "scannedBytes": scanned})


def op_commander_file_write(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "path", "content"], ["principal", "client_binding", "session_id", "fencing_token", "path", "content"])
    session = _cm_load_session(a, True)
    content = a.get("content")
    if not isinstance(content, str) or len(content.encode("utf-8")) > 65536:
        fail("commander_content_invalid")
    path = _cm_mutable_path(a.get("path"))
    lock = _cm_mutation_lock(session)
    try:
        tmp = path + ".tmp." + os.urandom(4).hex()
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(content)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
        os.chmod(path, 0o600)
        with open(path, "r", encoding="utf-8") as fh:
            verified = fh.read() == content
        if not verified:
            fail("commander_file_write_verify_failed")
    finally:
        _cm_unlock(lock)
    _cm_audit("file_write", session, {"path": path, "bytes": len(content.encode("utf-8"))})
    return _cm_response({"accepted": True, "executed": True, "mutated": True, "verified": True, "path": path, "bytes": len(content.encode("utf-8"))})


def op_commander_file_patch(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "path", "old_text", "new_text", "expected_occurrences"], ["principal", "client_binding", "session_id", "fencing_token", "path", "old_text"])
    session = _cm_load_session(a, True)
    old = a.get("old_text")
    new = a.get("new_text", "")
    expected = a.get("expected_occurrences", 1)
    if not isinstance(old, str) or not old or not isinstance(new, str) or not isinstance(expected, int) or expected < 1 or expected > 100:
        fail("commander_patch_invalid")
    path = _cm_mutable_path(a.get("path"))
    lock = _cm_mutation_lock(session)
    try:
        with open(path, "r", encoding="utf-8") as fh:
            content = fh.read(262145)
        if len(content.encode("utf-8")) > 262144:
            fail("commander_patch_file_too_large")
        actual = content.count(old)
        if actual != expected:
            fail("commander_patch_occurrence_mismatch")
        updated = content.replace(old, new)
        if len(updated.encode("utf-8")) > 262144:
            fail("commander_patch_result_too_large")
        tmp = path + ".tmp." + os.urandom(4).hex()
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write(updated)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    finally:
        _cm_unlock(lock)
    _cm_audit("file_patch", session, {"path": path, "occurrences": actual})
    return _cm_response({"accepted": True, "executed": True, "mutated": True, "verified": True, "path": path, "occurrences": actual})


def op_commander_exec(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "executable", "args", "working_directory", "timeout_seconds", "max_output_bytes"], ["principal", "client_binding", "session_id", "fencing_token", "executable"])
    session = _cm_load_session(a, False)
    argv = _cm_exec_validate(a.get("executable"), a.get("args", []))
    cwd = a.get("working_directory", "/")
    cwd = _cm_read_path(cwd)
    if not os.path.isdir(cwd):
        fail("commander_working_directory_invalid")
    timeout = a.get("timeout_seconds", 30)
    max_output = a.get("max_output_bytes", 65536)
    if not isinstance(timeout, int) or timeout < 1 or timeout > 120:
        fail("commander_timeout_invalid")
    if not isinstance(max_output, int) or max_output < 1024 or max_output > 131072:
        fail("commander_max_output_invalid")
    started = time.time()
    try:
        p = subprocess.run(argv, cwd=cwd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout, check=False)
        output, truncated = _cm_clip(_cm_redact(p.stdout), max_output)
        payload = {"accepted": True, "executed": True, "mutated": False, "verified": p.returncode == 0, "executable": argv[0], "args": argv[1:], "workingDirectory": cwd, "exitCode": p.returncode, "output": output, "outputTruncated": truncated, "durationMillis": int((time.time() - started) * 1000)}
        _cm_audit("exec", session, {"executable": argv[0], "args": argv[1:], "exitCode": p.returncode})
        return _cm_response(payload, p.returncode == 0, p.returncode, "" if p.returncode == 0 else "commander_exec_nonzero")
    except subprocess.TimeoutExpired as exc:
        output, truncated = _cm_clip(_cm_redact((exc.stdout or "") if isinstance(exc.stdout, str) else ""), max_output)
        _cm_audit("exec_timeout", session, {"executable": argv[0], "args": argv[1:], "timeoutSeconds": timeout})
        return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": False, "timedOut": True, "output": output, "outputTruncated": truncated}, False, 124, "commander_exec_timeout")


def op_commander_process_start(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "executable", "args", "working_directory", "timeout_seconds", "max_output_bytes"], ["principal", "client_binding", "session_id", "fencing_token", "executable"])
    session = _cm_load_session(a, True)
    executable = a.get("executable")
    args = a.get("args", [])
    if executable != "cat" or args != []:
        fail("commander_interactive_process_policy_denied")
    cwd = _cm_read_path(a.get("working_directory", "/tmp"))
    if not os.path.isdir(cwd):
        fail("commander_working_directory_invalid")
    timeout = a.get("timeout_seconds", 300)
    max_output = a.get("max_output_bytes", 262144)
    if not isinstance(timeout, int) or timeout < 5 or timeout > 1800:
        fail("commander_timeout_invalid")
    if not isinstance(max_output, int) or max_output < 1024 or max_output > 1048576:
        fail("commander_max_output_invalid")
    if not os.path.isfile(CM_SUPERVISOR):
        fail("commander_supervisor_missing")
    process_id = "proc-" + os.urandom(12).hex()
    pdir = _cm_process_dir(session, process_id)
    os.makedirs(pdir, mode=0o700, exist_ok=False)
    lock = _cm_mutation_lock(session)
    try:
        supervisor = subprocess.Popen(
            ["python3", CM_SUPERVISOR, pdir, executable, json.dumps(args), cwd, str(max_output), str(timeout)],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
            close_fds=True,
        )
    finally:
        _cm_unlock(lock)
    _cm_audit("process_start", session, {"processId": process_id, "executable": executable, "supervisorPid": supervisor.pid})
    return _cm_response({"accepted": True, "executed": True, "mutated": True, "verified": supervisor.pid > 1, "processId": process_id, "supervisorPid": supervisor.pid})


def op_commander_process_input(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "process_id", "data"], ["principal", "client_binding", "session_id", "fencing_token", "process_id", "data"])
    session = _cm_load_session(a, True)
    data = a.get("data")
    if not isinstance(data, str) or len(data.encode("utf-8")) > 4096:
        fail("commander_process_input_invalid")
    pdir = _cm_process_dir(session, a.get("process_id"))
    if not os.path.isdir(pdir):
        fail("commander_process_not_found")
    lock = _cm_mutation_lock(session)
    try:
        path = os.path.join(pdir, "input.jsonl")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_APPEND, 0o600)
        with os.fdopen(fd, "a", encoding="utf-8") as fh:
            fh.write(json.dumps({"dataHex": data.encode("utf-8").hex()}, separators=(",", ":")) + "\n")
            fh.flush()
            os.fsync(fh.fileno())
    finally:
        _cm_unlock(lock)
    _cm_audit("process_input", session, {"processId": a.get("process_id"), "bytes": len(data.encode("utf-8"))})
    return _cm_response({"accepted": True, "executed": True, "mutated": True, "verified": True, "processId": a.get("process_id"), "bytes": len(data.encode("utf-8"))})


def op_commander_process_output(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "process_id", "offset", "max_bytes"], ["principal", "client_binding", "session_id", "fencing_token", "process_id"])
    session = _cm_load_session(a, False)
    pdir = _cm_process_dir(session, a.get("process_id"))
    if not os.path.isdir(pdir):
        fail("commander_process_not_found")
    offset = a.get("offset", 0)
    max_bytes = a.get("max_bytes", 65536)
    if not isinstance(offset, int) or offset < 0 or not isinstance(max_bytes, int) or max_bytes < 1 or max_bytes > 131072:
        fail("commander_process_output_bounds_invalid")
    output_path = os.path.join(pdir, "output.log")
    data = b""
    next_offset = offset
    if os.path.exists(output_path):
        with open(output_path, "rb") as fh:
            fh.seek(offset)
            data = fh.read(max_bytes)
            next_offset = fh.tell()
    meta = _cm_process_meta(pdir)
    content = _cm_redact(data.decode("utf-8", errors="replace"))
    _cm_audit("process_output", session, {"processId": a.get("process_id"), "offset": offset, "bytes": len(data)})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": True, "processId": a.get("process_id"), "output": content, "nextOffset": next_offset, "meta": meta})


def op_commander_process_list(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token"], ["principal", "client_binding", "session_id", "fencing_token"])
    session = _cm_load_session(a, False)
    root = os.path.join(CM_ROOT, "processes", session["sessionId"])
    items = []
    if os.path.isdir(root):
        for name in sorted(os.listdir(root))[:100]:
            if CM_PROCESS_RE.fullmatch(name):
                items.append({"processId": name, "meta": _cm_process_meta(os.path.join(root, name))})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": True, "processes": items})


def op_commander_process_terminate(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "process_id"], ["principal", "client_binding", "session_id", "fencing_token", "process_id"])
    session = _cm_load_session(a, True)
    pdir = _cm_process_dir(session, a.get("process_id"))
    if not os.path.isdir(pdir):
        fail("commander_process_not_found")
    lock = _cm_mutation_lock(session)
    try:
        path = os.path.join(pdir, "control")
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            fh.write("TERMINATE\n")
            fh.flush()
            os.fsync(fh.fileno())
    finally:
        _cm_unlock(lock)
    _cm_audit("process_terminate", session, {"processId": a.get("process_id")})
    return _cm_response({"accepted": True, "executed": True, "mutated": True, "verified": True, "processId": a.get("process_id"), "terminationRequested": True})


def op_commander_docker_inspect(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "container"], ["principal", "client_binding", "session_id", "fencing_token", "container"])
    session = _cm_load_session(a, False)
    name = _cm_container(a.get("container"))
    rc, out, err = _hm_run(["docker", "inspect", name], 30)
    if rc != 0:
        return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": False, "container": name}, False, rc, "commander_docker_inspect_failed")
    raw = json.loads(out)[0]
    state = raw.get("State") or {}
    health = state.get("Health") or {}
    mounts = []
    for mount in raw.get("Mounts") or []:
        mounts.append({"type": mount.get("Type", ""), "name": mount.get("Name", ""), "destination": mount.get("Destination", ""), "rw": bool(mount.get("RW", False))})
    payload = {"container": name, "id": str(raw.get("Id", ""))[:12], "image": (raw.get("Config") or {}).get("Image", ""), "status": state.get("Status", ""), "health": health.get("Status", ""), "restartCount": raw.get("RestartCount", 0), "created": raw.get("Created", ""), "mounts": mounts}
    _cm_audit("docker_inspect", session, {"container": name})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": True, **payload})


def op_commander_docker_logs(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "container", "lines"], ["principal", "client_binding", "session_id", "fencing_token", "container"])
    session = _cm_load_session(a, False)
    name = _cm_container(a.get("container"))
    lines = a.get("lines", 200)
    if not isinstance(lines, int) or lines < 1 or lines > 1000:
        fail("commander_log_lines_invalid")
    rc, out, err = _hm_run(["docker", "logs", "--tail", str(lines), name], 45)
    text, truncated = _cm_clip(_cm_redact((out or "") + (err or "")), 131072)
    _cm_audit("docker_logs", session, {"container": name, "lines": lines, "exitCode": rc})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": rc == 0, "container": name, "logs": text, "outputTruncated": truncated}, rc == 0, rc, "" if rc == 0 else "commander_docker_logs_failed")


def op_commander_docker_action(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "container", "action"], ["principal", "client_binding", "session_id", "fencing_token", "container", "action"])
    session = _cm_load_session(a, True)
    name = _cm_container(a.get("container"))
    action = a.get("action")
    acceptance = name.startswith(CM_ACCEPTANCE_PREFIX)
    if acceptance:
        if action not in {"start", "stop", "restart", "remove"}:
            fail("commander_docker_action_denied")
    elif name in CM_PRODUCTION_RESTART and action == "restart":
        pass
    else:
        fail("commander_docker_action_denied")
    argv = ["docker", "rm", "-f", name] if action == "remove" else ["docker", action, name]
    lock = _cm_mutation_lock(session)
    try:
        rc, out, err = _hm_run(argv, 90)
    finally:
        _cm_unlock(lock)
    _cm_audit("docker_action", session, {"container": name, "action": action, "exitCode": rc})
    return _cm_response({"accepted": True, "executed": True, "mutated": rc == 0, "verified": rc == 0, "container": name, "action": action, "output": _cm_redact((out or "") + (err or ""))[:8192]}, rc == 0, rc, "" if rc == 0 else "commander_docker_action_failed")


def op_commander_storage_inspect(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token"], ["principal", "client_binding", "session_id", "fencing_token"])
    session = _cm_load_session(a, False)
    result = op_host_disk_audit({})
    _cm_audit("storage_inspect", session, {"exitCode": result.get("exitCode", 1)})
    return result


def op_commander_storage_cleanup(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token", "journal_max_mb", "builder_until_hours"], ["principal", "client_binding", "session_id", "fencing_token"])
    session = _cm_load_session(a, True)
    args = {}
    if "journal_max_mb" in a:
        args["journal_max_mb"] = a["journal_max_mb"]
    if "builder_until_hours" in a:
        args["builder_until_hours"] = a["builder_until_hours"]
    lock = _cm_mutation_lock(session)
    try:
        result = op_host_safe_cleanup(args)
    finally:
        _cm_unlock(lock)
    _cm_audit("storage_cleanup", session, {"exitCode": result.get("exitCode", 1), **args})
    return result


def op_commander_network_inspect(a):
    check_keys(a, ["principal", "client_binding", "session_id", "fencing_token"], ["principal", "client_binding", "session_id", "fencing_token"])
    session = _cm_load_session(a, False)
    rc, out, err = _hm_run(["ss", "-lntup"], 30)
    text, truncated = _cm_clip(_cm_redact((out or "") + (err or "")), 65536)
    _cm_audit("network_inspect", session, {"exitCode": rc})
    return _cm_response({"accepted": True, "executed": True, "mutated": False, "verified": rc == 0, "sockets": text, "outputTruncated": truncated}, rc == 0, rc, "" if rc == 0 else "commander_network_inspect_failed")

'''

source = source.replace("def start_self_upgrade():", commander + "def start_self_upgrade():", 1)
map_anchor = '"host_unused_volume_remove":op_host_unused_volume_remove'
map_insert = map_anchor + ',\n    "commander_open":op_commander_open' + ',\n    "commander_status":op_commander_status' + ',\n    "commander_close":op_commander_close' + ',\n    "commander_file_list":op_commander_file_list' + ',\n    "commander_file_stat":op_commander_file_stat' + ',\n    "commander_file_read":op_commander_file_read' + ',\n    "commander_file_search":op_commander_file_search' + ',\n    "commander_file_write":op_commander_file_write' + ',\n    "commander_file_patch":op_commander_file_patch' + ',\n    "commander_exec":op_commander_exec' + ',\n    "commander_process_start":op_commander_process_start' + ',\n    "commander_process_input":op_commander_process_input' + ',\n    "commander_process_output":op_commander_process_output' + ',\n    "commander_process_list":op_commander_process_list' + ',\n    "commander_process_terminate":op_commander_process_terminate' + ',\n    "commander_docker_inspect":op_commander_docker_inspect' + ',\n    "commander_docker_logs":op_commander_docker_logs' + ',\n    "commander_docker_action":op_commander_docker_action' + ',\n    "commander_storage_inspect":op_commander_storage_inspect' + ',\n    "commander_storage_cleanup":op_commander_storage_cleanup' + ',\n    "commander_network_inspect":op_commander_network_inspect'
source = source.replace(map_anchor, map_insert, 1)

for required in [
    marker,
    "def op_commander_open(a):",
    "def op_commander_exec(a):",
    "def op_commander_process_start(a):",
    "def op_commander_docker_action(a):",
    "def op_commander_storage_cleanup(a):",
    '"commander_open":op_commander_open',
    '"commander_network_inspect":op_commander_network_inspect',
    "commander_canonical_source_mutation_denied",
    "commander_secret_path_denied",
    "commander_founder_required",
]:
    if required not in source:
        raise SystemExit("g18 commander broker invariant missing: " + required)

out.write_text(source)
print("MCP_G18_COMMANDER_BROKER_PATCH_PASS")
