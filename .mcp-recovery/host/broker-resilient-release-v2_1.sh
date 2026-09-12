set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
LOG=/var/log/metatron-ssh-mcp-upgrade.log
mkdir -p "$STATE" "$ROUTER_STATE/sessions"
chmod 700 "$STATE" "$ROUTER_STATE" "$ROUTER_STATE/sessions" 2>/dev/null || true
cd "$BUILD"
echo "[START_RESILIENT_V2_1] $(date -Is)" > "$LOG"

echo -n "PRIVSEP_SEAL " >> "$LOG"
/usr/local/sbin/metatron-mcp-seal-root-key >> "$LOG" 2>&1
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
if ssh $SSH_OPTS root@127.0.0.1 true >/dev/null 2>&1; then echo "[SEAL_FAILED_ROOT_AUTH_STILL_ACCEPTED]" >> "$LOG"; exit 92; fi
probe=$(printf '%s' '{"op":"server_status","args":{}}' | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
echo "RESTRICTED_PROBE=$probe" >> "$LOG"
printf '%s' "$probe" | grep -q '"transport_user":"metatron-mcp"' || { echo "[RESTRICTED_PROBE_FAILED]" >> "$LOG"; exit 93; }

wait_health() {
  c="$1"; limit="${2:-90}"; i=0
  while [ "$i" -lt "$limit" ]; do
    state=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
    case "$state" in running/healthy) return 0;; exited/*|dead/*) return 1;; esac
    i=$((i+1)); sleep 1
  done
  return 1
}
read_state_field() {
  field="$1"
  python3 - "$ROUTER_STATE/router.json" "$field" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1])); v=d.get(sys.argv[2],'')
except Exception: v=''
print(v if isinstance(v,(str,int)) else '')
PY
}
write_state() {
  generation="$1"; active="$2"; standby="$3"; draining_csv="${4:-}"
  python3 - "$ROUTER_STATE/router.json" "$generation" "$active" "$standby" "$draining_csv" <<'PY'
import json,os,sys,tempfile
p,g,a,s,d=sys.argv[1:]
draining=[x for x in d.split(',') if x and x not in {a,s}]
obj={"generation":int(g),"active":a,"standby":s,"draining":draining}
fd,tmp=tempfile.mkstemp(prefix='.router.',dir=os.path.dirname(p),text=True)
try:
    with os.fdopen(fd,'w') as f:
        json.dump(obj,f,separators=(',',':')); f.flush(); os.fsync(f.fileno())
    os.chmod(tmp,0o600); os.replace(tmp,p)
finally:
    try:
        if os.path.exists(tmp): os.unlink(tmp)
    except OSError: pass
PY
}
public_transport_ok() {
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' \
    -H 'User-Agent: metatron-release-transport-probe/2.1' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
    https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
public_ready_eventually() {
  i=0
  while [ "$i" -lt 30 ]; do
    if curl -fsS --max-time 4 https://ssh.metatron.vn/ready >/dev/null 2>&1; then return 0; fi
    i=$((i+1)); sleep 0.25
  done
  return 1
}

[ -s "$STATE/generation" ] || { echo '[V2_1_REQUIRES_MIGRATED_TOPOLOGY]' >> "$LOG"; exit 20; }
gen=$(cat "$STATE/generation" 2>/dev/null || echo 0)
case "$gen" in ''|*[!0-9]*) echo '[INVALID_GENERATION]' >> "$LOG"; exit 21;; esac
old_active=$(read_state_field active)
old_standby=$(read_state_field standby)
[ -n "$old_active" ] || { echo '[ACTIVE_MISSING]' >> "$LOG"; exit 22; }
wait_health "$old_active" 15 || { echo '[ACTIVE_NOT_HEALTHY]' >> "$LOG"; exit 23; }
public_transport_ok || { echo '[BASELINE_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 24; }

next=$((gen+1))
lease="g${next}-$(python3 - <<'PY'
import secrets
print(secrets.token_hex(12))
PY
)"
printf '%s\n' "$lease" > "$STATE/release-owner"
chmod 600 "$STATE/release-owner" 2>/dev/null || true
fence() { [ "$(cat "$STATE/release-owner" 2>/dev/null || true)" = "$lease" ]; }

paused_standby=""
committed=0
restore_standby() {
  if [ -n "$paused_standby" ]; then
    docker start "$paused_standby" >/dev/null 2>&1 || true
    wait_health "$paused_standby" 60 || true
  fi
}
cleanup_release() {
  rc=$?
  if [ "$committed" -ne 1 ]; then restore_standby; fi
  exit "$rc"
}
trap cleanup_release EXIT INT TERM

if [ -n "$old_standby" ] && docker inspect "$old_standby" >/dev/null 2>&1; then
  echo "PAUSING_OLD_STANDBY=$old_standby" >> "$LOG"
  docker stop -t 15 "$old_standby" >> "$LOG" 2>&1 || { echo '[STANDBY_STOP_FAILED]' >> "$LOG"; exit 25; }
  paused_standby="$old_standby"
fi
sleep 2
mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
[ "${mem_avail_kb:-0}" -ge 524288 ] || { echo "[MEMORY_GUARD_FAILED] available_kb=${mem_avail_kb:-0}" >> "$LOG"; exit 26; }
avail_kb=$(df --output=avail -k / | tail -1 | tr -d ' ')
[ "${avail_kb:-0}" -ge 1572864 ] || { echo "[DISK_GUARD_FAILED] available_kb=${avail_kb:-0}" >> "$LOG"; exit 94; }

candidate="metatron-mcp-runtime-g${next}"
image="metatron-ssh-mcp-runtime:g${next}"
echo "RELEASE_GENERATION current=$gen candidate=$next lease=$lease" >> "$LOG"
if ! docker build -t "$image" . >> "$LOG" 2>&1; then echo '[BUILD_FAILED]' >> "$LOG"; exit 1; fi
fence || { echo '[FENCE_LOST_AFTER_BUILD]' >> "$LOG"; exit 95; }
docker rm -f "$candidate" >/dev/null 2>&1 || true
docker run -d --name "$candidate" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 768m --memory-swap 1024m --pids-limit 256 \
  --network metatron-gateway-online \
  --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro \
  "$image" >> "$LOG" 2>&1
if ! wait_health "$candidate" 90; then
  echo "[CANDIDATE_NOT_READY] $candidate" >> "$LOG"; docker logs --tail 120 "$candidate" >> "$LOG" 2>&1 || true; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 2
fi
fence || { echo '[FENCE_LOST_AFTER_CANDIDATE]' >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 96; }
echo "CANDIDATE_READY=$candidate" >> "$LOG"

probe_log="$STATE/release-transport-probe-${next}.log"
: > "$probe_log"
(
  i=0
  while [ "$i" -lt 40 ]; do
    if ! public_transport_ok; then printf 'FAIL %s\n' "$(date -Is)" >> "$probe_log"; fi
    i=$((i+1)); sleep 0.15
  done
) & probe_pid=$!
write_state "$next" "$candidate" "$old_active" "$old_standby"
sleep 1
wait "$probe_pid" 2>/dev/null || true
failures=$(grep -c '^FAIL ' "$probe_log" 2>/dev/null || true)
case "$failures" in ''|*[!0-9]*) failures=999;; esac
echo "CUTOVER_TRANSPORT_FAILURES=$failures" >> "$LOG"
if [ "$failures" -ne 0 ]; then
  write_state "$gen" "$old_active" "$old_standby" ""
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[ZERO_DOWNTIME_TRANSPORT_FAILED]' >> "$LOG"
  exit 12
fi
if ! public_ready_eventually; then
  write_state "$gen" "$old_active" "$old_standby" ""
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[READINESS_CONVERGENCE_FAILED]' >> "$LOG"
  exit 13
fi

printf '%s\n' "$next" > "$STATE/generation"
chmod 600 "$STATE/generation" 2>/dev/null || true
fence || { echo '[FENCE_LOST_AFTER_PROMOTION]' >> "$LOG"; exit 99; }
committed=1
trap - EXIT INT TERM
if [ -n "$paused_standby" ]; then docker rm "$paused_standby" >> "$LOG" 2>&1 || true; fi

public_transport_ok || { echo '[FINAL_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 14; }
echo "[DONE_RESILIENT_V2_1] generation=$next active=$candidate standby=$old_active $(date -Is)" >> "$LOG"
