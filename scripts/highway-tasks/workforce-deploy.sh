#!/usr/bin/env bash
set -euo pipefail

export SHA="${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}"
: "${HIGHWAY_TASK_ID:?HIGHWAY_TASK_ID required}"
: "${HIGHWAY_INSTALL_DIR:?HIGHWAY_INSTALL_DIR required}"
: "${HIGHWAY_STATE_DIR:?HIGHWAY_STATE_DIR required}"
export METATRON_VERSION="${METATRON_VERSION:-0.1.0}"

# --- Deploy exact tested artifact and isolated sandbox with rollback ---
mkdir -p "$HOME/.metatron"
exec 9>"$HOME/.metatron/production-mutation.lock"
flock -w 600 9
echo 'PRODUCTION_MUTATION_LOCK=ACQUIRED'

ENV_FILE="${METATRON_PRODUCTION_ENV_FILE:-$HOME/.metatron/config/workforce.env}"
SOURCE="$HIGHWAY_INSTALL_DIR/releases/$SHA"
ARTIFACT="$HIGHWAY_STATE_DIR/artifacts/$SHA"
WORK_ROOT="$HIGHWAY_STATE_DIR/workspaces"
WORKSPACE="$WORK_ROOT/$HIGHWAY_TASK_ID-deploy"
COMPOSE="$WORKSPACE/deploy/docker-compose.yml"
PROJECT=deploy
DEPLOY_STARTED_AT=$(date +%s)
JAR="metatron-workforce-${METATRON_VERSION}.jar"

mkdir -p "$WORK_ROOT"
rm -rf "$WORKSPACE"
TMP="$WORKSPACE.tmp.$"
rm -rf "$TMP"
mkdir -p "$TMP"
cleanup_workspace() { rm -rf "$TMP" "$WORKSPACE"; }
trap cleanup_workspace EXIT

echo '=== PRE-FLIGHT ==='
test -r "$ENV_FILE"
test -d "$SOURCE"
test "$(cat "$SOURCE/.highway-source-sha")" = "$SHA"
test -d "$ARTIFACT"
test "$(cat "$ARTIFACT/.highway-source-sha")" = "$SHA"
(
  cd "$ARTIFACT"
  sha256sum -c "$JAR.sha256"
)

# Assemble a private deployment context from immutable source + sealed tested artifact.
# Docker build output may mutate this workspace but can never touch the canonical release.
cp -a "$SOURCE/." "$TMP/"
mkdir -p "$TMP/build/libs"
cp "$ARTIFACT/$JAR" "$TMP/build/libs/$JAR"
cp "$ARTIFACT/$JAR.sha256" "$TMP/build/libs/$JAR.sha256"
mv "$TMP" "$WORKSPACE"
test "$(cat "$WORKSPACE/.highway-source-sha")" = "$SHA"
(
  cd "$WORKSPACE/build/libs"
  sha256sum -c "$JAR.sha256"
)
echo 'HIGHWAY_RELEASE_IDENTITY=PASS'
echo 'HIGHWAY_SEALED_ARTIFACT_IDENTITY=PASS'
echo 'HIGHWAY_DEPLOY_WORKSPACE_ISOLATION=PASS'
set -a; source "$ENV_FILE"; set +a

# Internal effect-boundary tokens are host-local credentials. Persist them once, never log them.
python3 - "$ENV_FILE" <<'PY'
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
set -a; source "$ENV_FILE"; set +a
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
  # Never leave Workforce without a Cognition Node: if the candidate node image exists, keep one running.
  if docker image inspect "metatron-cognition-node:$SHA" >/dev/null 2>&1; then
    METATRON_IMAGE_TAG="$SHA" METATRON_COMMIT_SHA="$SHA" \
      docker compose -p "$PROJECT" -f "$COMPOSE" --profile production-cognition up -d --no-build --no-deps cognition-node || true
  fi
}
trap rollback ERR

