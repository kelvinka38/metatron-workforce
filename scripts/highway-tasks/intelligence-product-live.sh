#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_RUN_ID:=${HIGHWAY_TASK_ID//[^0-9]/}}"
export GITHUB_RUN_ID
set -euo pipefail
chmod +x scripts/highway-intelligence-lane.sh
scripts/highway-intelligence-lane.sh
