# WORKFORCE AUTONOMOUS MANAGEMENT — IMPLEMENTATION STATUS AND CONTRACT

**Status:** BOUNDED INTEGRATED PRIMITIVES — AUTONOMY CLOSURE IN PROGRESS  
**Canonical upstream:** `metatron-institution/05_WORKFORCE/WORKFORCE_AUTONOMOUS_MANAGEMENT_OPERATING_SPEC.md`  
**Current program:** `docs/AUTONOMY_CLOSURE/IMPLEMENTATION_MASTER_PLAN.md`  
**Authority:** Implementation derivative only; not a Source of Truth

## Purpose

This document records what the existing autonomous-management composition actually proves and what the ratified Autonomy Closure still requires.

## Existing bounded composition

The repository contains:

- `ManagementAutonomyService` with a durable `ManagementStateStore` boundary;
- `FileManagementStateStore` Objective/history persistence;
- owner Worker references and management transitions;
- `AutonomousManagementCoordinator` composition with Workplace queue, authorization, execution handoff and runtime correlation;
- staffing-gap representation;
- local recovery/replan transitions;
- evidence-required delivery;
- bounded tests including `GatewayDirectorNorthStarAcceptanceTest`.

These are valid institutional primitives and bounded composition evidence.

## Explicit limitation

The existing acceptance tests drive service methods in the required sequence. They prove that primitives compose when called correctly; they do not prove a persistent Manager Runner autonomously decides and advances every transition.

The existing composition does not yet establish:

- transactional accept/persist/detach conversational ingress;
- a durable autonomous Management Runner with fencing;
- durable outbox/inbox across all Objective boundaries;
- a versioned Work Graph and general ready-set scheduler;
- autonomous staffing/admission/AI Worker formation;
- production lease/heartbeat/checkpoint recovery for unfinished Work;
- independent general Observation criterion closure;
- ChatGPT channel integration;
- full Workplace persistence/control integration;
- L10 production evidence.

## Current code-boundary corrections

1. `HumanObjectiveIngressService.submit()` is a synchronous compatibility path, not the target Objective lifetime owner.
2. `WorkQueueService`/interaction executors may support bounded interaction but are not the durable autonomy backbone.
3. `StaffingService` request lifecycle is not autonomous staffing completion.
4. `RemoteRuntimeExecutor.executeAsync()` is a transport primitive, not a Workforce scheduler.
5. `RepositoryAuditAutonomousCapability` proves one bounded capability, not general execution.
6. local management recovery transitions are not proof of operational retry/reassign/replan.
7. capability-produced evidence is not a substitute for a general Observation contract.

## Target composition

```text
Interaction Provider
-> Gateway admission
-> Workforce acceptance transaction
-> persistent Manager Runner
-> Intelligence-assisted understanding/plan proposal
-> versioned Work Graph
-> Workforce allocation/staffing
-> Governance authorization
-> durable Execution/runtime dispatch
-> Observation evidence
-> Workforce recovery/replan/closure
-> Workplace/channel progress and delivery
```

## Boundaries retained

Implementation MUST NOT create authority, own constitutional legitimacy, make Workplace projections authoritative, own Execution/Cloud semantics, turn model sessions into Workers, bypass admission/qualification, or promote unverified output into Knowledge.

## Acceptance

Existing bounded tests remain regression requirements. General completion requires all four golden slices and the upstream 45-condition production gate. Until then this component is reported as `PARTIAL / BOUNDED`, never `AUTONOMY COMPLETE`.
