# TELEGRAM_PRODUCTION_CLOSURE.md

## Purpose

Canonical closure checklist for the Human → Telegram → Intelligence → Metatron interaction path.

## Required runtime configuration

The Telegram webhook must fail closed unless all of the following are present:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_WEBHOOK_SECRET`
- `TELEGRAM_ALLOWED_USER_ID`
- `METATRON_ORGANIZATION_ID`
- at least one LLM credential: `OPENAI_API_KEY`, `GEMINI_API_KEY`, or `ANTHROPIC_API_KEY`

Optional provider selection/model variables remain:

- `METATRON_LLM_PROVIDER`
- `OPENAI_MODEL`
- `GEMINI_MODEL`
- `ANTHROPIC_MODEL`

## Canonical path

```text
Telegram update
  → webhook secret validation
  → Telegram identity binding
  → canonical MetatronInteraction
  → MetatronInteractionOrchestrator
  → IntelligenceFabric
  → configured LLM provider
  → response provenance
  → TelegramBotGateway
```

## Security closure

The webhook must use the Telegram `from.id` for identity authorization. The Telegram chat ID is a conversation/transport address and must not be treated as the authenticated human identity.

Unknown Telegram users must be denied rather than auto-provisioned.

## E2E acceptance

1. Authorized Telegram user sends a normal question.
2. Webhook accepts the secret and resolves the configured human identity.
3. The interaction reaches IntelligenceFabric.
4. A configured LLM returns a non-empty, non-echo response.
5. Telegram receives the response.
6. An unauthorized Telegram user receives HTTP 401 and no LLM invocation.
7. Missing required identity/org configuration prevents application startup.
8. Evidence/provenance references retain the inbound Telegram message reference.

## Execution closure

Natural-language execution requests are **not** considered complete merely because they are classified as `EXECUTION`. A production PASS requires a real authorized capability adapter behind the Metatron execution boundary and evidence that Gateway authorization remains authoritative.

Until that adapter and evidence exist, execution requests must fail closed and must never be represented to the user as completed.
