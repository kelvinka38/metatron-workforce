#!/usr/bin/env bash
set -euo pipefail

: "${TARGET_SHA:?TARGET_SHA required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID required}"
BASE=/opt/metatron/metatron-workforce
OUT="/tmp/metatron-production-highway-${GITHUB_RUN_ID}"
mkdir -p "$OUT"
test -r "$BASE/.env"
set -a; source "$BASE/.env"; set +a
test -n "${TELEGRAM_WEBHOOK_SECRET:-}"
test -n "${TELEGRAM_ALLOWED_USER_ID:-}"

CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$CID"
DEPLOYED_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$DEPLOYED_SHA" = "$TARGET_SHA"
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy

telegram_body() {
  local update="$1" text="$2"
  python3 - "$update" "$text" "$TELEGRAM_ALLOWED_USER_ID" <<'PY'
import json,sys
u=int(sys.argv[1]); text=sys.argv[2]; uid=int(sys.argv[3])
print(json.dumps({"update_id":u,"message":{"message_id":u%2000000000,"from":{"id":uid,"is_bot":False,"first_name":"Founder"},"chat":{"id":uid,"type":"private"},"date":0,"text":text}},ensure_ascii=False))
PY
}

send_update() {
  local update="$1" text="$2" transport="${3:-public}" body status attempt output attempts_log url curl_transport
  body=$(telegram_body "$update" "$text")
  output="$OUT/intelligence-${update}-ingress.json"
  attempts_log="$OUT/intelligence-${update}-ingress-attempts.log"
  : > "$attempts_log"
  if [ "$transport" = public ]; then
    url='https://gate.metatron.vn/telegram/webhook'
    curl_transport=(--proto '=https' --tlsv1.2 --connect-timeout 5)
  else
    url='http://127.0.0.1:8080/telegram/webhook'
    curl_transport=(--connect-timeout 3)
  fi
  for attempt in 1 2 3 4 5; do
    status=$(curl -sS -o "$output" -w '%{http_code}' \
      "${curl_transport[@]}" --max-time 25 --retry 1 --retry-delay 1 --retry-connrefused \
      -X POST "$url" \
      -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
      -H 'Content-Type: application/json' --data-binary "$body" 2>>"$attempts_log") || status=000
    printf 'transport=%s attempt=%s status=%s\n' "$transport" "$attempt" "$status" >> "$attempts_log"
    if [ "$status" = 200 ]; then return 0; fi
    sleep "$attempt"
  done
  echo "INTELLIGENCE_INGRESS_FAILED update_id=$update transport=$transport" >&2
  cat "$attempts_log" >&2
  return 1
}

wait_answer() {
  local since="$1" update="$2" log="$OUT/intelligence-${update}-runtime.log"
  for i in $(seq 1 120); do
    docker logs --since "$since" "$CID" > "$log" 2>&1 || true
    if grep -q "telegram_webhook_ack update_id=$update" "$log" \
      && grep -q "telegram_send_success update_id=$update" "$log" \
      && grep -q "telegram_answer_ready update_id=$update" "$log" \
      && grep -Eq 'metatron_intelligence_latency channel=telegram route=intelligence-[^ ]*-ir[1-9][0-9]*of[1-9][0-9]*' "$log"; then
      return 0
    fi
    sleep 2
  done
  echo "INTELLIGENCE_RUNTIME_TIMEOUT update_id=$update" >&2
  return 1
}

case_file_for_update() {
  local update="$1" pattern="$2" case_file
  while IFS= read -r case_file; do
    [ -n "$case_file" ] || continue
    if docker exec "$CID" sh -c "grep -Eqi '$pattern' '$case_file' && grep -q 'SATISFIED' '$case_file' && grep -Eq 'https?://' '$case_file'"; then
      echo "$case_file"
      return 0
    fi
  done < <(docker exec "$CID" sh -c "grep -R -l 'telegram:update:$update' /var/lib/metatron-workforce 2>/dev/null || true")
  return 1
}

fresh_case() {
  local update="$1" text="$2" pattern="$3" result_prefix="$4" transport="${5:-public}" since case_file log
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  send_update "$update" "$text" "$transport"
  wait_answer "$since" "$update"
  log="$OUT/intelligence-${update}-runtime.log"
  ! grep -q "telegram_interaction_failed update_id=$update" "$log"
  ! grep -q "telegram_failure_notification_sent update_id=$update" "$log"
  ! grep -Eq "execution-objective-workforce-accepted.*$update|METATRON WORK ACCEPTED.*$update|telegram_answer_ready update_id=$update.*objective_id=[^[:space:]]+" "$log"
  case_file=$(case_file_for_update "$update" "$pattern")
  test -n "$case_file"
  printf '%s\n' "$case_file" > "${result_prefix}.case"
  docker exec "$CID" cat "$case_file" > "${result_prefix}.json"
  test -s "${result_prefix}.json"
  echo "FRESH_CASE_PASS update_id=$update transport=$transport case_file=$case_file"
}

BASE_ID=$(date +%s%N | cut -c1-13)
GOLD_1="${BASE_ID}11"
FX="${BASE_ID}12"
WEATHER="${BASE_ID}13"
GOLD_2="${BASE_ID}14"

# Three independent current-information cases prove the full public Gateway -> live production path.
fresh_case "$GOLD_1" 'Giá vàng hôm nay tại Việt Nam. Hãy dùng dữ liệu hiện tại và nêu nguồn.' 'vàng|gold' "$OUT/gold-1" public
fresh_case "$FX" 'Tỷ giá USD/VND hiện tại khoảng bao nhiêu? Dùng dữ liệu mới và cho nguồn.' 'USD|VND|tỷ giá|exchange' "$OUT/fx" public
fresh_case "$WEATHER" 'Thời tiết hiện tại ở Thành phố Hồ Chí Minh thế nào? Kiểm tra dữ liệu mới và nêu nguồn.' 'thời tiết|weather|Ho Chi Minh|Hồ Chí Minh' "$OUT/weather" public

# Reacquisition is an Intelligence semantic invariant, not a second Gateway availability test.
# Exercise the same exact live production container directly so an unrelated edge/tunnel transient
# cannot masquerade as stale-evidence reuse. Public Gateway is already proven by the three cases above.
fresh_case "$GOLD_2" 'Kiểm tra lại giá vàng Việt Nam ngay lúc này. Hãy lấy dữ liệu hiện tại mới và nêu nguồn.' 'vàng|gold' "$OUT/gold-2" local
GOLD_CASE_1=$(cat "$OUT/gold-1.case")
GOLD_CASE_2=$(cat "$OUT/gold-2.case")
test "$GOLD_CASE_1" != "$GOLD_CASE_2"
! cmp -s "$OUT/gold-1.json" "$OUT/gold-2.json"

curl -fsS --proto '=https' --tlsv1.2 --max-time 10 https://gate.metatron.vn/telegram/health | grep -q '"status":"UP"'
echo 'INTELLIGENCE_PUBLIC_GATEWAY_CASES=3'
echo 'INTELLIGENCE_GOLD_FRESH=PASS'
echo 'INTELLIGENCE_FX_FRESH=PASS'
echo 'INTELLIGENCE_WEATHER_FRESH=PASS'
echo 'INTELLIGENCE_FRESH_INFORMATION_NOT_OBJECTIVE=PASS'
echo 'INTELLIGENCE_SAME_TOPIC_REACQUISITION=PASS'
echo 'INTELLIGENCE_GROUNDED_EVIDENCE=PASS'
echo 'POINT_2_INTELLIGENCE_LIVE_LANE=PASS'
