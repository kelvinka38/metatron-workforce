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
  local update="$1" text="$2" transport="${3:-public}" body status attempt output attempts_log url
  local -a curl_transport
  body=$(telegram_body "$update" "$text")
  printf '%s\n' "$body" > "$OUT/intelligence-${update}-request.json"
  output="$OUT/intelligence-${update}-ingress.json"
  attempts_log="$OUT/intelligence-${update}-ingress-attempts.log"
  : > "$attempts_log"
  if [ "$transport" = public ]; then
    url='https://gate.metatron.vn/telegram/webhook'; curl_transport=(--proto '=https' --tlsv1.2 --connect-timeout 5)
  else
    url='http://127.0.0.1:8080/telegram/webhook'; curl_transport=(--connect-timeout 3)
  fi
  for attempt in 1 2 3 4 5; do
    status=$(curl -sS -o "$output" -w '%{http_code}' "${curl_transport[@]}" --max-time 25 --retry 1 --retry-delay 1 --retry-connrefused \
      -X POST "$url" -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
      -H 'Content-Type: application/json' --data-binary "$body" 2>>"$attempts_log") || status=000
    printf 'transport=%s attempt=%s status=%s\n' "$transport" "$attempt" "$status" >> "$attempts_log"
    [ "$status" = 200 ] && return 0
    sleep "$attempt"
  done
  echo "INTELLIGENCE_INGRESS_FAILED update_id=$update transport=$transport" >&2
  cat "$attempts_log" >&2
  return 1
}

wait_answer() {
  local since="$1" update="$2"
  local log
  log="$OUT/intelligence-${update}-runtime.log"
  for _ in $(seq 1 120); do
    docker logs --since "$since" "$CID" > "$log" 2>&1 || true
    if grep -q "telegram_webhook_ack update_id=$update" "$log" \
      && grep -q "telegram_send_success update_id=$update" "$log" \
      && grep -q "telegram_answer_ready update_id=$update" "$log"; then return 0; fi
    sleep 2
  done
  echo "INTELLIGENCE_RUNTIME_TIMEOUT update_id=$update" >&2
  return 1
}

persist_candidate_cases() {
  local update="$1" prefix="$2" case_file index=0
  while IFS= read -r case_file; do
    [ -n "$case_file" ] || continue
    index=$((index + 1))
    printf '%s\n' "$case_file" > "${prefix}.candidate-${index}.path"
    docker exec "$CID" cat "$case_file" > "${prefix}.candidate-${index}.json" || true
  done < <(docker exec "$CID" sh -c "grep -R -l 'telegram:update:$update' /var/lib/metatron-workforce/intelligence-cases 2>/dev/null || true")
  echo "INTELLIGENCE_CANDIDATE_CASES update_id=$update count=$index"
}

case_file_for_update() {
  local update="$1" pattern="$2" case_file
  while IFS= read -r case_file; do
    [ -n "$case_file" ] || continue
    if docker exec "$CID" sh -c "grep -Eqi '$pattern' '$case_file' && grep -q 'SATISFIED' '$case_file' && grep -Eq 'https?://' '$case_file'"; then
      echo "$case_file"; return 0
    fi
  done < <(docker exec "$CID" sh -c "grep -R -l 'telegram:update:$update' /var/lib/metatron-workforce/intelligence-cases 2>/dev/null || true")
  return 1
}

