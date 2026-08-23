# METATRON WORKFORCE — G12 OPERATIONAL EVIDENCE REQUIREMENTS

Document Type:
Evidence Collection Contract

Status:
Repository Sources Connected / Runtime Execution Pending

Gate:
G12 — Workforce Production Readiness

## REQUIRED EVIDENCE SET

| Domain | Repository Source | Runtime Evidence |
|---|---|---|
| Architecture | Phase 1 implementation artifacts | Executed acceptance against current commit |
| Lifecycle | Phase 2 lifecycle model + Phase 10 execution | State transition records |
| Organization | Phase 4 organization model + Phase 12 isolation test | Organization execution/isolation records |
| Workplace | Phase 3 workplace communication/runtime binding | Communication/runtime records |
| Execution | Phase 10 vertical slice + Phase 12 concurrency/recovery tests | Workflow execution records |
| Capacity | Phase 5 capacity model + Phase 12 1,000-worker test | Capacity measurement result |
| Staffing | Phase 5 staffing/resource model | Staffing allocation evidence |
| Security | Phase 6 authorization + Phase 11/12 tests | Security validation evidence |
| Economic | Phase 5 economic evidence + Phase 12 economic test | Plan/actual/variance evidence |
| Audit | Provenance across material stages | Complete attributable audit trail |

## PRIMARY PHASE 12 ACCEPTANCE SOURCE

`src/test/java/com/metatron/workforce/phase12/Phase12ProductionReadinessAcceptanceTest.java`

Covered scenarios:

- multi-organization isolation
- 1,000-worker capacity representation
- 64 concurrent workflows
- failure recovery
- authorization denial outside valid window
- economic plan-vs-actual variance
- provenance across material stages

## RUNTIME EVIDENCE IDENTITY

Every collected evidence package must identify:

- commit SHA
- workflow run ID
- workflow attempt
- execution event/ref
- runner environment
- Java version
- Gradle version
- test result
- evidence artifact location

## RESULT

Evidence Collection:

PENDING

No repository artifact alone closes an evidence requirement.
