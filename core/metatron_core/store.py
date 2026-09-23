"""Task store. One SQLite file (WAL). Replaces the 30+ JSON file stores of the old Workforce."""
from __future__ import annotations

import sqlite3
import threading
import time
from contextlib import contextmanager

SCHEMA = """
CREATE TABLE IF NOT EXISTS tasks (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  chat_id     TEXT NOT NULL,
  request     TEXT NOT NULL,
  status      TEXT NOT NULL DEFAULT 'queued',   -- queued|running|done|failed|awaiting_approval|merged
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
"""


class Store:
    def __init__(self, path: str):
        self._conn = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._conn.executescript(SCHEMA)
        self._lock = threading.Lock()
        # Crash recovery: anything left 'running' by a dead process goes back to the queue.
        self._conn.execute("UPDATE tasks SET status='queued' WHERE status='running'")

    @contextmanager
    def _tx(self):
        with self._lock:
            yield self._conn

    def create_task(self, chat_id: str, request: str) -> int:
        now = time.time()
        with self._tx() as c:
            cur = c.execute("INSERT INTO tasks(chat_id, request, created_at, updated_at) VALUES (?,?,?,?)",
                            (chat_id, request, now, now))
            return cur.lastrowid

    def claim_next(self):
        with self._tx() as c:
            row = c.execute("SELECT * FROM tasks WHERE status='queued' ORDER BY id LIMIT 1").fetchone()
            if row:
                c.execute("UPDATE tasks SET status='running', updated_at=? WHERE id=?", (time.time(), row["id"]))
            return dict(row) if row else None

    def update(self, task_id: int, **fields):
        if not fields:
            return
        fields["updated_at"] = time.time()
        cols = ", ".join(f"{k}=?" for k in fields)
        with self._tx() as c:
            c.execute(f"UPDATE tasks SET {cols} WHERE id=?", (*fields.values(), task_id))

    def get(self, task_id: int):
        with self._tx() as c:
            row = c.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
            return dict(row) if row else None

    def recent(self, limit: int = 10):
        with self._tx() as c:
            return [dict(r) for r in c.execute("SELECT * FROM tasks ORDER BY id DESC LIMIT ?", (limit,))]

    def audit(self, task_id, kind: str, detail: str):
        with self._tx() as c:
            c.execute("INSERT INTO audit(task_id, at, kind, detail) VALUES (?,?,?,?)",
                      (task_id, time.time(), kind, detail[:4000]))
