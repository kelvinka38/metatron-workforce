set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
LOG=/var/log/metatron-ssh-mcp-upgrade.log
mkdir -p "$STATE" "$ROUTER_STATE/sessions"
chmod 700 "$STATE" "$ROUTER_STATE" "$ROUTER_STATE/sessions" 2>/dev/null || true
cd "$BUILD"
echo "[START_RESILIENT_V2_4] $(date -Is)" > "$LOG"

echo -n "PRIVSEP_SEAL " >> "$LOG"
/usr/local/sbin/metatron-mcp-seal-root-key >> "$LOG" 2>&1
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
if ssh $SSH_OPTS root@127.0.0.1 true >/dev/null 2>&1; then echo '[SEAL_FAILED_ROOT_AUTH_STILL_ACCEPTED]' >> "$LOG"; exit 92; fi
probe=$(printf '%s' '{"op":"server_status","args":{}}' | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
echo "RESTRICTED_PROBE=$probe" >> "$LOG"
printf '%s' "$probe" | grep -q '"transport_user":"metatron-mcp"' || { echo '[RESTRICTED_PROBE_FAILED]' >> "$LOG"; exit 93; }

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
os.makedirs(os.path.dirname(p),exist_ok=True)
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
    -H 'User-Agent: metatron-release-transport-probe/2.4' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
    https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
wait_resource_headroom() {
  i=0
  while [ "$i" -lt 45 ]; do
    mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    swap_free_kb=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
    load1=$(awk '{print $1}' /proc/loadavg)
    echo "RESOURCE_HEADROOM attempt=$i mem_available_kb=${mem_avail_kb:-0} swap_free_kb=${swap_free_kb:-0} load1=$load1" >> "$LOG"
    if [ "${mem_avail_kb:-0}" -ge 524288 ] && [ "${swap_free_kb:-0}" -ge 1048576 ]; then return 0; fi
    i=$((i+1)); sleep 2
  done
  return 1
}
router_candidate_cached() {
  router="$1"; candidate="$2"
  docker exec "$router" node - "$candidate" <<'NODE' 2>/dev/null
const backend=process.argv[2];
fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)})
 .then(r=>r.json()).then(j=>{
   const h=j?.health?.[backend];
   if(h?.ok===true){process.exit(0)}
   process.exit(2)
 }).catch(()=>process.exit(3));
NODE
}
wait_candidate_cached_both() {
  candidate="$1"; i=0
  while [ "$i" -lt 30 ]; do
    a=0; b=0
    router_candidate_cached metatron-mcp-router-a "$candidate" && a=1 || true
    router_candidate_cached metatron-mcp-router-b "$candidate" && b=1 || true
    echo "CANDIDATE_PREWARM attempt=$i router_a=$a router_b=$b candidate=$candidate" >> "$LOG"
    [ "$a" -eq 1 ] && [ "$b" -eq 1 ] && return 0
    i=$((i+1)); sleep 1
  done
  return 1
}
candidate_transport_soak() {
  candidate="$1"; i=0
  while [ "$i" -lt 12 ]; do
    docker exec "$candidate" node - <<'NODE' >/dev/null 2>&1 || return 1
const payload={jsonrpc:'2.0',id:1,method:'tools/list',params:{}};
fetch('http://127.0.0.1:3002/mcp',{
 method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},
 body:JSON.stringify(payload),signal:AbortSignal.timeout(4000)
}).then(r=>{if(r.status!==200)process.exit(2);return r.text()}).then(t=>{if(!t.includes('server_status'))process.exit(3)}).catch(()=>process.exit(4));
NODE
    i=$((i+1)); sleep 0.1
  done
  return 0
}

