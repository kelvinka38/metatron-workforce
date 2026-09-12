set -eu
BUILD=/opt/metatron/ssh-mcp/build
STATE=/var/lib/metatron-mcp
ROUTER_STATE="$STATE/router"
LOG=/var/log/metatron-ssh-mcp-upgrade.log
mkdir -p "$STATE" "$ROUTER_STATE/sessions"
chmod 700 "$STATE" "$ROUTER_STATE" "$ROUTER_STATE/sessions" 2>/dev/null || true
cd "$BUILD"
echo "[START_RESILIENT] $(date -Is)" > "$LOG"
echo -n "PRIVSEP_SEAL " >> "$LOG"
/usr/local/sbin/metatron-mcp-seal-root-key >> "$LOG" 2>&1
SSH_OPTS="-i /opt/metatron/ssh-mcp/id_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5"
if ssh $SSH_OPTS root@127.0.0.1 true >/dev/null 2>&1; then
  echo "[SEAL_FAILED_ROOT_AUTH_STILL_ACCEPTED] $(date -Is)" >> "$LOG"; exit 92
fi
probe=$(printf '%s' '{"op":"server_status","args":{}}' | ssh $SSH_OPTS metatron-mcp@127.0.0.1 2>/dev/null || true)
echo "RESTRICTED_PROBE=$probe" >> "$LOG"
printf '%s' "$probe" | grep -q '"transport_user":"metatron-mcp"' || { echo "[RESTRICTED_PROBE_FAILED] $(date -Is)" >> "$LOG"; exit 93; }

avail_kb=$(df --output=avail -k / | tail -1 | tr -d ' ')
[ "${avail_kb:-0}" -ge 1572864 ] || { echo "[DISK_GUARD_FAILED] available_kb=${avail_kb:-0}" >> "$LOG"; exit 94; }

gen=0
[ -s "$STATE/generation" ] && gen=$(cat "$STATE/generation" 2>/dev/null || echo 0)
case "$gen" in ''|*[!0-9]*) gen=0;; esac
next=$((gen+1))
lease="g${next}-$(python3 - <<'PY'
import secrets
print(secrets.token_hex(12))
PY
)"
printf '%s\n' "$lease" > "$STATE/release-owner"
chmod 600 "$STATE/release-owner" 2>/dev/null || true
fence() { [ "$(cat "$STATE/release-owner" 2>/dev/null || true)" = "$lease" ]; }

candidate="metatron-mcp-runtime-g${next}"
image="metatron-ssh-mcp-runtime:g${next}"
echo "RELEASE_GENERATION current=$gen candidate=$next lease=$lease" >> "$LOG"

if ! docker build -t "$image" . >> "$LOG" 2>&1; then
  echo "[BUILD_FAILED] $(date -Is)" >> "$LOG"; exit 1
fi
fence || { echo "[FENCE_LOST_AFTER_BUILD] $(date -Is)" >> "$LOG"; exit 95; }

docker rm -f "$candidate" >/dev/null 2>&1 || true
docker run -d --name "$candidate" --restart unless-stopped \
  --label metatron.mcp.role=runtime --label metatron.mcp.generation="$next" \
  --network metatron-gateway-online \
  --add-host host.docker.internal:host-gateway \
  -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro \
  "$image" >> "$LOG" 2>&1

