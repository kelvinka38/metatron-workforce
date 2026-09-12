#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
LOG="$BUILD/install-broker-resilience-v2_3.log"
PIDFILE="$BUILD/install-broker-resilience-v2_3.pid"
INSTALLER="$BUILD/install-broker-resilience-v2_3.sh"
ROUTER_STATE=/var/lib/metatron-mcp/router/router.json

[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_3_DETACHED_REQUIRES_ROOT' >&2; exit 2; }
[ -r "$INSTALLER" ] || { echo 'BROKER_V2_3_INSTALLER_MISSING' >&2; exit 3; }

if [ -s "$PIDFILE" ]; then
  oldpid=$(cat "$PIDFILE" 2>/dev/null || true)
  case "$oldpid" in ''|*[!0-9]*) oldpid='';; esac
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
    echo "BROKER_V2_3_ALREADY_RUNNING pid=$oldpid log=$LOG"
    exit 0
  fi
fi

: > "$LOG"
chmod 600 "$LOG"

# Clean a canary orphaned by a previous interactive HUP before touching serving routers.
docker rm -f metatron-mcp-router-v23-canary >/dev/null 2>&1 || true

# The 4 GiB host cannot safely keep the legacy hot standby resident during the bootstrap.
# Pause only the router-declared standby; the active runtime and both serving routers stay up.
standby=$(python3 - "$ROUTER_STATE" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1])); print(d.get('standby','') or '')
except Exception:
    print('')
PY
)
if [ -n "$standby" ] && docker inspect "$standby" >/dev/null 2>&1; then
  echo "BROKER_V2_3_RESCUE_PAUSING_STANDBY=$standby" >> "$LOG"
  docker stop -t 15 "$standby" >> "$LOG" 2>&1 || {
    echo 'BROKER_V2_3_RESCUE_STANDBY_STOP_FAILED' >> "$LOG"
    exit 4
  }
fi

# Wait for kernel reclaim/swap pressure to settle before asking the public route to prove stability.
i=0
while [ "$i" -lt 45 ]; do
  mem=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
  swap=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
  echo "BROKER_V2_3_RESCUE_HEADROOM attempt=$i mem_available_kb=${mem:-0} swap_free_kb=${swap:-0}" >> "$LOG"
  if [ "${mem:-0}" -ge 786432 ] && [ "${swap:-0}" -ge 1048576 ]; then
    break
  fi
  i=$((i+1)); sleep 2
done
if [ "$i" -ge 45 ]; then
  echo 'BROKER_V2_3_RESCUE_HEADROOM_TIMEOUT' >> "$LOG"
  exit 5
fi

echo 'BROKER_V2_3_RESCUE_HEADROOM_PASS' >> "$LOG"
nohup /bin/sh "$INSTALLER" >> "$LOG" 2>&1 </dev/null &
pid=$!
printf '%s\n' "$pid" > "$PIDFILE"
chmod 600 "$PIDFILE"
echo "BROKER_V2_3_DETACHED_SCHEDULED pid=$pid log=$LOG standby_paused=${standby:-none}"
