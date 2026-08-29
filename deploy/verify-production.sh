#!/usr/bin/env bash
set -euo pipefail

EXPECTED_SHA="${1:-}"
if [[ -n "$EXPECTED_SHA" && ! "$EXPECTED_SHA" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "expected SHA must be 40 hex characters" >&2
  exit 2
fi

curl -fsS http://127.0.0.1:8080/actuator/health >/tmp/metatron-workforce-health.json
python3 - <<'PY'
import json
with open('/tmp/metatron-workforce-health.json') as f:
    data=json.load(f)
assert data.get('status') == 'UP', data
PY

RUNNING_SHA="$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' deploy-workforce-1 | sed -n 's/^METATRON_COMMIT_SHA=//p' | tail -n1)"
IMAGE_REF="$(docker inspect -f '{{.Config.Image}}' deploy-workforce-1)"
if [[ -n "$EXPECTED_SHA" ]]; then
  EXPECTED_SHA="$(printf '%s' "$EXPECTED_SHA" | tr 'A-F' 'a-f')"
  if [[ "$RUNNING_SHA" != "$EXPECTED_SHA" ]]; then
    echo "production SHA mismatch: expected=$EXPECTED_SHA running=$RUNNING_SHA" >&2
    exit 3
  fi
fi

curl -fsS http://127.0.0.1:8080/telegram/health >/tmp/metatron-telegram-health.json
python3 - <<'PY'
import json
with open('/tmp/metatron-telegram-health.json') as f:
    data=json.load(f)
assert data.get('status') == 'UP', data
assert data.get('webhook') == 'ready', data
PY

echo "WORKFORCE PRODUCTION VERIFICATION: PASS"
echo "running_sha=$RUNNING_SHA"
echo "image=$IMAGE_REF"
