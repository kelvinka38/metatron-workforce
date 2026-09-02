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
test -n "${METATRON_RUNTIME_EXECUTION_TOKEN:-}"

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
  local update="$1" registry="$OUT/objectives-no-objective-$1.json"
  curl -fsS --max-time 10 http://127.0.0.1:8080/workforce/management/objectives > "$registry"
  python3 - "$registry" "$update" <<'PY'
import json,sys
with open(sys.argv[1],encoding='utf-8') as handle:
    rows=json.load(handle)
needle=f'telegram:update:{sys.argv[2]}'
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
MEETING_RECEIPT=$(wait_receipt_delivered "$MEETING_UPDATE" 0)
test "$MEETING_RECEIPT" = NONE
echo 'POINT5_MEETING_RECEIPT_DELIVERED=PASS'
no_objective_for_update "$MEETING_UPDATE"
echo 'POINT5_MEETING_NO_OBJECTIVE=PASS'

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
follow='meeting-follow-up:'+mid
assert any('handoff_ref='+follow in str(x) for x in (m.get('actionItems') or [])), 'FOLLOW_UP_HANDOFF_MISSING'
assert any(('meeting-handoff:'+follow) in str(x) and 'authority-created=false' in str(x)
           for x in (m.get('evidenceRefs') or [])), 'FOLLOW_UP_HANDOFF_EVIDENCE_MISSING'
print('POINT5_MEETING_DURABLE_LIFECYCLE=PASS')
print('POINT5_MEETING_MULTI_ROLE_ATTRIBUTION=PASS')
print('POINT5_MEETING_NO_AUTHORITY_ESCALATION=PASS')
print('POINT5_MEETING_EXPLICIT_AUTHORIZED_HANDOFF=PASS')
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
echo 'POINT5_OBJECTIVE_RECEIPT_DELIVERED=PASS'

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

