"""Metatron Core entrypoint: Telegram webhook + local API + one worker thread.

Approval rule (the only governance): the agent may do anything inside its workspace and open a
PR by itself. Merging needs the founder's '/approve <id>' in Telegram.
"""
from __future__ import annotations

import hmac
import http.client
import json
import os
import shutil
import threading
import time
import traceback
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from . import preview as previews
from .agent import Agent
from .llm import ProviderChain
from .store import Store
import urllib.error

from .tools import Workspace, agent_uid_for, close_pull_request, merge_pull_request

DATA = Path(os.environ.get("CORE_DATA_DIR", "/data"))
BOT_TOKEN = os.environ.get("CORE_TELEGRAM_BOT_TOKEN") or os.environ.get("TELEGRAM_BOT_TOKEN", "")
WEBHOOK_SECRET = os.environ.get("CORE_TELEGRAM_WEBHOOK_SECRET") or os.environ.get("TELEGRAM_WEBHOOK_SECRET", "")
ALLOWED_USER = os.environ.get("TELEGRAM_ALLOWED_USER_ID", "")
API_TOKEN = os.environ.get("CORE_API_TOKEN", "")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")

if os.geteuid() == 0:
    os.umask(0o077)  # core.db and audit data stay unreadable to the per-task agent users
DATA.mkdir(parents=True, exist_ok=True)
(DATA / "work").mkdir(exist_ok=True)
if os.geteuid() == 0:
    os.chmod(DATA, 0o711)
    os.chmod(DATA / "work", 0o711)
store = Store(str(DATA / "core.db"))
llm = ProviderChain.from_env()
wake = threading.Event()
PREVIEW_HOST = os.environ.get("CORE_PREVIEW_HOST", "").lower()  # e.g. preview.metatron.vn
preview = previews.Preview(DATA / "previews", notify=lambda text: send(ALLOWED_USER, text))


def send(chat_id: str, text: str) -> None:
    if not BOT_TOKEN or not chat_id or chat_id == "api":
        print(f"[reply to {chat_id}] {text}", flush=True)
        return
    for chunk in [text[i:i + 3900] for i in range(0, len(text), 3900)] or [""]:
        body = json.dumps({"chat_id": chat_id, "text": chunk, "disable_web_page_preview": True}).encode()
        req = urllib.request.Request(f"https://api.telegram.org/bot{BOT_TOKEN}/sendMessage", data=body,
                                     headers={"Content-Type": "application/json"})
        try:
            urllib.request.urlopen(req, timeout=30).read()
        except Exception as e:
            print(f"telegram send failed: {e}", flush=True)


# ---------------- worker ----------------
PROGRESS_EVERY = 5


def progress_audit(tid: int, chat: str):
    """Audit to the DB, one log line per step, and a Telegram progress ping every few tool steps."""
    steps = {"n": 0}

    def audit(kind: str, detail: str) -> None:
        store.audit(tid, kind, detail)
        if kind != "tool":
            return
        steps["n"] += 1
        action = detail.split(" -> ", 1)[0][:100]
        print(f"task #{tid} step {steps['n']}: {action}", flush=True)
        if steps["n"] % PROGRESS_EVERY == 0:
            send(chat, f"⏳ Task #{tid} still working, step {steps['n']}: {action}")

    return audit


TASK_TIME_LIMIT = 45 * 60      # M2-4
RETRY_DELAY = 15 * 60          # M2-5: every free provider cooling down
MAX_ATTEMPTS = 8
CI_FIX_ROUNDS = 2              # M2-2
WORKSPACE_MAX_AGE = 3 * 86400  # M2-6


