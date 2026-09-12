# METATRON INTELLIGENCE — GOVERNING ARCHITECTURE ENTRY POINT

**Status: MANDATORY — READ BEFORE ANY INTELLIGENCE CHANGE**

This directory is the Workforce engineering control point for Metatron Intelligence architecture.

## Governing order

```text
UPSTREAM CANONICAL SOT / POLICY
        ↓
04_COGNITIVE_RUNTIME_FINAL_PROPOSAL.md
        ↓
05_COGNITIVE_RUNTIME_DETAILED_PLAN.md
        ↓
06_COGNITIVE_RUNTIME_EXECUTION_PLAN.md
        ↓
03_TRACEABILITY_MATRIX.md + IMPLEMENTATION / RUNTIME EVIDENCE
        ↓
11_INTELLIGENCE_FABRIC_UPGRADE_CLOSURE.md
        ↓
CONTRACT / STATE / EVENT DESIGN
        ↓
IMPLEMENTATION
        ↓
TEST / RUNTIME EVIDENCE
```

Upstream canonical SoT/policy always wins. If a downstream rule conflicts with stronger upstream authority, the conflict must be surfaced and reconciled explicitly.

## Current mandatory baseline

1. `04_COGNITIVE_RUNTIME_FINAL_PROPOSAL.md`
2. `05_COGNITIVE_RUNTIME_DETAILED_PLAN.md`
3. `06_COGNITIVE_RUNTIME_EXECUTION_PLAN.md`
4. `03_TRACEABILITY_MATRIX.md`
5. `07_COGNITIVE_RUNTIME_P0_BASELINE.md`
6. `08_COGNITIVE_RUNTIME_P12_ACCEPTANCE.md`
7. `09_COGNITIVE_RUNTIME_P13_PRODUCTION_ACCEPTANCE.md`
8. `10_COGNITIVE_RUNTIME_P14_LEGACY_RETIREMENT.md`
9. `11_INTELLIGENCE_FABRIC_UPGRADE_CLOSURE.md` — current implementation/status reconciliation
10. `../INTELLIGENCE_ROUTING_CORRECTNESS_GAP_CLOSURE.md` — routing correctness evidence
11. `../INTELLIGENCE_EVALUATION_ROUTING_FEEDBACK_GAP_CLOSURE.md` — observed-outcome routing feedback evidence

12. `12_WORKFORCE_COGNITIVE_SUBSTRATE_SOVEREIGNTY_FINAL_PROPOSAL.md` — Founder-approved Worker cognition ownership authority
13. `13_WORKFORCE_COGNITIVE_SUBSTRATE_SOVEREIGNTY_DETAILED_ARCHITECTURE.md`
14. `14_WORKFORCE_COGNITIVE_SUBSTRATE_SOVEREIGNTY_CONTRACT_STATE_EVENT_DESIGN.md`
15. `15_WORKFORCE_COGNITIVE_SUBSTRATE_SOVEREIGNTY_DETAILED_IMPLEMENTATION_PLAN.md`
16. `16_WORKFORCE_COGNITIVE_SUBSTRATE_SOVEREIGNTY_EXECUTION_PLAN.md`
17. `17_WORKFORCE_COGNITIVE_SUBSTRATE_SOVEREIGNTY_ACCEPTANCE_MATRIX.md`
18. `18_WORKFORCE_COGNITIVE_SUBSTRATE_SOVEREIGNTY_HISTORICAL_RECONCILIATION.md`
19. `19_WORKFORCE_COGNITIVE_SUBSTRATE_SOVEREIGNTY_SERVER_UPGRADE_GATE.md`

For Worker cognition, provider-consumption, inference infrastructure, Worker-reachable retrieval, or cognitive-scale work, documents `12` through `19` are mandatory current authority after the Cognitive Runtime baseline. The downstream chain is `12 → 13 → 14 → 15 → 16 → 17`; document `18` preserves historical closure truth and document `19` is the blocking pre-implementation gate.

Hard invariant:

```text
WORKER_ORIGINATED_PAID_EXTERNAL_INFERENCE = 0
```

**Implementation is intentionally NOT authorized yet.** Founder server-upgrade review must release document `19` before any code/runtime implementation begins.

`01_FINAL_ARCHITECTURE_PROPOSAL.md` and `02_DETAILED_ARCHITECTURE.md` are historical/compatibility architecture only. Any old downstream rule that makes a frontier semantic call mandatory before every deterministic/retrieval path, treats depth as provider count, or permits duplicate production provider-control loops is superseded by the Founder-approved Cognitive Runtime baseline above.

