"""Tools the agent can call. All file/shell work is confined to one per-task workspace directory.

Secrets never enter the agent's reach:
  * in the container, every agent command and file tool runs as the task's own Unix user
    (CORE_AGENT_UID_BASE + task id), so it cannot read Core's /proc/1/environ, /data/core.db or
    another task's workspace;
  * shell commands also run with a scrubbed environment (no API keys, no GitHub token);
  * the GitHub token is only handed to git by core code, through the environment of a process the
    agent user cannot inspect - never in a URL, argv or .git/config;
  * before core touches the repo to publish, it kills the task's processes and replaces
    .git/config, so agent-set hooks or filters never run with the token around.
"""
from __future__ import annotations

import base64
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from . import fileops, research
from .fileops import clip as _clip

SAFE_ENV_KEYS = ("PATH", "LANG", "LC_ALL", "JAVA_HOME", "GRADLE_USER_HOME", "PIP_CACHE_DIR")
# Passed to the child as source, so the agent user needs no read access to Core's code.
FILEOPS_SOURCE = Path(fileops.__file__).read_text()
REPO_NAME = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")


def agent_uid_for(task_id: int) -> int | None:
    """The task's own Unix user, when Core runs as root with CORE_AGENT_UID_BASE set (the container)."""
    base = os.environ.get("CORE_AGENT_UID_BASE", "")
    if not base or os.geteuid() != 0:
        return None
    return int(base) + int(task_id)


