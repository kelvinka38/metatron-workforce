# METATRON WORKFORCE — PHASE 5 / 06 SIMULATION CONSTRAINT MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Semantic foundation:** Phase 2 canonical entity, lifecycle, transition, temporal, provenance, attribution, authorization-reference, economic-evidence, and learning-lineage models.
**Related artifacts:** `01_WORKING_TIME_MODEL.md`, `02_CAPACITY_MODEL.md`, `03_STAFFING_MODEL.md`, `04_RESOURCE_CONSTRAINTS_MODEL.md`, `05_ECONOMIC_REALITY_MODEL.md`
**Phase:** 5 — Time / Capacity / Staffing / Reality

## 1. Purpose

This artifact defines the constraints that a Workforce simulation MUST preserve when testing organizational behavior.

Simulation is an analytical mechanism. It MUST NOT become a mechanism for silently removing institutional constraints in order to produce a desired result.

## 2. Simulation principle

A valid simulation changes explicit assumptions while preserving the semantic rules of the Workforce model.

A simulation MAY vary:

- Worker count;
- skill mix;
- role distribution;
- working hours;
- shifts;
- availability;
- productivity;
- workload;
- capacity;
- staffing;
- resource limits;
- operating windows;
- cost assumptions;
- organizational configuration.

A simulation MUST NOT silently invent:

- Workers;
- authority;
- working time;
- capacity;
- qualifications;
- resources;
- budget;
- information;
- approvals;
- execution outcomes.

## 3. Scenario identity

Every material simulation scenario MUST preserve:

- scenario identity;
- baseline/reference scenario where applicable;
- assumptions;
- changed variables;
- unchanged constraints;
- simulation period;
- organizational context;
- workload context;
- initiating actor or process;
- provenance;
- result status.

A simulation result MUST be distinguishable from observed institutional reality.

## 4. Baseline

Where a simulation evaluates an alternative to an existing operating condition, the baseline MUST remain explicit.

Example:

```text
Baseline
Workers              = 8
Available capacity   = 64 labor-hours
Required capacity    = 192 labor-hours

Scenario B
Workers              = 24
Available capacity   = 192 labor-hours
Required capacity    = 192 labor-hours
```

The comparison MUST preserve the assumptions that changed between the scenarios.

## 5. Working-time constraint

Simulation MUST respect the working-time model.

A Worker cannot contribute unlimited hours merely because the simulation requires additional output.

The simulation MUST account for applicable:

- working days;
- working hours;
- shifts;
- breaks;
- rest;
- leave;
- availability;
- time zone;
- operating window.

## 6. Capacity constraint

Simulation MUST preserve the capacity model.

Conceptually:

```text
Available Capacity
=
Scheduled Capacity − Unavailable Capacity
```

and:

```text
Remaining Capacity
=
Available Capacity − Committed Capacity
```

A simulation MUST NOT increase capacity merely because a workload is unmet.

## 7. Staffing constraint

Simulation MUST preserve the distinction between:

```text
Current Staffing
Available Staffing
Qualified Staffing
Required Staffing
Staffing Deficit
```

A simulation with eight Workers cannot satisfy a requirement for twelve qualified Workers unless an explicit scenario change increases qualified staffing.

## 8. Resource constraint

Simulation MUST preserve finite resources.

Examples:

```text
Required machines = 4
Usable machines   = 2
```

The simulation MUST identify the resulting resource constraint rather than allowing all four concurrent operations to proceed as though the machines existed.

Resource contention MUST remain visible.

## 9. Authorization constraint

Simulation MUST preserve authorization semantics.

Increasing Worker count, capacity, or resources does not automatically create authority.

A simulated actor MUST remain subject to:

- role;
- scope;
- authority;
- delegation;
- policy;
- context;
- time validity;
- resource conditions.

A simulation cannot bypass an authorization requirement merely because the modeled action would otherwise be convenient.

## 10. Economic constraint

Simulation MUST preserve economic assumptions explicitly.

Examples:

```text
Worker cost rate      = explicit assumption
Equipment cost        = explicit assumption
Budget                 = explicit constraint
Resource consumption   = modeled quantity
```

Simulated economic outputs MUST remain distinguishable from authoritative accounting evidence.

## 11. Dependency constraint

Simulation MUST preserve explicit dependencies.

If Work B depends on Work A, simulation MUST NOT execute B as completed merely because B has available Workers.

Dependencies MAY be:

- organizational;
- temporal;
- resource-based;
- authorization-based;
- informational;
- staffing-based;
- external.

## 12. Concurrency constraint

Simulation MUST account for concurrency requirements.

If a resource or Worker cannot support two simultaneous operations, the simulation MUST schedule, queue, defer, or otherwise represent the contention.

Example:

```text
Machine capacity = 1 concurrent job

Job A = 08:00–10:00
Job B = 08:00–10:00

Both cannot be represented as concurrently executed on that machine.
```

## 13. Partial completion

A simulation MAY produce partial completion.

Example:

```text
Required work       = 192 hours
Available capacity  = 64 hours

Completed           = at most 64 hours,
subject to other constraints
Remaining           = at least 128 hours
```

The simulation MUST preserve incomplete work rather than forcing completion for scoring convenience.

## 14. Blocked and inconclusive results

A simulation MAY produce:

- completed;
- partially completed;
- blocked;
- deferred;
- failed;
- cancelled;
- insufficient evidence;
- infeasible.

