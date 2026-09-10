#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = ROOT / ".github" / "workflows"
ALLOW_AUTOMATIC_SELF_HOSTED = {
    ".github/workflows/production-deploy.yml",
}
ALLOW_DIRECT_SELF_HOSTED_CONTROL = {
    ".github/workflows/highway-conformance-ci.yml",
    ".github/workflows/highway-execution-fabric-acceptance.yml",
    ".github/workflows/highway-queue-surface-audit.yml",
}
SINGLE_SELF_HOSTED_PR_GATE = ".github/workflows/highway-conformance-ci.yml"

def block_after_top_level(lines: list[str], key: str) -> list[str]:
    needle = key + ":"
    for i, line in enumerate(lines):
        if line.rstrip() == needle and not line.startswith((" ", "\t")):
            out = [line]
            for nxt in lines[i + 1:]:
                if nxt.strip() and not nxt.startswith((" ", "\t")):
                    break
                out.append(nxt)
            return out
    return []

def trigger_names(on_block: list[str]) -> set[str]:
    out: set[str] = set()
    for line in on_block[1:]:
        m = re.match(r"^  ([A-Za-z0-9_-]+):", line)
        if m:
            out.add(m.group(1))
    return out

def runner_values(text: str) -> list[str]:
    return [m.group(1).strip() for m in re.finditer(r"(?m)^\s+runs-on:\s*(.+)$", text)]

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--report-only", action="store_true")
    args = ap.parse_args()

    violations: list[str] = []
    rows: list[tuple[str,set[str],list[str],bool]] = []

    for path in sorted(WORKFLOWS.glob("*.y*ml")):
        rel = str(path.relative_to(ROOT))
        text = path.read_text(encoding="utf-8")
        lines = text.splitlines()
        triggers = trigger_names(block_after_top_level(lines, "on"))
        runners = runner_values(text)
        self_hosted = any("self-hosted" in x for x in runners)
        automatic = bool(triggers & {"push","workflow_run","schedule","repository_dispatch"})
        rows.append((rel,triggers,runners,self_hosted))

        if self_hosted and automatic and rel not in ALLOW_AUTOMATIC_SELF_HOSTED:
            violations.append(
                f"AUTOMATIC_SELF_HOSTED {rel} triggers={sorted(triggers)} runners={runners}"
            )

        if self_hosted and "workflow_run" in triggers:
            violations.append(
                f"WORKFLOW_RUN_SELF_HOSTED {rel} must be manual ingress or Highway-native"
            )

        if self_hosted and "pull_request" in triggers and rel != SINGLE_SELF_HOSTED_PR_GATE:
            violations.append(
                f"MULTIPLE_SELF_HOSTED_PR_GATE {rel} must not compete with {SINGLE_SELF_HOSTED_PR_GATE}"
            )

        if rel == SINGLE_SELF_HOSTED_PR_GATE:
            if "pull_request" not in triggers:
                violations.append("SELF_HOSTED_PR_GATE_MISSING pull_request trigger")
            if "cancel-in-progress: true" not in text:
                violations.append("SELF_HOSTED_PR_GATE_NO_STALE_CANCEL cancel-in-progress must be true")

        if self_hosted and "workflow_dispatch" in triggers and not automatic:
            is_highway_ingress = (
                "highwayctl.py" in text
                or "highway-manual-ingress.sh" in text
                or rel in ALLOW_DIRECT_SELF_HOSTED_CONTROL
            )
            if not is_highway_ingress:
                violations.append(
                    f"MANUAL_DIRECT_SELF_HOSTED {rel} must submit to Highway instead of executing on the Actions runner"
                )
            else:
                print(f"MANUAL_HIGHWAY_INGRESS {rel}")

    print("=== HIGHWAY QUEUE SURFACE ===")
    for rel,triggers,runners,self_hosted in rows:
        if self_hosted:
            print(f"SELF_HOSTED {rel} triggers={sorted(triggers)} runners={runners}")

    if violations:
        print("=== QUEUE SURFACE VIOLATIONS ===")
        for item in violations:
            print(item)
        print(f"HIGHWAY_QUEUE_SURFACE_VIOLATIONS={len(violations)}")
        if not args.report_only:
            return 1
    else:
        print("HIGHWAY_QUEUE_SURFACE_VIOLATIONS=0")
        print("HIGHWAY_SINGLE_AUTOMATIC_SELF_HOSTED_INGRESS=PASS")
        print("HIGHWAY_NO_DIRECT_MANUAL_SELF_HOSTED_EXECUTION=PASS")
        print("HIGHWAY_SINGLE_SELF_HOSTED_PR_GATE=PASS")
        print("HIGHWAY_STALE_PR_RUN_CANCELLATION=PASS")

    return 0

if __name__ == "__main__":
    raise SystemExit(main())
