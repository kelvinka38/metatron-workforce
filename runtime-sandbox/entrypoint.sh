#!/bin/sh
set -eu
: "${SANDBOX_TOKEN:?SANDBOX_TOKEN is required}"
umask 077
printf '%s' "$SANDBOX_TOKEN" > /tmp/metatron-sandbox-token
unset SANDBOX_TOKEN
exec env -u SANDBOX_TOKEN SANDBOX_TOKEN_FILE=/tmp/metatron-sandbox-token python3 /opt/metatron-sandbox/server.py
