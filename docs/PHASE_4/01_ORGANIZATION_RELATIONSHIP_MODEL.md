# METATRON WORKFORCE — PHASE 4 / 01 ORGANIZATION RELATIONSHIP MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Semantic foundation:** Phase 2 canonical entity, ownership, lifecycle, transition, authorization, provenance, attribution, and temporal models.
**Phase:** 4 — Organization / Relationships

## 1. Purpose

This artifact defines the implementation-facing organization and relationship model required to make Workforce behave as an organization rather than a collection of independent Workers.

It does not define database tables, ORM mappings, UI screens, or external-domain ownership.

## 2. Phase 4 objective

The organization model MUST support:

- institutional hierarchy;
- organizational context;
- position and role occupancy;
- explicit reporting relationships;
- delegation within legitimate authority;
- escalation routing;
- cross-team coordination;
- temporal validity;
- attributable relationship changes.

The model MUST preserve the Phase 2 distinction between Worker, Position, Role, Participation, Authority, Delegation, Assignment, Authorization, Runtime, and Execution.

## 3. Organizational structure

The implementation MUST be able to represent a hierarchy such as:

```text
INSTITUTION
   ↓
ORGANIZATION
   ↓
DEPARTMENT
   ↓
TEAM
   ↓
POSITION
   ↓
WORKER
```

This is a representational pattern, not a mandatory fixed depth. Actual organizational hierarchy MUST remain configurable.

An organizational context identifies where a Worker participates operationally. It does not replace the authoritative Organization domain.

## 4. Organization context

`Organization Context` is the Workforce operational reference to an institutional unit in which work relationships are interpreted.

A context MAY represent:

- institution;
- organization;
- department;
- team;
- project or other approved organizational unit.

Each relationship that materially depends on organizational placement MUST be interpretable within an explicit context.

## 5. Position

A `Position` represents an institutional placement in an organizational structure.

Position is distinct from Worker and Role.

```text
WORKER ≠ POSITION
POSITION ≠ ROLE
```

A Worker may occupy a Position during a bounded validity interval. A Position may exist independently of the Worker currently occupying it.

The model MUST preserve effective and end validity where organizational placement is time-sensitive.

## 6. Role

A `Role` defines responsibility or functional placement applicable within an organizational context.

Role is not identity and does not itself constitute authority.

```text
ROLE ≠ AUTHORITY
ROLE ≠ AUTHORIZATION
```

A Role MAY be associated with one or more authority grants, but legitimate authority MUST remain explicitly represented and bounded.

## 7. Reporting relationships

Reporting relationships MUST be explicit and typed.

Supported relationship semantics include:

- `REPORTS_TO` — operational reporting relationship;
- `MANAGES` — management relationship;
- `SUPERVISES` — supervisory relationship;
- `ADVISES` — advisory relationship;
- `COORDINATES_WITH` — peer or cross-unit coordination;
- `DELEGATES_TO` — delegation relationship, subject to the delegation model.

These relationships MUST NOT be treated as interchangeable.

A reporting relationship MUST identify, at minimum:

- source participant/Worker reference;
- target participant/Worker reference;
- relationship type;
- organizational context;
- effective validity;
- initiating/authorizing context where required;
- provenance/evidence reference.

## 8. Relationship invariants

The implementation MUST preserve these invariants:

1. A reporting relationship does not create constitutional legitimacy.
2. A reporting relationship does not automatically create unrestricted authority.
3. A Role does not automatically authorize every action available to that Role.
4. Organizational hierarchy does not bypass authorization policy.
5. Delegation does not transfer more authority than the delegator legitimately possesses.
6. Relationship changes must remain attributable and historically reconstructable where required.
7. Cross-team coordination must not collapse distinct organizational identities.
8. Worker identity remains stable when organizational placement changes.

## 9. Delegation boundary

Delegation is an explicit, bounded extension or transfer of authority.

A delegation MUST be interpretable by:

