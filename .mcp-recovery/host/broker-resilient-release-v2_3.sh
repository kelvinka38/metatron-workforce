set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
LOG=/var/log/metatron-ssh-mcp-upgrade.log
mkdir -p "$STATE" "$ROUTER_STATE/sessions"
chmod 700 "$STATE" "$ROUTER_STATE" "$ROUTER_STATE/sessions" 2>/dev/null || true
cd "$BUILD"
echo "[START_RESILIENT_V2_3] $(date -Is)" > "$LOG"

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
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 6 \
    -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' \
    -H 'User-Agent: metatron-release-transport-probe/2.3' \
    --data '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
    https://ssh.metatron.vn/mcp 2>/dev/null || true)
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
    load1=$(awk '{print $1}' /proc/loadavg)
    echo "RESOURCE_HEADROOM attempt=$i mem_available_kb=${mem_avail_kb:-0} swap_free_kb=${swap_free_kb:-0} load1=$load1" >> "$LOG"
    if [ "${mem_avail_kb:-0}" -ge 524288 ] && [ "${swap_free_kb:-0}" -ge 1048576 ]; then return 0; fi
    i=$((i+1)); sleep 2
  done
  return 1
}
router_version_ok() {
  r="$1"
  docker exec "$r" node -e "fetch('http://127.0.0.1:3004/status',{signal:AbortSignal.timeout(2500)}).then(r=>r.json()).then(j=>{if(j.version!=='2.3.0')process.exit(2)}).catch(()=>process.exit(3))" >/dev/null 2>&1
}
router_probe_backend() {
  r="$1"; b="$2"
  docker exec "$r" node - "$b" <<'NODE' >/dev/null 2>&1
const b=process.argv[2];
fetch('http://127.0.0.1:3004/probe?backend='+encodeURIComponent(b),{method:'POST',signal:AbortSignal.timeout(3500)})
 .then(async r=>{const j=await r.json().catch(()=>({}));if(!r.ok||j.ok!==true)process.exit(2)})
 .catch(()=>process.exit(3));
NODE
}
router_direct_transport() {
  r="$1"; b="$2"
  docker exec "$r" node - "$b" <<'NODE' >/dev/null 2>&1
const b=process.argv[2];
fetch(`http://${b}:3002/mcp`,{
 method:'POST',signal:AbortSignal.timeout(5000),
 headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05','user-agent':'metatron-router-warmup/2.3'},
 body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}})
}).then(r=>{if(r.status!==200)process.exit(2);return r.arrayBuffer()}).catch(()=>process.exit(3));
NODE
}
warm_backend_all_routers() {
  b="$1"; loops="${2:-3}"; n=0
  while [ "$n" -lt "$loops" ]; do
    router_probe_backend metatron-mcp-router-a "$b" || return 1
    router_probe_backend metatron-mcp-router-b "$b" || return 1
    router_direct_transport metatron-mcp-router-a "$b" || return 1
    router_direct_transport metatron-mcp-router-b "$b" || return 1
    n=$((n+1)); sleep 0.25
  done
}
probe_window() {
  out="$1"; count="$2"; interval="$3"
  : > "$out"
  (
    i=0
    while [ "$i" -lt "$count" ]; do
      if ! public_transport_ok; then printf 'FAIL %s\n' "$(date -Is)" >> "$out"; fi
      i=$((i+1)); sleep "$interval"
    done
  ) &
  echo $!
}
count_failures() {
  n=$(grep -c '^FAIL ' "$1" 2>/dev/null || true)
  case "$n" in ''|*[!0-9]*) n=999;; esac
  printf '%s' "$n"
}

