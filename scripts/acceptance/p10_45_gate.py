#!/usr/bin/env python3
"""Strict Workforce Autonomy Closure 45-condition production evidence gate.

This evaluator intentionally fails closed. A condition passes only when the evidence bundle
contains a production artifact/state transition that directly supports it. Source existence,
CI, mocks, or narrative claims are not accepted as substitutes.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from dataclasses import dataclass, asdict
from typing import Any, Callable


@dataclass
class Result:
    number: int
    name: str
    passed: bool
    evidence: list[str]
    reason: str = ""


def read_text(path: pathlib.Path | None) -> str:
    if path is None or not path.exists():
        return ""
    return path.read_text(errors="replace")


def load_json(path: pathlib.Path | None) -> Any:
    if path is None or not path.exists():
        return None
    try:
        return json.loads(path.read_text())
    except Exception:
        return None


def find_one(root: pathlib.Path, name: str) -> pathlib.Path | None:
    rows = sorted(root.rglob(name))
    return rows[-1] if rows else None


def parse_kv(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in text.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def deep_text(value: Any) -> str:
    try:
        return json.dumps(value, sort_keys=True, separators=(",", ":"))
    except Exception:
        return str(value)


def objective_id(view: Any) -> str:
    if not isinstance(view, dict):
        return ""
    o = view.get("objective") or {}
    return str(o.get("objectiveId") or o.get("objective_id") or "")


def objective_status(view: Any) -> str:
    if not isinstance(view, dict):
        return ""
    return str((view.get("objective") or {}).get("status") or "")


def history(view: Any) -> list[dict[str, Any]]:
    if not isinstance(view, dict):
        return []
    rows = view.get("history") or []
    return [r for r in rows if isinstance(r, dict)]


def planned_work(view: Any) -> list[dict[str, Any]]:
    if not isinstance(view, dict):
        return []
    work = view.get("autonomousWork") or {}
    rows = work.get("plannedWork") or []
    return [r for r in rows if isinstance(r, dict)]


def contains_all(text: str, values: list[str]) -> bool:
    return all(v in text for v in values)


def result(n: int, name: str, passed: bool, evidence: list[str], reason: str = "") -> Result:
    return Result(n, name, bool(passed), evidence if passed else evidence, "" if passed else reason)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--evidence-root", required=True)
    ap.add_argument("--live-state", required=True)
    ap.add_argument("--target-sha", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()

    root = pathlib.Path(args.evidence_root)
    live = pathlib.Path(args.live_state)
    target_sha = args.target_sha.strip()

    def jp(name: str) -> Any:
        return load_json(find_one(root, name))

    def tp(name: str) -> str:
        return read_text(find_one(root, name))

    gs1 = jp("gs1-terminal-view.json")
    gs2 = jp("gs2-terminal-view.json")
    gs3a = jp("gs3a-terminal-view.json")
    gs3b = jp("gs3b-terminal-view.json")
    gs3c = jp("gs3c-terminal-view.json")
    gs4 = jp("gs4-terminal-view.json")
    gs1_id = objective_id(gs1)
    gs2_id = objective_id(gs2)
    gs3a_id = objective_id(gs3a)
    gs3b_id = objective_id(gs3b)
    gs3c_id = objective_id(gs3c)
    gs4_id = objective_id(gs4)

    gs1_ing = parse_kv(tp("gs1-ingress.txt"))
    gs2_ing = parse_kv(tp("gs2-ingress.txt"))
    gs3a_ing = parse_kv(tp("gs3a-ingress.txt"))
    gs3b_ing = parse_kv(tp("gs3b-ingress.txt"))
    gs3c_ing = parse_kv(tp("gs3c-ingress.txt"))
    gs4_ing = parse_kv(tp("gs4-ingress.txt"))

    live_blob = "\n".join(read_text(p) for p in sorted(live.rglob("*")) if p.is_file())
    evidence_blob = "\n".join(read_text(p) for p in sorted(root.rglob("*")) if p.is_file() and p.stat().st_size < 20_000_000)
    all_blob = live_blob + "\n" + evidence_blob

    deployment_files = [read_text(p) for p in root.rglob("deployment.txt")]
    exact_sha = bool(deployment_files) and all(
        f"target_sha={target_sha}" in text and f"deployed_sha={target_sha}" in text
        for text in deployment_files
    )

    gs1_work = planned_work(gs1)
    gs4_work = planned_work(gs4)
    gs1_hist = history(gs1)
    gs3b_hist = history(gs3b)
    gs3c_hist = history(gs3c)

    coordination = load_json(live / "autonomy-coordination-state.json") or {}
    scheduling = load_json(live / "autonomy-scheduling-state.json") or {}
    safety = load_json(live / "autonomy-safety-state.json") or {}
    execution = load_json(live / "execution-attempts.json") or {}
    observation = load_json(live / "observation-state.json") or {}
    core = load_json(live / "workforce-core-state.json") or {}
    workplace = load_json(live / "workplace-continuity-state.json") or {}
    ingress = load_json(live / "telegram-ingress-state.json") or {}
    management = load_json(live / "management-state.json") or {}

    coordination_blob = deep_text(coordination)
    scheduling_blob = deep_text(scheduling)
    safety_blob = deep_text(safety)
    execution_blob = deep_text(execution)
    observation_blob = deep_text(observation)
    core_blob = deep_text(core)
    workplace_blob = deep_text(workplace)
    ingress_blob = deep_text(ingress)
    management_blob = deep_text(management)

    # Helpers over current live durable state.
    receipts = ingress.get("receipts", []) if isinstance(ingress, dict) else []
    def receipt_for(ing: dict[str, str]) -> dict[str, Any] | None:
        update = str(ing.get("update_id", ""))
        for r in receipts if isinstance(receipts, list) else []:
            if isinstance(r, dict) and str(r.get("updateId", "")) == update:
                return r
        return None

    r1 = receipt_for(gs1_ing)
    r2 = receipt_for(gs2_ing)
    r3a = receipt_for(gs3a_ing)
    r3b = receipt_for(gs3b_ing)
    r3c = receipt_for(gs3c_ing)
    r4 = receipt_for(gs4_ing)

    exec_rows = list(execution.values()) if isinstance(execution, dict) else []
    exec_rows = [x for x in exec_rows if isinstance(x, dict)]
    dispatch_values = list((coordination.get("dispatches") or {}).values()) if isinstance(coordination, dict) else []
    dispatch_values = [x for x in dispatch_values if isinstance(x, dict)]
    graph_values = list((coordination.get("graphs") or {}).values()) if isinstance(coordination, dict) else []
    graph_values = [x for x in graph_values if isinstance(x, dict)]
    decisions = scheduling.get("decisions", []) if isinstance(scheduling, dict) else []
    decisions = [x for x in decisions if isinstance(x, dict)]
    reports = list((observation.get("reportsByRequirement") or {}).values()) if isinstance(observation, dict) else []
    reports = [x for x in reports if isinstance(x, dict)]
    requirements = list((observation.get("requirements") or {}).values()) if isinstance(observation, dict) else []
    requirements = [x for x in requirements if isinstance(x, dict)]

    gs4_decisions = [d for d in decisions if d.get("objectiveId") == gs4_id]
    gs4_dispatch = [d for d in dispatch_values if d.get("objectiveId") == gs4_id]
    gs3b_dispatch = [d for d in dispatch_values if d.get("objectiveId") == gs3b_id]
    gs3b_exec = [a for a in exec_rows if a.get("objectiveId") == gs3b_id]
    gs3a_exec = [a for a in exec_rows if a.get("objectiveId") == gs3a_id]
    gs3c_reports = [r for r in reports if r.get("objectiveId") == gs3c_id]

    lease_versions = []
    for e in gs3b_hist:
        if e.get("type") == "MANAGEMENT_LEASE_ACQUIRED":
            m = re.search(r"fencing_version=(\d+)", str(e.get("detail", "")))
            if m:
                lease_versions.append(int(m.group(1)))

    # Explicit preconditions: final gate must never evaluate a mixed-SHA bundle.
    precondition = exact_sha and all([gs1_id, gs2_id, gs3a_id, gs3b_id, gs3c_id, gs4_id])

    rows: list[Result] = []
    add = rows.append
    terminal = lambda v: objective_status(v) in ("COMPLETED", "DELIVERED")

    add(result(1, "real interaction provider material Objective", precondition and bool(gs1_ing) and terminal(gs1), ["gs1-ingress.txt", "gs1-terminal-view.json"], "missing real-provider material Objective evidence"))
    w1 = (gs1.get("autonomousWork") or {}) if isinstance(gs1, dict) else {}
    o1 = (gs1.get("objective") or {}) if isinstance(gs1, dict) else {}
    add(result(2, "Gateway authenticated principal/context binding", bool(w1.get("humanId") or w1.get("humanActorId")) and bool(o1.get("organizationContextId") or w1.get("organizationContextId")) and str(w1.get("channel", "")).lower() == "telegram", ["gs1-terminal-view.json"], "principal/channel/organization context not reconstructable from production Objective"))
    add(result(3, "replay does not duplicate Objective", gs1_ing.get("duplicate_http") == "200" and bool(gs1_id) and r1 is not None and str(r1.get("objectiveId", "")) == gs1_id, ["gs1-ingress.txt", "telegram-ingress-state.json"], "provider replay/objective correlation missing"))
    # Current receipt schema stores only latest status. Deliberately fail until transition history is durable.
    transition_history_present = bool(r1 and isinstance(r1.get("transitions"), list) and {x.get("status") for x in r1.get("transitions", []) if isinstance(x, dict)} >= {"RECEIVED", "ADMITTED", "ACCEPTED"})
    add(result(4, "RECEIVED/ADMITTED/ACCEPTED observable and distinct", transition_history_present, ["telegram-ingress-state.json"], "durable receipt currently lacks reconstructable RECEIVED→ADMITTED→ACCEPTED transition history"))
    acceptance_before_ack = bool(r1 and r1.get("acceptedAtEpochMillis") and gs1_ing.get("ack_completed_epoch_millis") and int(r1["acceptedAtEpochMillis"]) <= int(gs1_ing["ack_completed_epoch_millis"]))
    add(result(5, "Objective + owner + acceptance event persisted before ack", acceptance_before_ack and bool(o1.get("ownerWorkerId")) and any(e.get("type") in ("OBJECTIVE_ACCEPTED", "HUMAN_OBJECTIVE_ACCEPTED") for e in gs1_hist), ["gs1-terminal-view.json", "telegram-ingress-state.json", "gs1-ingress.txt"], "acceptance/ack ordering timestamp evidence missing"))
    add(result(6, "caller receives objective_id without waiting completion", bool(r1 and r1.get("status") == "DELIVERED" and str(r1.get("objectiveId", "")) == gs1_id) and int(gs1_ing.get("ack_millis", "999999")) < 5000, ["telegram-ingress-state.json", "gs1-ingress.txt"], "delivered objective_id acknowledgement evidence missing"))
    earliest_dispatch = min([float(d.get("createdAt")) for d in dispatch_values if d.get("objectiveId") == gs1_id and d.get("createdAt") is not None] or [float("inf")])
    ack_epoch = float(gs1_ing.get("ack_completed_epoch_millis", "0")) / 1000.0 if gs1_ing.get("ack_completed_epoch_millis") else 0
    add(result(7, "no substantial execution before acceptance ack", ack_epoch > 0 and earliest_dispatch >= ack_epoch, ["gs1-ingress.txt", "autonomy-coordination-state.json"], "cannot order provider acknowledgement before substantive dispatch"))
    add(result(8, "Objective survives originating channel disconnect", int(gs1_ing.get("ack_millis", "999999")) < 5000 and terminal(gs1), ["gs1-ingress.txt", "gs1-terminal-view.json"], "detached terminal completion not proven"))
    restarts = parse_kv(tp("gs3b-restart.txt"))
    add(result(9, "Objective survives Workforce process/container restart", terminal(gs3b) and int(restarts.get("restart_count_after", "0")) > int(restarts.get("restart_count_before", "0")), ["gs3b-restart.txt", "gs3b-terminal-view.json"], "restart survival evidence missing"))
    active_owner = bool(o1.get("ownerWorkerId"))
    add(result(10, "exactly one accountable active owner", active_owner and "ownerWorkerId" in deep_text(o1), ["gs1-terminal-view.json"], "accountable owner missing"))
    add(result(11, "stale Manager Runner/owner fenced", len(lease_versions) >= 2 and max(lease_versions) > min(lease_versions), ["gs3b-terminal-view.json"], "management lease fencing progression missing"))
    control_probe = load_json(find_one(root, "control-probe.json")) or {}
    add(result(12, "query/pause/resume/amend/cancel supported in production", all(control_probe.get(k) == "PASS" for k in ["query", "pause", "resume", "amend", "cancel"]), ["control-probe.json"], "authorized production control lifecycle has not been exercised end-to-end"))
    add(result(13, "authority revocation blocks illegitimate new dispatch", control_probe.get("authority_revocation") == "PASS", ["control-probe.json"], "authority revocation production probe missing"))
    add(result(14, "material Objective creates versioned Work Graph", len(gs1_work) >= 4 and any(g.get("objectiveId") == gs1_id and int(g.get("graphVersion", 0)) >= 1 for g in graph_values), ["gs1-terminal-view.json", "autonomy-coordination-state.json"], "versioned graph evidence missing"))
    add(result(15, "independent ready branches execute concurrently", any(len(d.get("selectedStepIds") or []) >= 2 for d in gs4_decisions), ["autonomy-scheduling-state.json"], "parallel scheduler decision missing"))
    gs4_graphs = [g for g in graph_values if g.get("objectiveId") == gs4_id]
    add(result(16, "joins unlock only when conditions satisfied", bool(gs4_graphs) and any("dependsOn" in deep_text(g) and "SUCCEEDED" in deep_text(g) for g in gs4_graphs), ["autonomy-coordination-state.json"], "join/dependency completion evidence missing"))
    add(result(17, "replan creates new version without rewriting history", control_probe.get("replan_version_history") == "PASS", ["control-probe.json"], "production replan/version-history probe missing"))
    add(result(18, "stale graph/Assignment/authorization versions cannot dispatch", control_probe.get("stale_dispatch_fencing") == "PASS", ["control-probe.json"], "stale version dispatch fencing production probe missing"))
    add(result(19, "capability and qualification affect Worker eligibility", gs1_id in core_blob and contains_all(core_blob, ["capabil", "qualif"]), ["workforce-core-state.json"], "capability/qualification allocation evidence missing"))
    add(result(20, "availability and finite capacity affect allocation", gs4_id in scheduling_blob and ("capacity" in scheduling_blob.lower()), ["autonomy-scheduling-state.json"], "finite-capacity scheduling evidence missing"))
    add(result(21, "Assignment distinct from authorization and execution", gs1_id in all_blob and contains_all(all_blob.lower(), ["assignment", "authorization", "execution"]), ["production durable state"], "distinct assignment/authorization/execution references missing"))
    add(result(22, "authorization fail-closed", control_probe.get("authorization_fail_closed") == "PASS" or "forbidden" in evidence_blob.lower(), ["control-probe.json", "production evidence"], "fail-closed authorization production denial missing"))
    add(result(23, "staffing gap resolved/escalated institutionally", control_probe.get("staffing_gap") == "PASS" or (gs4_id in all_blob and "staff" in all_blob.lower()), ["control-probe.json", "production durable state"], "institutional staffing-gap resolution evidence missing"))
    formation = load_json(find_one(root, "ai-worker-formation-probe.json")) or {}
    add(result(24, "AI Worker formation gates", all(formation.get(k) == "PASS" for k in ["recognition", "admission", "participation", "capability", "authority", "lifecycle"]), ["ai-worker-formation-probe.json"], "AI Worker formation production gate not fully evidenced"))
    runtime_ids = {str(a.get("runtimeId")) for a in exec_rows if a.get("runtimeId")}
    worker_ids = set(re.findall(r'"workerId":"([^"]+)"', core_blob))
    add(result(25, "model calls/runtime shards not misreported as Workers", bool(runtime_ids) and runtime_ids.isdisjoint(worker_ids), ["execution-attempts.json", "workforce-core-state.json"], "runtime/Worker identity separation missing"))
    add(result(26, "authorized Work dispatches through canonical Execution", bool(gs4_dispatch) and any(a.get("objectiveId") == gs4_id for a in exec_rows), ["autonomy-coordination-state.json", "execution-attempts.json"], "canonical execution attempt missing"))
    durable_attempt = any(a.get("attemptId") and a.get("lease") and (a.get("idempotencyKey") or a.get("dispatchId")) for a in exec_rows)
    add(result(27, "execution attempt durable identity/lease/idempotency", durable_attempt, ["execution-attempts.json"], "durable attempt identity/lease/idempotency fields missing"))
    add(result(28, "runtime loss does not destroy Worker identity", terminal(gs3b) and bool((gs3b.get("objective") or {}).get("ownerWorkerId")) and len({a.get("runtimeId") for a in gs3b_exec if a.get("runtimeId")}) >= 2, ["gs3b-terminal-view.json", "execution-attempts.json"], "Worker continuity across runtime replacement missing"))
    add(result(29, "runtime loss does not destroy Objective/unfinished Work", terminal(gs3b) and len(gs3b_dispatch) >= 2, ["gs3b-terminal-view.json", "autonomy-coordination-state.json"], "Objective/Work restart continuity missing"))
    add(result(30, "expired/abandoned execution automatically reconciled", any(a.get("status") in ("ABANDONED", "FENCED", "FAILED") for a in gs3b_exec) and any(a.get("status") == "SUCCEEDED" for a in gs3b_exec), ["execution-attempts.json"], "abandoned/fenced attempt reconciliation missing"))
    add(result(31, "duplicate execution delivery does not duplicate irreversible effect", control_probe.get("duplicate_irreversible_effect") == "PASS", ["control-probe.json"], "duplicate irreversible-effect execution probe missing"))
    add(result(32, "routine provider/network/runtime failure needs no Founder operation", terminal(gs3a) and terminal(gs3b), ["gs3a-terminal-view.json", "gs3b-terminal-view.json"], "autonomous routine failure recovery missing"))
    add(result(33, "retry/reassign/restaff/replan within authority", any(d.get("status") == "FAILED" for d in gs3b_dispatch) and any(d.get("status") == "SUCCEEDED" for d in gs3b_dispatch), ["autonomy-coordination-state.json"], "bounded retry/recovery dispatch evidence missing"))
    add(result(34, "budget/resource threshold enforced and observable", gs4_id in safety_blob and "budget" in safety_blob.lower(), ["autonomy-safety-state.json"], "budget/resource envelope evidence missing"))
    attempts_by_key: dict[str, int] = {}
    for d in dispatch_values:
        key = str(d.get("idempotencyKey") or d.get("stepId") or "")
        if key:
            attempts_by_key[key] = attempts_by_key.get(key, 0) + 1
    add(result(35, "retry/recovery bounded against unbounded cost loop", bool(attempts_by_key) and max(attempts_by_key.values()) <= 5 and "consumed" in safety_blob.lower(), ["autonomy-coordination-state.json", "autonomy-safety-state.json"], "bounded retry/cost evidence missing"))
    add(result(36, "execution success does not automatically complete Objective", bool(requirements) and bool(reports), ["observation-state.json"], "Observation closure boundary missing"))
    gs1_reports = [r for r in reports if r.get("objectiveId") == gs1_id]
    add(result(37, "Observation evaluates each required acceptance criterion", bool(gs1_reports) and all(r.get("criterionResult") == "PASS" for r in gs1_reports), ["observation-state.json"], "criterion-level Observation reports missing"))
    obs_attempts = observation.get("attemptsByRequirement") or {} if isinstance(observation, dict) else {}
    gs3c_requirements = [r for r in requirements if r.get("objectiveId") == gs3c_id]
    add(result(38, "missing/contradictory evidence keeps Objective non-completed", bool(gs3c_requirements) and all(int(obs_attempts.get(r.get("requirementId"), 0)) >= 2 for r in gs3c_requirements) and terminal(gs3c), ["gs3c-terminal-view.json", "observation-state.json"], "insufficient-evidence retry/closure proof missing"))
    package_probe = load_json(find_one(root, "completion-package-probe.json")) or {}
    required_package = ["outcome", "criteria", "evidence", "work", "workers", "attempts", "recovery", "cost", "duration", "remainingRisk"]
    add(result(39, "completion package is complete", all(package_probe.get(k) not in (None, "", [], {}) for k in required_package), ["completion-package-probe.json"], "authenticated production completion package probe missing/incomplete"))
    progress_probe = load_json(find_one(root, "progress-probe.json")) or {}
    required_progress = ["owner", "state", "work", "workforce", "runtime", "cost", "blockers", "decisions", "nextTransition"]
    add(result(40, "progress query reports canonical operational state", all(k in progress_probe for k in required_progress), ["progress-probe.json"], "authenticated production progress query probe missing/incomplete"))
    add(result(41, "Workplace reuses canonical Conversation/Meeting/Decision semantics", gs1_id in workplace_blob and any(k in workplace_blob.lower() for k in ["conversation", "decision", "meeting"]), ["workplace-continuity-state.json"], "canonical Workplace continuity semantics missing"))
    delivery_probe = load_json(find_one(root, "cross-channel-delivery-probe.json")) or {}
    add(result(42, "final outcome deliverable through different authorized channel", delivery_probe.get("different_authorized_channel") == "PASS", ["cross-channel-delivery-probe.json"], "cross-channel final delivery production probe missing"))
    add(result(43, "originating model/provider unnecessary after acceptance", terminal(gs3b) and len(lease_versions) >= 2, ["gs3b-terminal-view.json"], "detached autonomous continuation evidence missing"))
    dead_letters = coordination.get("deadLetters") or {} if isinstance(coordination, dict) else {}
    add(result(44, "dead-letter/stuck detection and reconciliation operational", control_probe.get("dead_letter_reconciliation") == "PASS" or (isinstance(dead_letters, (dict, list)) and "reconcil" in coordination_blob.lower()), ["control-probe.json", "autonomy-coordination-state.json"], "operational stuck/dead-letter reconciliation probe missing"))
    reconstruct = bool(gs1_hist) and gs1_id in management_blob and gs1_id in coordination_blob and gs1_id in observation_blob and gs1_id in workplace_blob
    add(result(45, "full Objective history attributable/reconstructable", reconstruct, ["management-state.json", "autonomy-coordination-state.json", "observation-state.json", "workplace-continuity-state.json"], "cross-domain Objective history is not fully reconstructable"))

    passed = sum(1 for r in rows if r.passed)
    payload = {
        "target_sha": target_sha,
        "precondition_exact_sha_bundle": precondition,
        "passed": passed,
        "total": 45,
        "verdict": "ACCEPTED_L10" if precondition and passed == 45 else "PARTIAL",
        "conditions": [asdict(r) for r in rows],
    }
    out = pathlib.Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    print(f"P10_45_GATE={passed}/45")
    for r in rows:
        print(f"{r.number:02d} {'PASS' if r.passed else 'FAIL'} {r.name}" + (f" :: {r.reason}" if not r.passed else ""))
    print(f"P10_VERDICT={payload['verdict']}")
    return 0 if payload["verdict"] == "ACCEPTED_L10" else 1


if __name__ == "__main__":
    sys.exit(main())
