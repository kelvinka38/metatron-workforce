set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
LOG=/var/log/metatron-ssh-mcp-upgrade.log
mkdir -p "$STATE" "$ROUTER_STATE/sessions"
chmod 700 "$STATE" "$ROUTER_STATE" "$ROUTER_STATE/sessions" 2>/dev/null || true
cd "$BUILD"
echo "[START_RESILIENT_V2_5] $(date -Is)" > "$LOG"

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
    s=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
    case "$s" in running/healthy) return 0;; exited/*|dead/*) return 1;; esac
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
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-release-transport-probe/2.5' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
public_ready_eventually() {
  i=0
  while [ "$i" -lt 60 ]; do
    if curl -fsS --max-time 4 https://ssh.metatron.vn/ready >/dev/null 2>&1; then return 0; fi
    i=$((i+1)); sleep 0.25
  done
  return 1
}
wait_resource_headroom() {
  i=0
  while [ "$i" -lt 45 ]; do
    mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    swap_free_kb=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
    echo "RESOURCE_HEADROOM attempt=$i mem_available_kb=${mem_avail_kb:-0} swap_free_kb=${swap_free_kb:-0}" >> "$LOG"
    if [ "${mem_avail_kb:-0}" -ge 262144 ] && [ "${swap_free_kb:-0}" -ge 1048576 ]; then return 0; fi
    i=$((i+1)); sleep 2
  done
  return 1
}
router_health_cached() {
  router="$1"; backend="$2"
  docker exec "$router" node - "$backend" <<'NODE' 2>/dev/null
const b=process.argv[2];fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.exit(j.health?.[b]?.ok===true?0:1)).catch(()=>process.exit(2));
NODE
}
wait_router_cached_both() {
  backend="$1"; i=0
  while [ "$i" -lt 30 ]; do
    if router_health_cached metatron-mcp-router-a "$backend" && router_health_cached metatron-mcp-router-b "$backend"; then return 0; fi
    i=$((i+1)); sleep 1
  done
  return 1
}
direct_candidate_soak() {
  c="$1"; i=0
  while [ "$i" -lt 20 ]; do
    docker exec "$c" node - <<'NODE' >/dev/null 2>&1 || return 1
fetch('http://127.0.0.1:3002/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2));
NODE
    i=$((i+1))
  done
}
run_probe_window() {
  file="$1"; count="$2"; delay="$3"; : > "$file"
  (
    i=0
    while [ "$i" -lt "$count" ]; do
      if ! public_transport_ok; then printf 'FAIL %s\n' "$(date -Is)" >> "$file"; fi
      i=$((i+1)); sleep "$delay"
    done
  ) & echo $!
}
count_failures() {
  n=$(grep -c '^FAIL ' "$1" 2>/dev/null || true)
  case "$n" in ''|*[!0-9]*) n=999;; esac
  printf '%s' "$n"
}

[ -s "$STATE/generation" ] || { echo '[V2_5_REQUIRES_MIGRATED_TOPOLOGY]' >> "$LOG"; exit 20; }
gen=$(cat "$STATE/generation" 2>/dev/null || echo 0)
case "$gen" in ''|*[!0-9]*) echo '[INVALID_GENERATION]' >> "$LOG"; exit 21;; esac
old_active=$(read_state_field active)
old_standby=$(read_state_field standby)
[ -n "$old_active" ] || { echo '[ACTIVE_MISSING]' >> "$LOG"; exit 22; }
wait_health "$old_active" 20 || { echo '[ACTIVE_NOT_HEALTHY]' >> "$LOG"; exit 23; }
public_transport_ok || { echo '[BASELINE_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 24; }
echo 'BASELINE_PUBLIC_TRANSPORT_PASS' >> "$LOG"

next=$((gen+1))
candidate="metatron-mcp-runtime-g${next}"
standby_clone="metatron-mcp-runtime-g${next}-standby"
image="metatron-ssh-mcp-runtime:g${next}"
if ! docker image inspect "$image" >/dev/null 2>&1; then
  echo "[PREBUILT_IMAGE_REQUIRED] image=$image" >> "$LOG"; exit 30
fi
artifact_id=$(docker image inspect "$image" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$artifact_id" ] || { echo '[PREBUILT_IMAGE_ID_MISSING]' >> "$LOG"; exit 31; }
echo "PREBUILT_ARTIFACT=$image id=$artifact_id" >> "$LOG"

paused_old_standby=""
if [ -n "$old_standby" ] && docker inspect "$old_standby" >/dev/null 2>&1; then
  state=$(docker inspect "$old_standby" --format '{{.State.Status}}' 2>/dev/null || true)
  if [ "$state" = running ]; then
    docker stop -t 10 "$old_standby" >> "$LOG" 2>&1 || true
    paused_old_standby="$old_standby"
  fi
fi
wait_resource_headroom || { echo '[RESOURCE_HEADROOM_TIMEOUT]' >> "$LOG"; exit 26; }

lease=$(cat "$STATE/release-owner" 2>/dev/null || true)
fence() { [ "$(cat "$STATE/release-owner" 2>/dev/null || true)" = "$lease" ] && [ -n "$lease" ]; }
fence || { echo '[FENCE_MISSING_AT_RELEASE_START]' >> "$LOG"; exit 95; }

docker rm -f "$candidate" "$standby_clone" >/dev/null 2>&1 || true
docker run -d --name "$candidate" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$image" >> "$LOG" 2>&1
wait_health "$candidate" 90 || { echo '[CANDIDATE_NOT_READY]' >> "$LOG"; docker logs --tail 100 "$candidate" >> "$LOG" 2>&1 || true; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 32; }
direct_candidate_soak "$candidate" || { echo '[CANDIDATE_DIRECT_SOAK_FAILED]' >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 33; }
echo "CANDIDATE_READY_AND_SOAKED=$candidate" >> "$LOG"
fence || { echo '[FENCE_LOST_AFTER_CANDIDATE]' >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 96; }

# Two-phase promotion: expose candidate only as draining/prewarm so both stable routers learn its health.
write_state "$gen" "$old_active" "$old_standby" "$candidate"
wait_router_cached_both "$candidate" || {
  write_state "$gen" "$old_active" "$old_standby" ""
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[CANDIDATE_PREWARM_CACHE_FAILED]' >> "$LOG"; exit 34
}
prewarm_log="$STATE/release-prewarm-${next}.log"
prewarm_pid=$(run_probe_window "$prewarm_log" 50 0.12)
wait "$prewarm_pid" 2>/dev/null || true
prewarm_failures=$(count_failures "$prewarm_log")
echo "PREWARM_TRANSPORT_FAILURES=$prewarm_failures" >> "$LOG"
[ "$prewarm_failures" -eq 0 ] || {
  write_state "$gen" "$old_active" "$old_standby" ""
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[PREWARM_IMPACTED_PUBLIC_TRANSPORT]' >> "$LOG"; exit 35
}
fence || { echo '[FENCE_LOST_BEFORE_PROMOTION]' >> "$LOG"; exit 97; }

cutover_log="$STATE/release-cutover-${next}.log"
cutover_pid=$(run_probe_window "$cutover_log" 100 0.10)
write_state "$next" "$candidate" "$old_active" "$old_standby"
wait "$cutover_pid" 2>/dev/null || true
cutover_failures=$(count_failures "$cutover_log")
echo "CUTOVER_TRANSPORT_FAILURES=$cutover_failures" >> "$LOG"
if [ "$cutover_failures" -ne 0 ]; then
  write_state "$gen" "$old_active" "$old_standby" ""
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[ZERO_DOWNTIME_CUTOVER_FAILED]' >> "$LOG"; exit 36
fi
public_ready_eventually || {
  write_state "$gen" "$old_active" "$old_standby" ""
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[READINESS_CONVERGENCE_FAILED]' >> "$LOG"; exit 37
}
echo 'ZERO_DOWNTIME_CUTOVER_PASS' >> "$LOG"

# Retire old active from service only after promotion; replace it with same-generation standby clone.
docker stop -t 10 "$old_active" >> "$LOG" 2>&1 || true
wait_resource_headroom || true
docker run -d --name "$standby_clone" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$image" >> "$LOG" 2>&1
wait_health "$standby_clone" 90 || { echo '[STANDBY_CLONE_NOT_READY]' >> "$LOG"; docker start "$old_active" >/dev/null 2>&1 || true; write_state "$next" "$candidate" "$old_active" ""; exit 38; }
write_state "$next" "$candidate" "$standby_clone" "$old_active"
wait_router_cached_both "$standby_clone" || { echo '[STANDBY_PREWARM_CACHE_FAILED]' >> "$LOG"; exit 39; }
public_transport_ok || { echo '[POST_STANDBY_PUBLIC_FAILED]' >> "$LOG"; exit 40; }

echo "HA_PAIR_READY active=$candidate standby=$standby_clone" >> "$LOG"

# Runtime failover acceptance: kill ACTIVE while probing; v2.3 routers must retry/fail over to standby.
failover_log="$STATE/runtime-failover-${next}.log"
failover_pid=$(run_probe_window "$failover_log" 100 0.10)
docker stop -t 2 "$candidate" >> "$LOG" 2>&1 || true
sleep 2
docker start "$candidate" >> "$LOG" 2>&1 || true
wait_health "$candidate" 90 || { echo '[ACTIVE_RESTART_FAILED_AFTER_FAILOVER_TEST]' >> "$LOG"; exit 41; }
wait "$failover_pid" 2>/dev/null || true
runtime_failures=$(count_failures "$failover_log")
echo "RUNTIME_FAILOVER_TRANSPORT_FAILURES=$runtime_failures" >> "$LOG"
[ "$runtime_failures" -eq 0 ] || { echo '[RUNTIME_FAILOVER_ACCEPTANCE_FAILED]' >> "$LOG"; exit 42; }
echo 'RUNTIME_FAILOVER_ACCEPTANCE_PASS' >> "$LOG"

# Connector-process failure-domain acceptance: each connector must be removable while public MCP stays live.
tunnel_log="$STATE/tunnel-failover-${next}.log"
tunnel_pid=$(run_probe_window "$tunnel_log" 120 0.12)
docker stop -t 5 source-cloudflared-1 >> "$LOG" 2>&1 || true
sleep 3
public_transport_ok || { docker start source-cloudflared-1 >/dev/null 2>&1 || true; echo '[PRIMARY_TUNNEL_FAILOVER_FAILED]' >> "$LOG"; exit 43; }
docker start source-cloudflared-1 >> "$LOG" 2>&1 || true
sleep 4
docker stop -t 5 metatron-cloudflared-ha >> "$LOG" 2>&1 || true
sleep 3
public_transport_ok || { docker start metatron-cloudflared-ha >/dev/null 2>&1 || true; echo '[HA_TUNNEL_FAILOVER_FAILED]' >> "$LOG"; exit 44; }
docker start metatron-cloudflared-ha >> "$LOG" 2>&1 || true
wait "$tunnel_pid" 2>/dev/null || true
tunnel_failures=$(count_failures "$tunnel_log")
echo "TUNNEL_FAILOVER_TRANSPORT_FAILURES=$tunnel_failures" >> "$LOG"
[ "$tunnel_failures" -eq 0 ] || { echo '[TUNNEL_FAILOVER_ACCEPTANCE_FAILED]' >> "$LOG"; exit 45; }
echo 'TUNNEL_FAILOVER_ACCEPTANCE_PASS' >> "$LOG"

printf '%s\n' "$next" > "$STATE/generation"
chmod 600 "$STATE/generation" 2>/dev/null || true
write_state "$next" "$candidate" "$standby_clone" ""
docker rm "$old_active" >/dev/null 2>&1 || true
if [ -n "$old_standby" ] && [ "$old_standby" != "$old_active" ]; then docker rm "$old_standby" >/dev/null 2>&1 || true; fi
fence || { echo '[FENCE_LOST_AT_COMMIT]' >> "$LOG"; exit 99; }
public_transport_ok || { echo '[FINAL_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 46; }
echo "[DONE_RESILIENT_V2_5] generation=$next active=$candidate standby=$standby_clone artifact=$artifact_id $(date -Is)" >> "$LOG"
