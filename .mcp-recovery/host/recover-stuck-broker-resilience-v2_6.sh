#!/bin/sh
set -eu
LOCK=/var/lock/metatron-mcp-release.lock
LOG=/var/log/metatron-ssh-mcp-upgrade.log
AUDIT=/opt/metatron/ssh-mcp/build/recovery-v2_6-runtime.log

[ "$(id -u)" -eq 0 ] || { echo 'RECOVERY_REQUIRES_ROOT' >&2; exit 2; }

public_ready_once(){
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 4 https://ssh.metatron.vn/ready 2>/dev/null || true)
  [ "$code" = 200 ]
}
public_ready_retry(){
  i=0
  while [ "$i" -lt 12 ]; do
    if public_ready_once; then return 0; fi
    i=$((i+1)); sleep 0.5
  done
  return 1
}
public_mcp_once(){
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-recovery-functional-probe/2.6' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
public_mcp_retry(){
  i=0
  while [ "$i" -lt 8 ]; do
    if public_mcp_once; then return 0; fi
    i=$((i+1)); sleep 1
  done
  return 1
}
router_container_healthy(){
  c="$1"
  s=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
  [ "$s" = 'running/healthy' ]
}
router_ready_retry(){
  c="$1"; i=0
  while [ "$i" -lt 12 ]; do
    if docker exec "$c" node -e "fetch('http://127.0.0.1:3002/ready',{signal:AbortSignal.timeout(2500)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2))" >/dev/null 2>&1; then return 0; fi
    i=$((i+1)); sleep 0.5
  done
  return 1
}
runtime_healthy(){
  c="$1"
  s=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
  [ "$s" = 'running/healthy' ]
}

{
  echo "RECOVERY_AUDIT_START $(date -Is)"
  for c in metatron-mcp-runtime-g1 metatron-mcp-runtime-g2; do
    echo "=== $c INSPECT ==="
    docker inspect "$c" --format 'status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart_count={{.RestartCount}} started={{.State.StartedAt}} oom={{.State.OOMKilled}} pids={{.HostConfig.PidsLimit}}' 2>&1 || true
    echo "=== $c LOGS ==="
    docker logs --tail 160 "$c" 2>&1 || true
  done
} > "$AUDIT"
chmod 600 "$AUDIT" 2>/dev/null || true

# A stuck v2.6 probe storm can transiently make router /ready fail. For pre-recovery safety,
# require both stable router containers and at least one healthy runtime, not a single /ready sample.
router_container_healthy metatron-mcp-router-a || { echo 'RECOVERY_ROUTER_A_CONTAINER_NOT_HEALTHY' >&2; exit 4; }
router_container_healthy metatron-mcp-router-b || { echo 'RECOVERY_ROUTER_B_CONTAINER_NOT_HEALTHY' >&2; exit 5; }
healthy=0
for c in metatron-mcp-runtime-g1 metatron-mcp-runtime-g2; do
  if runtime_healthy "$c"; then healthy=$((healthy+1)); fi
done
[ "$healthy" -ge 1 ] || { echo 'RECOVERY_NO_HEALTHY_RUNTIME' >&2; exit 6; }
public_ready_retry || { echo 'RECOVERY_BASELINE_PUBLIC_READY_FAILED' >&2; exit 3; }

if flock -n "$LOCK" -c true >/dev/null 2>&1; then
  echo 'RECOVERY_NOT_NEEDED_RELEASE_LOCK_FREE'
  exit 0
fi

[ -r "$LOG" ] || { echo 'RECOVERY_UPGRADE_LOG_MISSING' >&2; exit 7; }
grep -q '^\[START_RESILIENT_V2_6\]' "$LOG" || { echo 'RECOVERY_NOT_V2_6_RELEASE' >&2; exit 8; }
grep -q '^FAST_HANDOFF_PREPARED candidate=metatron-mcp-runtime-g2$' "$LOG" || { echo 'RECOVERY_V2_6_NOT_AT_KNOWN_STUCK_BOUNDARY' >&2; exit 9; }
if grep -q '^\[DONE_RESILIENT_V2_6\]' "$LOG"; then
  echo 'RECOVERY_REFUSE_COMPLETED_RELEASE' >&2
  exit 10
fi

now=$(date +%s)
mtime=$(stat -c %Y "$LOG")
age=$((now-mtime))
[ "$age" -ge 300 ] || { echo "RECOVERY_REFUSE_LOG_NOT_STALE age_seconds=$age" >&2; exit 11; }

# start_new_session=True makes the release shell a session leader. Kill only that v2.6 process group.
sid=$(ps -eo pid=,sid=,args= | awk '/\/bin\/sh -lc/ && /START_RESILIENT_V2_6/ {print $2; exit}')
case "$sid" in ''|*[!0-9]*) echo 'RECOVERY_V2_6_SESSION_NOT_FOUND' >&2; exit 12;; esac

echo "RECOVERY_TERMINATING_STUCK_V2_6 sid=$sid age_seconds=$age"
kill -TERM "-$sid" 2>/dev/null || true
i=0
while [ "$i" -lt 12 ]; do
  if flock -n "$LOCK" -c true >/dev/null 2>&1; then break; fi
  i=$((i+1)); sleep 1
done
if ! flock -n "$LOCK" -c true >/dev/null 2>&1; then
  kill -KILL "-$sid" 2>/dev/null || true
  sleep 1
fi
flock -n "$LOCK" -c true >/dev/null 2>&1 || { echo 'RECOVERY_RELEASE_LOCK_STILL_BUSY' >&2; exit 13; }

# Let process pressure collapse before functional verification.
sleep 3
router_container_healthy metatron-mcp-router-a || { echo 'RECOVERY_POST_ROUTER_A_CONTAINER_FAILED' >&2; exit 14; }
router_container_healthy metatron-mcp-router-b || { echo 'RECOVERY_POST_ROUTER_B_CONTAINER_FAILED' >&2; exit 15; }
router_ready_retry metatron-mcp-router-a || { echo 'RECOVERY_POST_ROUTER_A_READY_FAILED' >&2; exit 16; }
router_ready_retry metatron-mcp-router-b || { echo 'RECOVERY_POST_ROUTER_B_READY_FAILED' >&2; exit 17; }
public_ready_retry || { echo 'RECOVERY_POST_PUBLIC_READY_FAILED' >&2; exit 18; }
public_mcp_retry || { echo 'RECOVERY_POST_PUBLIC_MCP_FAILED' >&2; exit 19; }

{
  echo "RECOVERY_AUDIT_END $(date -Is)"
  for c in metatron-mcp-runtime-g1 metatron-mcp-runtime-g2; do
    docker inspect "$c" --format 'post status={{.State.Status}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} restart_count={{.RestartCount}} started={{.State.StartedAt}} oom={{.State.OOMKilled}}' 2>&1 || true
  done
} >> "$AUDIT"
echo "RECOVERY_STUCK_V2_6_PASS topology_untouched=true root_cause=probe_pressure audit=$AUDIT"
