import io
import json
import os
import subprocess
import tempfile
import time
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

from metatron_core.agent import Agent, invalid_reply_hint, parse_action
from metatron_core.llm import (FOREVER, EmptyReply, Gemini, LlmUnavailable, Provider, ProviderChain,
                                cooldown_for, gemini_text, pick_flash_model)
from metatron_core.store import Store
from metatron_core.tools import Workspace, agent_uid_for


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


# -B: no .pyc, or a same-size edit within the same second reuses stale bytecode.
CHECK = "python3 -B -c 'import calc; assert calc.add(2,3)==5'"


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


class FailingProvider(Provider):
    def __init__(self, name, code, body=b"", headers=None):
        super().__init__(name)
        self.code, self.body, self.headers = code, body, headers or {}

    def complete(self, messages, max_tokens):
        raise urllib.error.HTTPError("u", self.code, "x", self.headers, io.BytesIO(self.body))


class Cooldowns(unittest.TestCase):
    def test_bad_request_is_not_permanent(self):
        self.assertEqual(cooldown_for(400, "Request payload size exceeds the limit", None), 120)

    def test_account_errors_disable_for_good(self):
        self.assertEqual(cooldown_for(400, '{"reason": "API_KEY_INVALID"}', None), FOREVER)
        self.assertEqual(cooldown_for(403, "", None), FOREVER)

    def test_rate_limits_are_short_unless_daily(self):
        self.assertEqual(cooldown_for(429, "GenerateRequestsPerMinute", None), 60)
        self.assertEqual(cooldown_for(429, "GenerateRequestsPerDayPerProject", None), 3600)
        self.assertEqual(cooldown_for(429, "", "17"), 17)

    def test_chain_falls_through_and_reports(self):
        chain = ProviderChain([FailingProvider("gemini", 400, b"too large")])
        with self.assertRaises(LlmUnavailable) as ctx:
            chain.complete([])
        self.assertIn("HTTP 400 too large", str(ctx.exception))
        self.assertLess(chain.providers[0].cooldown_until, time.time() + 1000)


class Confinement(unittest.TestCase):
    def test_sibling_workspace_blocked(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "task-10").mkdir()
            (Path(d) / "task-10" / "secret.txt").write_text("other task")
            ws = Workspace(Path(d), 1, github_token="")
            with self.assertRaises(ValueError):
                ws.read_file("../task-10/secret.txt")

    def test_no_agent_user_outside_root(self):
        with mock.patch.dict(os.environ, {"CORE_AGENT_UID_BASE": "20000"}):
            expected = 20007 if os.geteuid() == 0 else None
            self.assertEqual(agent_uid_for(7), expected)


def _git(cwd, *args):
    subprocess.run(["git", *args], cwd=cwd, check=True, capture_output=True)


class Publish(unittest.TestCase):
    def _workspace(self, d):
        ws = Workspace(Path(d) / "work", 5, github_token="")
        repo = ws.dir / "repo"
        repo.mkdir()
        _git(repo, "init", "-q", "-b", "main")
        _git(repo, "config", "user.email", "t@t")
        _git(repo, "config", "user.name", "t")
        (repo / "f.txt").write_text("a\n")
        _git(repo, "add", "f.txt")
        _git(repo, "commit", "-qm", "init")
        upstream = Path(d) / "remote" / "o" / "r.git"
        _git(Path(d), "init", "-q", "--bare", str(upstream))
        ws.remote_base = f"file://{Path(d) / 'remote'}"
        ws.repo, ws.base_branch = "o/r", "main"
        ws.base_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True,
                                     capture_output=True, text=True).stdout.strip()
        return ws, upstream

    def test_agent_committed_work_is_published(self):
        with tempfile.TemporaryDirectory() as d:
            ws, upstream = self._workspace(d)
            ws.run("echo b >> f.txt && git commit -qam 'agent commit'")
            self.assertEqual(ws.publish_branch("t"), "metatron/task-5")
            log = subprocess.run(["git", "--git-dir", str(upstream), "log", "--format=%s", "metatron/task-5"],
                                 check=True, capture_output=True, text=True).stdout
            self.assertIn("agent commit", log)

    def test_agent_hooks_do_not_run_on_publish(self):
        with tempfile.TemporaryDirectory() as d:
            ws, _ = self._workspace(d)
            ws.run("mkdir -p h && printf '#!/bin/sh\\ntouch ../pwned\\n' > h/pre-push && chmod +x h/pre-push "
                   "&& git config core.hooksPath h && echo c >> f.txt")
            ws.publish_branch("t")
            self.assertFalse((ws.dir / "pwned").exists())

    def test_nothing_to_publish(self):
        with tempfile.TemporaryDirectory() as d:
            ws, _ = self._workspace(d)
            with self.assertRaises(RuntimeError):
                ws.publish_branch("t")


