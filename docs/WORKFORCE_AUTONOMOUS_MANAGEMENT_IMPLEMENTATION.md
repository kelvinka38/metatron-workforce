# WORKFORCE AUTONOMOUS MANAGEMENT — IMPLEMENTATION CONTRACT

**Status:** IMPLEMENTATION CONTRACT  
**Canonical upstream:** `metatron-institution/05_WORKFORCE/WORKFORCE_AUTONOMOUS_MANAGEMENT_OPERATING_SPEC.md`  
**Authority:** Implementation derivative only; this document is not a Source of Truth.

## Purpose

Implement the smallest production-safe Workforce slice that makes Objective ownership, staffing-gap detection, delegated assignment coordination, local recovery/replanning, escalation, and attributable completion explicit and testable without redefining canonical Workforce semantics.

## Scope

This slice introduces a management coordination service that:

- records persistent Objective ownership by Worker identity;
- records subordinate Assignment references without collapsing Assignment into Objective;
- evaluates required versus available capacity and produces a StaffingNeed when there is a gap;
- records local recovery/replan actions before escalation;
- records escalation only as an explicit management action;
- records delivery with evidence references;
- preserves an attributable event history for the Objective management loop.

## Boundaries

The implementation does not:

- create authority;
- decide constitutional legitimacy;
- execute external actions;
- own runtime lifecycle;
- own accounting truth;
- create Workers from model sessions;
- implement recruitment marketplace semantics;
- replace Organization, Execution, Intelligence, Economy, Knowledge, or Observation.

## Invariants

1. Objective ownership is anchored to persistent `workerId`, never runtime/model/session ID.
2. Assignment references remain independent records; Objective does not become an execution aggregate.
3. Capacity gap detection returns explicit staffing demand rather than pretending capacity is infinite.
4. Recovery/replan is a management coordination action and does not claim execution success.
5. Escalation is explicit and attributable; Human escalation is not the default for every failure.
6. Delivery requires at least one evidence reference.
7. Every management transition is timestamped and attributable.
8. Invalid terminal-state transitions fail closed.

## Acceptance Slice

The automated test must prove:

```text
Director accepts Objective
  ↓
capacity gap detected
  ↓
staffing need emitted
  ↓
subordinate assignments recorded
  ↓
failure/variance recorded
  ↓
Director performs local replan
  ↓
Objective remains owned by same persistent Worker
  ↓
completion with evidence
```

This is an implementation foundation for the full Gateway Director north-star scenario. It is not by itself final Workforce institutional acceptance.
