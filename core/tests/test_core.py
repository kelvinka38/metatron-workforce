import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from metatron_core.agent import Agent, parse_action
from metatron_core.llm import ProviderChain
from metatron_core.store import Store
from metatron_core.tools import Workspace


class ScriptedLlm:
    """Plays back a fixed list of replies; records what the agent showed it."""

    def __init__(self, replies):
        self.replies = list(replies)
        self.seen = []
        self.last_used = "scripted"

    def complete(self, messages, max_tokens=4096):
        self.seen.append(messages[-1].content)
        return self.replies.pop(0)


def act(tool, **args):
    return json.dumps({"thought": "", "tool": tool, "args": args})


CHECK = "python3 -c 'import calc; assert calc.add(2,3)==5'"


class ZeroCostRule(unittest.TestCase):
    def test_paid_keys_ignored_by_default(self):
        env = {"GEMINI_API_KEY": "g", "ANTHROPIC_API_KEY": "a", "OPENAI_API_KEY": "o"}
        with mock.patch.dict(os.environ, env, clear=True):
            names = [p.name for p in ProviderChain.from_env().providers]
        self.assertEqual(names, ["gemini", "ollama"])

    def test_openrouter_non_free_model_refused(self):
        env = {"OPENROUTER_FREE_API_KEY": "k", "OPENROUTER_FREE_MODEL": "openai/gpt-4o"}
        with mock.patch.dict(os.environ, env, clear=True):
            with self.assertRaises(ValueError):
                ProviderChain.from_env()


class AgentLoop(unittest.TestCase):
    def test_agent_sees_failure_fixes_and_finishes(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 1, github_token="")
            llm = ScriptedLlm([
                act("write_file", path="calc.py", content="def add(a, b):\n    return a - b\n"),
                act("run", command=CHECK),
                act("replace_in_file", path="calc.py", old="a - b", new="a + b"),
                act("run", command=CHECK),
                act("finish", summary="fixed add()", open_pr=False),
            ])
            out = Agent(llm, audit=lambda *a: None).run("fix add", ws)
            self.assertEqual(out["summary"], "fixed add()")
            self.assertIn("exit=1", llm.seen[2])   # agent saw the failing check
            self.assertIn("exit=0", llm.seen[4])   # and the passing one after the fix

    def test_invalid_replies_stop_cleanly(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 2, github_token="")
            out = Agent(ScriptedLlm(["hi", "hello", "nope"]), audit=lambda *a: None).run("x", ws)
            self.assertTrue(out["failed"])

    def test_parse_action_tolerates_fences(self):
        self.assertEqual(parse_action('```json\n{"tool":"finish","args":{}}\n```')["tool"], "finish")


class Safety(unittest.TestCase):
    def test_path_escape_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 3, github_token="")
            with self.assertRaises(ValueError):
                ws.read_file("../../etc/passwd")

    def test_shell_has_no_secrets(self):
        with tempfile.TemporaryDirectory() as d, mock.patch.dict(os.environ, {"GEMINI_API_KEY": "S3CRET"}):
            out = Workspace(Path(d), 4, github_token="GHTOKEN123").run("env")
            self.assertNotIn("S3CRET", out)
            self.assertNotIn("GHTOKEN123", out)


class StoreRecovery(unittest.TestCase):
    def test_running_tasks_requeued_after_crash(self):
        with tempfile.TemporaryDirectory() as d:
            s = Store(f"{d}/t.db")
            tid = s.create_task("c", "do x")
            s.claim_next()
            self.assertEqual(Store(f"{d}/t.db").get(tid)["status"], "queued")


if __name__ == "__main__":
    unittest.main()
