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
                                cooldown_for, flash_candidates, gemini_text, pick_flash_model)
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
            ws.run("cd repo && echo b >> f.txt && git commit -qam 'agent commit'")
            self.assertEqual(ws.publish_branch("t"), "metatron/task-5")
            log = subprocess.run(["git", "--git-dir", str(upstream), "log", "--format=%s", "metatron/task-5"],
                                 check=True, capture_output=True, text=True).stdout
            self.assertIn("agent commit", log)

    def test_agent_hooks_do_not_run_on_publish(self):
        with tempfile.TemporaryDirectory() as d:
            ws, _ = self._workspace(d)
            ws.run("cd repo && mkdir -p h && printf '#!/bin/sh\\ntouch ../pwned\\n' > h/pre-push && chmod +x h/pre-push "
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

    def test_long_per_minute_wait_is_left_to_the_chain(self):
        g = Gemini("gemini", key="k", model="gemini-3.8-flash")
        err = self._err(429, b'{"quotaId": "GenerateRequestsPerMinute", "retryDelay": "300s"}')
        with mock.patch.object(g, "_generate", side_effect=err):
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
            ws.run("cd repo && mkdir -p __pycache__ && echo x > __pycache__/f.cpython-312.pyc "
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
            ws.run("cd repo && echo '{}' > .acceptance_observed.json && echo b >> f.txt")
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


if __name__ == "__main__":
    unittest.main()
