# Telegram Gateway Recovery — 2026-08-26

## Diagnosis

Production reached a healthy application state, but Telegram reported `Wrong response from the webhook: 400 Bad Request` with pending updates. The canonical `TelegramWebhookController` contains a local `@ExceptionHandler(IllegalArgumentException.class)` that returns HTTP 400. That handler overrides the newer transport-level acknowledgement policy, so malformed/non-actionable Telegram updates are still acknowledged as 400 and Telegram retries them.

The outbound Bot API is independently reachable: `getMe` and `sendMessage` returned `ok=true`.

The intelligence path also contains a read-only web-search adapter and the intelligence fabric enriches current/external questions with web evidence when deployed with an LLM API key.

## Required recovery state

1. Controller invalid-update handler must return HTTP 200.
2. Production must deploy the resulting `main` commit.
3. Verify `/telegram/health` returns 200.
4. Verify Telegram `getWebhookInfo.pending_update_count` decreases after real messages.
5. Send `/start` and a current-information question from the configured Telegram user.
6. Verify application logs contain `telegram_send_success` and, for current-information questions, `web_research_complete`.

## Safety boundary

Do not print Telegram bot tokens or LLM API keys. Only print presence/length metadata during deployment diagnostics.
