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

One accepted Objective exercises the **general** engineering runtime, `execution.general.workspace`, against the exact deployed `kelvinka38/metatron-workforce` source SHA. It materializes the repository into the Objective workspace, performs a bounded non-fixture source edit through the general Action Fabric, runs the repository test suite after the edit, stages and commits the work product locally, and publishes that committed delta through `workspace.github.pr.publish`.

Remote publication is credential-isolated: the Worker sandbox never receives `GITHUB_TOKEN`. The Workforce JVM publishes only the clean committed Objective-workspace delta to an Objective-scoped `metatron/objective-*` proposal branch. The publisher requires the materialized source SHA to still equal the canonical default-branch SHA, binds the remote commit parent to that source SHA, limits the changed path set, opens an unmerged pull request, and exposes no merge action.

The harness then independently reruns tests and performs fresh GitHub reads. It requires the PR to be OPEN and UNMERGED, target `main`, have the exact Objective-scoped branch and remote commit reported by the general action, have the exact deployed source SHA as the remote commit parent, and contain exactly the source path changed by the Objective. A local-only commit or the historical hardcoded `repository.pr.propose` fixture cannot satisfy Golden Slice 2 or the L10 gate.

## What this stage does not prove

Passing Golden Slices 1 and 2 alone cannot produce `ACCEPTED_L10`. Golden Slice 3 failure recovery, Golden Slice 4 bounded elastic capacity, and the complete 45-condition production evidence matrix remain mandatory.

A failed slice is evidence of a real closure gap and must be repaired in product/runtime code rather than bypassed in the harness.
