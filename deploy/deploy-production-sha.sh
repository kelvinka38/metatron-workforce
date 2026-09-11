#!/usr/bin/env bash
set -euo pipefail

# Compatibility exact-SHA deployment lane. Canonical production release remains Highway.
# This script is intentionally independent of the caller's current branch/HEAD/worktree state:
# it reads one immutable commit from the canonical mirror and builds in a private deployment tree.
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SHA="${1:-}"
if [[ ! "$SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "usage: $0 <40-char-commit-sha>" >&2
  exit 2
fi
SHA="$(printf '%s' "$SHA" | tr 'A-F' 'a-f')"
RESOLVED="$(git -C "$ROOT_DIR" rev-parse "${SHA}^{commit}" 2>/dev/null || true)"
if [[ "$RESOLVED" != "$SHA" ]]; then
  echo "requested immutable commit is not present in canonical mirror: $SHA" >&2
  exit 3
fi

ENV_FILE="${METATRON_ENV_FILE:-$ROOT_DIR/deploy/.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "missing production env file: $ENV_FILE" >&2
  exit 5
fi

read_env_value() {
  local key="$1" file="$2"
  python3 - "$file" "$key" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1]); key = sys.argv[2]; value = ""
for raw in path.read_text().splitlines():
    line = raw.strip()
    if not line or line.startswith('#'): continue
    if line.startswith('export '): line = line[7:].lstrip()
    prefix = key + '='
    if not line.startswith(prefix): continue
    value = line.split('=', 1)[1].strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}: value = value[1:-1]
    break
print(value, end='')
PY
}

if [[ ${METATRON_SANDBOX_TOKEN+x} == x ]]; then EFFECTIVE_SANDBOX_TOKEN="$METATRON_SANDBOX_TOKEN"; else EFFECTIVE_SANDBOX_TOKEN="$(read_env_value METATRON_SANDBOX_TOKEN "$ENV_FILE")"; fi
if [[ -z "$EFFECTIVE_SANDBOX_TOKEN" ]]; then
  echo "production preflight failed: METATRON_SANDBOX_TOKEN is required before container recreation" >&2
  exit 7
fi
unset EFFECTIVE_SANDBOX_TOKEN

if [[ ${GITHUB_TOKEN+x} == x ]]; then EFFECTIVE_REPOSITORY_CREDENTIAL="$GITHUB_TOKEN"; else EFFECTIVE_REPOSITORY_CREDENTIAL="$(read_env_value GITHUB_TOKEN "$ENV_FILE")"; fi
if [[ -z "$EFFECTIVE_REPOSITORY_CREDENTIAL" ]]; then
  echo "production preflight failed: Repository Control Plane credential is required before container recreation" >&2
  exit 9
fi
unset EFFECTIVE_REPOSITORY_CREDENTIAL

DEPLOY_ROOT="${METATRON_LOCAL_DEPLOY_ROOT:-/tmp/metatron-workforce-deployments}"
mkdir -p "$DEPLOY_ROOT"
WORKSPACE="$(mktemp -d "$DEPLOY_ROOT/${SHA}.XXXXXX")"
cleanup() { rm -rf "$WORKSPACE"; }
trap cleanup EXIT

git -C "$ROOT_DIR" archive --format=tar "$SHA" | tar -xf - -C "$WORKSPACE"
printf '%s\n' "$SHA" > "$WORKSPACE/.metatron-deployment-source-sha"
test "$(cat "$WORKSPACE/.metatron-deployment-source-sha")" = "$SHA"
echo "LOCAL_DEPLOY_IMMUTABLE_SOURCE=PASS sha=$SHA"

(
  cd "$WORKSPACE"
  ./gradlew --no-daemon clean build
)

export METATRON_IMAGE_TAG="$SHA"
export METATRON_COMMIT_SHA="$SHA"
export METATRON_VERSION="${METATRON_VERSION:-0.1.0}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -p deploy -f "$WORKSPACE/deploy/docker-compose.yml")
"${COMPOSE[@]}" config >/dev/null
"${COMPOSE[@]}" build workforce workforce-sandbox
"${COMPOSE[@]}" up -d --no-build --force-recreate workforce-sandbox workforce

for _ in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:8080/actuator/health >/tmp/metatron-workforce-health.json 2>/dev/null; then
    if python3 - <<'PY'
import json
with open('/tmp/metatron-workforce-health.json') as f: data=json.load(f)
raise SystemExit(0 if data.get('status') == 'UP' else 1)
PY
    then break; fi
  fi
  sleep 2
done

curl -fsS http://127.0.0.1:8080/actuator/health >/tmp/metatron-workforce-health.json
python3 - <<'PY'
import json
with open('/tmp/metatron-workforce-health.json') as f: data=json.load(f)
assert data.get('status') == 'UP', data
PY

RUNNING_SHA="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' deploy-workforce-1 | sed -n 's/^METATRON_COMMIT_SHA=//p' | tail -n1)"
RUNNING_IMAGE="$(docker inspect -f '{{.Config.Image}}' deploy-workforce-1)"
if [[ "$RUNNING_SHA" != "$SHA" || "$RUNNING_IMAGE" != "metatron-workforce:$SHA" ]]; then
  echo "running Workforce identity mismatch: expected=$SHA/$SHA actual=$RUNNING_SHA/$RUNNING_IMAGE" >&2
  exit 6
fi

SANDBOX_HEALTH="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' deploy-workforce-sandbox-1 2>/dev/null || true)"
SANDBOX_IMAGE="$(docker inspect -f '{{.Config.Image}}' deploy-workforce-sandbox-1 2>/dev/null || true)"
if [[ "$SANDBOX_HEALTH" != "healthy" || "$SANDBOX_IMAGE" != "metatron-workforce-sandbox:$SHA" ]]; then
  echo "sandbox exact-SHA verification failed: health=$SANDBOX_HEALTH image=$SANDBOX_IMAGE" >&2
  exit 8
fi

echo "LOCAL_DEPLOY_SHARED_HEAD_INDEPENDENCE=PASS"
echo "WORKFORCE PRODUCTION DEPLOYMENT: PASS"
echo "sha=$SHA"
