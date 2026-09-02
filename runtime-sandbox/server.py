#!/usr/bin/env python3
import ctypes
import hmac
import json
import os
import re
import shlex
import signal
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(os.environ.get("SANDBOX_WORKSPACE_ROOT", "/workspaces")).resolve()
TOKEN_FILE = Path(os.environ.get("SANDBOX_TOKEN_FILE", "/tmp/metatron-sandbox-token"))
TOKEN = TOKEN_FILE.read_text(encoding="utf-8").strip()
try:
    TOKEN_FILE.unlink()
except FileNotFoundError:
    pass
if not TOKEN:
    raise SystemExit("SANDBOX_TOKEN_REQUIRED")

# The long-lived server keeps the transport token only in memory and is non-dumpable.
try:
    libc = ctypes.CDLL(None)
    PR_SET_DUMPABLE = 4
    libc.prctl(PR_SET_DUMPABLE, 0, 0, 0, 0)
except Exception:
    pass

# Drop root after reading/removing the root-owned token file. Worker child processes therefore
# never receive the transport credential and cannot recover it from the server environment.
if os.getuid() == 0:
    os.setgid(10001)
    os.setuid(10001)

ROOT.mkdir(parents=True, exist_ok=True)
LOG_ROOT = Path("/tmp/metatron-sandbox-logs")
LOG_ROOT.mkdir(parents=True, exist_ok=True)
ALLOWED = {x.strip() for x in os.environ.get(
    "SANDBOX_ALLOWED_EXECUTABLES",
    "git,java,javac,sh,bash,gradle,mvn,./gradlew,./mvnw"
).split(",") if x.strip()}
WORKSPACE_RE = re.compile(r"^[0-9a-f]{32}$")
SHELL_DENY = re.compile(r"[;&|><`$\n\r]|\$\(")
MAX_REQUEST = 128_000


def workspace_for(key: str) -> Path:
    if not WORKSPACE_RE.fullmatch(key or ""):
        raise ValueError("invalid workspace key")
    path = (ROOT / key).resolve()
    if ROOT not in path.parents:
        raise ValueError("workspace escaped root")
    path.mkdir(parents=True, exist_ok=True)
    return path


def executable_command(workspace: Path, executable: str, args):
    if executable not in ALLOWED:
        raise PermissionError("executable denied")
    args = [str(x) for x in (args or [])]
    if len(args) > 100:
        raise ValueError("too many process arguments")
    if sum(len(x) for x in args) > 32_000:
        raise ValueError("process arguments too large")

    if executable in {"sh", "bash"}:
        if len(args) != 2 or args[0] not in {"-c", "-lc", "-ec", "-euc"}:
            raise PermissionError("shell requires one governed command string")
        command = args[1].strip()
        if not command or SHELL_DENY.search(command):
            raise PermissionError("shell metacharacters denied")
        tokens = shlex.split(command, posix=True)
        if not tokens:
            raise PermissionError("empty shell command")
        command_executable = tokens[0]
        if command_executable not in ALLOWED - {"sh", "bash"}:
            raise PermissionError("shell command executable denied")
        # Execute the parsed command directly. The 'shell' action is a governed command surface,
        # not a way to bypass the executable profile through pipes/substitution/redirection.
        executable = command_executable
        args = tokens[1:]

    if executable.startswith("./"):
        candidate = (workspace / executable[2:]).resolve()
        if workspace not in candidate.parents or not candidate.is_file():
            raise PermissionError("workspace executable missing or escaped")
        return [str(candidate)] + args
    return [executable] + args


def run_process(payload):
    started = time.monotonic()
    workspace_key = str(payload.get("workspaceKey", ""))
    workspace = workspace_for(workspace_key)
    executable = str(payload.get("executable", ""))
    command = executable_command(workspace, executable, payload.get("args") or [])
    timeout = max(1, min(int(payload.get("timeoutSeconds", 60)), 300))
    max_output = max(1024, min(int(payload.get("maxOutputBytes", 512000)), 4_000_000))
    # Process output is sandbox transport state, not Objective work product. Keeping this log out of
    # the workspace prevents Git baselines/Observation from treating command plumbing as a mutation.
    log = LOG_ROOT / f"{workspace_key}.log"
    env = {
        "PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME": str(workspace),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "GIT_TERMINAL_PROMPT": "0",
        "GRADLE_USER_HOME": str(workspace / ".gradle"),
        "MAVEN_OPTS": "-Dmaven.repo.local=" + str(workspace / ".m2/repository"),
    }
    timed_out = False
    with log.open("wb") as stream:
        process = subprocess.Popen(
            command,
            cwd=workspace,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=stream,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            close_fds=True,
        )
        try:
            exit_code = process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            exit_code = process.wait(timeout=5)
    data = log.read_bytes()
    truncated = len(data) > max_output
    if truncated:
        data = data[:max_output]
    output = data.decode("utf-8", errors="replace")
    duration = int((time.monotonic() - started) * 1000)
    return {
        "success": (exit_code == 0 and not timed_out),
        "exitCode": exit_code,
        "timedOut": timed_out,
        "outputTruncated": truncated,
        "output": output,
        "workspaceKey": workspace_key,
        "executable": str(payload.get("executable", "")),
        "durationMillis": duration,
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "MetatronWorkerSandbox/1"

    def log_message(self, fmt, *args):
        return

    def _json(self, status, payload):
        data = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            self._json(200, {"status": "UP", "workspaceRoot": str(ROOT), "credentialsPresent": False})
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/run":
            self._json(404, {"error": "not_found"})
            return
        supplied = self.headers.get("X-Metatron-Sandbox-Token", "")
        if not hmac.compare_digest(TOKEN.encode(), supplied.encode()):
            self._json(403, {"error": "sandbox_authentication_failed"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > MAX_REQUEST:
                raise ValueError("invalid request size")
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
            result = run_process(payload)
            self._json(200, result)
        except PermissionError as exc:
            self._json(403, {"error": str(exc)})
        except (ValueError, json.JSONDecodeError) as exc:
            self._json(400, {"error": str(exc)})
        except Exception as exc:
            self._json(500, {"error": type(exc).__name__ + ":" + str(exc)[:300]})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8090), Handler).serve_forever()