@unittest.skipUnless(os.geteuid() == 0, "agent-user isolation needs root (CI runs this with sudo)")
class AgentUser(unittest.TestCase):
    UID = 65534  # nobody

    def test_agent_cannot_read_core_env_or_data(self):
        core = subprocess.Popen(["sleep", "30"], env={"GEMINI_API_KEY": "S3CRET"})  # stands in for Core
        try:
            with tempfile.TemporaryDirectory() as d:
                os.chmod(d, 0o711)
                Path(d, "core.db").write_text("S3CRET")
                os.chmod(Path(d, "core.db"), 0o600)
                ws = Workspace(Path(d) / "work", 1, github_token="", agent_uid=self.UID)
                out = ws.run(f"id -u; cat /proc/{core.pid}/environ; cat {d}/core.db")
                self.assertIn(f"exit=1\n--- stdout ---\n{self.UID}", out)
                self.assertNotIn("S3CRET", out)
        finally:
            core.kill()
            core.wait()

    def test_file_tools_run_as_agent(self):
        with tempfile.TemporaryDirectory() as d:
            os.chmod(d, 0o711)
            Path(d, "core.db").write_text("S3CRET")
            os.chmod(Path(d, "core.db"), 0o600)
            ws = Workspace(Path(d) / "work", 1, github_token="", agent_uid=self.UID)
            self.assertIn("wrote", ws.write_file("a.txt", "hi"))
            self.assertEqual(ws.read_file("a.txt"), "hi")
            self.assertEqual((ws.dir / "a.txt").stat().st_uid, self.UID)
            ws.run(f"ln -s {d}/core.db link")
            self.assertRaises(ValueError, ws.read_file, "link")


# M1-7: replies free-tier models really send. Each parses to the expected tool, or None = rejected cleanly.
EDGE_CASES = [
    ('{"thought":"x","tool":"run","args":{"command":"ls"}}', "run"),
    ('```json\n{"tool":"finish","args":{}}\n```', "finish"),
    ('```\n{"tool":"finish","args":{}}\n```', "finish"),
    ('Sure! Here is my next step:\n{"tool":"read_file","args":{"path":"a"}}\nLet me know.', "read_file"),
    ('{"tool":"run","args":"{\\"command\\": \\"ls\\"}"}', "run"),
    ('{"tool":"finish"}', "finish"),
    ('{"tool":"finish","args":null}', "finish"),
    ('{"tool":" run ","args":{"command":"ls"}}', "run"),
    ('{"thought":"plan"}\n{"tool":"run","args":{"command":"ls"}}', "run"),
    ('{"tool":"write_file","args":{"path":"a.py","content":"def f():\\n    return {1: 2}"}}', "write_file"),
    ('I think {curly} braces are fine. {"tool":"list_dir","args":{"path":"repo"}}', "list_dir"),
    ('{"tool":"run","args":{"command":"ls"}} {"tool":"finish","args":{}}', "run"),
    ('{"tool":"write_file","args":{"path":"a.py","content":"def f():\\n  retu', None),
    ('', None),
    ('   \n  ', None),
    ('I will now run the tests.', None),
    ('{"thought":"no tool here"}', None),
    ('{"tool":"","args":{}}', None),
    ('{"tool":"run","args":["ls"]}', None),
    ('{"tool":"run","args":"not json"}', None),
]


