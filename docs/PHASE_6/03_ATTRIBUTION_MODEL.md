# METATRON WORKFORCE — PHASE 6 / 03 ATTRIBUTION MODEL

**Authority:** `METATRON WORKFORCE — MASTER EXECUTION PLAN.md`
**Phase:** 6 — Authorization / Execution / Attribution
**Semantic foundation:** Phase 2 provenance and attribution models; Phase 3 workplace communication; Phase 4 organization, delegation, escalation; Phase 5 operational reality constraints; `01_AUTHORIZATION_MODEL.md`; `02_EXECUTION_MODEL.md`.

## 1. Purpose

This artifact defines how Workforce preserves responsibility and provenance across material institutional work.

Attribution answers:

```text
Who requested it?
Who decided?
Who authorized it?
Who was responsible?
Who executed it?
What runtime executed it?
What happened?
When did it happen?
Under which authority?
Based on which evidence?
```

Attribution MUST remain richer than the identity of the final runtime or Worker that physically performed an action.

## 2. Attribution chain

Where applicable, Workforce MUST preserve the chain:

```text
Originating Actor
      ↓
Request / Proposal
      ↓
Decision / Approval
      ↓
Authorization
      ↓
Assignment
      ↓
Responsible Worker
      ↓
Runtime Instance
      ↓
Execution
      ↓
Outcome / Evidence
```

Not every action contains every stage. Missing stages MUST remain semantically distinguishable from unknown stages.

## 3. Attribution dimensions

A material attribution record MUST preserve, where applicable:

- actor identity;
- actor type;
- Worker identity;
- runtime identity;
- organizational context;
- role/position;
- responsibility;
- action;
- target/resource scope;
- authority source;
- authorization reference;
- assignment reference;
- execution reference;
- temporal information;
- provenance/evidence;
- resulting outcome.

## 4. Actor identity

Actor identity MUST remain stable and distinguishable from display names, runtime credentials, sessions, or transport identities.

Actor types MAY include:

- Human;
- institutional Worker;
- authorized service/runtime actor;
- organizational actor where explicitly modeled.

A transport identity such as a Telegram account, browser session, API token, or runtime credential MUST NOT automatically become the institutional actor identity.

## 5. Responsibility versus execution

Responsibility and physical execution are distinct.

For example:

```text
Head Workforce
= responsible / decision authority

Worker A
= assigned executor

Runtime A-17
= technical execution instance
```

The system MUST preserve these distinctions where material.

A runtime performing an action does not necessarily mean the runtime was the responsible institutional actor.

## 6. Request attribution

A material work request MUST preserve its origin.

Where a Human instructs a Head:

```text
Human
  ↓
Head
```

the resulting work MUST retain a reference to the originating Human instruction where material.

Where a Worker creates work autonomously within delegated authority, the Worker remains the originating actor and the applicable authority/delegation MUST remain attributable.

## 7. Decision attribution

A decision that materially changes institutional work MUST preserve:

- decision maker;
- decision time;
- decision context;
- authority source;
- decision scope;
- decision outcome;
- supporting evidence.

A recommendation MUST remain distinguishable from a decision.

```text
Recommendation != Decision
```

## 8. Authorization attribution

Every material execution authorization MUST be traceable to the authority source and authorization decision that produced it.

Where authority was delegated, attribution MUST preserve both:

```text
Original authority holder
        ↓
Delegation
        ↓
Authorized delegate
```

The delegate MUST NOT replace the delegator in historical authority evidence.

## 9. Assignment attribution

Where work is assigned, the system MUST preserve:

- assigner;
- assigned Worker/team;
- assignment scope;
- assignment time;
- assignment authority;
- assignment validity;
- work reference.

Reassignment MUST create a distinguishable historical transition.

The current assignee MUST NOT overwrite the identity of a previous responsible Worker where historical attribution is material.

## 10. Execution attribution

Execution records MUST reference the institutional Worker responsible for execution and, where applicable, the runtime instance that performed the technical action.

Conceptually:

```text
Worker W-001
     ↓
Runtime R-017
     ↓
Execution E-042
```

The reverse relationship MUST also be reconstructable:

```text
Execution E-042
     ↓
Worker W-001
     ↓
Authorization A-009
     ↓
Delegation D-003
     ↓
Human / Head H-001
```

## 11. Communication attribution

