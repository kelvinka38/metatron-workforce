#!/usr/bin/env bash
set -euo pipefail
export GITHUB_ENV="${HIGHWAY_STATE_DIR}/${HIGHWAY_TASK_ID}.github-env"
: > "$GITHUB_ENV"

# migrated workflow step 1
set -euo pipefail
WORKFORCE_ENV="${METATRON_PRODUCTION_ENV_FILE:-$HOME/.metatron/config/workforce.env}"
BIOS_ENV="${METATRON_BIOS_ENV_FILE:-$HOME/.metatron/bios/.env}"
test -r "$WORKFORCE_ENV"
set -a; source "$WORKFORCE_ENV"; set +a
rm -rf /tmp/bios-p10
cat >/tmp/askpass <<'SH'
#!/bin/sh
case "$1" in *Username*) echo x-access-token;; *Password*) echo "$GITHUB_TOKEN";; esac
SH
chmod 700 /tmp/askpass; export GIT_ASKPASS=/tmp/askpass GIT_TERMINAL_PROMPT=0
git clone -q https://github.com/kelvinka38/bios.git /tmp/bios-p10
cd /tmp/bios-p10
SHA=$(git rev-parse HEAD); echo "BIOS_P10_SHA=$SHA" >> "$GITHUB_ENV"
          export BIOS_P10_SHA="$SHA"; echo "BIOS_P10_SHA=$SHA" | tee /tmp/p10-evidence.txt
python3 -m unittest discover -s tests -p 'test_*.py' 2>&1 | tee -a /tmp/p10-evidence.txt
if [ -s "$GITHUB_ENV" ]; then set -a; source "$GITHUB_ENV"; set +a; fi

# migrated workflow step 2
set -euo pipefail
test -r "$BIOS_ENV"
set -a; source "$BIOS_ENV"; set +a
export BIOS_IMAGE_TAG="$BIOS_P10_SHA" BIOS_COMMIT_SHA="$BIOS_P10_SHA"
docker build --pull -t "metatron-bios:$BIOS_P10_SHA" /tmp/bios-p10
docker compose -p bios --env-file "$BIOS_ENV" -f /tmp/bios-p10/deploy/docker-compose.yml up -d --force-recreate bios
CID=$(docker ps -aq --filter name='^metatron-bios$' | head -1); test -n "$CID"
for i in $(seq 1 60); do
  STATUS=$(docker inspect "$CID" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}')
  [ "$STATUS" = healthy ] && break
  [ "$STATUS" = unhealthy ] && { docker logs --tail 100 "$CID"; exit 1; }
  sleep 2
done
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
curl -fsS http://127.0.0.1:18080/health >/dev/null
DEPLOYED=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^BIOS_COMMIT_SHA=//p' | head -1); test "$DEPLOYED" = "$BIOS_P10_SHA"
echo "BIOS_P10_PRODUCTION_SHA=$DEPLOYED" | tee -a /tmp/p10-evidence.txt
if [ -s "$GITHUB_ENV" ]; then set -a; source "$GITHUB_ENV"; set +a; fi

# migrated workflow step 3
set -euo pipefail
test -r "$BIOS_ENV"
set -a; source "$BIOS_ENV"; set +a
cd /tmp/bios-p10
python3 - <<'PY' | tee -a /tmp/p10-evidence.txt
import json,urllib.request,urllib.error,uuid,os
base='http://127.0.0.1:18080'; key=os.environ['BIOS_API_KEY']; actor='P10_RELEASE_PROBE'; rid=lambda:'p10-'+uuid.uuid4().hex
def post(path,payload,who=actor,expect=200):
 body={'request_id':rid(),'correlation_id':'p10-release-ready','actor_id':who,'timestamp':'2026-09-01T04:00:00+00:00','schema_version':'0.1','payload':payload}
 req=urllib.request.Request(base+path,data=json.dumps(body).encode(),method='POST',headers={'Content-Type':'application/json','X-BIOS-API-Key':key})
 try:
  with urllib.request.urlopen(req,timeout=15) as r: assert r.status==expect; return json.loads(r.read())
 except urllib.error.HTTPError as e:
  assert e.code==expect,(e.code,e.read()); return None
case=post('/v1/cases/run',{'objective':'P10 release readiness','system_exists':True,'reality':[{'id':'r','subject':'bios','property':'release','value':'READY','source':'probe'}],'state':{'constraints':[]},'feasibility':{'status':'FEASIBLE','basis':['runtime']},'reasoning':{'claims':[{'id':'I','type':'INFERENCE','statement':'release telemetry is testable','evidence_refs':[{'subject':'bios','property':'release'}]},{'id':'F','type':'FEASIBILITY','statement':'probe feasible','status':'FEASIBLE','upstream_claim_ids':['I'],'counter_evidence':[],'falsifier':'probe fails'}],'relations':[{'type':'SUPPORTS','source':'r','target':'I'}],'assumptions':[],'counter_evidence':[],'falsifiers':[]}})['payload']; cid=case['id']
for et,s in [('SESSION_STARTED','s1'),('PRODUCT_USED','s1'),('CORRECTION','s1'),('SESSION_STARTED','s2'),('PRODUCT_USED','s2'),('OUTCOME_REPORTED','s2'),('PRICE_SIGNAL','s2'),('FEEDBACK','s2')]: post('/v2/product/release/events',{'case_id':cid,'event_type':et,'session_id':s,'provenance':'SYNTHETIC_RELEASE_READINESS_ONLY'})
summary=post('/v2/product/release/summary',{'case_id':cid})['payload']; assert summary['sessions']==2 and summary['repeat_subjects']==1 and summary['product_uses']==2 and summary['corrections']==1 and summary['outcomes_reported']==1 and summary['price_signals']==1
post('/v2/product/release/events',{'case_id':cid,'event_type':'PRODUCT_USED','session_id':'x'},who='UNAUTHORIZED_P10',expect=400)
open('/tmp/p10-case','w').write(cid); print('P10_AUTHORIZATION=PASS'); print('P10_METRICS=PASS'); print('P10_CASE='+cid)
PY
CID=$(docker ps -q --filter name='^metatron-bios$' | head -1); docker restart "$CID" >/dev/null
for i in $(seq 1 60); do curl -fsS http://127.0.0.1:18080/health >/dev/null 2>&1 && break; sleep 2; done
python3 - <<'PY' | tee -a /tmp/p10-evidence.txt
import json,urllib.request,os,uuid
cid=open('/tmp/p10-case').read(); body={'request_id':'restart-'+uuid.uuid4().hex,'correlation_id':'p10','actor_id':'P10_RELEASE_PROBE','timestamp':'2026-09-01T04:00:00+00:00','schema_version':'0.1','payload':{'case_id':cid}}
req=urllib.request.Request('http://127.0.0.1:18080/v2/product/release/summary',data=json.dumps(body).encode(),method='POST',headers={'Content-Type':'application/json','X-BIOS-API-Key':os.environ['BIOS_API_KEY']})
with urllib.request.urlopen(req) as r: s=json.loads(r.read())['payload']; assert s['product_uses']==2 and s['sessions']==2
print('P10_RESTART_DURABILITY=PASS'); print('P10_RELEASE_READINESS=PASS')
PY
if [ -s "$GITHUB_ENV" ]; then set -a; source "$GITHUB_ENV"; set +a; fi
