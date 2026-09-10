#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_RUN_ID:=${HIGHWAY_TASK_ID//[^0-9]/}}"
export GITHUB_RUN_ID
set -euo pipefail
CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$CID"
DEPLOYED_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test "$DEPLOYED_SHA" = "$TARGET_SHA"
docker exec "$CID" sh -c 'test -n "$GITHUB_TOKEN"'
BODY='{"organizationContextId":"METATRON","repository":"kelvinka38/bios"}'
HTTP_STATUS=$(curl --connect-timeout 5 --max-time 60 -sS -o /tmp/repository-audit-live.json -w '%{http_code}' -X POST \
  http://127.0.0.1:8080/workforce/execution/repository-audit \
  -H 'Content-Type: application/json' \
  -H 'X-Metatron-Actor: FOUNDER' \
  -H 'X-Metatron-Authority: LIVE-ACCEPTANCE-OBSERVATION-AUTHORITY' \
  -H 'X-Metatron-Authorization: LIVE-ACCEPTANCE-REPOSITORY-READ' \
  -d "$BODY")
echo "REPOSITORY_AUDIT_HTTP_STATUS=$HTTP_STATUS"; cat /tmp/repository-audit-live.json; echo
test "$HTTP_STATUS" = "200"
python3 - <<'PY'
import json,re
d=json.load(open('/tmp/repository-audit-live.json'))
e=d['workerResult']['evidence']
assert d['repository']=='kelvinka38/bios'
assert d['workerResult']['worker']=='RepositoryAuditWorker'
assert d['workerResult']['status']=='PASS'
for marker in ['Repository Audit Report','source=gateway-egress/github-api','authenticated=true','repository=kelvinka38/bios','treeHttpStatus=200','repositoryFilesObserved=','contentFilesRead=','contentBytesRead=','sotSignals=','gatewayEgressCrossings=','gatewayEgressProvenance=','authorization=LIVE-ACCEPTANCE-REPOSITORY-READ','credential=isolated','findings=','observedPaths=','verdict=PASS']:
    assert marker in e,(marker,e)
sha=re.search(r'commitSha=([0-9a-f]{40})',e); assert sha,e
files=int(re.search(r'contentFilesRead=(\d+)',e).group(1)); assert files>0,e
size=int(re.search(r'contentBytesRead=(\d+)',e).group(1)); assert size>0,e
crossings=int(re.search(r'gatewayEgressCrossings=(\d+)',e).group(1)); assert crossings>=4,e
assert d['work']['status']=='COMPLETED'
assert d['work']['outcomeRef']=='repository-audit:PASS:kelvinka38/bios'
assert any(x.startswith('runtime-evidence:') for x in d['work']['evidenceRefs'])
print('PRIVATE_BIOS_SUBSTANTIVE_AUDIT=PASS')
print('GOVERNED_GATEWAY_EGRESS=PASS')
print('EGRESS_CREDENTIAL_ISOLATION=PASS')
print('EGRESS_RETRIEVAL_PROVENANCE=PASS')
print('ONE_DISPATCH_TERMINAL_WORK=PASS')
PY