validate_case() {
  local file="$1" pattern="$2" require_numeric="$3"
  python3 - "$file" "$pattern" "$require_numeric" <<'PY'
import json,re,sys
case=json.load(open(sys.argv[1],encoding='utf-8')); pattern=sys.argv[2]; numeric=sys.argv[3]=='1'
reqs=case.get('informationRequirements') or []
matching=[r for r in reqs if re.search(pattern,str(r.get('question','')),re.I)]
assert matching,'SUBJECT_REQUIREMENT_MISSING'
assert any(str(r.get('status'))=='SATISFIED' for r in matching),'REQUIREMENT_NOT_SATISFIED'
refs=[str(x) for r in matching for x in (r.get('evidenceReferences') or [])]
assert any(x.startswith(('http://','https://')) for x in refs),'NO_EXTERNAL_REFS'
answer=str(case.get('latestConclusion') or '').strip(); assert answer,'EMPTY_ANSWER'
first_line=answer.splitlines()[0].upper() if answer else ''
assert not any(marker in first_line for marker in ('FAST · METATRON','ANALYZE · METATRON','DEEP · METATRON',
                                                   'FAST • METATRON','ANALYZE • METATRON','DEEP • METATRON')), 'INTERNAL_DEPTH_BANNER_LEAK'
low=answer.lower()
refusals=(
  'i cannot provide','i can\'t provide','i am unable','i\'m unable','insufficient information',
  'not enough information','does not provide','cannot determine','please check','please look up',
  'refer to the official','không thể cung cấp','không đủ thông tin','chưa đủ thông tin','không thể xác định',
  'vui lòng trực tiếp tra cứu','vui lòng tra cứu','hãy tự tra cứu'
)
assert not any(x in low for x in refusals),'REFUSAL_OR_SEARCH_INSTRUCTION'
assert re.search(pattern,answer,re.I),'ANSWER_OFF_SUBJECT'
if numeric:
    measurement=re.search(r'(?:[$₫]\s*)?\d[\d.,]*(?:\s*(?:USD|VND|đồng|₫|dollars?|°\s*C|°C|Celsius|%))',answer,re.I)
    assert measurement,'ANSWER_MISSING_CURRENT_MEASUREMENT'
print('USEFUL_CURRENT_ANSWER_PASS',answer[:500].replace('\n',' '))
print('CURRENT_EVIDENCE_REFS',refs[:5])
PY
}

fresh_case() {
  local update="$1" text="$2" pattern="$3" result_prefix="$4" transport="${5:-public}" require_numeric="${6:-1}" since case_file log
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  send_update "$update" "$text" "$transport" || return 1
  wait_answer "$since" "$update" || return 1
  log="$OUT/intelligence-${update}-runtime.log"
  persist_candidate_cases "$update" "$result_prefix" || return 1
  if grep -q "telegram_interaction_failed update_id=$update" "$log"; then
    echo "FRESH_CASE_RUNTIME_FAILURE update_id=$update" >&2
    return 1
  fi
  if grep -Eq "execution-objective-workforce-accepted.*$update|METATRON WORK ACCEPTED.*$update|telegram_answer_ready update_id=$update.*objective_id=[^[:space:]]+" "$log"; then
    echo "FRESH_CASE_WRONG_OBJECTIVE_ROUTE update_id=$update" >&2
    return 1
  fi
  case_file=$(case_file_for_update "$update" "$pattern") || {
    echo "FRESH_CASE_NO_MATCHING_CASE update_id=$update pattern=$pattern" >&2
    return 1
  }
  [ -n "$case_file" ] || {
    echo "FRESH_CASE_EMPTY_CASE_PATH update_id=$update" >&2
    return 1
  }
  printf '%s\n' "$case_file" > "${result_prefix}.case"
  docker exec "$CID" cat "$case_file" > "${result_prefix}.json" || return 1
  test -s "${result_prefix}.json" || return 1
  validate_case "${result_prefix}.json" "$pattern" "$require_numeric" || return 1
  echo "FRESH_CASE_PASS update_id=$update transport=$transport case_file=$case_file"
  # The public edge is itself verified below, but repeated synthetic acceptance messages do not
  # need to traverse Cloudflare. Give the interaction executor a bounded settle interval before
  # the next independent Case so acceptance measures Intelligence rather than edge timing jitter.
  sleep 2
}

BASE_ID=$(date +%s%N | cut -c1-13)
CHAT_MODE="${BASE_ID}10"; BTC_1="${BASE_ID}11"; FX="${BASE_ID}12"; WEATHER="${BASE_ID}13"; BTC_2="${BASE_ID}14"

