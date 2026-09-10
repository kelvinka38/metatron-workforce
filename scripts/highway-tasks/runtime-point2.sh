#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_RUN_ID:=${HIGHWAY_TASK_ID//[^0-9]/}}"
export GITHUB_RUN_ID
set -euo pipefail
chmod +x scripts/runtime-conformance-point2.sh
scripts/runtime-conformance-point2.sh
