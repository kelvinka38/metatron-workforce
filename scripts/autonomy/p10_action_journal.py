#!/usr/bin/env python3
"""Collect every durable action-journal row for one Objective."""

from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path


def collect(root: Path, objective_id: str) -> list[dict]:
    if not objective_id.strip():
        raise ValueError("objective_id_required")
    rows: list[dict] = []
    for path in sorted(root.rglob("*.jsonl")):
        if path.is_symlink() or not path.is_file():
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as failure:
                raise ValueError(f"invalid_action_journal_json:{path}:{number}") from failure
            if row.get("objectiveId") == objective_id:
                rows.append(row)
    if not rows:
        raise ValueError(f"objective_action_journal_empty:{objective_id}")
    return rows


def write_rows(rows: list[dict], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = "".join(json.dumps(row, separators=(",", ":"), sort_keys=True) + "\n" for row in rows)
    output.write_text(payload, encoding="utf-8")


def self_test() -> None:
    objective = "objective:target"
    other = "objective:other"
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / "step-1.jsonl").write_text(
            json.dumps({"objectiveId": objective, "cycle": 1, "thoughtAction": "workspace.repository.materialize"})
            + "\n"
            + json.dumps({"objectiveId": other, "cycle": 1, "thoughtAction": "ignored"})
            + "\n",
            encoding="utf-8",
        )
        nested = root / "nested"
        nested.mkdir()
        (nested / "step-2.jsonl").write_text(
            json.dumps({"objectiveId": objective, "cycle": 1, "thoughtAction": "workspace.test.run"}) + "\n",
            encoding="utf-8",
        )
        rows = collect(root, objective)
        assert [row["thoughtAction"] for row in rows] == [
            "workspace.test.run",
            "workspace.repository.materialize",
        ]
    print("P10_ACTION_JOURNAL_AGGREGATION_SELF_TEST=PASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path)
    parser.add_argument("--objective-id")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    if args.root is None or args.objective_id is None or args.output is None:
        parser.error("--root, --objective-id, and --output are required")
    write_rows(collect(args.root, args.objective_id), args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
