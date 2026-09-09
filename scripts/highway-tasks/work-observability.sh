#!/usr/bin/env bash
set -euo pipefail
export TARGET_SHA="${TARGET_SHA:-${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}}"
export GITHUB_WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
export GITHUB_RUN_ID="${GITHUB_RUN_ID:-$(date +%s)}"
set -euo pipefail
CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$CID"
DEPLOYED=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$DEPLOYED" = "$TARGET_SHA"
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
echo "WORK_OBSERVABILITY_SHA=$DEPLOYED"

set -euo pipefail
DASH=$(curl -fsS http://127.0.0.1:8080/workforce/monitor)
grep -q 'METATRON WORKFORCE' <<<"$DASH"
grep -q 'canonical management projection' <<<"$DASH"
grep -q 'Work Breakdown' <<<"$DASH"
grep -q 'Reports to' <<<"$DASH"
grep -q 'Staffing' <<<"$DASH"
API_FILE=$(mktemp)
trap 'rm -f "$API_FILE"' EXIT
curl -fsS -H 'X-Metatron-Actor: human-primary' http://127.0.0.1:8080/workforce/monitor/api/objectives > "$API_FILE"
python3 - "$API_FILE" <<'PY'
import json,sys
with open(sys.argv[1],encoding='utf-8') as fh:
    rows=json.load(fh)
assert isinstance(rows,list)
for row in rows:
    for key in ('objectiveId','humanStatus','progressPercent','ownerWorker','reportsTo','workload','etaCommitment','staffingState','workItems','recentEvents','executionProof'):
        assert key in row, (key,row)
    proof=row['executionProof']; assert 'state' in proof and 'last_activity_at' in proof
    if row['humanStatus']=='WORKING': assert proof['state']=='EXECUTION_ACTIVITY_OBSERVED', row
    if row['humanStatus']=='COMPLETED': assert row['progressPercent']==100 or row['totalWork']==0, row
    if row['staffingState']=='UNASSIGNED':
        for work in row['workItems']: assert work['performer']=='UNASSIGNED', work
    for work in row['workItems']:
        for key in ('requiredCapability','performer','acceptanceCriteria','dependsOn','status'): assert key in work, (key,work)
print('ACCOUNTABLE_WORK_ORDER_API=PASS')
print('VISIBLE_OBJECTIVES='+str(len(rows)))
PY
echo 'WORK_OBSERVABILITY_DASHBOARD=PASS'

set -euo pipefail
HEALTH=$(curl -fsS http://127.0.0.1:8080/telegram/health)
python3 - "$HEALTH" <<'PY'
import json,sys
h=json.loads(sys.argv[1]); assert h['status']=='UP'; assert h['channel']=='telegram'; assert 'active_task_monitors' in h
print('TELEGRAM_MONITOR_TRANSPORT=PASS')
PY
echo 'WORK_OBSERVABILITY_HIGHWAY_TASK=PASS'
