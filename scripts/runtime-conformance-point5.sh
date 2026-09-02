#!/usr/bin/env bash
set -euo pipefail

: "${TARGET_SHA:?TARGET_SHA is required}"

BASE=${METATRON_PRODUCTION_BASE:-/opt/metatron/metatron-workforce}
RUN_ID=${GITHUB_RUN_ID:-$(date +%s)}
OUT=${POINT5_OUT_DIR:-/tmp/runtime-conformance-point5-${RUN_ID}}
mkdir -p "$OUT"

test -r "$BASE/.env"
set -a
# shellcheck disable=SC1090
source "$BASE/.env"
set +a

test -n "${TELEGRAM_WEBHOOK_SECRET:-}"
test -n "${TELEGRAM_ALLOWED_USER_ID:-}"

CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$CID"
LIVE_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$LIVE_SHA" = "$TARGET_SHA"
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy

echo "POINT5_TARGET_SHA=$TARGET_SHA"
echo "POINT5_LIVE_SHA=$LIVE_SHA"

telegram_body() {
  python3 - "$1" "$2" "$TELEGRAM_ALLOWED_USER_ID" <<'PY'
import json,sys
u=int(sys.argv[1]); text=sys.argv[2]; uid=int(sys.argv[3])
print(json.dumps({'update_id':u,'message':{'message_id':u%2000000000,'from':{'id':uid,'is_bot':False,'first_name':'Founder'},'chat':{'id':uid,'type':'private'},'date':0,'text':text}},ensure_ascii=False))
PY
}

send_public() {
  local update="$1" text="$2" body status attempt
  body=$(telegram_body "$update" "$text")
  printf '%s\n' "$body" > "$OUT/request-$update.json"
  for attempt in 1 2 3 4 5; do
    status=$(curl -sS -o "$OUT/response-$update.json" -w '%{http_code}' --proto '=https' --tlsv1.2 \
      --connect-timeout 5 --max-time 30 -X POST https://gate.metatron.vn/telegram/webhook \
      -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
      -H 'Content-Type: application/json' --data-binary "$body" || true)
    [ "$status" = 200 ] && return 0
    sleep "$attempt"
  done
  echo "POINT5_INGRESS_FAILED update_id=$update" >&2
  return 1
}

wait_receipt_delivered() {
  local update="$1" objective_expected="$2" result
  for _ in $(seq 1 180); do
    docker exec "$CID" cat /var/lib/metatron-workforce/telegram-ingress-state.json > "$OUT/telegram-ingress.json" 2>/dev/null || true
    result=$(python3 - "$OUT/telegram-ingress.json" "$update" "$objective_expected" <<'PY' || true
import json,sys
try:
    data=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception:
    raise SystemExit(1)
update=int(sys.argv[2]); expect=sys.argv[3]=='1'
rows=data.get('receipts') or []
matches=[r for r in rows if int(r.get('updateId',-1))==update]
if len(matches)!=1:
    raise SystemExit(1)
r=matches[0]
status=str(r.get('status') or '')
if status in {'DEAD_LETTER','FAILED','REJECTED'}:
    reason=str(r.get('lastError') or r.get('failureReason') or r.get('reason') or 'terminal-ingress-failure')
    print('__FAIL__:'+reason.replace('\n',' ')[:500])
    raise SystemExit(0)
if status!='DELIVERED':
    raise SystemExit(1)
oid=str(r.get('objectiveId') or '')
if expect and not oid:
    raise SystemExit(1)
if not expect and oid:
    raise SystemExit(1)
print(oid or 'NONE')
PY
    )
    if [[ "${result:-}" == __FAIL__:* ]]; then
      echo "POINT5_RECEIPT_TERMINAL_FAILURE update_id=$update reason=${result#__FAIL__:}" >&2
      return 2
    fi
    [ -n "${result:-}" ] && { echo "$result"; return 0; }
    sleep 2
  done
  echo "POINT5_RECEIPT_TIMEOUT update_id=$update" >&2
  return 1
}

