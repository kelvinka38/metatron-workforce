#!/usr/bin/env bash
set -euo pipefail

BASE=/opt/metatron/metatron-workforce
OUT="/tmp/metatron-production-highway-${GITHUB_RUN_ID:-manual}/point2-root"
mkdir -p "$OUT"
test -r "$BASE/.env"
set -a; source "$BASE/.env"; set +a
test -n "${TELEGRAM_WEBHOOK_SECRET:-}"
test -n "${TELEGRAM_ALLOWED_USER_ID:-}"
test -n "${TARGET_SHA:-}"

CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$CID"
DEPLOYED_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$DEPLOYED_SHA" = "$TARGET_SHA"
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy

send_update() {
  local update_id="$1" text="$2" body status
  body=$(python3 - "$update_id" "$text" "$TELEGRAM_ALLOWED_USER_ID" <<'PY'
import json,sys
uid=int(sys.argv[3]); update=int(sys.argv[1])
print(json.dumps({"update_id":update,"message":{"message_id":update%2000000000,"from":{"id":uid,"is_bot":False,"first_name":"Founder"},"chat":{"id":uid,"type":"private"},"date":0,"text":sys.argv[2]}},ensure_ascii=False))
PY
  )
  printf '%s\n' "$body" > "$OUT/${update_id}-request.json"
  for attempt in 1 2 3 4; do
    status=$(curl -sS -o "$OUT/${update_id}-ingress.json" -w '%{http_code}' --connect-timeout 3 --max-time 25 \
      -X POST http://127.0.0.1:8080/telegram/webhook \
      -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
      -H 'Content-Type: application/json' --data-binary "$body" || true)
    echo "POINT2_INGRESS_ATTEMPT transport=local-live update_id=$update_id attempt=$attempt status=$status"
    [ "$status" = 200 ] && return 0
    sleep $((attempt * 2))
  done
  return 1
}

wait_terminal() {
  local since="$1" update_id="$2"
  for _ in $(seq 1 120); do
    LOGS=$(docker logs --since "$since" "$CID" 2>&1 || true)
    if grep -q "telegram_webhook_ack update_id=$update_id" <<<"$LOGS" \
      && grep -q "telegram_send_success update_id=$update_id" <<<"$LOGS" \
      && grep -q "telegram_answer_ready update_id=$update_id" <<<"$LOGS"; then return 0; fi
    sleep 2
  done
  echo "TERMINAL_TIMEOUT update_id=$update_id" >&2
  docker logs --since "$since" "$CID" 2>&1 | grep -E "update_id=$update_id|metatron_intelligence_latency" >&2 || true
  return 1
}

