#!/usr/bin/env bash
# First deploy of Metatron Core beside the old Workforce (tickets M1-3, M1-4, M1-5). Run on the host:
#   curl -fsSL https://raw.githubusercontent.com/kelvinka38/metatron-workforce/<ref>/core/scripts/first-deploy.sh | sudo bash
# Safe to re-run. Uses its own checkout, env file, container, port and volume; never touches Workforce's.
set -euo pipefail

REF="${CORE_REF:-metatron/objective-50334c388a41-aea68111}"
SRC=/opt/metatron/metatron-core-src
CORE_ENV=/opt/metatron/metatron-core.env
WF_ENV=/opt/metatron/metatron-workforce/deploy/.env

step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || fail "run as root (sudo)"
[ -f "$WF_ENV" ] || fail "$WF_ENV not found (GEMINI_API_KEY, GITHUB_TOKEN, TELEGRAM_ALLOWED_USER_ID come from it)"
docker network inspect metatron-workforce >/dev/null || fail "docker network metatron-workforce missing"
docker inspect metatron-ollama >/dev/null || fail "container metatron-ollama missing"

step "Source: $REF into $SRC (the Workforce checkout is not touched)"
[ -d "$SRC/.git" ] || git clone -q https://github.com/kelvinka38/metatron-workforce.git "$SRC"
git -C "$SRC" fetch -q origin "$REF"
git -C "$SRC" checkout -q --detach FETCH_HEAD
git -C "$SRC" log --oneline -1

step "Env file $CORE_ENV (values are never printed)"
umask 077
touch "$CORE_ENV"
chmod 600 "$CORE_ENV"
has() { grep -q "^$1=." "$CORE_ENV"; }
for name in CORE_TELEGRAM_WEBHOOK_SECRET CORE_API_TOKEN; do
  if ! has "$name"; then
    sed -i "/^$name=/d" "$CORE_ENV"
    echo "$name=$(openssl rand -hex 24)" >> "$CORE_ENV"
    echo "generated $name"
  fi
done
# A key saved as GEMINI_FREE_API_KEY is the free-tier one: Core uses it instead of Workforce's key.
free=$(grep '^GEMINI_FREE_API_KEY=' "$CORE_ENV" | grep -v '=your_key$' | tail -1 | cut -d= -f2- || true)
if [ -n "$free" ] && ! has GEMINI_API_KEY; then
  echo "GEMINI_API_KEY=$free" >> "$CORE_ENV"
  echo "Core will use GEMINI_FREE_API_KEY as its Gemini key"
fi
unset free
if has GEMINI_API_KEY; then echo "Gemini key: from $CORE_ENV"
else echo "Gemini key: from $WF_ENV (save a free-tier key with set-secret.sh GEMINI_API_KEY)"; fi
if has CORE_TELEGRAM_BOT_TOKEN; then echo "Telegram test bot token: set"
else echo "Telegram test bot token: NOT set yet (M1-6)"; fi

step "M1-4: ollama pull qwen2.5-coder:7b (about 4.7 GB, first time only)"
docker exec metatron-ollama ollama pull qwen2.5-coder:7b >/dev/null 2>&1 \
  || fail "ollama pull failed"
docker exec metatron-ollama ollama list | grep -E 'NAME|qwen2.5-coder:7b'

step "M1-3: build and start metatron-core"
cd "$SRC/core"
docker compose --env-file "$WF_ENV" --env-file "$CORE_ENV" up -d --build
for _ in $(seq 1 30); do
  health=$(curl -fsS 127.0.0.1:8095/health 2>/dev/null) && break
  sleep 2
done
[ -n "${health:-}" ] || { docker logs --tail 50 metatron-core; fail "no /health answer on 127.0.0.1:8095"; }
echo "/health: $health"
case "$health" in *anthropic*|*openai*) fail "a paid provider is enabled - zero-cost rule broken";; esac

step "M1-3: toolchains inside the image"
docker exec metatron-core sh -c 'java -version 2>&1 | head -1; node --version; git --version; python --version'

step "M1-3: full test suite as root inside the image (runs the agent-user isolation tests)"
docker run --rm -v "$SRC/core:/src:ro" -w /src -e PYTHONDONTWRITEBYTECODE=1 metatron-core:latest \
  python -m unittest discover -s tests -t .

step "M1-4: Ollama reachable from Core"
if ! docker inspect -f '{{json .NetworkSettings.Networks}}' metatron-ollama | grep -q '"metatron-workforce"'; then
  docker network connect metatron-workforce metatron-ollama
  echo "attached metatron-ollama to network metatron-workforce (it keeps its other networks)"
fi
docker exec metatron-core python -c "import socket; print('metatron-ollama resolves to', socket.gethostbyname('metatron-ollama'))" \
  || fail "Core still cannot resolve metatron-ollama"

step "M1-4/M1-5: one real completion from each free provider"
docker exec -i metatron-core python - <<'PY'
from metatron_core.llm import Gemini, Message, ProviderChain
ok = True
for p in ProviderChain.from_env().providers:
    try:
        text = p.complete([Message("user", "Reply with exactly: OK")], 64)
        print(f"{p.name}:{getattr(p, 'model', '')} -> {text.strip()[:40]!r}")
    except Exception as e:
        ok = False
        print(f"{p.name}:{getattr(p, 'model', '')} -> FAILED {type(e).__name__}: {str(e)[:300]}")
        if isinstance(p, Gemini):
            try:
                names = [m["name"] for m in p._list_models() if "flash" in m.get("name", "")]
                print("  flash models this key can use:", ", ".join(names) or "none")
            except Exception as e2:
                print(f"  listing models also failed: {type(e2).__name__}: {str(e2)[:200]}")
raise SystemExit(0 if ok else 1)
PY

step "M1-6: Telegram test bot (long polling: no tunnel or public URL needed)"
docker exec metatron-core python -c "import os,sys; sys.exit(0 if os.environ.get('TELEGRAM_ALLOWED_USER_ID') else 1)" \
  || fail "TELEGRAM_ALLOWED_USER_ID is not set: Core would ignore every message. Save it with set-secret.sh."
if has CORE_TELEGRAM_BOT_TOKEN; then
  line=""
  for _ in $(seq 1 15); do
    line=$(docker logs metatron-core 2>&1 | grep -E 'telegram: polling as|telegram poll failed' | tail -1) && [ -n "$line" ] && break
    sleep 2
  done
  echo "${line:-no telegram line in the logs yet}"
  case "$line" in *"polling as"*) echo "Send /help to that bot from your own Telegram account.";; esac
fi

step "Done"
echo "metatron-core is running on 127.0.0.1:8095 beside Workforce."
has CORE_TELEGRAM_BOT_TOKEN \
  || echo "Next (M1-6): create the test bot in BotFather, save its token with set-secret.sh CORE_TELEGRAM_BOT_TOKEN, re-run this script."