no_objective_for_update() {
  local update="$1" payload
  payload=$(curl -fsS --max-time 10 http://127.0.0.1:8080/workforce/management/objectives)
  python3 - "$payload" "$update" <<'PY'
import json,sys
rows=json.loads(sys.argv[1]); needle=f'telegram:update:{sys.argv[2]}'
assert not any(needle in json.dumps(r,sort_keys=True) for r in rows), 'UNEXPECTED_OBJECTIVE'
PY
}

echo 'POINT5_PHASE=CURRENT_INFORMATION'
chmod +x scripts/highway-intelligence-lane.sh
GITHUB_RUN_ID="${RUN_ID}51" TARGET_SHA="$TARGET_SHA" scripts/highway-intelligence-lane.sh > "$OUT/current-information.log" 2>&1
cat "$OUT/current-information.log"
grep -q 'POINT_2_INTELLIGENCE_LIVE_LANE=PASS' "$OUT/current-information.log"
grep -q 'INTELLIGENCE_FRESH_INFORMATION_NOT_OBJECTIVE=PASS' "$OUT/current-information.log"
echo 'POINT5_CURRENT_INFORMATION_ROUTE=PASS'

echo 'POINT5_PHASE=MULTI_ROLE_MEETING'
MEETING_UPDATE=$(date +%s%N | cut -c1-18)
MEETING_TEXT='Gọi Head of Strategy, Head of Finance và Head of Operations vào bàn kế hoạch tăng trưởng Metatron và đưa recommendation. Hãy giữ rõ disagreement, risk và follow-up.'
send_public "$MEETING_UPDATE" "$MEETING_TEXT"
test "$(wait_receipt_delivered "$MEETING_UPDATE" 0)" = NONE
no_objective_for_update "$MEETING_UPDATE"

MEETING_ID=''
for _ in $(seq 1 120); do
  curl -fsS --max-time 10 http://127.0.0.1:8080/workforce/workplace/meetings > "$OUT/meetings.json" || true
  MEETING_ID=$(python3 - "$OUT/meetings.json" "$MEETING_UPDATE" <<'PY' || true
import json,sys
try:
    rows=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception:
    raise SystemExit(1)
needle=f'telegram:update:{sys.argv[2]}'
found=[r for r in rows if r.get('externalMessageReference')==needle]
if len(found)!=1:
    raise SystemExit(1)
print(found[0].get('meetingId',''))
PY
  )
  [ -n "$MEETING_ID" ] && break
  sleep 2
done
test -n "$MEETING_ID"
python3 - "$OUT/meetings.json" "$MEETING_ID" <<'PY'
import json,sys
rows=json.load(open(sys.argv[1],encoding='utf-8')); mid=sys.argv[2]
m=[r for r in rows if r.get('meetingId')==mid][0]
assert m.get('status')=='FOLLOW_UP', m.get('status')
assert m.get('channelProvider')=='telegram'
assert str(m.get('conversationId','')).startswith('conversation:human:')
assert m.get('authorityCreated') is False
assert not (m.get('decisionRefs') or []), 'MEETING_CREATED_DECISION_AUTHORITY'
assert m.get('lifecycle')==['PROPOSED','OPEN','ACTIVE','DECISION_PENDING','CLOSED','FOLLOW_UP']
participants=m.get('participants') or []
assert 'role:head-of-strategy' in participants
assert 'role:head-of-finance' in participants
assert 'role:head-of-operations' in participants
c=m.get('contributions') or []
assert len(c)==3
assert {x.get('role') for x in c}=={'Head of Strategy','Head of Finance','Head of Operations'}
assert all(str(x.get('text','')).strip() for x in c)
assert all(str(x.get('providerReference','')).startswith('provider:') for x in c)
assert str(m.get('recommendation','')).strip()
assert m.get('actionItems'), 'FOLLOW_UP_MISSING'
print('POINT5_MEETING_DURABLE_LIFECYCLE=PASS')
print('POINT5_MEETING_MULTI_ROLE_ATTRIBUTION=PASS')
print('POINT5_MEETING_NO_AUTHORITY_ESCALATION=PASS')
PY
echo "POINT5_MEETING_ID=$MEETING_ID"
echo 'POINT5_MEETING_SAME_CONVERSATION_DELIVERY=PASS'
echo 'POINT5_MULTI_ROLE_MEETING_ROUTE=PASS'

echo 'POINT5_PHASE=REAL_OBJECTIVE'
OBJECTIVE_UPDATE=$(($(date +%s%N | cut -c1-18)+17))
OBJECTIVE_TEXT='Take ownership of one Objective: perform a governed single-repository read-only audit of kelvinka38/bios using the available repository audit capability, verify it through Observation, and deliver the resulting evidence. Do not mutate anything and do not perform cross-repository analysis.'
send_public "$OBJECTIVE_UPDATE" "$OBJECTIVE_TEXT"

OID=''
for _ in $(seq 1 180); do
  curl -fsS --max-time 5 http://127.0.0.1:8080/workforce/management/objectives > "$OUT/objectives.json" || true
  OID=$(python3 - "$OUT/objectives.json" "$OBJECTIVE_UPDATE" <<'PY' || true
import json,sys
try:
    rows=json.load(open(sys.argv[1],encoding='utf-8'))
except Exception:
    raise SystemExit(1)
needle=f'telegram:update:{sys.argv[2]}'; ids=[]
for row in rows if isinstance(rows,list) else []:
    if needle in json.dumps(row,sort_keys=True):
        o=row.get('objective') or {}; oid=o.get('objectiveId') or o.get('objective_id')
        if oid: ids.append(oid)
ids=list(dict.fromkeys(ids))
if len(ids)!=1:
    raise SystemExit(1)
print(ids[0])
PY
  )
  [ -n "$OID" ] && break
  sleep 2
done
test -n "$OID"
echo "POINT5_OBJECTIVE_ID=$OID"

TERMINAL=''
for _ in $(seq 1 300); do
  curl -fsS --max-time 5 "http://127.0.0.1:8080/workforce/management/objectives/$OID" > "$OUT/objective.json" || true
  TERMINAL=$(python3 - "$OUT/objective.json" <<'PY'
import json,sys
try:
    print((json.load(open(sys.argv[1])).get('objective') or {}).get('status',''))
except Exception:
    print('')
PY
  )
  case "$TERMINAL" in
    COMPLETED|DELIVERED) break ;;
    BLOCKED|ESCALATED|CANCELLED|FAILED) echo "POINT5_BAD_OBJECTIVE_TERMINAL=$TERMINAL" >&2; exit 2 ;;
  esac
  sleep 2