def process(task: dict) -> None:
    tid, chat = task["id"], task["chat_id"]
    audit = progress_audit(tid, chat)
    started = time.time()

    def should_stop():
        if (store.get(tid) or {}).get("status") == "cancelling":
            return "Cancelled by the founder."
        if time.time() - started > TASK_TIME_LIMIT:
            return f"Stopped: the {TASK_TIME_LIMIT // 60}-minute limit per task was reached."
        return None

    send(chat, f"▶️ Task #{tid} started. I'll post progress every {PROGRESS_EVERY} steps.")
    ws = Workspace(DATA / "work", tid, GITHUB_TOKEN, agent_uid_for(tid))
    ws.allow_public = "public" in task["request"].lower()
    agent = Agent(llm, audit)
    out = agent.run(task["request"], ws, should_stop)
    fields = {"steps": out["steps"], "result": out["summary"], "repo": ws.repo}
    print(f"task #{tid} agent finished after {out['steps']} steps: failed={bool(out.get('failed'))} "
          f"open_pr={bool(out.get('open_pr'))}", flush=True)

    if out.get("retry"):
        attempts = int(task.get("attempts") or 0) + 1
        if attempts < MAX_ATTEMPTS:
            store.update(tid, status="queued", attempts=attempts, not_before=time.time() + RETRY_DELAY,
                         steps=out["steps"])
            send(chat, f"⏸ Task #{tid} paused: no free model is available right now. "
                       f"I'll retry in {RETRY_DELAY // 60} minutes (attempt {attempts + 1} of {MAX_ATTEMPTS}).")
            return
    if out.get("stopped"):
        cancelled = (store.get(tid) or {}).get("status") == "cancelling"
        store.update(tid, status="cancelled" if cancelled else "failed", **fields)
        send(chat, f"🛑 Task #{tid} stopped\n{out['summary']}")
        return
    if out.get("failed"):
        store.update(tid, status="failed", **fields)
        send(chat, f"❌ Task #{tid} failed\n{out['summary']}")
        return
    if out.get("open_pr") and ws.repo:
        title = out.get("pr_title") or f"Metatron task #{tid}"
        try:
            url = ws.open_pull_request(title, f"{out['summary']}\n\nRequest:\n> {task['request']}\n\nTask #{tid}")
        except Exception as e:
            print(f"task #{tid} open PR failed: {str(e)[:300]}", flush=True)
            audit("error", f"open PR failed: {e}")
            fields["result"] = f"{out['summary']}\n\n(PR could not be opened: {e})"
        else:
            print(f"task #{tid} PR opened: {url}", flush=True)
            store.update(tid, status="checking_ci", pr_url=url, **fields)
            send(chat, f"📬 Task #{tid}: PR opened, checking its CI before you approve\n{url}")
            ci = follow_ci(tid, chat, task, ws, agent, should_stop, audit, title)
            store.update(tid, status="awaiting_approval")
            files = ws.change_summary()
            repo_url = f"https://github.com/{ws.repo}"
            live = ""
            if PREVIEW_HOST and previews.detect(ws.dir / "repo"):
                preview.start(ws, tid)
                live = f"🖥 Live preview: https://{PREVIEW_HOST} (starting; I'll message you when it's ready)\n"
            send(chat, f"✅ Task #{tid} done — {ci}\n\n"
                       f"📝 What was done:\n{out['summary']}\n\n"
                       f"📂 Files changed:\n{files or '(see the PR)'}\n\n"
                       f"🔗 Pull request (view the code and diff): {url}\n"
                       f"📦 Repo: {repo_url}\n{live}\n"
                       f"Reply /approve {tid} to merge, /reject {tid} to leave it open.")
            return
    if out.get("open_pr") and not ws.repo:
        # The agent wanted a PR but never cloned or created a GitHub repo: its files are not on GitHub.
        fields["result"] = (f"{out['summary']}\n\n⚠️ No PR: the work was not in a GitHub repo (nothing was "
                            "cloned or created). Ask again naming an existing repo, or a new project name.")
        store.update(tid, status="failed", **fields)
        send(chat, f"❌ Task #{tid} could not publish\n{fields['result']}")
        return
    store.update(tid, status="done", **fields)
    send(chat, f"✅ Task #{tid} done\n{fields['result']}")


