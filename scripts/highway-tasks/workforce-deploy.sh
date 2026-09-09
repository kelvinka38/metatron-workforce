#!/usr/bin/env bash
set -euo pipefail
export SHA="${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}"
export METATRON_VERSION="${METATRON_VERSION:-0.1.0}"
export GITHUB_WORKSPACE="${GITHUB_WORKSPACE:-$PWD}"
# --- Deploy exact tested artifact and isolated sandbox with rollback ---
set -euo pipefail
mkdir -p "$HOME/.metatron"
exec 9>"$HOME/.metatron/production-mutation.lock"
flock -w 600 9
echo 'PRODUCTION_MUTATION_LOCK=ACQUIRED'
BASE=/opt/metatron/metatron-workforce
WORKSPACE="$GITHUB_WORKSPACE"
COMPOSE="$WORKSPACE/deploy/docker-compose.yml"
PROJECT=deploy
DEPLOY_STARTED_AT=$(date +%s)

echo '=== PRE-FLIGHT ==='
test -r "$BASE/.env"
test -f "$WORKSPACE/.highway-source-sha"
test "$(cat "$WORKSPACE/.highway-source-sha")" = "$SHA"
test -f "$WORKSPACE/build/libs/metatron-workforce-${METATRON_VERSION}.jar"
test -f "$WORKSPACE/build/libs/metatron-workforce-${METATRON_VERSION}.jar.sha256"
(
  cd "$WORKSPACE"
  sha256sum -c "build/libs/metatron-workforce-${METATRON_VERSION}.jar.sha256"
)
echo 'HIGHWAY_RELEASE_IDENTITY=PASS'
set -a; source "$BASE/.env"; set +a

# Internal effect-boundary tokens are host-local credentials. Persist them once, never log them.
python3 - "$BASE/.env" <<'PY'
import os,secrets,sys
path=sys.argv[1]
with open(path,'r',encoding='utf-8') as f:
    lines=f.read().splitlines()
existing={}
for line in lines:
    if '=' in line and not line.lstrip().startswith('#'):
        key,value=line.split('=',1)
        existing[key]=value
changed=False
for key in ('METATRON_RUNTIME_EXECUTION_TOKEN','METATRON_SANDBOX_TOKEN'):
    if not existing.get(key,'').strip():
        lines=[line for line in lines if not line.startswith(key+'=')]
        lines.append(key+'='+secrets.token_urlsafe(48))
        changed=True
if changed:
    tmp=path+'.internal-token.tmp'
    with open(tmp,'w',encoding='utf-8') as f:
        f.write('\n'.join(lines)+'\n')
    os.chmod(tmp,0o600)
    os.replace(tmp,path)
PY
set -a; source "$BASE/.env"; set +a
test -n "${METATRON_RUNTIME_EXECUTION_TOKEN:-}"
test -n "${METATRON_SANDBOX_TOKEN:-}"
METATRON_IMAGE_TAG="$SHA" METATRON_COMMIT_SHA="$SHA" docker compose -p "$PROJECT" -f "$COMPOSE" config >/dev/null

PREVIOUS_CID=$(docker ps --filter name=deploy-workforce-1 --format '{{.ID}}' | head -1)
test -n "$PREVIOUS_CID"
PREVIOUS_IMAGE=$(docker inspect "$PREVIOUS_CID" --format '{{.Config.Image}}')
PREVIOUS_SHA=$(docker inspect "$PREVIOUS_CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
test -n "$PREVIOUS_IMAGE"
test -n "$PREVIOUS_SHA"
case "$PREVIOUS_IMAGE" in
  metatron-workforce:*) PREVIOUS_TAG="${PREVIOUS_IMAGE#metatron-workforce:}" ;;
  *) echo "unexpected previous image: $PREVIOUS_IMAGE" >&2; exit 1 ;;
esac
docker image inspect "$PREVIOUS_IMAGE" >/dev/null

rollback() {
  echo '=== FAILED CANDIDATE DIAGNOSTICS ==='
  FAILED_CID=$(docker compose -p "$PROJECT" -f "$COMPOSE" ps -q workforce 2>/dev/null || true)
  if [ -n "$FAILED_CID" ]; then
    docker inspect "$FAILED_CID" --format 'state={{.State.Status}} exit={{.State.ExitCode}} oom={{.State.OOMKilled}} error={{json .State.Error}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' || true
    docker logs --tail 400 "$FAILED_CID" 2>&1 || true
  fi
  echo '=== ROLLBACK WORKFORCE TO PREVIOUS IMMUTABLE IMAGE ==='
  METATRON_IMAGE_TAG="$PREVIOUS_TAG" METATRON_COMMIT_SHA="$PREVIOUS_SHA" \
    docker compose -p "$PROJECT" -f "$COMPOSE" up -d --no-build --force-recreate --no-deps workforce || true
  docker compose -p "$PROJECT" -f "$COMPOSE" stop workforce-sandbox >/dev/null 2>&1 || true
}
trap rollback ERR

