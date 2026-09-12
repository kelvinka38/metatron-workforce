#!/usr/bin/env bash
set -euo pipefail

CHECKOUT="${1:?checkout path required}"
SHA="${2:?exact source SHA required}"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]]
test -d "$CHECKOUT/.git"
RESOLVED="$(git -C "$CHECKOUT" rev-parse "${SHA}^{commit}" 2>/dev/null || true)"
test "$RESOLVED" = "$SHA"

INSTALL="${METATRON_HIGHWAY_INSTALL_DIR:-$HOME/.metatron/highway}"
RELEASES="$INSTALL/releases"
RELEASE="$RELEASES/$SHA"
mkdir -p "$RELEASES"

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
printf '%s
' "$SHA" > "$TMP/.highway-source-sha"
test -f "$TMP/scripts/highway-tasks/workforce-build.sh"
test -f "$TMP/scripts/highway-tasks/workforce-deploy.sh"
test ! -e "$RELEASE"
mv "$TMP" "$RELEASE"
trap - EXIT

test "$(cat "$RELEASE/.highway-source-sha")" = "$SHA"
echo "HIGHWAY_RELEASE_PUBLISHED=$SHA"
echo "HIGHWAY_RELEASE_IMMUTABLE=PASS"
echo "HIGHWAY_RELEASE_SHARED_HEAD_INDEPENDENCE=PASS"
