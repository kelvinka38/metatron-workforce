# METATRON WORKFORCE — PHASE 6 / 01 AUTHORIZATION MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 6 — Authorization / Execution / Attribution
**Semantic foundation:** Phase 2 authorization references, state-transition, temporal, provenance, attribution, and economic-evidence models; Phase 4 delegation and escalation; Phase 5 time, capacity, staffing, resource, and economic constraints.

## 1. Purpose

This artifact defines the Workforce-side authorization model required to determine whether a proposed institutional action may legitimately proceed to execution.

Authorization is not equivalent to technical capability, staffing, assignment, or approval alone.

A Worker may be technically capable, assigned, staffed, and available while still lacking authority to perform a specific action in a specific context.

Conversely, an actor may possess authority while execution remains infeasible because of time, capacity, staffing, resource, budget, dependency, or other constraints.

## 2. Authorization decision contract

Every material authorization decision MUST preserve:

- actor identity;
- role/position context;
- requested action;
- target/resource scope;
- organizational context;
- applicable policy;
- authority source;
- delegation reference where applicable;
- temporal validity;
- relevant preconditions;
- required dependencies;
- evidence/provenance;
- decision;
- decision reasons;
- decision time;
- resulting event or failure condition.

Technical ability to invoke an operation MUST never be treated as sufficient authority.

## 3. Authorization dimensions

Authorization MUST be evaluated across the applicable dimensions:

```text
Actor
  +
Role / Position
  +
Authority Source
  +
Action
  +
Scope
  +
Context
  +
Time
  +
Policy
  +
Delegation
  +
Resource
  +
Preconditions
  +
Dependencies
```

A missing material dimension MUST NOT be silently assumed to permit the action.

## 4. Authority source

A valid authorization MUST reference a legitimate authority source.

Possible sources include:

- institutional authority;
- organizational role authority;
- explicit delegation;
- configured policy;
- approved assignment authority;
- other authority explicitly recognized by the Workforce architecture.

Workforce MUST NOT manufacture authority owned by Governance or another external domain.

## 5. Delegated authority

Delegation MUST remain bounded by the Phase 4 delegation model.

A delegated authorization MUST preserve at least:

- delegator;
- delegatee;
- delegated authority;
- permitted actions;
- permitted scope;
- applicable context;
- effective time;
- expiry/revocation condition;
- attribution;
- evidence.

A delegation cannot grant more authority than the delegator legitimately possesses.

## 6. Authorization states

Authorization MAY use the following lifecycle semantics:

```text
requested
   ↓
resolving
   ├── allow
   ├── deny
   ├── review
   └── defer
```

The exact implementation state names remain subject to the canonical lifecycle model.

Every material transition MUST satisfy the Phase 2 transition contract:

- current state;
- requested next state;
- initiating actor;
- authority source;
- preconditions;
- temporal validity;
- evidence/provenance;
- resulting event;
- failure behavior.

## 7. Allow

An authorization MAY resolve to `allow` only when all material authorization conditions are satisfied.

The decision MUST preserve enough evidence to reconstruct why the action was permitted.

An `allow` decision MUST NOT be interpreted as proof that execution will succeed.

For example:

```text
Authorized = true
Executable = false
```

is a valid result when capacity, staffing, resource, operating-window, or dependency constraints prevent execution.

## 8. Deny

A material authorization denial MUST preserve:

- denied action;
- actor;
- scope;
- authority context;
- applicable policy/rule;
- denial reason;
- decision time;
- evidence/provenance.

A denial MUST NOT silently mutate the requested work into an executed state.

Where applicable, the denied request MAY become a review or escalation candidate.

## 9. Review and defer

Authorization MAY require review when policy or authority configuration requires human or higher-level judgment.

Authorization MAY be deferred when a required condition is expected to become resolvable later.

Review and defer MUST remain distinguishable from denial.

```text
review  != deny
defer  != deny
allow   != execute
```

## 10. Assignment boundary

Assignment and authorization are distinct.

```text
Assignment exists
≠
Authorization exists
```

An assignment may identify who is expected to perform work without granting authority for every action associated with that work.

Likewise:

```text
Authorization exists
≠
Assignment exists
```

The implementation MUST preserve both conditions independently where material.

## 11. Staffing and capacity boundary

Authorization MUST remain compatible with Phase 5 reality constraints.

A Worker cannot become executable merely because authorization succeeds.

