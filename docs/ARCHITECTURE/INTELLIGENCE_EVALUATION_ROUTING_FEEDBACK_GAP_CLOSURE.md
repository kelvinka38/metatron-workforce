# Metatron Intelligence Evaluation & Routing Feedback Loop

Status: CANONICAL CLOSURE CONTRACT
Scope: observed Intelligence outcomes → durable evaluation → provider quality recalibration → future AUTO routing

## Objective

Metatron must improve provider selection from real institutional outcomes without binding Worker identity to a provider or inventing provider superiority. The learning loop is:

```text
Worker / Intelligence request
    → provider + model route
    → provider call trace
    → execution / outcome
    → independent Observation
    → attribution + evaluation
    → durable capability-quality aggregate
    → ProviderCapabilityQualityRegistry
    → next AUTO route
```

Worker identity, memory, objective, authority and Assignment remain Metatron-owned. GPT/OpenAI, Gemini/Google and Claude/Anthropic remain replaceable cognitive capacity.

## Pre-closure audit

The repository already had:

- `LearningService`, `LearningEvidence`, `Experience` and Phase 8 learning primitives;
- `IntelligenceOutcomeLearningBridge` connecting successful Observation boundary evidence to Experience;
- `ProviderCallTraceRegistry` recording provider, model, logical request, case, success/failure and latency;
- `ProviderCapabilityQualityRegistry` consumed by P2 adaptive provider routing;
- independent `ObservationReport` with criterion result, confidence, quality and evidence.

The actual gap was that these systems did not form one feedback loop. Observed outcomes created Experiences, but no durable evaluation updated the provider-quality registry used by future routing. P2 quality was process-local unless manually seeded by environment.

## Closure architecture

### Durable feedback state

`IntelligenceRoutingFeedbackStore` persists:

- capability-specific provider aggregates;
- bounded feedback event history;
- provider and model used;
- logical request / case attribution;
- feedback source (`OBSERVATION` or `HUMAN`);
- score, weight, evidence reference and timestamp.

Production uses `FileIntelligenceRoutingFeedbackStore` at:

`METATRON_INTELLIGENCE_ROUTING_FEEDBACK_PATH`

Default:

`/var/lib/metatron-workforce/intelligence-routing-feedback.json`

On process restart, aggregate scores are restored into `ProviderCapabilityQualityRegistry` before subsequent AUTO routing decisions.

### Automatic Observation feedback

`IntelligenceOutcomeLearningBridge` still creates canonical Phase 8 `LearningEvidence` and `Experience` first. When production routing feedback is configured, the same successful Observation boundary is also offered to `IntelligenceRoutingFeedbackService`.

Automatic provider-quality learning is deliberately fail-closed. It requires:

1. successful `INT-WORKFORCE-OBSERVATION` boundary;
2. typed `ObservationReport` output;
3. terminal PASS or FAIL criterion;
4. non-INSUFFICIENT Observation quality;
5. provider call trace belonging to the same Intelligence Case and occurring no later than the Observation;
6. one latest logical request with exactly one successful provider call.

If attribution is ambiguous — especially multi-model collaboration — Metatron does not assign the case outcome to a provider.

### Scoring

Observation score is evidence-derived rather than brand-derived:

- PASS: `0.5 + 0.5 × confidence`
- FAIL: `0.5 × (1 - confidence)`

Observation weight:

- HIGH: `1.0`
- MEDIUM: `0.65`
- LOW: `0.35`
- INSUFFICIENT: no learning

Aggregates use a neutral prior of score `0.5` with prior weight `3`. This prevents one result from dominating future routing.

Effective score:

```text
(1.5 + Σ(score × weight)) / (3 + Σweight)
```

The resulting aggregate is written into `ProviderCapabilityQualityRegistry` with the Observation evidence reference. P2 AUTO routing then consumes the updated score on the next request for that capability class.

### Human assessment

`IntelligenceRoutingFeedbackService.recordHumanAssessment(...)` provides an explicit evidence-bearing Human feedback path. It uses the same durable aggregate and conservative weighting model; Human feedback does not bypass routing governance or mutate Worker identity.

### Provider failure vs quality

Transport/provider failures are not treated as outcome-quality evidence. Health, quota, cooldown, failures, concurrency and latency remain owned by `ProviderTelemetryRegistry`.

The feedback loop evaluates successful provider outputs against observed outcomes. This avoids double-counting infrastructure failure as semantic quality failure.

### Model evidence

Every feedback event retains the exact model used. Current AUTO quality aggregation is provider × capability because P2 presently has one configured model per provider/tier decision. Model-specific outcome history is therefore preserved for future model-level evaluation without pretending there is already a trustworthy model-comparison policy.

## Acceptance proof

`IntelligenceRoutingFeedbackLoopAcceptanceTest` proves:

1. authoritative Observation PASS creates a durable quality sample;
2. learned provider quality changes the next AUTO provider route;
3. authoritative Observation FAIL lowers capability quality;
4. multi-model outcome with ambiguous attribution does not teach either provider;
5. explicit Human acceptance feeds the same durable aggregate;
6. learned routing quality survives store/runtime reconstruction;
7. untyped Observation cannot mutate routing quality;
8. the normal `IntelligenceCaseLifecycleService → IntelligenceOutcomeLearningBridge` path triggers routing feedback automatically.

The Spring production startup test isolates `METATRON_INTELLIGENCE_ROUTING_FEEDBACK_PATH` so CI validates real production bean composition without touching production state.

## Invariants

```text
WORKER != PROVIDER
OBSERVATION SUCCESS != OUTCOME PASS
PROVIDER FAILURE != QUALITY FAILURE
AMBIGUOUS ATTRIBUTION != LEARNING SIGNAL
UNKNOWN QUALITY != LOW QUALITY
ONE OUTCOME != ROUTING TRUTH
EVIDENCELESS HUMAN OPINION != QUALITY UPDATE
```

## Definition of done

P3 is operationally closed only when:

- feedback store/service and Observation bridge wiring are merged;
- full tests/build pass;
- GitHub CI/conformance gates pass for the exact head;
- immutable merge SHA is deployed;
- production exact-SHA, liveness/readiness and Gateway health verification pass.
