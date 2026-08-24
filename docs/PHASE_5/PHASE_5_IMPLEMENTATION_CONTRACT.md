# METATRON WORKFORCE — PHASE 5 IMPLEMENTATION CONTRACT

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 5 — Time / Capacity / Staffing / Reality
**Depends on:** G4 PASS
**Status:** FROZEN IMPLEMENTATION CONTRACT

## 1. Purpose

Phase 5 makes Workforce explicitly reality-constrained. The implementation records finite working time, availability, capacity, qualified staffing, resource constraints, operational/economic evidence, and simulation assumptions without manufacturing execution capability.

## 2. Required acceptance behaviors

1. Working-time rules preserve effective validity, time zone, working days, shifts, breaks, overnight windows, and explicit unavailability.
2. Availability resolves to `AVAILABLE`, `UNAVAILABLE`, or `UNKNOWN`; unknown is never optimistic availability.
3. Capacity preserves scheduled, unavailable, available, committed, remaining, required, deficit, overcommitment, coverage, and utilization semantics.
4. Staffing distinguishes required, current, available, and qualified workers; qualified deficit is not hidden by nominal headcount.
5. Resources distinguish quantity, usability, authorization, and dependency satisfaction.
6. Economic evidence preserves planned/actual labor and resource quantities and optional cost basis without claiming accounting ownership.
7. Historical evidence preserves effective validity, attribution, context, and source reference.
8. A constrained workload can be `PARTIAL` or `BLOCKED`; it cannot become `FEASIBLE` by inventing workers, capacity, resources, budget, authority, or dependencies.

## 3. Boundaries

Phase 5 does not own payroll, financial accounting, constitutional authority, authorization policy, execution infrastructure, or external resource truth. It produces operational facts and evidence for owning domains.

## 4. Canonical realism scenario

The acceptance suite MUST preserve this scenario without reporting full execution:

- required labor: 192 hours;
- available labor: 64 hours;
- required qualified Workers: 6;
- available qualified Workers: 2;
- required equipment: 4 units;
- usable equipment: 2 units;
- operating window: 08:00–16:00 with only 08:00–12:00 available;
- budget: insufficient.

The resulting condition must remain constrained and attributable.

## 5. Completion rule

G5 is complete only after fresh CI evidence on the exact gate commit proves the Phase 5 acceptance test, full regression, and deployable artifact build.
