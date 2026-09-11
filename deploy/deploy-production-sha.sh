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

# Read one value without sourcing or printing the production env file. Explicit shell
# variables retain Compose precedence; otherwise the canonical production env file owns it.
read_env_value() {
  local key="$1"
  local file="$2"
  python3 - "$file" "$key" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
key = sys.argv[2]
value = ""
for raw in path.read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#'):
        continue
    if line.startswith('export '):
        line = line[7:].lstrip()
    prefix = key + '='
    if not line.startswith(prefix):
        continue
    value = line.split('=', 1)[1].strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        value = value[1:-1]
    break
print(value, end='')
PY
}

# Sandbox is fail-closed. Validate before build/container mutation.
if [[ ${METATRON_SANDBOX_TOKEN+x} == x ]]; then
  EFFECTIVE_SANDBOX_TOKEN="$METATRON_SANDBOX_TOKEN"
else
  EFFECTIVE_SANDBOX_TOKEN="$(read_env_value METATRON_SANDBOX_TOKEN "$ENV_FILE")"
fi
if [[ -z "$EFFECTIVE_SANDBOX_TOKEN" ]]; then
  echo "production preflight failed: METATRON_SANDBOX_TOKEN is required before container recreation" >&2
  exit 7
fi
unset EFFECTIVE_SANDBOX_TOKEN

# Repository Control Plane is an institutional Execution dependency, not Worker work.
# A repository-capable production rollout must never become healthy while the credential
# required by materialization/PR publication is absent. Do not print/hash the secret.
if [[ ${GITHUB_TOKEN+x} == x ]]; then
  EFFECTIVE_REPOSITORY_CREDENTIAL="$GITHUB_TOKEN"
else
  EFFECTIVE_REPOSITORY_CREDENTIAL="$(read_env_value GITHUB_TOKEN "$ENV_FILE")"
fi
if [[ -z "$EFFECTIVE_REPOSITORY_CREDENTIAL" ]]; then
  echo "production preflight failed: Repository Control Plane credential is required before container recreation" >&2
  exit 9
fi
unset EFFECTIVE_REPOSITORY_CREDENTIAL

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

SANDBOX_HEALTH="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' deploy-workforce-sandbox-1 2>/dev/null || true)"
if [[ "$SANDBOX_HEALTH" != "healthy" ]]; then
  echo "sandbox health check failed after deployment: status=$SANDBOX_HEALTH" >&2
  exit 8
fi

echo "WORKFORCE PRODUCTION DEPLOYMENT: PASS"
echo "sha=$SHA"
