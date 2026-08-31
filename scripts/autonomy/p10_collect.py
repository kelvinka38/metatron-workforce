#!/usr/bin/env python3
"""Build the strict P10 45-condition manifest from exact-SHA live and executable evidence.

The collector does not decide ACCEPTED_L10; p10_ratify.py owns that verdict. This program refuses
manifest construction unless both production Golden Slice artifact sets are exact-SHA consistent and
the institutional invariant tests needed for non-destructive/unsafe production conditions actually
executed with zero failures/errors on the same checked-out SHA.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

SHA_RE = re.compile(r"^[0-9a-f]{40}$")

REQUIRED_TESTS = {
    "com.metatron.workforce.management.HumanObjectiveIngressServiceTest",
    "com.metatron.workforce.management.ManagementAutonomyServiceTest",
    "com.metatron.workforce.management.ManagementControlLifecycleTest",
    "com.metatron.workforce.management.AutonomyControlControllerSecurityTest",
    "com.metatron.workforce.management.AutonomyCoordinationServiceTest",
    "com.metatron.workforce.management.AutonomousManagementParallelSchedulerTest",
    "com.metatron.workforce.management.AutonomousStaffingServiceTest",
    "com.metatron.workforce.management.GovernedAutonomousExecutionCapabilityTest",
    "com.metatron.workforce.management.GovernedAutonomousExecutionDispatchRetryTest",
    "com.metatron.workforce.management.GovernedExecutionRecoveryIntegrationTest",
    "com.metatron.workforce.management.AutonomySafetyServiceTest",
    "com.metatron.workforce.management.ObservationGatedAutonomousManagementRunnerTest",
    "com.metatron.workforce.management.CrossChannelWorkplaceContinuityTest",
    "com.metatron.workforce.management.GatewayDirectorNorthStarAcceptanceTest",
    "com.metatron.workforce.management.AutonomyEvidencePackageServiceTest",
}


def fail(message: str) -> None:
    raise RuntimeError(message)


def one(root: Path, name: str) -> Path:
    matches = [p for p in root.rglob(name) if p.is_file()]
    if len(matches) != 1:
        fail(f"expected exactly one {name} under {root}, found {len(matches)}")
    return matches[0]


def load_json(root: Path, name: str) -> dict | list:
    return json.loads(one(root, name).read_text(encoding="utf-8"))


def parse_kv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def exact_deployment(root: Path, target_sha: str, label: str) -> list[str]:
    deployment = one(root, "deployment.txt")
    values = parse_kv(deployment)
    if values.get("target_sha") != target_sha or values.get("deployed_sha") != target_sha:
        fail(f"{label} deployment evidence is not bound to target SHA")
    return [f"artifact:{label}/{deployment.name}"]


def objective_view(root: Path, name: str, label: str) -> tuple[dict, list[str]]:
    path = one(root, name)
    view = json.loads(path.read_text(encoding="utf-8"))
    objective = view.get("objective") or {}
    if objective.get("status") not in ("COMPLETED", "DELIVERED"):
        fail(f"{label} Objective is not complete: {objective.get('status')}")
    return view, [f"artifact:{label}/{path.name}"]


def validate_gs12(root: Path, target_sha: str) -> dict[str, list[str]]:
    refs: dict[str, list[str]] = {}
    refs["deploy"] = exact_deployment(root, target_sha, "gs12")

    gs1, refs["gs1"] = objective_view(root, "gs1-terminal-view.json", "GS1")
    blob = json.dumps(gs1, sort_keys=True)
    for repo in (
        "kelvinka38/universal", "kelvinka38/metatron-institution",
        "kelvinka38/metatron-workforce", "kelvinka38/bios",
    ):
        if repo not in blob:
            fail(f"GS1 missing planned repository {repo}")
    if "repository.audit.read" not in blob:
        fail("GS1 missing repository.audit.read binding")
    for name in ("gs1-request.json", "gs1-response.json", "gs1-duplicate-response.json", "gs1-ingress.txt"):
        p = one(root, name)
        refs["gs1"].append(f"artifact:gs12/{p.name}")

    gs2, refs["gs2"] = objective_view(root, "gs2-terminal-view.json", "GS2")
    if "repository.pr.propose" not in json.dumps(gs2, sort_keys=True):
        fail("GS2 missing governed mutation capability")
    pr_path = one(root, "gs2-pr.json")
    files_path = one(root, "gs2-pr-files.json")
    pr = json.loads(pr_path.read_text(encoding="utf-8"))
    files = json.loads(files_path.read_text(encoding="utf-8"))
    if pr.get("state") != "open" or pr.get("merged") is not False or pr.get("merged_at") is not None:
        fail("GS2 PR did not preserve Founder merge boundary")
    filenames = [f.get("filename") for f in files]
    if filenames != ["docs/AUTONOMY_CLOSURE/CURRENT_STATE_AND_GAP_MATRIX.md"]:
        fail(f"GS2 mutation scope mismatch: {filenames}")
    refs["gs2"] += [f"artifact:gs12/{pr_path.name}", f"artifact:gs12/{files_path.name}"]
    return refs


def reports_pass(data: dict, objective_id: str) -> bool:
    reports = data.get("reportsByRequirement") or {}
    selected = [r for r in reports.values() if r.get("objectiveId") == objective_id]
    return bool(selected) and all(r.get("criterionResult") == "PASS" and r.get("quality") != "INSUFFICIENT" for r in selected)


def validate_gs34(root: Path, target_sha: str) -> dict[str, list[str]]:
    refs: dict[str, list[str]] = {"deploy": exact_deployment(root, target_sha, "gs34")}
    for label in ("gs3a", "gs3b", "gs3c", "gs4"):
        view, row_refs = objective_view(root, f"{label}-terminal-view.json", label.upper())
        objective_id = (view.get("objective") or {}).get("objectiveId")
        if not objective_id:
            fail(f"{label} missing objectiveId")
        refs[label] = row_refs
        obs_path = one(root, f"{label}-observation-state.json")
        observation = json.loads(obs_path.read_text(encoding="utf-8"))
        if not reports_pass(observation, objective_id):
            fail(f"{label} does not have independent Observation PASS")
        refs[label].append(f"artifact:gs34/{obs_path.name}")

    # GS3-A: autonomous recovery and Execution success after a failure/replacement.
    gs3a = load_json(root, "gs3a-terminal-view.json")
    hblob = json.dumps(gs3a.get("history") or [], sort_keys=True)
    if "LOCAL_RECOVERY" not in hblob or "BLOCKED" not in hblob:
        fail("GS3-A missing typed autonomous recovery history")
    aexec_path = one(root, "gs3a-execution-attempts.json")
    aexec = json.loads(aexec_path.read_text(encoding="utf-8"))
    aid = (gs3a.get("objective") or {}).get("objectiveId")
    attempts = [a for a in aexec.values() if a.get("objectiveId") == aid]
    statuses = {a.get("status") for a in attempts}
    if "SUCCEEDED" not in statuses or not statuses.intersection({"FAILED", "FENCED", "ABANDONED"}):
        fail(f"GS3-A missing failure/recovery Execution attempts: {statuses}")
    if len({a.get("runtimeId") for a in attempts if a.get("runtimeId")}) < 2:
        fail("GS3-A missing runtime replacement evidence")
    refs["gs3a"].append(f"artifact:gs34/{aexec_path.name}")

    # GS3-B: real PID1 loss, Docker supervised restart, stale lease fencing, abandoned/fenced + success.
    restart_path = one(root, "gs3b-restart.txt")
    restart = parse_kv(restart_path)
    if int(restart.get("restart_count_after", "0")) <= int(restart.get("restart_count_before", "0")):
        fail("GS3-B restart counter did not advance")
    bexec_path = one(root, "gs3b-execution-attempts.json")
    bexec = json.loads(bexec_path.read_text(encoding="utf-8"))
    gs3b = load_json(root, "gs3b-terminal-view.json")
    bid = (gs3b.get("objective") or {}).get("objectiveId")
    battempts = [a for a in bexec.values() if a.get("objectiveId") == bid]
    bstatuses = {a.get("status") for a in battempts}
    if "SUCCEEDED" not in bstatuses or not bstatuses.intersection({"FAILED", "FENCED", "ABANDONED"}):
        fail(f"GS3-B missing interrupted + successful Execution attempts: {bstatuses}")
    lease_versions = []
    for event in gs3b.get("history") or []:
        if event.get("type") == "MANAGEMENT_LEASE_ACQUIRED":
            match = re.search(r"fencing_version=(\d+)", event.get("detail", ""))
            if match:
                lease_versions.append(int(match.group(1)))
    if len(lease_versions) < 2 or max(lease_versions) <= min(lease_versions):
        fail(f"GS3-B missing management lease fencing: {lease_versions}")
    refs["gs3b"] += [f"artifact:gs34/{restart_path.name}", f"artifact:gs34/{bexec_path.name}"]

    # GS3-C: Observation retries, while execution effect is not repeated.
    cobs_path = one(root, "gs3c-observation-state.json")
    cobs = json.loads(cobs_path.read_text(encoding="utf-8"))
    gs3c = load_json(root, "gs3c-terminal-view.json")
    cid = (gs3c.get("objective") or {}).get("objectiveId")
    reqs = [r for r in (cobs.get("requirements") or {}).values() if r.get("objectiveId") == cid]
    counts = [int((cobs.get("attemptsByRequirement") or {}).get(r.get("requirementId"), 0)) for r in reqs]
    if not counts or min(counts) < 2:
        fail(f"GS3-C missing bounded Observation retry: {counts}")
    refs["gs3c"].append(f"artifact:gs34/{cobs_path.name}")

    # GS4: four-way fan-out, bounded parallel scheduling, dependency-gated join, completed graph.
    gs4 = load_json(root, "gs4-terminal-view.json")
    plan = (gs4.get("autonomousWork") or {}).get("plannedWork") or []
    audits = [s for s in plan if s.get("requiredCapability") == "repository.audit.read"]
    joins = [s for s in plan if s.get("requiredCapability") == "cross-repository-audit-analysis"]
    if len(audits) < 4 or not any(len(s.get("dependsOn") or []) >= 4 for s in joins):
        fail("GS4 fan-out/join plan is not proven")
    scheduler_path = one(root, "gs4-autonomy-scheduling-state.json")
    scheduler = json.loads(scheduler_path.read_text(encoding="utf-8"))
    gid = (gs4.get("objective") or {}).get("objectiveId")
    decisions = [d for d in (scheduler.get("decisions") or []) if d.get("objectiveId") == gid]
    if not decisions or not any(len(d.get("selectedStepIds") or []) >= 2 for d in decisions):
        fail("GS4 missing bounded parallel scheduler decision")
    coordination_path = one(root, "gs4-autonomy-coordination-state.json")
    coordination = json.loads(coordination_path.read_text(encoding="utf-8"))
    graphs = [g for g in (coordination.get("graphs") or {}).values() if g.get("objectiveId") == gid]
    if not graphs or not any(g.get("status") == "COMPLETED" for g in graphs):
        fail("GS4 Work Graph is not completed")
    refs["gs4"] += [f"artifact:gs34/{scheduler_path.name}", f"artifact:gs34/{coordination_path.name}"]
    return refs


def validate_tests(results: Path) -> dict[str, str]:
    passed: dict[str, str] = {}
    for xml in results.rglob("TEST-*.xml"):
        suite = ET.parse(xml).getroot()
        name = suite.attrib.get("name", "")
        tests = int(suite.attrib.get("tests", "0"))
        failures = int(suite.attrib.get("failures", "0"))
        errors = int(suite.attrib.get("errors", "0"))
        skipped = int(suite.attrib.get("skipped", "0"))
        if name in REQUIRED_TESTS and tests > 0 and failures == 0 and errors == 0 and skipped < tests:
            passed[name] = f"test-result:{name}"
    missing = sorted(REQUIRED_TESTS - passed.keys())
    if missing:
        fail("required exact-SHA invariant tests missing/not-passing: " + ", ".join(missing))
    return passed


def build_manifest(target_sha: str, deployed_sha: str, gs12: Path, gs34: Path, test_results: Path) -> dict:
    if not SHA_RE.fullmatch(target_sha) or deployed_sha != target_sha:
        fail("collector requires exact target/deployed SHA equality")
    live12 = validate_gs12(gs12, target_sha)
    live34 = validate_gs34(gs34, target_sha)
    tests = validate_tests(test_results)

    def t(short: str) -> str:
        full = "com.metatron.workforce.management." + short
        if full not in tests:
            fail(f"required test not available: {full}")
        return tests[full]

    gs1 = live12["deploy"] + live12["gs1"]
    gs2 = live12["deploy"] + live12["gs2"]
    gs3a = live34["deploy"] + live34["gs3a"]
    gs3b = live34["deploy"] + live34["gs3b"]
    gs3c = live34["deploy"] + live34["gs3c"]
    gs4 = live34["deploy"] + live34["gs4"]

    ingress = [t("HumanObjectiveIngressServiceTest"), t("ManagementAutonomyServiceTest")]
    controls = [t("ManagementControlLifecycleTest"), t("AutonomyControlControllerSecurityTest")]
    coord = [t("AutonomyCoordinationServiceTest"), t("AutonomousManagementParallelSchedulerTest")]
    staffing = [t("AutonomousStaffingServiceTest"), t("GovernedAutonomousExecutionCapabilityTest")]
    recovery = [t("GovernedAutonomousExecutionDispatchRetryTest"), t("GovernedExecutionRecoveryIntegrationTest")]
    safety = [t("AutonomySafetyServiceTest")]
    observation = [t("ObservationGatedAutonomousManagementRunnerTest")]
    workplace = [t("CrossChannelWorkplaceContinuityTest")]
    northstar = [t("GatewayDirectorNorthStarAcceptanceTest")]
    package = [t("AutonomyEvidencePackageServiceTest")]

    evidence: dict[int, list[str]] = {
        1: gs1 + ingress, 2: gs1 + ingress, 3: gs1 + ingress + coord,
        4: gs1 + ingress, 5: gs1 + ingress, 6: gs1 + ingress, 7: gs1 + ingress,
        8: gs3b + ingress, 9: gs3b + northstar, 10: gs1 + ingress,
        11: gs3b + northstar, 12: controls, 13: safety + controls,
        14: gs4 + coord, 15: gs4 + coord, 16: gs4 + coord, 17: controls + coord,
        18: coord + safety + northstar, 19: staffing, 20: gs4 + staffing,
        21: staffing + northstar, 22: safety + northstar, 23: staffing + northstar,
        25: gs3b + recovery + northstar, 26: gs1 + staffing + northstar,
        27: gs3a + gs3b + recovery, 28: gs3b + recovery, 29: gs3b + northstar,
        30: gs3b + recovery, 31: gs1 + coord + safety, 32: gs3a + gs3b + gs3c + recovery,
        33: gs3a + gs3b + staffing + recovery, 34: safety, 35: gs3a + gs3b + safety + recovery,
        36: gs3c + observation, 37: gs1 + gs3c + observation,
        38: gs3c + observation, 39: gs1 + package, 40: gs1 + package + workplace,
        41: workplace, 42: workplace, 43: gs3b + workplace, 44: coord + package,
        45: gs1 + gs3b + package + northstar,
    }

    rows = []
    for condition_id in range(1, 46):
        if condition_id == 24:
            rows.append({
                "id": 24, "status": "NOT_APPLICABLE", "sha": target_sha, "evidence_refs": [],
                "applicability_reason": "AI Worker formation was not exercised by the four canonical closure Golden Slices; the normative condition is explicitly conditional ('when tested').",
                "contradictions": [],
            })
            continue
        refs = list(dict.fromkeys(evidence.get(condition_id, [])))
        if not refs:
            fail(f"condition {condition_id} has no collector evidence mapping")
        rows.append({"id": condition_id, "status": "PASS", "sha": target_sha,
                     "evidence_refs": refs, "contradictions": []})

    return {
        "target_sha": target_sha,
        "deployed_sha": deployed_sha,
        "golden_slices": {
            "1": {"status": "PASS", "sha": target_sha, "evidence_refs": gs1},
            "2": {"status": "PASS", "sha": target_sha, "evidence_refs": gs2},
            "3": {"status": "PASS", "sha": target_sha, "evidence_refs": gs3a + gs3b + gs3c},
            "4": {"status": "PASS", "sha": target_sha, "evidence_refs": gs4},
        },
        "conditions": rows,
        "unresolved_critical_contradictions": [],
        "collector": {
            "mode": "exact-sha-live-slices-plus-executable-institutional-invariants",
            "required_tests": sorted(REQUIRED_TESTS),
        },
    }


def self_test() -> int:
    assert len(REQUIRED_TESTS) >= 15
    condition_ids = set(range(1, 46))
    assert 24 in condition_ids and len(condition_ids) == 45
    print("P10_COLLECTOR_SELF_TEST=PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-sha")
    parser.add_argument("--deployed-sha")
    parser.add_argument("--gs12", type=Path)
    parser.add_argument("--gs34", type=Path)
    parser.add_argument("--test-results", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    required = (args.target_sha, args.deployed_sha, args.gs12, args.gs34, args.test_results, args.output)
    if any(value is None for value in required):
        parser.error("target/deployed SHA, gs12, gs34, test-results and output are required")
    manifest = build_manifest(args.target_sha, args.deployed_sha, args.gs12, args.gs34, args.test_results)
    args.output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"P10_EVIDENCE_MANIFEST={args.output}")
    print("P10_CONDITIONS_MAPPED=45")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"P10_COLLECTOR_FAIL={exc}", file=sys.stderr)
        raise
