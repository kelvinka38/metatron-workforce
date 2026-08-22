# METATRON WORKFORCE — PHASE 5 / 02 CAPACITY MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Semantic foundation:** Phase 2 canonical entity, lifecycle, transition, temporal, provenance, attribution, authorization-reference, economic-evidence, and learning-lineage models.
**Related artifact:** `01_WORKING_TIME_MODEL.md`
**Phase:** 5 — Time / Capacity / Staffing / Reality

## 1. Purpose

This artifact defines the Workforce-side capacity model required by Gate G5.

Capacity represents the amount of work a Worker, team, department, or other Workforce unit can legitimately support during a defined period under actual working-time, availability, staffing, skill, resource, and operational constraints.

Capacity is not an unlimited attribute of a Worker. It is time-bounded, context-dependent, and constrained by reality.

## 2. Capacity dimensions

The implementation MUST distinguish at least:

- theoretical capacity;
- scheduled capacity;
- unavailable capacity;
- available capacity;
- allocated capacity;
- committed capacity;
- consumed capacity;
- remaining capacity;
- required capacity;
- capacity deficit.

These terms MUST NOT be treated as interchangeable.

## 3. Capacity identity and context

A material capacity record MUST preserve enough context to answer:

- whose capacity is represented;
- which organizational context applies;
- which period applies;
- which work or activity class is relevant;
- which working-time assumptions apply;
- which availability constraints apply;
- which resource constraints apply;
- which skill/capability constraints apply;
- who or what produced the record;
- when the record was recorded.

Capacity MAY be represented for an individual Worker, team, department, position, or other Workforce construct when the aggregation semantics are explicit.

## 4. Theoretical capacity

Theoretical capacity is the maximum capacity implied by the applicable working-time model before availability, leave, rest, assignments, resource constraints, or other operational restrictions are applied.

It MUST NOT be interpreted as executable capacity.

Example:

```text
Scheduled working time = 40 hours
Theoretical capacity  = 40 labor-hours
```

If the Worker is unavailable for part of the period, theoretical capacity remains historically distinguishable from available capacity.

## 5. Available capacity

The canonical relationship is:

```text
Available Capacity
=
Scheduled Capacity
−
Unavailable Capacity
```

The implementation MUST preserve the inputs used to derive the result where materially relevant.

Unavailable capacity MAY include:

- leave;
- rest requirements;
- sickness/unavailability records where applicable;
- closed operating windows;
- organizational restrictions;
- other explicitly modeled non-working periods.

The model MUST NOT silently convert unavailable time into available work time.

## 6. Allocation and commitment

Allocated capacity represents capacity assigned or reserved for a defined organizational purpose.

Committed capacity represents capacity that the Workforce has accepted as an obligation against available capacity.

A commitment MUST have:

- owner/context;
- time window;
- quantity or measurable capacity basis;
- originating work/assignment where applicable;
- attribution;
- applicable authority;
- evidence/reference.

The system MUST prevent commitments from exceeding the capacity boundary unless an explicit overcommitment/exception semantic is defined and preserved.

## 7. Consumed capacity

Consumed capacity represents capacity actually used by completed or materially progressed work according to the applicable execution evidence.

Consumed capacity MUST NOT be inferred solely from assignment creation or commitment.

Where execution evidence exists, the implementation SHOULD preserve the distinction between:

```text
Committed ≠ Consumed
Planned   ≠ Actual
```

## 8. Remaining capacity

The canonical relationship is:

```text
Remaining Capacity
=
Available Capacity
−
Committed Capacity
```

The implementation MAY expose additional derived measures such as remaining scheduled capacity after actual consumption, but those measures MUST have explicit definitions and MUST NOT overwrite the canonical commitment boundary.

Negative remaining capacity MUST be treated as a material condition requiring explicit handling, such as overcommitment, exception, or escalation.

## 9. Required capacity

Required capacity represents the capacity needed to complete a defined workload under the applicable assumptions.

It MUST be derived from explicit workload and planning assumptions rather than from the amount of capacity currently available.

The system MUST NOT reduce required capacity merely because available capacity is insufficient.

Example:

```text
Required capacity = 192 labor-hours
Available capacity = 64 labor-hours
Deficit = 128 labor-hours
```

The workload therefore remains under-covered.

## 10. Coverage

Coverage is defined as:

```text
Coverage Ratio
=
Available Capacity / Required Capacity
```

Where:

```text
Required Capacity > 0
```

the result MUST be interpretable as the proportion of required capacity currently covered.

For the canonical example:

```text
Required = 192 labor-hours
Available = 64 labor-hours

Coverage = 64 / 192
         = 0.3333...
         = 33.3%

Deficit = 192 − 64
        = 128 labor-hours
```

If required capacity is zero, the implementation MUST use an explicit zero-demand semantic rather than performing an undefined division.

## 11. Capacity constraints

Capacity calculations MUST account for material constraints including:

- working time;
- availability;
- staffing level;
- required skills/capabilities;
- shifts;
- equipment;
- facilities;
- materials;
- capital;
- budgets;
- information dependencies;
- dependency on other Workers;
- operating windows.

