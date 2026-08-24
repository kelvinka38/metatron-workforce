#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$BASE_DIR/.." && pwd)"
cd "$ROOT_DIR"

test -f build/libs/metatron-workforce-0.1.0.jar || {
  echo 'missing build/libs/metatron-workforce-0.1.0.jar; run ./gradlew bootJar first' >&2
  exit 1
}

docker compose -f deploy/docker-compose.yml config >/dev/null
docker compose -f deploy/docker-compose.yml up -d --build
cleanup(){ docker compose -f deploy/docker-compose.yml down >/dev/null 2>&1 || true; }
trap cleanup EXIT

for i in $(seq 1 30); do
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
