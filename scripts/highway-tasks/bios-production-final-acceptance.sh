#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_RUN_ID:=${HIGHWAY_TASK_ID//[^0-9]/}}"
export GITHUB_RUN_ID
set -euo pipefail
BIOS_BASE="$HOME/.metatron/bios"
test -r "$BIOS_BASE/.env"
set -a; source "$BIOS_BASE/.env"; set +a
test -n "${BIOS_API_KEY:-}"

CID=$(docker ps --filter name=metatron-bios --format '{{.ID}}' | head -1)
test -n "$CID"
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
curl -fsS http://127.0.0.1:18080/health | grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"'
echo 'BIOS_LIVE_HEALTH=PASS'

echo '=== AUDIT DURABILITY AFTER RESTART ==='
docker exec -i "$CID" python - <<'PY'
import sqlite3
db=sqlite3.connect('/data/bios_runtime.db')
rows=db.execute("SELECT event_type,case_id FROM audit_events WHERE case_id IS NOT NULL ORDER BY id DESC").fetchall()
by_case={}
for event,case_id in rows:
    by_case.setdefault(case_id,set()).add(event)
complete=[case_id for case_id,events in by_case.items() if {'CASE_RUN','CASE_AUTHORIZE','CASE_OBSERVE'} <= events]
assert complete, f'no_complete_audit_chain:{by_case}'
case_id=complete[0]
case=db.execute('SELECT id,status,payload FROM cases WHERE id=?',(case_id,)).fetchone()
assert case is not None
assert case[1]=='MONITORING'
print('BIOS_AUDIT_CHAIN_CASE='+case_id)
print('BIOS_AUDIT_PERSISTENCE=PASS')
PY

echo '=== LIVE RATE LIMIT FROM ISOLATED GATEWAY CLIENT ==='
docker run --rm -i --network metatron-gateway-online \
  -e BIOS_API_KEY="$BIOS_API_KEY" python:3.12-slim python - <<'PY'
import os, urllib.request, urllib.error
key=os.environ['BIOS_API_KEY']
codes=[]
for _ in range(130):
    req=urllib.request.Request('http://bios:8080/health/ready',headers={'X-BIOS-API-Key':key})
    try:
        with urllib.request.urlopen(req,timeout=3) as r:
            codes.append(r.status)
    except urllib.error.HTTPError as e:
        codes.append(e.code)
assert 200 in codes, codes
assert 429 in codes, codes[-20:]
print('BIOS_RATE_LIMIT_429=PASS')
PY

echo '=== EXISTING GATEWAY REMAINS HEALTHY ==='
curl -fsS --proto '=https' --tlsv1.2 --max-time 10 https://gate.metatron.vn/telegram/health \
  | grep -q '"status":"UP"'
echo 'GATEWAY_POST_BIOS_HEALTH=PASS'

echo '=== BOUNDARY / HARDENING ==='
test "$(docker inspect "$CID" --format '{{.HostConfig.ReadonlyRootfs}}')" = true
test "$(docker inspect "$CID" --format '{{.HostConfig.Privileged}}')" = false
docker inspect "$CID" --format '{{json .NetworkSettings.Networks}}' | grep -q 'metatron-gateway-online'
docker inspect "$CID" --format '{{json .HostConfig.PortBindings}}' | grep -q '127.0.0.1'
echo 'BIOS_BOUNDARY_HARDENING=PASS'
echo 'BIOS_PRODUCTION_FINAL_ACCEPTANCE=PASS'