A Worker being present does not imply that the required capacity is executable.

## 12. Skill and capability capacity

Where work requires a specific skill or capability, aggregate headcount MUST NOT be treated as equivalent to qualified capacity.

The model MUST be able to distinguish at least conceptually:

```text
Total Available Capacity
Qualified Available Capacity
Unqualified Capacity
Skill Deficit
```

Example:

```text
Required qualified labor = 40 hours
Available total labor    = 80 hours
Available qualified     = 16 hours

Qualified coverage = 16 / 40 = 40%
Qualified deficit  = 24 hours
```

The system MUST NOT report 100% coverage merely because total labor is sufficient.

## 13. Shift and operational-window constraints

Capacity MUST respect the applicable working-time and operating-window model.

Work scheduled outside a valid operating window MUST NOT silently increase available capacity.

Shift overlap, rest requirements, and other temporal constraints MUST be preserved where they materially affect executable capacity.

## 14. Aggregation

Capacity may be aggregated across organizational levels, but aggregation MUST preserve semantic meaning.

Possible levels include:

```text
Worker
  ↓
Team
  ↓
Department
  ↓
Organization
```

Aggregated capacity SHOULD identify the underlying period and scope.

The implementation MUST avoid double-counting capacity when a Worker appears in multiple organizational aggregations.

## 15. Overcommitment

If:

```text
Committed Capacity > Available Capacity
```

the Workforce MUST preserve the overcommitment as an explicit material condition.

It MUST NOT silently create additional Worker time.

Possible resulting actions include:

- staffing request;
- workload reprioritization;
- schedule change;
- escalation;
- authorized exception;
- deferred work;
- additional resource allocation.

The appropriate action remains subject to the applicable authority and organizational models.

## 16. Capacity deficit

A capacity deficit represents the difference between required capacity and available capacity where required capacity exceeds available capacity.

Conceptually:

```text
Capacity Deficit
=
max(Required Capacity − Available Capacity, 0)
```

A deficit MUST remain attributable to the workload and assumptions that produced it.

The Workforce MUST NOT represent a deficit as successfully completed work without corresponding execution evidence.

## 17. Economic evidence

Capacity records MUST provide sufficient evidence for downstream economic processing.

Where materially applicable, preserve:

- labor demand;
- available labor;
- committed labor;
- consumed labor;
- utilization;
- staffing deficit;
- staffing cost basis;
- resource consumption basis;
- planned versus actual capacity.

Workforce provides evidence. Economy remains the authoritative accounting domain.

## 18. Utilization

Utilization MUST have an explicit denominator and time window.

A possible operational measure is:

```text
Utilization
=
Consumed Capacity / Available Capacity
```

The implementation MUST NOT present utilization without identifying the period and capacity basis used.

Utilization above 100% MUST NOT be normalized away; it indicates a material semantic condition requiring investigation or an explicitly defined basis.

## 19. Recalculation and temporal validity

Capacity is time-sensitive.

Material changes MUST trigger recalculation or revalidation where applicable, including:

- working-time change;
- availability change;
- leave/unavailability;
- assignment change;
- staffing change;
- capability/qualification change;
- resource unavailability;
- budget restriction;
- operating-window change;
- policy change.

Historical capacity records MUST remain interpretable under the assumptions applicable when they were produced.

## 20. Failure and insufficiency

If the system cannot determine capacity reliably because required inputs are missing or contradictory, it MUST preserve an explicit insufficient/inconclusive condition.

It MUST NOT silently substitute optimistic capacity assumptions.

Where the insufficiency materially blocks work, the condition SHOULD become an escalation candidate under the Phase 4 escalation model.

## 21. Simulation constraint

Simulation MAY vary:

- Worker count;
- shift structure;
- productivity assumptions;
- working hours;
- availability;
- workloads;
- labor costs;
- resource limits.

Simulation MUST preserve real capacity constraints.

A simulation is invalid if it completes work solely by allowing impossible capacity.

## 22. Acceptance invariants

The implementation MUST satisfy at least these invariants:

1. Capacity is time-bounded.
2. Available capacity cannot exceed the applicable scheduled capacity without an explicit modeled reason.
3. Required capacity is independent of available capacity.
4. Insufficient capacity remains visible as a deficit or coverage condition.
5. Qualified capacity is not equivalent to total headcount.
6. Commitments consume a defined capacity basis.
7. Consumption is distinguishable from commitment.
8. Overcommitment is explicit.
9. Capacity calculations preserve attribution and provenance.
10. Capacity history is not rewritten by later organizational changes.
11. Economic evidence remains attributable to the underlying capacity/work context.
12. Workforce does not manufacture authority, resources, or unlimited working time.

## 23. Gate G5 contribution

This artifact contributes evidence for Gate G5 by establishing the canonical capacity semantics needed to demonstrate:

- working hours;
- availability;
- capacity;
- staffing;
- coverage;
- resource constraints;
- labor/economic evidence;
- cost consequences;
- P&L-compatible output;
- no impossible execution.

This document defines the semantic contract only. It does not prescribe a specific database schema, scheduling engine, optimization algorithm, accounting implementation, or simulation framework.