def follow_ci(tid, chat, task, ws, agent, should_stop, audit, title) -> str:
    """Wait for the PR's CI; on failure give the logs back to the agent, up to CI_FIX_ROUNDS times."""
    for fix_round in range(CI_FIX_ROUNDS + 1):
        try:
            state, details = ws.wait_for_ci()
        except Exception as e:
            audit("error", f"CI check failed: {e}")
            return f"CI state unknown ({type(e).__name__})"
        audit("info", f"CI {state}: {details[:1500]}")
        if state == "success":
            return "CI green"
        if state == "none":
            return "no CI checks ran on this repo"
        if state == "timeout":
            return "CI did not finish within 20 minutes"
        if fix_round == CI_FIX_ROUNDS:
            return f"CI still failing after {CI_FIX_ROUNDS} fix rounds:\n{details[:800]}"
        send(chat, f"🔧 Task #{tid}: CI failed, fix round {fix_round + 1} of {CI_FIX_ROUNDS}")
        before = ws.head_sha
        out = agent.run(f"{task['request']}\n\nYou already changed the repository and opened a pull request. Its CI "
                        f"failed:\n{details[:6000]}\n\nFix the cause (do not clone again), run the "
                        "checks you can, then call finish with open_pr=true.", ws, should_stop, allow_clone=False)
        if out.get("failed"):
            return f"CI failed and the fix round stopped: {out['summary']}"
        try:
            ws.publish_branch(title)
        except Exception as e:
            return f"CI failed and the fix could not be pushed: {e}"
        if ws.head_sha == before:
            return f"CI failed and the fix round changed nothing:\n{details[:800]}"
    return "CI state unknown"


def cleanup_workspaces(now: float | None = None) -> int:
    """Delete task workspaces untouched for WORKSPACE_MAX_AGE, except for tasks still running (M2-6)."""
    now = now or time.time()
    removed = 0
    for d in (DATA / "work").glob("task-*"):
        tid = d.name.removeprefix("task-")
        task = store.get(int(tid)) if tid.isdigit() else None
        if task and (task["status"] in ("running", "cancelling") or task["id"] == preview.running_task()):
            continue
        try:
            if now - d.stat().st_mtime > WORKSPACE_MAX_AGE:
                shutil.rmtree(d)
                removed += 1
        except OSError as e:
            print(f"cleanup of {d.name} failed: {e}", flush=True)
    return removed


def worker_loop() -> None:
    last_cleanup = 0.0
    while True:
        if time.time() - last_cleanup > 3600:
            last_cleanup = time.time()
            removed = cleanup_workspaces()
            if removed:
                print(f"cleanup: removed {removed} old workspaces", flush=True)
        preview.expire()
        task = store.claim_next()
        if not task:
            wake.wait(30)
            wake.clear()
            continue
        try:
            process(task)
        except Exception:
            err = traceback.format_exc()
            store.audit(task["id"], "error", err)
            store.update(task["id"], status="failed", result=err[-1500:])
            send(task["chat_id"], f"❌ Task #{task['id']} crashed: {err.splitlines()[-1]}")


