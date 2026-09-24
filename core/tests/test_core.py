import io
import json
import os
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

from metatron_core.agent import Agent, invalid_reply_hint, parse_action
from metatron_core.llm import (FOREVER, EmptyReply, Gemini, LlmUnavailable, Message, Ollama, OpenRouterFree, Provider,
                                ProviderChain, cooldown_for, flash_candidates, gemini_text,
                                openrouter_free_candidates, pick_flash_model)
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
        chain = ProviderChain([FailingProvider("gemini", 400, b"too large")], fallback_wait=0)
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

        with mock.patch.object(g, "_generate", side_effect=generate), mock.patch("builtins.print"), \
                mock.patch.object(g, "_list_models", return_value=[_model("gemini-3-flash")]):
            self.assertEqual(g.complete([], 10), "OK")
        self.assertEqual(calls, ["gemini-2.5-flash", "gemini-3-flash"])
        self.assertEqual(g.model, "gemini-3-flash")


class TelegramPolling(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        env = {"CORE_DATA_DIR": self.tmp.name, "TELEGRAM_ALLOWED_USER_ID": "42"}
        with mock.patch.dict(os.environ, env):
            import importlib
            import metatron_core.app as app
            self.app = importlib.reload(app)

    def tearDown(self):
        self.tmp.cleanup()

    def _update(self, uid, user, text):
        return {"update_id": uid, "message": {"text": text, "from": {"id": user}, "chat": {"id": user}}}

    def test_only_founder_messages_become_tasks_and_offset_advances(self):
        updates = [self._update(7, 42, "In o/r fix it"), self._update(8, 99, "stranger task")]
        sent = []
        with mock.patch.object(self.app, "send", lambda chat, text: sent.append((chat, text))), \
                mock.patch("builtins.print") as printed:
            offset = self.app.poll_once(0, "T", call=lambda *a, **k: {"result": updates})
        self.assertEqual(offset, 9)
        self.assertEqual([r["request"] for r in self.app.store.recent()], ["In o/r fix it"])
        self.assertEqual(len(sent), 1)
        self.assertEqual(sent[0][0], "42")
        self.assertIn("user id 99", printed.call_args[0][0])
        self.assertNotIn("stranger task", printed.call_args[0][0])

    def test_bad_update_does_not_stop_polling(self):
        with mock.patch.object(self.app, "handle_update", side_effect=RuntimeError("boom")), \
                mock.patch("builtins.print"):
            offset = self.app.poll_once(5, "T", call=lambda *a, **k: {"result": [{"update_id": 5}]})
        self.assertEqual(offset, 6)


class GeminiRequest(unittest.TestCase):
    def _config(self, model):
        seen = {}

        def post(url, body, headers, timeout):
            seen.update(body)
            return {"candidates": [{"content": {"parts": [{"text": "OK"}]}}]}

        with mock.patch("metatron_core.llm._post", post):
            Gemini("gemini", key="k", model=model).complete([], 64)
        return seen["generationConfig"]

    def test_thinking_is_capped_for_every_model(self):
        self.assertEqual(self._config("gemini-2.5-flash")["thinkingConfig"], {"thinkingBudget": 1024})
        self.assertEqual(self._config("gemini-3.8-flash")["thinkingConfig"], {"thinkingLevel": "low"})
        self.assertEqual(self._config("gemini-3.8-flash")["responseMimeType"], "application/json")


class StepLimit(unittest.TestCase):
    def test_model_is_told_to_finish_and_summary_names_last_actions(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 8, github_token="")
            llm = ScriptedLlm([act("list_dir", path=".")] * 5)
            out = Agent(llm, audit=lambda *a: None, max_steps=5).run("x", ws)
            self.assertTrue(out["failed"])
            self.assertIn("Last actions: list_dir", out["summary"])
            self.assertIn("step(s) left. Call finish now", llm.seen[-1])
            self.assertNotIn("step(s) left", llm.seen[1])

    def test_log_command_shows_recent_audit(self):
        with tempfile.TemporaryDirectory() as d, \
                mock.patch.dict(os.environ, {"CORE_DATA_DIR": d, "TELEGRAM_ALLOWED_USER_ID": "42"}):
            import importlib
            import metatron_core.app as app
            app = importlib.reload(app)
            tid = app.store.create_task("42", "x")
            app.store.audit(tid, "tool", "list_dir({}) -> README.md")
            self.assertIn("[tool] list_dir", app.handle_text("42", f"/log {tid}"))
            self.assertEqual(app.handle_text("42", "/log x"), "Usage: /log <task id>")


class Progress(unittest.TestCase):
    def test_ping_every_five_tool_steps(self):
        with tempfile.TemporaryDirectory() as d, \
                mock.patch.dict(os.environ, {"CORE_DATA_DIR": d, "TELEGRAM_ALLOWED_USER_ID": "42"}):
            import importlib
            import metatron_core.app as app
            app = importlib.reload(app)
            sent = []
            with mock.patch.object(app, "send", lambda chat, text: sent.append(text)), \
                    mock.patch("builtins.print"):
                audit = app.progress_audit(3, "42")
                audit("llm", "thinking")
                for i in range(10):
                    audit("tool", f'run({{"command": "pytest"}}) -> exit={i}')
            self.assertEqual(len(sent), 2)
            self.assertIn("step 10", sent[1])
            self.assertIn("run(", sent[0])


def _fresh_app(d):
    import importlib
    with mock.patch.dict(os.environ, {"CORE_DATA_DIR": d, "TELEGRAM_ALLOWED_USER_ID": "42"}):
        import metatron_core.app as app  # first import must already see the temp data dir
        return importlib.reload(app)


class RepoGuidance(unittest.TestCase):  # M2-1
    def test_clone_shows_agents_md_and_skips_symlinks(self):
        with tempfile.TemporaryDirectory() as d:
            src = Path(d) / "src"
            src.mkdir()
            _git(src, "init", "-q", "-b", "main")
            (src / "AGENTS.md").write_text("Run make test before finishing.")
            (src / "README.md").write_text("Hello")
            (src / "CONTRIBUTING.md").symlink_to("/etc/hostname")
            _git(src, "add", "-A")
            _git(src, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "init")
            remote = Path(d) / "remote" / "o"
            remote.mkdir(parents=True)
            _git(Path(d), "clone", "-q", "--bare", str(src), str(remote / "r.git"))
            ws = Workspace(Path(d) / "work", 9, github_token="")
            ws.remote_base = f"file://{Path(d) / 'remote'}"
            out = ws.clone_repo("o/r")
            self.assertIn("guidance from AGENTS.md", out)
            self.assertIn("Run make test", out)
            self.assertIn("Also present: README.md", out)
            self.assertNotIn("CONTRIBUTING", out)


class WaitForCi(unittest.TestCase):  # M2-2
    def _ws(self, d):
        ws = Workspace(Path(d), 10, github_token="t")
        ws.repo, ws.head_sha, ws._sleep = "o/r", "abc", lambda s: None
        return ws

    def test_pending_then_failure_includes_log_tail(self):
        with tempfile.TemporaryDirectory() as d:
            ws = self._ws(d)
            pending = {"check_runs": [{"id": 1, "status": "in_progress"}]}
            failed = {"check_runs": [{"id": 7, "name": "test", "status": "completed", "conclusion": "failure",
                                      "output": {"title": "1 failed"}, "app": {"slug": "github-actions"}}]}
            with mock.patch("metatron_core.tools._github_api", side_effect=[pending, failed]), \
                    mock.patch("metatron_core.tools._github_text", return_value="line\nAssertionError: 5 != -1"):
                state, details = ws.wait_for_ci()
            self.assertEqual(state, "failure")
            self.assertIn("AssertionError: 5 != -1", details)
            self.assertIn("## test: failure", details)

    def test_no_checks_and_success(self):
        with tempfile.TemporaryDirectory() as d:
            ws = self._ws(d)
            with mock.patch("metatron_core.tools._github_api", return_value={"check_runs": []}):
                self.assertEqual(ws.wait_for_ci(grace=0)[0], "none")
            ok = {"check_runs": [{"status": "completed", "conclusion": "success"},
                                 {"status": "completed", "conclusion": "skipped"}]}
            with mock.patch("metatron_core.tools._github_api", return_value=ok):
                self.assertEqual(ws.wait_for_ci()[0], "success")


class CiFixRounds(unittest.TestCase):  # M2-2
    def test_failure_then_green_after_one_fix(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            ws = mock.Mock(head_sha="a")
            ws.wait_for_ci.side_effect = [("failure", "boom"), ("success", "")]
            ws.publish_branch.side_effect = lambda title: setattr(ws, "head_sha", "b")
            agent = mock.Mock()
            agent.run.return_value = {"summary": "fixed", "open_pr": True, "steps": 3}
            with mock.patch.object(app, "send"):
                result = app.follow_ci(1, "42", {"request": "fix"}, ws, agent, None, lambda *a: None, "t")
            self.assertEqual(result, "CI green")
            self.assertFalse(agent.run.call_args.kwargs["allow_clone"])
            self.assertIn("boom", agent.run.call_args.args[0])

    def test_gives_up_after_two_rounds(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            ws = mock.Mock(head_sha="a")
            ws.wait_for_ci.return_value = ("failure", "still red")
            counter = iter("bcd")
            ws.publish_branch.side_effect = lambda title: setattr(ws, "head_sha", next(counter))
            agent = mock.Mock()
            agent.run.return_value = {"summary": "tried", "open_pr": True, "steps": 3}
            with mock.patch.object(app, "send"):
                result = app.follow_ci(1, "42", {"request": "fix"}, ws, agent, None, lambda *a: None, "t")
            self.assertIn("still failing after 2 fix rounds", result)
            self.assertEqual(agent.run.call_count, 2)


class CancelAndLimits(unittest.TestCase):  # M2-4
    def test_should_stop_ends_the_loop(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 11, github_token="")
            llm = ScriptedLlm([act("list_dir", path=".")] * 5)
            reasons = iter([None, None, "Cancelled by the founder."])
            out = Agent(llm, audit=lambda *a: None).run("x", ws, should_stop=lambda: next(reasons))
            self.assertTrue(out["stopped"])
            self.assertEqual(out["steps"], 2)

    def test_cancel_command(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            queued = app.store.create_task("42", "a")
            running = app.store.create_task("42", "b")
            app.store.update(running, status="running")
            self.assertIn("cancelled before it started", app.handle_text("42", f"/cancel {queued}"))
            self.assertIn("Stopping task", app.handle_text("42", f"/cancel {running}"))
            self.assertEqual(app.store.get(running)["status"], "cancelling")
            self.assertEqual(app.store.claim_next(), None)


class QuotaRetry(unittest.TestCase):  # M2-5
    def test_no_free_model_requeues_later(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            tid = app.store.create_task("42", "x")
            task = app.store.claim_next()
            out = {"summary": "no model", "open_pr": False, "steps": 1, "failed": True, "retry": True}
            with mock.patch.object(app, "send"), mock.patch.object(app.Agent, "run", return_value=out):
                app.process(task)
            row = app.store.get(tid)
            self.assertEqual((row["status"], row["attempts"]), ("queued", 1))
            self.assertGreater(row["not_before"], time.time() + 60)
            self.assertIsNone(app.store.claim_next())  # not before the retry delay


class WorkspaceCleanup(unittest.TestCase):  # M2-6
    def test_old_idle_workspaces_go_running_ones_stay(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            old = app.store.create_task("42", "old")
            live = app.store.create_task("42", "live")
            app.store.update(live, status="running")
            for tid in (old, live):
                w = Path(d) / "work" / f"task-{tid}"
                w.mkdir(parents=True)
                os.utime(w, (time.time() - 5 * 86400,) * 2)
            self.assertEqual(app.cleanup_workspaces(), 1)
            self.assertFalse((Path(d) / "work" / f"task-{old}").exists())
            self.assertTrue((Path(d) / "work" / f"task-{live}").exists())


class Report(unittest.TestCase):  # M3-3
    def test_report_counts_models_and_flags_paid_use(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            a = app.store.create_task("42", "a")
            b = app.store.create_task("42", "b")
            app.store.update(a, status="merged")
            app.store.update(b, status="failed")
            app.store.audit(a, "llm", "[gemini:gemini-3.8-flash] {}")
            app.store.audit(a, "llm", "[gemini:gemini-3.8-flash] {}")
            app.store.audit(b, "llm", "[ollama:qwen2.5-coder:7b] {}")
            text = app.handle_text("42", "/report")
            self.assertIn("2 tasks, success rate 50%", text)
            self.assertIn("gemini:gemini-3.8-flash ×2", text)
            self.assertIn("Paid-provider calls: 0 ✅", text)
            app.store.audit(b, "llm", "[anthropic:claude] {}")
            self.assertIn("zero-cost rule broken", app.handle_text("42", "/report"))


class GeminiQuota(unittest.TestCase):
    MODELS = [_model("gemini-3.8-flash"), _model("gemini-3.5-flash"), _model("gemini-3.1-flash-lite"),
              _model("gemini-flash-lite-latest")]

    def _err(self, code, body):
        return urllib.error.HTTPError("u", code, "x", {}, io.BytesIO(body))

    def test_candidates_order(self):
        self.assertEqual(flash_candidates(self.MODELS),
                         ["gemini-3.8-flash", "gemini-3.5-flash", "gemini-3.1-flash-lite", "gemini-flash-lite-latest"])

    def test_daily_quota_moves_to_next_model(self):
        g = Gemini("gemini", key="k", model="gemini-3.8-flash")
        calls = []

        def generate(messages, max_tokens):
            calls.append(g.model)
            if g.model == "gemini-3.8-flash":
                raise self._err(429, b'{"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}')
            return "OK"

        with mock.patch.object(g, "_generate", side_effect=generate), mock.patch("builtins.print"), \
                mock.patch.object(g, "_list_models", return_value=self.MODELS):
            self.assertEqual(g.complete([], 10), "OK")
            self.assertEqual(calls, ["gemini-3.8-flash", "gemini-3.5-flash"])
            calls.clear()
            g.model = "gemini-3.8-flash"  # exhausted models are skipped until their quota is back
            self.assertEqual(g.complete([], 10), "OK")
            self.assertEqual(calls, ["gemini-3.8-flash", "gemini-3.5-flash"])

    def test_per_minute_limit_waits_and_retries_same_model(self):
        g = Gemini("gemini", key="k", model="gemini-3.8-flash")
        waits = []
        g.sleep = waits.append
        errors = [self._err(429, b'{"quotaId": "GenerateRequestsPerMinute", "retryDelay": "23s"}')]

        def generate(messages, max_tokens):
            if errors:
                raise errors.pop()
            return "OK"

        with mock.patch.object(g, "_generate", side_effect=generate), mock.patch("builtins.print"):
            self.assertEqual(g.complete([], 10), "OK")
        self.assertEqual((waits, g.model), ([24], "gemini-3.8-flash"))

    def test_long_quota_wait_moves_to_the_next_model(self):
        g = Gemini("gemini", key="k", model="gemini-3.8-flash")
        seen = []

        def generate(messages, max_tokens):
            seen.append(g.model)
            if g.model == "gemini-3.8-flash":
                raise self._err(429, b'{"message": "You exceeded your current quota", "retryDelay": "3000s"}')
            return "OK"

        with mock.patch.object(g, "_generate", side_effect=generate), mock.patch("builtins.print"), \
                mock.patch.object(g, "_list_models", return_value=self.MODELS):
            self.assertEqual(g.complete([], 10), "OK")
        self.assertEqual(seen, ["gemini-3.8-flash", "gemini-3.5-flash"])
        self.assertGreater(g.exhausted["gemini-3.8-flash"], time.time() + 2900)

    def test_quota_on_every_model_is_left_to_the_chain(self):
        g = Gemini("gemini", key="k", model="gemini-3.8-flash")
        err = self._err(429, b'{"quotaId": "GenerateRequestsPerMinute", "retryDelay": "300s"}')
        with mock.patch.object(g, "_generate", side_effect=err), mock.patch("builtins.print"), \
                mock.patch.object(g, "_list_models", return_value=[_model("gemini-3.8-flash")]):
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                g.complete([], 10)
        self.assertIn(b"PerMinute", ctx.exception.read())


class RepeatedActions(unittest.TestCase):
    def test_third_identical_call_gets_a_nudge(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 12, github_token="")
            llm = ScriptedLlm([act("list_dir", path=".")] * 3 + [act("finish", summary="s", open_pr=False)])
            Agent(llm, audit=lambda *a: None).run("x", ws)
            self.assertNotIn("exact call 3 times", llm.seen[2])
            self.assertIn("exact call 3 times", llm.seen[3])

    def test_run_starts_in_the_workspace_root(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 13, github_token="")
            (ws.dir / "repo").mkdir()
            (ws.dir / "repo" / "x.txt").write_text("hi")
            self.assertIn("hi", ws.run("cat repo/x.txt"))


class GeminiOverload(unittest.TestCase):
    def test_503_tries_the_next_model(self):
        g = Gemini("gemini", key="k", model="gemini-3.7-flash")
        seen = []

        def generate(messages, max_tokens):
            seen.append(g.model)
            if g.model == "gemini-3.7-flash":
                raise urllib.error.HTTPError("u", 503, "x", {}, io.BytesIO(b"overloaded"))
            return "OK"

        with mock.patch.object(g, "_generate", side_effect=generate), mock.patch("builtins.print"), \
                mock.patch.object(g, "_list_models", return_value=GeminiQuota.MODELS):
            self.assertEqual(g.complete([], 10), "OK")
        self.assertEqual(seen, ["gemini-3.7-flash", "gemini-3.8-flash"])


class PublishSkipsJunk(unittest.TestCase):
    def test_cache_files_stay_out_of_the_pr(self):
        with tempfile.TemporaryDirectory() as d:
            ws, upstream = Publish()._workspace(d)
            ws.run("mkdir -p __pycache__ && echo x > __pycache__/f.cpython-312.pyc "
                   "&& git add -A && git commit -qm 'agent commit with junk' && echo b >> f.txt")
            ws.publish_branch("t")
            files = subprocess.run(["git", "--git-dir", str(upstream), "ls-tree", "-r", "--name-only",
                                    "metatron/task-5"], check=True, capture_output=True, text=True).stdout
            self.assertIn("f.txt", files)
            self.assertNotIn("__pycache__", files)


class PublishOnlyIntendedFiles(unittest.TestCase):
    def test_side_effect_files_stay_out_written_files_go_in(self):
        with tempfile.TemporaryDirectory() as d:
            ws, upstream = Publish()._workspace(d)
            ws.write_file("repo/new_module.py", "X = 1\n")
            ws.run("echo '{}' > .acceptance_observed.json && echo b >> f.txt")
            ws.publish_branch("t")
            files = subprocess.run(["git", "--git-dir", str(upstream), "ls-tree", "-r", "--name-only",
                                    "metatron/task-5"], check=True, capture_output=True, text=True).stdout
            self.assertIn("new_module.py", files)
            self.assertNotIn(".acceptance_observed.json", files)
            self.assertEqual(ws.left_out, [".acceptance_observed.json"])


class ApproveWaitsForCi(unittest.TestCase):
    def test_approve_refused_while_ci_is_checked(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            tid = app.store.create_task("42", "x")
            app.store.update(tid, status="checking_ci", pr_url="https://github.com/o/r/pull/1")
            with mock.patch.object(app, "merge_pull_request") as merge:
                self.assertIn("still being checked", app.handle_text("42", f"/approve {tid}"))
            merge.assert_not_called()


class GeminiMalformed(unittest.TestCase):
    def test_malformed_reply_moves_to_next_model_but_safety_block_does_not(self):
        g = Gemini("gemini", key="k", model="gemini-3.5-flash-lite")
        seen = []

        def generate(messages, max_tokens):
            seen.append(g.model)
            if g.model == "gemini-3.5-flash-lite":
                raise EmptyReply("gemini returned no text (finishReason=MALFORMED_RESPONSE)")
            return "OK"

        with mock.patch.object(g, "_generate", side_effect=generate), mock.patch("builtins.print"), \
                mock.patch.object(g, "_list_models", return_value=GeminiQuota.MODELS):
            self.assertEqual(g.complete([], 10), "OK")
        self.assertEqual(seen, ["gemini-3.5-flash-lite", "gemini-3.8-flash"])
        blocked = Gemini("gemini", key="k", model="gemini-3.8-flash")
        with mock.patch.object(blocked, "_generate", side_effect=EmptyReply("no candidates (SAFETY)")):
            self.assertRaises(EmptyReply, blocked.complete, [], 10)


class ConflictAndRetry(unittest.TestCase):
    def test_conflict_explained_and_retry_requeues(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            tid = app.store.create_task("42", "rename average to mean")
            url = "https://github.com/o/r/pull/3"
            app.store.update(tid, status="awaiting_approval", pr_url=url)
            conflict = urllib.error.HTTPError(url, 405, "Method Not Allowed", {}, io.BytesIO(b""))
            with mock.patch.object(app, "merge_pull_request", side_effect=conflict), mock.patch("builtins.print"):
                reply = app.handle_text("42", f"/approve {tid}")
            self.assertIn("conflicts", reply)
            self.assertIn(f"/retry {tid}", reply)
            with mock.patch.object(app, "close_pull_request") as close:
                reply = app.handle_text("42", f"/retry {tid}")
            close.assert_called_once_with(url, app.GITHUB_TOKEN)
            self.assertEqual(app.store.get(tid)["status"], "superseded")
            new = app.store.claim_next()
            self.assertEqual(new["request"], "rename average to mean")
            self.assertIn(f"as #{new['id']}", reply)


class WebhookTakeover(unittest.TestCase):
    def test_409_warns_founder_once_and_backs_off(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            conflict = urllib.error.HTTPError("u", 409, "Conflict", {}, io.BytesIO(b""))
            sent, sleeps = [], []

            def sleep(seconds):
                sleeps.append(seconds)
                if len(sleeps) == 2:
                    raise KeyboardInterrupt  # end the endless loop

            with mock.patch.object(app, "telegram", return_value={"result": {"username": "b"}}), \
                    mock.patch.object(app, "poll_once", side_effect=conflict), \
                    mock.patch.object(app, "send", lambda chat, text: sent.append((chat, text))), \
                    mock.patch.object(app.time, "sleep", sleep), mock.patch("builtins.print"):
                with self.assertRaises(KeyboardInterrupt):
                    app.poll_loop("T")
            self.assertEqual(len(sent), 1)
            self.assertEqual(sent[0][0], "42")
            self.assertEqual(sleeps, [60, 60])


class CreateRepo(unittest.TestCase):
    def test_creates_private_repo_on_token_owner_then_clones(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 15, github_token="t")
            ws._sleep = lambda s: None
            calls = []

            def api(method, url, token, body):
                calls.append((method, url, body))
                return {"login": "kelvinka38"} if url.endswith("/user") else {}

            with mock.patch("metatron_core.tools._github_api", side_effect=api), \
                    mock.patch.object(ws, "clone_repo", return_value="cloned") as clone:
                self.assertEqual(ws.create_repo("metatron-ai/control-center"), "cloned")
            self.assertEqual(calls[1], ("POST", "https://api.github.com/user/repos",
                                        {"name": "control-center", "private": True, "auto_init": True}))
            clone.assert_called_once_with("kelvinka38/control-center")

    def test_bad_name_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 16, github_token="t")
            self.assertIn("error", ws.create_repo("bad name!"))

    def test_create_repo_not_allowed_in_ci_fix_rounds(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 17, github_token="t")
            self.assertIn("unknown tool", Agent._call(ws, "create_repo", {"name": "x"}, allow_clone=False))


class NoRepoNoSilentSuccess(unittest.TestCase):
    def test_pr_wanted_without_repo_is_reported_as_failed(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            tid = app.store.create_task("42", "build an app")
            task = app.store.claim_next()
            out = {"summary": "built it", "open_pr": True, "steps": 5}
            sent = []
            with mock.patch.object(app, "send", lambda chat, text: sent.append(text)), \
                    mock.patch.object(app.Agent, "run", return_value=out), mock.patch("builtins.print"):
                app.process(task)
            self.assertEqual(app.store.get(tid)["status"], "failed")
            self.assertIn("not in a GitHub repo", sent[-1])


class ChangeSummary(unittest.TestCase):
    def test_lists_changed_files_with_counts(self):
        with tempfile.TemporaryDirectory() as d:
            ws, _ = Publish()._workspace(d)
            ws.write_file("repo/app.py", "print(1)\nprint(2)\n")
            ws.run("echo b >> f.txt")
            ws.publish_branch("t")
            summary = ws.change_summary()
            self.assertIn("• app.py (+2 −0)", summary)
            self.assertIn("• f.txt (+1 −0)", summary)


def _free_port():
    import socket
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class PreviewApps(unittest.TestCase):
    def test_detects_node_python_and_static_apps(self):
        from metatron_core.preview import detect
        with tempfile.TemporaryDirectory() as d:
            repo = Path(d)
            self.assertIsNone(detect(repo))
            (repo / "public").mkdir()
            (repo / "public" / "index.html").write_text("<h1>hi</h1>")
            self.assertIn("cd public && python3 -m http.server", detect(repo))
            (repo / "app.py").write_text("print(1)")
            self.assertEqual(detect(repo), "python3 app.py")
            (repo / "package.json").write_text('{"scripts": {"start": "node server.js"}}')
            self.assertTrue(detect(repo).endswith("npm start"))

    def test_static_site_starts_is_proxied_and_stops(self):
        import http.client
        import http.server
        from metatron_core import preview as previews
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            ws = Workspace(Path(d) / "work", 20, github_token="")
            (ws.dir / "repo").mkdir()
            (ws.dir / "repo" / "index.html").write_text("<h1>Control Center</h1>")
            notes = []
            port = _free_port()
            pv = previews.Preview(Path(d) / "previews", notify=notes.append, port=port, ready_timeout=20)
            self.assertEqual(pv.start(ws, 20), "starting")
            for _ in range(100):
                if pv.port:
                    break
                time.sleep(0.2)
            self.assertEqual(pv.port, port)
            self.assertIn("ready", notes[-1])
            server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            try:
                with mock.patch.object(app, "PREVIEW_HOST", "preview.example"), \
                        mock.patch.object(app, "preview", pv):
                    conn = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=10)
                    conn.request("GET", "/", headers={"Host": "preview.example"})
                    resp = conn.getresponse()
                    self.assertEqual(resp.status, 401)  # no login yet
                    self.assertNotIn(b"Control Center", resp.read())
                    link = app.preview_login.new_link("preview.example")
                    login = link.split("preview.example", 1)[1]
                    conn.request("GET", login, headers={"Host": "preview.example"})
                    resp = conn.getresponse()
                    resp.read()
                    self.assertEqual(resp.status, 302)
                    cookie = resp.getheader("Set-Cookie").split(";")[0]
                    self.assertIn("HttpOnly", resp.getheader("Set-Cookie"))
                    conn.request("GET", login, headers={"Host": "preview.example"})
                    resp = conn.getresponse()
                    resp.read()
                    self.assertEqual(resp.status, 403)  # a link works once
                    auth = {"Host": "preview.example", "Cookie": cookie}
                    conn.request("GET", "/", headers=auth)
                    self.assertIn(b"Control Center", conn.getresponse().read())
                    conn.request("GET", "/", headers={"Host": "preview.example", "Cookie": "core_preview=forged"})
                    resp = conn.getresponse()
                    resp.read()
                    self.assertEqual(resp.status, 401)
                    conn.request("GET", "/health", headers=auth)
                    self.assertNotIn(b"providers", conn.getresponse().read())  # Core routes stay hidden
                    conn.request("GET", "/health", headers={"Host": "127.0.0.1"})
                    self.assertIn(b"providers", conn.getresponse().read())
                    conn.close()
            finally:
                server.shutdown()
            self.assertTrue(pv.stop())
            self.assertFalse(previews.port_open(port))
            self.assertIsNone(pv.running_task())


class WaitBeforeLocalModel(unittest.TestCase):
    def test_busy_gemini_is_waited_for_before_ollama(self):
        gemini = FailingProvider("gemini", 503, b"overloaded")
        answers = iter([urllib.error.HTTPError("u", 503, "x", {}, io.BytesIO(b"busy")), "from gemini"])

        def complete(messages, max_tokens):
            item = next(answers)
            if isinstance(item, Exception):
                raise item
            return item

        gemini.complete = complete
        local = mock.Mock(spec=["complete", "available", "cool_down", "name", "cooldown_until"])
        local.name, local.cooldown_until = "ollama", 0
        local.available.return_value = True
        naps = []

        def nap(seconds):
            naps.append(seconds)
            gemini.cooldown_until = 0  # Gemini is back after the nap

        chain = ProviderChain([gemini, local], fallback_wait=300, sleep=nap)
        with mock.patch("builtins.print"):
            self.assertEqual(chain.complete([]), "from gemini")
        self.assertEqual(len(naps), 1)
        local.complete.assert_not_called()

    def test_no_wait_when_gemini_is_out_for_the_day(self):
        gemini = FailingProvider("gemini", 403, b"key")
        local = mock.Mock(spec=["complete", "available", "cool_down", "name", "cooldown_until"])
        local.name, local.cooldown_until = "ollama", 0
        local.available.return_value = True
        local.complete.return_value = "from ollama"
        chain = ProviderChain([gemini, local], sleep=lambda s: self.fail("should not wait"))
        with mock.patch("builtins.print"):
            self.assertEqual(chain.complete([]), "from ollama")


class RepoRootPaths(unittest.TestCase):
    def test_paths_and_commands_start_in_the_repo_after_clone(self):
        with tempfile.TemporaryDirectory() as d:
            ws, _ = Publish()._workspace(d)
            ws.write_file("package.json", "{}")
            ws.write_file("repo/src/app.js", "1")
            self.assertTrue((ws.dir / "repo" / "package.json").is_file())
            self.assertTrue((ws.dir / "repo" / "src" / "app.js").is_file())
            self.assertEqual(ws.written, {"package.json", "src/app.js"})
            self.assertIn("f.txt", ws.run("ls"))
            self.assertIn("package.json", ws.list_dir("."))
            self.assertRaises(ValueError, ws.read_file, "../../etc/passwd")

    def test_fourth_identical_call_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 21, github_token="")
            calls = []
            with mock.patch.object(Agent, "_call", side_effect=lambda *a: calls.append(1) or "same"):
                llm = ScriptedLlm([act("list_dir", path=".")] * 4 + [act("finish", summary="s", open_pr=False)])
                Agent(llm, audit=lambda *a: None).run("x", ws)
            self.assertEqual(len(calls), 3)
            self.assertIn("refused", llm.seen[4])


class OpenRouterFreeOnly(unittest.TestCase):
    MODELS = [
        {"id": "big/general:free", "context_length": 200000, "pricing": {"prompt": "0", "completion": "0"}},
        {"id": "qwen/qwen3-coder:free", "context_length": 100000, "pricing": {"prompt": "0", "completion": "0"}},
        {"id": "paid/model", "context_length": 900000, "pricing": {"prompt": "0.000001", "completion": "0.000002"}},
        {"id": "tricky/priced:free", "context_length": 999999, "pricing": {"prompt": "0.5", "completion": "0"}},
    ]

    def test_only_zero_priced_free_models_coders_first(self):
        self.assertEqual(openrouter_free_candidates(self.MODELS), ["qwen/qwen3-coder:free", "big/general:free"])

    def test_key_without_model_no_longer_blocks_startup(self):
        env = {"OPENROUTER_FREE_API_KEY": "k"}
        with mock.patch.dict(os.environ, env, clear=True):
            names = [p.name for p in ProviderChain.from_env().providers]
        self.assertEqual(names, ["openrouter", "ollama"])

    def test_rate_limited_model_moves_to_next_free_one(self):
        r = OpenRouterFree("openrouter", key="k")
        seen = []

        def post(url, body, headers, timeout):
            seen.append(body["model"])
            if body["model"] == "qwen/qwen3-coder:free":
                raise urllib.error.HTTPError(url, 429, "x", {}, io.BytesIO(b""))
            return {"choices": [{"message": {"content": "OK"}}]}

        with mock.patch("metatron_core.llm._post", post), mock.patch("builtins.print"), \
                mock.patch.object(r, "_models", return_value=self.MODELS):
            self.assertEqual(r.complete([], 10), "OK")
        self.assertEqual(seen, ["qwen/qwen3-coder:free", "big/general:free"])


class ProviderPersistence(unittest.TestCase):
    def test_gemini_walks_past_four_bad_models(self):
        models = [_model(n) for n in ("gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.6-flash",
                                      "gemini-3.5-flash", "gemini-3.5-flash-lite")]
        g = Gemini("gemini", key="k", model="gemini-2.5-flash")

        def generate(messages, max_tokens):
            if g.model != "gemini-3.5-flash-lite":
                raise urllib.error.HTTPError("u", 503, "x", {}, io.BytesIO(b"busy"))
            return "OK"

        with mock.patch.object(g, "_generate", side_effect=generate), mock.patch("builtins.print"), \
                mock.patch.object(g, "_list_models", return_value=models):
            self.assertEqual(g.complete([], 10), "OK")

    def test_openrouter_empty_reply_moves_on(self):
        r = OpenRouterFree("openrouter", key="k")

        def post(url, body, headers, timeout):
            if body["model"] == "qwen/qwen3-coder:free":
                return {"choices": [{"message": {"content": "", "reasoning": "thinking..."}}]}
            return {"choices": [{"message": {"content": "OK"}}]}

        with mock.patch("metatron_core.llm._post", post), mock.patch("builtins.print"), \
                mock.patch.object(r, "_models", return_value=OpenRouterFreeOnly.MODELS):
            self.assertEqual(r.complete([], 10), "OK")
        self.assertEqual(r.model, "big/general:free")


class RepoPrivacyAndRestrictedModels(unittest.TestCase):
    def _create(self, ws, **kw):
        calls = []

        def api(method, url, token, body):
            calls.append(body)
            return {"login": "me"} if url.endswith("/user") else {}

        ws._sleep = lambda s: None
        with mock.patch("metatron_core.tools._github_api", side_effect=api), \
                mock.patch.object(ws, "clone_repo", return_value="cloned"):
            ws.create_repo("app", **kw)
        return calls[1]["private"]

    def test_model_cannot_make_a_repo_public_on_its_own(self):
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 22, github_token="t")
            self.assertTrue(self._create(ws, private=False))
            ws.allow_public = True
            self.assertFalse(self._create(ws, private=False))

    def test_restricted_openrouter_model_is_skipped_not_fatal(self):
        r = OpenRouterFree("openrouter", key="k")

        def post(url, body, headers, timeout):
            if body["model"] == "qwen/qwen3-coder:free":
                raise urllib.error.HTTPError(url, 403, "only on agentic harnesses", {}, io.BytesIO(b""))
            return {"choices": [{"message": {"content": "OK"}}]}

        with mock.patch("metatron_core.llm._post", post), mock.patch("builtins.print"), \
                mock.patch.object(r, "_models", return_value=OpenRouterFreeOnly.MODELS):
            self.assertEqual(r.complete([], 10), "OK")


if __name__ == "__main__":
    unittest.main()


class LocalModel(unittest.TestCase):
    def test_request_sets_a_context_window_big_enough_for_the_prompt(self):
        sent = {}

        def post(url, body, headers, timeout):
            sent.update(body)
            return {"message": {"content": "ok"}}

        with mock.patch("metatron_core.llm._post", post):
            Ollama("ollama", num_ctx=16384).complete([Message("system", "rules"), Message("user", "task")], 4096)
        self.assertEqual(sent["options"]["num_ctx"], 16384)
        self.assertEqual(sent["messages"][0]["content"], "rules")

    def test_long_history_keeps_rules_task_and_latest_turns(self):
        history = [Message("system", "RULES"), Message("user", "TASK")]
        for i in range(60):
            history += [Message("assistant", f"step {i}"), Message("user", f"output {i} " + "x" * 3000)]
        fitted = Ollama.fit(history, 20_000)
        self.assertLessEqual(sum(len(m.content) for m in fitted), 20_000)
        self.assertEqual([m.content for m in fitted[:2]], ["RULES", "TASK"])
        self.assertIn("left out", fitted[2].content)
        self.assertTrue(fitted[-1].content.startswith("output 59"))

    def test_no_wait_before_the_local_model_when_configured(self):
        slept = []
        busy = FailingProvider("gemini", 429, b"quota")
        chain = ProviderChain([busy, Ollama("ollama")], fallback_wait=0, sleep=slept.append)
        with mock.patch("metatron_core.llm._post", lambda *a, **k: {"message": {"content": "done"}}):
            self.assertEqual(chain.complete([Message("user", "hi")]), "done")
        self.assertEqual(slept, [])
        self.assertEqual(chain.last_used, "ollama:qwen2.5-coder:7b")


class BackgroundCommands(unittest.TestCase):
    def test_a_server_started_in_the_background_does_not_hang_the_call(self):
        with tempfile.TemporaryDirectory() as d:
            ws, _ = Publish()._workspace(d)
            began = time.time()
            out = ws.run("sleep 300 & echo started", timeout=60)
            self.assertLess(time.time() - began, 10)
            self.assertIn("exit=0", out)
            self.assertIn("started", out)
            self.assertIn("background processes", out)

    def test_plain_commands_get_no_note(self):
        with tempfile.TemporaryDirectory() as d:
            ws, _ = Publish()._workspace(d)
            out = ws.run("echo hi; echo oops >&2; exit 3")
            self.assertIn("exit=3", out)
            self.assertIn("hi", out)
            self.assertIn("oops", out)
            self.assertNotIn("[note]", out)


class PreviewLoginCookie(unittest.TestCase):
    def test_the_app_never_sees_cores_session_cookie(self):
        from metatron_core import preview_auth
        self.assertEqual(preview_auth.without_our_cookie("a=1; core_preview=secret; b=2"), "a=1; b=2")
        self.assertEqual(preview_auth.session_from("a=1; core_preview=secret"), "secret")

    def test_expired_link_does_not_log_in(self):
        from metatron_core.preview_auth import PreviewAuth
        auth = PreviewAuth(link_ttl=-1)
        token = auth.new_link("h").split("t=")[1]
        self.assertIsNone(auth.redeem(token))
        self.assertIsNone(PreviewAuth().redeem("made-up"))


class ControlRoom(unittest.TestCase):
    def _serve(self, app):
        import http.server
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.shutdown)
        return server.server_port

    def _call(self, port, method, path, headers, body=None):
        import http.client
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        conn.request(method, path, body=json.dumps(body) if body is not None else None, headers=headers)
        resp = conn.getresponse()
        data = resp.read()
        conn.close()
        return resp, data

    def test_login_state_actions_and_new_task(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            tid = app.store.create_task("42", "In kelvinka38/scratch add a page")
            app.store.update(tid, repo="kelvinka38/scratch", status="awaiting_approval",
                             pr_url="https://github.com/kelvinka38/scratch/pull/1")
            app.store.audit(tid, "llm", "[gemini:gemini-3.8-flash] {}")
            with mock.patch.object(app, "CONTROL_HOST", "control.example"):
                port = self._serve(app)
                host = {"Host": "control.example"}
                resp, _ = self._call(port, "GET", "/api/state", host)
                self.assertEqual(resp.status, 401)
                login = app.preview_login.new_link("control.example").split("control.example", 1)[1]
                resp, _ = self._call(port, "GET", login, host)
                self.assertEqual(resp.status, 302)
                auth = dict(host, Cookie=resp.getheader("Set-Cookie").split(";")[0])
                resp, page = self._call(port, "GET", "/", auth)
                self.assertIn(b"Control Room", page)
                resp, data = self._call(port, "GET", "/api/state", auth)
                state = json.loads(data)
                self.assertEqual(state["summary"]["awaiting_approval"], 1)
                self.assertEqual(state["projects"][0]["repo"], "kelvinka38/scratch")
                self.assertEqual(state["projects"][0]["open_prs"][0]["task_id"], tid)
                self.assertEqual(state["models"]["usage_7d"], [{"model": "gemini:gemini-3.8-flash", "calls": 1}])
                self.assertEqual(state["models"]["paid_calls_7d"], 0)
                resp, data = self._call(port, "GET", f"/api/tasks/{tid}", auth)
                self.assertEqual(json.loads(data)["log"][0]["kind"], "llm")
                # Actions need the custom header, so a cross-site form cannot trigger them.
                resp, _ = self._call(port, "POST", "/api/action", auth, {"action": "reject", "task_id": tid})
                self.assertEqual(resp.status, 403)
                act = dict(auth, **{"X-Core": "1", "Content-Type": "application/json"})
                with mock.patch.object(app, "send"):
                    resp, data = self._call(port, "POST", "/api/action", act, {"action": "reject", "task_id": tid})
                self.assertEqual(resp.status, 200)
                self.assertEqual(app.store.get(tid)["status"], "done")
                resp, data = self._call(port, "POST", "/api/tasks", act, {"request": "In kelvinka38/scratch fix it"})
                self.assertIn("queued", json.loads(data)["reply"])
                resp, _ = self._call(port, "POST", "/api/tasks", act, {"request": "/approve 1"})
                self.assertEqual(resp.status, 400)  # commands only through the action buttons
                resp, _ = self._call(port, "GET", "/health", auth)
                self.assertEqual(resp.status, 404)  # Core's own routes stay off the control host

    def test_processes_are_read_per_task_user(self):
        from metatron_core import control
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            (root / "stat").write_text("cpu 1 2 3\nbtime 1000\n")
            for pid, uid in ((10, 20007), (11, 0)):
                p = root / str(pid)
                p.mkdir()
                (p / "status").write_text(f"Name:\tnode\nUid:\t{uid}\t{uid}\t{uid}\t{uid}\nVmRSS:\t 2048 kB\n")
                (p / "cmdline").write_bytes(b"node\0server.js\0")
                (p / "stat").write_text(f"{pid} (node) S " + " ".join(["0"] * 10) + " 100 50 0 0 0 0 0 0 200 0")
            procs = control.processes(20000, str(root))
        self.assertEqual(len(procs), 1)
        self.assertEqual(procs[0]["task_id"], 7)
        self.assertEqual(procs[0]["cmd"], "node server.js")
        self.assertEqual(procs[0]["rss_mb"], 2.0)


class WorkforceCore(unittest.TestCase):  # W1
    def _store(self, d):
        return Store(str(Path(d) / "core.db"))

    def test_roster_and_ownership_survive_a_restart(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._store(d)
            tid = s.create_task("42", "In kelvinka38/bios fix the failing test")
            self.assertEqual(s.get(tid)["owner_id"], "head-of-engineering")
            s = self._store(d)  # restart: the roster is not seeded twice, identities are kept
            names = [w["name"] for w in s.workers()]
            self.assertEqual(names, ["Head of Engineering", "Software Engineer A", "Software Engineer B",
                                     "QA Engineer", "Research Analyst"])
            self.assertEqual(s.get(tid)["owner_id"], "head-of-engineering")

    def test_work_goes_to_a_capable_worker_with_free_capacity(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._store(d)
            code1 = s.create_task("42", "In kelvinka38/bios add a pond API")
            code2 = s.create_task("42", "In kelvinka38/bios fix the login page")
            code3 = s.create_task("42", "In kelvinka38/universal update the README")
            research = s.create_task("42", "Research the shrimp feed market in Vietnam and compare suppliers")
            first, second, third = s.claim_next(), s.claim_next(), s.claim_next()
            self.assertEqual((first["id"], first["assignee_id"]), (code1, "software-engineer-a"))
            self.assertEqual((second["id"], second["assignee_id"]), (code2, "software-engineer-b"))
            # Both engineers are busy: the third code task waits, the research task does not.
            self.assertEqual((third["id"], third["assignee_id"]), (research, "research-analyst"))
            self.assertIsNone(s.claim_next())
            self.assertEqual(s.get(code3)["status"], "queued")
            s.update(code1, status="awaiting_approval")  # its Work is done: capacity comes back
            fourth = s.claim_next()
            self.assertEqual((fourth["id"], fourth["assignee_id"]), (code3, "software-engineer-a"))
            loads = {w["id"]: w["load"] for w in s.workers()}
            self.assertEqual(loads["software-engineer-a"], 1)
            self.assertEqual(loads["head-of-engineering"], 4)  # still owns all four Objectives
            s.update(code1, status="merged")
            self.assertEqual({w["id"]: w["load"] for w in s.workers()}["head-of-engineering"], 3)

    def test_a_restart_mid_run_frees_the_worker_and_requeues(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._store(d)
            tid = s.create_task("42", "In kelvinka38/bios add a pond API")
            s.claim_next()
            s = self._store(d)
            self.assertEqual(s.get(tid)["status"], "queued")
            self.assertEqual({w["id"]: w["load"] for w in s.workers()}["software-engineer-a"], 0)
            self.assertEqual(s.claim_next()["assignee_id"], "software-engineer-a")

    def test_record_counts_what_each_worker_delivered(self):
        with tempfile.TemporaryDirectory() as d:
            s = self._store(d)
            a = s.create_task("42", "In kelvinka38/bios add a pond API")
            b = s.create_task("42", "In kelvinka38/bios add a feed API")
            s.claim_next(), s.claim_next()
            s.update(a, status="done")
            s.update(b, status="failed")
            by_id = {w["id"]: w for w in s.workers()}
            self.assertEqual(by_id["software-engineer-a"]["record"], {"done": 1})
            self.assertEqual(by_id["software-engineer-b"]["record"], {"failed": 1})

    def test_capability_is_read_from_the_request(self):
        from metatron_core.workforce import required_capability
        self.assertEqual(required_capability("Nghiên cứu thị trường tôm và so sánh nhà cung cấp"), "research")
        self.assertEqual(required_capability("In kelvinka38/bios fix the failing test"), "code")
        self.assertEqual(required_capability("Research and implement a cache in kelvinka38/bios"), "code")

    def test_the_run_is_told_which_worker_it_works_for(self):
        from metatron_core.workforce import ROSTER, persona
        llm = ScriptedLlm(['{"thought": "done", "tool": "finish", "args": {"summary": "ok"}}'])
        seen = []
        orig = llm.complete
        llm.complete = lambda messages, max_tokens=4096: (seen.append(messages[0].content), orig(messages))[1]
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 1, github_token="")
            Agent(llm, lambda k, v: None, persona=persona(ROSTER[1])).run("say hi", ws)
        self.assertTrue(seen[0].startswith("You are working as Software Engineer A"))

    def test_workers_command_and_status_show_who_does_what(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            app.store.create_task("42", "In kelvinka38/bios add a pond API")
            app.store.claim_next()
            out = app.handle_text("42", "/workers")
            self.assertIn("Software Engineer A — Software Engineer, load 1/1 (#1)", out)
            self.assertIn("Research Analyst", out)
            self.assertIn("Software Engineer A:", app.handle_text("42", "/status"))


class ResearchTools(unittest.TestCase):
    def test_only_public_addresses_can_be_fetched(self):
        from metatron_core import research
        for url in ("http://127.0.0.1:8095/health", "http://10.0.0.5/", "http://169.254.169.254/latest/",
                    "file:///etc/passwd", "http://[::1]/", "ftp://example.com/"):
            self.assertTrue(research.fetch_url(url).startswith("error:"), url)

    def test_search_result_pages_are_parsed(self):
        from metatron_core import research
        import base64
        target = "https://vasep.com.vn/feed-prices"
        u = "a1" + base64.urlsafe_b64encode(target.encode()).decode().rstrip("=")
        bing = (f'<li class="b_algo"><h2 class=""><a href="https://www.bing.com/ck/a?!&amp;p=x&amp;u={u}&amp;ntb=1">'
                '<strong>Shrimp feed</strong> prices</a></h2><div class="b_caption"><p class="b_lineclamp2">'
                'Feed costs 30,000 VND/kg</p></div></li>')
        self.assertEqual(research.parse_bing(bing),
                         [{"title": "Shrimp feed prices", "url": target, "snippet": "Feed costs 30,000 VND/kg"}])
        ddg = ('<div class="result results_links"><a class="result__a" href="//duckduckgo.com/l/?uddg='
               'https%3A%2F%2Fexample.org%2Fa&amp;rut=1">Example</a><a class="result__snippet">Snip</a></div>')
        self.assertEqual(research.parse_duckduckgo(ddg)[0]["url"], "https://example.org/a")

    def test_google_grounding_moves_to_the_next_free_model(self):
        from metatron_core import research
        calls = []

        def post(url, body):
            calls.append(url.split("/models/")[1])
            if len(calls) == 1:
                raise urllib.error.HTTPError(url, 429, "quota", {}, io.BytesIO(b""))
            return {"candidates": [{"content": {"parts": [{"text": "C.P. and Grobest lead."}]},
                                    "groundingMetadata": {"groundingChunks": [
                                        {"web": {"uri": "https://vertexaisearch.cloud.google.com/r/1",
                                                 "title": "tepbac.com"}}]}}]}

        results = research.google_search("shrimp feed Vietnam", "k", post=post)
        self.assertEqual(len(calls), 2)
        self.assertEqual(results[0]["title"], "tepbac.com")
        self.assertIn("Grobest", results[0]["snippet"])

    def test_agent_can_search_and_the_report_is_read_back(self):
        llm = ScriptedLlm([
            '{"thought": "look it up", "tool": "web_search", "args": {"query": "shrimp feed"}}',
            '{"thought": "write", "tool": "write_file", "args": {"path": "report.md", "content": "# Feed\\n'
            'Price 30k VND/kg ([tepbac](https://tepbac.com/x))"}}',
            '{"thought": "done", "tool": "finish", "args": {"summary": "ok"}}'])
        with tempfile.TemporaryDirectory() as d, \
                mock.patch("metatron_core.research.web_search", return_value="1. Tepbac\n   https://tepbac.com/x"):
            ws = Workspace(Path(d), 1, github_token="")
            out = Agent(llm, lambda k, v: None).run("research shrimp feed", ws)
            self.assertFalse(out.get("failed"))
            self.assertIn("tepbac.com/x", llm.seen[1])  # the search result reached the model
            self.assertIn("30k VND/kg", ws.report())


class ReportDelivery(unittest.TestCase):
    def test_report_is_sent_and_unsourced_reports_are_flagged(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            sent = []
            with mock.patch.object(app, "send_document", lambda *a: sent.append(a) or True), \
                    mock.patch.object(app, "CONTROL_HOST", "control.example"):
                lines = app.deliver_report("42", 7, "# R\nsee https://a.vn/x and https://b.vn/y")
                self.assertIn("sent as a file", lines)
                self.assertNotIn("unverified", lines)
                self.assertIn("2 sources", sent[0][3])
                self.assertIn("unverified", app.deliver_report("42", 8, "# R\nC.P. is the biggest."))
            self.assertEqual(app.deliver_report("42", 9, ""), "")

    def test_control_room_serves_the_report(self):
        with tempfile.TemporaryDirectory() as d:
            app = _fresh_app(d)
            tid = app.store.create_task("42", "Research shrimp feed suppliers")
            app.store.update(tid, status="done", report="# Shrimp feed\n| Supplier | VND/kg |\n|---|---|\n| C.P. | 30000 |")
            with mock.patch.object(app, "CONTROL_HOST", "control.example"):
                room = ControlRoom()
                port = room._serve(app)
                self.addCleanup(lambda: None)
                login = app.preview_login.new_link("control.example").split("control.example", 1)[1]
                resp, _ = room._call(port, "GET", login, {"Host": "control.example"})
                auth = {"Host": "control.example", "Cookie": resp.getheader("Set-Cookie").split(";")[0]}
                resp, data = room._call(port, "GET", f"/api/tasks/{tid}/report.md", auth)
                self.assertEqual(resp.status, 200)
                self.assertIn(b"| C.P. | 30000 |", data)
                resp, data = room._call(port, "GET", f"/api/tasks/{tid}", auth)
                self.assertIn("Shrimp feed", json.loads(data)["task"]["report"])
                resp, data = room._call(port, "GET", "/api/state", auth)
                self.assertTrue(json.loads(data)["tasks"][0]["has_report"])
                room.doCleanups()


class DeliverableRequired(unittest.TestCase):
    FINISH = '{"thought": "done", "tool": "finish", "args": {"summary": "Report complete"}}'

    def test_finish_waits_for_a_sourced_report(self):
        llm = ScriptedLlm([
            self.FINISH,  # claims done with no file: refused
            '{"thought": "w", "tool": "write_file", "args": {"path": "report.md", "content": "C.P. leads."}}',
            self.FINISH,  # file exists but cites nothing: refused once
            '{"thought": "w", "tool": "write_file", "args": {"path": "report.md", '
            '"content": "C.P. leads ([tepbac](https://tepbac.com/a))."}}',
            self.FINISH])
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 1, github_token="")
            out = Agent(llm, lambda k, v: None).run("research", ws, deliverable="report.md")
        self.assertFalse(out.get("failed"))
        self.assertEqual(out["steps"], 5)
        self.assertIn("does not exist yet", llm.seen[1])
        self.assertIn("cites no web sources", llm.seen[3])

    def test_no_report_at_all_fails_the_task(self):
        llm = ScriptedLlm([self.FINISH] * 3)
        with tempfile.TemporaryDirectory() as d:
            ws = Workspace(Path(d), 1, github_token="")
            out = Agent(llm, lambda k, v: None).run("research", ws, deliverable="report.md")
        self.assertTrue(out["failed"])
        self.assertIn("no report.md was written", out["summary"])

    def test_code_tasks_finish_as_before(self):
        llm = ScriptedLlm([self.FINISH])
        with tempfile.TemporaryDirectory() as d:
            out = Agent(llm, lambda k, v: None).run("fix", Workspace(Path(d), 1, github_token=""))
        self.assertEqual(out["steps"], 1)
