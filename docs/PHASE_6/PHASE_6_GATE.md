# METATRON WORKFORCE — PHASE 6 / GATE G6

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 6 — Authorization / Execution / Attribution
**Status:** Gate evidence definition

## 1. Gate objective

Gate G6 determines whether Workforce can turn proposed work into legitimate, attributable institutional execution without silently bypassing authority, reality constraints, or historical truth.

Phase 6 MUST NOT advance to Phase 7 until the required evidence exists and the acceptance invariants are satisfied.

## 2. Required evidence

### Proposal / approval

- [ ] Proposed work is distinguishable from approved work.
- [ ] Approval outcomes are explicit.
- [ ] Rejection is explicit.
- [ ] Return/defer/review outcomes remain distinguishable where applicable.
- [ ] Approval authority is attributable.

### Authorization

- [ ] Actor identity is explicit.
- [ ] Role/position context is explicit.
- [ ] Authority source is explicit.
- [ ] Action is explicit.
- [ ] Scope is explicit.
- [ ] Context is explicit.
- [ ] Temporal validity is explicit.
- [ ] Applicable policy is preserved.
- [ ] Delegation is preserved where applicable.
- [ ] Authorization decisions are attributable.
- [ ] Revocation and expiry are enforceable.
- [ ] Material changes trigger revalidation.

### Execution

- [ ] Execution identity is distinct from Worker identity.
- [ ] Requested execution is distinct from running execution.
- [ ] Execution admission checks authorization.
- [ ] Execution admission checks applicable reality constraints.
- [ ] Actual start evidence is preserved.
- [ ] Completion evidence is preserved.
- [ ] Failure is preserved.
- [ ] Blocked execution is preserved.
- [ ] Cancellation is preserved.
- [ ] Partial execution remains distinguishable.
- [ ] Retry remains distinguishable from the original attempt.

### Attribution

- [ ] Originating actor is preserved.
- [ ] Decision maker is preserved.
- [ ] Authorizing actor/source is preserved.
- [ ] Assigner is preserved.
- [ ] Responsible Worker is preserved.
- [ ] Runtime instance is preserved where applicable.
- [ ] Execution is linked to its authority basis.
- [ ] Evidence is linked to material institutional facts.
- [ ] Inference remains distinguishable from fact.
- [ ] Historical attribution remains reconstructable.

### Auditability

- [ ] Material transitions emit attributable evidence/events.
- [ ] Temporal distinctions are preserved.
- [ ] Corrections do not erase historical records.
- [ ] Cross-domain authority is not manufactured by Workforce.
- [ ] Economic evidence remains distinguishable from authoritative accounting truth.

## 3. Core acceptance scenario

A realistic execution scenario MUST demonstrate:

```text
Human / Worker Request
        ↓
Proposal
        ↓
Approval / Decision
        ↓
Authorization Resolution
        ↓
Execution Validation
        ↓
Execution Admission
        ↓
Running
        ↓
Completed / Failed / Blocked / Cancelled
        ↓
Attributable Outcome
```

The scenario MUST preserve the relevant actor, authority, temporal, evidence, and execution context at every material stage.

## 4. Mandatory denial scenario

The implementation MUST demonstrate that an otherwise technically executable action is not executed when authorization is invalid.

Example:

```text
Worker = available
Capacity = sufficient
Resource = available
Technical capability = available
Authorization = expired
```

The result MUST NOT be successful execution.

The system MUST preserve the authorization failure and applicable escalation/review path.

## 5. Mandatory reality-constraint scenario

The implementation MUST demonstrate:

```text
Authorization = allow
Staffing = insufficient
```

or:

```text
Authorization = allow
Capacity = insufficient
```

or:

```text
Authorization = allow
Resource = unavailable
```

The result MUST remain non-executable, blocked, deferred, partially executable, or otherwise constrained according to the applicable lifecycle rules.

Authorization MUST NOT manufacture execution feasibility.

## 6. Mandatory attribution scenario

The implementation MUST demonstrate a chain equivalent to:

```text
Human instruction
    ↓
Head decision
    ↓
Authorization
    ↓
Worker assignment
    ↓
Runtime execution
    ↓
Outcome
```

The system MUST be able to reconstruct each material link.

## 7. Mandatory failure scenario

The implementation MUST demonstrate an execution that starts but fails.

The failure MUST preserve:

- execution identity;
- responsible Worker;
- authorization reference;
- actual failure time;
- failure reason;
- evidence;
- outputs produced before failure where material.

The failed execution MUST NOT be rewritten as successful execution.

## 8. Mandatory blocked scenario

The implementation MUST demonstrate a running or admitted execution becoming blocked when a material condition changes.

Example:

```text
Execution = running
Resource = withdrawn
```

The resulting blocked condition MUST remain explicit and attributable.

## 9. Historical boundary

Gate G6 passes only if later retries, corrections, revocations, reassignment, or reauthorization do not erase historical execution and attribution truth.

A retry MUST remain distinguishable from the original attempt.

A later authorization MUST NOT retroactively authorize an earlier unauthorized execution.

## 10. Cross-domain boundary

Gate G6 passes only if Workforce does not manufacture authority, accounting truth, observation truth, knowledge authority, gateway enforcement, or other external-domain truth.

Workforce MAY preserve references and evidence required by downstream domains.

## 11. Failure rule

If authorization, execution, attribution, or historical evidence is missing, contradictory, stale, or insufficient to demonstrate the required constraints, Gate G6 MUST NOT be treated as passed.

The condition MUST remain explicit and attributable.

## 12. Exit condition

G6 is complete only when:

- [ ] Proposal semantics are implemented and tested.
- [ ] Approval/rejection semantics are implemented and tested.
- [ ] Authorization model is implemented and tested.
- [ ] Execution lifecycle is implemented and tested.
- [ ] Failure and blocked execution are implemented and tested.
- [ ] Attribution model is implemented and tested.
- [ ] Runtime binding remains attributable where applicable.
- [ ] Revalidation is implemented for material authorization changes.
- [ ] Historical execution truth remains reconstructable.
- [ ] Auditability evidence is reproducible from repository state.
- [ ] Cross-domain authority boundaries remain preserved.

Only after these conditions are satisfied may execution advance to Phase 7 — Reporting / Performance / Economic Evidence.
