#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_RUN_ID:=${HIGHWAY_TASK_ID//[^0-9]/}}"
export GITHUB_RUN_ID
set -euo pipefail
BASE=/opt/metatron/metatron-workforce
OUT=/tmp/runtime-conformance-point4-${GITHUB_RUN_ID}
mkdir -p "$OUT"
test -r "$BASE/.env"
set -a; source "$BASE/.env"; set +a
test -n "${TELEGRAM_WEBHOOK_SECRET:-}"
test -n "${TELEGRAM_ALLOWED_USER_ID:-}"
test -n "${GITHUB_TOKEN:-}"

CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$CID"
LIVE_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$LIVE_SHA" = "$TARGET_SHA"
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
echo "POINT4_TARGET_SHA=$TARGET_SHA"
echo "POINT4_LIVE_SHA=$LIVE_SHA"

UPDATE_ID=$(date +%s%N | cut -c1-18)
TEXT='Take ownership of one governed mutation Objective in kelvinka38/metatron-workforce: repair the stale Workforce Autonomy Closure implementation baseline in docs/AUTONOMY_CLOSURE/CURRENT_STATE_AND_GAP_MATRIX.md, verify the change, and open a pull request for Founder approval. Do not merge it.'
python3 - "$TELEGRAM_ALLOWED_USER_ID" "$UPDATE_ID" "$TEXT" > "$OUT/request.json" <<'PY'
import json,sys
uid=int(sys.argv[1]); update=int(sys.argv[2]); text=sys.argv[3]
print(json.dumps({'update_id':update,'message':{'message_id':update%2000000000,'from':{'id':uid,'is_bot':False,'first_name':'Founder'},'chat':{'id':uid,'type':'private'},'date':0,'text':text}}))
PY
STATUS=$(curl -sS -o "$OUT/response.json" -w '%{http_code}' --proto '=https' --tlsv1.2 --max-time 20 \
  -X POST https://gate.metatron.vn/telegram/webhook \
  -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" \
  -H 'Content-Type: application/json' --data-binary "@$OUT/request.json")
test "$STATUS" = 200
echo 'POINT4_NATURAL_OBJECTIVE_INGRESS=PASS'

OID=''
for _ in $(seq 1 150); do
  curl -fsS --max-time 5 http://127.0.0.1:8080/workforce/management/objectives > "$OUT/objectives.json" || true
  OID=$(python3 - "$OUT/objectives.json" "$UPDATE_ID" <<'PY' || true
import json,sys
try: rows=json.load(open(sys.argv[1]))
except Exception: raise SystemExit(1)
needle=f'telegram:update:{sys.argv[2]}'
ids=[]
for row in rows if isinstance(rows,list) else []:
    if needle in json.dumps(row,sort_keys=True):
        o=row.get('objective') or {}; oid=o.get('objectiveId') or o.get('objective_id')
        if oid: ids.append(oid)
ids=list(dict.fromkeys(ids))
if len(ids)!=1: raise SystemExit(1)
print(ids[0])
PY
  )
  [ -n "$OID" ] && break
  sleep 2
done
test -n "$OID"
echo "POINT4_OBJECTIVE_ID=$OID"

TERMINAL=''
for _ in $(seq 1 450); do
  curl -fsS --max-time 5 "http://127.0.0.1:8080/workforce/management/objectives/$OID" > "$OUT/objective.json" || true
  TERMINAL=$(python3 - "$OUT/objective.json" <<'PY'
import json,sys
try: print((json.load(open(sys.argv[1])).get('objective') or {}).get('status',''))
except Exception: print('')
PY
  )
  case "$TERMINAL" in
    COMPLETED|DELIVERED) break ;;
    BLOCKED|ESCALATED|CANCELLED|FAILED) echo "POINT4_BAD_TERMINAL=$TERMINAL" >&2; exit 2 ;;
  esac
  sleep 2
