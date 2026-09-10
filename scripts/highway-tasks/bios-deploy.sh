#!/usr/bin/env bash
set -euo pipefail
: "${BIOS_REQUESTED_SHA:?BIOS_REQUESTED_SHA required}"
set -euo pipefail
WORKFORCE_BASE=/opt/metatron/metatron-workforce
BIOS_BASE="$HOME/.metatron/bios"
STAGE=/tmp/metatron-bios-deploy
test -r "$WORKFORCE_BASE/.env"
set -a; source "$WORKFORCE_BASE/.env"; set +a
test -n "${GITHUB_TOKEN:-}"
docker network inspect metatron-gateway-online >/dev/null

BIOS_SHA="$BIOS_REQUESTED_SHA"
[[ "$BIOS_SHA" =~ ^[0-9a-f]{40}$ ]]
echo "BIOS_SOURCE_SHA=$BIOS_SHA"

rm -rf "$STAGE" && mkdir -p "$STAGE"
curl -fsSL \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/kelvinka38/bios/tarball/$BIOS_SHA" \
  | tar -xz -C "$STAGE" --strip-components=1

cd "$STAGE"
test -f deploy/docker-compose.yml
echo '=== EXACT BIOS SHA TEST ==='
python3 -m unittest discover -s tests -v
echo "BIOS_EXACT_SHA_TEST=PASS"

mkdir -p "$BIOS_BASE"
chmod 700 "$BIOS_BASE"
if [ ! -s "$BIOS_BASE/.env" ]; then
  umask 077
  BIOS_API_KEY=$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')
  printf 'BIOS_API_KEY=%s\nBIOS_RATE_LIMIT=120\nBIOS_RATE_WINDOW_SECONDS=60\n' "$BIOS_API_KEY" > "$BIOS_BASE/.env"
  unset BIOS_API_KEY
fi
chmod 600 "$BIOS_BASE/.env"
set -a; source "$BIOS_BASE/.env"; set +a
test -n "${BIOS_API_KEY:-}"
export BIOS_IMAGE_TAG="$BIOS_SHA"
export BIOS_COMMIT_SHA="$BIOS_SHA"

echo '=== BUILD / DEPLOY ==='
docker build --pull -t "metatron-bios:$BIOS_SHA" .
docker compose -p bios --env-file "$BIOS_BASE/.env" -f deploy/docker-compose.yml up -d --force-recreate bios
CID=$(docker compose -p bios --env-file "$BIOS_BASE/.env" -f deploy/docker-compose.yml ps -q bios)
test -n "$CID"
healthy=false
for i in $(seq 1 45); do
  if curl -fsS http://127.0.0.1:18080/health >/tmp/bios-health.json 2>/dev/null \
    && [ "$(docker inspect "$CID" --format '{{.State.Health.Status}}' 2>/dev/null || true)" = healthy ]; then
    healthy=true
    break
  fi
  sleep 2
done
if [ "$healthy" != true ]; then
  docker logs --tail 200 "$CID" || true
  exit 1
fi
grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"' /tmp/bios-health.json

test "$(docker inspect "$CID" --format '{{.HostConfig.ReadonlyRootfs}}')" = true
test "$(docker inspect "$CID" --format '{{.HostConfig.Privileged}}')" = false
docker inspect "$CID" --format '{{json .NetworkSettings.Networks}}' | grep -q 'metatron-gateway-online'

