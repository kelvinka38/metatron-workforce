#!/usr/bin/env bash
set -euo pipefail
export TARGET_SHA="${TARGET_SHA:-${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}}"
export GITHUB_WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
export GITHUB_RUN_ID="${GITHUB_RUN_ID:-$(date +%s)}"
set -euo pipefail
CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$CID"
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
DEPLOYED=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' |
  sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
if [ -n "${TARGET_SHA:-}" ]; then test "$DEPLOYED" = "$TARGET_SHA"; fi
echo "WORKPLACE_CONTROL_ROOM_SHA=$DEPLOYED"

set -euo pipefail
LOCAL=$(mktemp)
PUBLIC=$(mktemp)
trap 'rm -f "$LOCAL" "$PUBLIC"' EXIT

curl -fsS --max-time 10 http://127.0.0.1:8080/workplace/ > "$LOCAL"
curl -fsS --proto '=https' --tlsv1.2 --max-time 20 https://gate.metatron.vn/workplace/ > "$PUBLIC"

for page in "$LOCAL" "$PUBLIC"; do
  grep -q 'METATRON WORKPLACE' "$page"
  grep -q 'data-view="workers"' "$page"
  grep -q 'data-view="tasks"' "$page"
  grep -q 'data-view="projects"' "$page"
  grep -q '/workplace/api/control-room' "$page"
  grep -q '/chat' "$page"
  grep -q '/profile' "$page"
  grep -q 'Runtime & Tools' "$page"
  grep -q 'Materialized Position Constitution' "$page"
  grep -q 'Success measures / KPI contract' "$page"
  grep -q 'Evidence-derived learning' "$page"
  grep -q 'Formal contextual Performance evaluation' "$page"
  grep -q 'Live cognition instructions' "$page"
  grep -q 'Worker-model completeness gaps' "$page"
  grep -q '/availability' "$page"
  grep -q '/control/' "$page"
  grep -q 'Evidence drill-down' "$page"
  ! grep -q 'Objectives & task execution' "$page"
done

echo 'WORKPLACE_CONTROL_ROOM_LOCAL_UI=PASS'
echo 'WORKPLACE_CONTROL_ROOM_PUBLIC_UI=PASS'

set -euo pipefail
assert_unauthorized() {
  local url="$1"
  local status
  status=$(curl -sS -o /tmp/workplace-control-room-unauth.json -w '%{http_code}' --max-time 10 "$url")
  test "$status" = 401
}

assert_unauthorized http://127.0.0.1:8080/workplace/api/control-room
assert_unauthorized https://gate.metatron.vn/workplace/api/control-room
assert_unauthorized http://127.0.0.1:8080/workplace/api/workers/WORKER-GATEWAY-DIRECTOR
assert_unauthorized http://127.0.0.1:8080/workplace/api/workers/WORKER-GATEWAY-DIRECTOR/profile
echo 'WORKPLACE_CONTROL_ROOM_AUTH_BOUNDARY=PASS'

set -euo pipefail
CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$CID"
docker exec "$CID" sh -c 'test -d /var/lib/metatron-workforce'
docker exec "$CID" sh -c 'mkdir -p /var/lib/metatron-workforce/workplace-worker-chat && test -w /var/lib/metatron-workforce/workplace-worker-chat'
echo 'WORKPLACE_WORKER_CHAT_PERSISTENCE_PATH=PASS'

# The canonical Gateway Director must have a durable Position operating constitution,
# not merely a role label/runtime profile or UI-only projection.
CONSTITUTION=/var/lib/metatron-workforce/worker-constitution-state.json
for i in $(seq 1 30); do
  if docker exec "$CID" sh -c "test -s '$CONSTITUTION'"; then break; fi
  sleep 1
done
docker exec "$CID" sh -c "test -s '$CONSTITUTION'"
docker exec "$CID" sh -c "grep -Fq 'position-contract:position:gateway-director:v1' '$CONSTITUTION'"
docker exec "$CID" sh -c "grep -Fq 'CONTINUOUS_ACCOUNTABILITY_WITH_DEMAND_DRIVEN_BOUNDED_EXECUTION' '$CONSTITUTION'"
docker exec "$CID" sh -c "grep -Fq 'gateway-user-smoothness' '$CONSTITUTION'"
docker exec "$CID" sh -c "grep -Fq 'gateway.operational.management' '$CONSTITUTION'"
docker exec "$CID" sh -c "grep -Fq 'MAJOR_INVESTMENT_OR_PROTECTED_AUTHORITY' '$CONSTITUTION'"
echo 'WORKER_CONSTITUTION_DURABILITY=PASS'
echo 'WORKER_CONSTITUTION_GATEWAY_DIRECTOR=PASS'
echo 'WORKPLACE_CONTROL_ROOM_PRODUCTION_ACCEPTANCE=PASS'
echo 'CONTROL_ROOM_HIGHWAY_TASK=PASS'
