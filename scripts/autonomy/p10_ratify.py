#!/usr/bin/env python3
"""Strict Workforce Autonomy P10 production-evidence ratifier.

This program does not create evidence. It consumes an independently assembled exact-SHA evidence
manifest and refuses ACCEPTED_L10 unless all four Golden Slices, all 45 production conditions, and
the GS2 general execution runtime closure are evidence-backed PASS with no unresolved contradiction.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

SHA_RE = re.compile(r"^[0-9a-f]{40}$")

CONDITIONS = {
    1: "Human submits a material Objective through a real interaction provider.",
    2: "Gateway binds the correct authenticated principal/context.",
    3: "Replayed submission does not create a duplicate Objective.",
    4: "RECEIVED, ADMITTED and ACCEPTED are observable and not conflated.",
    5: "Workforce persists Objective, exactly one owner and acceptance event before acknowledgement.",
    6: "Caller receives objective_id without waiting for completion under normal operation.",
    7: "No substantial Objective execution is required before acceptance acknowledgement.",
    8: "Objective survives originating channel disconnect.",
    9: "Objective survives Workforce process/JVM/container restart.",
    10: "Exactly one accountable active owner exists.",
    11: "Stale Manager Runner/owner is fenced.",
    12: "Objective supports query, pause, resume, amendment and cancellation.",
    13: "Authority revocation prevents illegitimate new dispatch.",
    14: "Material Objective creates a versioned Work Graph.",
    15: "Independent ready branches execute concurrently.",
    16: "Joins unlock only when their conditions are satisfied.",
    17: "Replan creates a new version without rewriting history.",
    18: "Stale graph/Assignment/authorization versions cannot dispatch.",
    19: "Capability and qualification affect Worker eligibility.",
    20: "Availability and finite capacity affect allocation.",
    21: "Assignment remains distinct from authorization and execution.",
    22: "Authorization remains fail-closed.",
    23: "A legitimate staffing gap is resolved or escalated through the institutional path without manual API orchestration.",
    24: "AI Worker formation, when tested, passes recognition/admission/participation/capability/authority/lifecycle gates.",
    25: "Model calls and runtime shards are not misreported as Workers.",
    26: "Authorized Work dispatches through canonical Execution.",
    27: "Execution attempt has durable identity, lease and idempotency protection.",
    28: "Runtime loss does not destroy Worker identity.",
    29: "Runtime loss does not destroy Objective or unfinished Work.",
    30: "Expired/abandoned execution is automatically reconciled.",
    31: "Duplicate execution delivery does not duplicate an irreversible effect.",
    32: "Routine provider/network/runtime failure does not require Founder operation.",
    33: "Workforce retries, reassigns, restaffs or replans within authority.",
    34: "Budget/resource threshold is enforced and observable.",
    35: "Retry/recovery is bounded and cannot create an unbounded cost loop.",
    36: "Execution success does not automatically complete Objective.",
    37: "Observation evaluates each required acceptance criterion.",
    38: "Missing/contradictory evidence keeps Objective non-completed.",
    39: "Completion package contains outcome, criteria results, evidence, Work, Workers, attempts, recovery, cost, duration and remaining risk.",
    40: "Progress query reports canonical owner, state, Work, Workforce, runtime, cost, blockers, decisions and next transition.",
    41: "Workplace reuses canonical Conversation/Meeting/Meeting Room/Decision semantics.",
    42: "Final outcome can be delivered through an authorized channel different from ingress.",
    43: "ChatGPT, Telegram and the originating model provider are unnecessary after acceptance.",
    44: "Dead-letter/stuck Objective detection and reconciliation are operational.",
    45: "Full Objective history is attributable and reconstructable.",
}


def _fail(errors: list[str], message: str) -> None:
    errors.append(message)


def ratify(manifest: dict) -> dict:
    errors: list[str] = []
    target_sha = str(manifest.get("target_sha", "")).strip().lower()
    deployed_sha = str(manifest.get("deployed_sha", "")).strip().lower()
    if not SHA_RE.fullmatch(target_sha):
        _fail(errors, "target_sha must be an exact 40-character lowercase Git SHA")
    if not SHA_RE.fullmatch(deployed_sha):
        _fail(errors, "deployed_sha must be an exact 40-character lowercase Git SHA")
    if target_sha and deployed_sha and target_sha != deployed_sha:
        _fail(errors, f"exact-SHA mismatch: target={target_sha} deployed={deployed_sha}")

    slices = manifest.get("golden_slices")
    if not isinstance(slices, dict):
        _fail(errors, "golden_slices object is required")
        slices = {}
    for slice_id in range(1, 5):
        row = slices.get(str(slice_id), slices.get(slice_id))
        if not isinstance(row, dict):
            _fail(errors, f"Golden Slice {slice_id} evidence row missing")
            continue
        if row.get("status") != "PASS":
            _fail(errors, f"Golden Slice {slice_id} is not PASS")
        refs = row.get("evidence_refs")
        if not isinstance(refs, list) or not any(str(ref).strip() for ref in refs):
            _fail(errors, f"Golden Slice {slice_id} has no attributable evidence_refs")
        if row.get("sha") != target_sha:
            _fail(errors, f"Golden Slice {slice_id} is not bound to target_sha")
        if slice_id == 2:
            if row.get("scope") != "GENERAL_MUTATING_EXECUTION_RUNTIME":
                _fail(errors, "Golden Slice 2 does not prove GENERAL_MUTATING_EXECUTION_RUNTIME scope")
            if not isinstance(refs, list) or "artifact:gs12/gs2-general-runtime.txt" not in refs:
                _fail(errors, "Golden Slice 2 missing general runtime proof artifact")

    general_runtime = manifest.get("general_execution_runtime")
    if not isinstance(general_runtime, dict):
        _fail(errors, "general_execution_runtime proof is required")
        general_runtime = {}
    if general_runtime.get("status") != "PASS":
        _fail(errors, "general_execution_runtime is not PASS")
    if general_runtime.get("sha") != target_sha:
        _fail(errors, "general_execution_runtime is not bound to target_sha")
    if general_runtime.get("capability") != "execution.general.workspace":
        _fail(errors, "general_execution_runtime capability mismatch")
    if general_runtime.get("repository") != "kelvinka38/metatron-workforce":
        _fail(errors, "general_execution_runtime repository mismatch")
    if general_runtime.get("source_commit_sha") != target_sha:
        _fail(errors, "general_execution_runtime source repository is not exact target SHA")
    if not SHA_RE.fullmatch(str(general_runtime.get("local_git_commit", ""))):
        _fail(errors, "general_execution_runtime local Git commit is missing")
    if general_runtime.get("remote_publication") != "PASS":
        _fail(errors, "general_execution_runtime remote publication is not PASS")
    if not re.fullmatch(r"https://github\.com/kelvinka38/metatron-workforce/pull/[1-9][0-9]*",
                        str(general_runtime.get("remote_pull_request", ""))):
        _fail(errors, "general_execution_runtime remote pull request is missing")
    if not str(general_runtime.get("remote_branch", "")).startswith("metatron/objective-"):
        _fail(errors, "general_execution_runtime remote branch is not Objective-scoped")
    if not SHA_RE.fullmatch(str(general_runtime.get("remote_commit", ""))):
        _fail(errors, "general_execution_runtime remote commit is missing")
    if general_runtime.get("remote_changed_path") != "src/main/java/com/metatron/workforce/action/GeneralCognitiveWorkerBrainFactory.java":
        _fail(errors, "general_execution_runtime remote changed path is not the non-fixture coding acceptance target")
    runtime_refs = general_runtime.get("evidence_refs")
    if not isinstance(runtime_refs, list) or "artifact:gs12/gs2-general-runtime.txt" not in runtime_refs:
        _fail(errors, "general_execution_runtime has no strict proof reference")

    supplied = manifest.get("conditions")
    if not isinstance(supplied, list):
        _fail(errors, "conditions must be a list")
        supplied = []
    rows: dict[int, dict] = {}
    for row in supplied:
        if not isinstance(row, dict):
            _fail(errors, "condition row must be an object")
            continue
        try:
            condition_id = int(row.get("id"))
        except (TypeError, ValueError):
            _fail(errors, "condition row has invalid id")
            continue
        if condition_id not in CONDITIONS:
            _fail(errors, f"unknown condition id: {condition_id}")
            continue
        if condition_id in rows:
            _fail(errors, f"duplicate condition id: {condition_id}")
            continue
        rows[condition_id] = row

    normalized_rows: list[dict] = []
    for condition_id, statement in CONDITIONS.items():
        row = rows.get(condition_id)
        if row is None:
            _fail(errors, f"condition {condition_id} missing")
            normalized_rows.append({"id": condition_id, "statement": statement, "status": "UNPROVEN"})
            continue
        status = str(row.get("status", "")).strip().upper()
        refs = row.get("evidence_refs")
        contradictions = row.get("contradictions", [])
        if not isinstance(contradictions, list):
            _fail(errors, f"condition {condition_id} contradictions must be a list")
            contradictions = [str(contradictions)]
        live_contradictions = [str(x).strip() for x in contradictions if str(x).strip()]
        if live_contradictions:
            _fail(errors, f"condition {condition_id} has unresolved contradiction")

        if status != "PASS":
            _fail(errors, f"condition {condition_id} is not PASS: {status or 'UNPROVEN'}")
        if not isinstance(refs, list) or not any(str(ref).strip() for ref in refs):
            _fail(errors, f"condition {condition_id} PASS has no evidence_refs")
        if row.get("sha") != target_sha:
            _fail(errors, f"condition {condition_id} PASS is not bound to target_sha")

        normalized_rows.append({
            "id": condition_id,
            "statement": statement,
            "status": status or "UNPROVEN",
            "sha": row.get("sha", ""),
            "evidence_refs": refs if isinstance(refs, list) else [],
            "contradictions": live_contradictions,
        })

    unresolved = manifest.get("unresolved_critical_contradictions", [])
    if not isinstance(unresolved, list):
        _fail(errors, "unresolved_critical_contradictions must be a list")
        unresolved = [str(unresolved)]
    unresolved = [str(x).strip() for x in unresolved if str(x).strip()]
    if unresolved:
        _fail(errors, "manifest contains unresolved critical contradictions")

    passed_conditions = sum(1 for row in normalized_rows if row.get("status") == "PASS")
    accepted = not errors and passed_conditions == 45
    return {
        "scope": "METATRON_WORKFORCE_INSTITUTIONAL_AUTONOMY",
        "target_sha": target_sha,
        "deployed_sha": deployed_sha,
        "golden_slices_passed": sum(
            1 for i in range(1, 5)
            if isinstance(slices.get(str(i), slices.get(i)), dict)
            and slices.get(str(i), slices.get(i)).get("status") == "PASS"
        ),
        "general_execution_runtime_passed": general_runtime.get("status") == "PASS" and not any(
            "general_execution_runtime" in error or "Golden Slice 2" in error for error in errors
        ),
        "conditions_accounted": len(rows),
        "conditions_passed": passed_conditions,
        "conditions": normalized_rows,
        "unresolved_critical_contradictions": unresolved,
        "verdict": "ACCEPTED_L10" if accepted else "NOT_ACCEPTED_L10",
        "errors": errors,
    }


def self_test() -> int:
    sha = "a" * 40
    local_sha = "c" * 40
    remote_sha = "d" * 40
    good = {
        "target_sha": sha,
        "deployed_sha": sha,
        "golden_slices": {
            str(i): {"status": "PASS", "sha": sha, "evidence_refs": [f"artifact:gs{i}"]}
            for i in range(1, 5)
        },
        "general_execution_runtime": {
            "status": "PASS", "sha": sha, "capability": "execution.general.workspace",
            "repository": "kelvinka38/metatron-workforce", "source_commit_sha": sha,
            "local_git_commit": local_sha,
            "remote_publication": "PASS",
            "remote_pull_request": "https://github.com/kelvinka38/metatron-workforce/pull/999",
            "remote_branch": "metatron/objective-abc123def456-cccccccc",
            "remote_commit": remote_sha,
            "remote_changed_path": "src/main/java/com/metatron/workforce/action/GeneralCognitiveWorkerBrainFactory.java",
            "evidence_refs": ["artifact:gs12/gs2-general-runtime.txt"],
        },
        "conditions": [
            {"id": i, "status": "PASS", "sha": sha, "evidence_refs": [f"evidence:c{i}"],
             "contradictions": []}
            for i in range(1, 46)
        ],
        "unresolved_critical_contradictions": [],
    }
    good["golden_slices"]["2"]["scope"] = "GENERAL_MUTATING_EXECUTION_RUNTIME"
    good["golden_slices"]["2"]["evidence_refs"].append("artifact:gs12/gs2-general-runtime.txt")
    result = ratify(good)
    assert result["verdict"] == "ACCEPTED_L10", result
    assert result["conditions_passed"] == 45
    assert result["general_execution_runtime_passed"] is True

    broken = json.loads(json.dumps(good))
    broken["golden_slices"]["2"].pop("scope")
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    broken = json.loads(json.dumps(good))
    broken["general_execution_runtime"]["source_commit_sha"] = "b" * 40
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    broken = json.loads(json.dumps(good))
    broken["general_execution_runtime"]["remote_publication"] = "NONE"
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    broken = json.loads(json.dumps(good))
    broken["general_execution_runtime"]["remote_changed_path"] = "docs/AUTONOMY_CLOSURE/GS2_GENERAL_RUNTIME_PROOF.md"
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    broken = json.loads(json.dumps(good))
    broken["conditions"][23]["status"] = "NOT_APPLICABLE"
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    broken = json.loads(json.dumps(good))
    broken["conditions"][33]["evidence_refs"] = []
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    broken = json.loads(json.dumps(good))
    broken["deployed_sha"] = "b" * 40
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    broken = json.loads(json.dumps(good))
    broken["golden_slices"]["3"]["status"] = "FAIL"
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    broken = json.loads(json.dumps(good))
    broken["conditions"][0]["contradictions"] = ["contradiction"]
    assert ratify(broken)["verdict"] == "NOT_ACCEPTED_L10"
    print("P10_RATIFIER_SELF_TEST=PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("manifest", nargs="?", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.manifest is None:
        parser.error("manifest is required unless --self-test is used")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    result = ratify(manifest)
    rendered = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.write_text(rendered, encoding="utf-8")
    else:
        sys.stdout.write(rendered)
    return 0 if result["verdict"] == "ACCEPTED_L10" else 2


if __name__ == "__main__":
    raise SystemExit(main())
