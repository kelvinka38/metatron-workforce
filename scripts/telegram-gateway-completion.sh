#!/usr/bin/env bash
set -euo pipefail

cd /opt/metatron/metatron-workforce

printf '\n========================================\n'
printf ' METATRON — TELEGRAM GATEWAY COMPLETION\n'
printf '========================================\n'

printf '\n=== 1. SOURCE ===\n'
git fetch origin
git pull --ff-only origin main
printf 'HEAD='; git rev-parse HEAD

printf '\n=== 2. ENV PRESENCE (NO VALUES) ===\n'
set -a
source .env
set +a
for key in TELEGRAM_BOT_TOKEN TELEGRAM_WEBHOOK_SECRET TELEGRAM_ALLOWED_USER_ID METATRON_ORGANIZATION_ID OPENAI_API_KEY GEMINI_API_KEY ANTHROPIC_API_KEY; do
  value="${!key:-}"
  printf '%s length=%s\n' "$key" "${#value}"
done

printf '\n=== 3. BUILD + TEST ===\n'
./gradlew clean test bootJar

printf '\n=== 4. IMAGE ===\n'
docker build -t metatron-workforce:telegram-completion . >/tmp/metatron-telegram-docker-build.log
tail -8 /tmp/metatron-telegram-docker-build.log

printf '\n=== 5. RECREATE ===\n'
docker compose -f deploy/docker-compose.yml down
docker compose -f deploy/docker-compose.yml up -d --force-recreate

printf '\n=== 6. WAIT HEALTH ===\n'
for i in $(seq 1 60); do
  if curl -fsS --max-time 2 http://127.0.0.1:8080/actuator/health >/tmp/metatron-health.json 2>/dev/null; then
    if grep -q '"status":"UP"' /tmp/metatron-health.json; then break; fi
  fi
  sleep 1
done

printf '\n=== 7. LOCAL HEALTH ===\n'
curl -fsS http://127.0.0.1:8080/actuator/health
printf '\n'
curl -fsS http://127.0.0.1:8080/telegram/health
printf '\n'

printf '\n=== 8. CONTAINER ===\n'
docker ps --filter name=deploy-workforce-1 --format 'NAME={{.Names}} STATUS={{.Status}} IMAGE={{.Image}}'

printf '\n=== 9. TELEGRAM WEBHOOK INFO ===\n'
curl -fsS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getWebhookInfo" \
  | python3 -c 'import json,sys; x=json.load(sys.stdin); r=x.get("result",{}); print(json.dumps({"ok":x.get("ok"),"url":r.get("url"),"pending_update_count":r.get("pending_update_count"),"last_error_message":r.get("last_error_message")},indent=2))'

printf '\n=== 10. NON-MESSAGE ACK TEST ===\n'
UPDATE_ID="$(date +%s)000"
printf '{"update_id":%s,"my_chat_member":{"chat":{"id":%s,"type":"private"}}}' "$UPDATE_ID" "$TELEGRAM_ALLOWED_USER_ID" >/tmp/telegram-non-message.json
NON_MESSAGE_HTTP="$(curl -sS -o /tmp/telegram-non-message.out -w '%{http_code}' \
  -X POST \
  -H "X-Telegram-Bot-Api-Secret-Token: ${TELEGRAM_WEBHOOK_SECRET}" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/telegram-non-message.json \
  http://127.0.0.1:8080/telegram/webhook)"
printf 'HTTP=%s\n' "$NON_MESSAGE_HTTP"
test "$NON_MESSAGE_HTTP" = "200"

printf '\n=== 11. LOCAL /start E2E ===\n'
UPDATE_ID="$(date +%s)001"
cat >/tmp/telegram-start.json <<EOF
{
  "update_id": ${UPDATE_ID},
  "message": {
    "message_id": ${UPDATE_ID},
    "date": ${UPDATE_ID},
    "from": {"id": ${TELEGRAM_ALLOWED_USER_ID}, "is_bot": false, "first_name": "E2E"},
    "chat": {"id": ${TELEGRAM_ALLOWED_USER_ID}, "type": "private"},
    "text": "/start"
  }
}
EOF
START_HTTP="$(curl -sS -o /tmp/telegram-start.out -w '%{http_code}' \
  -X POST \
  -H "X-Telegram-Bot-Api-Secret-Token: ${TELEGRAM_WEBHOOK_SECRET}" \
  -H 'Content-Type: application/json' \
  --data-binary @/tmp/telegram-start.json \
  http://127.0.0.1:8080/telegram/webhook)"
printf 'HTTP=%s\n' "$START_HTTP"
test "$START_HTTP" = "200"

printf '\n=== 12. WEB REACHABILITY ===\n'
if curl -fsS --max-time 8 'https://www.bing.com/search?format=rss&q=Metatron' >/tmp/metatron-web-search.xml; then
  printf 'WEB_SEARCH=PASS\n'
else
  printf 'WEB_SEARCH=FAIL (server cannot reach search endpoint)\n'
fi

printf '\n=== 13. APPLICATION TRACE ===\n'
docker logs --since 2m deploy-workforce-1 2>&1 \
  | grep -E 'telegram_|web_research|intelligence_provider_failed|Exception' \
  | tail -120 || true

printf '\n========================================\n'
printf ' TELEGRAM GATEWAY COMPLETION CHECK DONE\n'
printf '========================================\n'
