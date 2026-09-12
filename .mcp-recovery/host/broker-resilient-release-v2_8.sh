set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
LOG=/var/log/metatron-ssh-mcp-upgrade.log
mkdir -p "$STATE" "$ROUTER_STATE/sessions"
chmod 700 "$STATE" "$ROUTER_STATE" "$ROUTER_STATE/sessions" 2>/dev/null || true
cd "$BUILD"
echo "[START_RESILIENT_V2_8] $(date -Is)" > "$LOG"

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
stable_health() {
  c="$1"; samples="${2:-5}"; delay="${3:-1}"; i=0
  while [ "$i" -lt "$samples" ]; do
    s=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
    [ "$s" = "running/healthy" ] || return 1
    i=$((i+1)); sleep "$delay"
  done
}
read_state_field() {
  field="$1"
  python3 - "$ROUTER_STATE/router.json" "$field" <<'PY'
import json,sys
try:
    d=json.load(open(sys.argv[1]))
    v=d.get(sys.argv[2],'')
except Exception:
    v=''
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
public_transport_once() {
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 4 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-release-transport-probe/2.8' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  [ "$code" = 200 ]
}
public_transport_hard_ok() {
  i=0
  while [ "$i" -lt 3 ]; do
    if public_transport_once; then return 0; fi
    i=$((i+1)); sleep 0.15
  done
  return 1
}
wait_resource_headroom() {
  min_kb="${1:-262144}"; limit="${2:-45}"; i=0
  while [ "$i" -lt "$limit" ]; do
    mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    swap_free_kb=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
    echo "RESOURCE_HEADROOM attempt=$i mem_available_kb=${mem_avail_kb:-0} swap_free_kb=${swap_free_kb:-0} min_kb=$min_kb" >> "$LOG"
    if [ "${mem_avail_kb:-0}" -ge "$min_kb" ] && [ "${swap_free_kb:-0}" -ge 1048576 ]; then return 0; fi
    i=$((i+1)); sleep 2
  done
  return 1
}
router_version() {
  docker exec "$1" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.stdout.write(String(j.version||''))).catch(()=>process.exit(3))" 2>/dev/null || true
}
router_force_probe() {
  router="$1"; backend="$2"
  docker exec "$router" node - "$backend" <<'NODE' >/dev/null 2>&1
const b=process.argv[2];
fetch('http://127.0.0.1:3004/probe?backend='+encodeURIComponent(b),{method:'POST',signal:AbortSignal.timeout(3000)})
  .then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2));
NODE
}
force_probe_both() {
  backend="$1"; i=0
  while [ "$i" -lt 3 ]; do
    if router_force_probe metatron-mcp-router-a "$backend" && router_force_probe metatron-mcp-router-b "$backend"; then return 0; fi
    i=$((i+1)); sleep 0.5
  done
  return 1
}
router_transport_ok() {
  router="$1"
  docker exec "$router" node - <<'NODE' >/dev/null 2>&1
fetch('http://127.0.0.1:3002/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)})
  .then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2));
NODE
}
# METATRON_MCP_HOST_SESSION_AFFINITY_COUNT_V1
# Routers share this host-backed session directory. Read the authority directly instead of
# spawning node processes in router containers.
host_session_count() {
  backend="$1"
  python3 - "$ROUTER_STATE/sessions" "$backend" <<'PY'
import json, os, sys, time
root, backend = sys.argv[1:]
cutoff = int(time.time() * 1000) - (30 * 60 * 1000)
count = 0
try:
    entries = os.listdir(root)[:10000]
except Exception:
    print(999999); raise SystemExit(0)
for entry in entries:
    if not entry.endswith('.json'): continue
    try:
        item = json.load(open(os.path.join(root, entry)))
        if item.get('backend') == backend and int(item.get('updatedAt', 0)) >= cutoff:
            count += 1
    except Exception:
        continue
print(count)
PY
}
direct_candidate_soak() {
  c="$1"; i=0
  while [ "$i" -lt 8 ]; do
    docker exec "$c" node - <<'NODE' >/dev/null 2>&1 || return 1
fetch('http://127.0.0.1:3002/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)})
  .then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2));
NODE
    i=$((i+1)); sleep 1
  done
}
# METATRON_MCP_READINESS_CONTINUITY_PROBE_V1
# Continuous cutover/failover observation must not generate MCP stdio child-process pressure.
# Use the public /ready path for the window, then use tools/list only at functional checkpoints.
public_ready_once() {
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 3 https://ssh.metatron.vn/ready 2>/dev/null || true)
  [ "$code" = 200 ]
}
PROBE_PID=""
start_public_probe_window() {
  file="$1"; count="$2"; delay="$3"; : > "$file"
  (
    i=0
    while [ "$i" -lt "$count" ]; do
      if ! public_ready_once; then printf 'FAIL %s\n' "$(date -Is)" >> "$file"; fi
      i=$((i+1)); sleep "$delay"
    done
  ) </dev/null >/dev/null 2>&1 &
  PROBE_PID=$!
}
wait_probe_window() {
  pid="$1"
  wait "$pid" 2>/dev/null || true
}
count_failures() {
  n=$(grep -c '^FAIL ' "$1" 2>/dev/null || true)
  case "$n" in ''|*[!0-9]*) n=999;; esac
  printf '%s' "$n"
}

