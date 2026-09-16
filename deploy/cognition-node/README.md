# Metatron Cognition Node

Lightweight, dependency-free (Node.js built-in `fetch`/`http` only) Metatron-owned cognition
front door. Bridges `METATRON_COGNITION_URL` (consumed by `HttpMetatronCognitionClient.java`) to a
self-hosted Ollama model first, falling back to configured paid frontier providers only if Ollama is
unavailable or fails.

## Provider order (2026-09-16)

1. **Ollama** (`OLLAMA_URL`, default `http://metatron-ollama:11434`; `OLLAMA_MODEL`, default
   `llama3.2:1b` -- production currently runs `qwen3:8b`) -- the actual "METATRON_OWNED" compute this
   node exists to provide.
2. **Gemini** (`GEMINI_API_KEY`, `GEMINI_MODEL`) -- fallback only.
3. **OpenAI** (`OPENAI_API_KEY`, `OPENAI_MODEL`) -- fallback only.
4. **Anthropic** (`ANTHROPIC_API_KEY`, `ANTHROPIC_MODEL`) -- fallback only.

Every response includes `providerUsed`, `fallbackOccurred`, `latencyMs`, and (on total failure)
`providerAttempts` -- the per-provider failure reasons in order tried.

## Known operational risk (2026-09-16)

OpenAI and Anthropic are currently out of credit. If Ollama and Gemini are both unavailable at the
same time, no provider currently succeeds. This is a billing/credential state, not a code defect --
tracked separately, not fixed by this change.

## Resource constraint

Host: 4 vCPU / 8 GB RAM / 160 GB disk. Only `qwen3:8b` is approved for this host -- do not switch to
`qwen3:14b`/`30b`, Mixtral, or DeepSeek-large without re-verifying memory headroom (`qwen3:8b` alone
pushed the host into swap during a single-request smoke test; see incident notes in chat history
2026-09-16).

## Deploying a change

This is deployed as a standalone Docker container (`metatron-cognition-node`), independent of the
main Workforce app's Highway/Highway-deploy pipeline. There is currently no automated deploy path for
this directory -- changes must be built and the container manually recreated:

```bash
docker build -f deploy/cognition-node/Dockerfile -t metatron-cognition-node:<tag> deploy/cognition-node
docker stop metatron-cognition-node && docker rm metatron-cognition-node
docker run -d \
  --name metatron-cognition-node \
  --network metatron-gateway-online \
  --restart unless-stopped \
  --env-file /opt/metatron/metatron-workforce/deploy/.env \
  -e METATRON_COGNITION_AUTH=<value matching Workforce's METATRON_COGNITION_AUTH> \
  -e OLLAMA_MODEL=qwen3:8b \
  metatron-cognition-node:<tag>
```

## Verifying a change

```bash
./deploy/cognition-node/smoke-test.sh "$METATRON_COGNITION_AUTH"
```

Requires `metatron-cognition-node` and `metatron-ollama` already running on the
`metatron-gateway-online` Docker network. Briefly stops and restarts `metatron-ollama` as part of the
fallback test.
