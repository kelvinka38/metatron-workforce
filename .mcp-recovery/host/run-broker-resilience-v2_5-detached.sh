#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build
LOG="$BUILD/install-broker-resilience-v2_5.log"
PIDFILE="$BUILD/install-broker-resilience-v2_5.pid"
INSTALLER="$BUILD/install-broker-resilience-v2_5.sh"
[ "$(id -u)" -eq 0 ] || { echo 'BROKER_V2_5_DETACHED_REQUIRES_ROOT' >&2; exit 2; }
[ -r "$INSTALLER" ] || { echo 'BROKER_V2_5_INSTALLER_MISSING' >&2; exit 3; }
if [ -s "$PIDFILE" ]; then
  oldpid=$(cat "$PIDFILE" 2>/dev/null || true)
  case "$oldpid" in ''|*[!0-9]*) oldpid='';; esac
  if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
    echo "BROKER_V2_5_ALREADY_RUNNING pid=$oldpid log=$LOG"; exit 0
  fi
fi
: > "$LOG"; chmod 600 "$LOG"
nohup /bin/sh "$INSTALLER" >> "$LOG" 2>&1 </dev/null &
pid=$!
printf '%s\n' "$pid" > "$PIDFILE"; chmod 600 "$PIDFILE"
echo "BROKER_V2_5_DETACHED_SCHEDULED pid=$pid log=$LOG"
