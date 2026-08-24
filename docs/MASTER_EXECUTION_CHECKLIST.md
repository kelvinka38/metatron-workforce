# MASTER_EXECUTION_CHECKLIST.md

## Status

CANONICAL EXECUTION REGISTER — RECONCILED 2026-08-24

This register governs the production-execution path from the frozen integration contract to certification. It is intentionally separate from the Workforce institutional phase numbering.

| ID | Phase | Task | Evidence Required | Status |
|---|---|---|---|---|
| ER-001 | Phase 0 | Freeze Execution Runtime Integration Contract | `EXECUTION_RUNTIME_INTEGRATION_SOT.md` | DONE |
| ER-002 | Phase 1 | Reconcile existing implementation | source + tests + runtime artifacts | DONE — audit completed |
| ER-003 | Phase 2 | Freeze Assignment → Execution Contract | contract + validation evidence | DONE — contract and vertical slice evidence |
| ER-004 | Phase 3 | Validate Worker Runtime integration | runtime execution evidence | DONE — durable runtime implementation; fresh re-certification required on current HEAD |
| ER-005 | Phase 4 | Freeze production requirements | requirement document | DONE — current production evidence envelope documented |
| ER-006 | Phase 5 | Build workload model | workload evidence | DONE — current acceptance envelope documented |
| ER-007 | Phase 6 | Build capacity model | capacity calculation | DONE — 1,000-worker / 8,000-hour acceptance envelope |
| ER-008 | Phase 7 | Evaluate technology options | ADR evidence | DONE — ADR-001 records current persistence choice and boundary |
| ER-009 | Phase 8 | Approve production architecture | architecture evidence | DONE — current deployable single-node boundary recorded |
| ER-010 | Phase 9 | Build production vertical slice | deployment evidence | DONE — deployable JAR + G12 evidence workflow |
| ER-011 | Phase 10 | Execute load/failure/recovery tests | test results | DONE — acceptance coverage exists; exact-current-HEAD evidence pending |
| ER-012 | Phase 11 | Production certification | certification evidence | BLOCKED — attributable production deployment evidence pending |

## Current Gate

**BLOCKED — ER-012 / Production Certification**

Implementation is closed, but certification is not. The repository currently contains an automated CI/deployable-runtime evidence mechanism, not evidence from an actual production deployment. The canonical G12 observability contract explicitly requires deployed-runtime evidence before the production observability gap can close.

The current HEAD is `81c1f3ad59c31185c68bd252fc15d0b4aeb60849`. The G12 workflow was corrected on this HEAD to validate exact-commit attribution and the runtime's actual `authorizationReference` field. A fresh CI result for this exact HEAD must be recorded before the automated evidence portion is accepted.

## Remaining Certification Conditions

- fresh full acceptance suite PASS on exact current HEAD
- fresh durable runtime recovery PASS on exact current HEAD
- fresh G12 acceptance PASS on exact current HEAD
- fresh deployable JAR PASS on exact current HEAD
- fresh automated runtime smoke PASS on exact current HEAD
- evidence bundle commit SHA == deployed/runtime commit SHA
- production deployment identity attributable to the exact deployed commit
- production runtime health signals attributable to the deployment
- production execution/recovery metrics attributable to the deployment
- production capacity/utilization measurements attributable to the deployment
- production logs, metrics and traces attributable to the deployment
- production security authorization and organization-visibility evidence attributable to the deployment
- clean working tree evidence

## Rule

No implementation task is considered complete without required evidence.

UNKNOWN does not equal MISSING.

Missing evidence creates an audit item, not an architectural assumption.

CI evidence is not silently promoted to production evidence.
