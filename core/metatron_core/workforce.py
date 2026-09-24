"""Workforce Core (W1): persistent Workers, their roles, reporting lines, capabilities and capacity,
and the Assignments that make a Worker responsible for a task.

Follows metatron-institution/05_WORKFORCE: a Worker is an institutional identity, not a model or a
runtime. Core's agent run is only the execution unit a Worker uses; Gemini, OpenRouter and Ollama are
only computation. Every Objective (task) has exactly one accountable owner, the Manager.
"""
from __future__ import annotations

import json
import re

ORGANIZATION = "Metatron Engineering"
MANAGER = "head-of-engineering"

# The founder-approved first roster (Rebuild Plan, "W - Workforce layer on Core", 24 Sep 2026).
ROSTER = [
    {"id": MANAGER, "name": "Head of Engineering", "role": "Manager",
     "purpose": "Owns every Objective: plans, assigns, recovers and closes it",
     "reports_to": "founder", "capabilities": ["plan", "review", "coordinate"], "capacity": 3},
    {"id": "software-engineer-a", "name": "Software Engineer A", "role": "Software Engineer",
     "purpose": "Implements Work in code", "reports_to": MANAGER,
     "capabilities": ["code", "python", "javascript", "java", "web"], "capacity": 1},
    {"id": "software-engineer-b", "name": "Software Engineer B", "role": "Software Engineer",
     "purpose": "Implements Work in code", "reports_to": MANAGER,
     "capabilities": ["code", "python", "javascript", "java", "web"], "capacity": 1},
    {"id": "qa-engineer", "name": "QA Engineer", "role": "QA Engineer",
     "purpose": "Verifies Work against its acceptance criteria", "reports_to": MANAGER,
     "capabilities": ["verify", "test"], "capacity": 1},
    {"id": "research-analyst", "name": "Research Analyst", "role": "Research Analyst",
     "purpose": "Research and decision analysis", "reports_to": MANAGER,
     "capabilities": ["research", "analysis"], "capacity": 1},
]

RESEARCH_WORDS = re.compile(
    r"\b(research|investigate|analy[sz]e|analysis|compare|survey|decision|market|report on)\b"
    r"|nghiên cứu|phân tích|so sánh|khảo sát|đánh giá thị trường", re.IGNORECASE)
CODE_WORDS = re.compile(r"\b(fix|implement|build|create|add|code|test|refactor|repo|bug|deploy|api|page|app)\b"
                        r"|[\w.-]+/[\w.-]+", re.IGNORECASE)


def required_capability(request: str) -> str:
    """Which capability the task's Work needs. Research only when it reads as research, not as code."""
    if RESEARCH_WORDS.search(request) and not CODE_WORDS.search(request):
        return "research"
    return "code"


def seed(conn) -> None:
    """Create the roster once; later runs keep each Worker's identity and history."""
    for w in ROSTER:
        conn.execute(
            "INSERT OR IGNORE INTO workers(id, name, role, purpose, organization, reports_to, capabilities,"
            " capacity, status, kind) VALUES (?,?,?,?,?,?,?,?, 'active', 'ai')",
            (w["id"], w["name"], w["role"], w["purpose"], ORGANIZATION, w["reports_to"],
             json.dumps(w["capabilities"]), w["capacity"]))


def pick_worker(workers: list[dict], load: dict[str, int], capability: str) -> dict | None:
    """The least-loaded active Worker who has the capability and free capacity (ties: roster order)."""
    order = {w["id"]: i for i, w in enumerate(ROSTER)}
    eligible = [w for w in workers
                if w["status"] == "active" and capability in w["capabilities"]
                and load.get(w["id"], 0) < w["capacity"]]
    eligible.sort(key=lambda w: (load.get(w["id"], 0), order.get(w["id"], 99), w["id"]))
    return eligible[0] if eligible else None


def persona(worker: dict | None) -> str:
    """Who the agent run is working for, told to the model at the start of the run."""
    if not worker:
        return ""
    return (f"You are working as {worker['name']} ({worker['role']}, {ORGANIZATION}), "
            f"accountable to the Head of Engineering. Your job: {worker['purpose'].lower()}.")