Material workplace communication MUST preserve sender identity and conversation context.

Where a message causes institutional work, the causal relationship SHOULD remain traceable:

```text
Message
  ↓
Request / Decision
  ↓
Execution
```

A copied, forwarded, summarized, or transformed message MUST NOT silently erase the original source where provenance remains material.

## 12. Evidence and provenance

Attribution MUST be linked to evidence sufficient to support the asserted institutional fact.

Evidence MAY include:

- message reference;
- meeting record;
- approval record;
- authorization record;
- execution record;
- report;
- external system reference;
- observation;
- other evidence recognized by the architecture.

Workforce MUST distinguish:

```text
Fact
Evidence supporting fact
Inference derived from evidence
```

An inference MUST NOT be represented as directly observed fact merely because it was produced by a Worker.

## 13. Temporal attribution

Attribution MUST preserve material temporal distinctions.

At minimum, the implementation SHOULD distinguish:

- created time;
- effective time;
- scheduled time;
- actual time;
- recorded time;
- completion time;
- expiry/revocation time where applicable.

Later recording of an event MUST NOT imply that the event happened at the recording time.

## 14. Corrections and amendments

Corrections MUST preserve historical truth.

A correction MAY establish that a prior record was inaccurate, incomplete, or superseded, but MUST NOT silently rewrite the original event into a different historical event.

Conceptually:

```text
Original Record
      ↓
Correction / Amendment
      ↓
Current Interpretation
```

The original record and correction remain separately attributable.

## 15. Delegation attribution

Delegation MUST preserve both authority lineage and execution attribution.

Example:

```text
Head Workforce
   ↓ delegates
Deputy Workforce
   ↓ authorizes
Worker A
   ↓ executes
Runtime A
```

The execution MUST remain traceable to the delegated authority chain.

Delegation expiry or revocation MUST remain visible in historical attribution.

## 16. Cross-domain attribution boundary

Workforce may preserve references to authority, observation, knowledge, economy, governance, gateway, or other external-domain facts.

It MUST NOT manufacture those domains' authoritative truth.

Examples:

```text
Workforce
= execution attribution + operational evidence

Economy
= authoritative accounting truth
```

and:

```text
Workforce
= reference to external observation

Observation
= authoritative observation truth
```

## 17. Attribution under autonomous execution

Autonomous Worker execution MUST remain attributable.

Autonomy means the Worker may act within delegated authority without a new Human instruction for every step.

It does not mean:

- anonymous execution;
- unbounded authority;
- untraceable decision-making;
- removal of delegation lineage.

The system MUST preserve the authority basis under which autonomous execution was permitted.

## 18. Failure and uncertainty

If attribution cannot be reliably established, the system MUST represent the attribution as incomplete, uncertain, or otherwise insufficient rather than inventing an actor.

Examples:

```text
Actor = unknown
Evidence = insufficient
```

is materially different from:

```text
Actor = Worker A
```

where no supporting evidence exists.

## 19. Audit reconstruction

A material institutional action MUST permit reconstruction of:

```text
Origin
  ↓
Decision
  ↓
Authority
  ↓
Assignment
  ↓
Execution
  ↓
Outcome
  ↓
Evidence
```

The reconstruction MUST preserve the relevant temporal and organizational context.

## 20. Acceptance invariants

The implementation MUST satisfy at least these invariants:

1. Institutional identity is distinct from transport/session/runtime identity.
2. Originating actor is distinguishable from executor.
3. Responsible Worker is distinguishable from runtime instance.
4. Recommendation is distinguishable from decision.
5. Assignment is distinguishable from authorization.
6. Delegator remains attributable after delegation.
7. Reassignment does not erase historical responsibility.
8. Material execution remains attributable to an institutional Worker and applicable authority.
9. Evidence is distinguishable from inference.
10. Material temporal distinctions are preserved.
11. Corrections do not silently rewrite historical truth.
12. Autonomous execution remains attributable and bounded by authority.
13. Missing attribution is represented as insufficiency rather than invented identity.
14. Cross-domain authoritative truth remains owned by its authoritative domain.
15. Attribution is sufficient to reconstruct material institutional actions.

## 21. Phase 6 contribution

This artifact completes the semantic attribution contract required by Gate G6 together with the authorization and execution models.

It does not prescribe a specific audit database, event-store technology, identity provider, logging vendor, tracing system, or observability platform.
