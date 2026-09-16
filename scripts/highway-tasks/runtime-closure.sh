#!/usr/bin/env bash
set -euo pipefail

: "${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}"
: "${HIGHWAY_PARENT_TASK_ID:?HIGHWAY_PARENT_TASK_ID required}"
: "${METATRON_HIGHWAY_TOKEN:?METATRON_HIGHWAY_TOKEN required}"

# Logic lives in runtime_closure_check.py so it is unit-testable without a real Highway daemon or
# Docker. See that module's docstring for the fanout-dispatch-spread scoped fix (2026-09-16).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/runtime_closure_check.py"
