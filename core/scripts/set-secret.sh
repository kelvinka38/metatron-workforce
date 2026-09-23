#!/usr/bin/env bash
# Store one secret in Core's env file without showing it on screen or leaving it in shell history.
#   curl -fsSL https://raw.githubusercontent.com/kelvinka38/metatron-workforce/<ref>/core/scripts/set-secret.sh | sudo bash -s NAME
# Then paste the value and press Enter. Nothing is echoed. An existing NAME= line is replaced.
set -euo pipefail
FILE="${CORE_ENV_FILE:-/opt/metatron/metatron-core.env}"
UNSET=""
if [ "${1:-}" = "--unset" ]; then UNSET=1; shift; fi
NAME="${1:-}"
[[ $NAME =~ ^[A-Z][A-Z0-9_]*$ ]] || { echo "usage: set-secret.sh [--unset] NAME   (e.g. CORE_TELEGRAM_BOT_TOKEN)" >&2; exit 1; }
[ "$(id -u)" = 0 ] || [ -n "${CORE_ENV_FILE:-}" ] || { echo "run with sudo" >&2; exit 1; }

if [ -n "$UNSET" ]; then
  [ -f "$FILE" ] && sed -i "/^$NAME=/d" "$FILE"
  echo "$NAME removed from $FILE"
  exit 0
fi

if { exec 3</dev/tty; } 2>/dev/null; then
  read -rsp "Paste $NAME (hidden), then Enter: " VALUE <&3; echo
else
  read -rs VALUE   # no terminal: read from stdin
fi
VALUE="${VALUE//$'\r'/}"
VALUE="${VALUE#"${VALUE%%[![:space:]]*}"}"; VALUE="${VALUE%"${VALUE##*[![:space:]]}"}"
[ -n "$VALUE" ] || { echo "empty value, nothing changed" >&2; exit 1; }
# Catch the easy mix-ups (a bot token pasted as a user id, and the reverse) before they are saved.
case "$NAME" in
  *_USER_ID) [[ $VALUE =~ ^[0-9]{4,15}$ ]] \
      || { echo "$NAME must be your numeric Telegram user id (ask @userinfobot); nothing changed" >&2; exit 1; } ;;
  *_BOT_TOKEN) [[ $VALUE =~ ^[0-9]+:[A-Za-z0-9_-]{30,}$ ]] \
      || { echo "$NAME must look like 123456789:AA... (from @BotFather); nothing changed" >&2; exit 1; } ;;
esac

umask 077
touch "$FILE"
tmp=$(mktemp "$FILE.XXXXXX")
grep -v "^$NAME=" "$FILE" > "$tmp" || true
printf '%s=%s\n' "$NAME" "$VALUE" >> "$tmp"
chmod 600 "$tmp"
mv "$tmp" "$FILE"
echo "$NAME saved in $FILE (${#VALUE} characters). Re-run first-deploy.sh to apply."