def _github_api(method: str, url: str, token: str, body: dict | None) -> dict:
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(), method=method,
                                 headers={"Authorization": f"Bearer {token}",
                                          "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read() or b"{}")


def _github_text(url: str, token: str) -> str:
    """A plain-text GitHub download (job logs). The token is not forwarded to the storage redirect."""
    req = urllib.request.Request(url)
    req.add_unredirected_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode(errors="replace")


GUIDANCE_FILES = ("AGENTS.md", "CONTRIBUTING.md", "README.md")
# Build and test leftovers never belong in a PR (only files new since the clone are dropped).
JUNK = re.compile(r"(^|/)(__pycache__|\.pytest_cache|\.mypy_cache|node_modules|\.gradle)(/|$)|\.py[co]$|(^|/)\.DS_Store$")
CI_OK = ("success", "skipped", "neutral")


def _pr_api(pr_url: str) -> str:
    number = pr_url.rstrip("/").split("/")[-1]
    owner_repo = "/".join(pr_url.split("github.com/")[1].split("/")[:2])
    return f"https://api.github.com/repos/{owner_repo}/pulls/{number}"


def merge_pull_request(pr_url: str, token: str) -> None:
    _github_api("PUT", _pr_api(pr_url) + "/merge", token, {"merge_method": "squash"})


def close_pull_request(pr_url: str, token: str) -> None:
    _github_api("PATCH", _pr_api(pr_url), token, {"state": "closed"})


def _killpg(pgid: int) -> bool:
    """SIGKILL a process group; True if anything was still in it."""
    try:
        os.killpg(pgid, signal.SIGKILL)
        return True
    except ProcessLookupError:
        return False


class Workspace:
    def __init__(self, root: Path, task_id: int, github_token: str, agent_uid: int | None = None):
        self.dir = root / f"task-{task_id}"
        self.task_id = task_id
        self.uid = agent_uid
        self._token = github_token
        self.remote_base = "https://github.com"
        self.repo: str | None = None
        self.base_branch = ""
        self.base_sha = ""
        self.head_sha = ""
        self.written: set[str] = set()   # repo-relative files the agent wrote on purpose
        self.left_out: list[str] = []    # new files found at publish but not written on purpose
        self.allow_public = False         # set when the founder's request itself asks for a public repo
        self._sleep = time.sleep
        self.dir.mkdir(parents=True, exist_ok=True)
        if self.uid is not None:
            os.chmod(root, 0o711)                 # traversable, not listable
            os.chown(self.dir, self.uid, self.uid)
            os.chmod(self.dir, 0o700)

    # ---------- helpers ----------
    @property
    def base(self) -> Path:
        """Where paths and commands start: the repo once one is cloned or created, else the workspace."""
        return self.dir / "repo" if self.repo else self.dir

    def _path(self, rel: str) -> Path:
        rel = str(rel).strip()
        if self.repo:  # models often still write "repo/x" or "./repo/x"; both mean the repo root
            for prefix in ("./repo/", "repo/"):
                if rel.startswith(prefix):
                    rel = rel[len(prefix):]
            if rel in ("repo", "./repo"):
                rel = "."
        p = (self.base / rel).resolve()
        if not p.is_relative_to(self.dir.resolve()):
            raise ValueError(f"path escapes workspace: {rel}")
        return p

    def _as_agent(self) -> dict:
        if self.uid is None:
            return {}
        return {"user": self.uid, "group": self.uid, "extra_groups": []}

    def _git(self, *args: str, as_agent: bool = False, auth: bool = False, cwd: Path | None = None,
             timeout: int = 300) -> str:
        config = [("core.hooksPath", "/dev/null"), ("safe.directory", "*")]
        if auth and self._token:
            basic = base64.b64encode(f"x-access-token:{self._token}".encode()).decode()
            config.append(("http.https://github.com/.extraheader", f"AUTHORIZATION: basic {basic}"))
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "GIT_TERMINAL_PROMPT": "0",
               "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null",
               "HOME": str(self.dir) if as_agent else "/nonexistent",
               "GIT_CONFIG_COUNT": str(len(config))}
        for i, (key, value) in enumerate(config):
            env[f"GIT_CONFIG_KEY_{i}"], env[f"GIT_CONFIG_VALUE_{i}"] = key, value
        r = subprocess.run(["git", *args], cwd=cwd or self.dir / "repo", capture_output=True, text=True,
                           timeout=timeout, env=env, **(self._as_agent() if as_agent else {}))
        if r.returncode != 0:
            err = r.stderr.replace(self._token, "***") if self._token else r.stderr
            raise RuntimeError(_clip(err))
        return r.stdout

    def _kill_agent_procs(self) -> None:
        """Stop anything the agent left running before core works on its files."""
        if self.uid is None:
            return
        for _ in range(3):
            found = False
            for status in Path("/proc").glob("[0-9]*/status"):
                try:
                    uid_line = next(l for l in status.read_text().splitlines() if l.startswith("Uid:"))
                    if int(uid_line.split()[1]) == self.uid:
                        os.kill(int(status.parent.name), signal.SIGKILL)
                        found = True
                except (OSError, StopIteration, ValueError):
                    continue
            if not found:
                return

    def _give_to_agent(self, path: Path) -> None:
        if self.uid is None:
            return
        os.lchown(path, self.uid, self.uid)
        for d, dirs, files in os.walk(path):
            for name in dirs + files:
                os.lchown(os.path.join(d, name), self.uid, self.uid)

    def _file_op(self, op: str, rel: str, **args) -> str:
        args = {"path": str(self._path(rel)), "shown": rel, **args}
        if self.uid is None:
            return fileops.OPS[op](**args)
        r = subprocess.run([sys.executable, "-c", FILEOPS_SOURCE], cwd=self.dir,
                           input=json.dumps({"op": op, "args": args}), capture_output=True, text=True,
                           timeout=60, env={"PATH": os.environ.get("PATH", ""), "HOME": str(self.dir)},
                           **self._as_agent())
        return r.stdout or "error: " + _clip(r.stderr.strip())

    # ---------- tools exposed to the agent ----------
    def clone_repo(self, repo: str, branch: str = "") -> str:
        if not REPO_NAME.fullmatch(repo):
            return "error: repo must look like owner/name"
        self._kill_agent_procs()
        target = self.dir / "repo"
        if target.is_symlink() or target.is_file():
            target.unlink()
        elif target.exists():
            shutil.rmtree(target)
        args = ["clone", "--depth", "50", *(["--branch", branch] if branch else []),
                "--", f"{self.remote_base}/{repo}.git", str(target)]
        try:
            self._git(*args, auth=True, cwd=self.dir)
            self._git("config", "user.name", "Metatron Core")
            self._git("config", "user.email", "core@metatron.local")
            self.base_branch = self._git("rev-parse", "--abbrev-ref", "HEAD").strip()
            self.base_sha = self._git("rev-parse", "HEAD").strip()
        except RuntimeError as e:
            return f"error: {e}"
        guidance = self._guidance(target)  # read before the agent user owns (and could swap) the files
        self._give_to_agent(target)
        self.repo = repo
        return f"cloned {repo}; paths and commands now start at its root" + guidance

    @staticmethod
    def _guidance(repo_dir: Path) -> str:
        """The repo's own instructions for contributors, shown to the agent right after cloning (M2-1)."""
        found = [n for n in GUIDANCE_FILES if (repo_dir / n).is_file() and not (repo_dir / n).is_symlink()]
        if not found:
            return "\nNo AGENTS.md, CONTRIBUTING.md or README.md at the repo root."
        text = (repo_dir / found[0]).read_text(errors="replace")[:3000]
        others = f" Also present: {', '.join(found[1:])}." if found[1:] else ""
        return f"\nRepository guidance from {found[0]} (follow it):{others}\n---\n{text}\n---"

    def create_repo(self, name: str, private: bool = True) -> str:
        """Create a new repo on the token owner's account, starting from a README so the normal
        branch + PR + /approve flow works, then clone it. Always private unless the founder's own
        request says public (allow_public), whatever the model passes."""
        private = bool(private) or not self.allow_public
        name = name.split("/")[-1].strip()
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,100}", name):
            return "error: repo name may only use letters, digits, '.', '_' and '-'"
        owner = _github_api("GET", "https://api.github.com/user", self._token, None)["login"]
        try:
            _github_api("POST", "https://api.github.com/user/repos", self._token,
                        {"name": name, "private": bool(private), "auto_init": True})
        except urllib.error.HTTPError as e:
            if e.code != 422:  # 422: it already exists; then just clone it
                return f"error: could not create {owner}/{name}: HTTP {e.code}"
        self._sleep(2)  # the new repo takes a moment to accept clones
        return self.clone_repo(f"{owner}/{name}")

    def list_dir(self, path: str = "repo") -> str:
        return self._file_op("list_dir", path)

    def read_file(self, path: str) -> str:
        return self._file_op("read_file", path)

    # Web tools run in Core's process, not as the agent user: they only return text, and only
    # public addresses are reachable (research.check_public).
    def web_search(self, query: str) -> str:
        return research.web_search(str(query))

    def fetch_url(self, url: str) -> str:
        return research.fetch_url(str(url))

    def report(self) -> str:
        """The task's report.md (research and analysis deliverable), read as the agent user."""
        for name in REPORT_NAMES:
            out = self._file_op("read_report", name)
            if out.strip() and not out.startswith("error: "):
                return out
        return ""

    def write_file(self, path: str, content: str) -> str:
        out = self._file_op("write_file", path, content=content)
        target = self._path(path)
        repo_root = (self.dir / "repo").resolve()
        if self.repo and target.is_relative_to(repo_root) and target != repo_root:
            self.written.add(target.relative_to(repo_root).as_posix())
        return out

    def replace_in_file(self, path: str, old: str, new: str) -> str:
        return self._file_op("replace_in_file", path, old=old, new=new)

    def run(self, command: str, timeout: int = 600) -> str:
        env = {k: os.environ[k] for k in SAFE_ENV_KEYS if k in os.environ}
        env["HOME"] = str(self.dir)
        env["PYTHONDONTWRITEBYTECODE"] = "1"
        timeout = min(int(timeout), 1200)
        # Same base as the file tools: the repo root once a repo is cloned or created.
        # Output goes to files, not pipes: a background process (`node server.js &`) would keep a
        # pipe open and the call would hang until the timeout although the shell already exited.
        with tempfile.TemporaryFile("w+") as out, tempfile.TemporaryFile("w+") as err:
            proc = subprocess.Popen(["bash", "-lc", command], cwd=self.base, stdout=out, stderr=err,
                                    stdin=subprocess.DEVNULL, text=True, env=env, start_new_session=True,
                                    **self._as_agent())
            try:
                proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                _killpg(proc.pid)
                proc.wait()
                return f"error: timed out after {timeout}s"
            note = ""
            if _killpg(proc.pid):
                note = ("\n[note] background processes this command started were stopped. Do not start "
                        "servers to check them: run the tests, or e.g. `timeout 15 node server.js & sleep 5; "
                        "curl -s localhost:PORT/...`. The founder sees the running app with /preview.")
            out.seek(0), err.seek(0)
            return _clip(f"exit={proc.returncode}\n--- stdout ---\n{out.read()}\n--- stderr ---\n{err.read()}") + note

    # ---------- used by core only, after the agent finishes ----------
    def _reset_git_config(self, git_dir: Path) -> None:
        """Drop everything the agent may have put in the repo's git config before core pushes."""
        for rel in ("config", "commondir", "objects/info/alternates", "objects/info/http-alternates"):
            (git_dir / rel).unlink(missing_ok=True)
        with open(git_dir / "config", "x") as f:
            f.write("[core]\n\trepositoryformatversion = 0\n\tbare = false\n"
                    "[user]\n\tname = Metatron Core\n\temail = core@metatron.local\n")

    def publish_branch(self, title: str) -> str:
        """Commit what the agent left (committed or not) and push it; returns the branch name."""
        if not self.repo:
            raise RuntimeError("no repository cloned")
        self._kill_agent_procs()
        repo_dir, git_dir = self.dir / "repo", self.dir / "repo" / ".git"
        if repo_dir.is_symlink() or git_dir.is_symlink() or not git_dir.is_dir():
            raise RuntimeError("repo/.git is not a plain directory")
        branch = f"metatron/task-{self.task_id}"
        # Commit as the agent: its own filters can only touch its own files.
        # Stage edits to tracked files and the new files the agent wrote on purpose. Other new files
        # are side effects of running things (test outputs, caches) and stay out of the PR.
        self._git("add", "-u", as_agent=True)
        untracked = self._git("ls-files", "--others", "--exclude-standard", as_agent=True).splitlines()
        wanted = [f for f in untracked if f in self.written]
        self.left_out = [f for f in untracked if f not in self.written]
        for i in range(0, len(wanted), 200):
            self._git("add", "--", *wanted[i:i + 200], as_agent=True)
        added = self._git("diff", "--cached", "--name-only", "--diff-filter=A", self.base_sha, as_agent=True)
        junk = [f for f in added.splitlines() if JUNK.search(f)]
        for i in range(0, len(junk), 200):
            self._git("rm", "-r", "-q", "--cached", "--", *junk[i:i + 200], as_agent=True)
        if self._git("diff", "--cached", "--name-only", as_agent=True).strip():
            self._git("commit", "-q", "-m", title, as_agent=True)
        if int(self._git("rev-list", "--count", f"{self.base_sha}..HEAD", as_agent=True)) == 0:
            raise RuntimeError("no changes to publish")
        self.head_sha = self._git("rev-parse", "HEAD", as_agent=True).strip()
        self._reset_git_config(git_dir)
        self._git("push", "--force", f"{self.remote_base}/{self.repo}.git", f"HEAD:refs/heads/{branch}",
                  auth=True)
        return branch

    def change_summary(self) -> str:
        """Files the PR changes, one line each with +/- counts (for the founder's report)."""
        try:
            stat = self._git("diff", "--numstat", self.base_sha, "HEAD", as_agent=True)
        except RuntimeError:
            return ""
        lines = []
        for row in stat.splitlines()[:20]:
            added, removed, path = (row.split("\t") + ["", "", ""])[:3]
            lines.append(f"• {path} (+{added} −{removed})")
        more = len(stat.splitlines()) - 20
        return "\n".join(lines) + (f"\n• … and {more} more files" if more > 0 else "")

    def open_pull_request(self, title: str, body: str) -> str:
        branch = self.publish_branch(title)
        if self.left_out:
            shown = "\n".join(f"- `{f}`" for f in self.left_out[:20])
            body += f"\n\nNew files left out (created while running commands, not written on purpose):\n{shown}"
        pr = _github_api("POST", f"https://api.github.com/repos/{self.repo}/pulls", self._token,
                         {"title": title, "head": branch, "base": self.base_branch, "body": body})
        return pr["html_url"]


    def wait_for_ci(self, timeout: float = 1200, poll: float = 30, grace: float = 120) -> tuple[str, str]:
        """Wait for the pushed commit's checks (M2-2): ('success'|'failure'|'none'|'timeout', details)."""
        start = time.time()
        while True:
            runs = _github_api("GET", f"https://api.github.com/repos/{self.repo}/commits/{self.head_sha}"
                                      "/check-runs?per_page=100", self._token, None).get("check_runs", [])
            waited = time.time() - start
            if runs and all(r.get("status") == "completed" for r in runs):
                bad = [r for r in runs if r.get("conclusion") not in CI_OK]
                return ("failure", self._ci_failures(bad)) if bad else ("success", "")
            if not runs and waited >= grace:
                return "none", ""
            if waited >= timeout:
                return "timeout", ""
            self._sleep(poll)

    def _ci_failures(self, runs: list[dict]) -> str:
        parts = []
        for r in runs[:3]:
            out = r.get("output") or {}
            part = f"## {r.get('name')}: {r.get('conclusion')}\n{(out.get('title') or '')}\n{(out.get('summary') or '')[:800]}"
            if (r.get("app") or {}).get("slug") == "github-actions":
                try:
                    log = _github_text(f"https://api.github.com/repos/{self.repo}/actions/jobs/{r['id']}/logs",
                                       self._token)
                    part += "\nLog tail:\n" + "\n".join(log.splitlines()[-80:])
                except Exception as e:
                    part += f"\n(log unavailable: {type(e).__name__})"
            parts.append(part)
        return _clip("\n\n".join(parts))


REPORT_NAMES = ("report.md", "REPORT.md")

TOOL_SPEC = """
clone_repo(repo, branch?)          clone GitHub repo 'owner/name'; afterwards you work in its root
create_repo(name, private?)        create a NEW repo on the founder's GitHub (private unless private=false)
                                   and clone it; only when the task asks for a new project
list_dir(path)                     list a directory; after clone_repo/create_repo every path is relative
                                   to the repository root (e.g. "src", "package.json")
read_file(path)                    read a file
web_search(query)                  search the web (Google via Gemini, else Bing/DuckDuckGo): titles and URLs
fetch_url(url)                     read a public web page as text (to check facts and get numbers)
write_file(path, content)          create or overwrite a file
replace_in_file(path, old, new)    replace one exact unique snippet in a file (prefer this for small edits)
run(command, timeout?)             run a bash command in the repository root (build, test, grep, git diff)
finish(summary, open_pr, pr_title?) end the task. open_pr=true publishes your changes as a pull request.
""".strip()
