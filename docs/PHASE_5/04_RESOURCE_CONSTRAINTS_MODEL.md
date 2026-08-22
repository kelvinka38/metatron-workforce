# METATRON WORKFORCE — PHASE 5 / 04 RESOURCE CONSTRAINTS MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Semantic foundation:** Phase 2 canonical entity, lifecycle, transition, temporal, provenance, attribution, authorization-reference, economic-evidence, and learning-lineage models.
**Related artifacts:** `01_WORKING_TIME_MODEL.md`, `02_CAPACITY_MODEL.md`, `03_STAFFING_MODEL.md`
**Phase:** 5 — Time / Capacity / Staffing / Reality

## 1. Purpose

This artifact defines the Workforce-side resource constraint model required by Gate G5.

A Worker, team, assignment, or staffing result does not imply that work is executable. Execution also depends on the material resources, facilities, equipment, materials, capital, information, dependencies, and authorized operating conditions required by the workload.

The model exists to prevent Workforce from representing impossible work as executable merely because Workers or nominal capacity exist.

## 2. Resource constraint principle

A resource constraint exists when a required resource condition limits, prevents, delays, or changes the legitimate execution of work.

The implementation MUST distinguish:

- required resource;
- available resource;
- usable resource;
- allocated resource;
- committed resource;
- consumed resource;
- remaining resource;
- resource deficit;
- resource contention;
- resource dependency;
- resource availability window;
- resource authorization condition.

These terms MUST NOT be treated as interchangeable.

## 3. Resource identity and context

A material resource requirement MUST preserve enough context to answer:

- which workload requires the resource;
- which organization, department, team, or work context requires it;
- which resource is required;
- what quantity or capacity is required;
- which period and operating window apply;
- when the resource is required;
- whether the requirement is concurrent or sequential;
- which Workers or roles depend on it;
- which authority permits its use;
- who or what produced the requirement;
- when the requirement was recorded;
- which evidence/provenance supports it.

Resources MAY be modeled as physical, financial, informational, infrastructural, organizational, or other explicitly defined resource classes.

## 4. Resource classes

The implementation SHOULD distinguish at least:

### 4.1 Equipment

Examples:

- machinery;
- vehicles;
- tools;
- computers;
- specialized instruments.

### 4.2 Facilities

Examples:

- rooms;
- production areas;
- farms;
- warehouses;
- laboratories;
- operational sites.

### 4.3 Materials

Examples:

- inventory;
- consumables;
- raw materials;
- spare parts;
- packaging.

### 4.4 Capital / budget

Examples:

- approved budget;
- spending allocation;
- capital availability;
- authorized expenditure limit.

Workforce provides operational evidence. Economy remains authoritative for accounting and financial truth.

### 4.5 Information

Examples:

- required records;
- instructions;
- measurements;
- source data;
- approved plans;
- required documents.

Missing or stale information MAY itself be a material execution constraint.

### 4.6 Human or organizational dependency

A workload may depend on:

- another Worker;
- another team;
- another department;
- an external party;
- required supervision;
- required approval;
- another completed activity.

Such dependencies MUST remain explicit rather than being treated as generic capacity.

## 5. Required resources

A workload MUST be able to express the resources necessary for legitimate execution.

A requirement MAY specify:

- resource identity or class;
- quantity;
- capacity;
- quality or qualification condition;
- start time;
- end time;
- duration;
- concurrency requirement;
- location/facility context;
- authorization requirement;
- dependency;
- substitution rule;
- criticality.

Required resources MUST be derived from the workload and applicable operating assumptions rather than from whatever resources happen to be available.

The system MUST NOT reduce a requirement merely because the organization lacks the required resource.

## 6. Availability

Resource availability represents whether a resource can legitimately be used during the applicable period and operating window.

A resource is not available merely because it exists.

Availability MAY be restricted by:

