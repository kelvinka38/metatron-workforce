import json
import sqlite3
import unittest
from pathlib import Path

TARGET = "e18cb73d1cd11d0d2c870ac648ae87937527f3a5"
REQUIRED = {
    "workforce-build",
    "workforce-deploy",
    "production-highway-fast",
    "work-observability",
    "control-room-fast",
    "runtime-closure",
}


class RuntimeClosureDiagnostic(unittest.TestCase):
    def test_exact_sha_highway_release_is_terminal_green(self):
        state = Path.home() / ".metatron" / "highway" / "state"
        dbs = list(state.glob("*.db")) + list(state.glob("*.sqlite*"))
        selected = None
        for db in dbs:
            try:
                with sqlite3.connect(db) as conn:
                    tables = {r[0] for r in conn.execute("select name from sqlite_master where type='table'")}
                    if "tasks" in tables:
                        selected = db
                        break
            except sqlite3.Error:
                pass
        self.assertIsNotNone(selected, f"Highway task DB not found under {state}; candidates={dbs}")
        with sqlite3.connect(selected) as conn:
            conn.row_factory = sqlite3.Row
            rows = [dict(r) for r in conn.execute(
                "select task_id,kind,state,failure,exit_code,created_at,started_at,finished_at,env_json,dependencies_json "
                "from tasks where source_sha=? order by created_at asc", (TARGET,)
            ).fetchall()]
        print("HIGHWAY_DIAG_DB=" + str(selected))
        print("HIGHWAY_DIAG_ROWS=" + json.dumps(rows, sort_keys=True))
        latest = {}
        for row in rows:
            latest[row["kind"]] = row
        missing = REQUIRED - set(latest)
        self.assertFalse(missing, f"missing required exact-SHA tasks: {sorted(missing)}")
        failed = {k: (latest[k]["state"], latest[k]["failure"], latest[k]["exit_code"])
                  for k in REQUIRED if latest[k]["state"] != "SUCCEEDED"}
        self.assertFalse(failed, f"exact-SHA Highway closure not green: {failed}")
        print("HIGHWAY_EXACT_SHA_TERMINAL_GREEN=PASS")


if __name__ == "__main__":
    unittest.main()