# Fresh-information acceptance is a Chat-product contract. Founder surface mode is durable across
# real interactions, so normalize the acceptance conversation to Chat instead of depending on the
# Human's last selected Work/Meeting mode.
CHAT_SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
send_update "$CHAT_MODE" "/chat" local
wait_answer "$CHAT_SINCE" "$CHAT_MODE"
CHAT_LOG="$OUT/intelligence-${CHAT_MODE}-runtime.log"
grep -q "telegram_answer_ready update_id=$CHAT_MODE" "$CHAT_LOG"
grep -q "provenance=conversation-surface-control:telegram:update:$CHAT_MODE" "$CHAT_LOG"
echo 'INTELLIGENCE_CHAT_SURFACE_NORMALIZATION=PASS'

# Run two bounded waves. BTC_2 remains after BTC_1 so same-topic reacquisition is preserved,
# while unrelated fresh-information Cases execute concurrently on the same production JVM.
set +e
fresh_case "$BTC_1" 'Giá Bitcoin hiện tại khoảng bao nhiêu USD và VND? Hãy dùng dữ liệu mới và nêu nguồn.' 'Bitcoin|BTC' "$OUT/btc-1" public 1 >"$OUT/btc-1-wave.log" 2>&1 & P_BTC1=$!
fresh_case "$FX" 'Tỷ giá USD/VND hiện tại khoảng bao nhiêu? Dùng dữ liệu mới và cho nguồn.' 'USD|VND|tỷ giá|exchange' "$OUT/fx" local 1 >"$OUT/fx-wave.log" 2>&1 & P_FX=$!
wait "$P_BTC1"; RC_BTC1=$?
wait "$P_FX"; RC_FX=$?
cat "$OUT/btc-1-wave.log" "$OUT/fx-wave.log" || true
test "$RC_BTC1" = 0 && test "$RC_FX" = 0 || exit 1

fresh_case "$WEATHER" 'Thời tiết hiện tại ở Thành phố Hồ Chí Minh thế nào? Kiểm tra dữ liệu mới và nêu nguồn.' 'thời tiết|weather|Ho Chi Minh|Hồ Chí Minh' "$OUT/weather" local 1 >"$OUT/weather-wave.log" 2>&1 & P_WEATHER=$!
fresh_case "$BTC_2" 'Kiểm tra lại giá Bitcoin ngay lúc này bằng dữ liệu hiện tại mới và nêu nguồn.' 'Bitcoin|BTC' "$OUT/btc-2" local 1 >"$OUT/btc-2-wave.log" 2>&1 & P_BTC2=$!
wait "$P_WEATHER"; RC_WEATHER=$?
wait "$P_BTC2"; RC_BTC2=$?
set -e
cat "$OUT/weather-wave.log" "$OUT/btc-2-wave.log" || true
test "$RC_WEATHER" = 0
test "$RC_BTC2" = 0
echo 'INTELLIGENCE_TWO_WAVE_PARALLELISM=PASS'
BTC_CASE_1=$(cat "$OUT/btc-1.case"); BTC_CASE_2=$(cat "$OUT/btc-2.case")
test "$BTC_CASE_1" != "$BTC_CASE_2"
! cmp -s "$OUT/btc-1.json" "$OUT/btc-2.json"

curl -fsS --proto '=https' --tlsv1.2 --max-time 10 https://gate.metatron.vn/telegram/health | grep -q '"status":"UP"'
echo 'INTELLIGENCE_PUBLIC_GATEWAY_CASES=1'
echo 'INTELLIGENCE_PRODUCTION_JVM_CASES=4'
echo 'INTELLIGENCE_BTC_FRESH=PASS'
echo 'INTELLIGENCE_FX_FRESH=PASS'
echo 'INTELLIGENCE_WEATHER_FRESH=PASS'
echo 'INTELLIGENCE_FRESH_INFORMATION_NOT_OBJECTIVE=PASS'
echo 'INTELLIGENCE_SAME_TOPIC_REACQUISITION=PASS'
echo 'INTELLIGENCE_GROUNDED_RELEVANT_EVIDENCE=PASS'
echo 'INTELLIGENCE_USEFUL_NON_REFUSAL_ANSWERS=PASS'
echo 'POINT_2_INTELLIGENCE_LIVE_LANE=PASS'
