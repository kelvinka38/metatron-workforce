# PHASE 9 / D2 — EXECUTABLE BOUNDARY SEMANTICS

## Objective

Turn the six canonical Phase 9 integration contracts into executable boundary semantics without moving ownership of external domains into Workforce.

D2 depends on D1. The canonical contracts remain the source of boundary identity and declared semantics.

## Canonical boundaries

1. Authorization
2. Gateway
3. Execution
4. Observation
5. Knowledge
6. Economy

## Common semantic model

Every boundary request carries:

- request identity;
- canonical integration contract identity;
- actor identity;
- external authority reference;
- input payload;
- provenance.

Every boundary decision carries:

- status;
- authority reference;
- reason;
- provenance.

Every boundary result carries:

- request identity;
- contract identity;
- terminal status;
- output/evidence when successful;
- authority reference;
- provenance.

## Terminal semantics

| Boundary | Success | Non-success |
|---|---|---|
| Authorization | `SUCCESS` | `DENIED` |
| Gateway | `SUCCESS` | `REJECTED` |
| Execution | `SUCCESS` | `FAILURE` |
| Observation | `SUCCESS` | `UNAVAILABLE` |
| Knowledge | `SUCCESS` | `REJECTED` |
| Economy | `SUCCESS` or attributable failure | failure remains failure |

A successful result requires output evidence. A failed result cannot be converted to success by Workforce.

## Authority rule

Workforce preserves the authority reference supplied by the owning external boundary. It does not manufacture an authorization decision, gateway authority, execution infrastructure, observation truth, knowledge admission authority, or accounting truth.

## Provenance rule

Provenance is mandatory at request, decision, and result boundaries. The executable service preserves the supplied provenance rather than manufacturing lineage.

## Ownership rule

`Phase9BoundaryService` performs boundary validation and semantic normalization only. It is not an authorization server, gateway, execution engine, observation system, knowledge repository, or accounting system.

## Acceptance gate

D9-D2 passes only when:

- all six D1 contracts remain present;
- every boundary is executable through the common semantic model;
- authorization failure denies execution;
- gateway failure fails closed;
- execution failure remains failure;
- unavailable observations remain unavailable;
- unvalidated knowledge is not admitted;
- economy emits evidence without becoming accounting authority;
- provenance is mandatory and preserved;
- external authority remains explicit;
- unknown contracts are rejected;
- successful results require output evidence;
- the full Phase 8 + Phase 9 test suite passes.

## Gate

D9-D2 = PASS only after implementation and full regression acceptance.

Phase 10 must not begin from D2 alone.
