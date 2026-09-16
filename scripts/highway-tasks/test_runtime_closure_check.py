"""Tests for runtime_closure_check.py -- proves the fanout dispatch spread fix without needing a
real Highway daemon or Docker: the get()/run() dependencies are injected fakes.

Uses only the standard library (unittest); no pytest convention exists yet in this repository.
Run with: python3 -m unittest scripts.highway-tasks.test_runtime_closure_check -v
(or simply `python3 scripts/highway-tasks/test_runtime_closure_check.py`)
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import runtime_closure_check as rcc  # noqa: E402

TARGET = "f2110b5f7cf582f733259f200953ea7017f5c857"
PARENT_ID = "hw-45c70c02bc4a4eae8216"
BUILD_ID = "hw-80eea521dac34bf38599"


def _task(task_id, kind, state, source_sha, started_at, finished_at, dependencies=None, failure=""):
    return {
        "task_id": task_id, "kind": kind, "state": state, "source_sha": source_sha,
        "started_at": started_at, "finished_at": finished_at,
        "dependencies": dependencies or [], "failure": failure,
        "env": {"HIGHWAY_PARENT_TASK_ID": PARENT_ID},
    }


def _fake_get(all_tasks):
    tasks_by_id = {t["task_id"]: t for t in all_tasks}

    def get(path):
        if path == "/health":
            return {"status": "UP", "executors": 4}
        if path == "/tasks":
            return all_tasks
        task_id = path.rsplit("/", 1)[-1]
        return tasks_by_id[task_id]
    return get


def _fake_run(live_sha, health_state):
    def run(args, text=True):
        if args[:2] == ["docker", "ps"]:
            return "container123\n"
        fmt = args[args.index("--format") + 1]
        if "Health" in fmt:
            return health_state + "\n"
        return f"METATRON_COMMIT_SHA={live_sha}\n"
    return run


def _build_and_deploy(deploy_finished_at):
    build = _task(BUILD_ID, "workforce-build", "SUCCEEDED", TARGET,
                  "2026-09-16T00:32:29.036669+00:00", "2026-09-16T00:33:03.275845+00:00")
    parent = _task(PARENT_ID, "workforce-deploy", "SUCCEEDED", TARGET,
                   "2026-09-16T00:33:04.115068+00:00", deploy_finished_at,
                   dependencies=[BUILD_ID])
    return build, parent


class RuntimeClosureCheckTest(unittest.TestCase):
    def test_elevated_spread_reproducing_incident_does_not_fail_closure(self):
        # Reproduces hw-e76b9860d3d740bc8f2a: 2.14s spread, a hard failure under the old 2.0s bound.
        # dispatch_lag is kept comfortably under its own (untouched) 2.0s bound so this test isolates
        # the spread behavior specifically.
        build, parent = _build_and_deploy("2026-09-16T00:33:12.200000+00:00")
        wo = _task("hw-a", "work-observability", "SUCCEEDED", TARGET,
                   "2026-09-16T00:33:12.000000+00:00", "2026-09-16T00:33:13.500000+00:00")
        crf = _task("hw-b", "control-room-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:13.000000+00:00", "2026-09-16T00:33:14.500000+00:00")
        phf = _task("hw-c", "production-highway-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:14.139781+00:00", "2026-09-16T00:33:15.500000+00:00")
        get = _fake_get([build, parent, wo, crf, phf])
        run = _fake_run(TARGET, "healthy")

        lines = rcc.run_closure(get, TARGET, PARENT_ID, run=run)
        joined = "\n".join(lines)

        self.assertIn("HIGHWAY_RUNTIME_CLOSURE=PASS", joined)
        self.assertIn("HIGHWAY_LIVE_EXACT_SHA=PASS", joined)
        self.assertIn("HIGHWAY_RUNTIME_CLOSURE_WARNING=fanout dispatch spread elevated", joined)
        self.assertIn("HIGHWAY_RUNTIME_CLOSURE_START_SPREAD_SECONDS=2.140", joined)

    def test_actual_child_task_failure_still_fails_closure(self):
        build, parent = _build_and_deploy("2026-09-16T00:33:11.000000+00:00")
        wo = _task("hw-a", "work-observability", "FAILED", TARGET,
                   "2026-09-16T00:33:12.000000+00:00", "2026-09-16T00:33:12.500000+00:00", failure="boom")
        crf = _task("hw-b", "control-room-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:12.100000+00:00", "2026-09-16T00:33:14.500000+00:00")
        phf = _task("hw-c", "production-highway-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:12.200000+00:00", "2026-09-16T00:33:15.500000+00:00")
        get = _fake_get([build, parent, wo, crf, phf])
        run = _fake_run(TARGET, "healthy")

        with self.assertRaisesRegex(AssertionError, "fanout failure"):
            rcc.run_closure(get, TARGET, PARENT_ID, run=run)

    def test_sha_mismatch_still_fails_closure_even_with_normal_timing(self):
        build, parent = _build_and_deploy("2026-09-16T00:33:12.200000+00:00")
        wo = _task("hw-a", "work-observability", "SUCCEEDED", TARGET,
                   "2026-09-16T00:33:12.000000+00:00", "2026-09-16T00:33:13.500000+00:00")
        crf = _task("hw-b", "control-room-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:12.100000+00:00", "2026-09-16T00:33:14.500000+00:00")
        phf = _task("hw-c", "production-highway-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:12.200000+00:00", "2026-09-16T00:33:15.500000+00:00")
        get = _fake_get([build, parent, wo, crf, phf])
        run = _fake_run("some-other-sha-entirely", "healthy")  # live container running the wrong SHA

        with self.assertRaises(AssertionError):
            rcc.run_closure(get, TARGET, PARENT_ID, run=run)

    def test_health_mismatch_still_fails_closure_even_with_normal_timing(self):
        build, parent = _build_and_deploy("2026-09-16T00:33:12.200000+00:00")
        wo = _task("hw-a", "work-observability", "SUCCEEDED", TARGET,
                   "2026-09-16T00:33:12.000000+00:00", "2026-09-16T00:33:13.500000+00:00")
        crf = _task("hw-b", "control-room-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:12.100000+00:00", "2026-09-16T00:33:14.500000+00:00")
        phf = _task("hw-c", "production-highway-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:12.200000+00:00", "2026-09-16T00:33:15.500000+00:00")
        get = _fake_get([build, parent, wo, crf, phf])
        run = _fake_run(TARGET, "unhealthy")

        with self.assertRaises(AssertionError):
            rcc.run_closure(get, TARGET, PARENT_ID, run=run)

    def test_spread_at_or_above_new_hard_cap_still_fails_closure(self):
        # Proves the raised threshold still catches a genuine defect (fanout effectively
        # serialized), rather than simply removing the check.
        build, parent = _build_and_deploy("2026-09-16T00:33:11.000000+00:00")
        wo = _task("hw-a", "work-observability", "SUCCEEDED", TARGET,
                   "2026-09-16T00:33:12.000000+00:00", "2026-09-16T00:33:13.500000+00:00")
        crf = _task("hw-b", "control-room-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:14.000000+00:00", "2026-09-16T00:33:16.500000+00:00")
        phf = _task("hw-c", "production-highway-fast", "SUCCEEDED", TARGET,
                    "2026-09-16T00:33:18.000000+00:00", "2026-09-16T00:33:20.500000+00:00")
        get = _fake_get([build, parent, wo, crf, phf])
        run = _fake_run(TARGET, "healthy")

        with self.assertRaisesRegex(AssertionError, "fanout dispatch spread too large"):
            rcc.run_closure(get, TARGET, PARENT_ID, run=run)


if __name__ == "__main__":
    unittest.main()
