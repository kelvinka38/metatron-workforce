"""File operations behind the agent's file tools.

Workspace calls these in-process when there is no separate agent user (tests, local dev). In the
container they run in a child process under the task's own Unix user (this file's source via
`python -c`, JSON on stdin), so a symlink the agent planted can never make Core's
root process read or write outside the workspace.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

MAX_OUTPUT = 8000


def clip(text: str) -> str:
    if len(text) <= MAX_OUTPUT:
        return text
    half = MAX_OUTPUT // 2
    return text[:half] + f"\n... [{len(text) - MAX_OUTPUT} chars cut] ...\n" + text[-half:]


def list_dir(path: str, shown: str) -> str:
    p = Path(path)
    if not p.is_dir():
        return f"error: not a directory: {shown}"
    out = [c.name + ("/" if c.is_dir() else "") for c in sorted(p.iterdir()) if c.name != ".git"]
    return "\n".join(out[:300]) or "(empty)"


def read_file(path: str, shown: str) -> str:
    p = Path(path)
    if not p.is_file():
        return f"error: no such file: {shown}"
    return clip(p.read_text(errors="replace"))


def write_file(path: str, shown: str, content: str) -> str:
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content)
    return f"wrote {len(content)} chars to {shown}"


def replace_in_file(path: str, shown: str, old: str, new: str) -> str:
    p = Path(path)
    if not p.is_file():
        return f"error: no such file: {shown}"
    text = p.read_text(errors="replace")
    count = text.count(old)
    if count != 1:
        return f"error: 'old' must occur exactly once, found {count} times"
    p.write_text(text.replace(old, new))
    return f"replaced 1 occurrence in {shown}"


OPS = {"list_dir": list_dir, "read_file": read_file, "write_file": write_file,
       "replace_in_file": replace_in_file}


if __name__ == "__main__":
    req = json.load(sys.stdin)
    try:
        print(OPS[req["op"]](**req["args"]), end="")
    except Exception as e:  # reported to the agent as a tool error
        print(f"error: {type(e).__name__}: {e}", end="")
