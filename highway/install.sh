#!/usr/bin/env bash
set -euo pipefail

CHECKOUT="${1:?checkout path required}"
SHA="${2:?exact source SHA required}"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]]
test -d "$CHECKOUT/.git"
test "$(git -C "$CHECKOUT" rev-parse HEAD)" = "$SHA"

INSTALL="${METATRON_HIGHWAY_INSTALL_DIR:-$HOME/.metatron/highway}"
STATE="${METATRON_HIGHWAY_STATE_DIR:-$INSTALL/state}"
BASE_ENV=/opt/metatron/metatron-workforce/.env
CURRENT="$INSTALL/current"
RELEASE="$INSTALL/releases/$SHA"
ENV_FILE="$INSTALL/highway.env"

test -r "$BASE_ENV"
mkdir -p "$INSTALL/releases" "$STATE/logs" "$STATE/workspaces" "$STATE/artifacts"

# Source publication is immutable and serialized independently from task execution.
# Re-installing the control plane must never replace a release an executor is using.
METATRON_HIGHWAY_INSTALL_DIR="$INSTALL" \
  bash "$CHECKOUT/highway/publish-release.sh" "$CHECKOUT" "$SHA"

OLD_DAEMON_HASH=""
if [ -f "$CURRENT/highwayd.py" ]; then
  OLD_DAEMON_HASH=$(sha256sum "$CURRENT/highwayd.py" | awk '{print $1}')
fi
NEW_DAEMON_HASH=$(sha256sum "$CHECKOUT/highway/highwayd.py" | awk '{print $1}')

rm -rf "$CURRENT.tmp"
mkdir -p "$CURRENT.tmp"
cp "$CHECKOUT/highway/highwayd.py" "$CURRENT.tmp/highwayd.py"
cp "$CHECKOUT/highway/highwayctl.py" "$CURRENT.tmp/highwayctl.py"
cp "$CHECKOUT/highway/ensure-running.sh" "$CURRENT.tmp/ensure-running.sh"
cp "$CHECKOUT/highway/task-registry.json" "$CURRENT.tmp/task-registry.json"
chmod 0755 "$CURRENT.tmp/highwayd.py" "$CURRENT.tmp/highwayctl.py" "$CURRENT.tmp/ensure-running.sh"
rm -rf "$CURRENT"
mv "$CURRENT.tmp" "$CURRENT"

python3 - "$ENV_FILE" <<'PY'
import os,secrets,sys
path=sys.argv[1]
values={}
lines=[]
if os.path.exists(path):
    lines=open(path,encoding='utf-8').read().splitlines()
    for line in lines:
        if '=' in line and not line.lstrip().startswith('#'):
            k,v=line.split('=',1); values[k]=v
def ensure(key,value):
    global lines
    if not values.get(key,'').strip():
        lines=[x for x in lines if not x.startswith(key+'=')]
        lines.append(key+'='+value)
ensure('METATRON_HIGHWAY_TOKEN',secrets.token_urlsafe(48))
ensure('METATRON_HIGHWAY_EXECUTORS','4')
ensure('METATRON_HIGHWAY_PORT','18090')
tmp=path+'.tmp'
with open(tmp,'w',encoding='utf-8') as f: f.write('\n'.join(lines)+'\n')
os.chmod(tmp,0o600)
os.replace(tmp,path)
PY

set -a
source "$ENV_FILE"
set +a

if [ "$OLD_DAEMON_HASH" != "$NEW_DAEMON_HASH" ] && curl -fsS --max-time 1 http://127.0.0.1:18090/health >/dev/null 2>&1; then
  echo 'HIGHWAY_DAEMON_CODE_CHANGED=YES'
  for _ in $(seq 1 600); do
    ACTIVE=$(curl -fsS --max-time 1 http://127.0.0.1:18090/health | python3 -c 'import json,sys; print(int(json.load(sys.stdin).get("active",0)))' || echo 1)
    [ "$ACTIVE" = 0 ] && break
    sleep .5
  done
  ACTIVE=$(curl -fsS --max-time 1 http://127.0.0.1:18090/health | python3 -c 'import json,sys; print(int(json.load(sys.stdin).get("active",0)))' || echo 1)
  test "$ACTIVE" = 0
  METATRON_HIGHWAY_INSTALL_DIR="$INSTALL" METATRON_HIGHWAY_STATE_DIR="$STATE" bash "$CURRENT/ensure-running.sh" --restart
else
  echo 'HIGHWAY_DAEMON_RESTART=NOT_REQUIRED'
  METATRON_HIGHWAY_INSTALL_DIR="$INSTALL" METATRON_HIGHWAY_STATE_DIR="$STATE" bash "$CURRENT/ensure-running.sh"
fi

python3 "$CURRENT/highwayctl.py" health
test "$(cat "$RELEASE/.highway-source-sha")" = "$SHA"
echo "HIGHWAY_RELEASE_SHA=$SHA"
echo "HIGHWAY_ROOTLESS_PERSISTENCE=PASS"
echo "HIGHWAY_INSTALL=PASS"
