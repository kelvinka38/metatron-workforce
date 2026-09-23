#!/usr/bin/env bash
# Store one secret in Core's env file without showing it on screen or leaving it in shell history.
#   curl -fsSL https://raw.githubusercontent.com/kelvinka38/metatron-workforce/<ref>/core/scripts/set-secret.sh | sudo bash -s NAME
# Then paste the value and press Enter. Nothing is echoed. An existing NAME= line is replaced.
set -euo pipefail
FILE="${CORE_ENV_FILE:-/opt/metatron/metatron-core.env}"
NAME="${1:-}"
[[ $NAME =~ ^[A-Z][A-Z0-9_]*$ ]] || { echo "usage: set-secret.sh NAME   (e.g. CORE_TELEGRAM_BOT_TOKEN)" >&2; exit 1; }
[ "$(id -u)" = 0 ] || [ -n "${CORE_ENV_FILE:-}" ] || { echo "run with sudo" >&2; exit 1; }

if { exec 3</dev/tty; } 2>/dev/null; then
  read -rsp "Paste $NAME (hidden), then Enter: " VALUE <&3; echo
else
  read -rs VALUE   # no terminal: read from stdin
fi
VALUE="${VALUE//$'\r'/}"
[ -n "$VALUE" ] || { echo "empty value, nothing changed" >&2; exit 1; }

umask 077
touch "$FILE"
tmp=$(mktemp "$FILE.XXXXXX")
grep -v "^$NAME=" "$FILE" > "$tmp" || true
printf '%s=%s\n' "$NAME" "$VALUE" >> "$tmp"
chmod 600 "$tmp"
mv "$tmp" "$FILE"
echo "$NAME saved in $FILE (${#VALUE} characters). Re-run first-deploy.sh to apply."
