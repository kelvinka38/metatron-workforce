#!/usr/bin/env bash
set -euo pipefail

CHECKOUT="${1:?checkout path required}"
SHA="${2:?exact source SHA required}"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]]
test -d "$CHECKOUT/.git"
test "$(git -C "$CHECKOUT" rev-parse HEAD)" = "$SHA"

INSTALL="${METATRON_HIGHWAY_INSTALL_DIR:-$HOME/.metatron/highway}"
RELEASES="$INSTALL/releases"
RELEASE="$RELEASES/$SHA"
mkdir -p "$RELEASES"

# Publishing is outside the Highway task scheduler, so protect the source store with a
# host-local publication lock. Once a SHA exists it is immutable: never remove or
# replace it while an executor may be reading it.
exec 8>"$RELEASES/.publish.lock"
flock -w 60 8

if [ -e "$RELEASE" ]; then
  test -d "$RELEASE"
  test -f "$RELEASE/.highway-source-sha"
  test "$(cat "$RELEASE/.highway-source-sha")" = "$SHA"
  test -f "$RELEASE/scripts/highway-tasks/workforce-build.sh"
  echo "HIGHWAY_RELEASE_ALREADY_PRESENT=$SHA"
  echo "HIGHWAY_RELEASE_IMMUTABLE=PASS"
  exit 0
fi

TMP="$RELEASES/.${SHA}.publish.$$"
rm -rf "$TMP"
mkdir -p "$TMP"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

git -C "$CHECKOUT" archive "$SHA" | tar -x -C "$TMP"
printf '%s\n' "$SHA" > "$TMP/.highway-source-sha"
test -f "$TMP/scripts/highway-tasks/workforce-build.sh"
test -f "$TMP/scripts/highway-tasks/workforce-deploy.sh"

# The publication lock guarantees the destination is still absent. mv on the same
# filesystem gives executors either no release or the complete release, never half of one.
test ! -e "$RELEASE"
mv "$TMP" "$RELEASE"
trap - EXIT

test "$(cat "$RELEASE/.highway-source-sha")" = "$SHA"
echo "HIGHWAY_RELEASE_PUBLISHED=$SHA"
echo "HIGHWAY_RELEASE_IMMUTABLE=PASS"
