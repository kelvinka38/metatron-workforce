#!/usr/bin/env bash
set -euo pipefail

CHECKOUT="${1:?checkout path required}"
SHA="${2:?exact source SHA required}"
[[ "$SHA" =~ ^[0-9a-f]{40}$ ]]
test -d "$CHECKOUT/.git"
test "$(git -C "$CHECKOUT" rev-parse HEAD)" = "$SHA"

INSTALL="${METATRON_HIGHWAY_INSTALL_DIR:-$HOME/.metatron/highway}"
RELEASE="$INSTALL/releases/$SHA"
if [ -f "$RELEASE/.highway-source-sha" ] && [ "$(cat "$RELEASE/.highway-source-sha")" = "$SHA" ]; then
  echo "HIGHWAY_RELEASE_ALREADY_PRESENT=$SHA"
  exit 0
fi

mkdir -p "$INSTALL/releases"
rm -rf "$RELEASE.tmp"
mkdir -p "$RELEASE.tmp"
git -C "$CHECKOUT" archive "$SHA" | tar -x -C "$RELEASE.tmp"
printf '%s\n' "$SHA" > "$RELEASE.tmp/.highway-source-sha"
rm -rf "$RELEASE"
mv "$RELEASE.tmp" "$RELEASE"

test "$(cat "$RELEASE/.highway-source-sha")" = "$SHA"
echo "HIGHWAY_RELEASE_PUBLISHED=$SHA"
