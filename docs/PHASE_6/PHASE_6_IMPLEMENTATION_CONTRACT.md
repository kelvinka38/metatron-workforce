# METATRON WORKFORCE — PHASE 6 IMPLEMENTATION CONTRACT

**Phase:** 6 — Authorization / Execution / Attribution
**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`

## Objective

Turn proposed work into legitimate institutional execution while preserving explicit approval, external authorization, execution outcome, and attribution.

## Canonical flow

```text
Proposal
  ↓
Approval / Rejection
  ↓
Authorization
  ↓
Execution
  ↓
Outcome / Failure
  ↓
Attribution / Audit evidence
```

## Invariants

1. A proposal is not execution.
2. Rejection cannot authorize execution.
3. Authorization is evaluated by an explicit policy boundary; Workforce does not manufacture authority.
4. Actor, action, scope, context, resource and time must remain aligned with the proposal.
5. Unauthorized execution fails closed.
6. Execution records preserve actor, authorization reference, inputs, outputs, timestamps and failure state.
7. Failure is an attributable execution outcome, not silent success.
8. Attribution preserves human instruction → decision → worker execution → outcome chronology.
9. Historical attribution is immutable from the acceptance surface.
10. Phase 6 does not own Governance authority, Gateway enforcement, execution infrastructure, accounting truth, or external resource truth.

## Required acceptance evidence

`Phase6AuthorizationExecutionAcceptanceTest` must prove:

- proposal and approval attribution;
- rejection;
- authorization boundary;
- actor/scope/action/context alignment;
- external policy denial;
- successful execution;
- failed execution;
- fail-closed unauthorized execution;
- chronological immutable attribution.
