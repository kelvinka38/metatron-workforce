# METATRON WORKFORCE — PHASE 5 / GATE G5

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 5 — Time / Capacity / Staffing / Reality
**Status:** ACCEPTANCE RUNNING — fresh main-branch CI pending
**Implementation contract:** `PHASE_5_IMPLEMENTATION_CONTRACT.md`

## 1. Gate objective

Gate G5 determines whether Workforce can represent organizational work under finite time, capacity, staffing, resource, and economic constraints without silently inventing execution capability.

## 2. Acceptance evidence

The frozen Phase 5 acceptance suite is `Phase5RealityAcceptanceTest`. CI proves, in order:

1. Phase 5 acceptance;
2. Phase 5 regression;
3. full regression;
4. deployable `bootJar`.

A PR validation run already proved the implementation surface green. The gate remains open until the same evidence is reproduced by a fresh CI run on the exact current `main` gate commit.

## 3. Required capability coverage

### Working time

- working hours, days and shifts;
- break/rest exclusion;
- explicit time zone;
- overnight shifts;
- effective validity;
- explicit leave/unavailability;
- `AVAILABLE` / `UNAVAILABLE` / `UNKNOWN` resolution;
- operating-window constraint.

### Capacity and staffing

- finite scheduled/available capacity;
- required capacity independent of availability;
- coverage and deficit;
- explicit overcommitment and utilization evidence;
- current vs available vs qualified staffing;
- qualified staffing deficit.

### Resources and economics

- quantity vs usable resource capacity;
- authorization and dependency boundaries;
- resource deficits;
- planned vs actual labor/resource evidence;
- cost-per-work-unit evidence where valid;
- no Workforce accounting ownership.

### Reality and history

- constrained `PARTIAL` outcomes;
- hard `BLOCKED` outcomes;
- no invented Workers, capacity, resources, budget, authority, or dependencies;
- attributable temporal evidence;
- historical validity preserved.

## 4. Mandatory realism scenario

The acceptance suite proves the canonical constrained condition:

```text
Required labor capacity      = 192 hours
Available labor capacity     = 64 hours

Required qualified Workers   = 6
Available qualified Workers  = 2

Required equipment           = 4 units
Usable equipment             = 2 units
```

Insufficient quantitative capacity remains `PARTIAL`; hard constraints such as closed operating windows, insufficient budget, failed dependencies, or unauthorized resources produce `BLOCKED` rather than fabricated execution.

## 5. Non-negotiable boundaries

Phase 5 does not own payroll, authoritative financial accounting, constitutional authority, authorization policy, execution infrastructure, or external resource truth.

Workforce provides operational facts, constraints, context, and economic evidence. Economy remains authoritative for accounting truth.

## 6. Gate decision

**PENDING FRESH MAIN CI.**

Only a green CI run on the exact current gate commit may change this decision to **PASS** and permit Phase 6 — Authorization / Execution / Attribution.