- existing allocation;
- existing commitment;
- maintenance;
- downtime;
- location;
- working-time window;
- operating window;
- ownership or organizational scope;
- authorization;
- budget restriction;
- prerequisite completion;
- another resource dependency;
- safety or policy condition.

The implementation MUST preserve the reason for material unavailability where known.

## 7. Usability

Available resources MUST remain distinguishable from usable resources.

A resource may exist and be technically available but remain unusable because a material condition is not satisfied.

Example:

```text
Equipment units             = 2
Available equipment units   = 2
Usable equipment units      = 1
Reason                      = 1 unit awaiting maintenance
```

Workforce MUST NOT treat the second unit as executable capacity until the applicable usability condition is satisfied.

## 8. Allocation and commitment

Resource allocation identifies resources reserved or assigned to a defined workload, organization, or operating context.

Resource commitment identifies resource capacity already obligated under an applicable assignment, work, schedule, or institutional commitment.

Therefore:

```text
Available Resource
≠
Allocated Resource
≠
Committed Resource
```

The model MUST preserve these distinctions where materially relevant.

## 9. Resource consumption

Where work consumes a measurable resource, Workforce MUST preserve operational evidence sufficient to reconstruct:

```text
Required
   ↓
Allocated
   ↓
Consumed
   ↓
Remaining
   ↓
Variance
```

Consumption MUST NOT be inferred solely from assignment or completion status unless the applicable operational rule explicitly establishes that inference.

Where accounting truth is required, the evidence MUST be passed to the authoritative Economy domain.

## 10. Resource capacity

A resource may have a finite capacity rather than a simple available/unavailable state.

Examples:

- machine operating hours;
- vehicle load capacity;
- storage capacity;
- room occupancy;
- network throughput;
- budget amount;
- inventory quantity.

Conceptually:

```text
Remaining Resource Capacity
=
Available Resource Capacity
−
Committed Resource Capacity
```

If consumption is material:

```text
Remaining Resource Capacity
=
Available Resource Capacity
−
Committed Capacity
−
Consumed Capacity Not Yet Reflected in Commitment
```

The exact accounting semantics remain implementation-specific, but double counting MUST be prevented.

## 11. Resource deficit

A resource deficit exists when the required resource quantity or capacity exceeds the legitimately usable resource quantity or capacity.

For quantity:

```text
Resource Deficit
=
max(Required Quantity − Usable Quantity, 0)
```

For capacity:

```text
Resource Capacity Deficit
=
max(Required Capacity − Usable Capacity, 0)
```

Example:

```text
Required equipment = 3 units
Usable equipment   = 1 unit

Deficit = 3 − 1
       = 2 units
```

The deficit MUST remain attributable to the workload, resource, period, and organizational context.

## 12. Resource contention

Resource contention exists when multiple legitimate demands compete for the same limited resource.

Example:

```text
Available vehicle capacity = 10 hours
Work A committed           = 7 hours
Work B committed           = 6 hours

Total commitment           = 13 hours
Contention                  = 3 hours
```

The system MUST NOT represent both workloads as fully executable without an explicit scheduling, allocation, prioritization, or policy decision.

Contention MAY require:

- scheduling;
- prioritization;
- reassignment;
- deferral;
- partial fulfillment;
- escalation;
- approved substitution;
- additional resource acquisition.

Any selected resolution MUST remain attributable and authorized.

## 13. Resource dependencies

A workload MAY depend on another resource or activity becoming available first.

Examples:

```text
Maintenance work
    ↓
requires spare part
    ↓
requires procurement
    ↓
requires approved budget
```

or:

```text
Worker execution
    ↓
requires supervisor review
    ↓
requires supervisor availability
```

A dependency MUST remain explicit.

The existence of downstream Worker capacity MUST NOT cause an unmet upstream dependency to disappear.

## 14. Temporal constraints

Resource constraints are time-sensitive.

The implementation MUST preserve where materially relevant:

