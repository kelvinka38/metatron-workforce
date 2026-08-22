# METATRON WORKFORCE — PHASE 5 / 01 WORKING TIME MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Semantic foundation:** Phase 2 temporal model, lifecycle/state model, provenance, attribution, authorization references, and Phase 4 organization/relationship and escalation models.
**Phase:** 5 — Time / Capacity / Staffing / Reality

## 1. Purpose

This artifact defines the Workforce-side working-time model required to make Worker availability and operational execution reality-constrained.

The model establishes when a Worker may operationally work. It does not by itself grant authority, create capacity, approve assignments, or authorize execution.

Working time MUST remain distinguishable from:

- Worker identity;
- organizational membership;
- authority validity;
- assignment validity;
- scheduled work;
- actual execution;
- economic accounting.

## 2. Core distinction

The implementation MUST distinguish at least these concepts:

- **working window** — a period in which the Worker is permitted/expected to work under an applicable work-time arrangement;
- **availability** — a period in which the Worker is operationally available to accept or perform work;
- **schedule** — planned work or planned commitment during a defined period;
- **unavailability** — a period during which the Worker cannot operationally perform work;
- **actual work time** — time in which execution actually occurred.

A scheduled period MUST NOT be treated as proof that work occurred.

An available period MUST NOT be treated as proof that capacity was uncommitted.

## 3. Working-time record

A material working-time record SHOULD preserve, where applicable:

- working-time identity;
- Worker identity;
- organization/context identity;
- time zone;
- effective start;
- effective end;
- recurrence or schedule rule;
- applicable working days;
- shift/window definition;
- break definition;
- source/authority reference;
- provenance/attribution;
- creation and recording timestamps.

A record with no effective validity MUST NOT be used as a valid operational working-time constraint.

## 4. Time-zone semantics

Working-time interpretation MUST be associated with an explicit time-zone context.

The implementation MUST NOT silently interpret a local working window in the server's default time zone.

A recurring working-time rule MUST preserve the time-zone context required to determine its occurrence.

Where a time-zone transition materially changes an occurrence, the resolved occurrence MUST remain attributable to the applicable rule and time context.

## 5. Working days and shifts

The model MUST support working periods that vary by day and shift.

Examples include:

- Monday–Friday daytime work;
- rotating shifts;
- overnight shifts;
- different working windows by weekday;
- organization-specific operating windows.

An overnight shift MUST be representable without assuming that its end occurs on the same calendar date as its start.

## 6. Breaks

Breaks are periods inside a working arrangement during which the Worker is not expected to perform normal work.

A break MUST NOT be counted as available working time merely because it lies inside a scheduled shift.

Where break rules are not fully specified, the implementation MUST NOT invent a duration and silently convert it into capacity.

## 7. Availability

Availability is a time-bounded operational fact.

At minimum, availability resolution MUST be able to distinguish:

```text
AVAILABLE
UNAVAILABLE
UNKNOWN
```

`UNKNOWN` MUST NOT be treated as `AVAILABLE` for material capacity commitments.

Availability MAY be derived from working-time rules plus explicit exceptions, but the derived result must remain traceable to its inputs.

## 8. Unavailability and exceptions

Explicit unavailability MAY arise from:

- leave;
- suspension;
- rest period;
- illness/absence record where applicable;
- organizational closure;
- operational restriction;
- explicit Worker unavailability;
- other configured exception.

An exception MUST NOT rewrite the underlying recurring working-time rule.

Instead, the exception applies within its own temporal validity and provenance.

## 9. Effective validity

Working-time rules MUST follow the Phase 2 temporal semantics.

Where materially applicable, distinguish:

- `created_at` — record creation;
- `effective_at` — institutional validity begins;
- `expires_at` — institutional validity ends;
- `scheduled_at` / scheduled window — planned work;
- `actual_started_at` — actual execution start;
- `actual_completed_at` — actual execution completion;
- `recorded_at` — recording time.

Current working-time status MUST be evaluated against the applicable effective/expiry window rather than inferred from record creation time.

## 10. Availability resolution

For a Worker and time instant/window, the resolution process SHOULD conceptually follow:

