# METATRON WORKFORCE — G12 RUNTIME EVIDENCE MAP

Document Type:
Evidence Mapping Contract

Status:
Repository Sources Connected / Runtime Execution Pending

Gate:
G12 — Workforce Production Readiness

## PURPOSE

Map each G12 production-readiness requirement to the concrete repository implementation and acceptance-test source that will produce runtime evidence.

## SOURCE MAP

| G12 Requirement | Repository Implementation Source | Acceptance Source | Runtime Evidence Required |
|---|---|---|---|
| Lifecycle operational | Phase 10 vertical-slice execution lifecycle | `Phase12ProductionReadinessAcceptanceTest` | Passing lifecycle/execution records |
| Organization operational | `OrganizationRelationship`, Phase 10 request/plan provenance | `multiOrganizationWorkflowsRemainIsolated` | Multi-organization isolation evidence |
| Workplace operational | Phase 3 workplace communication/runtime binding models | Full acceptance suite | Communication/runtime records |
| Work operational | Phase 10 `Phase10PrimaryVerticalSliceService` / `ExecutionOutcome` | Concurrent workflow + recovery tests | Execution and recovery evidence |
| Capacity operational | Phase 5 capacity models + `FarmOperatingPlan` | `thousandWorkerCapacityIsRepresentableWithoutArtificialOverflow` | Capacity calculation result |
| Staffing operational | Phase 5 staffing/resource models | Full acceptance suite | Staffing allocation evidence |
| Authorization operational | Phase 6 `AuthorizationService` / authorization decisions | `securityRejectsOutOfWindowAuthorization` | Authorization decision evidence |
| Reporting operational | Phase 10 `CycleReport` | Failure recovery + observability tests | Report/performance evidence |
| Economic integrity | Phase 5 economic evidence + Phase 10 `EconomicSliceEvidence` | `economicEvidenceContainsPlanActualVarianceAndBoundary` | Plan/actual/variance evidence |
| Security integrity | Phase 6 authorization boundary | Phase 11 + Phase 12 security tests | Security validation evidence |
| Audit completeness | Provenance fields across assignment, plan, approval, execution, report, economics, learning | `observabilityProvenanceExistsAcrossMaterialStages` | Complete attributable audit/provenance evidence |
| Learning integrity | Phase 10 `LearningImprovement` | Observability/provenance acceptance | Learning evidence |

## CURRENT ACCEPTANCE COVERAGE

The repository currently contains a dedicated Phase 12 acceptance test:

`src/test/java/com/metatron/workforce/phase12/Phase12ProductionReadinessAcceptanceTest.java`

It covers:

- multi-organization isolation
- 1,000-worker capacity representation
- 64 concurrent workflows
- failure recovery
- authorization denial outside the valid window
- economic plan-vs-actual variance
- provenance across material stages

The existence of these tests is repository evidence only. Their results become runtime evidence only after actual execution.

## RUNTIME EVIDENCE DESTINATION

Canonical workflow:

`.github/workflows/g12-production-readiness.yml`

Expected workflow artifact:

`g12-production-readiness-evidence`

Expected runtime metadata:

- workflow run ID
- workflow attempt
- event
- ref
- commit SHA
- runner OS
- Java version
- Gradle version
- test reports / execution output

## CURRENT STATE

Repository source mapping:

CONNECTED

Runtime execution:

PENDING

G12 decision:

PENDING

## DECISION RULE

A repository test definition cannot close a G12 gap. A gap closes only when the corresponding test/runtime evidence has actually executed, is attributable to a concrete commit/run, and satisfies the G12 contract.
