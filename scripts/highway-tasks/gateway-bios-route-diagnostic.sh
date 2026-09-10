#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_RUN_ID:=${HIGHWAY_TASK_ID//[^0-9]/}}"
export GITHUB_RUN_ID
set -euo pipefail
ENVOY=source-envoy-1
GW=gateway-v2
echo '=== GATEWAY V2 MOUNTS / LABELS ==='
docker inspect "$GW" --format '{{range .Mounts}}{{println .Type .Source "->" .Destination}}{{end}}'
docker inspect "$GW" --format '{{json .Config.Labels}}'
echo '=== GATEWAY V2 FILES ==='
docker exec "$GW" sh -c 'find /app /srv /opt -maxdepth 3 -type f 2>/dev/null | sort | head -200' || true
echo '=== GATEWAY V2 SOURCE BIOS/EGRESS REFERENCES ==='
docker exec "$GW" sh -c 'grep -Rni "bios\|egress\|capability\|authorization" /app /srv /opt 2>/dev/null | head -300' || true
echo '=== HOST GATEWAY SOURCE FILES ==='
find /opt/metatron/gateway-g6/source -maxdepth 3 -type f -printf '%p\n' | sort | head -200
echo '=== HOST GATEWAY BIOS/EGRESS REFERENCES ==='
grep -Rni 'bios\|egress\|capability\|authorization' /opt/metatron/gateway-g6/source --exclude='*.log' 2>/dev/null | head -300 || true
echo '=== CURRENT ENVOY ROUTE TABLE ==='
sed -n '65,125p' /opt/metatron/gateway-g6/source/envoy-online.yaml || true
echo '=== CURRENT ENVOY BIOS CLUSTER ==='
grep -n -A8 -B3 'name: bios' /opt/metatron/gateway-g6/source/envoy-online.yaml || true
