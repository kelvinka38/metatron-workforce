#!/usr/bin/env bash
# Smoke test for the Metatron-owned cognition node's provider order (Phase 2, 2026-09-16).
#
# Proves, against the real running containers (no mocks -- this is an infra smoke test, not a unit
# test, since the node's only logic is "try providers in order and report which one answered"):
#   1. Ollama/qwen3:8b succeeds as PRIMARY when healthy (fallbackOccurred=false).
#   2. Gemini takes over correctly when Ollama is unavailable (fallbackOccurred=true), and Ollama is
#      restarted immediately after so the outage window is minimal.
#
# Requires: metatron-cognition-node and metatron-ollama containers already running on the
# metatron-gateway-online network, with METATRON_COGNITION_AUTH matching the node's configured value.
#
# Usage: ./smoke-test.sh <cognition-auth-token>
set -euo pipefail

AUTH="${1:?usage: smoke-test.sh <METATRON_COGNITION_AUTH value>}"
NETWORK="metatron-gateway-online"
NODE_URL="http://metatron-cognition-node:8091/v1/cognition"

call() {
  docker run --rm --network "$NETWORK" curlimages/curl:latest \
    -s -X POST "$NODE_URL" \
    -H "Authorization: Bearer $AUTH" \
    -H "Content-Type: application/json" \
    -d "$1"
}

echo "=== Test 1: Ollama primary (expect fallbackOccurred=false, providerUsed=ollama) ==="
RESULT_1=$(call '{"requestId":"smoke-primary","objective":"Return only: SMOKE_OK","context":"","requiredOutput":"one word"}')
echo "$RESULT_1"
echo "$RESULT_1" | grep -q '"providerUsed":"ollama"' && echo "PASS: ollama was primary" || { echo "FAIL: ollama was not primary"; exit 1; }
echo "$RESULT_1" | grep -q '"fallbackOccurred":false' && echo "PASS: no fallback occurred" || { echo "FAIL: unexpected fallback"; exit 1; }

echo
echo "=== Test 2: Gemini fallback when Ollama is down (expect fallbackOccurred=true) ==="
docker stop metatron-ollama >/dev/null
trap 'docker start metatron-ollama >/dev/null' EXIT
RESULT_2=$(call '{"requestId":"smoke-fallback","objective":"Return only: SMOKE_FALLBACK_OK","context":"","requiredOutput":"one word"}')
echo "$RESULT_2"
docker start metatron-ollama >/dev/null
trap - EXIT
echo "$RESULT_2" | grep -q '"providerUsed":"gemini"' && echo "PASS: gemini served the fallback" || { echo "FAIL: gemini did not serve the fallback (check GEMINI_API_KEY / quota)"; exit 1; }
echo "$RESULT_2" | grep -q '"fallbackOccurred":true' && echo "PASS: fallbackOccurred=true" || { echo "FAIL: fallbackOccurred not true"; exit 1; }

echo
echo "ALL SMOKE TESTS PASSED"