Historical P13/P14 receipts retain the state and evidence known when they were written. Their historical status banners MUST NOT be interpreted as the current overall Intelligence Fabric status when later accepted/production evidence exists. `11_INTELLIGENCE_FABRIC_UPGRADE_CLOSURE.md` is the current downstream reconciliation record and supersedes stale historical "deploy pending" language for present-state interpretation without rewriting history.

## Cognitive runtime lock

```text
REQUEST / OBJECTIVE
        ↓
DETERMINISTIC INGRESS + CONTINUITY
        ↓
RELEVANT INSTITUTIONAL CONTEXT
        ↓
INFORMATION REQUIREMENTS + ACQUISITION
        ↓
COGNITION NEED GATE
        ↓
ZERO FRONTIER CALLS WHEN SUFFICIENT
        OR
ONE FRONTIER CALL BY DEFAULT
        ↓
RESULT / EVIDENCE GATE
        ↓
BOUNDED REASON-CODED ESCALATION ONLY WHEN JUSTIFIED
```

Raw Human text MUST NOT be routed to institutional execution through keyword/regex/continuation heuristics as the principal semantic architecture. At the same time, deterministic controls, deterministic computation, authoritative retrieval and existing valid cognitive artifacts MUST NOT pay an unnecessary frontier call merely to satisfy an obsolete semantic toll.

FAST / ANALYZE / DEEP represent investigation depth, not provider count. Multi-model is exceptional and explicitly bounded.

## Ownership locks

```text
LLM != METATRON
LLM != WORKER
MODEL != ROLE
CLIENT != PROVIDER
INTELLIGENCE != AUTHORITY
KNOWLEDGE != COGNITION
INTENT != AUTHORIZATION
CLAIM != EVIDENCE
DECISION != EXECUTION
EXECUTION != OUTCOME
```

Workplace retains meeting/conversation ownership. Workforce retains Worker/work/assignment lifecycle. Gateway retains the controlled external boundary. Knowledge admission, Observation/Outcome and Execution remain in their canonical domains.

Provider/model choice is an implementation detail of the Intelligence Fabric unless the Human explicitly requests a provider. Provider sessions never own institutional memory.

## Production composition lock

Production provider control is owned by `InstitutionalIntelligenceRuntime` and the shared `IntelligenceFabric`. Channel ingress, execution planning and Worker cognition consume that shared institutional Intelligence capacity; they MUST NOT create independent production-owned provider routers or policy loops.

AUTO routing may use evidence-backed capability quality, measured provider health/failure/concurrency/latency and authoritative cost data when available. Observed outcomes may feed future routing only through the governed routing-feedback loop. Unknown quality/cost remains unknown/neutral; provider brand is not evidence.

Compatibility/test constructors and overloads may remain only when clearly non-governing. See `10_COGNITIVE_RUNTIME_P14_LEGACY_RETIREMENT.md`, `11_INTELLIGENCE_FABRIC_UPGRADE_CLOSURE.md`, and the `interaction.intelligence` package documentation.

## Current implementation gate

`08_COGNITIVE_RUNTIME_P12_ACCEPTANCE.md` records pre-production acceptance. `09_COGNITIVE_RUNTIME_P13_PRODUCTION_ACCEPTANCE.md` records the historical accepted production rollout. `10_COGNITIVE_RUNTIME_P14_LEGACY_RETIREMENT.md` records the historical legacy-retirement gate. `11_INTELLIGENCE_FABRIC_UPGRADE_CLOSURE.md` reconciles those receipts with later routing correctness, outcome-feedback and current production evidence.

Within the current Founder-approved Workforce-owned Intelligence scope, no additional runtime phase is implied by this closure. A new phase requires an observed defect, an unmet existing canonical requirement, or new Founder/upstream-approved scope.

No production percentage savings claim is authorized without measured telemetry. Architecture-derived and fixture-level call reductions must remain labeled as such.

## Change discipline

Before modifying conversational semantics, LLM/provider routing, reasoning, retrieval, Worker intelligence, execution planning, memory/context, tool use or learning-from-intelligence behavior:

- read the current mandatory baseline;
- preserve upstream authority;
- retain measurable evidence for provider consumption;
- prefer deterministic/retrieval paths when sufficient;
- keep any second-or-later frontier call bounded and reason-coded;
- do not create a second production provider-control loop;
- do not convert routing feedback into provider identity or Worker identity;
- do not claim execution, Observation, cost or savings without evidence;
- do not invent a new roadmap phase merely because a future enhancement is technically possible.