[ -s "$STATE/generation" ] || { echo '[V2_3_REQUIRES_MIGRATED_TOPOLOGY]' >> "$LOG"; exit 20; }
router_version_ok metatron-mcp-router-a || { echo '[ROUTER_A_VERSION_NOT_2_3]' >> "$LOG"; exit 28; }
router_version_ok metatron-mcp-router-b || { echo '[ROUTER_B_VERSION_NOT_2_3]' >> "$LOG"; exit 29; }
gen=$(cat "$STATE/generation" 2>/dev/null || echo 0)
case "$gen" in ''|*[!0-9]*) echo '[INVALID_GENERATION]' >> "$LOG"; exit 21;; esac
old_active=$(read_state_field active)
old_standby=$(read_state_field standby)
[ -n "$old_active" ] || { echo '[ACTIVE_MISSING]' >> "$LOG"; exit 22; }
wait_health "$old_active" 20 || { echo '[ACTIVE_NOT_HEALTHY]' >> "$LOG"; exit 23; }
public_transport_ok || { echo '[BASELINE_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 24; }
warm_backend_all_routers "$old_active" 2 || { echo '[ACTIVE_ROUTER_WARMUP_FAILED]' >> "$LOG"; exit 30; }

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
  if [ -n "$paused_standby" ] && docker inspect "$paused_standby" >/dev/null 2>&1; then
    docker start "$paused_standby" >/dev/null 2>&1 || true
    wait_health "$paused_standby" 60 || true
    warm_backend_all_routers "$paused_standby" 1 || true
  fi
}
cleanup_release() {
  rc=$?
  if [ "$committed" -ne 1 ]; then restore_standby; fi
  exit "$rc"
}
trap cleanup_release EXIT INT TERM

if [ -n "$old_standby" ] && docker inspect "$old_standby" >/dev/null 2>&1; then
  state=$(docker inspect "$old_standby" --format '{{.State.Status}}' 2>/dev/null || true)
  if [ "$state" = running ]; then
    echo "PAUSING_OLD_STANDBY=$old_standby" >> "$LOG"
    docker stop -t 15 "$old_standby" >> "$LOG" 2>&1 || { echo '[STANDBY_STOP_FAILED]' >> "$LOG"; exit 25; }
    paused_standby="$old_standby"
  else
    paused_standby="$old_standby"
  fi
fi
sync || true
if ! wait_resource_headroom; then echo '[RESOURCE_HEADROOM_TIMEOUT]' >> "$LOG"; exit 26; fi
avail_kb=$(df --output=avail -k / | tail -1 | tr -d ' ')
[ "${avail_kb:-0}" -ge 1572864 ] || { echo "[DISK_GUARD_FAILED] available_kb=${avail_kb:-0}" >> "$LOG"; exit 94; }

candidate="metatron-mcp-runtime-g${next}"
image="metatron-ssh-mcp-runtime:g${next}"
echo "RELEASE_GENERATION current=$gen candidate=$next lease=$lease" >> "$LOG"
build_probe="$STATE/release-build-probe-${next}.log"
build_probe_pid=$(probe_window "$build_probe" 240 0.5)
if ! nice -n 10 docker build -t "$image" . >> "$LOG" 2>&1; then
  kill "$build_probe_pid" >/dev/null 2>&1 || true; wait "$build_probe_pid" 2>/dev/null || true
  echo '[BUILD_FAILED]' >> "$LOG"; exit 1
fi
kill "$build_probe_pid" >/dev/null 2>&1 || true; wait "$build_probe_pid" 2>/dev/null || true
build_failures=$(count_failures "$build_probe")
echo "BUILD_TRANSPORT_FAILURES=$build_failures" >> "$LOG"
[ "$build_failures" -eq 0 ] || { echo '[BUILD_IMPACTED_PUBLIC_TRANSPORT]' >> "$LOG"; exit 27; }
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

# Candidate must be independently reachable and cached healthy in BOTH routers before promotion.
if ! warm_backend_all_routers "$candidate" 5; then
  echo '[CANDIDATE_ROUTER_WARMUP_FAILED]' >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 31
fi
if ! warm_backend_all_routers "$old_active" 2; then
  echo '[STANDBY_ROUTER_WARMUP_FAILED]' >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 32
fi
soak="$STATE/release-candidate-soak-${next}.log"
: > "$soak"
i=0
while [ "$i" -lt 20 ]; do
  router_direct_transport metatron-mcp-router-a "$candidate" || printf 'FAIL A %s\n' "$(date -Is)" >> "$soak"
  router_direct_transport metatron-mcp-router-b "$candidate" || printf 'FAIL B %s\n' "$(date -Is)" >> "$soak"
  i=$((i+1)); sleep 0.15
done
soak_failures=$(grep -c '^FAIL ' "$soak" 2>/dev/null || true)
case "$soak_failures" in ''|*[!0-9]*) soak_failures=999;; esac
echo "CANDIDATE_SOAK_FAILURES=$soak_failures" >> "$LOG"
[ "$soak_failures" -eq 0 ] || { echo '[CANDIDATE_SOAK_FAILED]' >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 33; }

