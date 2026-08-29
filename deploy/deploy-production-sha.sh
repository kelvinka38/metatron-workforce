#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

SHA="${1:-}"
if [[ ! "$SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "usage: $0 <40-char-commit-sha>" >&2
  exit 2
fi
SHA="$(printf '%s' "$SHA" | tr 'A-F' 'a-f')"
HEAD_SHA="$(git rev-parse HEAD)"
if [[ "$HEAD_SHA" != "$SHA" ]]; then
  echo "requested SHA $SHA is not current local HEAD $HEAD_SHA" >&2
  exit 3
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "tracked worktree changes present; commit before production deployment" >&2
  exit 4
fi

ENV_FILE="${METATRON_ENV_FILE:-$ROOT_DIR/deploy/.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "missing production env file: $ENV_FILE" >&2
  exit 5
fi

./gradlew --no-daemon clean build

export METATRON_IMAGE_TAG="$SHA"
export METATRON_COMMIT_SHA="$SHA"
export METATRON_VERSION="${METATRON_VERSION:-0.1.0}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f deploy/docker-compose.yml)
"${COMPOSE[@]}" config >/dev/null
"${COMPOSE[@]}" up -d --build workforce

for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/tmp/metatron-workforce-health.json 2>/dev/null; then
    if python3 - <<'PY'
import json
with open('/tmp/metatron-workforce-health.json') as f:
    data=json.load(f)
raise SystemExit(0 if data.get('status') == 'UP' else 1)
PY
    then
      break
    fi
  fi
  sleep 2
done

curl -fsS http://127.0.0.1:8080/actuator/health >/tmp/metatron-workforce-health.json
python3 - <<'PY'
import json
with open('/tmp/metatron-workforce-health.json') as f:
    data=json.load(f)
assert data.get('status') == 'UP', data
PY

RUNNING_SHA="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' deploy-workforce-1 | sed -n 's/^METATRON_COMMIT_SHA=//p' | tail -n1)"
if [[ "$RUNNING_SHA" != "$SHA" ]]; then
  echo "running container commit mismatch: expected=$SHA actual=$RUNNING_SHA" >&2
  exit 6
fi

echo "WORKFORCE PRODUCTION DEPLOYMENT: PASS"
echo "sha=$SHA"