assert_current_answer() {
  local update_id="$1" text="$2" subject="$3" require_numeric="$4" source_terms="$5" since case_file
  since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  send_update "$update_id" "$text"
  wait_terminal "$since" "$update_id"
  LOGS=$(docker logs --since "$since" "$CID" 2>&1 || true)
  printf '%s\n' "$LOGS" > "$OUT/${update_id}-runtime.log"
  ! grep -Eq "execution-objective-workforce-accepted.*$update_id|METATRON WORK ACCEPTED.*$update_id|telegram_answer_ready update_id=$update_id.*objective_id=[^[:space:]]+" <<<"$LOGS"
  case_file=$(docker exec "$CID" sh -c "grep -R -l 'telegram:update:$update_id' /var/lib/metatron-workforce/intelligence-cases 2>/dev/null | tail -1")
  test -n "$case_file"
  printf '%s\n' "$case_file" > "$OUT/${update_id}-case-path.txt"
  docker exec "$CID" cat "$case_file" | tee "$OUT/${update_id}-case.json" | python3 -c '
import json,re,sys,urllib.request
case=json.load(sys.stdin); subject=sys.argv[1]; numeric=sys.argv[2]=="1"; source_terms=[x for x in sys.argv[3].split(",") if x]
reqs=case.get("informationRequirements") or []
assert reqs, "NO_INFORMATION_REQUIREMENTS"
assert not any(str(r.get("question","")).strip().lower()=="current external evidence" for r in reqs), "GENERIC_FRESH_QUERY"
matching=[r for r in reqs if re.search(subject,str(r.get("question","")),re.I)]
assert matching, "SEMANTIC_REQUIREMENT_LOST_SUBJECT"
assert any(str(r.get("status"))=="SATISFIED" for r in matching), "SEMANTIC_REQUIREMENT_NOT_SATISFIED"
refs=[str(x) for r in matching for x in (r.get("evidenceReferences") or []) if str(x).startswith(("http://","https://"))]
assert refs, "NO_EXTERNAL_EVIDENCE"
validated=[]
for url in refs:
    try:
        req=urllib.request.Request(url,headers={"User-Agent":"Metatron-Point2-Acceptance/1.0"})
        with urllib.request.urlopen(req,timeout=12) as response:
            body=response.read(1000000).decode("utf-8","ignore")
        text=re.sub(r"<[^>]+>"," ",body)
        text=re.sub(r"\s+"," ",text)
        if all(re.search(term,text,re.I) for term in source_terms): validated.append(url)
    except Exception:
        pass
assert validated, "SOURCE_LEVEL_EVIDENCE_ENTITY_MISMATCH"
answer=str(case.get("latestConclusion") or "").strip(); assert answer, "EMPTY_ANSWER"
low=answer.lower()
refusals=("i cannot provide","i can’t provide","unable to provide","insufficient information","not enough information","does not provide","cannot determine","không thể cung cấp","không đủ thông tin","chưa đủ thông tin","không thể xác định")
assert not any(x in low for x in refusals), "CURRENT_ANSWER_IS_REFUSAL_OR_INSUFFICIENT"
assert re.search(subject,answer,re.I), "ANSWER_LOST_SUBJECT"
if numeric: assert re.search(r"\d",answer), "ANSWER_MISSING_CURRENT_VALUE"
print("CURRENT_ANSWER_PASS",answer[:500].replace("\n"," "))
print("CURRENT_EVIDENCE_REFS",refs[:5])
print("SOURCE_LEVEL_EVIDENCE_PASS",validated[:5])
' "$subject" "$require_numeric" "$source_terms"
}

BASE_ID=$(date +%s%N | cut -c1-14)
CASUAL="${BASE_ID}41"; VERSION="${BASE_ID}42"; LEADER="${BASE_ID}43"
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
send_update "$CASUAL" 'Chào Metatron, hôm nay nói chuyện bình thường thôi.'
wait_terminal "$SINCE" "$CASUAL"
LOGS=$(docker logs --since "$SINCE" "$CID" 2>&1 || true)
printf '%s\n' "$LOGS" > "$OUT/${CASUAL}-runtime.log"
! grep -Eq "execution-objective-workforce-accepted.*$CASUAL|METATRON WORK ACCEPTED.*$CASUAL|telegram_answer_ready update_id=$CASUAL.*objective_id=[^[:space:]]+" <<<"$LOGS"

assert_current_answer "$VERSION" 'Phiên bản stable mới nhất của Python hiện tại là gì? Kiểm tra nguồn hiện tại rồi trả lời.' 'Python|stable|version|phiên bản' 1 'Python,3\.[0-9]+'
assert_current_answer "$LEADER" 'Ai hiện đang là Tổng thống Indonesia? Kiểm tra nguồn hiện tại rồi trả lời.' 'Indonesia|Tổng thống|President' 0 'Indonesia,(?:President|Presiden)'

echo 'POINT2_LOCAL_LIVE_RUNTIME=PASS'
echo 'POINT2_CASUAL_NOT_OBJECTIVE=PASS'
echo 'POINT2_CURRENT_EXTERNAL_NOT_OBJECTIVE=PASS'
echo 'POINT2_DOMAIN_INDEPENDENT_SEMANTIC_REQUIREMENTS=PASS'
echo 'POINT2_EXTERNAL_EVIDENCE_ACQUIRED=PASS'
echo 'POINT2_SOURCE_LEVEL_EVIDENCE_RELEVANCE=PASS'
echo 'POINT2_USEFUL_NON_REFUSAL_ANSWER=PASS'
echo 'POINT2_NATURAL_TASK_ROUTING=PASS'
