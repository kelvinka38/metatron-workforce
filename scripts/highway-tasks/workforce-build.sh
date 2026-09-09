#!/usr/bin/env bash
set -euo pipefail
: "${HIGHWAY_SOURCE_SHA:?HIGHWAY_SOURCE_SHA required}"
export METATRON_VERSION="${METATRON_VERSION:-0.1.0}"
export GRADLE_OPTS="${GRADLE_OPTS:--Dorg.gradle.jvmargs=-Xmx384m -XX:MaxMetaspaceSize=192m -Dorg.gradle.workers.max=1 -Dorg.gradle.daemon=false}"
test -f .highway-source-sha
test "$(cat .highway-source-sha)" = "$HIGHWAY_SOURCE_SHA"
chmod +x ./gradlew
./gradlew clean test bootJar --no-daemon --max-workers=1
METATRON_SANDBOX_TOKEN=compose-validation-only docker compose -f deploy/docker-compose.yml config >/dev/null
METATRON_IMAGE_TAG="highway-build-${HIGHWAY_SOURCE_SHA}" METATRON_SANDBOX_TOKEN=highway-build-contract docker compose -f deploy/docker-compose.yml build workforce-sandbox
test -f "build/libs/metatron-workforce-${METATRON_VERSION}.jar"
sha256sum "build/libs/metatron-workforce-${METATRON_VERSION}.jar" > "build/libs/metatron-workforce-${METATRON_VERSION}.jar.sha256"
echo "HIGHWAY_WORKFORCE_BUILD_SHA=${HIGHWAY_SOURCE_SHA}"
echo "HIGHWAY_WORKFORCE_BUILD=PASS"
