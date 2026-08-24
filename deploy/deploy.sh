#!/usr/bin/env bash
set -euo pipefail
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$BASE_DIR/.." && pwd)"
cd "$ROOT_DIR"

ENV_FILE="${METATRON_ENV_FILE:-$ROOT_DIR/.env}"
TEMP_ENV=""
if [[ ! -f "$ENV_FILE" ]]; then
  if [[ "${METATRON_CI:-false}" != "true" ]]; then
    echo "missing deployment env file: $ENV_FILE" >&2
    exit 1
  fi
  TEMP_ENV="$ROOT_DIR/.env.ci"
  ENV_FILE="$TEMP_ENV"
  cat > "$ENV_FILE" <<'EOF'
METATRON_VERSION=0.1.0
METATRON_COMMIT_SHA=ci
METATRON_ENVIRONMENT=ci
TELEGRAM_BOT_TOKEN=
TELEGRAM_WEBHOOK_SECRET=
TELEGRAM_ALLOWED_USER_ID=
METATRON_ORGANIZATION_ID=
METATRON_GATEWAY_AUDIT_URL=
METATRON_GATEWAY_AUDIT_TOKEN=
OPENAI_API_KEY=
GEMINI_API_KEY=
ANTHROPIC_API_KEY=
GITHUB_TOKEN=
CLOUDFLARE_API_TOKEN=
CLOUDFLARE_ACCOUNT_ID=
HETZNER_API_TOKEN=
EOF
  trap 'rm -f "$TEMP_ENV"' EXIT
fi

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

"${COMPOSE[@]}" down --remove-orphans >/dev/null

echo 'WORKFORCE DEPLOYMENT GATE: PASS'