These results MUST remain semantically distinct.

A simulation MUST NOT translate an infeasible scenario into a successful result merely because an optimizer or test expects completion.

## 15. Scenario comparison

When comparing scenarios, the implementation SHOULD preserve:

- baseline inputs;
- scenario inputs;
- invariant constraints;
- changed constraints;
- outputs;
- differences;
- assumptions;
- uncertainty.

A comparison MUST NOT attribute a result to a changed variable when another material variable also changed unless the attribution is explicitly modeled as uncertain or controlled.

## 16. Simulation provenance

Simulation results MUST preserve provenance sufficient to answer:

- who or what initiated the scenario;
- which model version was used;
- which inputs were supplied;
- which assumptions were introduced;
- which constraints were applied;
- when the simulation was run;
- what result was produced.

Simulation output MUST NOT be presented as observed institutional evidence without an explicit semantic distinction.

## 17. Simulation versus prediction

A simulation scenario is not automatically a forecast or prediction.

The implementation MUST distinguish, where modeled:

- observed state;
- scenario assumption;
- simulated outcome;
- forecast;
- recommendation.

A simulated outcome MUST NOT be represented as historical fact.

## 18. Sensitivity

Where useful, simulation MAY vary one or more assumptions to determine sensitivity.

Examples:

```text
Workers: 8 → 12 → 24
Productivity: 80% → 100% → 120%
Resource capacity: 50 → 100 → 150 hours
```

The system SHOULD preserve which variable changed and which constraints remained fixed.

## 19. Infeasibility

A scenario is infeasible when its stated requirements cannot be satisfied under the modeled constraints.

Examples:

- required labor exceeds available labor;
- required skill exceeds qualified staffing;
- resource capacity is insufficient;
- required operating window is unavailable;
- budget is insufficient;
- authorization is absent;
- prerequisite work cannot complete in time.

Infeasibility is a valid result and MUST remain visible.

## 20. No constraint laundering

Simulation MUST NOT launder impossible assumptions into apparently valid execution.

The following are invalid unless explicitly represented as scenario changes:

```text
Add invisible Workers
Add invisible capacity
Extend working hours without changing the time model
Ignore rest
Ignore unavailable resources
Ignore authorization
Ignore budget
Ignore dependencies
Ignore required skills
Ignore operating windows
```

## 21. Economic evidence from simulation

Simulation MAY produce estimated economic consequences, including:

- estimated labor requirement;
- estimated staffing cost;
- estimated resource consumption;
- estimated cost per work unit;
- estimated variance between scenarios.

These MUST remain labeled as simulated or estimated evidence.

They MUST NOT overwrite actual operational or accounting evidence.

## 22. Simulation and learning

Simulation results MAY become inputs to later learning or improvement processes.

However, the learning lineage MUST preserve that the result originated from simulation rather than observed execution.

Conceptually:

```text
Simulation
   ↓
Simulated Outcome
   ↓
Evidence / Analysis
   ↓
Experience Candidate
```

The system MUST NOT treat simulated success as equivalent to validated real-world performance without the applicable validation process.

## 23. Reproducibility

A material simulation SHOULD be reproducible from its preserved inputs and model version.

At minimum, the system SHOULD preserve:

- scenario parameters;
- model/version reference;
- simulation time;
- randomization seed where stochastic behavior is used;
- constraint configuration;
- relevant policy version.

If reproducibility is impossible, the limitation SHOULD remain explicit.

## 24. Historical separation

Simulation MUST NOT rewrite historical reality.

For example:

```text
Actual 2026 operation:
8 Workers, 64 available hours

Later simulation:
24 Workers, 192 available hours
```

The simulation MUST NOT change the historical record to imply that 24 Workers were present in the actual period.

## 25. Acceptance invariants

The implementation MUST satisfy at least these invariants:

1. Simulation scenarios are explicitly identified.
2. Simulation results are distinguishable from observed reality.
3. Working-time constraints remain enforced.
4. Capacity constraints remain enforced.
5. Staffing constraints remain enforced.
6. Skill and qualification constraints remain enforced.
7. Resource constraints remain enforced.
8. Authorization constraints remain enforced.
9. Economic assumptions remain explicit.
10. Dependencies remain enforced.
11. Concurrency limits remain enforced.
12. Partial completion remains possible.
13. Blocked, failed, deferred, and infeasible outcomes remain valid.
14. Simulation cannot silently create Workers, time, capacity, resources, authority, budget, or information.
15. Simulation provenance remains attributable.
16. Simulated economic evidence remains distinct from accounting truth.
17. Simulated outcomes remain distinct from observed outcomes.
18. Historical reality is never rewritten by simulation.
19. Material scenarios preserve sufficient inputs for reconstruction.
20. Constraint violations are surfaced rather than hidden.

## 26. Gate G5 contribution

This artifact contributes evidence for Gate G5 by establishing that Workforce simulation can vary operational assumptions while preserving:

- working hours;
- availability;
- capacity;
- staffing;
- coverage;
- resources;
- authorization;
- dependencies;
- economic constraints;
- cost consequences;
- incomplete and infeasible outcomes;
- no impossible execution.

This document defines the semantic simulation constraint contract only. It does not prescribe a specific simulator, optimizer, forecasting engine, stochastic framework, database schema, or user interface.
