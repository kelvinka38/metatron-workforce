#!/usr/bin/env python3
"""Upgrade a collected P10 manifest only after strict GS2 general mutating execution proof.

A local-only workspace or a bounded hardcoded PR proposer is insufficient. GS2 must prove the generic
execution.general.workspace path from source materialization through edit/test/commit and credential-isolated
remote proposal publication, followed by fresh independent Observation. p10_ratify.py refuses ACCEPTED_L10
without this annotation.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

SHA_RE = re.compile(r"^[0-9a-f]{40}$")


def fail(message: str) -> None:
    raise RuntimeError(message)


def one(root: Path, name: str) -> Path:
    matches = [p for p in root.rglob(name) if p.is_file()]
    if len(matches) != 1:
        fail(f"expected exactly one {name} under {root}, found {len(matches)}")
    return matches[0]


def parse_kv(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            out[key.strip()] = value.strip()
    return out


def guard(gs12: Path, manifest_path: Path, target_sha: str) -> dict:
    if not SHA_RE.fullmatch(target_sha):
        fail("target_sha must be exact lowercase Git SHA")
    proof_path = one(gs12, "gs2-general-runtime.txt")
    values = parse_kv(proof_path)
    required_pass = (
        "workspace_materialized", "cognitive_action_fabric", "source_edit_action", "test_action",
        "independent_test_rerun", "local_git_commit", "independent_observation", "sandbox_isolated",
        "remote_publication",
    )
    if values.get("general_capability") != "execution.general.workspace":
        fail("GS2 did not execute through execution.general.workspace")
    if values.get("repository") != "kelvinka38/metatron-workforce":
        fail("GS2 did not operate on the real canonical Workforce repository")
    if values.get("source_commit_sha") != target_sha:
        fail("GS2 materialized repository source is not exact deployed target SHA")
    for key in required_pass:
        if key == "local_git_commit":
            if not SHA_RE.fullmatch(values.get(key, "")):
                fail("GS2 local Git work product is missing an immutable commit SHA")
        elif values.get(key) != "PASS":
            fail(f"GS2 general mutating runtime proof missing {key}=PASS")

    if not re.fullmatch(r"https://github\.com/kelvinka38/metatron-workforce/pull/[1-9][0-9]*",
                        values.get("remote_pull_request", "")):
        fail("GS2 remote pull request URL is missing or outside the canonical Workforce repository")
    if not values.get("remote_branch", "").startswith("metatron/objective-"):
        fail("GS2 remote branch is not Objective-scoped")
    if not SHA_RE.fullmatch(values.get("remote_commit", "")):
        fail("GS2 remote commit is missing an immutable Git SHA")
    if values.get("remote_changed_path") != "src/main/java/com/metatron/workforce/action/GeneralCognitiveWorkerBrainFactory.java":
        fail("GS2 did not remotely mutate exactly the non-fixture general coding acceptance path")

    terminal_path = one(gs12, "gs2-terminal-view.json")
    terminal = json.loads(terminal_path.read_text(encoding="utf-8"))
    objective = terminal.get("objective") or {}
    if objective.get("status") not in ("COMPLETED", "DELIVERED"):
        fail("GS2 general runtime Objective is not terminal-complete")
    blob = json.dumps(terminal, sort_keys=True)
    if "execution.general.workspace" not in blob:
        fail("GS2 terminal Work did not bind the general workspace capability")

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("target_sha") != target_sha or manifest.get("deployed_sha") != target_sha:
        fail("manifest is not exact-SHA bound")
    slices = manifest.get("golden_slices") or {}
    row = slices.get("2")
    if not isinstance(row, dict) or row.get("status") != "PASS" or row.get("sha") != target_sha:
        fail("collector GS2 row is not PASS on exact target SHA")
    evidence_ref = "artifact:gs12/gs2-general-runtime.txt"
    refs = list(dict.fromkeys(list(row.get("evidence_refs") or []) + [evidence_ref]))
    row["evidence_refs"] = refs
    row["scope"] = "GENERAL_MUTATING_EXECUTION_RUNTIME"
    manifest["general_execution_runtime"] = {
        "status": "PASS",
        "sha": target_sha,
        "capability": "execution.general.workspace",
        "repository": values["repository"],
        "source_commit_sha": values["source_commit_sha"],
        "local_git_commit": values["local_git_commit"],
        "remote_publication": values["remote_publication"],
        "remote_pull_request": values["remote_pull_request"],
        "remote_branch": values["remote_branch"],
        "remote_commit": values["remote_commit"],
        "remote_changed_path": values["remote_changed_path"],
        "evidence_refs": [evidence_ref],
    }
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def self_test() -> int:
    assert SHA_RE.fullmatch("a" * 40)
    assert not SHA_RE.fullmatch("bounded-pr-is-not-general-runtime")
    print("P10_GENERAL_RUNTIME_GUARD_SELF_TEST=PASS")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--gs12", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--target-sha")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    if args.gs12 is None or args.manifest is None or args.target_sha is None:
        parser.error("--gs12, --manifest and --target-sha are required")
    guard(args.gs12, args.manifest, args.target_sha)
    print("P10_GS2_GENERAL_MUTATING_EXECUTION_SCOPE=PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
