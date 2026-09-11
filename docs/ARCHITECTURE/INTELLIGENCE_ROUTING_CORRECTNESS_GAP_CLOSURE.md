# Metatron Intelligence Routing Correctness Gap Closure

Status: IMPLEMENTED — acceptance pending canonical merge/deploy
Scope: Workforce Intelligence Fabric provider/model routing

## Closure objective

A canonical Worker must not become a provider persona. Worker identity, memory, objective, authority,
assignment and evidence remain Metatron-owned while GPT/OpenAI, Gemini/Google and Claude/Anthropic are
replaceable cognitive capacity. AUTO routing must select capacity using measured/configured evidence,
not brand assumptions or Worker-specific hard-coding.

## Pre-closure audit

The repository already had:

- provider-neutral `IntelligenceFabric`;
- `LlmProviderRouter` with OpenAI, Google and Anthropic clients;
- provider call budgets and bounded provider-failure fallback;
- live health / failure / concurrency / latency / token / optional cost telemetry;
- provider/model call tracing;
- adaptive health routing;
- a configured model per provider.

The real gaps were narrower:

1. Cost telemetry existed but AUTO provider routing did not use it.
2. Required capability had no evidence-backed provider quality-fit input.
3. Provider model selection was static for every request.
4. There was no single acceptance proving capability fit + cost + latency + model tier + bounded fallback
   while preserving one Worker requester identity.

## Canonical routing contract

### Provider selection

Explicit provider request:

- preserve the Human/system request exactly;
- never silently substitute another provider;
- fail closed if an explicitly requested provider is not configured.

AUTO provider request:

1. reject/deprioritize exhausted or cooling-down capacity;
2. prefer fewer consecutive provider failures;
3. apply evidence-backed capability quality fit;
4. for latency-sensitive requests, prefer measured lower latency;
5. for cost-sensitive requests, prefer measured lower cost when cost is known;
6. prefer lower active concurrency;
7. use measured latency and stable provider priority only as final tie-breakers.

No provider gets a quality advantage from its brand name. Unknown quality is neutral.
Unknown cost is not fabricated.

### Capability quality evidence

`ProviderCapabilityQualityRegistry` classifies required capability into:

- GENERAL
- SEMANTIC
- ANALYSIS
- PLANNING
- CODING
- CREATIVE

Measured/configured quality is a score in `[0,1]` and requires an evidence reference when recorded at
runtime. Production can seed scores with:

- `METATRON_OPENAI_QUALITY_<CLASS>`
- `METATRON_GOOGLE_QUALITY_<CLASS>`
- `METATRON_ANTHROPIC_QUALITY_<CLASS>`

If no score exists, routing remains neutral rather than inventing one.

### Cost and latency

Latency comes only from live `ProviderTelemetryRegistry` measurements.

Cost comes only from provider token usage plus explicitly configured pricing:

- `METATRON_<PROVIDER>_INPUT_USD_PER_MILLION`
- `METATRON_<PROVIDER>_OUTPUT_USD_PER_MILLION`

If price or usage is unknown, cost remains unknown and is never estimated from provider reputation.

### Model selection

Provider selection and model selection are separate decisions.

`AdaptiveModelRoutingPolicy` maps each request to:

- FAST
- ANALYZE
- DEEP

based on latency/cost depth semantics plus the minimum reasoning tier implied by the required capability.
The policy never invents model names. Per-provider model tiers are optional:

- `OPENAI_FAST_MODEL`, `OPENAI_ANALYZE_MODEL`, `OPENAI_DEEP_MODEL`
- `GEMINI_FAST_MODEL`, `GEMINI_ANALYZE_MODEL`, `GEMINI_DEEP_MODEL`
- `ANTHROPIC_FAST_MODEL`, `ANTHROPIC_ANALYZE_MODEL`, `ANTHROPIC_DEEP_MODEL`

Any missing tier falls back to the provider's existing configured model (`OPENAI_MODEL`, `GEMINI_MODEL`,
`ANTHROPIC_MODEL`) and therefore preserves current production behavior until operators intentionally
configure differentiated tiers.

### Failure and fallback

The ordered route is planned before execution. A provider failure does not mutate Worker identity.
`IntelligenceFabric` advances to the next ordered provider only within the existing bounded
`ProviderBudget` / `ProviderCallBudgetRegistry` contract.

Provider/model/success/failure/reason are observable in `ProviderCallTraceRegistry`. Worker cognition
already emits provider/model evidence from `IntelligenceResult.ProviderResult`, so provider choice is
execution evidence rather than Worker identity.

## Acceptance proof

The closure acceptance suite proves:

- capability quality can override static provider tie-break priority;
- interactive routing prefers measured lower latency when quality/health are equal;
- low-cost routing prefers measured lower cost when quality/health are equal;
- semantic work selects FAST model tier;
- coding/cognitive work selects ANALYZE model tier by minimum capability need;
- explicit deep work selects DEEP model tier;
- first-ranked provider failure falls through to the next provider;
- the same canonical Worker requester appears in both failed and successful provider calls;
- call trace records failed provider/model and successful fallback provider/model with `PROVIDER_FAILURE` reason.

Acceptance classes:

- `IntelligenceRoutingConformanceAcceptanceTest`
- `LatencyAwareProviderRoutingTest`
- `CostAwareProviderRoutingTest`

## Non-goals

This closure does not claim that any provider is universally better for coding, analysis or creative
work. It creates the institutional mechanism for measured evaluation results to affect routing without
binding a Worker to one vendor.

It also does not train a Metatron foundation model. External models remain replaceable cognitive engines
behind Metatron-owned Workers and Intelligence governance.
