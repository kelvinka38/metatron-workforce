# METATRON WORKFORCE — PHASE 4 IMPLEMENTATION CONTRACT

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 4 — Organization / Relationships
**Depends on:** G3 PASS
**Status:** FROZEN IMPLEMENTATION CONTRACT

## 1. Purpose

Phase 4 makes Workforce behave as an organization rather than a collection of independent Workers.

The implementation surface is limited to:

1. organizational hierarchy/context;
2. position and role occupancy;
3. typed Worker relationships;
4. bounded delegation;
5. explicit escalation routing;
6. temporal validity;
7. attribution and evidence;
8. authorization-boundary preservation.

Phase 4 does not create Governance legitimacy, Gateway enforcement, Execution infrastructure, or Economy accounting.

## 2. Required acceptance behaviors

### UC-01 Organizational structure

The implementation must represent multiple organizational levels, including institution, organization, department/team, position, role, and Worker occupancy. Hierarchy depth remains configurable.

### UC-02 Reporting relationships

The implementation must support distinct `REPORTS_TO`, `MANAGES`, `SUPERVISES`, `ADVISES`, `COORDINATES_WITH`, and `DELEGATES_TO` relationships. A relationship is temporal and attributable.

### UC-03 Delegation

A delegator may create a bounded delegation only when the supplied authorization context permits it. Delegation must preserve authority reference, scope, organization context, effective/expiry time, initiator, and evidence. Delegation never grants more authority than the referenced authority permits.

### UC-04 Authority limits

A delegatee is considered covered only when the delegation is active, in the requested context, and its scope explicitly covers the requested action. Organizational reachability alone never creates authority.

### UC-05 Escalation

A Worker can raise an attributable escalation for the eight Gate G4 categories. The route must be resolved from explicit configured escalation rules, valid reporting/management/supervisory relationships, or valid delegation. Ambiguous or missing routes must remain unresolved rather than inventing a target.

### UC-06 Cross-team coordination

Workers in different teams may coordinate through `COORDINATES_WITH` without creating a reporting relationship.

### UC-07 Temporal reconstruction

Historical organizational relationships, placements, delegations, and escalation routes must remain reconstructable at the relevant time. Later changes must not rewrite historical validity.

## 3. Mandatory invariants

1. `WORKER != POSITION`.
2. `POSITION != ROLE`.
3. `ROLE != AUTHORITY`.
4. Reporting does not create constitutional legitimacy.
5. Reporting does not automatically create unrestricted authority.
6. Organizational hierarchy does not bypass authorization.
7. Delegation is explicit, bounded, time-aware, scope-aware, attributable, and revocable by expiry/validity.
8. Delegation cannot exceed the referenced authority scope.
9. Cross-team coordination does not create reporting authority.
10. Escalation routing does not authorize the requested action.
11. Missing/ambiguous escalation routes never select an arbitrary Worker.
12. Relationship and escalation history remains attributable.
13. Workforce records Worker-side organizational semantics; authoritative external legitimacy remains with the owning domain.

## 4. Authorization contract

Every restricted Phase 4 mutation or authority-sensitive routing decision must receive an explicit authorization result from an injected policy/evaluator.

Conceptually:

```text
ACTOR
  + ROLE / POSITION
  + AUTHORITY REFERENCE
  + SCOPE
  + CONTEXT
  + TIME
  + POLICY
        ↓
AUTHORIZATION RESULT
        ↓
PHASE 4 OPERATION
```

Phase 4 may consume an authorization result; it must not manufacture institutional legitimacy.

## 5. Escalation route precedence

Route resolution uses deterministic precedence:

1. explicit configured route;
2. active `REPORTS_TO`;
3. active `MANAGES`;
4. active `SUPERVISES`;
5. valid delegated authority route;
6. unresolved/review state.

A route target is not automatically authorized to execute the requested action.

## 6. Attribution contract

Material changes preserve:

```text
WHO
WHAT
WHEN
CONTEXT
AUTHORITY REFERENCE
EVIDENCE
```

Escalations additionally preserve condition, category, urgency, route, response/decision references, and lifecycle timestamps.

## 7. Completion rule

G4 is complete only when fresh CI evidence on the exact current commit proves:

- organizational hierarchy;
- multiple organizational levels;
- real reporting relationships;
- delegation and scope limits;
- escalation routing and failure behavior;
- cross-team coordination without false reporting;
- temporal validity;
- attribution/evidence;
- full regression;
- deployable artifact build.
