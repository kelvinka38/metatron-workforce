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
# Never run acceptance against a mutable farmer pilot. Create a fresh farm inside
# the synthetic acceptance Case and seed the exact reality this test asserts.
fixture=post("/farms/create",{
    "case_id":case_id,
    "name":"BIOS Aquaculture deploy acceptance",
    "location":{"country":"VN","province":"Bạc Liêu"}
})
farm_id=fixture["farm_id"]
post("/discovery/update",{
    "case_id":case_id,
    "farm_id":farm_id,
    "items":[
      {"semantic_key":"CD-01","original_value":"Đông Hải, Bạc Liêu","normalized_value":{"label":"Đông Hải, Bạc Liêu","country":"VN","province":"Bạc Liêu"},"field_state":"DECLARED"},
      {"semantic_key":"CD-02","original_value":"650 m2","normalized_value":{"value":650,"unit":"m2"},"field_state":"DECLARED"},
      {"semantic_key":"CD-03","original_value":"Sông","normalized_value":"RIVER","field_state":"DECLARED"},
      {"semantic_key":"CD-04","original_value":"Nước lợ","normalized_value":"BRACKISH","field_state":"DECLARED"},
      {"semantic_key":"CD-05","original_value":"Nuôi thương phẩm","normalized_value":"GROW_OUT","field_state":"DECLARED"},
      {"semantic_key":"CD-06","original_value":"Cá nâu","normalized_value":"Scatophagus argus","field_state":"DECLARED","owner_lock":False},
      {"semantic_key":"CD-07","original_value":"500000000 VND","normalized_value":{"amount_minor":"500000000","currency":"VND"},"field_state":"DECLARED","owner_lock":True},
      {"semantic_key":"CD-08","original_value":"","normalized_value":"NONE_STATED","field_state":"DECLARED","owner_lock":True}
    ]
})
print("AQ_ACCEPTANCE_ISOLATED_FARM=PASS",farm_id)
generated=post("/scenarios/generate",{"case_id":case_id,"farm_id":farm_id,"refresh":True})
assert generated["candidate_count"]==3, generated
assert generated["reasoning_version"]=="aq-product-reasoning-4.0", generated
scenarios=post("/scenarios/list",{"case_id":case_id,"farm_id":farm_id})
assert len(scenarios)==3, scenarios
for sc in scenarios:
    post("/scenarios/evaluate",{"case_id":case_id,"scenario_id":sc["scenario_id"],"version":sc["version"]})
rec=post("/decision/recommend",{"case_id":case_id,"farm_id":farm_id})
assert rec["comparator_version"]=="aq-decision-comparator-1.2", rec
ws=post("/farmer/workspace",{"case_id":case_id,"farm_id":farm_id})
assert ws["reasoning_version"]=="aq-product-reasoning-4.0", ws
assert ws["proposal_count"]==3, ws
assert len(ws["scenarios"])==3, ws["scenarios"]
assert len(ws["unknowns"])<=8, ws["unknowns"]
props=[x["property"] for x in ws["unknowns"]]
assert len(props)==len(set(props)), props
farmer_resolution=" ".join(x.get("resolution_method","") for x in ws["unknowns"]).lower()
assert "obtain validated" not in farmer_resolution, farmer_resolution
assert "complete economic model" not in farmer_resolution, farmer_resolution
primary=next(x for x in ws["scenarios"] if x.get("proposal_role")=="PRIMARY")
assert primary["scientific_name"]=="Scatophagus argus", primary
trace={x.get("semantic") for x in primary.get("decision_trace") or []}
assert {"CD-01","CD-02","CD-03","CD-04","CD-05","CD-06","CD-07","CD-08"}.issubset(trace), trace
blueprint=primary.get("production_blueprint") or {}
assert (blueprint.get("area_basis") or {}).get("usable_area_m2")==650.0, blueprint
assert (blueprint.get("environment_strategy") or {}).get("water_source")=="RIVER", blueprint
integrated=[x for x in ws["scenarios"] if x.get("species_configuration")=="POLYCULTURE"]
assert integrated, ws["scenarios"]
assert any(" + " in x.get("species_name","") for x in integrated), integrated
assert all(x["eligibility"]=="CONDITIONAL" for x in integrated), integrated
assert all(x["recommendation_state"]=="PROVISIONAL" for x in integrated), integrated
assert all(int(x.get("blocking_unknowns") or 0)>0 for x in integrated), integrated
print("AQ_LIVE_THREE_PROPOSAL_REASONING=PASS",[x.get("species_name") for x in ws["scenarios"]])
print("AQ_LIVE_INTEGRATED_FAIL_CLOSED=PASS",[x.get("species_name") for x in integrated])
print("AQ_LIVE_UNKNOWN_DEDUP=PASS",props)
market=post("/farmer/market",{"case_id":case_id,"farm_id":farm_id})
scat_market=[x for x in market["evidence"] if x["product"]=="Cá nâu"]
assert not any((x.get("region") or {}).get("province") in {"Huế","Thừa Thiên Huế"} for x in scat_market), scat_market
print("AQ_LIVE_MARKET_GEOGRAPHY_GUARD=PASS")
print("AQ_LIVE_COMPARATOR_V12=PASS")
PY

python3 - <<'PY'
import json
d=json.load(open('/tmp/bios-ready.json'))
assert d["bundle_version"]=="1.2.1", d
assert d["counts"]["knowledge"]>=35, d
assert d["counts"]["market_evidence"]>=6, d
print("AQ_BUNDLE_1_2=PASS")
PY

curl -fsS -D /tmp/aq-decision-js.headers -o /tmp/aq-decision-app.js https://gate.metatron.vn/aquaculture/assets/app.js
grep -qi '^cache-control: no-store' /tmp/aq-decision-js.headers
grep -q 'Sản lượng tham chiếu' /tmp/aq-decision-app.js
grep -q 'CHƯA ĐỦ BẰNG CHỨNG ĐỂ KHUYẾN NGHỊ / CHỐT' /tmp/aq-decision-app.js
grep -q 'const ready=s.eligibility==="ELIGIBLE"&&Number(s.blocking_unknowns||0)===0&&s.recommendation_state==="FINAL_FOR_CURRENT_EVIDENCE"' /tmp/aq-decision-app.js
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
