# METATRON COGNITIVE RUNTIME — EXECUTION PLAN

**Status: P0-P12 IMPLEMENTED / PRE-PRODUCTION ACCEPTANCE GREEN — P13 NOT STARTED — 2026-09-11**

## Dependency order

```text
P0 -> P1 -> P2/P3 -> P4 -> P5 -> P6 -> P7 -> P8 -> P9 -> P10 -> P11 -> P12 -> P13 -> P14
```

## P0 — Baseline and evidence

Instrument per-call frontier usage and capture current call-count behavior without changing production semantics. Establish representative baselines for ordinary conversation, deterministic requests, provider failover, multi-model and execution planning.

Acceptance: each frontier call can be correlated to one logical request/Case/purpose with provider/model/latency/token evidence where reported.

## P1 — Canonical request boundary

Add `CanonicalRequestEnvelope` and stable logical request identity at channel ingress. Preserve backward compatibility while consumers migrate.

Acceptance: channel/provider transport metadata cannot become institutional authority or Worker identity.

## P2 — Institutional Context Plane

Add `InstitutionalContextPackage`, fingerprinting and resolver boundary. Context precedence follows canonical authority order and explicitly records conflicts/freshness.

Acceptance: cognition receives a bounded, relevant, attributable context package instead of unstructured institutional dumping.

## P3 — Structured memory

Separate conversational transcript from institutional/cognitive state. Persist/reuse Case references, cognitive artifacts, evidence, decisions and outcomes independent of provider session.

Acceptance: provider history can be discarded without losing institutional state.

## P4 — Information-first acquisition

Complete structured information requirements and deterministic acquisition ordering. Knowledge/repo/runtime/API/tool evidence precedes unnecessary cognition.

Acceptance: missing information is machine-visible; acquisition provenance is retained.

## P5 — Cognition gate

Add deterministic cognition-need policy and zero-call paths where deterministic/retrieval results are sufficient.

Acceptance: representative deterministic/retrieval requests complete without frontier consumption.

## P6 — Centralize Intelligence Fabric

Converge production Human interaction, execution planning and Worker intelligence onto the shared institutional Intelligence runtime. Compatibility constructors may remain only for tests/migration.

Acceptance: no production-owned duplicate provider router/policy loop.

## P7 — Provider budgets

Add explicit provider budget and `EscalationReason` contracts. Initial frontier budget is one for FAST/ANALYZE/DEEP unless deterministic zero-call is sufficient. Fallback and extra calls are bounded.

Acceptance: any second-or-later call has a valid reason code and budget trace.

## P8 — Cognitive Artifact Store

Introduce reusable provider-neutral cognitive artifacts with input/context/evidence fingerprints and validity controls.

Acceptance: equivalent valid work can reuse an artifact with zero new frontier calls and explicit reuse trace.

## P9 — Evidence-backed escalation and multi-model

Disable implicit provider-count-as-depth behavior. Multi-model and challenge rounds require evidence-backed reasons. Seek stronger evidence before targeted challenge when useful.

Acceptance: multi-model is exceptional, bounded and reason-coded; unresolved disagreement remains explicit.

## P10 — Execution / Worker integration

Preserve authority separation while propagating Case/request/context/evidence refs into planning and Worker cognition. Prefer deterministic execution plans when sufficient.

Acceptance: cognition cannot authorize execution; Worker identity remains institutional, not provider-derived.

## P11 — Cross-channel continuity

Use Case/request/context identities independent of Telegram/Web/API/other client transport and provider sessions.

Acceptance: transport-only channel decoration cannot change otherwise equivalent cognitive state/fingerprints; Case state remains institutional and provider-neutral.

## P12 — Comparative acceptance

Run unit, integration and representative workload acceptance. Compare baseline vs new call-count/token/latency behavior using observed metrics only.

Acceptance: AC-01..AC-17 from the Final Proposal are backed by tests/runtime evidence; no fabricated savings claim. Current pre-production evidence is recorded in `08_COGNITIVE_RUNTIME_P12_ACCEPTANCE.md`.

## P13 — Controlled production rollout

Roll out behind compatibility-safe defaults. Observe failure rate, latency, zero/one-call rate, fallback/escalation/reuse and authority/evidence regressions. Roll back on institutional correctness regressions.

**Not started in this tranche.** Production must remain on the known-good image until the reconciled branch passes the full Gradle build and a separately authorized deployment begins.

## P14 — Legacy retirement and docs closure

Remove superseded mandatory semantic-call assumptions and duplicated provider-control paths only after production acceptance. Update traceability/evidence docs and mark compatibility code clearly.

**Not started.** Requires P13 production evidence.

## Change discipline

- Preserve current upstream Workforce schema/runtime additions when reconciling old work.
- Never overwrite current main wholesale with an older branch.
- Port only Cognitive Runtime-specific deltas.
- Build/test before any deploy.
- Do not claim runtime/production completion without successful evidence.
- Do not deploy as part of P0-P12 implementation unless separately authorized.
