#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$BASE_DIR/.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="${METATRON_ENV_FILE:-$ROOT_DIR/.env}"
test -f "$ENV_FILE" || {
  echo "missing deployment env file: $ENV_FILE" >&2
  exit 1
}

test -f build/libs/metatron-workforce-0.1.0.jar || {
  echo 'missing build/libs/metatron-workforce-0.1.0.jar; run ./gradlew bootJar first' >&2
  exit 1
}

COMPOSE=(docker compose --env-file "$ENV_FILE" -f deploy/docker-compose.yml)
"${COMPOSE[@]}" config >/dev/null
"${COMPOSE[@]}" up -d --build

for i in $(seq 1 45); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/tmp/metatron-workforce-health.json; then
    break
  fi
  sleep 2
done

test -s /tmp/metatron-workforce-health.json
python3 - <<'PY'
import json
with open('/tmp/metatron-workforce-health.json') as f:
    data=json.load(f)
assert data.get('status') == 'UP', data
PY

echo 'WORKFORCE DEPLOYMENT GATE: PASS'
