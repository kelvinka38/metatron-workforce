#!/usr/bin/env bash
set -euo pipefail

CHECKOUT="${1:?checkout path required}"
SHA="${2:?exact source SHA required}"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]]
test -d "$CHECKOUT/.git"
test "$(git -C "$CHECKOUT" rev-parse HEAD)" = "$SHA"

RUN_USER="${SUDO_USER:-$(id -un)}"
RUN_GROUP="$(id -gn "$RUN_USER")"
INSTALL=/opt/metatron/highway
STATE=/var/lib/metatron-highway
BASE_ENV=/opt/metatron/metatron-workforce/.env
CURRENT="$INSTALL/current"
RELEASE="$INSTALL/releases/$SHA"

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
cp "$CHECKOUT/highway/task-registry.json" "$CURRENT.tmp/task-registry.json"
chmod 0755 "$CURRENT.tmp/highwayd.py" "$CURRENT.tmp/highwayctl.py"
rm -rf "$CURRENT"
mv "$CURRENT.tmp" "$CURRENT"

python3 - "$BASE_ENV" <<'PY'
import os,secrets,sys
path=sys.argv[1]
lines=open(path,encoding='utf-8').read().splitlines()
values={}
for line in lines:
    if '=' in line and not line.lstrip().startswith('#'):
        k,v=line.split('=',1); values[k]=v
if not values.get('METATRON_HIGHWAY_TOKEN','').strip():
    lines=[x for x in lines if not x.startswith('METATRON_HIGHWAY_TOKEN=')]
    lines.append('METATRON_HIGHWAY_TOKEN='+secrets.token_urlsafe(48))
if not values.get('METATRON_HIGHWAY_EXECUTORS','').strip():
    lines=[x for x in lines if not x.startswith('METATRON_HIGHWAY_EXECUTORS=')]
    lines.append('METATRON_HIGHWAY_EXECUTORS=4')
tmp=path+'.highway.tmp'
with open(tmp,'w',encoding='utf-8') as f: f.write('\n'.join(lines)+'\n')
os.chmod(tmp,0o600)
os.replace(tmp,path)
PY

chown -R "$RUN_USER:$RUN_GROUP" "$INSTALL" "$STATE"
cat >/etc/systemd/system/metatron-highway.service <<EOF
[Unit]
Description=Metatron Persistent Highway Execution Fabric
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
Group=$RUN_GROUP
WorkingDirectory=$INSTALL/current
EnvironmentFile=-$BASE_ENV
Environment=METATRON_HIGHWAY_INSTALL_DIR=$INSTALL
Environment=METATRON_HIGHWAY_STATE_DIR=$STATE
Environment=METATRON_HIGHWAY_PORT=18090
ExecStart=/usr/bin/python3 $INSTALL/current/highwayd.py
Restart=always
RestartSec=2
TimeoutStopSec=15
KillMode=control-group

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable metatron-highway.service >/dev/null
systemctl restart metatron-highway.service

for _ in $(seq 1 40); do
  if curl -fsS --max-time 2 http://127.0.0.1:18090/health >/tmp/metatron-highway-health.json 2>/dev/null; then
    break
  fi
  sleep .25
done
cat /tmp/metatron-highway-health.json
python3 - /tmp/metatron-highway-health.json <<'PY'
import json,sys
d=json.load(open(sys.argv[1]))
assert d['status']=='UP',d
assert int(d['executors']) >= 4,d
assert d['scheduler']=='persistent',d
print('HIGHWAY_PERSISTENT_SCHEDULER=PASS')
PY
systemctl --no-pager --full status metatron-highway.service | sed -n '1,18p'
echo "HIGHWAY_RELEASE_SHA=$SHA"
echo "HIGHWAY_INSTALL=PASS"
