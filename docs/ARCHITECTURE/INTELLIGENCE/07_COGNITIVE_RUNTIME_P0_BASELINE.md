# METATRON COGNITIVE RUNTIME — P0 BASELINE

**Status: IMPLEMENTATION ACTIVE — BASELINE VERIFIED ON CURRENT MAIN — 2026-09-11**

Baseline repository state before Cognitive Runtime behavior changes:

```text
branch: reconcile/cognitive-runtime
base:   9d5471b60b04c2f15ac4281a2ea688a347d35309
```

`./gradlew test` on that clean base completed successfully before the Cognitive Runtime port.

## Confirmed pre-migration call behavior

The current architecture pays a semantic frontier call before ordinary natural-language reasoning. The same logical Human request can then consume another frontier call for main reasoning.

Observed architectural minimums/upper bounds from current source paths:

- ordinary natural-language reasoning happy path: **minimum 2 frontier calls** — semantic normalization + main reasoning;
- FAST direct answer: **minimum 1 semantic frontier call**;
- deterministic operations discovered through semantic normalization still pay the semantic call;
- SINGLE-mode failover with three configured providers can reach **up to 6 attempts** — three semantic attempts plus three reasoning attempts if providers fail in sequence;
- three-provider multi-model can reach **up to 8 frontier calls** before any separate synthesis path: one semantic + three initial independent responses + one normalization + up to three targeted challenges;
- execution planning has a separate frontier cognition path when deterministic capability binding is insufficient;
- Worker intelligence escalation can call the shared Intelligence Fabric separately;
- conversational history can be supplied during semantic normalization and again during main reasoning, duplicating context consumption.

These are architecture-derived call counts, not a claim about provider billing or production average traffic.

## P0 instrumentation added

Per-call trace now records:

- logical request reference;
- Case reference where available;
- purpose;
- provider and model;
- success/failure;
- latency;
- provider-reported input/output/total token usage;
- system-context and user-input character counts;
- bounded failure evidence;
- completion timestamp.

Unknown provider usage remains `LlmUsage.UNKNOWN`; cost is not invented.

## Non-goals for P0

P0 does not yet alter provider selection, semantic policy, authority, execution authorization, Worker identity, Knowledge precedence or production deployment. It establishes evidence required to prove later credit/call reduction safely.

## Acceptance evidence

After the P0 port onto current main, the full Gradle test suite must remain green. Later phases must compare representative workload metrics against this baseline rather than inventing percentage savings.