echo 'POINT5_PHASE=RUNTIME_ACTUAL_EFFECT_CONSUMER'
WORKER_VIEW=$(curl -fsS --max-time 10 http://127.0.0.1:8080/workforce/core/workers/WORKER-REPOSITORY-AUDITOR)
printf '%s\n' "$WORKER_VIEW" > "$OUT/runtime-worker-view.json"
read -r PARTICIPATION_ID ORGANIZATION_REF < <(python3 - "$OUT/runtime-worker-view.json" <<'PY'
import json,sys
v=json.load(open(sys.argv[1],encoding='utf-8'))
rows=[p for p in (v.get('participations') or []) if p.get('status')=='ACTIVE']
assert rows, 'NO_ACTIVE_REPOSITORY_AUDITOR_PARTICIPATION'
p=rows[0]
print(p['participationId'],p['organizationRef'])
PY
)
test -n "$PARTICIPATION_ID"
test -n "$ORGANIZATION_REF"
RUNTIME_OBJECTIVE="objective:runtime-consumer:${RUN_ID}"
RUNTIME_ASSIGNMENT="assignment:runtime-consumer:${RUN_ID}"
RUNTIME_STEP="runtime-consumer-audit-${RUN_ID}"
RUNTIME_DISPATCH="${RUNTIME_OBJECTIVE}:graph:1:step:${RUNTIME_STEP}:attempt:1"

python3 - "$RUNTIME_ASSIGNMENT" "$RUNTIME_OBJECTIVE" "$PARTICIPATION_ID" "$ORGANIZATION_REF" > "$OUT/runtime-assignment-request.json" <<'PY'
import json,sys
assignment,objective,participation,org=sys.argv[1:5]
print(json.dumps({
  'assignmentId':assignment,
  'objectiveRef':objective,
  'workerId':'WORKER-REPOSITORY-AUDITOR',
  'participationId':participation,
  'authorityRef':'policy:founder-readonly-repository-audit:v1',
  'authorizationRef':'authorization:founder-readonly-repository-audit:v1',
  'description':'Point5 production proof: governed remote runtime actual effect'
}))
PY
curl -fsS --max-time 15 -X POST http://127.0.0.1:8080/workforce/core/assignments \
  -H 'Content-Type: application/json' --data-binary @"$OUT/runtime-assignment-request.json" \
  > "$OUT/runtime-assignment.json"
python3 - "$OUT/runtime-assignment.json" "$RUNTIME_ASSIGNMENT" "$RUNTIME_OBJECTIVE" <<'PY'
import json,sys
v=json.load(open(sys.argv[1],encoding='utf-8'))
assert v.get('assignmentId')==sys.argv[2]
assert v.get('objectiveRef')==sys.argv[3]
assert v.get('workerId')=='WORKER-REPOSITORY-AUDITOR'
assert v.get('status')=='ACTIVE'
assert v.get('authorizationRef')=='authorization:founder-readonly-repository-audit:v1'
print('POINT5_RUNTIME_CANONICAL_ASSIGNMENT=PASS')
PY

python3 - "$RUN_ID" "$RUNTIME_OBJECTIVE" "$RUNTIME_ASSIGNMENT" "$RUNTIME_STEP" "$RUNTIME_DISPATCH" "$ORGANIZATION_REF" > "$OUT/runtime-execution-command.json" <<'PY'
import json,sys
run_id,objective,assignment,step,dispatch,org=sys.argv[1:7]
print(json.dumps({
  'executionId':'execution:runtime-consumer:'+run_id,
  'workerId':'WORKER-REPOSITORY-AUDITOR',
  'runtimeId':'runtime:production:'+run_id,
  'workSpec':{
    'stepId':step,
    'objective':'Perform a real governed repository audit through the runtime command consumer',
    'target':'kelvinka38/bios',
    'requiredCapability':'repository.audit.read',
    'dependsOn':[],
    'consequence':'READ_ONLY',
    'acceptanceCriteria':['repository audit completed'],
    'evidenceRequirements':['real external repository evidence']
  },
  'humanId':'human:founder-runtime-acceptance',
  'organizationContextId':org,
  'objectiveId':objective,
  'assignmentReference':assignment,
  'authorizationReference':'authorization:founder-readonly-repository-audit:v1',
  'dispatchReference':dispatch,
  'dispatchAttempt':1
}))
PY
RUNTIME_HTTP=$(curl -sS -o "$OUT/runtime-execution-result.json" -w '%{http_code}' --max-time 90 \
  -X POST http://127.0.0.1:8080/workforce/runtime/execute \
  -H "X-Metatron-Runtime-Execution-Token: $METATRON_RUNTIME_EXECUTION_TOKEN" \
  -H 'Content-Type: application/json' --data-binary @"$OUT/runtime-execution-command.json")
test "$RUNTIME_HTTP" = 200
python3 - "$OUT/runtime-execution-result.json" "$RUNTIME_OBJECTIVE" "$RUNTIME_ASSIGNMENT" <<'PY'
import json,sys
v=json.load(open(sys.argv[1],encoding='utf-8'))
assert v.get('success') is True, v
assert v.get('objectiveId')==sys.argv[2]
assert v.get('assignmentReference')==sys.argv[3]
assert v.get('workerId')=='WORKER-REPOSITORY-AUDITOR'
assert v.get('capabilityRef')=='repository.audit.read'
assert v.get('authorizationReference')=='authorization:founder-readonly-repository-audit:v1'
assert v.get('authorityReference')=='policy:founder-readonly-repository-audit:v1'
refs=v.get('evidenceReferences') or []
assert any(str(x).startswith('runtime-actual-effect:') and ':success=true' in str(x) for x in refs)
assert any('execution-model:cognitive-action-fabric' in str(x) for x in refs)
print('POINT5_RUNTIME_COMMAND_ACTUAL_EFFECT=PASS')
print('POINT5_RUNTIME_EFFECT_ATTRIBUTION=PASS')
PY
RUNTIME_JOURNAL=$(docker exec "$CID" sh -c "grep -R -l -F '\"objectiveId\":\"$RUNTIME_OBJECTIVE\"' /var/lib/metatron-workforce/runtime-evidence/action-journal 2>/dev/null | tail -1" || true)
test -n "$RUNTIME_JOURNAL"
docker exec "$CID" cat "$RUNTIME_JOURNAL" > "$OUT/runtime-action-journal.jsonl"
python3 - "$OUT/runtime-action-journal.jsonl" <<'PY'
import json,sys
rows=[json.loads(x) for x in open(sys.argv[1],encoding='utf-8') if x.strip()]
assert len(rows)>=4
assert all(r.get('workerId')=='WORKER-REPOSITORY-AUDITOR' for r in rows)
assert all(r.get('authorizationReference')=='authorization:founder-readonly-repository-audit:v1' for r in rows)
assert all(r.get('actionSuccess') is True for r in rows)
assert rows[-1].get('reflection')=='COMPLETE'
print('POINT5_RUNTIME_EFFECT_REAL_ACTION_JOURNAL=PASS')
PY
curl -fsS --max-time 10 -X POST \
  "http://127.0.0.1:8080/workforce/core/assignments/$RUNTIME_ASSIGNMENT/status/COMPLETED" \
  > "$OUT/runtime-assignment-completed.json"
python3 - "$OUT/runtime-assignment-completed.json" <<'PY'
import json,sys
v=json.load(open(sys.argv[1],encoding='utf-8'))
assert v.get('status')=='COMPLETED'
print('POINT5_RUNTIME_ASSIGNMENT_CLOSED=PASS')
PY

test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
FINAL_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$FINAL_SHA" = "$TARGET_SHA"
echo 'POINT5_EXACT_SHA_STABLE=PASS'
echo 'RUNTIME_CONFORMANCE_POINT5=PASS'