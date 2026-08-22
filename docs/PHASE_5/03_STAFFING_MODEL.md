# METATRON WORKFORCE — PHASE 5 / 03 STAFFING MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Semantic foundation:** Phase 2 canonical entity, lifecycle, transition, temporal, provenance, attribution, authorization-reference, economic-evidence, and learning-lineage models.
**Related artifacts:** `01_WORKING_TIME_MODEL.md`, `02_CAPACITY_MODEL.md`
**Phase:** 5 — Time / Capacity / Staffing / Reality

## 1. Purpose

This artifact defines the Workforce-side staffing model required by Gate G5.

Staffing determines whether the Workers, positions, skills, shifts, availability, capacity, and resources required for a defined workload are actually present and usable within the applicable organizational and temporal context.

Staffing is not equivalent to headcount. A Worker being admitted, active, or assigned does not by itself mean that the required work is adequately staffed.

## 2. Staffing dimensions

The implementation MUST distinguish at least:

- required staffing;
- current staffing;
- available staffing;
- qualified staffing;
- allocated staffing;
- committed staffing;
- staffing deficit;
- skill deficit;
- shift deficit;
- capacity deficit;
- staffing cost implication.

These terms MUST NOT be treated as interchangeable.

## 3. Staffing identity and context

A material staffing requirement MUST preserve enough context to answer:

- which organization, department, team, position, or work context requires staffing;
- which workload or operational objective creates the requirement;
- which period and operating window apply;
- which Worker quantity is required;
- which skills/capabilities are required;
- which shifts or coverage windows are required;
- which capacity basis is required;
- which resources or facilities constrain staffing;
- which cost assumptions apply;
- who or what produced the requirement;
- when the requirement was recorded;
- under which authority the staffing decision is made.

Staffing MAY be represented at Worker, team, department, organization, or other Workforce levels when aggregation semantics are explicit.

## 4. Required staffing

Required staffing represents the Workers or qualified Worker capacity needed to satisfy a defined workload, operating requirement, or coverage obligation under explicit assumptions.

Required staffing MUST be derived from workload and operating requirements rather than from currently available headcount.

The implementation MUST preserve material derivation assumptions, including:

- workload quantity;
- expected productivity;
- required labor-hours;
- operating window;
- shift structure;
- required skills;
- required roles/positions;
- required concurrency;
- resource constraints;
- service or completion deadline.

The system MUST NOT reduce required staffing merely because insufficient Workers are currently available.

## 5. Current and available staffing

Current staffing represents Workers or positions presently recognized within the applicable organizational context.

Available staffing represents Workers who can legitimately contribute to the defined work during the applicable period and operating window.

These MUST remain distinct.

Example:

```text
Current Workers     = 10
Unavailable Workers = 3
Available Workers   = 7
```

Availability MUST respect the working-time and capacity models and MAY be restricted by schedules, leave, rest, assignments, commitments, suspension, operating windows, resources, location/facility constraints, authorization, or other explicit operational restrictions.

An active Worker is not automatically an available Worker.

## 6. Qualified staffing

Qualified staffing represents available Workers whose capabilities satisfy the workload requirements.

The implementation MUST distinguish:

```text
Current Staffing
Available Staffing
Qualified Available Staffing
```

Example:

```text
Required qualified Workers = 5
Available Workers          = 8
Qualified Workers          = 3
Qualified deficit          = 2 Workers
```

The system MUST NOT report full staffing merely because total headcount is sufficient.

## 7. Role, position, and substitution

Where a workload requires a defined role or position, that requirement MUST remain separate from generic Worker count.

Example:

```text
1 Head of Farm
2 Farm Operators
1 Maintenance Worker
```

Four Workers with unrelated roles do not satisfy this requirement unless applicable policy permits substitution.

Any substitution MUST be explicit, attributable, authorized, time-valid, and preserved as evidence.

## 8. Shift staffing and temporal coverage

Staffing MUST account for required temporal coverage.

A staffing requirement MAY specify:

- shift identifier;
- start/end time;
- time zone;
- required Worker count;
- required qualified Worker count;
- required role/position;
- required capacity;
- required resource coverage.

Example:

```text
Night shift
Required Workers    = 4
Required qualified  = 2
Available Workers   = 5
Available qualified = 1

Headcount coverage  = 125%
Qualified coverage  = 50%
Qualified deficit   = 1 Worker
```

Sufficient total headcount MUST NOT hide a temporal or qualification deficit.

## 9. Staffing and capacity

Staffing and capacity are related but distinct.

Staffing answers:

```text
Do we have the Workers / qualified Workers needed?
```

Capacity answers:

```text
How much legitimate work can those Workers support?
```

A staffing count MUST NOT be interpreted as executable capacity without the applicable working-time, availability, commitment, skill, and resource assumptions.

Conversely, sufficient aggregate capacity MUST NOT be interpreted as sufficient staffing when a required role, skill, shift, or concurrency constraint is missing.

## 10. Coverage and deficit

Where staffing is expressed as a measurable qualified Worker requirement:

```text
Staffing Coverage Ratio
=
Available Qualified Staffing / Required Qualified Staffing
```

For:

```text
Required Qualified Staffing > 0
```

the ratio represents the proportion of the qualified requirement covered.

Example:

```text
Required qualified staffing = 6
Available qualified staffing = 4

Coverage = 4 / 6 = 66.7%
Deficit  = 6 − 4 = 2 Workers
```

If the requirement is zero, the implementation MUST use an explicit zero-demand semantic rather than undefined division.

A staffing deficit is:

```text
Staffing Deficit
=
max(Required Staffing − Available Staffing, 0)
```

Qualified staffing deficit is:

```text
Qualified Staffing Deficit
=
max(Required Qualified Staffing − Available Qualified Staffing, 0)
```

Deficits MUST remain attributable to the underlying workload, period, skill, role, shift, and organizational context.

## 11. Skill deficit

A skill deficit exists when available Workers cannot satisfy capability requirements even when total Worker count may be sufficient.

Example:

```text
Required labor            = 40 hours
Available labor           = 80 hours
Qualified available labor = 16 hours
Qualified deficit         = 24 hours
```

Skill deficit MUST remain distinct from generic staffing deficit.

Possible remedies MAY include recruiting, training, reassignment, authorized substitution, workload reduction/deferment, permitted external resources, or escalation. The selected remedy remains subject to applicable authority and policy.

## 12. Resource-constrained staffing

Staffing MUST account for non-Worker constraints where those constraints determine whether Workers can actually perform the work.

Material constraints MAY include:

- equipment;
- facilities;
- vehicles;
- materials;
- capital;
- budgets;
- information dependencies;
- required supervision;
- dependency on another Worker/team;
- operating permits or other authorized conditions.

Example:

```text
Required maintenance Workers = 2
Available maintenance Workers = 2
Required equipment units      = 2
Available equipment units     = 1
```

The staffing count alone MUST NOT cause the work to be represented as fully executable.

## 13. Staffing requests and lifecycle

When a material deficit exists, Workforce MAY create a staffing request.

A staffing request SHOULD preserve:

- request identity;
- requesting organization/context;
- originating workload;
- required Worker quantity;
- required qualification;
- required role/position;
- required shift/window;
- required capacity;
- start/end validity;
- priority where modeled;
- cost basis where available;
- initiating actor;
- authority reference;
- evidence/provenance;
- current state.

Creating a staffing request MUST NOT imply that additional Workers exist.

A request MAY use semantics such as:

```text
requested
  ↓
reviewing
  ↓
approved / rejected / deferred
  ↓
fulfilling
  ↓
fulfilled / partially_fulfilled / cancelled
```

Exact states remain subject to the canonical Workforce lifecycle and transition models.

Every material transition MUST preserve current state, requested next state, initiating actor, authority source, preconditions, temporal validity, evidence/provenance, resulting event, and failure behavior.

## 14. Partial fulfillment and overstaffing

A staffing requirement MAY be partially fulfilled and the remaining deficit MUST remain explicit.

Example:

```text
Required Workers = 10
Fulfilled        = 6
Remaining deficit = 4
Coverage         = 60%
```

The requirement MUST NOT be marked fully fulfilled merely because some Workers were added.

Staffing above the requirement MUST remain distinguishable from adequate staffing.

Example:

```text
Required Workers  = 4
Available Workers = 8
```

