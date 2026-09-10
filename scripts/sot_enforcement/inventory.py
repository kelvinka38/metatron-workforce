#!/usr/bin/env python3
"""Fail-closed static inventory for SoT enforcement mutation/completion surfaces."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src/main/java"
OUT = ROOT / "docs/sot-enforcement/mutation-surface-inventory.json"

MUTATING_ACTION = re.compile(r'action\("([^"]+)"\s*,\s*ActionFabric\.Consequence\.MUTATING')
DIRECT_MUTATION_TOKENS = (
    ".write(", "Files.write", "Files.move", "Files.delete", "deleteIfExists(",
    "transitionAssignment(", "completeAutonomousObjective(", "completeGraph(",
    "publish(", "replace(", "save(",
)
TERMINAL_TOKENS = ("completeAutonomousObjective(", "completeGraph(", "ExecutionState.COMPLETED", "Status.COMPLETED")

DECLARED_OWNERS = {
    "ActionFabric.java": "ActionFabric",
    "CognitiveWorkerRuntime.java": "ExecutionGate->ActionFabric",
    "GovernedAutonomousExecutionCapability.java": "GovernancePlan/AttemptBinding",
    "AutonomousManagementRunner.java": "CompletionGate-required",
    "ExecutionAttemptService.java": "ExecutionAttempt fencing",
    "FileExecutionAttemptStore.java": "ExecutionAttempt persistence",
    "FileGovernanceStateStore.java": "Governance persistence",
    "ManagementAutonomyService.java": "Management lifecycle",
    "AutonomyCoordinationService.java": "Durable work graph",
    "ObjectiveWorkspaceService.java": "Objective-isolated workspace",
    "GitHubWorkspaceProposalPublisher.java": "Review-only PR publication",
}


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def classify(path: Path, text: str) -> list[dict]:
    rows: list[dict] = []
    for match in MUTATING_ACTION.finditer(text):
        rows.append({
            "kind": "ACTION_FABRIC_MUTATION",
            "path": rel(path),
            "symbol": match.group(1),
            "owner": "ExecutionGate->ActionFabric",
            "gateRequired": True,
            "known": True,
        })
    for token in DIRECT_MUTATION_TOKENS:
        if token not in text:
            continue
        owner = DECLARED_OWNERS.get(path.name)
        rows.append({
            "kind": "DIRECT_MUTATION_SURFACE",
            "path": rel(path),
            "symbol": token,
            "owner": owner or "UNDECLARED",
            "gateRequired": path.name not in {"FileGovernanceStateStore.java", "FileExecutionAttemptStore.java"},
            "known": owner is not None,
        })
    for token in TERMINAL_TOKENS:
        if token in text:
            rows.append({
                "kind": "COMPLETION_SURFACE",
                "path": rel(path),
                "symbol": token,
                "owner": DECLARED_OWNERS.get(path.name, "UNDECLARED"),
                "gateRequired": True,
                "known": path.name in DECLARED_OWNERS,
            })
    return rows


def main() -> int:
    rows: list[dict] = []
    for path in sorted(SRC.rglob("*.java")):
        rows.extend(classify(path, path.read_text(encoding="utf-8")))
    unknown = [row for row in rows if not row["known"]]
    payload = {
        "schema": "metatron.sot-enforcement.mutation-surface.v1",
        "generatedBy": "scripts/sot_enforcement/inventory.py",
        "surfaces": rows,
        "unknownCount": len(unknown),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"SOT_MUTATION_SURFACES={len(rows)}")
    print(f"SOT_UNKNOWN_SURFACES={len(unknown)}")
    if unknown:
        for row in unknown:
            print(f"UNKNOWN {row['path']} {row['symbol']}")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