done
case "$TERMINAL" in COMPLETED|DELIVERED) ;; *) echo 'POINT4_TERMINAL_TIMEOUT' >&2; exit 1 ;; esac
echo "POINT4_OBJECTIVE_TERMINAL=$TERMINAL"

JOURNAL=$(docker exec "$CID" sh -c "grep -R -l -F '\"objectiveId\":\"$OID\"' /var/lib/metatron-workforce/runtime-evidence/action-journal 2>/dev/null | tail -1" || true)
test -n "$JOURNAL"
docker exec "$CID" cat "$JOURNAL" > "$OUT/action-journal.jsonl"
python3 - "$OUT/action-journal.jsonl" "$OUT/pr-url.txt" <<'PY'
import json,sys
rows=[json.loads(line) for line in open(sys.argv[1],encoding='utf-8') if line.strip()]
expected=[
  'github.repository.main-ref.read',
  'github.repository.approved-file.read',
  'github.repository.proposal-branch.ensure',
  'github.repository.approved-file.propose',
  'github.repository.pull-request.ensure',
]
assert [r.get('thoughtAction') for r in rows]==expected, [r.get('thoughtAction') for r in rows]
assert [r.get('cycle') for r in rows]==[1,2,3,4,5]
assert all(r.get('workerId')=='WORKER-REPOSITORY-PR-PROPOSER' for r in rows)
assert all(r.get('authorizationReference')=='authorization:founder-autonomy-gap-matrix-pr:v1' for r in rows)
assignments={r.get('assignmentReference') for r in rows}
assert len(assignments)==1 and None not in assignments and '' not in assignments, assignments
assert all(r.get('actionSuccess') is True for r in rows)
assert [r.get('reflection') for r in rows]==['CONTINUE','CONTINUE','CONTINUE','CONTINUE','COMPLETE']
assert not any('merge' in str(r.get('thoughtAction','')).lower() for r in rows)
pr_url=(rows[-1].get('outputs') or {}).get('prUrl','')
assert pr_url.startswith('https://github.com/kelvinka38/metatron-workforce/pull/'), pr_url
open(sys.argv[2],'w',encoding='utf-8').write(pr_url+'\n')
print('POINT4_COGNITIVE_SEQUENCE=PASS')
print('POINT4_PER_ACTION_WORKER_ATTRIBUTION=PASS')
print('POINT4_PER_ACTION_AUTHORIZATION=PASS')
print('POINT4_OBSERVATION_DRIVEN_REFLECTION=PASS')
print('POINT4_CURRENT_OBJECTIVE_PR_ATTRIBUTION=PASS')
print('POINT4_NO_MERGE_ACTION=PASS')
PY

PR_URL=$(cat "$OUT/pr-url.txt")
test -n "$PR_URL"
PR_NUMBER=${PR_URL##*/}
curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/kelvinka38/metatron-workforce/pulls/$PR_NUMBER" > "$OUT/pr.json"
python3 - "$OUT/pr.json" <<'PY'
import json,sys
pr=json.load(open(sys.argv[1]))
assert pr.get('state')=='open'
assert pr.get('merged') is False and pr.get('merged_at') is None
assert ((pr.get('head') or {}).get('ref') or '').startswith('autonomy/gs2-')
print('POINT4_REAL_GITHUB_MUTATION=PASS')
print('POINT4_FOUNDER_MERGE_BOUNDARY=PRESERVED')
PY

docker exec "$CID" sh -c "grep -F '$OID' /var/lib/metatron-workforce/observation-state.json >/dev/null && grep -F 'PASS' /var/lib/metatron-workforce/observation-state.json >/dev/null"
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
FINAL_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$FINAL_SHA" = "$TARGET_SHA"
echo 'POINT4_INDEPENDENT_OBSERVATION=PASS'
echo 'POINT4_EXACT_SHA_STABLE=PASS'
echo 'RUNTIME_CONFORMANCE_POINT4=PASS'