echo '=== BOUNDED HOST STORAGE GC ==='
bounded_storage_gc() {
  local removed=0 kept=0 ref repo
  local used_images
  used_images="$(docker ps -a --format '{{.Image}}')"
  for repo in metatron-workforce metatron-workforce-sandbox metatron-cognition-node; do
    kept=0
    while IFS= read -r ref; do
      [ -n "$ref" ] || continue
      [ "$ref" != "metatron-workforce:rollback" ] || continue
      if printf '%s\n' "$used_images" | grep -Fxq "$ref"; then continue; fi
      if [ "$kept" -lt 3 ]; then kept=$((kept+1)); continue; fi
      [ "$removed" -lt 40 ] || break 2
      if docker image rm "$ref" >/dev/null 2>&1; then removed=$((removed+1)); fi
    done < <(docker image ls "$repo" --format '{{.Repository}}:{{.Tag}}')
  done
  docker image prune -f >/dev/null 2>&1 || true
  echo "HOST_STORAGE_GC=PASS removed_tags=$removed disk_used=$(df -P / | awk 'NR==2{print $5}')"
}
bounded_storage_gc

echo '=== BUILD EXACT TESTED WORKFORCE + SANDBOX + COGNITION NODE IMAGES ==='
METATRON_IMAGE_TAG="$SHA" METATRON_COMMIT_SHA="$SHA" \
  docker compose -p "$PROJECT" -f "$COMPOSE" --profile production-cognition build workforce workforce-sandbox cognition-node
docker image inspect "metatron-workforce:$SHA" >/dev/null
docker image inspect "metatron-workforce-sandbox:$SHA" >/dev/null
docker image inspect "metatron-cognition-node:$SHA" >/dev/null

# One-time adoption: the Cognition Node used to be a hand-run container outside this pipeline. Replace any
# same-named container this compose project does not own, so every later deploy rolls it like Workforce.
LEGACY_COGNITION=$(docker ps -aq --filter 'name=^/metatron-cognition-node$')
if [ -n "$LEGACY_COGNITION" ] && [ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$LEGACY_COGNITION")" != "$PROJECT" ]; then
  echo 'COGNITION_NODE_LEGACY_CONTAINER=ADOPTED'
  docker rm -f "$LEGACY_COGNITION" >/dev/null
fi

echo '=== DEPLOY EXACT TESTED STACK ==='
METATRON_IMAGE_TAG="$SHA" METATRON_COMMIT_SHA="$SHA" \
  docker compose -p "$PROJECT" -f "$COMPOSE" --profile production-cognition up -d --no-build --force-recreate workforce-sandbox cognition-node workforce

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

echo '=== VERIFY COGNITION NODE ==='
test "$(docker inspect metatron-cognition-node --format '{{.Config.Image}}')" = "metatron-cognition-node:$SHA"
COGNITION_ENV=$(docker inspect metatron-cognition-node --format '{{range .Config.Env}}{{println .}}{{end}}')
for forbidden in OPENAI_API_KEY ANTHROPIC_API_KEY GITHUB_TOKEN TELEGRAM_BOT_TOKEN; do
  if printf '%s\n' "$COGNITION_ENV" | grep -q "^${forbidden}="; then
    echo "cognition node received forbidden credential: $forbidden" >&2
    exit 1
  fi
done
COGNITION_HEALTH=""
for i in $(seq 1 30); do
  COGNITION_HEALTH=$(docker exec "$CID" sh -c 'wget -qO- --timeout=5 http://metatron-cognition-node:8091/healthz' 2>/dev/null || true)
  printf '%s' "$COGNITION_HEALTH" | grep -q "\"revision\":\"$SHA\"" && break
  sleep 2
done
printf '%s' "$COGNITION_HEALTH" | grep -q "\"revision\":\"$SHA\""
echo "COGNITION_NODE_HEALTH=$COGNITION_HEALTH"

echo '=== LOCAL HEALTH ==='
curl -fsS --connect-timeout 3 --max-time 10 http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"'

echo '=== EXTERNAL ACCEPTANCE DEFERRED TO HIGHWAY FANOUT ==='
echo '=== PERFORMANCE SLO ==='
ELAPSED=$(( $(date +%s) - DEPLOY_STARTED_AT ))
test "$ELAPSED" -lt 900

trap - ERR
echo "PRODUCTION_SHA=$SHA"
echo "PRODUCTION_IMAGE=metatron-workforce:$SHA"
echo "SANDBOX_IMAGE=metatron-workforce-sandbox:$SHA"
echo "COGNITION_NODE_IMAGE=metatron-cognition-node:$SHA"
echo "DEPLOY_SECONDS=$ELAPSED"
echo 'PRODUCTION_DEPLOY=PASS'
