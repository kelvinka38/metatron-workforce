# METATRON COGNITIVE RUNTIME — P12 PRE-PRODUCTION ACCEPTANCE

**Status: GREEN — SUPERSEDED FOR PRODUCTION STATE BY P13/P14 RECEIPTS — 2026-09-11**

Repository accepted at P12 was the reconciled Cognitive Runtime on current-main. This document records pre-production evidence only; production evidence is recorded separately in `09_COGNITIVE_RUNTIME_P13_PRODUCTION_ACCEPTANCE.md` and legacy-retirement closure in `10_COGNITIVE_RUNTIME_P14_LEGACY_RETIREMENT.md`.

## Baseline comparison

The verified pre-migration architecture in `07_COGNITIVE_RUNTIME_P0_BASELINE.md` had these architecture-derived characteristics:

- ordinary natural-language reasoning: minimum two frontier calls — semantic normalization followed by main reasoning;
- FAST direct answer: minimum one semantic call;
- provider failover could repeat separately in semantic and reasoning phases;
- three-provider multi-model could reach eight frontier calls before any separate synthesis path;
- conversation/context could be repeated across semantic and main reasoning.

The reconciled Cognitive Runtime enforces the following testable behavior:

- one canonical logical request is created at channel ingress;
- institutional context is resolved before provider cognition and inserted into provider context with a stable fingerprint;
- a normal self-contained FAST/ANALYZE/DEEP answer may complete in the first frontier call;
- deterministic controls such as `/help` complete with zero configured frontier providers;
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

## Build receipt

The final P12 integration build completed successfully:

```text
./gradlew --no-daemon clean build
BUILD SUCCESSFUL
8 actionable tasks: 8 executed
```

Only deprecation/compiler warnings were emitted; no build/test failure was present.

## Acceptance interpretation

P12 established architecture and fixture-level call-count reduction opportunities without inventing financial percentages. For the representative one-call acceptance fixture, the old ordinary reasoning architecture required a semantic call plus a reasoning call, while the new fixture terminates after the first cognitive/semantic call. For valid artifact reuse, the equivalent follow-up requires zero new frontier calls. These are bounded fixture results, not production averages.

## Later gates

```text
P0-P12  ✅ pre-production implementation / acceptance / full build
P13     ✅ production acceptance — see 09_COGNITIVE_RUNTIME_P13_PRODUCTION_ACCEPTANCE.md
P14     ⏳ implemented; final build/deploy verification tracked in 10_COGNITIVE_RUNTIME_P14_LEGACY_RETIREMENT.md
```
