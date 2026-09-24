#!/usr/bin/env bash
# M3-2: move the main Telegram bot from the old Workforce to Core, or roll back. Run on the host:
#   curl -fsSL https://raw.githubusercontent.com/kelvinka38/metatron-workforce/<ref>/core/scripts/cutover.sh | sudo bash
#   curl -fsSL .../cutover.sh | sudo bash -s -- --rollback
# Token values are never printed. The old Workforce keeps running (bios still calls its API).
set -euo pipefail

CORE_ENV="${CORE_ENV_FILE:-/opt/metatron/metatron-core.env}"
WF_ENV="${WF_ENV_FILE:-/opt/metatron/metatron-workforce/deploy/.env}"
DEPLOY="${DEPLOY_SCRIPT:-/opt/metatron/metatron-core-src/core/scripts/first-deploy.sh}"
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }
deploy() {  # run a copy: first-deploy.sh updates its own checkout while it runs
  local copy
  copy=$(mktemp)
  cp "$DEPLOY" "$copy"
  bash "$copy"
  rm -f "$copy"
}

[ "$(id -u)" = 0 ] || [ -n "${CORE_ENV_FILE:-}" ] || fail "run as root (sudo)"
[ -f "$CORE_ENV" ] && [ -f "$WF_ENV" ] && [ -f "$DEPLOY" ] || fail "run first-deploy.sh once before cutover"
umask 077

value() {  # value NAME FILE -> the last NAME=... value, quotes stripped
  grep "^$1=" "$2" | tail -1 | cut -d= -f2- | sed -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/" || true
}
put() {  # put NAME VALUE -> replace NAME in Core's env file
  sed -i "/^$1=/d" "$CORE_ENV"
  printf '%s=%s\n' "$1" "$2" >> "$CORE_ENV"
}

if [ "${1:-}" = "--rollback" ]; then
  test_token=$(value CORE_TELEGRAM_TEST_BOT_TOKEN "$CORE_ENV")
  [ -n "$test_token" ] || fail "no saved test bot token (CORE_TELEGRAM_TEST_BOT_TOKEN)"
  put CORE_TELEGRAM_BOT_TOKEN "$test_token"
  unset test_token
  echo "Core is back on the test bot."
  deploy
  echo
  echo "Last step: re-register the main bot's webhook for the old Workforce"
  echo "(ask Claude, which uses the Metatron telegram_set_webhook tool)."
  exit 0
fi

main_token=$(value TELEGRAM_BOT_TOKEN "$WF_ENV")
[[ $main_token =~ ^[0-9]+:[A-Za-z0-9_-]{30,}$ ]] || fail "TELEGRAM_BOT_TOKEN in $WF_ENV is missing or malformed"
current=$(value CORE_TELEGRAM_BOT_TOKEN "$CORE_ENV")
if [ "$current" = "$main_token" ]; then
  echo "Core already uses the main bot."
else
  [ -n "$current" ] && put CORE_TELEGRAM_TEST_BOT_TOKEN "$current" && echo "Saved the test bot token for rollback."
  put CORE_TELEGRAM_BOT_TOKEN "$main_token"
  echo "Core now uses the main bot's token."
fi
unset main_token current
chmod 600 "$CORE_ENV"
deploy
echo
echo "Cutover done: send /help to your main bot. The old Workforce keeps running for bios."
echo "Roll back with: curl -fsSL <this script> | sudo bash -s -- --rollback"
