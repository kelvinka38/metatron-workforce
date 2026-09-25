#!/usr/bin/env python3
"""Semantic fail-closed inventory for consequential mutation and completion surfaces."""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src/main/java"
OUT = ROOT / "docs/sot-enforcement/mutation-surface-inventory.json"

MUTATING_ACTION = re.compile(r'action\("([^"]+)"\s*,\s*ActionFabric\.Consequence\.MUTATING')
FILES_EFFECT = re.compile(r'Files\.(write|move|delete|deleteIfExists)\s*\(')
PROCESS_EFFECT = re.compile(r'new\s+ProcessBuilder\s*\(')
LIFECYCLE_EFFECTS = (
    "completeAutonomousObjective(",
    "completeGraph(",
    "transitionAssignment(",
)

# Filesystem writes in these boundaries are institutional persistence/evidence or isolated workspace state,
# not direct mutation of an external governed target. They remain inventoried, but do not independently
# require an ExecutionPermit because their caller/effect boundary owns the governing decision.
SYSTEM_STATE_NAMES = {
    "MetatronWorkforceApplication.java",
    "ActionJournal.java",
    "EvidenceWriter.java",
    "FileExecutionAttemptStore.java",
    "FileGovernanceStateStore.java",
    "DirectWorkerConversationBindingStore.java",
    "PersistentConversationSurfaceModeStore.java",
    "TelegramIngressReceiptStore.java",
    "PersistentIntelligenceCaseStore.java",
    "PersistentIntelligenceDepthPreferenceStore.java",
    "PersistentConversationMemoryStore.java",
    "PersistentWorkerConversationMemoryStore.java",
    "PersistentCognitiveArtifactStore.java",
    "FounderWorkerWorkProductStore.java",
    "FileAutonomyCoordinationStateStore.java",
    "FileAutonomySafetyStateStore.java",
    "FileAutonomySchedulingStateStore.java",
    "FileManagementStateStore.java",
    "FileObservationStateStore.java",
    "FileWorkerConstitutionRuntimeStateStore.java",
    "FileWorkerConstitutionStateStore.java",
    "FileStaffingStateStore.java",
    "FileWorkScheduleStateStore.java",
    "FileReviewStateStore.java",
    "FileRuntimePersistenceStore.java",
    "FileWorkStateStore.java",
    "FileWorkplaceContinuityStateStore.java",
    "PersistentMeetingStore.java",
    "WorkplaceWorkerChatStore.java",
    "WorkerRuntimeProfileBindingService.java",
    "WorkerResourceScopeService.java",
}

LOCAL_WORKSPACE_NAMES = {
    "GeneralWorkspaceActionCatalog.java",
    "ObjectiveWorkspaceService.java",
    "ExecutionWorkspaceManager.java",
    "RepositoryWorkspaceMaterializationService.java",
    "AutonomyRecoveryProbeCapability.java",
    "CrossRepositoryAuditAnalysisCapability.java",
    "WorkerRuntime.java",
}

COMPLETION_OWNERS = {
    "AutonomousManagementRunner.java": "CompletionGate->AutonomyCoordinationService",
    "AutonomyCoordinationService.java": "CompletionGate",
    "ManagementAutonomyService.java": "Management lifecycle after completed governed graph",
}

ASSIGNMENT_OWNERS = {
    "AutonomousManagementRunner.java": "ObservationClosureService->Workforce Core lifecycle",
    "GovernedAutonomousExecutionCapability.java": "Assignment/Authorization/Execution admission",
    "WorkforceCoreService.java": "Workforce Core lifecycle",
    "WorkforceCoreController.java": "Workforce Core authorized ingress",
}


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


JAVA_COMMENT_OR_LITERAL = re.compile(
    r'(?P<text_block>""".*?""")'
    r'|(?P<string>"(?:\\.|[^"\\])*")'
    r"|(?P<char>'(?:\\.|[^'\\])*')"
    r'|(?P<line>//[^\r\n]*)'
    r'|(?P<block>/\*.*?\*/)',
    re.DOTALL,
)


def strip_java_comments(text: str) -> str:
    """Strip Java line/block/Javadoc comments without treating comment markers in literals as comments."""
    def replace(match: re.Match[str]) -> str:
        token = match.group(0)
        if match.lastgroup not in {"line", "block"}:
            return token
        return "".join(char if char in "\r\n" else " " for char in token)

    return JAVA_COMMENT_OR_LITERAL.sub(replace, text)

def has_github_proposal_publish_call(text: str) -> bool:
    # Avoid treating comments such as `GitHubWorkspaceProposalPublisher.publish()` as effects.
    # Extract concrete variables/parameters typed as the publisher, then require an invocation on
    # one of those identifiers. This stays fail-closed for real publisher calls without lexical
    # false positives from documentation/comments.
    code = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    code = re.sub(r"//[^\n]*", "", code)
    names = set(re.findall(r"\bGitHubWorkspaceProposalPublisher\s+([A-Za-z_$][A-Za-z0-9_$]*)", code))
    return any(re.search(rf"\b{re.escape(name)}\.publish\s*\(", code) for name in names)


def row(kind: str, path: Path, symbol: str, owner: str, gate_required: bool, known: bool = True) -> dict:
    return {
        "kind": kind,
        "path": rel(path),
        "symbol": symbol,
        "owner": owner,
        "gateRequired": gate_required,
        "known": known,
    }


