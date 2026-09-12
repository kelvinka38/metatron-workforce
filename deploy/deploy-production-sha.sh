#!/usr/bin/env bash
set -euo pipefail

# Compatibility entrypoint only. Production mutation authority belongs exclusively to Highway.
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

INSTALL="${METATRON_HIGHWAY_INSTALL_DIR:-}"
if [[ -z "$INSTALL" ]]; then
  for candidate in /opt/metatron/highway "$HOME/.metatron/highway" /home/*/.metatron/highway; do
    if [[ -r "$candidate/highway.env" && -x "$candidate/current/highwayctl.py" ]]; then INSTALL="$candidate"; break; fi
  done
fi
if [[ -z "$INSTALL" || ! -r "$INSTALL/highway.env" || ! -x "$INSTALL/current/highwayctl.py" ]]; then
  echo "canonical Highway installation not found" >&2
  exit 4
fi

METATRON_HIGHWAY_INSTALL_DIR="$INSTALL" bash "$ROOT_DIR/highway/publish-release.sh" "$ROOT_DIR" "$SHA"

# Idempotent desired-state closure: if the exact immutable revision is already the running
# Workforce image, the deploy request is already satisfied. Do not create redundant build/deploy
# tasks or relabel a satisfied request as failure. Release publication above still verifies the
# immutable source snapshot before this fast path.
CURRENT_SHA="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' deploy-workforce-1 2>/dev/null | sed -n 's/^METATRON_COMMIT_SHA=//p' | tail -n1 || true)"
CURRENT_IMAGE="$(docker inspect -f '{{.Config.Image}}' deploy-workforce-1 2>/dev/null || true)"
if [[ "$CURRENT_SHA" == "$SHA" && "$CURRENT_IMAGE" == "metatron-workforce:$SHA" ]]; then
  echo "HIGHWAY_RELEASE_IDEMPOTENT_ALREADY_CURRENT=PASS"
  echo "LOCAL_DEPLOY_DELEGATED_TO_HIGHWAY=PASS"
  echo "PROD_WORKFORCE_SINGLE_MUTATION_AUTHORITY=PASS"
  echo "WORKFORCE PRODUCTION DEPLOYMENT: PASS"
  echo "sha=$SHA"
  exit 0
fi
set -a
# shellcheck disable=SC1090
source "$INSTALL/highway.env"
set +a
export METATRON_HIGHWAY_INSTALL_DIR="$INSTALL"
export METATRON_HIGHWAY_STATE_DIR="${METATRON_HIGHWAY_STATE_DIR:-$INSTALL/state}"
export METATRON_HIGHWAY_URL="${METATRON_HIGHWAY_URL:-http://127.0.0.1:${METATRON_HIGHWAY_PORT:-18090}}"
CTL="$INSTALL/current/highwayctl.py"
python3 "$CTL" health >/dev/null

submit_id() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)["task_id"])'
}
INVOCATION_ID="$(date +%s)-$$"
CORRELATION_ID="compat-release-$SHA-$INVOCATION_ID"
BUILD_JSON="$(python3 "$CTL" submit --kind workforce-build --source-sha "$SHA" --correlation-id "$CORRELATION_ID")"
BUILD_ID="$(printf '%s' "$BUILD_JSON" | submit_id)"
python3 "$CTL" wait "$BUILD_ID" --timeout 1500 --poll 1 >/dev/null
DEPLOY_JSON="$(python3 "$CTL" submit --kind workforce-deploy --source-sha "$SHA" --correlation-id "$CORRELATION_ID" --dependency "$BUILD_ID")"
DEPLOY_ID="$(printf '%s' "$DEPLOY_JSON" | submit_id)"
python3 "$CTL" wait "$DEPLOY_ID" --timeout 1200 --poll 1 >/dev/null

RUNNING_SHA="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' deploy-workforce-1 | sed -n 's/^METATRON_COMMIT_SHA=//p' | tail -n1)"
RUNNING_IMAGE="$(docker inspect -f '{{.Config.Image}}' deploy-workforce-1)"
if [[ "$RUNNING_SHA" != "$SHA" || "$RUNNING_IMAGE" != "metatron-workforce:$SHA" ]]; then
  echo "Highway deployment identity mismatch: expected=$SHA actual=$RUNNING_SHA/$RUNNING_IMAGE" >&2
  exit 6
fi

echo "LOCAL_DEPLOY_DELEGATED_TO_HIGHWAY=PASS"
echo "PROD_WORKFORCE_SINGLE_MUTATION_AUTHORITY=PASS"
echo "WORKFORCE PRODUCTION DEPLOYMENT: PASS"
echo "sha=$SHA"