[ -s "$STATE/generation" ] || { echo '[V2_4_REQUIRES_MIGRATED_TOPOLOGY]' >> "$LOG"; exit 20; }
gen=$(cat "$STATE/generation" 2>/dev/null || echo 0)
case "$gen" in ''|*[!0-9]*) echo '[INVALID_GENERATION]' >> "$LOG"; exit 21;; esac
old_active=$(read_state_field active)
old_standby=$(read_state_field standby)
[ -n "$old_active" ] || { echo '[ACTIVE_MISSING]' >> "$LOG"; exit 22; }
wait_health "$old_active" 20 || { echo '[ACTIVE_NOT_HEALTHY]' >> "$LOG"; exit 23; }
# Require a stable baseline before any candidate work.
i=0
while [ "$i" -lt 12 ]; do public_transport_ok || { echo "[BASELINE_PUBLIC_TRANSPORT_FAILED] sample=$i" >> "$LOG"; exit 24; }; i=$((i+1)); sleep 0.15; done
echo 'BASELINE_PUBLIC_TRANSPORT_PASS' >> "$LOG"

next=$((gen+1))
lease="g${next}-$(python3 - <<'PY'
import secrets
print(secrets.token_hex(12))
PY
)"
printf '%s\n' "$lease" > "$STATE/release-owner"
chmod 600 "$STATE/release-owner" 2>/dev/null || true
fence() { [ "$(cat "$STATE/release-owner" 2>/dev/null || true)" = "$lease" ]; }

# Resource-safe release on the current 4 GB host: previous standby may stay stopped during build/cutover.
paused_standby=""
if [ -n "$old_standby" ] && docker inspect "$old_standby" >/dev/null 2>&1; then
  running=$(docker inspect "$old_standby" --format '{{.State.Running}}' 2>/dev/null || echo false)
  if [ "$running" = true ]; then
    echo "PAUSING_OLD_STANDBY=$old_standby" >> "$LOG"
    docker stop -t 15 "$old_standby" >> "$LOG" 2>&1 || { echo '[STANDBY_STOP_FAILED]' >> "$LOG"; exit 25; }
    paused_standby="$old_standby"
  fi
fi
sync || true
wait_resource_headroom || { echo '[RESOURCE_HEADROOM_TIMEOUT]' >> "$LOG"; exit 26; }
avail_kb=$(df --output=avail -k / | tail -1 | tr -d ' ')
[ "${avail_kb:-0}" -ge 1572864 ] || { echo "[DISK_GUARD_FAILED] available_kb=${avail_kb:-0}" >> "$LOG"; exit 94; }

candidate="metatron-mcp-runtime-g${next}"
image="metatron-ssh-mcp-runtime:g${next}"
echo "RELEASE_GENERATION current=$gen candidate=$next lease=$lease" >> "$LOG"

build_probe="$STATE/release-build-probe-${next}.log"; : > "$build_probe"
(
  while :; do
    if ! public_transport_ok; then printf 'FAIL %s\n' "$(date -Is)" >> "$build_probe"; fi
    sleep 0.5
  done
) & build_probe_pid=$!
if ! nice -n 10 docker build -t "$image" . >> "$LOG" 2>&1; then
  kill "$build_probe_pid" >/dev/null 2>&1 || true; wait "$build_probe_pid" 2>/dev/null || true
  echo '[BUILD_FAILED]' >> "$LOG"; exit 1
fi
kill "$build_probe_pid" >/dev/null 2>&1 || true; wait "$build_probe_pid" 2>/dev/null || true
build_failures=$(grep -c '^FAIL ' "$build_probe" 2>/dev/null || true)
case "$build_failures" in ''|*[!0-9]*) build_failures=999;; esac
echo "BUILD_TRANSPORT_FAILURES=$build_failures" >> "$LOG"
[ "$build_failures" -eq 0 ] || { echo '[BUILD_IMPACTED_PUBLIC_TRANSPORT]' >> "$LOG"; exit 27; }
fence || { echo '[FENCE_LOST_AFTER_BUILD]' >> "$LOG"; exit 95; }

docker rm -f "$candidate" >/dev/null 2>&1 || true
docker run -d --name "$candidate" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 640m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online \
  --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro \
  "$image" >> "$LOG" 2>&1
if ! wait_health "$candidate" 90; then
  echo "[CANDIDATE_NOT_READY] $candidate" >> "$LOG"; docker logs --tail 120 "$candidate" >> "$LOG" 2>&1 || true; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 2