Overstaffing MAY indicate surplus capacity, inefficiency, redundancy, overlapping shifts, contingency, or another legitimate reason. The applicable reason SHOULD be preserved where material. It MUST NOT automatically be treated as an error unless policy defines it as such.

## 15. Economic evidence

Staffing decisions MAY create economic consequences.

Where materially applicable, Workforce SHOULD preserve evidence sufficient for Economy to determine:

- required labor cost;
- incremental staffing cost;
- overtime implication;
- shift differential;
- recruitment cost basis;
- training cost basis;
- contractor/external labor cost basis;
- cost of idle or surplus staffing;
- planned versus actual staffing cost.

Workforce provides evidence and operational context. Economy remains the authoritative accounting domain.

## 16. Staffing versus authorization and assignment

A Worker being technically available does not imply authority to perform every workload.

Staffing MUST remain compatible with authorization.

A result MAY therefore be:

```text
Staffed but unauthorized
```

or:

```text
Authorized but insufficiently staffed
```

These are materially different conditions and MUST NOT be collapsed.

Likewise:

```text
Assignment exists
≠
Staffing is sufficient
```

and:

```text
Staffing is sufficient
≠
Assignment is authorized
```

The implementation MUST preserve both semantics.

## 17. Recalculation and revalidation

Staffing MUST be re-evaluated when material inputs change, including:

- Worker activation or retirement;
- Worker suspension;
- organizational relationship change;
- role/position change;
- assignment change;
- working-time change;
- availability change;
- leave/unavailability;
- capability/qualification change;
- shift change;
- workload change;
- resource availability change;
- budget restriction;
- authorization change;
- operating-window change;
- policy change.

A later staffing result MUST NOT rewrite the historical staffing condition that existed at an earlier time.

## 18. Evidence, attribution, failure, and insufficiency

Every material staffing determination MUST remain attributable.

The evidence model SHOULD allow reconstruction of:

```text
Requirement
   ↓
Assumptions
   ↓
Available Workers
   ↓
Qualification / Role Matching
   ↓
Capacity / Resource Constraints
   ↓
Staffing Result
   ↓
Deficit / Coverage
   ↓
Decision or Escalation
```

Relevant actor, authority, temporal validity, provenance, and evidence references MUST be preserved.

If staffing cannot be determined reliably because required inputs are missing, contradictory, stale, or unavailable, the system MUST preserve an explicit insufficient/inconclusive condition.

It MUST NOT silently assume Workers, qualifications, shift coverage, resources, authority, budget, or capacity exist.

Where insufficiency materially blocks work, it SHOULD become an escalation candidate under the Phase 4 escalation model.

## 19. Simulation constraint

Simulation MAY vary Worker count, skill mix, role distribution, shifts, availability, workload, productivity, labor cost, resource limits, and operating windows.

Simulation MUST preserve staffing and capacity constraints.

A simulation is invalid if it completes work solely by inventing Workers, qualifications, time, resources, or authority.

## 20. Acceptance invariants

The implementation MUST satisfy at least these invariants:

1. Staffing requirements are time-bounded.
2. Required staffing is independent of current available staffing.
3. Current headcount is distinguishable from available staffing.
4. Available staffing is distinguishable from qualified staffing.
5. Role/position requirements are not equivalent to generic Worker count.
6. Shift requirements are time-specific.
7. Staffing deficits remain visible and attributable.
8. Partial fulfillment remains distinguishable from full fulfillment.
9. Resource constraints can prevent otherwise sufficient staffing from being executable.
10. Staffing does not manufacture authority, Workers, capacity, or resources.
11. Staffing decisions preserve attribution, provenance, and temporal validity.
12. Historical staffing conditions are not rewritten by later changes.
13. Staffing cost implications remain evidence for Economy rather than Workforce accounting.
14. Staffing status remains distinguishable from assignment and authorization status.
15. Missing or contradictory inputs produce explicit insufficiency rather than optimistic assumptions.

## 21. Gate G5 contribution

This artifact contributes evidence for Gate G5 by establishing the staffing semantics required to demonstrate:

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

This document defines the semantic staffing contract only. It does not prescribe a specific database schema, staffing optimizer, recruitment system, scheduling engine, accounting implementation, or simulation framework.
