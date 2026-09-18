#!/usr/bin/env bash
# Safe production smoke test. This script never stops or restarts Ollama.
# Fallback/deadline behavior is covered by server.test.js and may also be exercised
# against a separate canary node by overriding NODE_URL.
set -euo pipefail

AUTH="${1:?usage: smoke-test.sh <METATRON_COGNITION_AUTH value>}"
NODE_URL="${NODE_URL:-http://metatron-cognition-node:8091}"
COGNITION_URL="${NODE_URL%/}/v1/cognition"
HEALTH_URL="${NODE_URL%/}/healthz"
NETWORK="${NETWORK:-metatron-gateway-online}"

call() {
  docker run --rm --network "$NETWORK" curlimages/curl:latest     -sS -X POST "$COGNITION_URL"     -H "Authorization: Bearer $AUTH"     -H "Content-Type: application/json"     -d "$1"
}

echo "=== Health / immutable revision ==="
HEALTH=$(docker run --rm --network "$NETWORK" curlimages/curl:latest -sS "$HEALTH_URL")
echo "$HEALTH"
echo "$HEALTH" | grep -q '"status":"ok"' || { echo "FAIL: node unhealthy"; exit 1; }
echo "$HEALTH" | grep -q '"revision":"' || { echo "FAIL: revision missing"; exit 1; }

echo
echo "=== Primary cognition path ==="
RESULT=$(call '{"requestId":"smoke-primary","objective":"Return only: SMOKE_OK","context":"","requiredOutput":"one word"}')
echo "$RESULT"
echo "$RESULT" | grep -q '"providerUsed":"ollama"' || { echo "FAIL: ollama was not primary"; exit 1; }
echo "$RESULT" | grep -q '"fallbackOccurred":false' || { echo "FAIL: unexpected fallback"; exit 1; }

echo
echo "SAFE SMOKE TEST PASSED"
echo "Fallback/deadline tests: node --test deploy/cognition-node/server.test.js"