# ---------------- commands ----------------
def handle_text(chat_id: str, text: str) -> str | None:
    text = text.strip()
    if text in ("/start", "/help"):
        return ("Send me any task in plain language, e.g.\n"
                "\"In kelvinka38/bios fix the failing test in the aquaculture module\".\n"
                "/status — recent tasks\n/log <id> — a task's last steps\n/cancel <id> — stop a task\n"
                "/retry <id> — redo a task on the latest code\n/preview <id> — open a task's app live\n"
                "/report — last 7 days\n/approve <id> — merge a task's PR\n/reject <id> — leave it open")
    if text.startswith("/status"):
        rows = store.recent(10)
        return "\n".join(f"#{r['id']} [{r['status']}] {r['request'][:60]}" for r in rows) or "No tasks yet."
    if text.startswith("/cancel"):
        parts = text.split()
        if len(parts) != 2 or not parts[1].isdigit():
            return "Usage: /cancel <task id>"
        task = store.get(int(parts[1]))
        if not task:
            return "No such task."
        if task["status"] == "queued":
            store.update(task["id"], status="cancelled")
            return f"Task #{task['id']} cancelled before it started."
        if task["status"] == "running":
            store.update(task["id"], status="cancelling")
            return f"Stopping task #{task['id']} after its current step."
        return f"Task #{task['id']} is {task['status']}; nothing to cancel."
    if text.startswith("/retry"):
        parts = text.split()
        if len(parts) != 2 or not parts[1].isdigit():
            return "Usage: /retry <task id>"
        task = store.get(int(parts[1]))
        if not task:
            return "No such task."
        if task["status"] in ("queued", "running", "cancelling", "checking_ci"):
            return f"Task #{task['id']} is {task['status']}; wait for it or /cancel it first."
        note = ""
        if task["status"] == "awaiting_approval" and task.get("pr_url"):
            try:
                close_pull_request(task["pr_url"], GITHUB_TOKEN)
                note = f" Closed its old PR {task['pr_url']}."
            except Exception as e:
                note = f" (Could not close the old PR: {e})"
            store.update(task["id"], status="superseded")
        new = store.create_task(chat_id, task["request"])
        wake.set()
        return f"🔁 Task #{task['id']} queued again as #{new} on the latest code.{note}"
    if text.startswith("/preview"):
        parts = text.split()
        if not PREVIEW_HOST:
            return "Previews are not set up (CORE_PREVIEW_HOST)."
        if len(parts) == 2 and parts[1] == "stop":
            return "Preview stopped." if preview.stop() else "No preview is running."
        if len(parts) != 2 or not parts[1].isdigit():
            running = preview.running_task()
            state = f"showing task #{running}" if running else "nothing running"
            return f"Usage: /preview <task id> or /preview stop ({state}: https://{PREVIEW_HOST})"
        task = store.get(int(parts[1]))
        ws_dir = DATA / "work" / f"task-{parts[1]}"
        if not task or not (ws_dir / "repo").is_dir():
            return "That task's files are gone (workspaces are kept 3 days). Send /retry to rebuild it."
        ws = Workspace(DATA / "work", task["id"], GITHUB_TOKEN, agent_uid_for(task["id"]))
        result = preview.start(ws, task["id"])
        if result != "starting":
            return f"Can't preview task #{task['id']}: {result}."
        return f"🖥 Starting a preview of task #{task['id']}: https://{PREVIEW_HOST} (I'll message you when it's ready)"
    if text.startswith("/report"):
        return weekly_report()
    if text.startswith("/log"):
        parts = text.split()
        if len(parts) != 2 or not parts[1].isdigit():
            return "Usage: /log <task id>"
        rows = store.audit_tail(int(parts[1]))
        return "\n\n".join(f"[{r['kind']}] {r['detail'][:300]}" for r in rows) or "No log for that task."
    if text.startswith("/approve") or text.startswith("/reject"):
        parts = text.split()
        if len(parts) != 2 or not parts[1].isdigit():
            return "Usage: /approve <task id>"
        task = store.get(int(parts[1]))
        if task and task["status"] == "checking_ci":
            return f"Task #{task['id']}'s CI is still being checked. I'll tell you when it's ready to approve."
        if not task or task["status"] != "awaiting_approval":
            return "That task has no PR waiting for approval."
        if text.startswith("/reject"):
            store.update(task["id"], status="done")
            store.audit(task["id"], "approval", "rejected by founder")
            return f"Task #{task['id']} rejected. PR left open for you to close or edit: {task['pr_url']}"
        try:
            merge_pull_request(task["pr_url"], GITHUB_TOKEN)
        except Exception as e:
            print(f"task #{task['id']} merge failed: {str(e)[:300]}", flush=True)
            if isinstance(e, urllib.error.HTTPError) and e.code in (405, 409):
                return (f"Can't merge task #{task['id']}: its PR conflicts with changes merged since it was "
                        f"made.\n{task['pr_url']}\nSend /retry {task['id']} to redo it on the latest code "
                        "(the old PR is closed).")
            return f"Merge failed: {e}"
        print(f"task #{task['id']} merged: {task['pr_url']}", flush=True)
        store.update(task["id"], status="merged")
        store.audit(task["id"], "approval", "merged by founder")
        return f"Merged task #{task['id']}: {task['pr_url']}"
    tid = store.create_task(chat_id, text)
    wake.set()
    return f"📥 Task #{tid} queued. I'll message you when it's done."