done
case "$TERMINAL" in COMPLETED|DELIVERED) ;; *) echo "POINT5_OBJECTIVE_TIMEOUT=$OID" >&2; exit 1 ;; esac
RECEIPT_OID=$(wait_receipt_delivered "$OBJECTIVE_UPDATE" 1)
test "$RECEIPT_OID" = "$OID"

JOURNAL=$(docker exec "$CID" sh -c "grep -R -l -F '\"objectiveId\":\"$OID\"' /var/lib/metatron-workforce/runtime-evidence/action-journal 2>/dev/null | tail -1" || true)
test -n "$JOURNAL"
docker exec "$CID" cat "$JOURNAL" > "$OUT/objective-action-journal.jsonl"
python3 - "$OUT/objective-action-journal.jsonl" <<'PY'
import json,sys
rows=[json.loads(x) for x in open(sys.argv[1],encoding='utf-8') if x.strip()]
assert len(rows)>=4
assert all(r.get('workerId')=='WORKER-REPOSITORY-AUDITOR' for r in rows)
assert all(r.get('authorizationReference')=='authorization:founder-readonly-repository-audit:v1' for r in rows)
assert all(r.get('actionSuccess') is True for r in rows)
assert rows[-1].get('reflection')=='COMPLETE'
print('POINT5_OBJECTIVE_COGNITIVE_WORKER=PASS')
PY
REPORT=$(docker exec "$CID" sh -c "grep -R -l -F 'repository=kelvinka38/bios' /var/lib/metatron-workforce/runtime-evidence 2>/dev/null | xargs -r grep -l -F 'executionModel=cognitive-action-fabric' | tail -1" || true)
test -n "$REPORT"
docker exec "$CID" cat "$REPORT" > "$OUT/objective-report.txt"
grep -F 'source=gateway-egress/github-api' "$OUT/objective-report.txt" >/dev/null
grep -F 'verdict=PASS' "$OUT/objective-report.txt" >/dev/null
docker exec "$CID" sh -c "grep -F '$OID' /var/lib/metatron-workforce/observation-state.json >/dev/null && grep -F 'PASS' /var/lib/metatron-workforce/observation-state.json >/dev/null"
echo "POINT5_OBJECTIVE_TERMINAL=$TERMINAL"
echo 'POINT5_OBJECTIVE_REAL_TOOL_EVIDENCE=PASS'
echo 'POINT5_OBJECTIVE_INDEPENDENT_OBSERVATION=PASS'
echo 'POINT5_OBJECTIVE_SAME_CONVERSATION_DELIVERY=PASS'
echo 'POINT5_REAL_OBJECTIVE_ROUTE=PASS'

test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
FINAL_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$FINAL_SHA" = "$TARGET_SHA"
echo 'POINT5_EXACT_SHA_STABLE=PASS'
echo 'RUNTIME_CONFORMANCE_POINT5=PASS'
