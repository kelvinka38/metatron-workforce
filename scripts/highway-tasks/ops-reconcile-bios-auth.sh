#!/usr/bin/env bash
set -euo pipefail


# migrated workflow step 1
set -euo pipefail
BIOS_ENV="$HOME/.metatron/bios/.env"
test -r "$BIOS_ENV"
set -a; source "$BIOS_ENV"; set +a
test -n "${BIOS_API_KEY:-}"

OLD_CID=$(docker ps --filter name=metatron-bios --format '{{.ID}}' | head -1)
test -n "$OLD_CID"
IMAGE=$(docker inspect "$OLD_CID" --format '{{.Config.Image}}')
BIOS_SHA=$(docker inspect "$OLD_CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^BIOS_COMMIT_SHA=//p' | head -1)
test -n "$IMAGE"
test -n "$BIOS_SHA"
docker image inspect "$IMAGE" >/dev/null
docker volume inspect metatron-bios-data >/dev/null
docker network inspect metatron-gateway-online >/dev/null

echo "BIOS_RECONCILE_IMAGE=$IMAGE"
echo "BIOS_RECONCILE_SHA=$BIOS_SHA"

cat >/tmp/bios-auth-reconcile-compose.yml <<'YAML'
services:
  bios:
    image: ${BIOS_IMAGE}
    container_name: metatron-bios
    restart: unless-stopped
    environment:
      BIOS_PORT: "8080"
      BIOS_DB_PATH: "/data/bios_runtime.db"
      BIOS_API_KEY: "${BIOS_API_KEY}"
      BIOS_RATE_LIMIT: "${BIOS_RATE_LIMIT:-120}"
      BIOS_RATE_WINDOW_SECONDS: "${BIOS_RATE_WINDOW_SECONDS:-60}"
      BIOS_COMMIT_SHA: "${BIOS_COMMIT_SHA}"
      BIOS_WORKFORCE_URL: "http://workforce-production:8080"
    volumes:
      - bios-data:/data
    expose:
      - "8080"
    ports:
      - "127.0.0.1:18080:8080"
    read_only: true
    tmpfs:
      - /tmp:size=64m,mode=1777
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    healthcheck:
      test: ["CMD-SHELL", "python -c \"import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=2)\" >/dev/null 2>&1"]
      interval: 10s
      timeout: 3s
      retries: 12
      start_period: 10s
    networks:
      gateway:
        aliases:
          - bios
volumes:
  bios-data:
    external: true
    name: metatron-bios-data
networks:
  gateway:
    external: true
    name: metatron-gateway-online
YAML

export BIOS_IMAGE="$IMAGE"
export BIOS_COMMIT_SHA="$BIOS_SHA"
docker compose -p bios -f /tmp/bios-auth-reconcile-compose.yml config >/dev/null
docker compose -p bios -f /tmp/bios-auth-reconcile-compose.yml up -d --force-recreate bios

CID=$(docker ps --filter name=metatron-bios --format '{{.ID}}' | head -1)
test -n "$CID"
for i in $(seq 1 60); do
  HEALTH=$(docker inspect "$CID" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  [ "$HEALTH" = healthy ] && break
  sleep 2
done
test "$(docker inspect "$CID" --format '{{.State.Health.Status}}')" = healthy
test "$(docker inspect "$CID" --format '{{.Config.Image}}')" = "$IMAGE"
test "$(docker inspect "$CID" --format '{{.HostConfig.ReadonlyRootfs}}')" = true
docker inspect "$CID" --format '{{json .NetworkSettings.Networks}}' | grep -q 'metatron-gateway-online'

ROUTE_HTTP=$(docker exec "$CID" python -c "import urllib.request; print(urllib.request.urlopen('http://workforce-production:8080/actuator/health',timeout=5).status)" || true)
echo "BIOS_TO_CANONICAL_WORKFORCE_HTTP=$ROUTE_HTTP"
test "$ROUTE_HTTP" = 200

HEALTH_HTTP=$(curl -sS -o /tmp/bios-reconcile-health.json -w '%{http_code}' --max-time 10 http://127.0.0.1:18080/health)
READY_HTTP=$(curl -sS -o /tmp/bios-reconcile-ready.json -w '%{http_code}' --max-time 10 -H "X-BIOS-API-Key: $BIOS_API_KEY" http://127.0.0.1:18080/health/ready)
echo "BIOS_RECONCILE_HEALTH_HTTP=$HEALTH_HTTP"
echo "BIOS_RECONCILE_READY_HTTP=$READY_HTTP"
test "$HEALTH_HTTP" = 200
test "$READY_HTTP" = 200

echo 'BIOS_AUTH_CONFIG_DRIFT_RECONCILED=PASS'
echo 'BIOS_RUNTIME_AUTH_HEALTH=PASS'
