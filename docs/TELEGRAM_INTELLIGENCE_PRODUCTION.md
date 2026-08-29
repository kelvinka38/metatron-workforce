# Telegram Intelligence Production State

Production behavior established on 2026-08-29:

- durable per-conversation Telegram memory on the persistent Workforce state volume;
- conversation history supplied to intelligence for follow-up/reference resolution;
- conversational continuation can recover the preceding user objective for external research;
- AUTO provider routing prefers Google, then Anthropic, then OpenAI, while explicit provider requests remain authoritative;
- provider failure falls through to the next configured provider;
- canonical immutable-SHA production deployment and verification scripts are present.

This document records deployed behavior; runtime health and exact running identity must still be verified through the production verification gate.
