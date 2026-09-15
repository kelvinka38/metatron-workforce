#!/usr/bin/env bash
set -euo pipefail

: "${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}"
: "${HIGHWAY_TASK_ID:?HIGHWAY_TASK_ID required}"

before_bytes="$(df -B1 --output=used / | tail -1 | tr -d ' ')"
echo "HOST_DISK_CLEANUP_BEFORE_BYTES=$before_bytes"

# Preserve the exact bounded policy of the previous scheduled workflow:
# apt cache, journal capped at 500 MB, dangling images, and BuildKit cache older than 24h.
# Never remove running/stopped containers, named volumes, or workspace/state data.
sudo apt-get clean || true
sudo journalctl --vacuum-size=500M || true
docker image prune -f
docker builder prune -f --filter until=24h

after_bytes="$(df -B1 --output=used / | tail -1 | tr -d ' ')"
reclaimed=0
if [[ "$before_bytes" =~ ^[0-9]+$ && "$after_bytes" =~ ^[0-9]+$ && "$before_bytes" -ge "$after_bytes" ]]; then
  reclaimed=$((before_bytes-after_bytes))
fi

echo "HOST_DISK_CLEANUP_AFTER_BYTES=$after_bytes"
echo "HOST_DISK_CLEANUP_RECLAIMED_BYTES=$reclaimed"
echo "HOST_DISK_CLEANUP_POLICY=apt-cache,journal<=500MB,dangling-images,buildkit>24h"
echo "HOST_DISK_CLEANUP_HIGHWAY=PASS"