READY=$(curl -sS -o /tmp/bios-ready.json -w '%{http_code}' -H "X-BIOS-API-Key: $BIOS_API_KEY" http://127.0.0.1:18080/health/ready)
MISSING=$(curl -sS -o /tmp/bios-missing.json -w '%{http_code}' http://127.0.0.1:18080/health/ready)
INVALID=$(curl -sS -o /tmp/bios-invalid.json -w '%{http_code}' -H 'X-BIOS-API-Key: invalid' http://127.0.0.1:18080/health/ready)
test "$READY" = 200
test "$MISSING" = 401
test "$INVALID" = 401

echo '=== OPERATE-01 CASE ==='
python3 - <<'PY' >/tmp/bios-case.json
import json, datetime
print(json.dumps({
  "request_id":"operate-01-run",
  "correlation_id":"operate-01-production",
  "actor_id":"FOUNDER",
  "timestamp":datetime.datetime.now(datetime.timezone.utc).isoformat(),
  "schema_version":"0.1",
  "payload":{
    "objective":"Operate BIOS production through accepted Metatron Gateway and Workforce boundaries",
    "system_exists":True,
    "reality":[{"id":"gateway-live","subject":"metatron","property":"gateway_production_acceptance","value":"PASS","source":"gateway-final-live-acceptance"}],
    "state":{"constraints":["Gateway boundary mandatory","Execution requires institutional authorization"]},
    "feasibility":{"status":"FEASIBLE","basis":["Gateway and Workforce terminal production gates are accepted"]},
    "reasoning":{
      "claims":[
        {"id":"I1","type":"INFERENCE","statement":"Accepted production boundary evidence permits BIOS online operation without boundary bypass.","evidence_refs":[{"subject":"metatron","property":"gateway_production_acceptance"}]},
        {"id":"F1","type":"FEASIBILITY","statement":"BIOS OPERATE-01 is feasible on the accepted production boundary.","status":"FEASIBLE","upstream_claim_ids":["I1"],"counter_evidence":[],"falsifier":"Gateway or Workforce production acceptance becomes invalid."}
      ],
      "relations":[{"type":"SUPPORTS","source":"gateway-live","target":"I1"}],
      "assumptions":[],"counter_evidence":[],"falsifiers":[]
    }
  }
}))
PY
RUN=$(curl -sS -o /tmp/bios-run.json -w '%{http_code}' -X POST http://127.0.0.1:18080/v1/cases/run -H "X-BIOS-API-Key: $BIOS_API_KEY" -H 'Content-Type: application/json' --data-binary @/tmp/bios-case.json)
test "$RUN" = 200
CASE_ID=$(python3 -c "import json; print(json.load(open('/tmp/bios-run.json'))['payload']['id'])")

python3 - <<'PY' >/tmp/bios-authorize.json
import json, datetime
print(json.dumps({"request_id":"operate-01-authorize","correlation_id":"operate-01-production","actor_id":"FOUNDER","timestamp":datetime.datetime.now(datetime.timezone.utc).isoformat(),"schema_version":"0.1","payload":{}}))
PY
AUTH=$(curl -sS -o /tmp/bios-auth.json -w '%{http_code}' -X POST "http://127.0.0.1:18080/v1/cases/$CASE_ID/authorize" -H "X-BIOS-API-Key: $BIOS_API_KEY" -H 'Content-Type: application/json' --data-binary @/tmp/bios-authorize.json)
test "$AUTH" = 200
python3 - <<'PY'
import json
d=json.load(open('/tmp/bios-auth.json'))
assert d['payload']['status']=='ACTIVE'
assert d['payload']['program']['status']=='AUTHORIZED'
PY

python3 - <<'PY' >/tmp/bios-observe.json
import json, datetime
print(json.dumps({"request_id":"operate-01-observe","correlation_id":"operate-01-production","actor_id":"FOUNDER","timestamp":datetime.datetime.now(datetime.timezone.utc).isoformat(),"schema_version":"0.1","payload":{"execution_id":"operate-01-production","subject":"metatron","property":"bios_production_operation","value":"PASS","source":"production-runtime","expected_value":"PASS"}}))
PY
OBS=$(curl -sS -o /tmp/bios-observed.json -w '%{http_code}' -X POST "http://127.0.0.1:18080/v1/cases/$CASE_ID/observe" -H "X-BIOS-API-Key: $BIOS_API_KEY" -H 'Content-Type: application/json' --data-binary @/tmp/bios-observe.json)
test "$OBS" = 200
python3 - <<'PY'
import json
d=json.load(open('/tmp/bios-observed.json'))
assert d['payload']['status']=='MONITORING'
assert d['payload']['execution']['status']=='COMPLETED'
assert d['payload']['state']['version'] >= 2
PY

echo '=== PERSISTENCE / GATEWAY NETWORK ==='
docker restart "$CID" >/dev/null
for i in $(seq 1 30); do curl -fsS http://127.0.0.1:18080/health >/dev/null 2>&1 && break; sleep 2; done
FETCH=$(curl -sS -o /tmp/bios-fetched.json -w '%{http_code}' -H "X-BIOS-API-Key: $BIOS_API_KEY" "http://127.0.0.1:18080/v1/cases/$CASE_ID")
test "$FETCH" = 200
python3 - <<PY
import json
d=json.load(open('/tmp/bios-fetched.json'))
assert d['payload']['id']=='$CASE_ID'
assert d['payload']['state']['version'] >= 2
PY

docker run --rm --network metatron-gateway-online python:3.12-slim python -c "import urllib.request,json; d=json.load(urllib.request.urlopen('http://bios:8080/health',timeout=5)); assert d['status']=='ok'; print('GATEWAY_PRIVATE_NETWORK_TO_BIOS=PASS')"

echo '=== AQUACULTURE LIVE DECISION RECONCILE ==='
python3 - "$BIOS_API_KEY" <<'PY'
import datetime,json,sys,urllib.request
key=sys.argv[1]
actor="aq_farmer_b200f732435343a381484488563711fc"
case_id="case_d9b2e6f64a4a4b25a3c52d60339901cf"
farm_id="aq_farm_1f612ab9df4248af83275c4121c15cab"
base="http://127.0.0.1:18080/v2/product/aquaculture"
def post(route,payload):
    body={"request_id":"aq-live-"+route.strip("/").replace("/","-")+"-"+datetime.datetime.now(datetime.timezone.utc).strftime("%H%M%S%f"),"correlation_id":"aq-decision-v12-live","actor_id":actor,"timestamp":datetime.datetime.now(datetime.timezone.utc).isoformat(),"schema_version":"0.1","payload":payload}
    req=urllib.request.Request(base+route,data=json.dumps(body,ensure_ascii=False).encode(),headers={"Content-Type":"application/json","X-BIOS-API-Key":key},method="POST")
    with urllib.request.urlopen(req,timeout=30) as r: out=json.load(r)
    if out.get("status")=="ERROR": raise AssertionError(out)
    return out["payload"]["data"]
generated=post("/scenarios/generate",{"case_id":case_id,"farm_id":farm_id,"refresh":True})
assert generated["candidate_count"]>=8, generated
scenarios=post("/scenarios/list",{"case_id":case_id,"farm_id":farm_id})
for sc in scenarios: post("/scenarios/evaluate",{"case_id":case_id,"scenario_id":sc["scenario_id"],"version":sc["version"]})
rec=post("/decision/recommend",{"case_id":case_id,"farm_id":farm_id})
assert rec["comparator_version"]=="aq-decision-comparator-1.2", rec
ws=post("/farmer/workspace",{"case_id":case_id,"farm_id":farm_id})
scat=[x for x in ws["scenarios"] if x["scientific_name"]=="Scatophagus argus"]
assert len(scat)>=3, scat
variants={(x["culture_method"],x["input_strategy"]) for x in scat}
assert ("EARTH_POND","MIXED_LOW_INPUT") in variants, variants
assert ("POND","COMMERCIAL_FEED") in variants, variants
assert all(x["input_strategy"]!="NATURAL" for x in scat), scat
low=next(x for x in scat if x["culture_method"]=="EARTH_POND" and x["input_strategy"]=="MIXED_LOW_INPUT")
assert low["economic_model_state"]=="PARTIAL_REFERENCE", low
assert low["production_volume_kg_range"], low
assert low["historical_cycle_cost_reference_minor_range"], low
assert low["capital_required_minor"] is None, low
market=post("/farmer/market",{"case_id":case_id,"farm_id":farm_id})
scat_market=[x for x in market["evidence"] if x["product"]=="Cá nâu"]
assert not any((x.get("region") or {}).get("province") in {"Huế","Thừa Thiên Huế"} for x in scat_market), scat_market
print("AQ_LIVE_SYSTEM_VARIANTS=PASS",len(scat),sorted(variants))
print("AQ_LIVE_ECONOMICS_REFERENCE_GUARD=PASS")
print("AQ_LIVE_MARKET_GEOGRAPHY_GUARD=PASS")
print("AQ_LIVE_COMPARATOR_V12=PASS")
PY

python3 - <<'PY'
import json
d=json.load(open('/tmp/bios-ready.json'))
assert d["bundle_version"]=="1.2.0", d
assert d["counts"]["knowledge"]>=35, d
assert d["counts"]["market_evidence"]>=6, d
print("AQ_BUNDLE_1_2=PASS")
PY

curl -fsS -D /tmp/aq-decision-js.headers -o /tmp/aq-decision-app.js https://gate.metatron.vn/aquaculture/assets/app.js
grep -qi '^cache-control: no-store' /tmp/aq-decision-js.headers
grep -q 'Sản lượng tham chiếu' /tmp/aq-decision-app.js
grep -q 'Cam kết mua' /tmp/aq-decision-app.js
grep -q 'Kế hoạch vận hành derive' /tmp/aq-decision-app.js
echo 'AQ_DECISION_UX_V12_PUBLIC=PASS'

echo '=== BIOS PRODUCTION ACCEPTANCE ==='
echo "BIOS_PRODUCTION_SHA=$BIOS_SHA"
echo "BIOS_CASE_ID=$CASE_ID"
echo 'BIOS_HEALTH=PASS'
echo 'BIOS_READINESS=PASS'
echo 'BIOS_AUTH_FAIL_CLOSED=PASS'
echo 'BIOS_PERSISTENCE_RESTART=PASS'
echo 'OPERATE_01_LOOP=PASS'
echo 'BIOS_PRODUCTION_ACCEPTANCE=PASS'
echo 'HIGHWAY_BIOS_DEPLOY_TASK=PASS'
