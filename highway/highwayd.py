#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import queue
import signal
import sqlite3
import subprocess
import threading
import time
import uuid
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

DEFAULT_STATE_DIR = Path(os.environ.get("METATRON_HIGHWAY_STATE_DIR", "/var/lib/metatron-highway"))
DEFAULT_INSTALL_DIR = Path(os.environ.get("METATRON_HIGHWAY_INSTALL_DIR", "/opt/metatron/highway"))
DEFAULT_PORT = int(os.environ.get("METATRON_HIGHWAY_PORT", "18090"))
DEFAULT_EXECUTORS = int(os.environ.get("METATRON_HIGHWAY_EXECUTORS", "4"))
LEASE_SECONDS = int(os.environ.get("METATRON_HIGHWAY_LEASE_SECONDS", "30"))
HEARTBEAT_SECONDS = max(2, LEASE_SECONDS // 3)
POLL_SECONDS = float(os.environ.get("METATRON_HIGHWAY_POLL_SECONDS", "0.25"))
REPO = os.environ.get("GITHUB_REPOSITORY", "kelvinka38/metatron-workforce")
API = os.environ.get("GITHUB_API_URL", "https://api.github.com")


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_iso(value: str | None) -> datetime | None:
    if not value:
        return None
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def resource_names_conflict(a: str, b: str) -> bool:
    if a == b:
        return True
    return a.startswith(b + ":") or b.startswith(a + ":")


def resource_conflicts(candidate: dict[str, str], held: dict[str, str]) -> bool:
    for cname, cmode in candidate.items():
        for hname, hmode in held.items():
            if resource_names_conflict(cname, hname) and not (cmode == "READ" and hmode == "READ"):
                return True
    return False


class Registry:
    def __init__(self, path: Path):
        self.path = path
        self._mtime = 0.0
        self._data: dict[str, dict[str, Any]] = {}
        self.reload(force=True)

    def reload(self, force: bool = False) -> None:
        mtime = self.path.stat().st_mtime
        if not force and mtime <= self._mtime:
            return
        data = json.loads(self.path.read_text(encoding="utf-8"))
        tasks = data.get("tasks")
        if not isinstance(tasks, dict):
            raise RuntimeError("task registry requires object field tasks")
        for kind, spec in tasks.items():
            if not isinstance(spec.get("command"), list) or not spec["command"]:
                raise RuntimeError(f"task kind {kind} requires command array")
            for r in spec.get("resources", []):
                if r.get("mode") not in ("READ", "WRITE") or not str(r.get("name", "")).strip():
                    raise RuntimeError(f"invalid resource in task kind {kind}: {r}")
        self._data = tasks
        self._mtime = mtime

    def get(self, kind: str) -> dict[str, Any]:
        self.reload()
        spec = self._data.get(kind)
        if spec is None:
            raise KeyError(kind)
        return spec


class HighwayStore:
    def __init__(self, path: Path):
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self._init()

    def conn(self) -> sqlite3.Connection:
        c = sqlite3.connect(self.path, timeout=30, isolation_level=None)
        c.row_factory = sqlite3.Row
        c.execute("PRAGMA journal_mode=WAL")
        c.execute("PRAGMA synchronous=NORMAL")
        c.execute("PRAGMA busy_timeout=30000")
        return c

    def _init(self) -> None:
        with self.conn() as c:
            c.executescript(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                  task_id TEXT PRIMARY KEY,
                  kind TEXT NOT NULL,
                  name TEXT NOT NULL,
                  source_sha TEXT NOT NULL,
                  correlation_id TEXT NOT NULL,
                  priority INTEGER NOT NULL,
                  state TEXT NOT NULL,
                  attempt INTEGER NOT NULL,
                  max_attempts INTEGER NOT NULL,
                  timeout_seconds INTEGER NOT NULL,
                  env_json TEXT NOT NULL,
                  dependencies_json TEXT NOT NULL,
                  resources_json TEXT NOT NULL,
                  dedupe_key TEXT UNIQUE,
                  lease_owner TEXT,
                  lease_expires_at TEXT,
                  heartbeat_at TEXT,
                  created_at TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  started_at TEXT,
                  finished_at TEXT,
                  exit_code INTEGER,
                  failure TEXT NOT NULL DEFAULT '',
                  log_path TEXT NOT NULL DEFAULT ''
                );
                CREATE INDEX IF NOT EXISTS idx_tasks_sched
                  ON tasks(state, priority DESC, created_at ASC);
                CREATE TABLE IF NOT EXISTS resource_locks (
                  task_id TEXT NOT NULL,
                  resource_name TEXT NOT NULL,
                  mode TEXT NOT NULL,
                  acquired_at TEXT NOT NULL,
                  PRIMARY KEY(task_id, resource_name)
                );
                CREATE INDEX IF NOT EXISTS idx_resource_locks_name
                  ON resource_locks(resource_name);
                """
            )

    def create_task(self, payload: dict[str, Any], registry: Registry) -> dict[str, Any]:
        kind = str(payload.get("kind", "")).strip()
        spec = registry.get(kind)
        source_sha = str(payload.get("source_sha", "")).strip()
        if len(source_sha) != 40 or any(ch not in "0123456789abcdefABCDEF" for ch in source_sha):
            raise ValueError("source_sha must be a 40-character git SHA")
        resources: dict[str, str] = {}
        for r in spec.get("resources", []):
            name = str(r["name"]).replace("{source_sha}", source_sha)
            resources[name] = r["mode"]
        for r in payload.get("resources", []):
            name = str(r.get("name", "")).strip()
            mode = str(r.get("mode", "")).upper()
            if not name or mode not in ("READ", "WRITE"):
                raise ValueError("extra resources require name and READ/WRITE mode")
            if name in resources and resources[name] == "WRITE" and mode == "READ":
                raise ValueError("submission cannot weaken registry WRITE resource")
            resources[name] = "WRITE" if "WRITE" in (resources.get(name), mode) else "READ"

        task_id = str(payload.get("task_id") or f"hw-{uuid.uuid4().hex[:20]}")
        correlation_id = str(payload.get("correlation_id") or task_id)
        name = str(payload.get("name") or spec.get("name") or kind)
        priority = int(payload.get("priority", spec.get("priority", 50)))
        max_attempts = int(payload.get("max_attempts", spec.get("max_attempts", 1)))
        timeout_seconds = int(payload.get("timeout_seconds", spec.get("timeout_seconds", 600)))
        if max_attempts < 1 or timeout_seconds < 1:
            raise ValueError("max_attempts and timeout_seconds must be positive")
        env = {str(k): str(v) for k, v in dict(payload.get("env") or {}).items()}
        deps = [str(x) for x in payload.get("dependencies", [])]
        dedupe_key = payload.get("dedupe_key")
        created = now_iso()

        with self.conn() as c:
            try:
                c.execute(
                    """
                    INSERT INTO tasks(
                      task_id,kind,name,source_sha,correlation_id,priority,state,attempt,max_attempts,
                      timeout_seconds,env_json,dependencies_json,resources_json,dedupe_key,
                      created_at,updated_at,failure
                    ) VALUES(?,?,?,?,?,?,'QUEUED',0,?,?,?,?,?,?,?,?,'')
                    """,
                    (
                        task_id, kind, name, source_sha, correlation_id, priority, max_attempts,
                        timeout_seconds, json.dumps(env, sort_keys=True), json.dumps(deps),
                        json.dumps(resources, sort_keys=True), dedupe_key, created, created
                    )
                )
            except sqlite3.IntegrityError:
                if not dedupe_key:
                    raise
                row = c.execute("SELECT * FROM tasks WHERE dedupe_key=?", (dedupe_key,)).fetchone()
                return dict(row)
            row = c.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            return dict(row)

    def get_task(self, task_id: str) -> dict[str, Any] | None:
        with self.conn() as c:
            row = c.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            return dict(row) if row else None

    def list_tasks(self, limit: int = 100) -> list[dict[str, Any]]:
        with self.conn() as c:
            rows = c.execute(
                "SELECT * FROM tasks ORDER BY created_at DESC LIMIT ?", (max(1, min(limit, 500)),)
            ).fetchall()
            return [dict(r) for r in rows]

    def stats(self) -> dict[str, Any]:
        with self.conn() as c:
            rows = c.execute("SELECT state,COUNT(*) n FROM tasks GROUP BY state").fetchall()
            locks = c.execute("SELECT resource_name,mode,task_id FROM resource_locks ORDER BY resource_name").fetchall()
            return {
                "states": {r["state"]: r["n"] for r in rows},
                "locks": [dict(r) for r in locks],
            }

    def _dependencies_state(self, c: sqlite3.Connection, deps: list[str]) -> tuple[bool, bool]:
        if not deps:
            return True, False
        rows = c.execute(
            f"SELECT task_id,state FROM tasks WHERE task_id IN ({','.join('?' for _ in deps)})", deps
        ).fetchall()
        state = {r["task_id"]: r["state"] for r in rows}
        if any(d not in state for d in deps):
            return False, True
        terminal_failure = any(state[d] in ("FAILED", "DEAD_LETTERED", "BLOCKED", "CANCELLED") for d in deps)
        if terminal_failure:
            return False, True
        return all(state[d] == "SUCCEEDED" for d in deps), False

    def recover_expired(self) -> int:
        now = datetime.now(timezone.utc)
        recovered = 0
        with self.conn() as c:
            c.execute("BEGIN IMMEDIATE")
            rows = c.execute("SELECT task_id,lease_expires_at,attempt,max_attempts FROM tasks WHERE state='RUNNING'").fetchall()
            for r in rows:
                expiry = parse_iso(r["lease_expires_at"])
                if expiry and expiry <= now:
                    c.execute("DELETE FROM resource_locks WHERE task_id=?", (r["task_id"],))
                    next_state = "QUEUED" if r["attempt"] < r["max_attempts"] else "DEAD_LETTERED"
                    c.execute(
                        "UPDATE tasks SET state=?,lease_owner=NULL,lease_expires_at=NULL,heartbeat_at=NULL,"
                        "updated_at=?,failure=? WHERE task_id=?",
                        (next_state, now_iso(), "expired-executor-lease", r["task_id"])
                    )
                    recovered += 1
            c.execute("COMMIT")
        return recovered

    def claim_next(self, executor_id: str) -> dict[str, Any] | None:
        now = datetime.now(timezone.utc)
        lease_expiry = (now + timedelta(seconds=LEASE_SECONDS)).isoformat()
        with self.conn() as c:
            c.execute("BEGIN IMMEDIATE")
            rows = c.execute(
                "SELECT * FROM tasks WHERE state='QUEUED' ORDER BY priority DESC,created_at ASC LIMIT 200"
            ).fetchall()
            held_rows = c.execute("SELECT resource_name,mode FROM resource_locks").fetchall()
            held = {r["resource_name"]: r["mode"] for r in held_rows}
            for row in rows:
                task = dict(row)
                deps = json.loads(task["dependencies_json"])
                ready, blocked = self._dependencies_state(c, deps)
                if blocked:
                    c.execute(
                        "UPDATE tasks SET state='BLOCKED',updated_at=?,finished_at=?,failure=? WHERE task_id=?",
                        (now.isoformat(), now.isoformat(), "dependency-failed-or-missing", task["task_id"])
                    )
                    continue
                if not ready:
                    continue
                resources = json.loads(task["resources_json"])
                if resource_conflicts(resources, held):
                    continue
                attempt = task["attempt"] + 1
                c.execute(
                    """
                    UPDATE tasks SET state='RUNNING',attempt=?,lease_owner=?,lease_expires_at=?,
                      heartbeat_at=?,started_at=COALESCE(started_at,?),updated_at=?,failure=''
                    WHERE task_id=? AND state='QUEUED'
                    """,
                    (
                        attempt, executor_id, lease_expiry, now.isoformat(), now.isoformat(),
                        now.isoformat(), task["task_id"]
                    )
                )
                if c.total_changes == 0:
                    continue
                for name, mode in resources.items():
                    c.execute(
                        "INSERT INTO resource_locks(task_id,resource_name,mode,acquired_at) VALUES(?,?,?,?)",
                        (task["task_id"], name, mode, now.isoformat())
                    )
                c.execute("COMMIT")
                return self.get_task(task["task_id"])
            c.execute("COMMIT")
        return None

    def heartbeat(self, task_id: str, executor_id: str) -> bool:
        expiry = (datetime.now(timezone.utc) + timedelta(seconds=LEASE_SECONDS)).isoformat()
        now = now_iso()
        with self.conn() as c:
            cur = c.execute(
                "UPDATE tasks SET heartbeat_at=?,lease_expires_at=?,updated_at=? "
                "WHERE task_id=? AND state='RUNNING' AND lease_owner=?",
                (now, expiry, now, task_id, executor_id)
            )
            return cur.rowcount == 1

    def finish(self, task_id: str, executor_id: str, success: bool, exit_code: int,
               failure: str, log_path: str) -> dict[str, Any]:
        now = now_iso()
        with self.conn() as c:
            c.execute("BEGIN IMMEDIATE")
            row = c.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            if not row:
                c.execute("ROLLBACK")
                raise KeyError(task_id)
            if row["state"] != "RUNNING" or row["lease_owner"] != executor_id:
                c.execute("ROLLBACK")
                raise RuntimeError("executor lost task lease")
            if success:
                state = "SUCCEEDED"
            elif row["attempt"] < row["max_attempts"]:
                state = "QUEUED"
            else:
                state = "FAILED"
            c.execute("DELETE FROM resource_locks WHERE task_id=?", (task_id,))
            c.execute(
                """
                UPDATE tasks SET state=?,updated_at=?,finished_at=?,exit_code=?,failure=?,
                  log_path=?,lease_owner=NULL,lease_expires_at=NULL,heartbeat_at=NULL
                WHERE task_id=?
                """,
                (state, now, now if state != "QUEUED" else None, exit_code, failure, log_path, task_id)
            )
            c.execute("COMMIT")
        return self.get_task(task_id) or {}


class HighwayFabric:
    def __init__(self, store: HighwayStore, registry: Registry, state_dir: Path, install_dir: Path, executors: int):
        if executors < 1:
            raise ValueError("executors must be positive")
        self.store = store
        self.registry = registry
        self.state_dir = state_dir
        self.install_dir = install_dir
        self.executors = executors
        self.pool = ThreadPoolExecutor(max_workers=executors, thread_name_prefix="highway-executor")
        self.active: set[str] = set()
        self.active_lock = threading.Lock()
        self.stop_event = threading.Event()
        self.scheduler = threading.Thread(target=self._schedule_loop, name="highway-scheduler", daemon=True)

    def start(self) -> None:
        self.store.recover_expired()
        self.scheduler.start()

    def stop(self) -> None:
        self.stop_event.set()
        self.scheduler.join(timeout=5)
        self.pool.shutdown(wait=False, cancel_futures=True)

    def submit(self, payload: dict[str, Any]) -> dict[str, Any]:
        task = self.store.create_task(payload, self.registry)
        self._github_status(task, "pending", "queued")
        return self.public_task(task)

    def public_task(self, task: dict[str, Any]) -> dict[str, Any]:
        out = dict(task)
        for field in ("env_json", "dependencies_json", "resources_json"):
            if field in out:
                out[field[:-5] if field.endswith("_json") else field] = json.loads(out[field])
                del out[field]
        return out

    def _schedule_loop(self) -> None:
        while not self.stop_event.is_set():
            self.store.recover_expired()
            launched = False
            while not self.stop_event.is_set():
                with self.active_lock:
                    if len(self.active) >= self.executors:
                        break
                executor_id = f"executor-{uuid.uuid4().hex[:12]}"
                task = self.store.claim_next(executor_id)
                if not task:
                    break
                task_id = task["task_id"]
                with self.active_lock:
                    self.active.add(task_id)
                self.pool.submit(self._run_task, task, executor_id)
                launched = True
            self.stop_event.wait(POLL_SECONDS if not launched else 0.05)

    def _run_task(self, task: dict[str, Any], executor_id: str) -> None:
        task_id = task["task_id"]
        spec = self.registry.get(task["kind"])
        log_dir = self.state_dir / "logs"
        log_dir.mkdir(parents=True, exist_ok=True)
        log_path = log_dir / f"{task_id}.log"
        release = self.install_dir / "releases" / task["source_sha"]
        if not release.is_dir():
            self.store.finish(task_id, executor_id, False, 127, "release-snapshot-missing", str(log_path))
            with self.active_lock:
                self.active.discard(task_id)
            return

        env = os.environ.copy()
        env.update(json.loads(task["env_json"]))
        env.update({
            "HIGHWAY_TASK_ID": task_id,
            "HIGHWAY_TASK_KIND": task["kind"],
            "HIGHWAY_SOURCE_SHA": task["source_sha"],
            "HIGHWAY_CORRELATION_ID": task["correlation_id"],
            "HIGHWAY_INSTALL_DIR": str(self.install_dir),
            "HIGHWAY_STATE_DIR": str(self.state_dir),
            "GITHUB_REPOSITORY": env.get("GITHUB_REPOSITORY", REPO),
            "GITHUB_API_URL": env.get("GITHUB_API_URL", API),
        })
        command = [str(x).replace("{source_sha}", task["source_sha"]) for x in spec["command"]]
        heartbeat_stop = threading.Event()

        def beat() -> None:
            while not heartbeat_stop.wait(HEARTBEAT_SECONDS):
                if not self.store.heartbeat(task_id, executor_id):
                    return

        beat_thread = threading.Thread(target=beat, name=f"heartbeat-{task_id}", daemon=True)
        beat_thread.start()
        self._github_status(task, "pending", "running")
        success = False
        exit_code = 1
        failure = ""
        try:
            with log_path.open("ab", buffering=0) as log:
                header = (
                    f"HIGHWAY_TASK_START task_id={task_id} kind={task['kind']} source_sha={task['source_sha']} "
                    f"executor={executor_id} at={now_iso()}\n"
                ).encode()
                log.write(header)
                proc = subprocess.run(
                    command,
                    cwd=release,
                    env=env,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    timeout=task["timeout_seconds"],
                    check=False,
                )
                exit_code = proc.returncode
                success = exit_code == 0
                if not success:
                    failure = f"command-exit-{exit_code}"
        except subprocess.TimeoutExpired:
            exit_code = 124
            failure = "timeout"
        except Exception as exc:
            exit_code = 125
            failure = f"executor-error:{type(exc).__name__}:{exc}"
        finally:
            heartbeat_stop.set()
            beat_thread.join(timeout=2)

        final = self.store.finish(task_id, executor_id, success, exit_code, failure, str(log_path))
        if final["state"] == "SUCCEEDED":
            self._github_status(final, "success", "completed")
            self._fanout(final, spec)
        elif final["state"] == "QUEUED":
            self._github_status(final, "pending", "retry-queued")
        else:
            self._github_status(final, "failure", failure or "failed")
        with self.active_lock:
            self.active.discard(task_id)

    def _fanout(self, parent: dict[str, Any], spec: dict[str, Any]) -> None:
        for child_kind in spec.get("on_success", []):
            payload = {
                "kind": child_kind,
                "source_sha": parent["source_sha"],
                "correlation_id": parent["correlation_id"],
                "dependencies": [parent["task_id"]],
                "dedupe_key": f"{parent['task_id']}:{child_kind}",
                "env": {
                    "TARGET_SHA": parent["source_sha"],
                    "HIGHWAY_PARENT_TASK_ID": parent["task_id"],
                },
            }
            try:
                self.submit(payload)
            except Exception:
                pass

    def _github_status(self, task: dict[str, Any], state: str, description: str) -> None:
        token = os.environ.get("METATRON_GITHUB_TOKEN") or os.environ.get("GITHUB_TOKEN")
        sha = task.get("source_sha")
        if not token or not sha or len(sha) != 40:
            return
        body = json.dumps({
            "state": state,
            "context": f"metatron-highway/{task.get('kind','task')}",
            "description": description[:140],
        }).encode()
        req = urllib.request.Request(
            f"{API}/repos/{REPO}/statuses/{sha}",
            data=body,
            method="POST",
            headers={
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "Content-Type": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=10):
                pass
        except Exception:
            pass


class ApiHandler(BaseHTTPRequestHandler):
    fabric: HighwayFabric
    token: str

    def log_message(self, fmt: str, *args: Any) -> None:
        return

    def _authorized(self) -> bool:
        if self.path == "/health":
            return True
        return self.headers.get("Authorization", "") == f"Bearer {self.token}"

    def _json(self, status: int, value: Any) -> None:
        data = json.dumps(value, ensure_ascii=False, sort_keys=True).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        if not self._authorized():
            self._json(401, {"error": "unauthorized"})
            return
        if self.path == "/health":
            self._json(200, {
                "status": "UP",
                "executors": self.fabric.executors,
                "active": len(self.fabric.active),
                "scheduler": "persistent",
            })
            return
        if self.path == "/stats":
            self._json(200, self.fabric.store.stats())
            return
        if self.path.startswith("/tasks/"):
            task_id = self.path.split("/", 2)[2]
            task = self.fabric.store.get_task(task_id)
            if not task:
                self._json(404, {"error": "not_found"})
            else:
                self._json(200, self.fabric.public_task(task))
            return
        if self.path.startswith("/tasks"):
            self._json(200, [self.fabric.public_task(x) for x in self.fabric.store.list_tasks()])
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:
        if not self._authorized():
            self._json(401, {"error": "unauthorized"})
            return
        if self.path != "/tasks":
            self._json(404, {"error": "not_found"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size < 1 or size > 1024 * 1024:
                raise ValueError("invalid request size")
            payload = json.loads(self.rfile.read(size))
            task = self.fabric.submit(payload)
            self._json(202, task)
        except KeyError as exc:
            self._json(400, {"error": f"unknown_task_kind:{exc.args[0]}"})
        except (ValueError, TypeError, json.JSONDecodeError) as exc:
            self._json(400, {"error": str(exc)})
        except Exception as exc:
            self._json(500, {"error": f"{type(exc).__name__}:{exc}"})


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--state-dir", default=str(DEFAULT_STATE_DIR))
    parser.add_argument("--install-dir", default=str(DEFAULT_INSTALL_DIR))
    parser.add_argument("--port", type=int, default=DEFAULT_PORT)
    parser.add_argument("--executors", type=int, default=DEFAULT_EXECUTORS)
    args = parser.parse_args()

    token = os.environ.get("METATRON_HIGHWAY_TOKEN", "").strip()
    if not token:
        raise SystemExit("METATRON_HIGHWAY_TOKEN is required")
    state_dir = Path(args.state_dir)
    install_dir = Path(args.install_dir)
    registry = Registry(install_dir / "current" / "task-registry.json")
    store = HighwayStore(state_dir / "highway.db")
    fabric = HighwayFabric(store, registry, state_dir, install_dir, args.executors)
    fabric.start()

    ApiHandler.fabric = fabric
    ApiHandler.token = token
    server = ThreadingHTTPServer(("127.0.0.1", args.port), ApiHandler)

    stopping = threading.Event()

    def stop_handler(signum: int, frame: Any) -> None:
        if stopping.is_set():
            return
        stopping.set()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop_handler)
    signal.signal(signal.SIGINT, stop_handler)
    try:
        server.serve_forever(poll_interval=0.25)
    finally:
        fabric.stop()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
