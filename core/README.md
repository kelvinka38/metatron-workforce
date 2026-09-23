# Metatron Core

One small Python service: a Telegram message becomes a task, one agent works on it in a loop
(read → edit → run tests → fix) inside its own workspace, opens a GitHub PR and replies on Telegram.
Merging needs the founder's `/approve <id>`. LLM spend is $0 by construction: Gemini free tier,
OpenRouter `:free` models, local Ollama.

## Run the tests

```sh
cd core
python3 -m unittest discover -s tests -t . -v          # as a normal user
sudo python3 -m unittest discover -s tests -t . -v     # also runs the agent-user isolation tests
```

CI (`.github/workflows/core-ci.yml`) runs both on every PR that touches `core/`.

## Environment

| Variable | Needed | Meaning |
| --- | --- | --- |
| `GEMINI_API_KEY` | yes | Key from an AI Studio project with **no billing account** |
| `CORE_GEMINI_MODEL` | no | Default `gemini-2.5-flash` |
| `OPENROUTER_FREE_API_KEY`, `OPENROUTER_FREE_MODEL` | no | Model must end in `:free`, or Core refuses to start |
| `OLLAMA_URL`, `CORE_OLLAMA_MODEL` | no | Default `http://metatron-ollama:11434`, `qwen2.5-coder:7b` |
| `CORE_TELEGRAM_BOT_TOKEN`, `CORE_TELEGRAM_WEBHOOK_SECRET` | yes | Test bot until cutover |
| `TELEGRAM_ALLOWED_USER_ID` | yes | The only Telegram user Core obeys |
| `CORE_API_TOKEN` | yes | Bearer token for the local API |
| `GITHUB_TOKEN` | yes | Clone, push, open and merge PRs. Never visible to the agent |
| `CORE_AGENT_UID_BASE` | set in image | Task N runs as Unix user base + N (container only) |

`ANTHROPIC_API_KEY` and `OPENAI_API_KEY` are ignored unless `METATRON_ALLOW_PREPAID_CREDIT=true`.
Leave that unset.

## Deploy (on the host)

First time (builds the image, pulls the Ollama model, checks toolchains, runs the tests inside the
image and makes one real call to each free provider):

```sh
curl -fsSL https://raw.githubusercontent.com/kelvinka38/metatron-workforce/metatron/objective-50334c388a41-aea68111/core/scripts/first-deploy.sh | sudo bash
```

By hand:

```sh
cd /opt/metatron/metatron-workforce/core
docker compose --env-file ../deploy/.env --env-file /opt/metatron/metatron-core.env up -d --build
curl -s 127.0.0.1:8095/health     # lists gemini and ollama, never anthropic or openai
```

`/opt/metatron/metatron-core.env` (mode 600) holds the `CORE_*` values. Update: `git pull`, then
the same `docker compose` line. Roll back: `git checkout <previous sha>`, same line. Data lives
in volume `metatron-core-data`. The Telegram webhook goes to `/telegram` (or `/core/telegram`)
with the secret token.

## Use

Send the bot a task in plain words, e.g. "In kelvinka38/bios fix the failing test".
`/status` lists recent tasks, `/approve <id>` merges a task's PR, `/reject <id>` leaves it open.
Local API: `POST /tasks {"request": "..."}` and `GET /tasks/<id>`, both with
`Authorization: Bearer $CORE_API_TOKEN`.
