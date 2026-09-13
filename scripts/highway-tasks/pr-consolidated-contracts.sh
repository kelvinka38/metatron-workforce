#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"

python3 - <<'PY'
from pathlib import Path
for name in ("gradlew",):
    data = Path(name).read_bytes()
    if b"\r\n" in data or b"\r" in data:
        raise SystemExit(f"CRLF_NOT_ALLOWED: {name}")
print("PR_CONSOLIDATED_POSIX_LINE_ENDINGS=PASS")
PY

bash -n scripts/runtime-conformance-point5.sh
python3 -m py_compile \
  runtime-sandbox/server.py \
  scripts/autonomy/p10_action_journal.py \
  scripts/autonomy/p10_collect.py \
  scripts/autonomy/p10_general_runtime_guard.py \
  scripts/autonomy/p10_ratify.py \
  scripts/sot_enforcement/inventory.py \
  scripts/cognition/qualification_runner.py

python3 scripts/cognition/qualification_runner.py self-test
python3 scripts/autonomy/p10_action_journal.py --self-test
python3 scripts/autonomy/p10_collect.py --self-test
python3 scripts/autonomy/p10_general_runtime_guard.py --self-test
python3 scripts/autonomy/p10_ratify.py --self-test

python3 - <<'PY'
from pathlib import Path
forbidden = (
    "interaction.llm.LlmProviderRouter",
    "interaction.llm.OpenAiLlmProviderClient",
    "interaction.llm.GoogleLlmProviderClient",
    "interaction.llm.AnthropicLlmProviderClient",
)
violations = []
for path in Path("src/main/java").rglob("*.java"):
    p = path.as_posix()
    if "/interaction/intelligence/" in p or "/interaction/llm/" in p:
        continue
    text = path.read_text(encoding="utf-8")
    for token in forbidden:
        if token in text:
            violations.append(f"{p} -> {token}")
if violations:
    print("INTELLIGENCE_PROVIDER_BOUNDARY=FAIL")
    print("\n".join(violations))
    raise SystemExit(1)
print("INTELLIGENCE_PROVIDER_BOUNDARY=PASS")
PY

python3 - <<'PY'
import importlib.util
from pathlib import Path
path = Path("acceptance/general-engineering-go1/slugify.py")
spec = importlib.util.spec_from_file_location("go1_slugify", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
expected = "hello-metatron-world"
actual = module.slugify("Hello   Metatron   World")
assert actual != expected, "GO1 fixture unexpectedly already fixed"
assert "--" in actual, actual
print("GO1_BASELINE_DEFECT_PRESENT=PASS")
PY

IMAGE="metatron-polyglot-sandbox-pr-${GITHUB_SHA:-local}"
cleanup() { docker image rm -f "$IMAGE" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker build -f runtime-sandbox/Dockerfile -t "$IMAGE" .
docker run --rm --entrypoint node "$IMAGE" --version
docker run --rm --entrypoint npm "$IMAGE" --version
docker run --rm --entrypoint pnpm "$IMAGE" --version
docker run --rm --entrypoint python3 "$IMAGE" --version
docker run --rm --entrypoint python3 "$IMAGE" -m pytest --version

echo "GENERAL_RUNTIME_POLYGLOT_IMAGE=PASS"
echo "GENERAL_ENGINEERING_GO1_CONSOLIDATED=PASS"
echo "TYPED_WORK_INGRESS_CONSOLIDATED=PASS"
echo "PR_CONSOLIDATED_CONTRACT_GATES=PASS"
