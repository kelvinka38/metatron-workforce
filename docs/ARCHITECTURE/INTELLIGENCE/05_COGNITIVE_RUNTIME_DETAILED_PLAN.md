# METATRON COGNITIVE RUNTIME — DETAILED IMPLEMENTATION PLAN

**Status: FOUNDER APPROVED IMPLEMENTATION BASELINE — 2026-09-11**

This plan implements `04_COGNITIVE_RUNTIME_FINAL_PROPOSAL.md` incrementally without discarding working runtime behavior or violating upstream canonical authority.

## WP1 — Baseline observability

Instrument every frontier call with logical request reference, Case reference, purpose, provider, model, latency, provider-reported token usage, context/input sizes, success/failure and timestamp. Unknown usage remains unknown. Capture representative call-count baselines before behavior changes.

Exit: per-call evidence can answer how many provider calls one logical request consumed and why.

## WP2 — Canonical request boundary

Introduce a provider-neutral `CanonicalRequestEnvelope` at the channel-to-Intelligence boundary. Explicit controls are deterministic metadata; raw Human text remains content, not authority. Request identity must be stable enough to correlate semantic, reasoning, fallback and execution-planning cognition.

Exit: channel adapters produce equivalent canonical envelopes and do not own reasoning policy.

## WP3 — Institutional Context Plane

Introduce `InstitutionalContextPackage` and a resolver boundary. Resolve only relevant authority/canonical/spec/plan/runtime/Case/evidence references, with provenance, freshness, conflicts and fingerprint. No prompt receives a blind dump of all institutional material.

Exit: every cognition request can identify the governing context version used.

## WP4 — Memory evolution

Keep conversational transcript memory for continuity, but separate it from institutional/cognitive state. Add structured Case/cognitive artifacts, decisions, evidence references and reusable facts/outcomes. Provider conversation history is never canonical memory.

Exit: cross-channel continuation can recover institutional state without requiring a provider-specific transcript.

## WP5 — Information Requirement Engine

Normalize requirements into statuses:

```text
known | missing | stale | conflicting | unavailable
```

and importance:

```text
required | supporting | optional
```

Acquire in canonical order: valid Case/artifact/cache → Knowledge → institutional artifact → runtime/repo → deterministic API/DB/calculation → Worker evidence → authorized structured external → web/research → cognition → Human-only.

Exit: missing information is explicitly represented and acquisition precedes unnecessary cognition.

## WP6 — Cognition Need Gate

Add a deterministic gate that can return:

- `NOT_REQUIRED` when the answer/action can be produced deterministically or by retrieval/evidence already acquired;
- `REQUIRED` when synthesis/reasoning is genuinely needed;
- `BLOCKED` when required authority/evidence is unavailable and cognition cannot safely substitute.

Depth changes breadth of investigation, not automatic provider count.

Exit: representative deterministic/retrieval requests reach zero frontier calls.

## WP7 — Shared Intelligence Fabric

All production cognition consumers use the same institutional provider runtime, routing health, budget policy and telemetry. Human interaction, execution planning and Worker intelligence consume Fabric; they do not own independent uncontrolled provider loops.

Compatibility adapters may remain temporarily but must converge on the shared boundary.

Exit: provider routing/telemetry/budget policy has one institutional owner.

## WP8 — Provider budgets and escalation

Introduce explicit provider budget and escalation reason contracts.

Default:

```text
FAST    initial_frontier_calls <= 1
ANALYZE initial_frontier_calls <= 1
DEEP    initial_frontier_calls <= 1
multi-model disabled unless justified
```

Valid reason codes:

```text
PROVIDER_FAILURE
MATERIAL_CONTRADICTION
INSUFFICIENT_EVIDENCE
HIGH_CONSEQUENCE_CHALLENGE
EXPLICIT_HUMAN_REQUEST
CAPABILITY_MISMATCH
NOVEL_INFORMATION_ACQUIRED
```

Each second-or-later call must carry a reason and remain within bounded limits. Provider failure fallback is bounded; no unbounded retry fan-out.

Exit: one-call default is machine-verifiable.

## WP9 — Cognitive Artifact Store

Store reusable `CognitiveArtifact` objects keyed by:

- normalized objective fingerprint;
- institutional context fingerprint;
- evidence version/fingerprint;
- required capability;
- output contract/model constraints as applicable.

Artifact records contain provider/model attribution, claims/evidence/uncertainty, validity and reuse eligibility. Reuse is invalidated by relevant context/evidence change.

Exit: repeated equivalent work can avoid a new frontier call and produces reuse evidence.

## WP10 — Result/Evidence Gate and multi-model control

Evaluate output against evidence, uncertainty, contradiction and consequence before considering escalation. Consensus is not correctness. On contradiction, seek stronger evidence before targeted challenge when practical.

Multi-model flow, when justified:

```text
independent proposals
→ normalization / contradiction map
→ evidence acquisition where useful
→ one targeted challenge round maximum
→ synthesis with unresolved disagreement preserved
```

Exit: multi-model is exceptional and every extra call is reason-coded.

## WP11 — Execution / Worker integration

Cognition may propose plans or interpretations; it does not grant authority. Execution remains gated by canonical authorization/capability contracts. Worker identity is never inferred from provider/model. Execution planning must reuse deterministic plans when sufficient and consume frontier capacity only when decomposition materially adds value.

Exit: no cognitive result can bypass authority, authorization, capability or Observation.

## WP12 — Cross-channel continuity

Use stable Case/request/context references independent of client transport. ChatGPT, Telegram, Web/API and future clients must be able to continue the same institutional Case without provider-owned memory.

Exit: at least two client surfaces can continue one Case with equivalent institutional state.

## Tests

Required test layers:

- unit tests for contracts, fingerprints, gates, budgets, reuse and reason codes;
- integration tests for one-call default, bounded fallback and zero-call deterministic paths;
- conformance tests proving authority/evidence separation and client/provider neutrality;
- workload comparison using representative requests before/after migration;
- load/latency checks for context resolution, artifact reuse and telemetry.

## Metrics

Track at minimum:

- frontier calls per logical request;
- frontier calls per Case;
- zero-call rate;
- one-call rate;
- fallback rate;
- escalation rate by reason;
- multi-model rate;
- provider-reported input/output/total tokens;
- unknown-usage rate;
- duplicated-context bytes/chars;
- cognitive artifact reuse rate;
- retrieval-before-cognition rate;
- provider health and p50/p95 latency;
- estimated cost only where pricing data is authoritative, otherwise unknown.

## Migration rule

Do not rewrite the entire runtime at once. Reuse existing contracts where compatible, including `IntelligenceCase`, `InformationRequirement`, `KnowledgeRetrievalService`/Knowledge Fabric, `IntelligenceEngine`, `LlmProviderRouter`, `ProviderTelemetryRegistry`, conversation continuity, Tool Fabric and the existing execution/Worker integration. Replace policy and state ownership where they conflict with the Founder lock.

## Exit criteria

The implementation phase is complete only when acceptance is machine-verifiable and representative workloads demonstrate the new runtime behavior against the measured baseline without claiming fabricated savings percentages.