[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { echo '[ROUTER_A_V23_REQUIRED]' >> "$LOG"; exit 20; }
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { echo '[ROUTER_B_V23_REQUIRED]' >> "$LOG"; exit 21; }
[ -s "$STATE/generation" ] || { echo '[GENERATION_STATE_MISSING]' >> "$LOG"; exit 22; }
gen=$(cat "$STATE/generation" 2>/dev/null || echo 0)
case "$gen" in ''|*[!0-9]*) echo '[INVALID_GENERATION]' >> "$LOG"; exit 23;; esac
old_active=$(read_state_field active)
old_standby=$(read_state_field standby)
[ -n "$old_active" ] || { echo '[ACTIVE_MISSING]' >> "$LOG"; exit 24; }
wait_health "$old_active" 20 || { echo '[ACTIVE_NOT_HEALTHY]' >> "$LOG"; exit 25; }
stable_health "$old_active" 3 1 || { echo '[ACTIVE_NOT_STABLE]' >> "$LOG"; exit 26; }
# METATRON_MCP_EDGE_ONLY_BASELINE_V1
# Before the legacy standby is removed, prove only edge/router liveness. Functional MCP is
# checked after the cheap-readiness candidate is up and probe pressure has been reduced.
public_edge_live_ok() {
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 4 https://ssh.metatron.vn/live 2>/dev/null || true)
  [ "$code" = 200 ]
}
public_edge_live_ok || { echo '[BASELINE_EDGE_LIVE_FAILED]' >> "$LOG"; exit 27; }
echo 'BASELINE_EDGE_LIVE_PASS' >> "$LOG"

