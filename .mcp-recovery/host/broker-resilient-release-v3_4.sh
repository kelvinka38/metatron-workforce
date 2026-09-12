# METATRON_MCP_DOCKER_EXEC_STDIN_V1
set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
LOG=/var/log/metatron-ssh-mcp-upgrade.log
mkdir -p "$STATE" "$ROUTER_STATE/sessions"
chmod 700 "$STATE" "$ROUTER_STATE" "$ROUTER_STATE/sessions" 2>/dev/null || true
cd "$BUILD"
echo "[START_RESILIENT_V3_4] $(date -Is)" > "$LOG"

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
# METATRON_MCP_AUTH_REQUIRED_RELEASE_ACCEPTANCE_V1
# During pre-promotion the old generation may still expose anonymous tools/list, so the
# compatibility probe accepts either legacy 200 or the new OAuth 401 challenge. After
# promotion, public_transport_stable uses the strict OAuth-boundary probe below.
public_transport_once() {
  hdr=$(mktemp /tmp/metatron-mcp-public.XXXXXX)
  code=$(curl -sS -D "$hdr" -o /dev/null -w '%{http_code}' --max-time 4 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-release-transport-probe/3.5' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  if [ "$code" = 200 ]; then rm -f "$hdr"; return 0; fi
  if [ "$code" = 401 ] && grep -qi '^www-authenticate:.*Bearer' "$hdr" && grep -qi 'resource_metadata=' "$hdr"; then rm -f "$hdr"; return 0; fi
  rm -f "$hdr"; return 1
}
public_oauth_boundary_once() {
  hdr=$(mktemp /tmp/metatron-mcp-oauth.XXXXXX)
  code=$(curl -sS -D "$hdr" -o /dev/null -w '%{http_code}' --max-time 4 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-release-oauth-probe/3.5' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' https://ssh.metatron.vn/mcp 2>/dev/null || true)
  ok=1
  [ "$code" = 401 ] || ok=0
  grep -qi '^www-authenticate:.*Bearer' "$hdr" || ok=0
  grep -qi 'resource_metadata=' "$hdr" || ok=0
  rm -f "$hdr"
  [ "$ok" -eq 1 ]
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
# METATRON_MCP_ADAPTIVE_MIGRATION_HEADROOM_V1
# The one-time legacy->cheap-readiness migration may begin with low free RAM because the
# legacy runtime itself is the pressure source. Permit startup when aggregate RAM+swap is
# healthy, but retain a hard minimum of 96 MiB immediately available RAM and require three
# consecutive safe samples. Later post-migration headroom gates remain strict at 256 MiB RAM.
wait_migration_headroom() {
  i=0; consecutive=0
  while [ "$i" -lt 45 ]; do
    mem_avail_kb=$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)
    swap_free_kb=$(awk '/SwapFree:/ {print $2}' /proc/meminfo)
    mem_avail_kb=${mem_avail_kb:-0}; swap_free_kb=${swap_free_kb:-0}
    aggregate_kb=$((mem_avail_kb + swap_free_kb))
    if [ "$mem_avail_kb" -ge 262144 ] && [ "$swap_free_kb" -ge 1048576 ]; then
      echo "MIGRATION_HEADROOM_PASS mode=normal mem_available_kb=$mem_avail_kb swap_free_kb=$swap_free_kb aggregate_kb=$aggregate_kb" >> "$LOG"
      return 0
    fi
    if [ "$mem_avail_kb" -ge 98304 ] && [ "$swap_free_kb" -ge 1572864 ] && [ "$aggregate_kb" -ge 2097152 ]; then
      consecutive=$((consecutive+1))
    else
      consecutive=0
    fi
    echo "MIGRATION_HEADROOM attempt=$i mem_available_kb=$mem_avail_kb swap_free_kb=$swap_free_kb aggregate_kb=$aggregate_kb consecutive_safe=$consecutive" >> "$LOG"
    if [ "$consecutive" -ge 3 ]; then
      echo "MIGRATION_HEADROOM_PASS mode=swap-backed mem_available_kb=$mem_avail_kb swap_free_kb=$swap_free_kb aggregate_kb=$aggregate_kb consecutive_safe=$consecutive" >> "$LOG"
      return 0
    fi
    i=$((i+1)); sleep 1
  done
  return 1
}

router_version() {
  docker exec "$1" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>process.stdout.write(String(j.version||''))).catch(()=>process.exit(3))" 2>/dev/null || true
}
router_force_probe() {
  router="$1"; backend="$2"
  docker exec -i "$router" node - "$backend" <<'NODE' >/dev/null 2>&1
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
router_transport_compatible() {
  router="$1"
  docker exec -i "$router" node - <<'NODE' >/dev/null 2>&1
fetch('http://127.0.0.1:3002/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)})
  .then(r=>{const w=String(r.headers.get('www-authenticate')||'');process.exit(r.status===200||(r.status===401&&/Bearer/i.test(w)&&/resource_metadata=/i.test(w))?0:1)}).catch(()=>process.exit(2));
NODE
}
router_transport_ok() {
  router="$1"
  docker exec -i "$router" node - <<'NODE' >/dev/null 2>&1
fetch('http://127.0.0.1:3002/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)})
  .then(r=>{const w=String(r.headers.get('www-authenticate')||'');process.exit(r.status===401&&/Bearer/i.test(w)&&/resource_metadata=/i.test(w)?0:1)}).catch(()=>process.exit(2));
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
# METATRON_MCP_AUTH_CROSS_REPLICA_ACCEPTANCE_V1
auth_state_hash() {
  [ -s "$BUILD/.runtime-auth-security.enc" ] || return 1
  sha256sum "$BUILD/.runtime-auth-security.enc" | awk '{print $1}'
}
runtime_auth_startup_acceptance() {
  c="$1"; label="$2"
  auth_log="$STATE/auth-startup-${next}-${label}.log"
  docker logs "$c" > "$auth_log" 2>&1 || return 1
  grep -q 'AUTH_CONTROL_STATE_HOST_LOAD_PASS' "$auth_log" || return 1
  grep -q 'AUTH_CONTROL_STATE_HOST_REUSE_NO_WRITE' "$auth_log" || return 1
  if grep -q 'AUTH_CONTROL_STATE_HOST_LOAD_FAILED' "$auth_log"; then return 1; fi
  if grep -q 'AUTH_CONTROL_STATE_HOST_SAVE_PASS' "$auth_log"; then return 1; fi
  echo "AUTH_REPLICA_STARTUP_READ_ONLY_PASS backend=$c label=$label" >> "$LOG"
}

direct_candidate_soak() {
  c="$1"; i=0
  while [ "$i" -lt 8 ]; do
    # Functional MCP registry acceptance bypasses only the local auth proxy and talks to
    # the private supergateway on 3003. Public/router acceptance separately proves 401 OAuth.
    docker exec -i "$c" node - <<'NODE' >/dev/null 2>&1 || return 1
fetch('http://127.0.0.1:3003/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)})
  .then(async r=>{const t=await r.text();process.exit(r.ok&&t.includes('server_status')?0:1)}).catch(()=>process.exit(2));
NODE
    i=$((i+1)); sleep 1
  done
}
# METATRON_MCP_READINESS_CONTINUITY_PROBE_V1
# METATRON_MCP_BOUNDED_SAFE_READINESS_RETRY_V1
# /ready is an idempotent GET. Record a first-attempt miss as SOFT, but call it a hard
# availability failure only when the same safe probe still fails after two bounded retries.
public_ready_raw_once() {
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 3 https://ssh.metatron.vn/ready 2>/dev/null || true)
  [ "$code" = 200 ]
}
PROBE_PID=""
start_public_probe_window() {
  file="$1"; count="$2"; delay="$3"; : > "$file"
  (
    i=0
    while [ "$i" -lt "$count" ]; do
      if ! public_ready_raw_once; then
        printf 'SOFT %s\n' "$(date -Is)" >> "$file"
        recovered=0; r=0
        while [ "$r" -lt 2 ]; do
          sleep 0.15
          if public_ready_raw_once; then recovered=1; break; fi
          r=$((r+1))
        done
        [ "$recovered" -eq 1 ] || printf 'FAIL %s\n' "$(date -Is)" >> "$file"
      fi
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
count_soft_retries() {
  n=$(grep -c '^SOFT ' "$1" 2>/dev/null || true)
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
standby_clone=""
restore_pre_release() {
  restore_standby="$old_standby"
  [ "$restore_standby" = "$candidate" ] && restore_standby=""
  write_state "$gen" "$old_active" "$restore_standby" ""
  [ -n "$standby_clone" ] && docker rm -f "$standby_clone" >/dev/null 2>&1 || true
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  docker start "$old_active" >/dev/null 2>&1 || true
  if [ -n "$paused_old_standby" ] && [ "$paused_old_standby" != "$candidate" ]; then docker start "$paused_old_standby" >/dev/null 2>&1 || true; fi
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
wait_migration_headroom || abort_release 33 '[MIGRATION_HEADROOM_TIMEOUT]'

auth_hash_before_candidate=$(auth_state_hash) || abort_release 73 '[AUTH_CONTROL_STATE_AUTHORITY_MISSING]'
echo "AUTH_CONTROL_STATE_HASH_BEFORE_CANDIDATE=$auth_hash_before_candidate" >> "$LOG"
docker run -d --name "$candidate" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$image" >> "$LOG" 2>&1
wait_health "$candidate" 90 || abort_release 34 '[CANDIDATE_NOT_READY]'
stable_health "$candidate" 5 1 || abort_release 35 '[CANDIDATE_HEALTH_UNSTABLE]'
direct_candidate_soak "$candidate" || abort_release 36 '[CANDIDATE_DIRECT_SOAK_FAILED]'
runtime_auth_startup_acceptance "$candidate" candidate || abort_release 74 '[AUTH_CANDIDATE_STARTUP_SINGLE_WRITER_FAILED]'
auth_hash_after_candidate=$(auth_state_hash) || abort_release 75 '[AUTH_CONTROL_STATE_AUTHORITY_MISSING_AFTER_CANDIDATE]'
echo "AUTH_CONTROL_STATE_HASH_AFTER_CANDIDATE=$auth_hash_after_candidate" >> "$LOG"
[ "$auth_hash_before_candidate" = "$auth_hash_after_candidate" ] || abort_release 76 '[AUTH_CONTROL_STATE_CHANGED_DURING_CANDIDATE_STARTUP]'
echo 'AUTH_CANDIDATE_AUTHORITY_HASH_STABLE_PASS' >> "$LOG"
echo "CANDIDATE_READY_AND_SOAKED=$candidate" >> "$LOG"
fence || abort_release 96 '[FENCE_LOST_AFTER_CANDIDATE]'

# Prewarm candidate as standby while old ACTIVE continues to serve.
write_state "$gen" "$old_active" "$candidate" ""
force_probe_both "$candidate" || abort_release 37 '[CANDIDATE_FORCE_PROBE_FAILED]'
router_transport_compatible metatron-mcp-router-a || abort_release 38 '[PREPROMOTION_ROUTER_A_FAILED]'
router_transport_compatible metatron-mcp-router-b || abort_release 39 '[PREPROMOTION_ROUTER_B_FAILED]'
public_transport_hard_ok || abort_release 40 '[PREPROMOTION_PUBLIC_FAILED]'
echo "CANDIDATE_PREWARM_PASS standby=$candidate" >> "$LOG"
fence || abort_release 97 '[FENCE_LOST_BEFORE_PROMOTION]'

# METATRON_MCP_SIMPLE_RELEASE_V1
# Normal runtime release is intentionally simple: qualify candidate, promote, stabilize,
# drain the old runtime, create a homogeneous standby, verify, then commit generation.
# Destructive runtime/tunnel failover certification belongs to a separate resilience lane.
public_transport_stable() {
  required="${1:-3}"; limit="${2:-20}"; delay="${3:-1}"; consecutive=0; i=0
  while [ "$i" -lt "$limit" ]; do
    if public_oauth_boundary_once; then
      consecutive=$((consecutive+1))
      [ "$consecutive" -ge "$required" ] && return 0
    else
      consecutive=0
    fi
    i=$((i+1)); sleep "$delay"
  done
  return 1
}
wait_sessions_drain() {
  backend="$1"; limit="${2:-60}"; i=0
  while [ "$i" -lt "$limit" ]; do
    sessions=$(host_session_count "$backend")
    echo "DRAIN_PROGRESS backend=$backend sessions=$sessions attempt=$i" >> "$LOG"
    [ "$sessions" -eq 0 ] && return 0
    i=$((i+1)); sleep 1
  done
  return 1
}
stable_running() {
  c="$1"; samples="${2:-3}"; delay="${3:-1}"; i=0
  while [ "$i" -lt "$samples" ]; do
    st=$(docker inspect "$c" --format '{{.State.Status}}' 2>/dev/null || true)
    [ "$st" = running ] || return 1
    i=$((i+1)); sleep "$delay"
  done
}

# Promote candidate while the previous active stays available as standby.
write_state "$next" "$candidate" "$old_active" ""
force_probe_both "$candidate" || abort_release 41 '[POST_PROMOTION_CANDIDATE_PROBE_FAILED]'
force_probe_both "$old_active" || abort_release 42 '[POST_PROMOTION_STANDBY_PROBE_FAILED]'
router_transport_ok metatron-mcp-router-a || abort_release 45 '[POST_PROMOTION_ROUTER_A_FAILED]'
router_transport_ok metatron-mcp-router-b || abort_release 46 '[POST_PROMOTION_ROUTER_B_FAILED]'
public_transport_stable 3 20 1 || abort_release 44 '[POST_PROMOTION_PUBLIC_STABILIZATION_FAILED]'
echo "PROMOTION_STABLE_PASS active=$candidate standby=$old_active" >> "$LOG"

# Drain the previous active before retirement. No destructive failover test is performed.
old_active_sessions=$(host_session_count "$old_active")
echo "PREVIOUS_ACTIVE_SESSIONS_BEFORE_DRAIN backend=$old_active count=$old_active_sessions" >> "$LOG"
if [ "$old_active_sessions" -ne 0 ]; then
  wait_sessions_drain "$old_active" 60 || abort_release 62 '[PREVIOUS_ACTIVE_DRAIN_TIMEOUT]'
fi

docker stop -t 5 "$old_active" >> "$LOG" 2>&1 || true
standby_clone="${candidate}-standby"
docker rm -f "$standby_clone" >/dev/null 2>&1 || true
wait_resource_headroom 262144 60 || abort_release 63 '[STANDBY_HEADROOM_FAILED]'
docker run -d --name "$standby_clone" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$image" >> "$LOG" 2>&1
wait_health "$standby_clone" 90 || abort_release 64 '[STANDBY_NOT_READY]'
stable_health "$standby_clone" 5 1 || abort_release 65 '[STANDBY_UNSTABLE]'
docker exec -i "$standby_clone" node - <<'NODE' >/dev/null 2>&1 || abort_release 66 '[STANDBY_FUNCTIONAL_FAILED]'
fetch('http://127.0.0.1:3003/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)})
  .then(async r=>{const t=await r.text();process.exit(r.ok&&t.includes('server_status')?0:1)}).catch(()=>process.exit(2));
NODE
runtime_auth_startup_acceptance "$standby_clone" standby || abort_release 77 '[AUTH_STANDBY_STARTUP_SINGLE_WRITER_FAILED]'
write_state "$next" "$candidate" "$standby_clone" ""
force_probe_both "$candidate" || abort_release 67 '[FINAL_ACTIVE_PROBE_FAILED]'
force_probe_both "$standby_clone" || abort_release 68 '[FINAL_STANDBY_PROBE_FAILED]'
router_transport_ok metatron-mcp-router-a || abort_release 69 '[FINAL_ROUTER_A_FAILED]'
router_transport_ok metatron-mcp-router-b || abort_release 70 '[FINAL_ROUTER_B_FAILED]'
public_transport_stable 3 20 1 || abort_release 71 '[FINAL_PUBLIC_STABILIZATION_FAILED]'
stable_running source-cloudflared-1 3 1 || abort_release 53 '[PRIMARY_TUNNEL_BASELINE_UNHEALTHY]'
stable_running metatron-cloudflared-ha 3 1 || abort_release 54 '[HA_TUNNEL_BASELINE_UNHEALTHY]'
echo "HOMOGENEOUS_STANDBY_PASS active=$candidate standby=$standby_clone" >> "$LOG"
echo "AUTH_CROSS_REPLICA_CONTROL_STATE_ACCEPTANCE_PASS active=$candidate standby=$standby_clone authority=host-encrypted startup_writer=none revocation_model=accepted" >> "$LOG"
echo 'TUNNEL_BASELINE_ACCEPTANCE_PASS primary=source-cloudflared-1 ha=metatron-cloudflared-ha destructive_failover=separate-resilience-lane' >> "$LOG"

# Commit generation only after normal-release stabilization and homogeneous standby convergence.
printf '%s\n' "$next" > "$STATE/generation"
chmod 600 "$STATE/generation" 2>/dev/null || true
write_state "$next" "$candidate" "$standby_clone" ""
fence || abort_release 99 '[FENCE_LOST_AT_COMMIT]'
public_transport_stable 3 20 1 || abort_release 72 '[FINAL_PUBLIC_TRANSPORT_FAILED]'
docker rm "$old_active" >/dev/null 2>&1 || true
if [ -n "$old_standby" ] && [ "$old_standby" != "$candidate" ] && [ "$old_standby" != "$standby_clone" ] && [ "$old_standby" != "$old_active" ]; then
  docker rm -f "$old_standby" >/dev/null 2>&1 || true
fi
echo "DRAIN_POLICY=normal-release-drain-then-homogeneous-standby" >> "$LOG"
echo "[DONE_RESILIENT_V3_4] generation=$next active=$candidate standby=$standby_clone artifact=$artifact_id $(date -Is)" >> "$LOG"
