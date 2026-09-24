"""Live previews: run the app a task built so the founder can open it in a browser.

One preview at a time, started from the task's workspace as the task's own Unix user (no secrets in
its environment), stopped after MAX_AGE. Core's HTTP server proxies the preview host name to it.
"""
from __future__ import annotations

import json
import os
import signal
import socket
import subprocess
import threading
import time
from pathlib import Path

from .tools import SAFE_ENV_KEYS

PORT = 9100
MAX_AGE = 2 * 3600
READY_TIMEOUT = 300           # npm/pip installs can take a few minutes
CANDIDATE_PORTS = (PORT, 3000, 5000, 8000, 8080, 5173, 4173)  # apps that ignore $PORT


def detect(repo: Path) -> str | None:
    """The shell command that serves this repo, or None if it is not a runnable web app."""
    pkg = repo / "package.json"
    if pkg.is_file() and not pkg.is_symlink():
        try:
            data = json.loads(pkg.read_text(errors="replace"))
        except ValueError:
            data = {}
        install = "npm install --no-audit --no-fund --loglevel=error"
        if (data.get("scripts") or {}).get("start"):
            return f"{install} && npm start"
        if data.get("main"):
            return f"{install} && node {json.dumps(str(data['main']))}"
    for name in ("app.py", "main.py", "server.py"):
        if (repo / name).is_file():
            req = "pip install --user -q -r requirements.txt && " if (repo / "requirements.txt").is_file() else ""
            return f"{req}python3 {name}"
    for folder in (".", "public", "dist", "build", "docs"):
        if (repo / folder / "index.html").is_file():
            return f"cd {folder} && python3 -m http.server $PORT --bind 127.0.0.1"
    return None


def port_open(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


class Preview:
    def __init__(self, log_dir: Path, notify=None, port: int = PORT, ready_timeout: float = READY_TIMEOUT):
        self.log_dir = log_dir
        self.notify = notify or (lambda text: None)
        self.base_port = port
        self.ready_timeout = ready_timeout
        self.lock = threading.Lock()
        self.proc: subprocess.Popen | None = None
        self.ws = None
        self.task_id: int | None = None
        self.started = 0.0
        self.port: int | None = None      # where the app actually listens, once ready

    def running_task(self) -> int | None:
        return self.task_id if self.proc and self.proc.poll() is None else None

    def start(self, ws, task_id: int) -> str:
        """Start serving the task's repo in the background; the notify callback reports ready or failed."""
        repo = ws.dir / "repo"
        command = detect(repo) if repo.is_dir() else None
        if not command:
            return "no web app found (package.json, app.py/main.py or index.html)"
        self.stop()
        self.log_dir.mkdir(parents=True, exist_ok=True)
        log_path = self.log_dir / f"task-{task_id}.log"
        env = {k: os.environ[k] for k in SAFE_ENV_KEYS if k in os.environ}
        env.update(HOME=str(ws.dir), PORT=str(self.base_port), HOST="127.0.0.1", NODE_ENV="development")
        with self.lock:
            log = open(log_path, "w")
            self.proc = subprocess.Popen(["bash", "-lc", command], cwd=repo, env=env, stdout=log,
                                         stderr=subprocess.STDOUT, start_new_session=True, **ws._as_agent())
            log.close()
            self.ws, self.task_id, self.started, self.port = ws, task_id, time.time(), None
        threading.Thread(target=self._wait_ready, args=(self.proc, task_id, log_path), daemon=True).start()
        return "starting"

    def _wait_ready(self, proc, task_id: int, log_path: Path) -> None:
        deadline = time.time() + self.ready_timeout
        candidates = (self.base_port,) + tuple(p for p in CANDIDATE_PORTS if p != self.base_port)
        while time.time() < deadline and proc.poll() is None and self.proc is proc:
            for p in candidates:
                if port_open(p):
                    self.port = p
                    self.notify(f"🖥 Preview of task #{task_id} is ready.")
                    return
            time.sleep(2)
        if self.proc is not proc:
            return  # replaced by a newer preview
        tail = "\n".join(log_path.read_text(errors="replace").splitlines()[-15:])
        reason = "it exited" if proc.poll() is not None else "no port answered within 5 minutes"
        self.stop()
        self.notify(f"⚠️ Preview of task #{task_id} failed: {reason}.\n{tail[-1500:]}")

    def stop(self) -> bool:
        with self.lock:
            proc, ws = self.proc, self.ws
            self.proc = self.ws = self.task_id = self.port = None
        if not proc:
            return False
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        if ws is not None:
            ws._kill_agent_procs()
        proc.wait(timeout=10)
        return True

    def expire(self) -> None:
        if self.proc and time.time() - self.started > MAX_AGE:
            task_id = self.task_id
            self.stop()
            self.notify(f"🖥 Preview of task #{task_id} stopped after {MAX_AGE // 3600} hours.")