next=$((gen+1))
candidate="metatron-mcp-runtime-g${next}"
image="metatron-ssh-mcp-runtime:g${next}"
docker image inspect "$image" >/dev/null 2>&1 || { echo "[PREBUILT_IMAGE_REQUIRED] image=$image" >> "$LOG"; exit 30; }
artifact_id=$(docker image inspect "$image" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$artifact_id" ] || { echo '[PREBUILT_IMAGE_ID_MISSING]' >> "$LOG"; exit 31; }
echo "PREBUILT_ARTIFACT=$image id=$artifact_id" >> "$LOG"

lease=$(cat "$STATE/release-owner" 2>/dev/null || true)
fence() { [ "$(cat "$STATE/release-owner" 2>/dev/null || true)" = "$lease" ] && [ -n "$lease" ]; }
fence || { echo '[FENCE_MISSING_AT_RELEASE_START]' >> "$LOG"; exit 95; }

paused_old_standby=""
restore_pre_release() {
  write_state "$gen" "$old_active" "$old_standby" ""
  docker start "$old_active" >/dev/null 2>&1 || true
  if [ -n "$paused_old_standby" ]; then docker start "$paused_old_standby" >/dev/null 2>&1 || true; fi
  docker rm -f "$candidate" >/dev/null 2>&1 || true
}
abort_release() {
  code="$1"; marker="$2"
  echo "$marker" >> "$LOG"
  restore_pre_release
  exit "$code"
}

if [ -n "$old_standby" ] && docker inspect "$old_standby" >/dev/null 2>&1; then
  sessions=$(host_session_count "$old_standby")
  echo "PRIOR_STANDBY_SESSIONS backend=$old_standby count=$sessions" >> "$LOG"
  [ "$sessions" -eq 0 ] || { echo '[PRIOR_STANDBY_NOT_DRAINED]' >> "$LOG"; exit 32; }
  st=$(docker inspect "$old_standby" --format '{{.State.Status}}' 2>/dev/null || true)
  if [ "$st" = running ]; then
    docker stop -t 5 "$old_standby" >> "$LOG" 2>&1 || true
    paused_old_standby="$old_standby"
  fi
fi

docker rm -f "$candidate" >/dev/null 2>&1 || true
wait_resource_headroom 262144 60 || abort_release 33 '[RESOURCE_HEADROOM_TIMEOUT]'

docker run -d --name "$candidate" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$image" >> "$LOG" 2>&1
wait_health "$candidate" 90 || abort_release 34 '[CANDIDATE_NOT_READY]'
stable_health "$candidate" 5 1 || abort_release 35 '[CANDIDATE_HEALTH_UNSTABLE]'
direct_candidate_soak "$candidate" || abort_release 36 '[CANDIDATE_DIRECT_SOAK_FAILED]'
echo "CANDIDATE_READY_AND_SOAKED=$candidate" >> "$LOG"
fence || abort_release 96 '[FENCE_LOST_AFTER_CANDIDATE]'

# Prewarm candidate as standby while old ACTIVE continues to serve.
write_state "$gen" "$old_active" "$candidate" ""
force_probe_both "$candidate" || abort_release 37 '[CANDIDATE_FORCE_PROBE_FAILED]'
router_transport_ok metatron-mcp-router-a || abort_release 38 '[PREPROMOTION_ROUTER_A_FAILED]'
router_transport_ok metatron-mcp-router-b || abort_release 39 '[PREPROMOTION_ROUTER_B_FAILED]'
public_transport_hard_ok || abort_release 40 '[PREPROMOTION_PUBLIC_FAILED]'
echo "CANDIDATE_PREWARM_PASS standby=$candidate" >> "$LOG"
fence || abort_release 97 '[FENCE_LOST_BEFORE_PROMOTION]'

# Atomic promotion: candidate becomes ACTIVE; previous ACTIVE remains live as STANDBY.
# This preserves session affinity and makes drain real instead of killing the old runtime.
cutover_log="$STATE/release-cutover-${next}.log"
start_public_probe_window "$cutover_log" 40 0.25
cutover_pid="$PROBE_PID"
write_state "$next" "$candidate" "$old_active" ""
force_probe_both "$candidate" || abort_release 41 '[POST_PROMOTION_CANDIDATE_PROBE_FAILED]'
force_probe_both "$old_active" || abort_release 42 '[POST_PROMOTION_STANDBY_PROBE_FAILED]'
wait_probe_window "$cutover_pid"
cutover_failures=$(count_failures "$cutover_log")
echo "CUTOVER_HARD_FAILURES=$cutover_failures" >> "$LOG"
[ "$cutover_failures" -eq 0 ] || abort_release 43 '[ZERO_DOWNTIME_CUTOVER_FAILED]'
public_transport_hard_ok || abort_release 44 '[POST_PROMOTION_PUBLIC_FAILED]'
router_transport_ok metatron-mcp-router-a || abort_release 45 '[POST_PROMOTION_ROUTER_A_FAILED]'
router_transport_ok metatron-mcp-router-b || abort_release 46 '[POST_PROMOTION_ROUTER_B_FAILED]'
echo "ZERO_DOWNTIME_CUTOVER_PASS active=$candidate standby=$old_active" >> "$LOG"

# Runtime failover acceptance. Stop ACTIVE only after STANDBY is known healthy.
failover_log="$STATE/runtime-failover-${next}.log"
start_public_probe_window "$failover_log" 40 0.25
failover_pid="$PROBE_PID"
docker stop -t 1 "$candidate" >> "$LOG" 2>&1 || true
sleep 1
public_transport_hard_ok || { docker start "$candidate" >/dev/null 2>&1 || true; abort_release 47 '[RUNTIME_FAILOVER_PUBLIC_FAILED]'; }
router_transport_ok metatron-mcp-router-a || { docker start "$candidate" >/dev/null 2>&1 || true; abort_release 48 '[RUNTIME_FAILOVER_ROUTER_A_FAILED]'; }
router_transport_ok metatron-mcp-router-b || { docker start "$candidate" >/dev/null 2>&1 || true; abort_release 49 '[RUNTIME_FAILOVER_ROUTER_B_FAILED]'; }
docker start "$candidate" >> "$LOG" 2>&1 || true
wait_health "$candidate" 90 || abort_release 50 '[ACTIVE_RESTART_FAILED_AFTER_FAILOVER_TEST]'
stable_health "$candidate" 3 1 || abort_release 51 '[ACTIVE_RESTART_UNSTABLE_AFTER_FAILOVER_TEST]'
force_probe_both "$candidate" || true
wait_probe_window "$failover_pid"
runtime_failures=$(count_failures "$failover_log")
echo "RUNTIME_FAILOVER_HARD_FAILURES=$runtime_failures" >> "$LOG"
[ "$runtime_failures" -eq 0 ] || abort_release 52 '[RUNTIME_FAILOVER_ACCEPTANCE_FAILED]'
echo 'RUNTIME_FAILOVER_ACCEPTANCE_PASS' >> "$LOG"

# Connector-process failure-domain acceptance.
tunnel_log="$STATE/tunnel-failover-${next}.log"
start_public_probe_window "$tunnel_log" 60 0.25
tunnel_pid="$PROBE_PID"
docker stop -t 5 source-cloudflared-1 >> "$LOG" 2>&1 || true
sleep 2
public_transport_hard_ok || { docker start source-cloudflared-1 >/dev/null 2>&1 || true; abort_release 53 '[PRIMARY_TUNNEL_FAILOVER_FAILED]'; }
docker start source-cloudflared-1 >> "$LOG" 2>&1 || true
sleep 3
docker stop -t 5 metatron-cloudflared-ha >> "$LOG" 2>&1 || true
sleep 2
public_transport_hard_ok || { docker start metatron-cloudflared-ha >/dev/null 2>&1 || true; abort_release 54 '[HA_TUNNEL_FAILOVER_FAILED]'; }
docker start metatron-cloudflared-ha >> "$LOG" 2>&1 || true
wait_probe_window "$tunnel_pid"
tunnel_failures=$(count_failures "$tunnel_log")
echo "TUNNEL_FAILOVER_HARD_FAILURES=$tunnel_failures" >> "$LOG"
[ "$tunnel_failures" -eq 0 ] || abort_release 55 '[TUNNEL_FAILOVER_ACCEPTANCE_FAILED]'
echo 'TUNNEL_FAILOVER_ACCEPTANCE_PASS' >> "$LOG"

# METATRON_MCP_SAME_GENERATION_STANDBY_REPLACEMENT_V1
# The legacy active still carries deep /ready semantics. If it has no pinned sessions, replace it
# with a same-generation clone of the cheap-readiness candidate so steady-state HA is homogeneous.
standby_clone="${candidate}-standby"
old_active_sessions=$(host_session_count "$old_active")
echo "PREVIOUS_ACTIVE_SESSIONS backend=$old_active count=$old_active_sessions" >> "$LOG"
final_standby="$old_active"
if [ "$old_active_sessions" -eq 0 ]; then
  docker stop -t 5 "$old_active" >> "$LOG" 2>&1 || true
  docker rm -f "$standby_clone" >/dev/null 2>&1 || true
  wait_resource_headroom 262144 60 || { docker start "$old_active" >/dev/null 2>&1 || true; abort_release 56 '[STANDBY_REPLACEMENT_HEADROOM_FAILED]'; }
  docker run -d --name "$standby_clone" --restart unless-stopped     --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next"     --memory 512m --memory-swap 768m --pids-limit 256     --network metatron-gateway-online --add-host host.docker.internal:host-gateway     -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$image" >> "$LOG" 2>&1
  wait_health "$standby_clone" 90 || { docker start "$old_active" >/dev/null 2>&1 || true; abort_release 57 '[STANDBY_CLONE_NOT_READY]'; }
  stable_health "$standby_clone" 5 1 || { docker start "$old_active" >/dev/null 2>&1 || true; abort_release 58 '[STANDBY_CLONE_UNSTABLE]'; }
  write_state "$next" "$candidate" "$standby_clone" ""
  force_probe_both "$standby_clone" || abort_release 59 '[STANDBY_CLONE_PROBE_FAILED]'
  public_transport_hard_ok || abort_release 60 '[STANDBY_CLONE_PUBLIC_FAILED]'
  final_standby="$standby_clone"
  docker rm "$old_active" >/dev/null 2>&1 || true
  echo 'PREVIOUS_ACTIVE_DRAIN_COMPLETE replacement=same-generation-hotfix-standby' >> "$LOG"
else
  write_state "$next" "$candidate" "$old_active" ""
  echo "PREVIOUS_ACTIVE_DRAIN_PENDING sessions=$old_active_sessions" >> "$LOG"
fi

# Commit generation only after runtime + connector failover acceptance and standby convergence.
printf '%s\n' "$next" > "$STATE/generation"
chmod 600 "$STATE/generation" 2>/dev/null || true
fence || abort_release 99 '[FENCE_LOST_AT_COMMIT]'
public_transport_hard_ok || abort_release 61 '[FINAL_PUBLIC_TRANSPORT_FAILED]'
if [ -n "$old_standby" ] && [ "$old_standby" != "$old_active" ] && [ "$old_standby" != "$final_standby" ]; then docker rm -f "$old_standby" >/dev/null 2>&1 || true; fi
if [ "$old_active_sessions" -ne 0 ]; then
  echo "[DONE_RESILIENT_V2_8_DRAIN_PENDING] generation=$next active=$candidate standby=$final_standby artifact=$artifact_id $(date -Is)" >> "$LOG"
  exit 62
fi
echo "DRAIN_POLICY=same-generation-hotfix-pair prior_runtime_retired_only_when_host_session_count_zero" >> "$LOG"
echo "[DONE_RESILIENT_V2_8] generation=$next active=$candidate standby=$final_standby artifact=$artifact_id $(date -Is)" >> "$LOG"