probe_log="$STATE/release-transport-probe-${next}.log"
probe_pid=$(probe_window "$probe_log" 80 0.15)
write_state "$next" "$candidate" "$old_active" "$old_standby"
wait "$probe_pid" 2>/dev/null || true
failures=$(count_failures "$probe_log")
echo "CUTOVER_TRANSPORT_FAILURES=$failures" >> "$LOG"
if [ "$failures" -ne 0 ]; then
  write_state "$gen" "$old_active" "$old_standby" ""
  warm_backend_all_routers "$old_active" 1 || true
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[ZERO_DOWNTIME_TRANSPORT_FAILED]' >> "$LOG"
  exit 12
fi
if ! public_ready_eventually; then
  write_state "$gen" "$old_active" "$old_standby" ""
  warm_backend_all_routers "$old_active" 1 || true
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  echo '[READINESS_CONVERGENCE_FAILED]' >> "$LOG"
  exit 13
fi

printf '%s\n' "$next" > "$STATE/generation"
chmod 600 "$STATE/generation" 2>/dev/null || true
fence || { echo '[FENCE_LOST_AFTER_PROMOTION]' >> "$LOG"; exit 99; }
committed=1
trap - EXIT INT TERM
if [ -n "$paused_standby" ] && docker inspect "$paused_standby" >/dev/null 2>&1; then docker rm "$paused_standby" >> "$LOG" 2>&1 || true; fi

# Bound the newly-created standby container as well; its process-level cap will be replaced on the next generation.
docker update --memory 768m --memory-swap 1024m --pids-limit 256 "$old_active" >> "$LOG" 2>&1 || true
public_transport_ok || { echo '[FINAL_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 14; }

# Optional one-shot runtime failover acceptance. Triggered only by a bounded workspace flag.
if [ -f "$BUILD/.run-mcp-runtime-failover-acceptance" ]; then
  failover_log="$STATE/runtime-failover-acceptance-${next}.log"
  failover_pid=$(probe_window "$failover_log" 80 0.15)
  docker stop -t 8 "$candidate" >> "$LOG" 2>&1 || { echo '[RUNTIME_FAILOVER_STOP_FAILED]' >> "$LOG"; exit 40; }
  sleep 3
  docker start "$candidate" >> "$LOG" 2>&1 || { echo '[RUNTIME_FAILOVER_RESTART_FAILED]' >> "$LOG"; exit 41; }
  wait_health "$candidate" 60 || { echo '[RUNTIME_FAILOVER_RECOVERY_FAILED]' >> "$LOG"; exit 42; }
  warm_backend_all_routers "$candidate" 2 || { echo '[RUNTIME_FAILOVER_REWARM_FAILED]' >> "$LOG"; exit 43; }
  wait "$failover_pid" 2>/dev/null || true
  failover_failures=$(count_failures "$failover_log")
  echo "RUNTIME_FAILOVER_TRANSPORT_FAILURES=$failover_failures" >> "$LOG"
  [ "$failover_failures" -eq 0 ] || { echo '[RUNTIME_FAILOVER_ACCEPTANCE_FAILED]' >> "$LOG"; exit 44; }
  rm -f "$BUILD/.run-mcp-runtime-failover-acceptance"
  echo 'RUNTIME_FAILOVER_ACCEPTANCE_PASS' >> "$LOG"
fi

# Optional one-shot connector-process failover acceptance.
if [ -f "$BUILD/.run-mcp-tunnel-failover-acceptance" ]; then
  tunnel_log="$STATE/tunnel-failover-acceptance-${next}.log"
  tunnel_pid=$(probe_window "$tunnel_log" 120 0.15)
  docker restart -t 8 source-cloudflared-1 >> "$LOG" 2>&1 || { echo '[TUNNEL_FAILOVER_RESTART_FAILED]' >> "$LOG"; exit 50; }
  wait "$tunnel_pid" 2>/dev/null || true
  tunnel_failures=$(count_failures "$tunnel_log")
  echo "TUNNEL_FAILOVER_TRANSPORT_FAILURES=$tunnel_failures" >> "$LOG"
  [ "$tunnel_failures" -eq 0 ] || { echo '[TUNNEL_FAILOVER_ACCEPTANCE_FAILED]' >> "$LOG"; exit 51; }
  rm -f "$BUILD/.run-mcp-tunnel-failover-acceptance"
  echo 'TUNNEL_FAILOVER_ACCEPTANCE_PASS' >> "$LOG"
fi

echo "[DONE_RESILIENT_V2_3] generation=$next active=$candidate standby=$old_active $(date -Is)" >> "$LOG"
