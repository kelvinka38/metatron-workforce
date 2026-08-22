# METATRON WORKFORCE — PHASE 5 / 05 ECONOMIC REALITY MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Semantic foundation:** Phase 2 canonical entity, lifecycle, transition, temporal, provenance, attribution, authorization-reference, economic-evidence, and learning-lineage models.
**Related artifacts:** `01_WORKING_TIME_MODEL.md`, `02_CAPACITY_MODEL.md`, `03_STAFFING_MODEL.md`, `04_RESOURCE_CONSTRAINTS_MODEL.md`
**Phase:** 5 — Time / Capacity / Staffing / Reality

## 1. Purpose

This artifact defines the Workforce-side economic reality and evidence contract required by Gate G5.

Workforce does not own authoritative accounting, treasury, financial reporting, or P&L truth. Workforce MUST nevertheless preserve sufficient operational evidence for Economy to determine the economic consequences of work, staffing, capacity, resource use, and organizational decisions.

Economic evidence exists to prevent the Workforce from modeling operational activity as economically free, unlimited, or consequence-free.

## 2. Economic reality principle

Material Workforce activity that consumes labor, time, capacity, resources, facilities, materials, capital, or other economically relevant inputs MUST remain economically representable.

The implementation MUST distinguish:

- demand;
- allocation;
- utilization;
- consumption;
- cost basis;
- planned cost;
- actual cost evidence;
- variance;
- economic consequence;
- accounting authority.

Workforce MUST NOT silently convert operational evidence into authoritative accounting truth.

## 3. Economic evidence identity

A material economic evidence record MUST preserve enough context to answer:

- which organization or organizational unit generated the activity;
- which Worker, role, position, or team contributed;
- which work, assignment, or workload generated the activity;
- which time period applies;
- which capacity was required or consumed;
- which resources were required or consumed;
- which quantity or utilization basis applies;
- which cost basis was used where known;
- who produced the evidence;
- when it was recorded;
- under which authority or operational context;
- which source/provenance supports it.

## 4. Labor demand

Workforce SHOULD preserve evidence of labor demand arising from workload requirements.

Where measurable:

```text
Required Labor Hours
=
Required Work Quantity / Expected Productivity
```

The exact productivity model may vary by workload, Worker, role, skill, or operating condition.

Required labor MUST remain distinct from actual labor supplied.

Example:

```text
Required labor = 192 hours
Available labor = 64 hours
Labor deficit = 128 hours
```

The system MUST NOT represent the 192-hour requirement as fulfilled merely because the workload exists.

## 5. Labor utilization

Where actual or committed Worker time is measurable, Workforce SHOULD preserve utilization evidence.

Conceptually:

```text
Labor Utilization
=
Consumed / Available Capacity
```

The numerator and denominator MUST use compatible temporal and capacity semantics.

Utilization above or below an expected range MUST remain visible rather than being silently normalized.

## 6. Staffing cost evidence

Where a staffing requirement has an associated cost basis, Workforce SHOULD preserve evidence such as:

- required labor-hours;
- Worker/role basis;
- scheduled hours;
- committed hours;
- actual hours where observed;
- applicable rate basis where provided;
- overtime implication;
- shift differential;
- training implication;
- recruitment implication;
- contractor/external labor implication.

A cost basis is evidence, not automatically an accounting entry.

## 7. Resource consumption evidence

For economically material resources, Workforce SHOULD preserve:

- resource identity/class;
- required quantity;
- allocated quantity;
- committed quantity;
- consumed quantity;
- remaining quantity/capacity;
- utilization period;
- allocation basis;
- cost basis where available;
- source/provenance.

Example:

```text
Required material       = 1,000 units
Allocated material      = 1,000 units
Consumed material       =   620 units
Remaining material      =   380 units
```

Workforce MUST NOT infer monetary value unless an authoritative or explicitly provided cost basis exists.

## 8. Organizational cost evidence

Workforce SHOULD support aggregation of operational evidence at explicit organizational levels:

- Worker;
- position;
- team;
- department;
- organization;
- institution;
- workload;
- work unit.

Aggregation MUST preserve the semantic basis of the underlying evidence.

A department-level operational cost signal MUST remain traceable to the contributing evidence where material.

## 9. Planned versus actual

Workforce MUST distinguish planned operational quantities from observed or recorded actual quantities.

Examples:

```text
Planned labor hours = 100
Actual labor hours  = 120
```

and:

```text
Planned resource use = 500 units
Actual resource use  = 620 units
```

The difference MUST remain visible as variance evidence.

## 10. Variance

Where compatible quantities exist:

```text
Variance
=
Actual − Planned
```

Examples include:

- labor variance;
- capacity variance;
- staffing variance;
- resource variance;
- schedule variance;
- output variance;
- utilization variance.

Variance MUST preserve the underlying units and context.

Workforce MUST NOT assign monetary meaning to a non-monetary variance without an applicable cost basis.

## 11. Cost consequences

Operational events MAY create economic consequences even when the final accounting result is determined elsewhere.

Examples:

- additional staffing;
- overtime;
- idle labor;
- resource rental;
- equipment utilization;
- maintenance;
- material consumption;
- delayed work;
- rework;
- failed execution;
- capacity shortage;
- surplus staffing.

Workforce SHOULD preserve evidence of the operational cause and relevant quantities.

Economy remains responsible for authoritative financial treatment.

## 12. P&L-compatible evidence

Workforce SHOULD provide evidence that can support economic reporting such as:

- labor demand;
- labor utilization;
- staffing cost basis;
- resource consumption;
- organizational operating cost signals;
- planned cost basis;
- actual cost evidence;
- variance evidence;
- cost per work unit;
- cost consequence of exceptions.

"P&L-compatible" means the evidence can be consumed by the authoritative Economy domain. It does not mean Workforce owns or calculates the authoritative P&L.

## 13. Cost per work unit

Where a valid cost basis and measurable output exist, the evidence MAY support:

```text
Cost per Work Unit
=
Applicable Cost / Completed Work Units
```

The implementation MUST preserve:

- cost basis;
- work-unit definition;
- period;
- organizational context;
- attribution;
- source evidence.

If either cost or completed work is unknown or incompatible, the result MUST remain unavailable or inconclusive rather than being invented.

## 14. Economic constraints

Economic constraints MAY prevent otherwise operationally feasible work.

Examples:

```text
Staffing required        = 5 Workers
Qualified Workers        = 5
Resource available       = sufficient
Approved budget          = insufficient
```

Result:

```text
Operational staffing     = sufficient
Economic authorization   = insufficient
Execution                = constrained
```

Workforce MUST preserve the constraint and MUST NOT create budget or financial authority.

## 15. Budget evidence

Where budget is an input to Workforce operations, the implementation SHOULD preserve:

- budget reference;
- applicable organizational scope;
- amount or capacity where supplied;
- validity period;
- committed amount where known;
- remaining amount where authoritative evidence is available;
- authority reference;
- source/provenance.

A budget request or cost estimate MUST NOT be represented as approved budget.

## 16. Economic attribution

Material economic evidence MUST remain attributable.

The evidence chain SHOULD support reconstruction of:

```text
Workload
   ↓
Worker / Organization
   ↓
Time / Capacity
   ↓
Resource Use
   ↓
Operational Outcome
   ↓
Economic Evidence
   ↓
Economy Processing
```

Attribution MUST remain compatible with the Phase 2 attribution and provenance models.

## 17. Temporal validity

Economic evidence is time-sensitive.

The implementation MUST distinguish where materially relevant:

- effective time;
- scheduled time;
- actual time;
- recording time;
- accounting period;
- validity window.

A later correction MUST NOT erase the historical operational condition that generated earlier evidence.

## 18. Economic evidence versus accounting truth

The boundary is explicit:

```text
Workforce
= operational evidence + context

Economy
= authoritative accounting / financial truth
```

Workforce MUST NOT:

- create authoritative ledger entries merely from operational assumptions;
- invent monetary values;
- override accounting policy;
- declare treasury truth;
- redefine accounting periods;
- bypass Economy authority.

## 19. Missing economic inputs

If an economic consequence cannot be determined because a required cost basis, quantity, period, or authoritative input is missing, Workforce MUST preserve an explicit unknown, insufficient, or inconclusive condition.

It MUST NOT silently assume:

- labor rate;
- resource price;
- budget availability;
- accounting treatment;
- monetary value;
- financial approval.

## 20. Economic evidence and simulation

Simulation MAY vary:

- labor rates as explicit assumptions;
- Worker count;
- productivity;
- working hours;
- staffing;
- resource quantities;
- resource costs;
- workloads;
- budgets;
- operating windows.

Simulation MUST label assumed economic inputs distinctly from observed or authoritative evidence.

A simulated cost MUST NOT be represented as historical or authoritative financial truth.

## 21. Recalculation and revalidation

Economic evidence MUST be re-evaluated when material inputs change, including:

- workload;
- Worker assignment;
- working time;
- capacity;
- staffing;
- resource allocation;
- resource consumption;
- cost basis;
- budget condition;
- organizational scope;
- operating window;
- authorization;
- accounting-relevant external input.

Later evidence MUST NOT rewrite historical evidence without preserving the correction and its provenance.

## 22. Failure and insufficiency

If economic evidence is incomplete, contradictory, stale, or unavailable, the system MUST preserve that condition.

The Workforce MUST NOT convert incomplete evidence into a confident financial conclusion.

Where the missing economic condition materially blocks execution, the condition SHOULD become an escalation candidate under the Phase 4 escalation model.

## 23. Acceptance invariants

The implementation MUST satisfy at least these invariants:

1. Material operational consumption remains economically representable.
2. Labor demand is distinguishable from actual labor supplied.
3. Staffing cost evidence is distinguishable from authoritative accounting.
4. Resource consumption evidence is attributable.
5. Planned and actual quantities remain distinguishable.
6. Variance preserves compatible units and context.
7. Cost per work unit requires valid cost and output bases.
8. Economic constraints can prevent otherwise operationally feasible work.
9. Budget requests and estimates are not treated as approved budget.
10. Economic evidence preserves attribution, provenance, and temporal validity.
11. Workforce does not manufacture monetary values or accounting truth.
12. Missing economic inputs produce explicit insufficiency rather than optimistic assumptions.
13. Simulation assumptions remain distinguishable from historical or authoritative evidence.
14. Historical economic evidence is not silently rewritten by later changes.
15. Economy remains the authoritative accounting domain.

## 24. Gate G5 contribution

This artifact contributes evidence for Gate G5 by establishing the economic reality required to demonstrate:

- labor demand;
- labor utilization;
- staffing cost;
- resource consumption;
- organizational cost evidence;
- planned versus actual quantities;
- variance;
- cost consequences;
- cost-per-work-unit evidence;
- budget constraints;
- P&L-compatible output;
- no economically impossible execution.

This document defines the Workforce economic-evidence contract only. It does not prescribe a ledger, accounting engine, treasury implementation, financial reporting system, budgeting engine, or P&L implementation.
