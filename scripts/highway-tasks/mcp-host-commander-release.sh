#!/usr/bin/env bash
set -euo pipefail

: "${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}"
: "${HIGHWAY_TASK_ID:?HIGHWAY_TASK_ID required}"
: "${HIGHWAY_INSTALL_DIR:?HIGHWAY_INSTALL_DIR required}"

SOURCE="$HIGHWAY_INSTALL_DIR/releases/$HIGHWAY_SOURCE_SHA"
test -d "$SOURCE"
test -f "$SOURCE/.highway-source-sha"
test "$(cat "$SOURCE/.highway-source-sha")" = "$HIGHWAY_SOURCE_SHA"
cd "$SOURCE"

bash -n scripts/mcp/release-host-commander-g19.sh
node --check scripts/mcp/patch-host-commander-runtime-g19.mjs
python3 -m py_compile \
  scripts/mcp/patch-host-commander-broker-g19.py \
  scripts/mcp/commander-supervisor-g18.py \
  scripts/mcp/test-host-commander-g19.py
python3 scripts/mcp/test-host-commander-g19.py
bash scripts/mcp/release-host-commander-g19.sh "$HIGHWAY_SOURCE_SHA"

echo "MCP_RELEASE_SHA=$HIGHWAY_SOURCE_SHA"
echo "MCP_HOST_COMMANDER_G19_RELEASE_HIGHWAY=PASS"
