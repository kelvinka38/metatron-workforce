"""Task store. One SQLite file (WAL). Replaces the 30+ JSON file stores of the old Workforce."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from contextlib import contextmanager

from . import workforce

SCHEMA = """
CREATE TABLE IF NOT EXISTS tasks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id     TEXT NOT NULL,
  request     TEXT NOT NULL,
  status      TEXT NOT NULL DEFAULT 'queued',   -- queued|running|cancelling|cancelled|done|failed|checking_ci|awaiting_approval|merged|superseded
  result      TEXT,
  pr_url      TEXT,
  repo        TEXT,
  steps       INTEGER NOT NULL DEFAULT 0,
  created_at  REAL NOT NULL,
  updated_at  REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS audit (
  id       INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id  INTEGER,
  at       REAL NOT NULL,
  kind     TEXT NOT NULL,     -- tool|llm|approval|error|info
  detail   TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS workers (      -- Workforce Core: persistent institutional identities
  id           TEXT PRIMARY KEY,
  name         TEXT NOT NULL,
  role         TEXT NOT NULL,
  purpose      TEXT NOT NULL,
  organization TEXT NOT NULL,
  reports_to   TEXT NOT NULL,
  capabilities TEXT NOT NULL,              -- JSON list
  capacity     INTEGER NOT NULL,           -- concurrent Work items
  status       TEXT NOT NULL,              -- active|suspended
  kind         TEXT NOT NULL               -- ai|human
);
CREATE TABLE IF NOT EXISTS assignments (  -- who is institutionally responsible for which task
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id     INTEGER NOT NULL,
  worker_id   TEXT NOT NULL,
  kind        TEXT NOT NULL,               -- owner|responsible
  capability  TEXT,
  state       TEXT NOT NULL,               -- active|released
  created_at  REAL NOT NULL,
  released_at REAL,
  outcome     TEXT
);
"""


CLOSED = ("done", "failed", "cancelled", "merged", "superseded")   # the Objective is over
IDLE = ("queued", "awaiting_approval")                              # no Work in progress


class Store:
    def __init__(self, path: str):
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(SCHEMA)
        for column in ("attempts INTEGER NOT NULL DEFAULT 0", "not_before REAL NOT NULL DEFAULT 0",
                       "owner_id TEXT", "assignee_id TEXT", "report TEXT"):
            try:  # added after the first deploy; existing databases get them here
                self._conn.execute(f"ALTER TABLE tasks ADD COLUMN {column}")
            except sqlite3.OperationalError:
                pass
        self._lock = threading.Lock()
        workforce.seed(self._conn)
        # Tasks from before Workforce Core: the Manager owns them too (history, not new work).
        now = time.time()
        for row in self._conn.execute("SELECT id, status FROM tasks WHERE owner_id IS NULL").fetchall():
            state = "released" if row["status"] in CLOSED else "active"
            self._conn.execute("UPDATE tasks SET owner_id=? WHERE id=?", (workforce.MANAGER, row["id"]))
            self._conn.execute("INSERT INTO assignments(task_id, worker_id, kind, state, created_at, released_at,"
                               " outcome) VALUES (?,?, 'owner', ?, ?, ?, ?)",
                               (row["id"], workforce.MANAGER, state, now, now if state == "released" else None,
                                row["status"] if state == "released" else None))
        # Crash recovery: anything left 'running' by a dead process goes back to the queue. Its Worker
        # keeps nothing reserved; the task is allocated again when it is claimed.
        self._conn.execute("UPDATE assignments SET state='released', released_at=?, outcome='interrupted' "
                           "WHERE state='active' AND kind='responsible' AND task_id IN "
                           "(SELECT id FROM tasks WHERE status IN ('running','cancelling'))", (time.time(),))
        self._conn.execute("UPDATE tasks SET status='queued' WHERE status='running'")
        self._conn.execute("UPDATE tasks SET status='cancelled' WHERE status='cancelling'")
        # A restart during the CI check: the PR exists, the founder decides.
        self._conn.execute("UPDATE tasks SET status='awaiting_approval' WHERE status='checking_ci'")

    @contextmanager
    def _tx(self):
        with self._lock:
            yield self._conn

    def create_task(self, chat_id: str, request: str) -> int:
        """Accept an Objective: it gets its one accountable owner, the Manager, in the same transaction."""
        now = time.time()
        with self._tx() as c:
            cur = c.execute("INSERT INTO tasks(chat_id, request, created_at, updated_at, owner_id) "
                            "VALUES (?,?,?,?,?)", (chat_id, request, now, now, workforce.MANAGER))
            c.execute("INSERT INTO assignments(task_id, worker_id, kind, state, created_at) "
                      "VALUES (?,?, 'owner', 'active', ?)", (cur.lastrowid, workforce.MANAGER, now))
            return cur.lastrowid

    def claim_next(self):
        """The oldest queued task that a Worker with the right capability has capacity for, now assigned
        to that Worker. Tasks nobody can take yet stay queued; later ones may go first."""
        with self._tx() as c:
            rows = c.execute("SELECT * FROM tasks WHERE status='queued' AND not_before <= ? ORDER BY id",
                             (time.time(),)).fetchall()
            if not rows:
                return None
            workers, load = self._workers(c), self._load(c)
            for row in rows:
                capability = workforce.required_capability(row["request"])
                worker = workforce.pick_worker(workers, load, capability)
                if not worker:
                    continue
                now = time.time()
                c.execute("UPDATE tasks SET status='running', assignee_id=?, updated_at=? WHERE id=?",
                          (worker["id"], now, row["id"]))
                c.execute("INSERT INTO assignments(task_id, worker_id, kind, capability, state, created_at) "
                          "VALUES (?,?, 'responsible', ?, 'active', ?)", (row["id"], worker["id"], capability, now))
                return dict(row, status="running", assignee_id=worker["id"])
            return None

    @staticmethod
    def _workers(c) -> list[dict]:
        return [dict(r, capabilities=json.loads(r["capabilities"]))
                for r in c.execute("SELECT * FROM workers ORDER BY rowid")]

    @staticmethod
    def _load(c) -> dict[str, int]:
        return {r["worker_id"]: r["n"] for r in c.execute(
            "SELECT worker_id, COUNT(*) AS n FROM assignments WHERE state='active' GROUP BY worker_id")}

    def workers(self) -> list[dict]:
        """Every Worker with its current load and record (tasks it was responsible for)."""
        with self._tx() as c:
            workers, load = self._workers(c), self._load(c)
            stats = {}
            for r in c.execute("SELECT a.worker_id, t.status, COUNT(DISTINCT t.id) AS n FROM assignments a "
                               "JOIN tasks t ON t.id = a.task_id GROUP BY a.worker_id, t.status"):
                stats.setdefault(r["worker_id"], {})[r["status"]] = r["n"]
            current = {}
            for r in c.execute("SELECT worker_id, task_id FROM assignments WHERE state='active' ORDER BY id"):
                current.setdefault(r["worker_id"], []).append(r["task_id"])
        for w in workers:
            w["load"] = load.get(w["id"], 0)
            w["record"] = stats.get(w["id"], {})
            w["current_tasks"] = current.get(w["id"], [])
        return workers

    def worker(self, worker_id: str | None) -> dict | None:
        return next((w for w in self.workers() if w["id"] == worker_id), None)

    def update(self, task_id: int, **fields):
        if not fields:
            return
        fields["updated_at"] = time.time()
        cols = ", ".join(f"{k}=?" for k in fields)
        status = fields.get("status")
        with self._tx() as c:
            c.execute(f"UPDATE tasks SET {cols} WHERE id=?", (*fields.values(), task_id))
            # Assignments follow the task: the responsible Worker's capacity is freed once its Work
            # stops (finished, paused for a retry, waiting on the founder); the owner's when it closes.
            kinds = ("owner", "responsible") if status in CLOSED else ("responsible",) if status in IDLE else ()
            for kind in kinds:
                c.execute("UPDATE assignments SET state='released', released_at=?, outcome=? "
                          "WHERE task_id=? AND kind=? AND state='active'", (time.time(), status, task_id, kind))

    def get(self, task_id: int):
        with self._tx() as c:
            row = c.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
            return dict(row) if row else None

    def recent(self, limit: int = 10):
        with self._tx() as c:
            return [dict(r) for r in c.execute("SELECT * FROM tasks ORDER BY id DESC LIMIT ?", (limit,))]

    def tasks_since(self, since: float):
        with self._tx() as c:
            return [dict(r) for r in c.execute("SELECT * FROM tasks WHERE created_at >= ? ORDER BY id DESC",
                                               (since,))]

    def llm_calls_since(self, since: float):
        """(task_id, 'provider:model') for every model reply recorded since the given time."""
        with self._tx() as c:
            rows = c.execute("SELECT task_id, detail FROM audit WHERE kind='llm' AND at >= ?", (since,)).fetchall()
        out = []
        for r in rows:
            detail = r["detail"]
            if detail.startswith("[") and "]" in detail:
                out.append((r["task_id"], detail[1:detail.index("]")]))
        return out

    def audit_tail(self, task_id: int, limit: int = 8):
        with self._tx() as c:
            rows = c.execute("SELECT at, kind, detail FROM audit WHERE task_id=? ORDER BY id DESC LIMIT ?",
                             (task_id, limit)).fetchall()
            return [dict(r) for r in reversed(rows)]

    def audit(self, task_id, kind: str, detail: str):
        with self._tx() as c:
            c.execute("INSERT INTO audit(task_id, at, kind, detail) VALUES (?,?,?,?)",
                      (task_id, time.time(), kind, detail[:4000]))