class ReplyParsing(unittest.TestCase):
    def test_twenty_edge_cases(self):
        self.assertEqual(len(EDGE_CASES), 20)
        for reply, tool in EDGE_CASES:
            with self.subTest(reply=reply):
                if tool is None:
                    self.assertRaises(ValueError, parse_action, reply)
                else:
                    action = parse_action(reply)
                    self.assertEqual(action["tool"], tool)
                    self.assertIsInstance(action["args"], dict)

    def test_truncated_reply_gets_a_useful_hint(self):
        self.assertIn("cut off", invalid_reply_hint('{"tool":"write_file","args":{"content":"abc'))
        self.assertIn("empty", invalid_reply_hint(""))

    def test_invalid_count_resets_after_a_good_reply(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 6, github_token="")
            llm = ScriptedLlm(["bad", "bad", act("list_dir", path="."), "bad", "bad",
                               act("finish", summary="ok", open_pr=False)])
            out = Agent(llm, audit=lambda *a: None).run("x", ws)
            self.assertEqual(out["summary"], "ok")


class GeminiReplies(unittest.TestCase):
    def test_safety_block_and_empty_text_raise_empty_reply(self):
        self.assertRaises(EmptyReply, gemini_text, {"promptFeedback": {"blockReason": "SAFETY"}})
        self.assertRaises(EmptyReply, gemini_text, {"candidates": [{"finishReason": "SAFETY"}]})
        self.assertRaises(EmptyReply, gemini_text,
                          {"candidates": [{"content": {"parts": [{"text": "hmm", "thought": True}]}}]})

    def test_thought_parts_are_dropped(self):
        data = {"candidates": [{"content": {"parts": [{"text": "thinking", "thought": True},
                                                      {"text": '{"tool":"finish"}'}]}}]}
        self.assertEqual(gemini_text(data), '{"tool":"finish"}')

    def test_empty_reply_moves_on_without_cooldown(self):
        class Blocked(Provider):
            def complete(self, messages, max_tokens):
                raise EmptyReply("blocked")

        class Ok(Provider):
            def complete(self, messages, max_tokens):
                return "fine"

        chain = ProviderChain([Blocked("gemini"), Ok("ollama")])
        self.assertEqual(chain.complete([]), "fine")
        self.assertTrue(chain.providers[0].available())


def _model(name, *methods):
    return {"name": f"models/{name}", "supportedGenerationMethods": list(methods or ["generateContent"])}


class GeminiModelChoice(unittest.TestCase):
    def test_newest_stable_flash_wins(self):
        models = [_model("gemini-2.5-flash"), _model("gemini-3.5-flash"), _model("gemini-3-flash"),
                  _model("gemini-4-flash-preview-06"), _model("gemini-3.5-flash-lite"),
                  _model("gemini-9-flash", "embedContent"), _model("gemini-3.5-pro")]
        self.assertEqual(pick_flash_model(models), "gemini-3.5-flash")

    def test_alias_when_no_stable_flash(self):
        self.assertEqual(pick_flash_model([_model("gemini-flash-latest"), _model("gemini-3-pro")]),
                         "gemini-flash-latest")
        self.assertEqual(pick_flash_model([_model("gemini-3-pro")]), "")

    def test_retired_model_switches_once(self):
        g = Gemini("gemini", key="k", model="gemini-2.5-flash")
        calls = []

        def generate(messages, max_tokens):
            calls.append(g.model)
            if g.model == "gemini-2.5-flash":
                raise urllib.error.HTTPError("u", 404, "Not Found", {}, io.BytesIO(b""))
            return "OK"

        with mock.patch.object(g, "_generate", side_effect=generate), \
                mock.patch.object(g, "_list_models", return_value=[_model("gemini-3-flash")]):
            self.assertEqual(g.complete([], 10), "OK")
        self.assertEqual(calls, ["gemini-2.5-flash", "gemini-3-flash"])
        self.assertEqual(g.model, "gemini-3-flash")


if __name__ == "__main__":
    unittest.main()
