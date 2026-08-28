# WORKFORCE AUTONOMOUS MANAGEMENT — IMPLEMENTATION CONTRACT

**Status:** INTEGRATED IMPLEMENTATION CONTRACT  
**Canonical upstream:** `metatron-institution/05_WORKFORCE/WORKFORCE_AUTONOMOUS_MANAGEMENT_OPERATING_SPEC.md`  
**Authority:** Implementation derivative only; this document is not a Source of Truth.

## Purpose

Implement a production-safe Workforce composition that makes Objective ownership, staffing-gap detection, Workplace coordination, canonical authorization, Execution handoff, runtime correlation, local recovery/replanning, escalation, durable continuity, and attributable completion explicit and testable without redefining canonical Workforce semantics.

## Implemented Composition

```text
AUTHORIZED HUMAN
  ↓ objective
MANAGEMENT OBJECTIVE
  ↓ persistent Worker ownership
WORKPLACE QUEUE
  ↓ request / staffing proposal / report
WORKFORCE MANAGEMENT
  ↓ assignment reference
PHASE-6 AUTHORIZATION
  ↓ allowed authorization reference
EXECUTION HANDOFF
  ↓
RUNTIME EXECUTION CORRELATION
  ↓
EXECUTION SERVICE
  ↓
OUTCOME / EVIDENCE
  ↓
LOCAL RECOVERY / REPLAN WHEN REQUIRED
  ↓
EVIDENCE-BACKED DELIVERY
  ↓
WORKPLACE REPORT TO HUMAN
```

## Durable Objective Continuity

`ManagementAutonomyService` accepts a `ManagementStateStore` boundary. `FileManagementStateStore` persists Objective state and attributable management-event history as JSON using atomic replacement where the filesystem supports it.

A process/runtime replacement may construct a new `ManagementAutonomyService` from the same store and recover:

- Objective identity;
- persistent owner Worker identity;
- organization context;
- status;
- Assignment references;
- evidence references;
- management event history.

The default no-argument constructor remains an in-memory compatibility surface for transient callers and historical tests. Production composition is expected to inject durable storage.

## Workplace Integration

`AutonomousManagementCoordinator` uses the existing Phase-3 `WorkQueueService`; it does not create a parallel Workplace model.

Current integrated flows are:

- Human objective → Director `REQUEST` queue item → delivered and acknowledged;
- detected capability/capacity gap → legitimate `PROPOSAL` queue item to a staffing authority;
- completed Objective → `REPORT` queue item back to the Human recipient.

A staffing proposal does not manufacture a Participant or Worker. Participant recognition/admission remains with the legitimate upstream authority; Workforce owns the staffing demand and Worker-side consequence only.

## Authorization, Execution, and Runtime Integration

Before an execution handoff is created, the coordinator calls the existing Phase-6 `AuthorizationService.authorize(...)` against `WorkProposal`, `ApprovalDecision`, and `AuthorizationRequest`.

Denied authorization fails closed and no `ExecutionHandoffRequest` is produced.

An allowed path:

1. records the Assignment reference against the Objective;
2. preserves the canonical authorization reference;
3. creates the existing Phase-3 `ExecutionHandoffRequest`;
4. creates runtime execution correlation through `RuntimeExecutionCoordinator`;
5. invokes the existing `ExecutionService.executeAuthorized(...)`, which revalidates authorization before execution;
6. returns the Execution record without transferring Execution semantics into Workforce management.

## Boundaries

The implementation does not:

- create authority;
- decide constitutional legitimacy;
- bypass approval or authorization;
- make Workplace queue state authoritative Work state;
- own Execution lifecycle semantics;
- own runtime lifecycle;
- own accounting truth;
- create Workers from model sessions;
- implement recruitment marketplace semantics;
- replace Organization, Execution, Intelligence, Economy, Knowledge, Observation, Gateway, Data, or Library.

## Invariants

1. Objective ownership is anchored to persistent `workerId`, never runtime/model/session ID.
2. Assignment references remain independent records; Objective does not become an Execution aggregate.
3. Capacity gap detection returns explicit staffing demand rather than pretending capacity is infinite.
4. Staffing demand may enter an authorized staffing/admission path but cannot manufacture institutional identity.
5. No Execution handoff exists before canonical Phase-6 authorization succeeds.
6. Execution revalidates authorization at its own boundary.
7. Runtime replacement does not replace Worker or Objective identity.
8. Recovery/replan is a management coordination action and does not claim execution success.
9. Escalation is explicit and attributable; Human escalation is not the default for every failure.
10. Delivery requires evidence.
11. Every material management transition is timestamped and attributable.
12. Invalid terminal-state transitions fail closed.

## North-Star Automated Acceptance

`GatewayDirectorNorthStarAcceptanceTest` proves the bounded integration path:

```text
Founder submits Gateway V2 Objective
  ↓
Gateway Director accepts and acknowledges it
  ↓
capacity gap detected
  ↓
staffing proposal emitted to legitimate authority
  ↓
Assignment recorded
  ↓
Phase-6 authorization succeeds
  ↓
Execution handoff + runtime correlation created
  ↓
Execution succeeds
  ↓
variance occurs
  ↓
Director performs local recovery/replan
  ↓
Objective remains owned by the same persistent Worker
  ↓
delivery requires evidence
  ↓
report returned through Workplace
  ↓
service/runtime replacement
  ↓
Objective + event history remain durable
```

A separate denial test proves that denied authorization cannot create an Execution handoff.

## Acceptance Boundary

Passing this automated scenario proves the **Workforce autonomous-management integration slice** across durable management state, Workplace queue, Authorization, Execution handoff, runtime correlation, Execution, recovery, and evidence-backed delivery.

It does **not** assert that Gateway V2 itself has been built or accepted. Gateway V2 remains the downstream bounded institutional project used for the later live Workforce acceptance scenario. Full Workforce institutional acceptance requires production deployment of this integration and a formal acceptance decision against the canonical Workforce acceptance requirements.