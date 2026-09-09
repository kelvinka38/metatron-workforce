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
mkdir -p "$INSTALL/releases" "$STATE/logs"

rm -rf "$RELEASE.tmp"
mkdir -p "$RELEASE.tmp"
git -C "$CHECKOUT" archive "$SHA" | tar -x -C "$RELEASE.tmp"
printf '%s\n' "$SHA" > "$RELEASE.tmp/.highway-source-sha"
rm -rf "$RELEASE"
mv "$RELEASE.tmp" "$RELEASE"

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

METATRON_HIGHWAY_INSTALL_DIR="$INSTALL" METATRON_HIGHWAY_STATE_DIR="$STATE"   bash "$CURRENT/ensure-running.sh" --restart

set -a
source "$ENV_FILE"
set +a
python3 "$CURRENT/highwayctl.py" health
test "$(cat "$RELEASE/.highway-source-sha")" = "$SHA"
echo "HIGHWAY_RELEASE_SHA=$SHA"
echo "HIGHWAY_ROOTLESS_PERSISTENCE=PASS"
echo "HIGHWAY_INSTALL=PASS"