```text
Worker identity
      ↓
Applicable organization/context
      ↓
Effective working-time rules
      ↓
Recurring working windows
      ↓
Explicit exceptions / unavailability
      ↓
Time-zone resolution
      ↓
Availability result
```

Conflicting material rules MUST NOT be resolved by arbitrary ordering.

The implementation MUST use explicit precedence/configuration or produce an unresolved result requiring review.

## 11. Working-time constraints

A Worker MUST NOT be considered continuously available merely because the Worker is active in the Workforce system.

The following conditions may prevent operational availability even when Worker identity remains active:

- outside working window;
- break period;
- explicit unavailability;
- organizational closure;
- suspension;
- ended participation;
- expired working-time rule;
- unresolved temporal conflict.

Working-time availability also does not override authorization, assignment, capacity, resource, or policy constraints.

## 12. Interaction with capacity

Working time is an input to capacity; it is not equivalent to capacity.

Conceptually:

```text
Scheduled Capacity
≤
Valid Working Time
```

and, for a defined period:

```text
Available Working Time
=
Valid Working Time
−
Unavailable Time
−
Break Time
```

Capacity calculation MUST then apply the additional constraints defined by the Phase 5 capacity model.

A Worker with 40 available working hours does not necessarily have 40 hours of assignable capacity.

## 13. Interaction with organization

Organizational relationships may determine which working-time policy or operating context applies, but a relationship alone MUST NOT manufacture availability.

For example:

```text
Organization
   ↓
Department
   ↓
Team
   ↓
Worker
   ↓
Working-time arrangement
```

The applicable arrangement must remain attributable to the relevant organizational context and authority source.

## 14. Interaction with suspension and authority

A Worker suspension or authority restriction may make otherwise scheduled time operationally unavailable for particular work.

Working-time availability MUST NOT be used as evidence that an action is authorized.

Likewise, valid authority MUST NOT be interpreted as unlimited working-time availability.

## 15. Historical truth

Changing a Worker’s current working-time arrangement MUST NOT rewrite historical availability or historical execution context.

Historical records MUST remain interpretable using the working-time and exception context applicable at the relevant time.

## 16. Revalidation

Time-sensitive decisions MUST be re-evaluated when a material working-time input changes, including:

- working-time rule becomes effective;
- working-time rule expires;
- shift changes;
- explicit unavailability is created or removed;
- organization operating window changes;
- Worker suspension changes availability;
- relevant time-zone interpretation changes;
- assignment window changes.

A previously resolved availability result MUST NOT be assumed permanently valid when its material inputs have changed.

## 17. Evidence and provenance

A material availability result MUST be traceable to the inputs used to resolve it.

At minimum, the evidence chain SHOULD identify:

- Worker;
- evaluation time/window;
- applicable working-time rule(s);
- applicable exception(s);
- organization/context;
- time-zone context;
- resolution result;
- recording time;
- provenance/reference identifiers.

The result MUST NOT appear as an unexplained boolean fact when the underlying temporal rules materially matter.

## 18. Failure and ambiguity

If working-time resolution cannot determine a valid result because of conflicting or incomplete material inputs:

- the system MUST NOT silently assume availability;
- the unresolved condition MUST remain attributable;
- the condition SHOULD create an appropriate review/escalation path where operationally material.

This follows the Phase 2 failure rule and Phase 4 escalation model.

## 19. Non-ownership boundary

This artifact does not make Workforce authoritative for:

- payroll accounting;
- financial accounting;
- constitutional authority;
- governance policy;
- infrastructure clock authority;
- external identity authority.

Workforce consumes or publishes the evidence required by those domains through explicit interfaces.

## 20. Gate G5 relevance

This artifact establishes the working-time foundation for Gate G5.

The complete Phase 5 implementation must ultimately demonstrate:

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

Working time is therefore a prerequisite to realistic capacity and staffing, but it does not by itself satisfy Gate G5.

## 21. Implementation constraint

This document defines working-time semantics only.

It does not prescribe a specific scheduler, database schema, cron mechanism, Java time API usage, persistence strategy, workforce roster UI, or external calendar provider.

Those implementation choices remain subordinate to the semantic contract defined here and by the existing Phase 2 temporal model.