def weekly_report(days: int = 7) -> str:
    """M3-3: tasks, success rate and which model did the work. Paid providers must stay at zero."""
    since = time.time() - days * 86400
    tasks = store.tasks_since(since)
    counts: dict[str, int] = {}
    for t in tasks:
        counts[t["status"]] = counts.get(t["status"], 0) + 1
    ok = sum(counts.get(s, 0) for s in ("done", "awaiting_approval", "merged"))
    finished = ok + counts.get("failed", 0)
    rate = f"{100 * ok // finished}%" if finished else "n/a"
    per_task: dict[int, dict[str, int]] = {}
    totals: dict[str, int] = {}
    for tid, model in store.llm_calls_since(since):
        per_task.setdefault(tid, {})
        per_task[tid][model] = per_task[tid].get(model, 0) + 1
        totals[model] = totals.get(model, 0) + 1
    paid = sum(n for m, n in totals.items() if m.split(":")[0] in ("anthropic", "openai"))
    lines = [f"Last {days} days: {len(tasks)} tasks, success rate {rate} ({ok} of {finished} finished).",
             "By status: " + (", ".join(f"{k} {v}" for k, v in sorted(counts.items())) or "none"),
             "Model calls: " + (", ".join(f"{m} ×{n}" for m, n in sorted(totals.items())) or "none"),
             f"Paid-provider calls: {paid}" + (" ✅" if paid == 0 else " ⚠️ zero-cost rule broken")]
    for t in tasks[:10]:
        used = ", ".join(f"{m} ×{n}" for m, n in sorted(per_task.get(t["id"], {}).items())) or "no model calls"
        lines.append(f"#{t['id']} [{t['status']}] {used}")
    return "\n".join(lines)


# ---------------- telegram ----------------
def handle_update(update: dict) -> None:
    """One Telegram update, from the webhook or from polling. Only the founder's messages count."""
    msg = update.get("message") or {}
    user = str((msg.get("from") or {}).get("id", ""))
    chat = str((msg.get("chat") or {}).get("id", ""))
    if not msg.get("text"):
        return
    if not ALLOWED_USER or user != ALLOWED_USER:
        # Only the numeric id is logged, never the text: lets the founder find their own id.
        print(f"telegram: ignored a message from user id {user} (not TELEGRAM_ALLOWED_USER_ID)", flush=True)
        return
    reply = handle_text(chat, msg["text"])
    if reply:
        send(chat, reply)


def telegram(method: str, body: dict, token: str, timeout: float = 30) -> dict:
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/{method}", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())


def poll_once(offset: int, token: str, call=telegram) -> int:
    """Fetch waiting updates (long poll, 50 s), handle them, return the next offset."""
    result = call("getUpdates", {"offset": offset, "timeout": 50, "allowed_updates": ["message"]},
                  token, timeout=60).get("result", [])
    for update in result:
        offset = max(offset, int(update["update_id"]) + 1)
        try:
            handle_update(update)
        except Exception:
            print(f"telegram update failed: {traceback.format_exc()}", flush=True)
    return offset


def poll_loop(token: str) -> None:
    """Long polling: no public URL, tunnel or open port needed. Only for Core's own bot."""
    telegram("deleteWebhook", {}, token)
    me = telegram("getMe", {}, token).get("result", {})
    print(f"telegram: polling as @{me.get('username', '?')}", flush=True)
    offset = 0
    warned = False
    while True:
        try:
            offset = poll_once(offset, token)
            warned = False
        except urllib.error.HTTPError as e:
            if e.code != 409:
                print(f"telegram poll failed: HTTP {e.code}", flush=True)
                time.sleep(5)
                continue
            # 409: someone set a webhook on this bot again (e.g. the old Workforce). Don't fight over it.
            print("telegram: another system set a webhook on this bot; Core is not receiving messages", flush=True)
            if not warned and ALLOWED_USER:
                send(ALLOWED_USER, "⚠️ Another system took over this bot's webhook, so Core no longer receives "
                                   "messages. Stop that system or roll back, then restart Core.")
                warned = True
            time.sleep(60)
        except Exception as e:
            print(f"telegram poll failed: {type(e).__name__}: {str(e).replace(token, '***')[:300]}", flush=True)
            time.sleep(5)


