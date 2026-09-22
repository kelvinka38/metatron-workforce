# Metatron Cognition Node

Dependency-free Node.js front door for Metatron-owned cognition. AUTO failover defaults to:

1. Gemini
2. OpenAI
3. Anthropic
4. Ollama / `qwen3:8b` (local no-paid-credit fallback)

The order is configurable with `COGNITION_PROVIDER_ORDER`; explicit deployments may override it without changing code.

## Reliability envelope

The node treats one cognition request as a single bounded transaction:

- `METATRON_COGNITION_TOTAL_TIMEOUT_MS=180000`
- `OLLAMA_TIMEOUT_MS=105000`
- `FRONTIER_PROVIDER_TIMEOUT_MS=18000`
- `PROVIDER_COOLDOWN_MS=60000`
- `PROVIDER_LONG_COOLDOWN_MS=900000`
- `COGNITION_PROVIDER_ORDER=gemini,openai,anthropic,ollama`
- `COGNITION_MAX_OUTPUT_TOKENS=256`
- every provider attempt uses `min(provider timeout, remaining whole-request budget)`
- every HTTP call is AbortController-bounded
- completed provider chain failure returns `502 all_providers_failed`
- exhausted whole-request budget returns `504 cognition_deadline_exhausted`

For `worker.cognition`, qwen thinking defaults to `false` when `OLLAMA_THINK` is unset. A production-shaped
256-token probe with native thinking consumed the entire generation budget as internal thinking and returned an empty
action response. Other capabilities retain the model's native behavior unless `OLLAMA_THINK` is explicitly set.

Provider failure metadata is bounded to provider name, duration, and failure class; raw provider bodies
and credentials are not returned.

## Immutable identity

Build with the exact source SHA:

```bash
SHA="$(git rev-parse HEAD)"
docker build \
  --build-arg METATRON_COGNITION_REVISION="$SHA" \
  -t "metatron-cognition-node:$SHA" \
  -f deploy/cognition-node/Dockerfile deploy/cognition-node
```

The image carries `org.opencontainers.image.revision`, and `/healthz` reports the running revision,
timeouts, output cap, model, and provider order.

## Tests

```bash
node --test deploy/cognition-node/server.test.js
./gradlew test --no-daemon
```

The Node suite covers configurable provider order, provider cooldown/skip behavior, local Ollama fallback,
output caps, whole-request deadline clamping, deterministic 502/504 behavior, and explicit-vs-default Ollama thinking.

## Safe smoke test

```bash
./deploy/cognition-node/smoke-test.sh "$METATRON_COGNITION_AUTH"
```

The smoke test never stops or restarts the live Ollama container. It checks immutable health identity
and one normal cognition request. Provider-failure/cooldown behavior is covered by the Node test suite.
A destructive real fallback smoke must use a separate canary Cognition Node by overriding `NODE_URL`.

## Deployment ordering

Deploy Workforce first with `METATRON_COGNITION_HTTP_TIMEOUT_MS=240000`, then deploy the Cognition
Node. This keeps the Java caller alive longer than the Node's 180-second total deadline.

For the current 4-vCPU / 8-GB host, start with one concurrent cognition request. Queue sizing and wait
time must be based on the measured production-shaped qwen benchmark rather than increased blindly.
