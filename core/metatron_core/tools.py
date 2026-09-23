"""Tools the agent can call. All file/shell work is confined to one per-task workspace directory.

Secrets never enter the agent's reach:
  * shell commands run with a scrubbed environment (no API keys, no GitHub token);
  * the clone remote URL is stripped of the token right after cloning;
  * only core code (never the agent) uses the token, to push and open the PR.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import urllib.request
from pathlib import Path

MAX_OUTPUT = 8000
SAFE_ENV_KEYS = ("PATH", "LANG", "LC_ALL", "JAVA_HOME", "GRADLE_USER_HOME", "PIP_CACHE_DIR")


def _clip(text: str) -> str:
    if len(text) <= MAX_OUTPUT:
        return text
    half = MAX_OUTPUT // 2
    return text[:half] + f"\n... [{len(text) - MAX_OUTPUT} chars cut] ...\n" + text[-half:]


class Workspace:
    def __init__(self, root: Path, task_id: int, github_token: str):
        self.dir = root / f"task-{task_id}"
        self.task_id = task_id
        self._token = github_token
        self.repo: str | None = None
        self.dir.mkdir(parents=True, exist_ok=True)

    # ---------- helpers ----------
    def _path(self, rel: str) -> Path:
        p = (self.dir / rel).resolve()
        if not str(p).startswith(str(self.dir.resolve())):
            raise ValueError(f"path escapes workspace: {rel}")
        return p

    def _git(self, *args: str, timeout: int = 300) -> str:
        r = subprocess.run(["git", *args], cwd=self.dir / "repo", capture_output=True, text=True,
                           timeout=timeout, env={"PATH": os.environ.get("PATH", "/usr/bin:/bin"),
                                                 "GIT_TERMINAL_PROMPT": "0", "HOME": str(self.dir)})
        if r.returncode != 0:
            raise RuntimeError(_clip(r.stderr.replace(self._token, "***") if self._token else r.stderr))
        return r.stdout

    # ---------- tools exposed to the agent ----------
    def clone_repo(self, repo: str, branch: str = "") -> str:
        if "/" not in repo or repo.count("/") != 1:
            return "error: repo must look like owner/name"
        target = self.dir / "repo"
        if target.exists():
            shutil.rmtree(target)
        url = f"https://x-access-token:{self._token}@github.com/{repo}.git"
        args = ["git", "clone", "--depth", "50", url, str(target)]
        if branch:
            args[2:2] = ["--branch", branch]
        r = subprocess.run(args, capture_output=True, text=True, timeout=300,
                           env={"PATH": os.environ.get("PATH", ""), "GIT_TERMINAL_PROMPT": "0"})
        if r.returncode != 0:
            return "error: " + _clip(r.stderr.replace(self._token, "***"))
        self._git("remote", "set-url", "origin", f"https://github.com/{repo}.git")
        self._git("config", "user.name", "Metatron Core")
        self._git("config", "user.email", "core@metatron.local")
        self.repo = repo
        return f"cloned {repo} into ./repo"

    def list_dir(self, path: str = "repo") -> str:
        p = self._path(path)
        if not p.is_dir():
            return f"error: not a directory: {path}"
        out = []
        for child in sorted(p.iterdir()):
            if child.name == ".git":
                continue
            out.append(child.name + ("/" if child.is_dir() else ""))
        return "\n".join(out[:300]) or "(empty)"

    def read_file(self, path: str) -> str:
        p = self._path(path)
        if not p.is_file():
            return f"error: no such file: {path}"
        return _clip(p.read_text(errors="replace"))

    def write_file(self, path: str, content: str) -> str:
        p = self._path(path)
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)
        return f"wrote {len(content)} chars to {path}"

    def replace_in_file(self, path: str, old: str, new: str) -> str:
        p = self._path(path)
        if not p.is_file():
            return f"error: no such file: {path}"
        text = p.read_text(errors="replace")
        count = text.count(old)
        if count != 1:
            return f"error: 'old' must occur exactly once, found {count} times"
        p.write_text(text.replace(old, new))
        return f"replaced 1 occurrence in {path}"

    def run(self, command: str, timeout: int = 600) -> str:
        env = {k: os.environ[k] for k in SAFE_ENV_KEYS if k in os.environ}
        env["HOME"] = str(self.dir)
        cwd = self.dir / "repo" if (self.dir / "repo").exists() else self.dir
        try:
            r = subprocess.run(["bash", "-lc", command], cwd=cwd, capture_output=True, text=True,
                               timeout=min(int(timeout), 1200), env=env)
        except subprocess.TimeoutExpired:
            return f"error: timed out after {timeout}s"
        return _clip(f"exit={r.returncode}\n--- stdout ---\n{r.stdout}\n--- stderr ---\n{r.stderr}")

    # ---------- used by core only, after the agent finishes ----------
    def open_pull_request(self, title: str, body: str) -> str:
        if not self.repo:
            raise RuntimeError("no repository cloned")
        if not self._git("status", "--porcelain").strip():
            raise RuntimeError("no changes to publish")
        base = self._git("rev-parse", "--abbrev-ref", "HEAD").strip()
        branch = f"metatron/task-{self.task_id}"
        self._git("checkout", "-B", branch)
        self._git("add", "-A")
        self._git("commit", "-m", title)
        self._git("push", "--force", f"https://x-access-token:{self._token}@github.com/{self.repo}.git",
                  f"{branch}:{branch}")
        req = urllib.request.Request(
            f"https://api.github.com/repos/{self.repo}/pulls",
            data=json.dumps({"title": title, "head": branch, "base": base, "body": body}).encode(),
            headers={"Authorization": f"Bearer {self._token}", "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read())["html_url"]

    def merge_pull_request(self, pr_url: str) -> None:
        number = pr_url.rstrip("/").split("/")[-1]
        owner_repo = "/".join(pr_url.split("github.com/")[1].split("/")[:2])
        req = urllib.request.Request(
            f"https://api.github.com/repos/{owner_repo}/pulls/{number}/merge",
            data=json.dumps({"merge_method": "squash"}).encode(), method="PUT",
            headers={"Authorization": f"Bearer {self._token}", "Accept": "application/vnd.github+json"})
        urllib.request.urlopen(req, timeout=60).read()


TOOL_SPEC = """
clone_repo(repo, branch?)          clone GitHub repo 'owner/name' into ./repo
list_dir(path)                     list a directory (paths are relative to the workspace, e.g. "repo/src")
read_file(path)                    read a file
write_file(path, content)          create or overwrite a file
replace_in_file(path, old, new)    replace one exact unique snippet in a file (prefer this for small edits)
run(command, timeout?)             run a bash command inside ./repo (build, test, grep, git diff...)
finish(summary, open_pr, pr_title?) end the task. open_pr=true publishes your changes as a pull request.
""".strip()
