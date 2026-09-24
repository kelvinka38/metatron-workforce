"""Control room data: tasks, the worker, agent processes, projects, models and the host, as JSON.

Read-only views over the store and /proc; actions go through the same commands as Telegram."""
from __future__ import annotations

import os
import shutil
import signal
import time
from pathlib import Path

ACTIVE = ("queued", "running", "cancelling", "checking_ci")
OK = ("done", "awaiting_approval", "merged")
PAID = ("anthropic", "openai")
CLK = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else 100


def _task_row(t: dict) -> dict:
    return {k: t.get(k) for k in ("id", "status", "request", "repo", "pr_url", "steps", "attempts",
                                  "created_at", "updated_at", "not_before", "result", "owner_id", "assignee_id")}


def processes(uid_base: int | None, proc_root: str = "/proc") -> list[dict]:
    """Every process running as a per-task agent user (uid = base + task id)."""
    if uid_base is None:
        return []
    out = []
    boot = _boot_time(proc_root)
    for status in Path(proc_root).glob("[0-9]*/status"):
        try:
            fields = dict(line.split(":", 1) for line in status.read_text().splitlines() if ":" in line)
            uid = int(fields["Uid"].split()[1])
            if uid <= uid_base:
                continue
            pid = int(status.parent.name)
            cmd = (status.parent / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace").strip()
            stat = (status.parent / "stat").read_text().rsplit(")", 1)[1].split()
            cpu = (int(stat[11]) + int(stat[12])) / CLK
            started = boot + int(stat[19]) / CLK if boot else 0
            out.append({"pid": pid, "task_id": uid - uid_base, "cmd": cmd[:300] or fields.get("Name", "").strip(),
                        "rss_mb": round(int(fields.get("VmRSS", "0 kB").split()[0]) / 1024, 1),
                        "cpu_s": round(cpu, 1), "started": started})
        except (OSError, KeyError, ValueError, IndexError):
            continue
    return sorted(out, key=lambda p: (p["task_id"], p["pid"]))


def kill_task_processes(task_id: int, uid_base: int | None, proc_root: str = "/proc") -> int:
    killed = 0
    for p in processes(uid_base, proc_root):
        if p["task_id"] == task_id:
            try:
                os.kill(p["pid"], signal.SIGKILL)
                killed += 1
            except OSError:
                pass
    return killed


def _boot_time(proc_root: str) -> float:
    try:
        for line in Path(proc_root, "stat").read_text().splitlines():
            if line.startswith("btime"):
                return float(line.split()[1])
    except OSError:
        pass
    return 0.0


def host(data_dir: Path, proc_root: str = "/proc") -> dict:
    info: dict = {}
    try:
        info["load"] = [float(x) for x in Path(proc_root, "loadavg").read_text().split()[:3]]
    except OSError:
        pass
    try:
        mem = {l.split(":")[0]: int(l.split()[1]) for l in Path(proc_root, "meminfo").read_text().splitlines()}
        info["mem_total_mb"] = mem["MemTotal"] // 1024
        info["mem_used_mb"] = (mem["MemTotal"] - mem.get("MemAvailable", mem.get("MemFree", 0))) // 1024
    except (OSError, KeyError, ValueError, IndexError):
        pass
    try:
        du = shutil.disk_usage(data_dir)
        info["disk_total_gb"], info["disk_used_gb"] = round(du.total / 2**30, 1), round(du.used / 2**30, 1)
    except OSError:
        pass
    info["cpus"] = os.cpu_count()
    return info


def projects(tasks: list[dict]) -> list[dict]:
    by_repo: dict[str, dict] = {}
    for t in tasks:
        repo = t.get("repo") or ""
        if not repo:
            continue
        p = by_repo.setdefault(repo, {"repo": repo, "tasks": 0, "ok": 0, "failed": 0, "active": 0,
                                      "open_prs": [], "last_activity": 0, "last_task": None})
        p["tasks"] += 1
        p["ok"] += t["status"] in OK
        p["failed"] += t["status"] == "failed"
        p["active"] += t["status"] in ACTIVE
        if t["status"] == "awaiting_approval" and t.get("pr_url"):
            p["open_prs"].append({"task_id": t["id"], "url": t["pr_url"]})
        if t["updated_at"] > p["last_activity"]:
            p["last_activity"], p["last_task"] = t["updated_at"], t["id"]
    return sorted(by_repo.values(), key=lambda p: -p["last_activity"])


def models(llm, calls: list[tuple]) -> dict:
    now = time.time()
    providers = [{"name": p.name, "model": getattr(p, "model", ""),
                  "available": p.available(), "cooldown_s": max(0, round(p.cooldown_until - now))}
                 for p in llm.providers]
    usage: dict[str, int] = {}
    for _, model in calls:
        usage[model] = usage.get(model, 0) + 1
    paid = sum(n for m, n in usage.items() if m.split(":")[0] in PAID)
    return {"providers": providers, "last_used": llm.last_used,
            "usage_7d": sorted(({"model": m, "calls": n} for m, n in usage.items()), key=lambda x: -x["calls"]),
            "paid_calls_7d": paid}


def snapshot(store, llm, preview, slots: list[dict], uid_base: int | None, data_dir: Path) -> dict:
    now = time.time()
    week = store.tasks_since(now - 7 * 86400)
    recent = store.recent(200)
    counts: dict[str, int] = {}
    for t in week:
        counts[t["status"]] = counts.get(t["status"], 0) + 1
    ok = sum(counts.get(s, 0) for s in OK)
    finished = ok + counts.get("failed", 0)
    procs = processes(uid_base)
    return {
        "now": now,
        "summary": {"tasks_7d": len(week), "success_rate": round(100 * ok / finished) if finished else None,
                    "by_status": counts,
                    "queued": sum(1 for t in recent if t["status"] == "queued"),
                    "running": sum(1 for t in recent if t["status"] in ("running", "cancelling")),
                    "awaiting_approval": sum(1 for t in recent if t["status"] == "awaiting_approval")},
        "workers": store.workers(),
        "runtime": [dict(s, slot=i + 1, busy=bool(s.get("task_id"))) for i, s in enumerate(slots)],
        "preview": {"task_id": preview.running_task(), "port": preview.port},
        "tasks": [_task_row(t) for t in recent],
        "processes": procs,
        "projects": projects(recent),
        "models": models(llm, store.llm_calls_since(now - 7 * 86400)),
        "host": host(data_dir),
    }


def task_detail(store, task_id: int, limit: int = 300) -> dict | None:
    task = store.get(task_id)
    if not task:
        return None
    return {"task": _task_row(task), "log": store.audit_tail(task_id, limit)}
