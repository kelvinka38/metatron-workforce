#!/usr/bin/env bash
set -euo pipefail
export TARGET_SHA="${TARGET_SHA:-${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}}"
export GITHUB_WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
export GITHUB_RUN_ID="${GITHUB_RUN_ID:-$(date +%s)}"
export HIGHWAY_PROFILE=FULL
bash scripts/production-acceptance-highway.sh
echo 'PRODUCTION_HIGHWAY_FULL_TASK=PASS'
