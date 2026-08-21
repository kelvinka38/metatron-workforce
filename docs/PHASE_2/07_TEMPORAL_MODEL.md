# METATRON WORKFORCE — PHASE 2 / 07 TEMPORAL MODEL

## 1. Purpose

Material Workforce records must be temporally interpretable. Current state must not be inferred solely from record creation time.

## 2. Required temporal meanings

Where materially applicable, distinguish:

- `created_at` — when a record was created;
- `effective_at` — when a relationship/state became institutionally valid;
- `expires_at` — when validity ends;
- `scheduled_at` / scheduled window — when work is planned;
- `actual_started_at` — when activity actually began;
- `actual_completed_at` — when activity actually completed;
- `recorded_at` — when the fact was recorded.

Not every construct requires every timestamp.

## 3. Temporal dimensions

### Worker / participation

Participation validity must be independent of Worker identity persistence.

### Role / position

Role and position occupancy must support effective periods so organizational history is not rewritten by current assignments.

### Authority

Authority grants and delegations must preserve validity periods, including expiry and revocation.

### Assignment

Assignment validity must be distinguishable from assignment creation and from execution time.

### Authorization

Authorization validity must be distinguishable from the time an authorization request was created. Material changes may require revalidation.

### Schedule / availability

Schedules represent planned commitments. Availability represents periods in which the Worker may operationally work.

### Capacity

Capacity is time-bounded. Capacity commitments consume capacity during a defined period and must not create unlimited availability.

### Execution

Execution records must preserve actual start/completion times independently from scheduled time and authorization time.

### Evidence / reporting

Recorded time must not be substituted for occurrence time when reconstructing material history.

## 4. Example

```text
Assignment created:     09:00
Assignment effective:  13:00
Execution started:     13:20
Execution completed:   14:05
Authorization expires: 14:30
Report recorded:       15:10
```

Each timestamp answers a different institutional question.

## 5. Historical truth

A later organizational change must not rewrite historical truth merely because the current relationship differs.

Historical records must remain interpretable under the organizational, authority, assignment, and policy context that existed when the event occurred.

## 6. Temporal validation

A state transition that depends on time MUST validate applicable effective/expiry windows. Examples include:

- expired authority;
- ended participation;
- inactive assignment;
- unavailable Worker;
- closed operating window;
- expired qualification.

## 7. Recurrence and exceptions

Schedules may be recurring and may have exceptions. The semantic model must preserve both the recurrence rule and material exceptions where needed.

## 8. Implementation constraint

This document defines temporal semantics only. It does not prescribe a specific timestamp type, database temporal table, scheduler, timezone library, or storage strategy.