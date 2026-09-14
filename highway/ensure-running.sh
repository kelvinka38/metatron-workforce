#!/usr/bin/env bash
set -euo pipefail

INSTALL="${METATRON_HIGHWAY_INSTALL_DIR:-$HOME/.metatron/highway}"
STATE="${METATRON_HIGHWAY_STATE_DIR:-$INSTALL/state}"
BASE_ENV="${METATRON_PRODUCTION_ENV_FILE:-$HOME/.metatron/config/workforce.env}"
ENV_FILE="$INSTALL/highway.env"
PID_FILE="$STATE/highwayd.pid"
LOG_FILE="$STATE/highwayd.log"
RESTART="${1:-}"

test -r "$BASE_ENV"
test -r "$ENV_FILE"
test -f "$INSTALL/current/highwayd.py"
mkdir -p "$STATE"

healthy() {
  curl -fsS --max-time 1 http://127.0.0.1:18090/health >/dev/null 2>&1
}

if [ "$RESTART" != "--restart" ] && healthy; then
  exit 0
fi

if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE" 2>/dev/null || true)
  if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
    kill "$OLD_PID" 2>/dev/null || true
    for _ in $(seq 1 30); do
      kill -0 "$OLD_PID" 2>/dev/null || break
      sleep .1
    done
    kill -9 "$OLD_PID" 2>/dev/null || true
  fi
fi

set -a
# shellcheck disable=SC1090
source "$BASE_ENV"
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
export METATRON_HIGHWAY_INSTALL_DIR="$INSTALL"
export METATRON_HIGHWAY_STATE_DIR="$STATE"
export METATRON_HIGHWAY_PORT="${METATRON_HIGHWAY_PORT:-18090}"
export METATRON_HIGHWAY_EXECUTORS="${METATRON_HIGHWAY_EXECUTORS:-4}"

# GitHub Actions kills orphan children that retain RUNNER_TRACKING_ID.
# Remove that marker before detaching so Highway remains the persistent execution plane.
unset RUNNER_TRACKING_ID || true
nohup python3 "$INSTALL/current/highwayd.py"   --install-dir "$INSTALL"   --state-dir "$STATE"   --port "$METATRON_HIGHWAY_PORT"   --executors "$METATRON_HIGHWAY_EXECUTORS"   >>"$LOG_FILE" 2>&1 </dev/null &
PID=$!
printf '%s\n' "$PID" > "$PID_FILE"

for _ in $(seq 1 80); do
  if healthy; then
    echo "HIGHWAY_DAEMON_PID=$PID"
    echo "HIGHWAY_DAEMON=RUNNING"
    exit 0
  fi
  if ! kill -0 "$PID" 2>/dev/null; then
    tail -n 100 "$LOG_FILE" >&2 || true
    echo "Highway daemon exited during startup" >&2
    exit 1
  fi
  sleep .1
done

tail -n 100 "$LOG_FILE" >&2 || true
echo "Highway daemon health timeout" >&2
exit 1
