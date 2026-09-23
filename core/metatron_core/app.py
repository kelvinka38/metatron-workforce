"""Metatron Core entrypoint: Telegram webhook + local API + one worker thread.

Approval rule (the only governance): the agent may do anything inside its workspace and open a
PR by itself. Merging needs the founder's '/approve <id>' in Telegram.
"""
from __future__ import annotations

import hmac
import json
import os
import threading
import time
import traceback
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .agent import Agent
from .llm import ProviderChain
from .store import Store
from .tools import Workspace

DATA = Path(os.environ.get("CORE_DATA_DIR", "/data"))
BOT_TOKEN = os.environ.get("CORE_TELEGRAM_BOT_TOKEN") or os.environ.get("TELEGRAM_BOT_TOKEN", "")
WEBHOOK_SECRET = os.environ.get("CORE_TELEGRAM_WEBHOOK_SECRET") or os.environ.get("TELEGRAM_WEBHOOK_SECRET", "")
ALLOWED_USER = os.environ.get("TELEGRAM_ALLOWED_USER_ID", "")
API_TOKEN = os.environ.get("CORE_API_TOKEN", "")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN", "")

DATA.mkdir(parents=True, exist_ok=True)
store = Store(str(DATA / "core.db"))
llm = ProviderChain.from_env()
wake = threading.Event()


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
def process(task: dict) -> None:
    tid, chat = task["id"], task["chat_id"]
    audit = lambda kind, detail: store.audit(tid, kind, detail)  # noqa: E731
    ws = Workspace(DATA / "work", tid, GITHUB_TOKEN)
    out = Agent(llm, audit).run(task["request"], ws)
    fields = {"steps": out["steps"], "result": out["summary"], "repo": ws.repo}

    if out.get("failed"):
        store.update(tid, status="failed", **fields)
        send(chat, f"❌ Task #{tid} failed\n{out['summary']}")
        return
    if out.get("open_pr") and ws.repo:
        try:
            title = out.get("pr_title") or f"Metatron task #{tid}"
            url = ws.open_pull_request(title, f"{out['summary']}\n\nRequest:\n> {task['request']}\n\nTask #{tid}")
            store.update(tid, status="awaiting_approval", pr_url=url, **fields)
            send(chat, f"✅ Task #{tid} done — PR opened\n{url}\n\n{out['summary']}\n\n"
                       f"Reply /approve {tid} to merge, /reject {tid} to close it.")
            return
        except Exception as e:
            audit("error", f"open PR failed: {e}")
            fields["result"] = f"{out['summary']}\n\n(PR could not be opened: {e})"
    store.update(tid, status="done", **fields)
    send(chat, f"✅ Task #{tid} done\n{fields['result']}")


def worker_loop() -> None:
    while True:
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
                "/status — recent tasks\n/approve <id> — merge a task's PR\n/reject <id> — close it")
    if text.startswith("/status"):
        rows = store.recent(10)
        return "\n".join(f"#{r['id']} [{r['status']}] {r['request'][:60]}" for r in rows) or "No tasks yet."
    if text.startswith("/approve") or text.startswith("/reject"):
        parts = text.split()
        if len(parts) != 2 or not parts[1].isdigit():
            return "Usage: /approve <task id>"
        task = store.get(int(parts[1]))
        if not task or task["status"] != "awaiting_approval":
            return "That task has no PR waiting for approval."
        if text.startswith("/reject"):
            store.update(task["id"], status="done")
            store.audit(task["id"], "approval", "rejected by founder")
            return f"Task #{task['id']} rejected. PR left open for you to close or edit: {task['pr_url']}"
        try:
            Workspace(DATA / "work", task["id"], GITHUB_TOKEN).merge_pull_request(task["pr_url"])
        except Exception as e:
            return f"Merge failed: {e}"
        store.update(task["id"], status="merged")
        store.audit(task["id"], "approval", "merged by founder")
        return f"Merged task #{task['id']}: {task['pr_url']}"
    tid = store.create_task(chat_id, text)
    wake.set()
    return f"📥 Task #{tid} queued. I'll message you when it's done."


# ---------------- http ----------------
class Handler(BaseHTTPRequestHandler):
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
        if self.path == "/health":
            return self._json(200, {"ok": True, "providers": [p.name for p in llm.providers]})
        if self.path.startswith("/tasks/") and self._api_ok():
            task = store.get(int(self.path.split("/")[2]))
            return self._json(200 if task else 404, task or {})
        self._json(404, {})

    def do_POST(self):
        if self.path == "/telegram":
            got = self.headers.get("X-Telegram-Bot-Api-Secret-Token", "")
            if not WEBHOOK_SECRET or not hmac.compare_digest(got, WEBHOOK_SECRET):
                return self._json(401, {})
            msg = (self._body().get("message") or {})
            user = str((msg.get("from") or {}).get("id", ""))
            chat = str((msg.get("chat") or {}).get("id", ""))
            self._json(200, {})  # ack fast; Telegram retries otherwise
            if msg.get("text") and user == ALLOWED_USER:
                reply = handle_text(chat, msg["text"])
                if reply:
                    send(chat, reply)
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
    port = int(os.environ.get("PORT", "8095"))
    print(f"metatron-core listening on :{port}, providers={[p.name for p in llm.providers]}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