```text
DELEGATOR
   ↓
LEGITIMATE AUTHORITY
   ↓
DELEGATION
   ↓
DELEGATEE
   ↓
SCOPE
   ↓
CONTEXT
   ↓
TIME WINDOW
   ↓
POLICY / CONDITIONS
```

Delegation MUST be:

- explicit;
- bounded;
- time-aware;
- scope-aware;
- attributable;
- revocable.

Workforce records the Worker-side representation of delegation. It MUST NOT manufacture legitimacy owned by Governance or another authoritative domain.

## 10. Escalation

Workers MUST be able to route material exceptions to an appropriate organizational authority.

Escalation categories include:

- blocked work;
- insufficient authority;
- resource shortage;
- capacity shortage;
- conflict;
- exception;
- risk;
- policy ambiguity.

An escalation route MUST be resolved from explicit organizational relationships and configured rules rather than assumed from an arbitrary Worker identifier.

An escalation record SHOULD preserve:

- originating Worker/work context;
- reason/category;
- target organizational context or authority route;
- urgency;
- created time;
- current state;
- evidence/reference;
- resulting decision or action when available.

## 11. Cross-team coordination

Workers in different teams or departments MUST be able to coordinate without implying a reporting relationship.

Example:

```text
HEAD WORKFORCE
       ↕
HEAD TECH
       ↕
HEAD PEOPLE
       ↕
HEAD ECONOMY
```

Coordination MUST remain distinct from:

- management;
- supervision;
- delegation;
- authorization.

## 12. Relationship lifecycle

Organizational relationships are temporal records.

At minimum, the implementation MUST distinguish:

- proposed/created;
- effective;
- suspended where applicable;
- ended/revoked where applicable;
- recorded time.

A later organizational change MUST NOT rewrite historical relationship truth.

## 13. Relationship change contract

A material organization/relationship transition MUST preserve the Phase 2 transition contract:

- current state;
- requested next state;
- initiating actor;
- authority source;
- preconditions;
- temporal validity;
- evidence/provenance;
- resulting event;
- failure behavior.

Technical ability to mutate a relationship is never sufficient authority.

## 14. Authorization boundary

Organization structure supplies context for authorization; it does not replace authorization.

A relationship such as `MANAGES` may establish organizational responsibility, but a specific action remains subject to:

```text
ACTOR
+
ROLE
+
AUTHORITY
+
SCOPE
+
POLICY
+
CONTEXT
+
TIME
+
RESOURCE
```

This preserves the Phase 2 distinction:

```text
AUTHORITY ≠ AUTHORIZATION
ASSIGNMENT ≠ AUTHORIZATION
AUTHORIZATION ≠ EXECUTION
```

## 15. Ownership boundary

Workforce owns the operational representation of Worker-side organizational relationships.

The model MUST NOT silently claim ownership of:

- constitutional legitimacy;
- institutional policy;
- Governance authority;
- authoritative Organization identity where externally owned;
- Gateway enforcement;
- Execution infrastructure;
- Economy accounting.

Cross-domain writes require an explicit contract and authoritative acceptance by the owning domain.

## 16. Evidence and attribution

Material organizational changes MUST remain attributable.

At minimum, the implementation must be able to reconstruct:

```text
WHO
 ↓
WHAT RELATIONSHIP CHANGED
 ↓
IN WHICH CONTEXT
 ↓
WHEN
 ↓
UNDER WHICH AUTHORITY
 ↓
BASED ON WHAT EVIDENCE
 ↓
WHAT RESULTED
```

Relationship evidence is not automatically truth. Authoritative truth remains with the owning authority.

## 17. Phase 4 acceptance target

The implementation derived from this artifact must be capable of demonstrating the Master Execution Plan Gate G4 requirements:

- [ ] Real reporting structure
- [ ] Multiple organizational levels
- [ ] Delegation
- [ ] Escalation
- [ ] Authority inheritance/limits
- [ ] Cross-team coordination

No Phase 4 implementation should be considered complete merely because relationship objects can be stored. The behavior must demonstrate organizational semantics, authority boundaries, temporal validity, and attribution.
