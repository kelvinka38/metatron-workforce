# WORKFORCE AUTONOMY P10 — STRICT PRODUCTION ACCEPTANCE METHOD

Status: ACTIVE — EXECUTION HARNESS

This document records how P10 evidence is generated. It does not claim an acceptance verdict.

## Principle

The production harness may inject a Human-shaped request through the deployed public interaction adapter and may observe/query production state. It must not drive Objective state transitions, create Assignments, complete Work, mark Observation, recover a failure, or otherwise substitute test orchestration for Workforce autonomy.

The canonical chain under test remains:

`interaction -> accept/persist/detach -> own -> plan -> staff -> assign -> authorize -> schedule -> execute -> observe -> recover/replan -> close -> deliver`

Any transition after acceptance must be performed by the production Workforce runtime itself.

## Golden Slice 1

One accepted Objective asks Workforce to audit the four canonical repositories. The harness requires one durable Objective, one accountable owner, a Workforce-planned four-repository Work Graph, governed repository audit capability binding, terminal completion, runtime effect evidence for all four repositories and independent Observation evidence.

Duplicate delivery of the same provider event is replayed to test submission idempotency. The interaction acknowledgement is timed to ensure acceptance detaches from substantive execution.

## Golden Slice 2

One accepted Objective asks Workforce to repair the approved stale Autonomy Closure gap matrix and open a pull request. The only allowed mutation capability is `repository.pr.propose`, bounded to `kelvinka38/metatron-workforce` and `docs/AUTONOMY_CLOSURE/CURRENT_STATE_AND_GAP_MATRIX.md`.

The harness independently re-reads GitHub and requires the generated PR to be OPEN and UNMERGED, target `main`, use the bounded `autonomy/gs2-*` branch and modify exactly one approved path. There is no merge action in the capability. Founder approval remains required before merge.

## What this stage does not prove

Passing Golden Slices 1 and 2 alone cannot produce `ACCEPTED_L10`. Golden Slice 3 failure recovery, Golden Slice 4 bounded elastic capacity, and the complete 45-condition production evidence matrix remain mandatory.

A failed slice is evidence of a real closure gap and must be repaired in product/runtime code rather than bypassed in the harness.
