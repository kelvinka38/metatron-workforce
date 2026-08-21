# METATRON WORKFORCE — PHASE 2 / 03 LIFECYCLE STATE MODEL

## 1. Principle

Lifecycle is multidimensional. There is no universal Worker status representing every operational condition.

Identity state, participation, authority, assignment, authorization, execution, reporting, and improvement have separate lifecycles.

## 2. Worker identity

```text
ADMITTED
   ↓
ACTIVE
   ↓
RETIRED
```

Suspension or unavailability conditions do not automatically change Worker identity.

## 3. Participation

```text
PENDING
   ↓
ACTIVE
   ├── SUSPENDED
   └── ENDED
```

## 4. Authority

```text
GRANTED
   ↓
ACTIVE
   ├── RESTRICTED
   ├── EXPIRED
   └── REVOKED
```

## 5. Proposal

```text
DRAFT
 ↓
SUBMITTED
 ↓
UNDER_REVIEW
 ├── APPROVED
 ├── REJECTED
 ├── RETURNED
 └── DEFERRED
```

## 6. Assignment

```text
PROPOSED
 ↓
APPROVED
 ↓
ACTIVE
 ├── COMPLETED
 └── CANCELLED
```

Assignment is not authorization and not execution.

## 7. Authorization

```text
REQUESTED
 ↓
RESOLVING
 ├── ALLOW
 ├── DENY
 ├── REVIEW
 └── DEFER
```

An ALLOW may become invalid if a material condition changes before execution. Revalidation is therefore required where the implementation architecture or policy requires it.

## 8. Execution

```text
REQUESTED
 ↓
VALIDATING
 ↓
AUTHORIZED
 ↓
RUNNING
 ├── COMPLETED
 ├── FAILED
 ├── BLOCKED
 └── CANCELLED
```

Execution remains externally owned by Execution; Workforce keeps the coordination and attribution context.

## 9. Report

```text
DRAFT
 ↓
SUBMITTED
 ↓
UNDER_REVIEW
 ├── ACCEPTED
 ├── RETURNED
 └── REJECTED
```

## 10. Improvement Candidate

```text
OBSERVED
 ↓
REFLECTED
 ↓
PROPOSED
 ↓
EVALUATED
 ├── VALIDATED
 ├── REJECTED
 └── INCONCLUSIVE
        ↓
     ADOPTED
```

Adoption is not equivalent to institutional policy change.

## 11. Work lifecycle

The implementation architecture allows a configurable work lifecycle:

```text
REQUESTED
 → PROPOSED
 → REVIEWED
 → APPROVED / REJECTED / RETURNED
 → ASSIGNED
 → AUTHORIZED
 → SCHEDULED
 → RUNNING
 → COMPLETED / FAILED / BLOCKED / CANCELLED
 → REPORTED
 → REVIEWED
```

Not every work class must use every state.

## 12. Runtime lifecycle

```text
CREATED
 → READY
 → ACTIVE
 → TERMINATED / FAILED
```

Runtime termination MUST NOT retire Worker identity.

## 13. State invariants

1. Worker identity survives assignment and runtime changes.
2. Capability does not imply authority.
3. Authority does not imply authorization for a specific action.
4. Assignment does not imply authorization.
5. Authorization does not imply execution.
6. Execution does not imply successful outcome.
7. Learning adoption does not silently change institutional policy.
8. Historical state must remain reconstructable where required.

## 14. Phase 2 implementation requirement

Each stateful construct must be implemented only after its valid transitions, transition authority, transition conditions, and transition evidence are defined in `04_STATE_TRANSITION_RULES.md`.