# ---------------- http ----------------
HOP_HEADERS = {"connection", "keep-alive", "transfer-encoding", "upgrade", "proxy-connection", "te", "trailer"}


class Handler(BaseHTTPRequestHandler):
    def _is_preview(self) -> bool:
        return bool(PREVIEW_HOST) and self.headers.get("Host", "").split(":")[0].lower() == PREVIEW_HOST

    def _proxy(self) -> None:
        """Everything on the preview host goes to the running preview app, never to Core's own routes."""
        port = preview.port
        if not port or not previews.port_open(port):
            body = (b"<h2>No preview is running.</h2><p>Send <code>/preview &lt;task id&gt;</code> to the bot.</p>")
            self.send_response(503)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        length = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(length) if length else None
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS}
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
        try:
            conn.request(self.command, self.path, body=data, headers=headers)
            resp = conn.getresponse()
            payload = resp.read()
            self.send_response(resp.status)
            for k, v in resp.getheaders():
                if k.lower() not in HOP_HEADERS and k.lower() != "content-length":
                    self.send_header(k, v)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(payload)
        except OSError as e:
            self._json(502, {"error": f"preview app did not answer: {type(e).__name__}"})
        finally:
            conn.close()

    def do_PUT(self):
        return self._proxy() if self._is_preview() else self._json(404, {})

    do_PATCH = do_DELETE = do_OPTIONS = do_PUT

    def do_HEAD(self):
        return self._proxy() if self._is_preview() else self._json(404, {})

    def _json(self, code: int, obj) -> None:
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if self._is_preview():
            return self._proxy()
        if self.path == "/health":
            return self._json(200, {"ok": True, "providers": [p.name for p in llm.providers]})
        if self.path.startswith("/tasks/") and self._api_ok():
            tid = self.path.split("/")[2]
            task = store.get(int(tid)) if tid.isdigit() else None
            return self._json(200 if task else 404, task or {})
        self._json(404, {})

    def do_POST(self):
        if self._is_preview():
            return self._proxy()
        if self.path in ("/telegram", "/core/telegram"):  # tunnel may keep the /core prefix
            got = self.headers.get("X-Telegram-Bot-Api-Secret-Token", "")
            if not WEBHOOK_SECRET or not hmac.compare_digest(got, WEBHOOK_SECRET):
                return self._json(401, {})
            update = self._body()
            self._json(200, {})  # ack fast; Telegram retries otherwise
            handle_update(update)
            return
        if self.path == "/tasks" and self._api_ok():
            reply = handle_text("api", self._body().get("request", ""))
            return self._json(200, {"reply": reply})
        self._json(404, {})

    def _api_ok(self) -> bool:
        ok = bool(API_TOKEN) and hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {API_TOKEN}")
        if not ok:
            self._json(401, {})
        return ok

    def log_message(self, *args):  # quiet access log
        pass


def main() -> None:
    threading.Thread(target=worker_loop, daemon=True, name="worker").start()
    core_bot = os.environ.get("CORE_TELEGRAM_BOT_TOKEN", "")
    if core_bot and os.environ.get("CORE_TELEGRAM_MODE", "poll") == "poll":
        # Polling removes the bot's webhook. With the main bot's token this is the cutover (M3-2):
        # the old Workforce stops receiving Telegram messages.
        threading.Thread(target=poll_loop, args=(core_bot,), daemon=True, name="telegram").start()
    port = int(os.environ.get("PORT", "8095"))
    print(f"metatron-core listening on :{port}, providers={[p.name for p in llm.providers]}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