The final feasibility condition may therefore be:

```text
Authorized
AND
Staffed
AND
Available
AND
Qualified
AND
Within Capacity
AND
Resources Available
AND
Operating Window Open
AND
Dependencies Satisfied
```

If any mandatory condition is unsatisfied, the action MUST remain non-executable or enter the applicable blocked/deferred/escalation path.

## 12. Resource and economic boundary

Authorization may depend on resources, budget, or economic constraints.

Workforce MUST preserve the operational evidence required for Economy to evaluate economic consequences, but MUST NOT become authoritative accounting truth.

For example:

```text
Budget authorization = granted
Economic accounting  = Economy
```

or:

```text
Budget condition = insufficient
Authorization    = deny/defer/review
```

The applicable decision MUST preserve the evidence supporting the result.

## 13. Revalidation

Authorization MUST be re-evaluated when material inputs change, including:

- delegation revoked or changed;
- authority expired;
- Worker suspended or retired;
- assignment cancelled or changed;
- role/position changed;
- policy changed;
- resource becomes unavailable;
- budget becomes unavailable;
- operating window closes;
- capacity becomes insufficient;
- staffing becomes insufficient;
- required qualification changes;
- dependency becomes unsatisfied.

A prior authorization MUST NOT be treated as permanently valid when its material conditions have changed.

## 14. Temporal validity

Authorization MUST preserve effective and expiry semantics where material.

At minimum, the system MUST be able to distinguish:

```text
Authorization granted at T1
Effective from T2
Expires at T3
Action requested at T4
```

An authorization that is valid at one time MUST NOT automatically authorize the same action outside its validity window.

Historical authorization truth MUST remain immutable evidence even when current authority changes.

## 15. Policy and precedence

Where multiple applicable authority or policy rules exist, the implementation MUST preserve which rules were evaluated and which rule determined the result.

The authorization model MUST NOT silently resolve conflicting authority by guessing.

If precedence cannot be established from the applicable configuration, the result MUST be explicit insufficiency, review, or another defined non-executing condition.

## 16. Authorization reference

An execution request MUST be able to reference the authorization decision that permits it.

Conceptually:

```text
Execution Request
       ↓
Authorization Resolution
       ↓
Authorization Reference
       ↓
Execution Admission
```

The reference MUST remain attributable and time-valid.

Execution MUST NOT rely solely on an unrecorded conversational instruction when a material authorization decision is required.

## 17. Failure and insufficiency

If authorization cannot be reliably determined because inputs are missing, contradictory, stale, expired, revoked, or otherwise insufficient, the system MUST NOT optimistically authorize the action.

The result MUST remain explicit, attributable, and reviewable where applicable.

A failed authorization transition MUST NOT silently mutate the target state.

## 18. Audit reconstruction

A material authorization decision MUST allow reconstruction of:

```text
Request
  ↓
Actor / Role
  ↓
Authority Source
  ↓
Delegation / Policy
  ↓
Scope / Context
  ↓
Temporal Conditions
  ↓
Preconditions / Dependencies
  ↓
Decision
  ↓
Reasons / Evidence
  ↓
Authorization Reference
```

This reconstruction is required for later execution and attribution evidence.

## 19. Acceptance invariants

The implementation MUST satisfy at least these invariants:

1. Technical capability is not authority.
2. Authority is attributable to a legitimate source.
3. Delegated authority cannot exceed the delegator's authority.
4. Authorization is bounded by action, scope, context, and time.
5. Assignment does not automatically imply authorization.
6. Authorization does not automatically imply assignment.
7. Authorization does not automatically imply execution feasibility.
8. Staffing, capacity, resources, operating windows, and dependencies remain separate execution constraints.
9. Revoked or expired authority cannot authorize new execution.
10. Material authorization changes trigger revalidation where applicable.
11. Denial, review, and defer remain distinguishable outcomes.
12. Missing or contradictory authorization inputs do not produce optimistic authorization.
13. Authorization decisions preserve evidence, provenance, attribution, and temporal validity.
14. Workforce does not manufacture authority owned by Governance or another external domain.
15. Historical authorization evidence is not rewritten by later state changes.

## 20. Phase 6 contribution

This artifact establishes the authorization semantics required before implementation of execution admission, execution state transitions, and complete attribution.

It does not prescribe a specific database schema, policy engine, identity provider, workflow engine, or external governance implementation.
