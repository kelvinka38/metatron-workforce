set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
LOG=/var/log/metatron-ssh-mcp-upgrade.log
mkdir -p "$STATE" "$ROUTER_STATE/sessions"
chmod 700 "$STATE" "$ROUTER_STATE" "$ROUTER_STATE/sessions" 2>/dev/null || true
cd "$BUILD"
echo "[START_RESILIENT_V2_6] $(date -Is)" > "$LOG"

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
public_transport_once() {
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 4 \
    -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
    -H 'MCP-Protocol-Version: 2024-11-05' -H 'User-Agent: metatron-release-transport-probe/2.6' \
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
const b=process.argv[2];fetch('http://127.0.0.1:3004/probe?backend='+encodeURIComponent(b),{method:'POST',signal:AbortSignal.timeout(3000)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2));
NODE
}
force_probe_both() {
  backend="$1"; i=0
  while [ "$i" -lt 8 ]; do
    if router_force_probe metatron-mcp-router-a "$backend" && router_force_probe metatron-mcp-router-b "$backend"; then return 0; fi
    i=$((i+1)); sleep 0.5
  done
  return 1
}
router_transport_ok() {
  router="$1"
  docker exec "$router" node - <<'NODE' >/dev/null 2>&1
fetch('http://127.0.0.1:3002/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2));
NODE
}
direct_candidate_soak() {
  c="$1"; i=0
  while [ "$i" -lt 3 ]; do
    docker exec "$c" node - <<'NODE' >/dev/null 2>&1 || return 1
fetch('http://127.0.0.1:3002/mcp',{method:'POST',headers:{'content-type':'application/json','accept':'application/json, text/event-stream','mcp-protocol-version':'2024-11-05'},body:JSON.stringify({jsonrpc:'2.0',id:1,method:'tools/list',params:{}}),signal:AbortSignal.timeout(4000)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(2));
NODE
    i=$((i+1))
  done
}
run_public_probe_window() {
  file="$1"; count="$2"; delay="$3"; : > "$file"
  (
    i=0
    while [ "$i" -lt "$count" ]; do
      if ! public_transport_hard_ok; then printf 'FAIL %s\n' "$(date -Is)" >> "$file"; fi
      i=$((i+1)); sleep "$delay"
    done
  ) & echo $!
}
count_failures() {
  n=$(grep -c '^FAIL ' "$1" 2>/dev/null || true)
  case "$n" in ''|*[!0-9]*) n=999;; esac
  printf '%s' "$n"
}

# Stable routers are a prerequisite and are never replaced by this release controller.
[ "$(router_version metatron-mcp-router-a)" = '2.3.0' ] || { echo '[ROUTER_A_V23_REQUIRED]' >> "$LOG"; exit 20; }
[ "$(router_version metatron-mcp-router-b)" = '2.3.0' ] || { echo '[ROUTER_B_V23_REQUIRED]' >> "$LOG"; exit 21; }
[ -s "$STATE/generation" ] || { echo '[GENERATION_STATE_MISSING]' >> "$LOG"; exit 22; }
gen=$(cat "$STATE/generation" 2>/dev/null || echo 0)
case "$gen" in ''|*[!0-9]*) echo '[INVALID_GENERATION]' >> "$LOG"; exit 23;; esac
old_active=$(read_state_field active)
old_standby=$(read_state_field standby)
[ -n "$old_active" ] || { echo '[ACTIVE_MISSING]' >> "$LOG"; exit 24; }
wait_health "$old_active" 20 || { echo '[ACTIVE_NOT_HEALTHY]' >> "$LOG"; exit 25; }
public_transport_hard_ok || { echo '[BASELINE_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 26; }
router_transport_ok metatron-mcp-router-a || { echo '[BASELINE_ROUTER_A_FAILED]' >> "$LOG"; exit 27; }
router_transport_ok metatron-mcp-router-b || { echo '[BASELINE_ROUTER_B_FAILED]' >> "$LOG"; exit 28; }
echo 'BASELINE_TRANSPORT_PASS' >> "$LOG"

next=$((gen+1))
candidate="metatron-mcp-runtime-g${next}"
standby_clone="metatron-mcp-runtime-g${next}-standby"
image="metatron-ssh-mcp-runtime:g${next}"
docker image inspect "$image" >/dev/null 2>&1 || { echo "[PREBUILT_IMAGE_REQUIRED] image=$image" >> "$LOG"; exit 30; }
artifact_id=$(docker image inspect "$image" --format '{{.Id}}' 2>/dev/null || true)
[ -n "$artifact_id" ] || { echo '[PREBUILT_IMAGE_ID_MISSING]' >> "$LOG"; exit 31; }
echo "PREBUILT_ARTIFACT=$image id=$artifact_id" >> "$LOG"

lease=$(cat "$STATE/release-owner" 2>/dev/null || true)
fence() { [ "$(cat "$STATE/release-owner" 2>/dev/null || true)" = "$lease" ] && [ -n "$lease" ]; }
fence || { echo '[FENCE_MISSING_AT_RELEASE_START]' >> "$LOG"; exit 95; }

# Retire any non-authoritative stale candidate containers from previous failed attempts.
docker rm -f "$candidate" "$standby_clone" >/dev/null 2>&1 || true
if [ -n "$old_standby" ] && docker inspect "$old_standby" >/dev/null 2>&1; then
  st=$(docker inspect "$old_standby" --format '{{.State.Status}}' 2>/dev/null || true)
  [ "$st" != running ] || docker stop -t 5 "$old_standby" >> "$LOG" 2>&1 || true
fi
wait_resource_headroom 262144 45 || { echo '[RESOURCE_HEADROOM_TIMEOUT]' >> "$LOG"; exit 32; }

# Start the low-memory candidate, validate it directly, then force both routers to cache it.
docker run -d --name "$candidate" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$image" >> "$LOG" 2>&1
wait_health "$candidate" 90 || { echo '[CANDIDATE_NOT_READY]' >> "$LOG"; docker logs --tail 100 "$candidate" >> "$LOG" 2>&1 || true; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 33; }
direct_candidate_soak "$candidate" || { echo '[CANDIDATE_DIRECT_SOAK_FAILED]' >> "$LOG"; docker logs --tail 100 "$candidate" >> "$LOG" 2>&1 || true; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 34; }
# Put candidate in standby slot so routers may immediately retry to it when old active is stopped.
write_state "$gen" "$old_active" "$candidate" "$old_standby"
force_probe_both "$candidate" || { write_state "$gen" "$old_active" "$old_standby" ""; docker rm -f "$candidate" >/dev/null 2>&1 || true; echo '[CANDIDATE_FORCE_PROBE_FAILED]' >> "$LOG"; exit 35; }
router_transport_ok metatron-mcp-router-a || { echo '[PREHANDOFF_ROUTER_A_FAILED]' >> "$LOG"; exit 36; }
router_transport_ok metatron-mcp-router-b || { echo '[PREHANDOFF_ROUTER_B_FAILED]' >> "$LOG"; exit 37; }
echo "FAST_HANDOFF_PREPARED candidate=$candidate" >> "$LOG"
fence || { echo '[FENCE_LOST_BEFORE_HANDOFF]' >> "$LOG"; exit 96; }

# Keep the two-runtime overlap to only a few seconds: probe candidate, then immediately stop old active.
handoff_log="$STATE/fast-handoff-${next}.log"
handoff_pid=$(run_public_probe_window "$handoff_log" 40 0.08)
docker stop -t 1 "$old_active" >> "$LOG" 2>&1 || true
# While state still points to old active, v2.3 routers must safe-retry tools/list to candidate standby.
sleep 0.5
public_transport_hard_ok || { docker start "$old_active" >/dev/null 2>&1 || true; write_state "$gen" "$old_active" "$old_standby" ""; docker rm -f "$candidate" >/dev/null 2>&1 || true; echo '[FAST_HANDOFF_FAILOVER_FAILED]' >> "$LOG"; exit 38; }
# Atomic promotion after failover is already serving from candidate.
write_state "$next" "$candidate" "" "$old_active"
force_probe_both "$candidate" || { echo '[POST_HANDOFF_CANDIDATE_PROBE_FAILED]' >> "$LOG"; exit 39; }
wait "$handoff_pid" 2>/dev/null || true
handoff_failures=$(count_failures "$handoff_log")
echo "FAST_HANDOFF_HARD_FAILURES=$handoff_failures" >> "$LOG"
[ "$handoff_failures" -eq 0 ] || { echo '[FAST_HANDOFF_ACCEPTANCE_FAILED]' >> "$LOG"; exit 40; }
router_transport_ok metatron-mcp-router-a || { echo '[POST_HANDOFF_ROUTER_A_FAILED]' >> "$LOG"; exit 41; }
router_transport_ok metatron-mcp-router-b || { echo '[POST_HANDOFF_ROUTER_B_FAILED]' >> "$LOG"; exit 42; }
public_transport_hard_ok || { echo '[POST_HANDOFF_PUBLIC_FAILED]' >> "$LOG"; exit 43; }
echo 'FAST_HANDOFF_PASS' >> "$LOG"

# Old heavy active is now stopped. Wait for memory to recover before creating the same-generation standby clone.
wait_resource_headroom 524288 60 || { echo '[POST_HANDOFF_HEADROOM_TIMEOUT]' >> "$LOG"; exit 44; }
docker run -d --name "$standby_clone" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --memory 512m --memory-swap 768m --pids-limit 256 \
  --network metatron-gateway-online --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro "$image" >> "$LOG" 2>&1
wait_health "$standby_clone" 90 || { echo '[STANDBY_CLONE_NOT_READY]' >> "$LOG"; docker logs --tail 100 "$standby_clone" >> "$LOG" 2>&1 || true; exit 45; }
write_state "$next" "$candidate" "$standby_clone" "$old_active"
force_probe_both "$standby_clone" || { echo '[STANDBY_FORCE_PROBE_FAILED]' >> "$LOG"; exit 46; }
router_transport_ok metatron-mcp-router-a || { echo '[HA_PAIR_ROUTER_A_FAILED]' >> "$LOG"; exit 47; }
router_transport_ok metatron-mcp-router-b || { echo '[HA_PAIR_ROUTER_B_FAILED]' >> "$LOG"; exit 48; }
public_transport_hard_ok || { echo '[HA_PAIR_PUBLIC_FAILED]' >> "$LOG"; exit 49; }
echo "HA_PAIR_READY active=$candidate standby=$standby_clone" >> "$LOG"

# Runtime failover acceptance. Hard failure means three consecutive public failures in one sample.
runtime_log="$STATE/runtime-failover-${next}.log"
runtime_pid=$(run_public_probe_window "$runtime_log" 60 0.08)
docker stop -t 1 "$candidate" >> "$LOG" 2>&1 || true
sleep 2
public_transport_hard_ok || { docker start "$candidate" >/dev/null 2>&1 || true; echo '[RUNTIME_FAILOVER_PUBLIC_FAILED]' >> "$LOG"; exit 50; }
router_transport_ok metatron-mcp-router-a || { docker start "$candidate" >/dev/null 2>&1 || true; echo '[RUNTIME_FAILOVER_ROUTER_A_FAILED]' >> "$LOG"; exit 51; }
router_transport_ok metatron-mcp-router-b || { docker start "$candidate" >/dev/null 2>&1 || true; echo '[RUNTIME_FAILOVER_ROUTER_B_FAILED]' >> "$LOG"; exit 52; }
docker start "$candidate" >> "$LOG" 2>&1 || true
wait_health "$candidate" 90 || { echo '[ACTIVE_RESTART_FAILED_AFTER_FAILOVER_TEST]' >> "$LOG"; exit 53; }
force_probe_both "$candidate" || true
wait "$runtime_pid" 2>/dev/null || true
runtime_failures=$(count_failures "$runtime_log")
echo "RUNTIME_FAILOVER_HARD_FAILURES=$runtime_failures" >> "$LOG"
[ "$runtime_failures" -eq 0 ] || { echo '[RUNTIME_FAILOVER_ACCEPTANCE_FAILED]' >> "$LOG"; exit 54; }
echo 'RUNTIME_FAILOVER_ACCEPTANCE_PASS' >> "$LOG"

# Connector-process failover acceptance, one connector at a time.
tunnel_log="$STATE/tunnel-failover-${next}.log"
tunnel_pid=$(run_public_probe_window "$tunnel_log" 90 0.10)
docker stop -t 5 source-cloudflared-1 >> "$LOG" 2>&1 || true
sleep 3
public_transport_hard_ok || { docker start source-cloudflared-1 >/dev/null 2>&1 || true; echo '[PRIMARY_TUNNEL_FAILOVER_FAILED]' >> "$LOG"; exit 55; }
docker start source-cloudflared-1 >> "$LOG" 2>&1 || true
sleep 5
docker stop -t 5 metatron-cloudflared-ha >> "$LOG" 2>&1 || true
sleep 3
public_transport_hard_ok || { docker start metatron-cloudflared-ha >/dev/null 2>&1 || true; echo '[HA_TUNNEL_FAILOVER_FAILED]' >> "$LOG"; exit 56; }
docker start metatron-cloudflared-ha >> "$LOG" 2>&1 || true
wait "$tunnel_pid" 2>/dev/null || true
tunnel_failures=$(count_failures "$tunnel_log")
echo "TUNNEL_FAILOVER_HARD_FAILURES=$tunnel_failures" >> "$LOG"
[ "$tunnel_failures" -eq 0 ] || { echo '[TUNNEL_FAILOVER_ACCEPTANCE_FAILED]' >> "$LOG"; exit 57; }
echo 'TUNNEL_FAILOVER_ACCEPTANCE_PASS' >> "$LOG"

# Commit final generation and retire old heavy generation only after every acceptance passes.
printf '%s\n' "$next" > "$STATE/generation"
chmod 600 "$STATE/generation" 2>/dev/null || true
write_state "$next" "$candidate" "$standby_clone" ""
fence || { echo '[FENCE_LOST_AT_COMMIT]' >> "$LOG"; exit 99; }
public_transport_hard_ok || { echo '[FINAL_PUBLIC_TRANSPORT_FAILED]' >> "$LOG"; exit 58; }
docker rm "$old_active" >/dev/null 2>&1 || true
if [ -n "$old_standby" ] && [ "$old_standby" != "$old_active" ]; then docker rm -f "$old_standby" >/dev/null 2>&1 || true; fi
echo "[DONE_RESILIENT_V2_6] generation=$next active=$candidate standby=$standby_clone artifact=$artifact_id $(date -Is)" >> "$LOG"
