#!/bin/sh
set -eu
BUILD=/opt/metatron/ssh-mcp/build

[ "$(id -u)" -eq 0 ] || { echo 'CLIENT_AGNOSTIC_G9_PREPARE_REQUIRES_ROOT' >&2; exit 2; }

python3 -m py_compile \
  "$BUILD/prebuild-client-agnostic-diagnostics-patch.py" \
  "$BUILD/fix-client-agnostic-boundary-v2.py" \
  "$BUILD/fix-client-agnostic-validredirect-dedup-v3.py" \
  "$BUILD/fix-client-agnostic-ui-acceptance-v4.py"
python3 "$BUILD/prebuild-client-agnostic-diagnostics-patch.py"
python3 "$BUILD/fix-client-agnostic-boundary-v2.py"
python3 "$BUILD/fix-client-agnostic-validredirect-dedup-v3.py"
python3 "$BUILD/fix-client-agnostic-ui-acceptance-v4.py"
sh "$BUILD/verify-client-agnostic-ui-v4.sh"
node --check "$BUILD/auth-client-agnostic-oauth-patch.mjs"

grep -Fq "replaceBlock(\"function authorizePage(params, error = '') {\", '\\nfunction validRedirect(uri) {'" "$BUILD/auth-client-agnostic-oauth-patch.mjs" || {
  echo 'CLIENT_AGNOSTIC_G9_VALID_REDIRECT_BOUNDARY_MISSING' >&2
  exit 8
}
python3 - "$BUILD/auth-client-agnostic-oauth-patch.mjs" <<'PY'
from pathlib import Path
import sys
s=Path(sys.argv[1]).read_text()
a=s.index("const ownerHelpers = `function cookieValue(req, name) {")
b=s.index("source = source.slice(0, parseIndex) + ownerHelpers + source.slice(parseIndex);",a)
assert 'function validRedirect(uri) {' not in s[a:b], 'ownerHelpers duplicate validRedirect'
print('CLIENT_AGNOSTIC_G9_VALID_REDIRECT_DEDUP_PASS')
PY
echo 'CLIENT_AGNOSTIC_G9_VALID_REDIRECT_BOUNDARY_PASS'

sh "$BUILD/prebuild-runtime-g9-client-agnostic.sh"

python3 -m py_compile \
  "$BUILD/dockerfile-client-agnostic-canonical-patch.py" \
  "$BUILD/dockerfile-client-agnostic-post-canonical-patch.py"
python3 "$BUILD/dockerfile-client-agnostic-canonical-patch.py" "$BUILD/Dockerfile"
python3 "$BUILD/dockerfile-client-agnostic-post-canonical-patch.py" "$BUILD/Dockerfile"

grep -Fq 'CLIENT_AGNOSTIC_MCP_CANONICAL_V1' "$BUILD/Dockerfile" || { echo 'CLIENT_AGNOSTIC_G9_CANONICAL_MARKER_MISSING' >&2; exit 3; }
(grep -Fq 'CLIENT_AGNOSTIC_POST_CANONICAL_V1' "$BUILD/Dockerfile" || grep -Fq 'CLIENT_AGNOSTIC_POST_CANONICAL_V2' "$BUILD/Dockerfile") || { echo 'CLIENT_AGNOSTIC_G9_POST_CANONICAL_MARKER_MISSING' >&2; exit 4; }
grep -Fq 'METATRON_CLIENT_AGNOSTIC_OAUTH_V1' "$BUILD/Dockerfile" || { echo 'CLIENT_AGNOSTIC_G9_CANONICAL_AUTH_ACCEPTANCE_MISSING' >&2; exit 5; }
(grep -Fq 'METATRON_CLIENT_AGNOSTIC_POST_PATCH_V1' "$BUILD/Dockerfile" || grep -Fq 'METATRON_CLIENT_AGNOSTIC_POST_PATCH_V2' "$BUILD/Dockerfile") || { echo 'CLIENT_AGNOSTIC_G9_CANONICAL_POST_ACCEPTANCE_MISSING' >&2; exit 6; }
grep -Fq 'METATRON_MCP_PROTOCOL_TRANSPARENT_V1' "$BUILD/Dockerfile" || { echo 'CLIENT_AGNOSTIC_G9_CANONICAL_PROTOCOL_ACCEPTANCE_MISSING' >&2; exit 7; }

echo 'CLIENT_AGNOSTIC_G9_CANONICAL_SOURCE_PASS'
echo 'CLIENT_AGNOSTIC_G9_PREPARE_COMPLETE release_not_started=true production_generation=8'