fi
candidate_transport_soak "$candidate" || { echo '[CANDIDATE_DIRECT_TRANSPORT_SOAK_FAILED]' >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 28; }
echo "CANDIDATE_DIRECT_TRANSPORT_SOAK_PASS=$candidate" >> "$LOG"
fence || { echo '[FENCE_LOST_AFTER_CANDIDATE]' >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 96; }

# TWO-PHASE PROMOTION: expose candidate only as draining/prewarm while old active continues serving.
# Router v1 probes active, standby and draining backends every health interval, so this populates
# both routers' candidate health caches without routing normal traffic to the candidate.
write_state "$gen" "$old_active" "$old_standby" "$candidate"
if ! wait_candidate_cached_both "$candidate"; then
  write_state "$gen" "$old_active" "$old_standby" ""
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[CANDIDATE_PREWARM_FAILED]' >> "$LOG"; exit 29
fi
echo "CANDIDATE_PREWARM_PASS=$candidate" >> "$LOG"
# Soak public path after prewarm but before promotion.
prewarm_probe="$STATE/release-prewarm-probe-${next}.log"; : > "$prewarm_probe"
i=0
while [ "$i" -lt 30 ]; do if ! public_transport_ok; then printf 'FAIL %s\n' "$(date -Is)" >> "$prewarm_probe"; fi; i=$((i+1)); sleep 0.1; done
prewarm_failures=$(grep -c '^FAIL ' "$prewarm_probe" 2>/dev/null || true); case "$prewarm_failures" in ''|*[!0-9]*) prewarm_failures=999;; esac
echo "PREWARM_PUBLIC_TRANSPORT_FAILURES=$prewarm_failures" >> "$LOG"
[ "$prewarm_failures" -eq 0 ] || { write_state "$gen" "$old_active" "$old_standby" ""; docker rm -f "$candidate" >/dev/null 2>&1 || true; echo '[PREWARM_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 30; }
fence || { echo '[FENCE_LOST_BEFORE_PROMOTION]' >> "$LOG"; exit 98; }

cutover_probe="$STATE/release-cutover-probe-${next}.log"; : > "$cutover_probe"
(
  i=0
  while [ "$i" -lt 80 ]; do
    if ! public_transport_ok; then printf 'FAIL %s\n' "$(date -Is)" >> "$cutover_probe"; fi
    i=$((i+1)); sleep 0.1
  done
) & cutover_pid=$!
# Atomic promotion after both routers already know the candidate healthy.
write_state "$next" "$candidate" "$old_active" "$old_standby"
wait "$cutover_pid" 2>/dev/null || true
cutover_failures=$(grep -c '^FAIL ' "$cutover_probe" 2>/dev/null || true)
case "$cutover_failures" in ''|*[!0-9]*) cutover_failures=999;; esac
echo "CUTOVER_TRANSPORT_FAILURES=$cutover_failures" >> "$LOG"
if [ "$cutover_failures" -ne 0 ]; then
  write_state "$gen" "$old_active" "$old_standby" ""
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[ZERO_DOWNTIME_TRANSPORT_FAILED]' >> "$LOG"; exit 12
fi
# Require candidate to remain cached healthy after becoming active.
wait_candidate_cached_both "$candidate" || { write_state "$gen" "$old_active" "$old_standby" ""; docker rm -f "$candidate" >/dev/null 2>&1 || true; echo '[POST_PROMOTION_CACHE_FAILED]' >> "$LOG"; exit 31; }
public_transport_ok || { write_state "$gen" "$old_active" "$old_standby" ""; docker rm -f "$candidate" >/dev/null 2>&1 || true; echo '[FINAL_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 14; }
printf '%s\n' "$next" > "$STATE/generation"
chmod 600 "$STATE/generation" 2>/dev/null || true
fence || { echo '[FENCE_LOST_AFTER_PROMOTION]' >> "$LOG"; exit 99; }
# Previous standby is outside the new pair and can be retired; old active becomes live standby.
if [ -n "$old_standby" ] && [ "$old_standby" != "$old_active" ] && [ "$old_standby" != "$candidate" ]; then docker rm -f "$old_standby" >> "$LOG" 2>&1 || true; fi
echo "[DONE_RESILIENT_V2_4] generation=$next active=$candidate standby=$old_active $(date -Is)" >> "$LOG"
