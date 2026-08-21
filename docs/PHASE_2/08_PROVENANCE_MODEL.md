# METATRON WORKFORCE — PHASE 2 / 08 PROVENANCE MODEL

## 1. Principle

Material claims, decisions, state transitions, and evidence must preserve enough lineage to explain where they came from.

Evidence is not automatically truth.
Observation is not automatically interpretation.

## 2. Provenance chain

```text
CLAIM / DECISION / STATE
        ↓
SOURCE
        ↓
EVIDENCE
        ↓
PROVENANCE
        ↓
TIME / CONTEXT
        ↓
ACTOR / AUTHORITY
```

## 3. Minimum provenance dimensions

Where materially applicable, preserve:

- source reference;
- source type;
- actor/producer;
- originating event;
- timestamp;
- organizational context;
- authority context;
- assignment context;
- causal relationship;
- evidence references;
- transformation/derivation reference.

## 4. Capability provenance

Capability claims must distinguish evidence status such as:

- claimed;
- demonstrated;
- assessed;
- certified;
- inferred;
- observed.

A capability claim must not silently become an authority grant.

## 5. Decision provenance

A material decision must preserve the inputs and authority context that made the decision possible.

Conceptually:

```text
request
 ↓
context
 ↓
policy / authority inputs
 ↓
decision
 ↓
reason / evidence
```

## 6. State provenance

A material state must be traceable to the accepted transition/event that produced it. Reconstructing state from current values alone must not erase material historical origin.

## 7. Learning provenance

Learning claims must preserve lineage to:

```text
execution
 ↓
outcome / observation / evidence
 ↓
experience
 ↓
reflection
 ↓
evaluation
 ↓
learning
```

A learning result without source experience/evidence is not sufficient lineage.

## 8. Economic provenance

Economic evidence supplied by Workforce must preserve the operational basis from which Economy can determine consequences, including where applicable:

- Worker;
- organization/context;
- work;
- role;
- assignment;
- time;
- capacity;
- resources;
- staffing;
- cost basis;
- allocation basis;
- outcome.

Workforce does not become the accounting ledger.

## 9. Transformation rule

When evidence or state is transformed into a derived record, the derived record must retain a reference to its material source lineage. Transformation must not erase origin merely because representation changed.

## 10. External provenance

When evidence originates in another institutional domain, Workforce records the external source/reference rather than claiming ownership of the external domain's authoritative truth.

## 11. Implementation constraint

This model defines provenance semantics and required lineage. It does not prescribe a particular audit-log, event-store, database, or storage technology.