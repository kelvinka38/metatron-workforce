# METATRON COGNITIVE RUNTIME — P12 PRE-PRODUCTION ACCEPTANCE

**Status: GREEN ON RECONCILE BRANCH — FULL BUILD PASS — PRODUCTION NOT YET ROLLED OUT — 2026-09-11**

Repository under acceptance:

```text
branch: reconcile/cognitive-runtime
base:   9d5471b60b04c2f15ac4281a2ea688a347d35309
```

This document records pre-production evidence only. It does not claim production rollout, production savings, provider billing reduction, or legacy retirement.

## Baseline comparison

The verified pre-migration architecture in `07_COGNITIVE_RUNTIME_P0_BASELINE.md` had these architecture-derived characteristics:

- ordinary natural-language reasoning: minimum two frontier calls — semantic normalization followed by main reasoning;
- FAST direct answer: minimum one semantic call;
- provider failover could repeat separately in semantic and reasoning phases;
- three-provider multi-model could reach eight frontier calls before any separate synthesis path;
- conversation/context could be repeated across semantic and main reasoning.

The reconciled Cognitive Runtime now enforces the following testable behavior:

- one canonical logical request is created at channel ingress;
- institutional context is resolved before provider cognition and inserted into provider context with a stable fingerprint;
- a normal self-contained FAST/ANALYZE/DEEP answer may complete in the first frontier call;
- a deterministic `/help` control completes with zero configured frontier providers;
- semantic provider failover is bounded and reason-coded `PROVIDER_FAILURE`;
- second-or-later cognition is admitted only through the hard provider-call budget with an approved reason code;
- explicit multi-model work is disabled by default and receives a bounded exceptional budget only when collaboration was explicitly requested;
- deliberation normalization and targeted challenge calls are reason-coded and bounded;
- valid Metatron-owned cognitive artifacts can satisfy equivalent cognition with zero additional provider calls;
- transport-only channel evidence is excluded from the reusable cognition fingerprint, while substantive evidence remains part of it;
- provider sessions do not own Case or cognitive artifact state.

## Machine-verifiable acceptance evidence

`CognitiveRuntimeP12AcceptanceTest` verifies:

1. institutional context is present in the provider request before the first cognition call;
2. an ordinary ANALYZE answer completes with exactly one provider call;
3. the canonical logical request reference is retained in provider call telemetry;
4. provider failover consumes one initial plus one fallback call and the fallback trace carries `PROVIDER_FAILURE`;
5. Telegram/Web transport decoration does not change an otherwise identical cognitive fingerprint;
6. deterministic `/help` completes with no frontier provider configured.

`ProviderCallBudgetRegistryTest` verifies hard admission control occurs before provider transport and that denied calls do not reach a provider.

`CognitiveRuntimeFabricTest` verifies cognitive artifact reuse, reason-coded SINGLE failover, and reason-coded explicit collaboration.

`CognitiveRuntimeContractsTest` verifies canonical request identity, stable institutional-context fingerprints, cognition-gate ordering, default one-initial-call depth budgets, and cognitive artifact validity.

## Build receipts

After the final integration tranche:

```text
./gradlew test
BUILD SUCCESSFUL

./gradlew build
BUILD SUCCESSFUL in 31s
8 actionable tasks: 8 executed
```

Only deprecation/compiler warnings were emitted; no build/test failure was present.

## Acceptance interpretation

The pre-production acceptance establishes architectural call-count reduction opportunities without inventing financial percentages. For the representative one-call acceptance fixture, the old ordinary reasoning architecture required a semantic call plus a reasoning call, while the new fixture terminates after the first cognitive/semantic call. For valid artifact reuse, the equivalent follow-up requires zero new frontier calls. These are bounded fixture results, not production averages.

## Remaining gates

```text
P0-P12  ✅ pre-production implementation / acceptance / full build
P13     ⏳ controlled production rollout + live telemetry
P14     ⏳ legacy retirement + final documentation closure
```

P13 is a separate deployment decision and must use the reconciled current-main candidate rather than the superseded stale branch. P14 must not start until production evidence proves the new runtime is healthy and institutionally correct.
