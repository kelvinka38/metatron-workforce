# METATRON COGNITIVE RUNTIME — P14 LEGACY RETIREMENT / CLOSURE

**Status: IMPLEMENTATION + FULL BUILD COMPLETE — FINAL DEPLOY VERIFICATION PENDING — 2026-09-11**

## Purpose

P14 retires superseded Cognitive Runtime assumptions from production semantics without deleting useful compatibility/test surfaces blindly.

The retirement rule is semantic and ownership-based:

```text
PRODUCTION PATH != LEGACY COMPATIBILITY PATH
```

A compatibility constructor, historical document, or test helper may remain in the repository only when it is clearly non-governing and cannot create a second production-owned provider-control architecture.

## Retired production assumptions

The following assumptions are no longer valid production architecture:

1. every natural-language request must pay a standalone frontier semantic call before any other useful work;
2. a NEW Case requires replaying the Human message through a second semantic frontier call;
3. FAST / ANALYZE / DEEP imply increasing provider count;
4. multi-model collaboration is enabled implicitly by available provider count;
5. a second provider call may occur without a machine-recordable escalation reason;
6. Human interaction, execution planning, or Worker cognition may each own independent production provider routers/policies;
7. provider conversation/session state owns institutional memory;
8. Telegram/Web/API transport identity changes otherwise equivalent cognitive state.

## Current production ownership

The accepted architecture is:

```text
CHANNEL / WORKER / EXECUTION PLANNING
        ↓
InstitutionalIntelligenceRuntime
        ↓
IntelligenceFabric
        ↓
provider-neutral router / budgets / telemetry
        ↓
OpenAI / Google / Anthropic / future providers
```

`InstitutionalIntelligenceRuntime` is the production provider-control owner. Channel ingress does not own a provider router. Execution planning consumes the shared runtime. Worker cognition consumes the shared Intelligence Fabric.

`CognitiveRuntimeP14RetirementTest` machine-checks these ownership conditions for Channel ingress and execution planning.

## Compatibility surfaces retained intentionally

The following remain because they still support tests/migration or current first-pass cognition, but they are not separate production architectures:

- `FrontierSemanticInterpreter` remains the first Cognitive Runtime frontier pass where cognition is needed. It is no longer a mandatory toll before deterministic/retrieval zero-call paths, and its first pass may produce the terminal answer.
- raw-provider-key constructors on `MetatronIntelligenceResponder` remain compatibility/test composition. Production composition uses `InstitutionalIntelligenceRuntime`.
- overloads that omit explicit request correlation remain compatibility surfaces; production Channel ingress establishes `CognitiveRequestScope`/`LlmCallContext` before provider calls.
- `01_FINAL_ARCHITECTURE_PROPOSAL.md` and `02_DETAILED_ARCHITECTURE.md` remain historical/compatibility references. Where they conflict with the Founder-approved Cognitive Runtime baseline, 04/05/06 and later acceptance evidence govern.

Package-level documentation in `interaction.intelligence/package-info.java` records these constraints next to the code.

## Production evidence carried forward from P13

P13 accepted immutable production candidate:

```text
6c7cd1c8739e920fcb2fa0f0af7c8307b68d8847
```

At P14 start, live evidence showed:

- Workforce healthy;
- sandbox healthy;
- Gateway healthy;
- Telegram webhook configured with zero pending updates;
- Cognitive Runtime emitted live `cognitive_artifact_saved` events;
- provider credit/quota exhaustion surfaced as explicit provider failure, not false completion.

See `09_COGNITIVE_RUNTIME_P13_PRODUCTION_ACCEPTANCE.md`.

## P14 build receipt

After adding the retirement ownership test, package compatibility boundary, and final documentation closure:

```text
./gradlew --no-daemon clean build
BUILD SUCCESSFUL in 34s
8 actionable tasks: 8 executed
```

Only existing deprecation/compiler warnings were emitted.

## Final acceptance gate

P14 is complete only after:

1. changes are committed to one immutable SHA on top of the P13 candidate;
2. that exact SHA is deployed with the canonical deployment script;
3. Workforce and sandbox remain healthy;
4. Gateway and Telegram remain healthy/configured;
5. no new startup/state-schema/provider-control regression appears in production logs.

Until those steps pass, this document remains `IMPLEMENTATION + FULL BUILD COMPLETE — FINAL DEPLOY VERIFICATION PENDING`.