wait_health() {
  c="$1"; limit="${2:-90}"; i=0
  while [ "$i" -lt "$limit" ]; do
    state=$(docker inspect "$c" --format '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' 2>/dev/null || true)
    case "$state" in running/healthy) return 0;; exited/*|dead/*) return 1;; esac
    i=$((i+1)); sleep 1
  done
  return 1
}

if ! wait_health "$candidate" 90; then
  echo "[CANDIDATE_NOT_READY] $candidate $(date -Is)" >> "$LOG"
  docker logs --tail 120 "$candidate" >> "$LOG" 2>&1 || true
  docker rm -f "$candidate" >/dev/null 2>&1 || true
  exit 2
fi
fence || { echo "[FENCE_LOST_AFTER_CANDIDATE] $(date -Is)" >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 96; }
echo "CANDIDATE_READY=$candidate" >> "$LOG"

if ! docker build -f Dockerfile.router -t metatron-mcp-router:v1 . >> "$LOG" 2>&1; then
  echo "[ROUTER_BUILD_FAILED] $(date -Is)" >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 3
fi

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

read_state_field() {
  field="$1"
  python3 - "$ROUTER_STATE/router.json" "$field" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1])); v=d.get(sys.argv[2],'')
except Exception: v=''
print(v if isinstance(v,(str,int)) else '')
PY
}

start_router() {
  name="$1"
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker run -d --name "$name" --restart unless-stopped \
    --label metatron.mcp.role=router \
    --network metatron-gateway-online --network-alias metatron-ssh-mcp \
    --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m \
    --security-opt no-new-privileges:true --cap-drop ALL \
    -v "$ROUTER_STATE:/state:rw" \
    metatron-mcp-router:v1 >> "$LOG" 2>&1
  wait_health "$name" 60
}

public_ready() { curl -fsS --max-time 8 https://ssh.metatron.vn/ready >/dev/null 2>&1; }
router_exists=0
if docker inspect metatron-mcp-router-a >/dev/null 2>&1 && docker inspect metatron-mcp-router-b >/dev/null 2>&1; then router_exists=1; fi
old_active="$(read_state_field active)"
old_standby="$(read_state_field standby)"

if [ "$router_exists" -eq 0 ] || [ -z "$old_active" ]; then
  echo "TOPOLOGY_MIGRATION=legacy-to-ha" >> "$LOG"
  legacy_image=$(docker inspect metatron-ssh-mcp --format '{{.Image}}' 2>/dev/null || true)
  [ -n "$legacy_image" ] || { echo "[LEGACY_RUNTIME_MISSING]" >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 4; }
  bootstrap="metatron-mcp-runtime-g0-bootstrap"
  docker rm -f "$bootstrap" >/dev/null 2>&1 || true
  docker run -d --name "$bootstrap" --restart unless-stopped \
    --label metatron.mcp.role=runtime --label metatron.mcp.generation=0 \
    --network metatron-gateway-online \
    --add-host host.docker.internal:host-gateway \
    -v /opt/metatron/ssh-mcp/id_ed25519:/ssh/id_ed25519:ro \
    "$legacy_image" >> "$LOG" 2>&1
  if ! wait_health "$bootstrap" 60; then
    echo "[BOOTSTRAP_STANDBY_NOT_READY]" >> "$LOG"; docker rm -f "$bootstrap" "$candidate" >/dev/null 2>&1 || true; exit 5
  fi
  write_state "$next" "$candidate" "$bootstrap" ""
  fence || { echo "[FENCE_LOST_BEFORE_ROUTER_MIGRATION]" >> "$LOG"; exit 97; }
  start_router metatron-mcp-router-a || { echo "[ROUTER_NOT_READY] metatron-mcp-router-a" >> "$LOG"; docker rm -f metatron-mcp-router-a "$bootstrap" "$candidate" >/dev/null 2>&1 || true; exit 6; }
  start_router metatron-mcp-router-b || { echo "[ROUTER_NOT_READY] metatron-mcp-router-b" >> "$LOG"; docker rm -f metatron-mcp-router-a metatron-mcp-router-b "$bootstrap" "$candidate" >/dev/null 2>&1 || true; exit 6; }
  docker rm -f metatron-ssh-mcp >> "$LOG" 2>&1 || true
  sleep 2
  public_ready || { echo "[MIGRATION_PUBLIC_READY_FAILED] $(date -Is)" >> "$LOG"; exit 10; }
  promoted_active="$candidate"
  promoted_standby="$bootstrap"
else
  echo "TOPOLOGY_MIGRATION=none" >> "$LOG"
  [ -n "$old_active" ] || { echo "[ROUTER_STATE_ACTIVE_MISSING]" >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 11; }
  fence || { echo "[FENCE_LOST_BEFORE_PROMOTION]" >> "$LOG"; docker rm -f "$candidate" >/dev/null 2>&1 || true; exit 98; }
  probe_log="$STATE/release-probe-${next}.log"
  : > "$probe_log"
  (
    i=0
    while [ "$i" -lt 30 ]; do
      if ! curl -fsS --max-time 2 https://ssh.metatron.vn/ready >/dev/null 2>&1; then printf 'FAIL %s\n' "$(date -Is)" >> "$probe_log"; fi
      i=$((i+1)); sleep 0.2
    done
  ) & probe_pid=$!
  draining="$old_standby"
  write_state "$next" "$candidate" "$old_active" "$draining"
  sleep 2
  if ! public_ready; then
    echo "[PUBLIC_READY_FAILED_ROLLBACK] $(date -Is)" >> "$LOG"
    write_state "$gen" "$old_active" "$old_standby" ""
    docker rm -f "$candidate" >/dev/null 2>&1 || true
    wait "$probe_pid" 2>/dev/null || true
    exit 7
  fi
  wait "$probe_pid" 2>/dev/null || true
  failures=$(grep -c '^FAIL ' "$probe_log" 2>/dev/null || true)
  case "$failures" in ''|*[!0-9]*) failures=999;; esac
  echo "CUTOVER_PROBE_FAILURES=$failures" >> "$LOG"
  [ "$failures" -eq 0 ] || {
    echo "[ZERO_DOWNTIME_PROBE_FAILED] $(date -Is)" >> "$LOG"
    write_state "$gen" "$old_active" "$old_standby" ""
    docker rm -f "$candidate" >/dev/null 2>&1 || true
    exit 12
  }
  if [ -n "$old_standby" ]; then
    sessions=$(docker exec metatron-mcp-router-a node - "$old_standby" <<'NODE' 2>/dev/null || echo 1
const b=process.argv[2];fetch('http://127.0.0.1:3004/status').then(r=>r.json()).then(j=>process.stdout.write(String(j.sessions?.[b]||0))).catch(()=>process.stdout.write('1'));
NODE
)
    case "$sessions" in ''|*[!0-9]*) sessions=1;; esac
    if [ "$sessions" -eq 0 ]; then docker rm -f "$old_standby" >> "$LOG" 2>&1 || true; else echo "DRAINING_RUNTIME=$old_standby sessions=$sessions" >> "$LOG"; fi
  fi
  promoted_active="$candidate"
  promoted_standby="$old_active"
fi

printf '%s\n' "$next" > "$STATE/generation"
chmod 600 "$STATE/generation" 2>/dev/null || true
fence || { echo "[FENCE_LOST_AFTER_PROMOTION]" >> "$LOG"; exit 99; }

if ! docker inspect metatron-cloudflared-ha >/dev/null 2>&1; then
  docker run -d --name metatron-cloudflared-ha --restart unless-stopped \
    --label metatron.mcp.role=tunnel-ha \
    --network metatron-gateway-online \
    --read-only --tmpfs /tmp:rw,noexec,nosuid,size=16m \
    --security-opt no-new-privileges:true --cap-drop ALL \
    -v /etc/cloudflared/g6-token:/run/secrets/cloudflared-token:ro \
    cloudflare/cloudflared:2026.8.1 tunnel run --token-file /run/secrets/cloudflared-token >> "$LOG" 2>&1 || {
      echo "[CLOUDFLARED_HA_START_FAILED] $(date -Is)" >> "$LOG"; exit 8; }
fi
sleep 2

public_ready || { echo "[FINAL_PUBLIC_READY_FAILED] $(date -Is)" >> "$LOG"; exit 9; }
echo "[DONE_RESILIENT] generation=$next active=$promoted_active standby=$promoted_standby $(date -Is)" >> "$LOG"