def classify_file_effect(path: Path, symbol: str) -> dict:
    name = path.name
    if name in SYSTEM_STATE_NAMES or name.startswith("File") and name.endswith("Store.java"):
        return row("SYSTEM_STATE", path, symbol, "institutional durable state/evidence store", False)
    if name in LOCAL_WORKSPACE_NAMES:
        if name == "GeneralWorkspaceActionCatalog.java":
            owner = "ExecutionGate->ActionFabric"
        elif name == "ExecutionWorkspaceManager.java":
            owner = "ExecutionAttempt->isolated Execution workspace"
        elif name == "ObjectiveWorkspaceService.java":
            owner = "ExecutionWorkspaceManager compatibility facade"
        else:
            owner = "isolated Objective/runtime workspace"
        return row("LOCAL_WORKSPACE", path, symbol, owner, name == "GeneralWorkspaceActionCatalog.java")
    return row("UNKNOWN_HIGH_RISK_FILESYSTEM_EFFECT", path, symbol, "UNDECLARED", True, False)


def classify(path: Path, text: str) -> list[dict]:
    rows: list[dict] = []

    # Concrete Worker tool mutations are always governed at ExecutionGate -> ActionFabric.
    for match in MUTATING_ACTION.finditer(text):
        rows.append(row("GOVERNED_ACTION_EFFECT", path, match.group(1), "ExecutionGate->ActionFabric", True))

    # Direct filesystem/process side effects remain visible even when they are internal state or scratch-work effects.
    for match in FILES_EFFECT.finditer(text):
        rows.append(classify_file_effect(path, "Files." + match.group(1) + "("))
    if PROCESS_EFFECT.search(text):
        if path.name in {"WorkerExecutionSandboxService.java", "WorkerRuntime.java"}:
            rows.append(row("LOCAL_PROCESS_EFFECT", path, "ProcessBuilder(", "isolated Worker sandbox/runtime", False))
        elif path.name in {"HostCommanderAutonomousCapability.java", "WorkerHousekeepingCapability.java"}:
            rows.append(row("GOVERNED_HOST_COMMANDER_TRANSPORT", path, "ProcessBuilder(",
                            "GovernedAutonomousExecutionCapability->ExecutionGate->Host Commander privileged broker", True))
        else:
            rows.append(row("UNKNOWN_HIGH_RISK_PROCESS_EFFECT", path, "ProcessBuilder(", "UNDECLARED", True, False))

    # Institutional terminal/lifecycle surfaces are tracked separately from raw persistence.
    # Scan executable Java text only: lifecycle method names in comments/Javadocs are documentation,
    # not mutation surfaces. Real code remains fail-closed through the same owner classification below.
    lifecycle_text = strip_java_comments(text)
    for token in LIFECYCLE_EFFECTS:
        if token not in lifecycle_text:
            continue
        if token == "completeAutonomousObjective(":
            owner = COMPLETION_OWNERS.get(path.name)
            rows.append(row("OBJECTIVE_COMPLETION_TRANSITION", path, token, owner or "UNDECLARED", True, owner is not None))
        elif token == "completeGraph(":
            owner = COMPLETION_OWNERS.get(path.name)
            rows.append(row("GRAPH_COMPLETION_TRANSITION", path, token, owner or "UNDECLARED", True, owner is not None))
        else:
            owner = ASSIGNMENT_OWNERS.get(path.name)
            rows.append(row("ASSIGNMENT_LIFECYCLE_TRANSITION", path, token, owner or "UNDECLARED", True, owner is not None))

    # Explicit external publication is a governed effect even though implementation is delegated to a publisher service.
    if has_github_proposal_publish_call(text):
        owner = "ExecutionGate->ActionFabric" if path.name == "GeneralWorkspaceActionCatalog.java" else "UNDECLARED"
        rows.append(row("GITHUB_PUBLICATION_EFFECT", path, "GitHubWorkspaceProposalPublisher.publish(", owner, True,
                        owner != "UNDECLARED"))

    return rows


def main() -> int:
    rows: list[dict] = []
    for path in sorted(SRC.rglob("*.java")):
        rows.extend(classify(path, path.read_text(encoding="utf-8")))

    # Exact duplicates add noise and hide the actual number of effect boundaries.
    unique: list[dict] = []
    seen: set[tuple] = set()
    for item in rows:
        key = (item["kind"], item["path"], item["symbol"], item["owner"])
        if key in seen:
            continue
        seen.add(key)
        unique.append(item)
    rows = unique

    unknown = [item for item in rows if not item["known"]]
    payload = {
        "schema": "metatron.sot-enforcement.mutation-surface.v2",
        "generatedBy": "scripts/sot_enforcement/inventory.py",
        "classification": {
            "GOVERNED_ACTION_EFFECT": "External/material Worker effect requiring ExecutionPermit",
            "SYSTEM_STATE": "Institutional persistence/evidence mutation owned by its governing caller",
            "LOCAL_WORKSPACE": "Isolated Objective/runtime workspace mutation",
            "OBJECTIVE_COMPLETION_TRANSITION": "Institutional Objective terminal transition",
            "GRAPH_COMPLETION_TRANSITION": "Durable Work graph terminal transition",
        },
        "surfaces": rows,
        "unknownCount": len(unknown),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")

    counts: dict[str, int] = {}
    for item in rows:
        counts[item["kind"]] = counts.get(item["kind"], 0) + 1
    print("SOT_MUTATION_SURFACE_COUNTS=" + json.dumps(counts, sort_keys=True))
    print(f"SOT_MUTATION_SURFACES={len(rows)}")
    print(f"SOT_UNKNOWN_HIGH_RISK_SURFACES={len(unknown)}")
    if unknown:
        for item in unknown:
            print(f"UNKNOWN {item['kind']} {item['path']} {item['symbol']}")
        return 1
    print("SOT_MUTATION_SURFACE_INVENTORY=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
