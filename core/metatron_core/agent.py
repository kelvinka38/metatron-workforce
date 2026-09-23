"""The agent: one loop, one model conversation, tools until the task is really done.

No phases, no keyword planners. The model sees every tool result (including failing tests)
and keeps editing/running until it can honestly call finish().
"""
from __future__ import annotations

import json

from .llm import LlmUnavailable, Message, ProviderChain
from .tools import TOOL_SPEC, Workspace

SYSTEM = f"""You are a senior software engineer working for Metatron. You complete the user's task
end-to-end inside a private workspace, like a human engineer at a terminal.

Tools:
{TOOL_SPEC}

Protocol - every reply is exactly ONE JSON object and nothing else:
  {{"thought": "<short reasoning>", "tool": "<tool name>", "args": {{...}}}}

Working rules:
- If the task names a repository, clone it first. Explore before editing (list_dir, read_file, run grep).
- Follow the repository guidance (AGENTS.md, CONTRIBUTING.md, README) shown after cloning.
- After changing code, run the project's build/tests. If they fail, read the error, fix, re-run.
- Never claim success you have not verified by running something. Say plainly what you could not verify.
- Only call finish with open_pr=true when there are real changes and the checks you could run pass.
- If the task is a question (no code change needed), answer it via finish(summary=..., open_pr=false).
- Keep the summary short, concrete, and in the same language the user wrote in.
"""

_DECODER = json.JSONDecoder()


def parse_action(text: str) -> dict:
    """The first JSON object with a 'tool' in a model reply, ignoring prose and code fences around it.

    Raises ValueError when there is none, or when 'args' is not an object.
    """
    for i, ch in enumerate(text):
        if ch != "{":
            continue
        try:
            obj, _ = _DECODER.raw_decode(text, i)
        except json.JSONDecodeError:
            continue
        if not (isinstance(obj, dict) and isinstance(obj.get("tool"), str) and obj["tool"].strip()):
            continue
        args = obj.get("args") or {}
        if isinstance(args, str):  # some models send args as a JSON string
            try:
                args = json.loads(args)
            except json.JSONDecodeError:
                raise ValueError("'args' is not a JSON object") from None
        if not isinstance(args, dict):
            raise ValueError("'args' is not a JSON object")
        return {**obj, "tool": obj["tool"].strip(), "args": args}
    raise ValueError("no JSON object with a 'tool' field")


def invalid_reply_hint(reply: str) -> str:
    if not reply.strip():
        return "Your reply was empty."
    if "{" in reply and not reply.rstrip().rstrip("`").rstrip().endswith("}"):
        return ("Your reply was cut off before the JSON ended. Keep each reply short: use "
                "replace_in_file for edits, or write large files in smaller parts.")
    return "Your reply had no valid action."


class Agent:
    def __init__(self, llm: ProviderChain, audit, max_steps: int = 40):
        self.llm = llm
        self.audit = audit          # callable(kind, detail)
        self.max_steps = max_steps

    def run(self, request: str, ws: Workspace, should_stop=None, allow_clone: bool = True) -> dict:
        """Returns {'summary', 'open_pr', 'pr_title', 'steps'}, plus 'failed' and, when no free model
        was reachable, 'retry'; 'stopped' when should_stop() gave a reason (cancel, time limit)."""
        messages = [Message("system", SYSTEM), Message("user", f"Task:\n{request}")]
        bad_replies = 0
        recent_tools: list[str] = []
        for step in range(1, self.max_steps + 1):
            reason = should_stop() if should_stop else None
            if reason:
                return {"summary": reason, "open_pr": False, "steps": step - 1, "failed": True, "stopped": True}
            self._compact(messages)
            try:
                reply = self.llm.complete(messages, max_tokens=4096)
            except LlmUnavailable as e:
                return {"summary": f"Stopped: no free LLM available right now ({e}).",
                        "open_pr": False, "steps": step, "failed": True, "retry": True}
            self.audit("llm", f"[{self.llm.last_used}] {reply[:1500]}")
            messages.append(Message("assistant", reply))
            try:
                action = parse_action(reply)
                tool, args = action["tool"], action["args"]
            except ValueError:
                bad_replies += 1
                if bad_replies >= 3:
                    return {"summary": "Stopped: the model kept replying in an invalid format.",
                            "open_pr": False, "steps": step, "failed": True}
                messages.append(Message("user", invalid_reply_hint(reply) + " Reply with exactly ONE JSON "
                                                'object {"thought":..., "tool":..., "args":{...}}.'))
                continue
            bad_replies = 0

            if tool == "finish":
                return {"summary": str(args.get("summary", "")).strip() or "(no summary)",
                        "open_pr": bool(args.get("open_pr")),
                        "pr_title": str(args.get("pr_title") or "")[:120],
                        "steps": step}

            result = self._call(ws, tool, args, allow_clone)
            self.audit("tool", f"{tool}({json.dumps(args)[:500]}) -> {result[:1500]}")
            recent_tools.append(tool)
            left = self.max_steps - step
            note = ""
            if 0 < left <= 3:
                note = (f"\n\n[{left} step(s) left. Call finish now: say what you did, what you found, and "
                        "what you could not do. If the task cannot be done as asked, say why.]")
            messages.append(Message("user", f"Result of {tool}:\n{result}{note}"))

        return {"summary": f"Stopped after {self.max_steps} steps without finishing. "
                           f"Last actions: {', '.join(recent_tools[-5:]) or 'none'}.",
                "open_pr": False, "steps": self.max_steps, "failed": True}

    @staticmethod
    def _call(ws: Workspace, tool: str, args: dict, allow_clone: bool = True) -> str:
        allowed = {"list_dir", "read_file", "write_file", "replace_in_file", "run"}
        if allow_clone:
            allowed.add("clone_repo")
        if tool not in allowed:
            return f"error: unknown tool '{tool}'. Allowed: {sorted(allowed)} or finish"
        try:
            return str(getattr(ws, tool)(**args))
        except TypeError as e:
            return f"error: bad arguments for {tool}: {e}"
        except Exception as e:
            return f"error: {type(e).__name__}: {e}"

    @staticmethod
    def _compact(messages: list[Message], keep_recent: int = 16, budget_chars: int = 120_000) -> None:
        """Free-tier context is limited: shrink old tool outputs, keep system + task + recent turns."""
        total = sum(len(m.content) for m in messages)
        if total <= budget_chars:
            return
        for m in messages[2:-keep_recent]:
            if m.role == "user" and len(m.content) > 600:
                m.content = m.content[:300] + "\n...[old output trimmed]..."
            elif m.role == "assistant" and len(m.content) > 2000:  # e.g. an old write_file body
                m.content = m.content[:1000] + "\n...[old action trimmed]..."
