#!/usr/bin/env bash
set -euo pipefail

: "${GITHUB_RUN_ID:=${HIGHWAY_TASK_ID//[^0-9]/}}"
export GITHUB_RUN_ID
set -euo pipefail
BASE=/opt/metatron/gateway-g6/source
CONFIG="$BASE/envoy-online.yaml"
COMPOSE="$BASE/docker-compose.yml"
test -f "$CONFIG"; test -f "$COMPOSE"
grep -q 'address: bios, port_value: 8081' "$CONFIG"
cp "$CONFIG" "$CONFIG.bak.pre-bios-production-$(date +%s)"
sed -i 's/address: bios, port_value: 8081/address: bios, port_value: 8080/' "$CONFIG"
grep -q 'address: bios, port_value: 8080' "$CONFIG"

docker run --rm -v "$CONFIG:/etc/envoy/envoy.yaml:ro" \
  envoyproxy/envoy:v1.36-latest --mode validate -c /etc/envoy/envoy.yaml
echo 'ENVOY_BIOS_CLUSTER_CONFIG_VALID=PASS'

cd "$BASE"
docker compose up -d --force-recreate envoy
for i in $(seq 1 30); do
  if curl -fsS --max-time 5 https://gate.metatron.vn/healthz >/tmp/gateway-healthz.json 2>/dev/null; then break; fi
  sleep 2
done
grep -q '"status":"ok"' /tmp/gateway-healthz.json
echo 'GATEWAY_PUBLIC_HEALTH_AFTER_BIOS_CLUSTER_RECONCILE=PASS'

docker network inspect metatron-gateway-online --format '{{range .Containers}}{{println .Name}}{{end}}' | grep -q '^metatron-bios$'
docker run --rm --network metatron-gateway-online python:3.12-slim python -c "import urllib.request; assert urllib.request.urlopen('http://bios:8080/health',timeout=3).status==200; print('BIOS_PRIVATE_UPSTREAM=PASS')"
echo 'GATEWAY_BIOS_CLUSTER_RECONCILE=PASS'
