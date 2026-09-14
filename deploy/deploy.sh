#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
SHA="${1:-}"

cat >&2 <<'EOF'
NOTICE: deploy/deploy.sh is a compatibility wrapper only.
Production mutation authority belongs exclusively to the persistent Highway control plane.
Use deploy/deploy-production-sha.sh with an exact 40-character commit SHA.
EOF

exec "$BASE_DIR/deploy-production-sha.sh" "$SHA"
