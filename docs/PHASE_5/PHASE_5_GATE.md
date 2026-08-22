# METATRON WORKFORCE — PHASE 5 / GATE G5

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 5 — Time / Capacity / Staffing / Reality
**Status:** Gate evidence definition

## 1. Gate objective

Gate G5 determines whether Workforce can represent organizational work under finite time, capacity, staffing, resource, and economic constraints without silently inventing execution capability.

Phase 5 MUST NOT advance to Phase 6 until the required evidence exists and the acceptance invariants are satisfied.

## 2. Required evidence

### Working time

- [ ] Working hours are explicit.
- [ ] Working days are explicit.
- [ ] Shifts are explicit where applicable.
- [ ] Break/rest constraints are represented.
- [ ] Availability is time-aware.
- [ ] Leave/unavailability is represented where applicable.
- [ ] Time zone is preserved.
- [ ] Operating windows are enforced.

### Capacity

- [ ] Theoretical capacity is distinguishable from available capacity.
- [ ] Allocated capacity is distinguishable from committed capacity.
- [ ] Consumed capacity is represented where material.
- [ ] Remaining capacity is derivable.
- [ ] Capacity deficits remain visible.
- [ ] Coverage ratio is calculated from compatible quantities.
- [ ] Historical capacity conditions remain preserved.

### Staffing

- [ ] Required staffing is independent of current available staffing.
- [ ] Current staffing is distinguishable from available staffing.
- [ ] Available staffing is distinguishable from qualified staffing.
- [ ] Role/position requirements remain explicit.
- [ ] Shift deficits remain visible.
- [ ] Skill deficits remain visible.
- [ ] Partial staffing remains distinguishable from full staffing.
- [ ] Staffing cost implications remain evidence rather than Workforce accounting truth.

### Resource constraints

- [ ] Equipment constraints are represented.
- [ ] Facility constraints are represented.
- [ ] Material constraints are represented.
- [ ] Capital/budget constraints are represented where applicable.
- [ ] Information dependencies are represented.
- [ ] Human/organizational dependencies are represented.
- [ ] Resource availability is time-aware.
- [ ] Resource usability is distinguishable from availability.
- [ ] Resource authorization is distinguishable from availability.
- [ ] Resource contention is visible.
- [ ] Resource deficits are visible.
- [ ] Over-allocation is not silently hidden.

### Economic reality

- [ ] Labor demand is economically representable.
- [ ] Labor utilization is representable where measurable.
- [ ] Staffing cost evidence is preserved.
- [ ] Resource consumption evidence is preserved.
- [ ] Planned versus actual quantities remain distinguishable.
- [ ] Variance remains visible.
- [ ] Cost consequences are representable.
- [ ] Cost-per-work-unit evidence is supported where valid inputs exist.
- [ ] P&L-compatible evidence can be produced for Economy.
- [ ] Workforce does not claim authoritative accounting ownership.

### Simulation

- [ ] Simulation scenarios are explicitly identified.
- [ ] Baseline and changed assumptions are distinguishable.
- [ ] Working-time constraints remain enforced.
- [ ] Capacity constraints remain enforced.
- [ ] Staffing constraints remain enforced.
- [ ] Resource constraints remain enforced.
- [ ] Authorization constraints remain enforced.
- [ ] Economic assumptions remain explicit.
- [ ] Dependencies and concurrency constraints remain enforced.
- [ ] Partial completion is possible.
- [ ] Blocked/infeasible outcomes remain valid.
- [ ] Simulation cannot invent Workers, time, capacity, resources, authority, budget, or information.
- [ ] Simulation results remain distinguishable from historical reality.

## 3. Core acceptance scenario

A realistic Workforce scenario MUST demonstrate all of the following simultaneously:

```text
Workload
  ↓
Working-Time Constraints
  ↓
Available Capacity
  ↓
Qualified Staffing
  ↓
Resource Availability / Capacity
  ↓
Authorization / Dependencies
  ↓
Economic Constraints
  ↓
Execution Feasibility
```

The scenario MUST be able to produce a constrained result when one of these conditions is insufficient.

## 4. Mandatory realism test

The implementation MUST be capable of representing a scenario such as:

```text
Required labor capacity      = 192 hours
Available labor capacity     = 64 hours

Required qualified Workers   = 6
Available qualified Workers  = 2

Required equipment           = 4 units
Usable equipment             = 2 units

Required operating window    = 08:00–16:00
Available operating window   = 08:00–12:00

Budget condition             = insufficient
```

The result MUST NOT be represented as successful full execution.

The system MUST preserve the applicable deficits and constraints and MUST allow the resulting work to be blocked, deferred, partially completed, escalated, or otherwise handled under the canonical lifecycle and authority rules.

## 5. Economic boundary

Gate G5 passes only if Workforce provides operational/economic evidence without becoming the authoritative accounting domain.

The boundary MUST remain:

```text
Workforce
= operational facts + evidence + context

Economy
= authoritative accounting / financial truth
```

## 6. Simulation boundary

Gate G5 passes only if simulation preserves real constraints.

The simulator MUST NOT be able to obtain a successful result by silently creating:

- Workers;
- qualifications;
- working hours;
- capacity;
- resources;
- authority;
- budget;
- information;
- approvals.

## 7. Historical boundary

Gate G5 passes only if later recalculation, simulation, or correction does not rewrite the historical condition that existed at an earlier time.

Material evidence MUST preserve attribution, provenance, and temporal validity.

## 8. Failure rule

If required evidence is missing, contradictory, stale, or insufficient to demonstrate the realism constraints, Gate G5 MUST NOT be treated as passed.

The condition MUST remain explicit and attributable.

## 9. Exit condition

G5 is complete only when:

- [ ] Working-time model is implemented and tested.
- [ ] Capacity model is implemented and tested.
- [ ] Staffing model is implemented and tested.
- [ ] Resource-constraint model is implemented and tested.
- [ ] Economic-reality evidence model is implemented and tested.
- [ ] Simulation constraints are implemented and tested where simulation is in scope.
- [ ] Required cross-model invariants are tested.
- [ ] No impossible execution path is accepted merely because nominal headcount exists.
- [ ] Evidence remains attributable and time-valid.
- [ ] Economic boundary remains preserved.
- [ ] Gate evidence is reproducible from the repository state.

Only after these conditions are satisfied may execution advance to Phase 6 — Authorization / Execution / Attribution.
