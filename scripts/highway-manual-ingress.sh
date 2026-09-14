#!/usr/bin/env bash
set -euo pipefail

KIND="${1:?Highway task kind required}"
TARGET_SHA="${2:-}"
PRIORITY="${3:-50}"
shift 3 || true

: "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE required}"
: "${GITHUB_SHA:?GITHUB_SHA required}"
: "${GITHUB_RUN_ID:?GITHUB_RUN_ID required}"

bash highway/publish-release.sh "$GITHUB_WORKSPACE" "$GITHUB_SHA"

ENV_FILE="${METATRON_PRODUCTION_ENV_FILE:-$HOME/.metatron/config/workforce.env}"
test -r "$ENV_FILE"
test -r "$HOME/.metatron/highway/highway.env"
set -a
source "$ENV_FILE"
source "$HOME/.metatron/highway/highway.env"
set +a
bash "$HOME/.metatron/highway/current/ensure-running.sh"

CTL=(python3 "$HOME/.metatron/highway/current/highwayctl.py")
ARGS=(submit --kind "$KIND" --source-sha "$GITHUB_SHA" --priority "$PRIORITY")
ARGS+=(--dedupe-key "$KIND:${TARGET_SHA:-$GITHUB_SHA}:$GITHUB_RUN_ID")
if [ -n "$TARGET_SHA" ]; then
  [[ "$TARGET_SHA" =~ ^[0-9a-f]{40}$ ]]
  ARGS+=(--env "TARGET_SHA=$TARGET_SHA")
fi
for pair in "$@"; do
  ARGS+=(--env "$pair")
done

TASK=$("${CTL[@]}" "${ARGS[@]}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["task_id"])')
echo "HIGHWAY_MANUAL_TASK=$TASK"
echo "HIGHWAY_MANUAL_KIND=$KIND"
echo "HIGHWAY_MANUAL_SOURCE_SHA=$GITHUB_SHA"
echo "HIGHWAY_MANUAL_TARGET_SHA=${TARGET_SHA:-$GITHUB_SHA}"
"${CTL[@]}" get "$TASK"
echo 'HIGHWAY_MANUAL_INGRESS=PASS'
