# Metatron Intelligence Evaluation & Routing Feedback Loop

Status: CANONICAL CLOSURE CONTRACT
Scope: observed Intelligence outcomes → durable evaluation → provider quality recalibration → future AUTO routing

## Objective

Metatron must improve provider selection from real institutional outcomes without binding Worker identity to a provider or inventing provider superiority.

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

The repository already had `LearningService`, `LearningEvidence`, `Experience`, `IntelligenceOutcomeLearningBridge`, independent `ObservationReport`, `ProviderCallTraceRegistry`, and the P2 `ProviderCapabilityQualityRegistry` consumed by adaptive provider routing.

The real gap was that these systems did not form one feedback loop. Observed outcomes created Experiences, but no durable evaluation updated the provider-quality registry used by future routing. P2 quality was process-local unless manually seeded by environment.

## Closure architecture

### Durable feedback state

`IntelligenceRoutingFeedbackStore` persists capability-specific provider aggregates and bounded feedback event history including provider/model, logical request/case attribution, source (`OBSERVATION` or `HUMAN`), score, weight, evidence and time.

Production uses `FileIntelligenceRoutingFeedbackStore` at `METATRON_INTELLIGENCE_ROUTING_FEEDBACK_PATH`, defaulting to `/var/lib/metatron-workforce/intelligence-routing-feedback.json`.

On restart, aggregate scores are restored into `ProviderCapabilityQualityRegistry` before subsequent AUTO routing decisions.

### Automatic Observation feedback

`IntelligenceOutcomeLearningBridge` still creates canonical Phase 8 `LearningEvidence` and `Experience`. The same successful Observation boundary is then offered to `IntelligenceRoutingFeedbackService`.

Automatic learning is fail-closed and requires:

1. successful `INT-WORKFORCE-OBSERVATION` boundary;
2. typed `ObservationReport`;
3. PASS or FAIL criterion;
4. non-INSUFFICIENT Observation quality;
5. provider trace belonging to the same Intelligence Case and no later than the Observation;
6. exactly one successful provider call in the latest logical request.

Ambiguous multi-model outcomes are not attributed to any provider.

### Scoring

Observation score:

- PASS: `0.5 + 0.5 × confidence`
- FAIL: `0.5 × (1 - confidence)`

Observation weight:

- HIGH: `1.0`
- MEDIUM: `0.65`
- LOW: `0.35`
- INSUFFICIENT: no learning

Aggregates use a neutral score `0.5` with prior weight `3`:

```text
(1.5 + Σ(score × weight)) / (3 + Σweight)
```

This prevents one outcome from dominating routing. The aggregate is applied to `ProviderCapabilityQualityRegistry`, so the next P2 AUTO routing decision consumes the new evidence-backed score.

### Human assessment

`recordHumanAssessment(...)` provides an explicit evidence-bearing Human feedback path using the same durable aggregate and conservative weighting. It does not bypass routing governance or mutate Worker identity.

### Provider failure vs quality

Provider transport failure remains health/capacity telemetry and is not treated as semantic-quality evidence. The feedback loop evaluates successful provider outputs against observed outcomes.

### Model evidence

Feedback events retain the exact model used. Current quality aggregation is provider × capability; model-specific history is preserved for future model-level calibration without pretending a trustworthy model-comparison policy already exists.

## Acceptance proof

`IntelligenceRoutingFeedbackLoopAcceptanceTest` proves:

1. authoritative Observation PASS creates quality evidence;
2. learned quality changes the next AUTO route;
3. authoritative Observation FAIL lowers quality;
4. ambiguous multi-model outcome does not teach the wrong provider;
5. explicit Human acceptance feeds the same durable aggregate;
6. learned quality survives restart;
7. untyped Observation cannot mutate quality;
8. the normal `IntelligenceCaseLifecycleService → IntelligenceOutcomeLearningBridge` path invokes feedback automatically.

The Spring startup test isolates `METATRON_INTELLIGENCE_ROUTING_FEEDBACK_PATH` so CI validates production bean composition without touching production state.

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

P3 is operationally closed only when feedback store/service and Observation wiring are merged, full build and CI/conformance pass, exact immutable merge SHA is deployed, and production SHA/health/Gateway verification pass.