echo '=== BUILD EXACT TESTED WORKFORCE + SANDBOX IMAGES ==='
METATRON_IMAGE_TAG="$SHA" METATRON_COMMIT_SHA="$SHA" \
  docker compose -p "$PROJECT" -f "$COMPOSE" build workforce workforce-sandbox
docker image inspect "metatron-workforce:$SHA" >/dev/null
docker image inspect "metatron-workforce-sandbox:$SHA" >/dev/null

echo '=== DEPLOY EXACT TESTED STACK ==='
METATRON_IMAGE_TAG="$SHA" METATRON_COMMIT_SHA="$SHA" \
  docker compose -p "$PROJECT" -f "$COMPOSE" up -d --no-build --force-recreate workforce-sandbox workforce

echo '=== VERIFY WORKFORCE CONTAINER ==='
CID=$(docker compose -p "$PROJECT" -f "$COMPOSE" ps -q workforce)
test -n "$CID"
for i in $(seq 1 90); do
  STATUS=$(docker inspect "$CID" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  [ "$STATUS" = healthy ] && break
  sleep 2
done
STATUS=$(docker inspect "$CID" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
test "$STATUS" = healthy
DEPLOYED_SHA=$(docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | sed -n 's/^METATRON_COMMIT_SHA=//p' | head -1)
DEPLOYED_IMAGE=$(docker inspect "$CID" --format '{{.Config.Image}}')
test "$DEPLOYED_SHA" = "$SHA"
test "$DEPLOYED_IMAGE" = "metatron-workforce:$SHA"
docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -q '^METATRON_RUNTIME_EXECUTION_TOKEN=.'
docker inspect "$CID" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -q '^METATRON_SANDBOX_TOKEN=.'

echo '=== VERIFY ISOLATED SANDBOX ==='
SID=$(docker compose -p "$PROJECT" -f "$COMPOSE" ps -q workforce-sandbox)
test -n "$SID"
for i in $(seq 1 90); do
  SSTATUS=$(docker inspect "$SID" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
  [ "$SSTATUS" = healthy ] && break
  sleep 2
done
SSTATUS=$(docker inspect "$SID" --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
test "$SSTATUS" = healthy
test "$(docker inspect "$SID" --format '{{.Config.Image}}')" = "metatron-workforce-sandbox:$SHA"
SANDBOX_ENV=$(docker inspect "$SID" --format '{{range .Config.Env}}{{println .}}{{end}}')
for forbidden in GITHUB_TOKEN OPENAI_API_KEY GEMINI_API_KEY ANTHROPIC_API_KEY TELEGRAM_BOT_TOKEN TELEGRAM_WEBHOOK_SECRET CLOUDFLARE_API_TOKEN HETZNER_API_TOKEN; do
  if printf '%s\n' "$SANDBOX_ENV" | grep -q "^${forbidden}="; then
    echo "sandbox received forbidden institutional credential: $forbidden" >&2
    exit 1
  fi
done
docker exec "$CID" sh -c 'wget -qO- --timeout=5 http://workforce-sandbox:8090/health' | grep -q '"status":"UP"'

echo '=== PUBLIC GATEWAY ==='
curl -fsS --proto '=https' --tlsv1.2 --connect-timeout 5 --max-time 15 https://gate.metatron.vn/telegram/health | grep -q '"status":"UP"'

echo '=== LOCAL HEALTH ==='
curl -fsS --connect-timeout 3 --max-time 10 http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'

echo '=== INTERNET EGRESS ==='
docker exec "$CID" sh -c 'wget -qO- --timeout=10 https://api.github.com/zen >/dev/null'

echo '=== TELEGRAM WEBHOOK ==='
test -n "${TELEGRAM_WEBHOOK_SECRET:-}"
TEST_UPDATE=$(date +%s%N | cut -c1-15)
BODY=$(python3 - "$TEST_UPDATE" "${TELEGRAM_ALLOWED_USER_ID:-0}" <<'PY'
import json,sys
update=int(sys.argv[1]); uid=int(sys.argv[2])
print(json.dumps({"update_id":update,"message":{"message_id":update%2000000000,"from":{"id":uid,"is_bot":False,"first_name":"DeployProbe"},"chat":{"id":uid,"type":"private"},"date":0,"text":"/mode"}}))
PY
)
HTTP=$(curl -sS -o /tmp/metatron-deploy-webhook.json -w '%{http_code}' --proto '=https' --tlsv1.2 --connect-timeout 5 --max-time 20 -X POST https://gate.metatron.vn/telegram/webhook -H "X-Telegram-Bot-Api-Secret-Token: $TELEGRAM_WEBHOOK_SECRET" -H 'Content-Type: application/json' --data-binary "$BODY")
test "$HTTP" = 200

echo '=== PERFORMANCE SLO ==='
ELAPSED=$(( $(date +%s) - DEPLOY_STARTED_AT ))
test "$ELAPSED" -lt 900

trap - ERR
echo "PRODUCTION_SHA=$SHA"
echo "PRODUCTION_IMAGE=metatron-workforce:$SHA"
echo "SANDBOX_IMAGE=metatron-workforce-sandbox:$SHA"
echo "DEPLOY_SECONDS=$ELAPSED"
echo 'PRODUCTION_DEPLOY=PASS'
