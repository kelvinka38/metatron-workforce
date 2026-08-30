# MASTER_EXECUTION_CHECKLIST.md

## AUTONOMY CLOSURE SUPERSESSION NOTICE — 2026-08-31

This register remains historical/current only for the execution-runtime certification scope it names. It does not govern or prove general Workforce autonomy. The Founder-approved active program is `docs/AUTONOMY_CLOSURE/IMPLEMENTATION_MASTER_PLAN.md`; status and completion claims follow `docs/AUTONOMY_CLOSURE/CURRENT_STATE_AND_GAP_MATRIX.md` and the upstream autonomy-closure production gate.

## Status

CANONICAL EXECUTION REGISTER — RECONCILED 2026-08-25

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
| ER-013 | Phase 12 | Close Human → Telegram → Intelligence E2E | exact-current-HEAD Telegram E2E evidence | IN PROGRESS — transport, identity, anti-echo and Intelligence path implemented; live evidence pending |
| ER-014 | Phase 13 | Close Intelligence → authorized Metatron execution bridge | execution/evidence/Gateway boundary proof | IN PROGRESS — admitted capability dispatch + read-only Gateway audit capability implemented; live Gateway evidence pending |
| ER-015 | Phase 14 | Re-certify production on exact deployed HEAD | G12 production evidence bundle | BLOCKED — depends on ER-013/ER-014 and deployed evidence |

## Current Gate

**BLOCKED — ER-013 / ER-014 / ER-015**

The execution runtime implementation is substantially complete. The remaining closure work is integration proof: prove the current HEAD can receive a real Telegram interaction, route it through the canonical Intelligence/BIOS boundary, and—when execution is requested—enter the authorized Metatron execution boundary without bypassing Gateway. Only after those are proven can production certification close.

The canonical repository HEAD at this reconciliation is `a8b30e68b5b0dd8ef3f16eab43ea6ca1fe33c5dc`. Earlier references to `457c83b30a4e93acee067e99beaf9457d04e4714`, `3617311479c40574985f25e47634cb1043ce05f8`, and `81c1f3ad59c31185c68bd252fc15d0b4aeb60849` are stale and must not be used as current deployment identity.

The repository now contains an explicit execution-capability registry, a read-only Gateway audit capability, Telegram wiring for `audit gateway` / `audit g4 gateway`, an execution-path regression test, and an isolated deployment gate that can run without production credentials. These are implementation changes, not production evidence.

The available GitHub evidence does not yet establish a successful CI run for the exact current HEAD or attributable production deployment evidence. CI evidence must not be silently promoted to production evidence.

## Runtime Configuration for Gateway Audit

The Telegram execution path requires:

- `METATRON_GATEWAY_AUDIT_URL` — the exact read-only Gateway audit/health endpoint to invoke.
- `METATRON_GATEWAY_AUDIT_TOKEN` — optional bearer token when that endpoint requires authentication.

The capability is read-only. It is registered as `gateway.audit.read` and fails closed when the URL is not configured.

## Remaining Certification Conditions

- fresh full acceptance suite PASS on exact current HEAD
- fresh durable runtime recovery PASS on exact current HEAD
- fresh G12 acceptance PASS on exact current HEAD
- fresh deployable JAR PASS on exact current HEAD
- fresh automated runtime smoke PASS on exact current HEAD
- real Telegram inbound → Intelligence → Telegram response PASS on exact current HEAD
- `audit gateway` / `audit g4 gateway` → admitted execution capability → Gateway response PASS on exact current HEAD
- Gateway authorization remains authoritative for execution
- evidence/provenance correlation survives the full interaction path
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