- effective time;
- scheduled time;
- actual time;
- availability window;
- allocation window;
- commitment window;
- maintenance window;
- expiration time;
- operating window;
- authorization validity.

Example:

```text
Required resource window: 08:00–12:00
Resource available:       13:00–17:00

Result: requirement remains uncovered.
```

A resource available at another time does not satisfy the current requirement unless the applicable scheduling rule explicitly permits the substitution.

## 15. Resource authorization

Technical availability does not imply authority to use a resource.

The Workforce resource result MUST remain compatible with authorization semantics.

A resource may therefore be:

```text
Available
but unauthorized
```

or:

```text
Authorized
but unavailable
```

or:

```text
Available and authorized
but insufficient in quantity/capacity
```

These conditions are materially distinct and MUST NOT be collapsed.

Workforce MUST NOT manufacture authority owned by Governance, Gateway, Economy, Execution, or another external domain.

## 16. Substitution

A resource substitution MAY be permitted where policy allows it.

A substitution MUST be:

- explicit;
- equivalent under the applicable requirement;
- authorized;
- attributable;
- time-valid;
- preserved as evidence.

The system MUST NOT silently substitute an arbitrary resource merely to make a workload appear executable.

Example:

```text
Required: vehicle type A
Substitute: vehicle type B

Only valid if the applicable policy permits B as an equivalent substitute.
```

## 17. Resource constraints and staffing

Staffing and resource constraints interact but remain distinct.

Example:

```text
Required Workers          = 4
Available qualified       = 4
Required equipment        = 4
Usable equipment          = 2
```

Result:

```text
Staffing coverage         = sufficient
Resource coverage         = insufficient
Execution                 = constrained
```

The Workforce MUST preserve both facts rather than reporting the workload as fully executable.

## 18. Resource constraints and capacity

Worker capacity does not create resource capacity.

Example:

```text
Available labor capacity  = 80 hours
Available machine capacity = 20 hours
Required labor             = 40 hours
Required machine           = 40 hours
```

Labor is sufficient, but machine capacity is deficient by 20 hours.

The system MUST identify the binding constraint rather than using aggregate labor capacity as evidence of executable work.

## 19. Resource constraints and assignment

An assignment establishes responsibility or commitment under the applicable assignment model.

It does not guarantee resource availability.

Therefore:

```text
Assignment exists
≠
Required resources exist
```

and:

```text
Required resources exist
≠
Assignment is authorized
```

The implementation MUST preserve these independent semantics.

## 20. Resource constraints and execution

Execution admission MUST account for material resource constraints.

Where a required resource is missing, unusable, unauthorized, or insufficient, the execution state MUST reflect the applicable condition rather than silently proceeding as if the resource existed.

Possible outcomes include:

- blocked;
- deferred;
- partially executable;
- awaiting resource;
- escalated;
- cancelled under applicable authority.

The exact execution states remain governed by the canonical execution/state models.

## 21. Resource request lifecycle

When a material resource deficit exists, Workforce MAY create a resource request.

A resource request SHOULD preserve:

- request identity;
- requesting organization/context;
- originating workload;
- resource class/identity;
- required quantity/capacity;
- required period/window;
- urgency/priority where modeled;
- cost basis where available;
- initiating actor;
- authority reference;
- evidence/provenance;
- current state.

Conceptually:

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

Exact states remain subject to the canonical lifecycle and transition models.

Creating a resource request MUST NOT imply that the resource already exists.

## 22. Partial resource availability

A workload MAY be partially supported by available resources.

Example:

```text
Required equipment = 5
Usable equipment   = 3

Coverage           = 60%
Deficit            = 2 units
```

Partial availability MUST remain explicit.

The system MUST NOT convert partial resource coverage into full execution without evidence that the workload can legitimately operate under the reduced resource condition.

## 23. Over-allocation

A resource MUST NOT be committed beyond its legitimate capacity unless the applicable model explicitly permits the condition and preserves it as a constraint or risk.

Example:

```text
Resource capacity       = 100 hours
Committed work          = 130 hours
Over-allocation         = 30 hours
```

The system MUST preserve the over-allocation rather than silently increasing resource capacity.

## 24. Resource cost evidence

Resource use MAY create economic consequences.

Where materially applicable, Workforce SHOULD preserve evidence sufficient for Economy to determine:

- resource demand;
- allocation basis;
- utilization;
- consumption;
- rental or acquisition basis;
- maintenance implication;
- material usage;
- capital utilization;
- incremental cost;
- planned versus actual resource cost;
- cost per work unit.

Workforce provides operational evidence and context.

Economy remains the authoritative accounting domain.

## 25. Recalculation and revalidation

Resource constraints MUST be re-evaluated when material inputs change, including:

- resource availability;
- resource status;
- maintenance state;
- allocation;
- commitment;
- consumption;
- workload;
- staffing;
- assignment;
- organizational relationship;
- authorization;
- budget restriction;
- operating window;
- prerequisite completion;
- substitution policy;
- external dependency;
- resource quality or qualification.

A later result MUST NOT rewrite the historical resource condition that existed at an earlier time.

## 26. Failure and insufficiency

If resource sufficiency cannot be determined reliably because required inputs are missing, contradictory, stale, or unavailable, the system MUST preserve an explicit insufficient or inconclusive condition.

It MUST NOT silently assume:

- resources exist;
- resources are usable;
- resources are available at the required time;
- resources are authorized;
- required capacity exists;
- dependencies are satisfied;
- budget exists;
- information is current.

Where the insufficiency materially blocks work, it SHOULD become an escalation candidate under the Phase 4 escalation model.

## 27. Simulation constraint

Simulation MAY vary:

- resource quantity;
- resource capacity;
- availability;
- maintenance;
- allocation;
- workload;
- staffing;
- operating windows;
- cost assumptions;
- dependencies.

Simulation MUST preserve finite resource constraints.

A simulation is invalid if it completes work solely by inventing equipment, facilities, materials, capital, information, dependencies, or resource capacity.

## 28. Acceptance invariants

The implementation MUST satisfy at least these invariants:

1. Resource requirements are attributable to a defined workload/context.
2. Required resources are independent of currently available resources.
3. Resource existence is distinguishable from resource availability.
4. Availability is distinguishable from usability.
5. Usability is distinguishable from authorization.
6. Allocation and commitment remain distinguishable where materially relevant.
7. Resource quantity and resource capacity are not conflated.
8. Resource deficits remain visible and attributable.
9. Resource contention cannot be silently ignored.
10. Resource dependencies remain explicit.
11. Resource constraints are time-aware.
12. Partial resource availability remains distinguishable from full coverage.
13. Over-allocation remains visible rather than silently increasing capacity.
14. Substitution is explicit, authorized, attributable, and evidence-backed.
15. Resource constraints can block otherwise sufficiently staffed work.
16. Resource constraints do not manufacture authority, capacity, resources, or budget.
17. Resource evidence preserves attribution, provenance, and temporal validity.
18. Historical resource conditions are not rewritten by later changes.
19. Economic consequences remain evidence for Economy rather than Workforce accounting.
20. Missing or contradictory inputs produce explicit insufficiency rather than optimistic assumptions.

## 29. Gate G5 contribution

This artifact contributes evidence for Gate G5 by establishing the resource semantics required to demonstrate:

- finite equipment;
- finite facilities;
- finite materials;
- finite capital/budget conditions;
- information dependencies;
- human/organizational dependencies;
- resource availability;
- resource capacity;
- resource contention;
- resource deficits;
- staffing/resource interaction;
- execution blocking;
- economic evidence;
- cost consequences;
- no impossible execution.

This document defines the semantic resource-constraint contract only. It does not prescribe a specific inventory system, scheduler, procurement system, accounting implementation, optimization algorithm, database schema, or execution engine.
