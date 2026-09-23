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

step "Env file $CORE_ENV"
if [ ! -f "$CORE_ENV" ]; then
  umask 077
  cat > "$CORE_ENV" <<EOF
# Fill CORE_TELEGRAM_BOT_TOKEN after creating the test bot with BotFather (M1-6), then re-run this script.
CORE_TELEGRAM_BOT_TOKEN=
CORE_TELEGRAM_WEBHOOK_SECRET=$(openssl rand -hex 24)
CORE_API_TOKEN=$(openssl rand -hex 24)
EOF
  echo "created (mode 600)"
else
  echo "kept existing"
fi
chmod 600 "$CORE_ENV"

step "M1-4: ollama pull qwen2.5-coder:7b (about 4.7 GB, first time only)"
docker exec metatron-ollama ollama pull qwen2.5-coder:7b >/dev/null
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

step "M1-4/M1-5: one real completion from each free provider"
docker exec metatron-core python - <<'PY'
from metatron_core.llm import Message, ProviderChain
ok = True
for p in ProviderChain.from_env().providers:
    try:
        text = p.complete([Message("user", "Reply with exactly: OK")], 64)
        print(f"{p.name}:{getattr(p, 'model', '')} -> {text.strip()[:40]!r}")
    except Exception as e:
        ok = False
        print(f"{p.name}:{getattr(p, 'model', '')} -> FAILED {type(e).__name__}: {str(e)[:300]}")
raise SystemExit(0 if ok else 1)
PY

step "Done"
echo "metatron-core is running on 127.0.0.1:8095 beside Workforce."
grep -q '^CORE_TELEGRAM_BOT_TOKEN=.\+' "$CORE_ENV" \
  || echo "Next (M1-6): create the test bot in BotFather, put its token in $CORE_ENV, re-run this script."
