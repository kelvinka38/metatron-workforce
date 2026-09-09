#!/usr/bin/env bash
set -euo pipefail

: "${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}"
: "${HIGHWAY_TASK_ID:?HIGHWAY_TASK_ID required}"
: "${HIGHWAY_INSTALL_DIR:?HIGHWAY_INSTALL_DIR required}"
: "${HIGHWAY_STATE_DIR:?HIGHWAY_STATE_DIR required}"

export METATRON_VERSION="${METATRON_VERSION:-0.1.0}"
export GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=192m -Dorg.gradle.workers.max=1 -Dorg.gradle.daemon=false}"

SOURCE="$HIGHWAY_INSTALL_DIR/releases/$HIGHWAY_SOURCE_SHA"
WORK_ROOT="$HIGHWAY_STATE_DIR/workspaces"
WORK="$WORK_ROOT/$HIGHWAY_TASK_ID-build"
ARTIFACTS="$HIGHWAY_STATE_DIR/artifacts"
ARTIFACT="$ARTIFACTS/$HIGHWAY_SOURCE_SHA"
JAR="metatron-workforce-${METATRON_VERSION}.jar"

test -d "$SOURCE"
test -f "$SOURCE/.highway-source-sha"
test "$(cat "$SOURCE/.highway-source-sha")" = "$HIGHWAY_SOURCE_SHA"
test -f "$SOURCE/scripts/highway-tasks/workforce-build.sh"

mkdir -p "$WORK_ROOT" "$ARTIFACTS"
rm -rf "$WORK"
TMP="$WORK.tmp.$$"
rm -rf "$TMP"
mkdir -p "$TMP"
cleanup() {
  rm -rf "$TMP" "$WORK"
}
trap cleanup EXIT

# Never run Gradle inside the canonical source release. A build owns only its private
# workspace, so clean/test/retry cannot delete files another task is reading.
cp -a "$SOURCE/." "$TMP/"
mv "$TMP" "$WORK"
cd "$WORK"

test "$(cat .highway-source-sha)" = "$HIGHWAY_SOURCE_SHA"
chmod +x ./gradlew
./gradlew clean test bootJar --no-daemon --max-workers=1

METATRON_SANDBOX_TOKEN=compose-validation-only \
  docker compose -f deploy/docker-compose.yml config >/dev/null
METATRON_IMAGE_TAG="highway-build-$HIGHWAY_SOURCE_SHA" \
  METATRON_SANDBOX_TOKEN=highway-build-contract \
  docker compose -f deploy/docker-compose.yml build workforce-sandbox

test -f "build/libs/$JAR"

# Seal only the tested JAR into a SHA-addressed artifact store. Publishing is atomic;
# a retry may reuse an identical seal but may never replace it with different bytes.
ART_TMP="$ARTIFACTS/.${HIGHWAY_SOURCE_SHA}.artifact.$HIGHWAY_TASK_ID.$$"
rm -rf "$ART_TMP"
mkdir -p "$ART_TMP"
cp "build/libs/$JAR" "$ART_TMP/$JAR"
(
  cd "$ART_TMP"
  sha256sum "$JAR" > "$JAR.sha256"
)
printf '%s\n' "$HIGHWAY_SOURCE_SHA" > "$ART_TMP/.highway-source-sha"
printf '%s\n' "$HIGHWAY_TASK_ID" > "$ART_TMP/.highway-build-task-id"

exec 7>"$ARTIFACTS/.publish.lock"
flock -w 60 7
if [ -e "$ARTIFACT" ]; then
  test -d "$ARTIFACT"
  test "$(cat "$ARTIFACT/.highway-source-sha")" = "$HIGHWAY_SOURCE_SHA"
  (
    cd "$ARTIFACT"
    sha256sum -c "$JAR.sha256"
  )
  EXISTING="$(sha256sum "$ARTIFACT/$JAR" | awk '{print $1}')"
  CANDIDATE="$(sha256sum "$ART_TMP/$JAR" | awk '{print $1}')"
  test "$EXISTING" = "$CANDIDATE"
  rm -rf "$ART_TMP"
  echo "HIGHWAY_ARTIFACT_ALREADY_SEALED=$HIGHWAY_SOURCE_SHA"
else
  mv "$ART_TMP" "$ARTIFACT"
  echo "HIGHWAY_ARTIFACT_SEALED=$HIGHWAY_SOURCE_SHA"
fi

(
  cd "$ARTIFACT"
  sha256sum -c "$JAR.sha256"
)
echo "HIGHWAY_BUILD_WORKSPACE_ISOLATION=PASS"
echo "HIGHWAY_SOURCE_RELEASE_UNMODIFIED=PASS"
echo "HIGHWAY_WORKFORCE_BUILD_SHA=$HIGHWAY_SOURCE_SHA"
echo "HIGHWAY_WORKFORCE_BUILD=PASS"
