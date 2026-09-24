"""Zero-cost LLM provider chain for Metatron Core.

Founder rule: Metatron never spends money on LLM inference.
Enforced in code, not in documents:
  * Providers: Gemini (free-tier project), OpenRouter ':free' models, local Ollama.
  * Anthropic/OpenAI keys are only used when METATRON_ALLOW_PREPAID_CREDIT=true, i.e. the
    founder has confirmed those accounts hold only existing/promo credit with auto-reload OFF,
    so the maximum possible spend is credit already sitting there - never a new charge.
  * OpenRouter models without the ':free' suffix are rejected at startup.
  * The Gemini key MUST belong to an AI Studio project with NO billing account attached.

Order: gemini -> (anthropic, openai if allowed) -> openrouter -> ollama.
A provider that returns 429/5xx/timeout, or rejects one request (400), is skipped for a
cool-down and the next is tried. Key, credit and billing errors (401/402/403, or a 400 that names
the key or billing) disable that provider for the process lifetime.
"""
from __future__ import annotations

import io
import json
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field


class LlmUnavailable(RuntimeError):
    pass


class EmptyReply(RuntimeError):
    """The provider answered but gave no text (e.g. a safety block). Try the next one, no cool-down."""


FOREVER = 10 ** 9
# A 400 carrying one of these is about the account, not the request: never retry it.
ACCOUNT_ERRORS = ("API_KEY_INVALID", "API key not valid", "FAILED_PRECONDITION", "billing", "credit")


def cooldown_for(code: int, detail: str, retry_after: str | None) -> float:
    """Seconds to skip a provider after an HTTP error. Free-tier 429s are mostly per-minute limits."""
    if code in (401, 402, 403):
        return FOREVER
    if code == 400:
        return FOREVER if any(m.lower() in detail.lower() for m in ACCOUNT_ERRORS) else 120
    if code == 429:
        if retry_after and retry_after.strip().isdigit():
            return int(retry_after)
        return 3600 if is_daily_quota(detail) else 60
    return 120


@dataclass
class Message:
    role: str  # "system" | "user" | "assistant"
    content: str


def _post(url: str, body: dict, headers: dict, timeout: float) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json", **headers}
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read())


@dataclass
class Provider:
    name: str
    cooldown_until: float = 0.0

    def available(self) -> bool:
        return time.time() >= self.cooldown_until

    def cool_down(self, seconds: float) -> None:
        self.cooldown_until = time.time() + seconds

    def complete(self, messages: list[Message], max_tokens: int) -> str:  # pragma: no cover
        raise NotImplementedError


def _split_system(messages):
    system = "\n".join(m.content for m in messages if m.role == "system")
    rest = [m for m in messages if m.role != "system"]
    return system, rest


@dataclass
class Gemini(Provider):
    key: str = ""
    model: str = "gemini-2.5-flash"
    exhausted: dict = field(default_factory=dict)  # model -> time it may be tried again
    sleep: object = time.sleep

    def complete(self, messages, max_tokens):
        """Each free model has its own daily quota and capacity: on a retired model (404), a used-up
        daily quota (429 per day) or an overloaded model (5xx), move to the next free Flash model
        instead of giving up on Gemini."""
        for _ in range(4):
            try:
                return self._generate(messages, max_tokens)
            except EmptyReply as e:
                if "MALFORMED" not in str(e):
                    raise  # a safety block is about the request, not the model
                # This model keeps answering in a broken format: rest it and use the next one.
                self.exhausted[self.model] = time.time() + 1800
                nxt = next((m for m in flash_candidates(self._list_models())
                            if self.exhausted.get(m, 0) <= time.time()), "")
                if not nxt:
                    raise
                print(f"gemini: model {self.model} gave a malformed reply, switching to {nxt}", flush=True)
                self.model = nxt
                continue
            except urllib.error.HTTPError as e:
                if e.code not in (404, 429) and e.code < 500:
                    raise
                body = e.read()
                text = body.decode(errors="replace")
                if e.code == 429 and not is_daily_quota(text):
                    # Per-minute limit: a short wait beats a slow local model for this step.
                    wait = retry_delay(text)
                    if wait <= 60:
                        print(f"gemini: {self.model} per-minute limit, waiting {wait}s", flush=True)
                        self.sleep(wait)
                        continue
                    raise urllib.error.HTTPError(e.url, e.code, e.msg, e.hdrs, io.BytesIO(body)) from None
                pause = {404: FOREVER, 429: 6 * 3600}.get(e.code, 600)  # 5xx: overloaded for now
                self.exhausted[self.model] = time.time() + pause
                nxt = next((m for m in flash_candidates(self._list_models())
                            if self.exhausted.get(m, 0) <= time.time()), "")
                if not nxt:
                    raise urllib.error.HTTPError(e.url, e.code, e.msg, e.hdrs, io.BytesIO(body)) from None
                why = {404: "not found", 429: "out of daily free quota"}.get(e.code, f"overloaded ({e.code})")
                print(f"gemini: model {self.model} {why}, switching to {nxt}", flush=True)
                self.model = nxt
        return self._generate(messages, max_tokens)

    def _generate(self, messages, max_tokens):
        system, rest = _split_system(messages)
        contents = [{"role": "model" if m.role == "assistant" else "user", "parts": [{"text": m.content}]}
                    for m in rest]
        if self.model.startswith("gemini-2.5"):
            # 2.5 models count thinking against maxOutputTokens; cap it so the answer is not starved.
            config = {"maxOutputTokens": max_tokens + 1024, "thinkingConfig": {"thinkingBudget": 1024}}
        else:
            # Newer models think by default and can be slow on the free tier: ask for light thinking.
            config = {"maxOutputTokens": max_tokens + 2048, "thinkingConfig": {"thinkingLevel": "low"}}
        # Every agent reply is one JSON object; asking for JSON output also stops the model from
        # trying a native function call (finishReason MALFORMED_FUNCTION_CALL, empty text).
        config["responseMimeType"] = "application/json"
        body = {"contents": contents, "generationConfig": config}
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        url = f"{GEMINI_API}/models/{self.model}:generateContent"
        return gemini_text(_post(url, body, {"x-goog-api-key": self.key}, timeout=120))

    def _list_models(self) -> list[dict]:
        req = urllib.request.Request(f"{GEMINI_API}/models?pageSize=1000", headers={"x-goog-api-key": self.key})
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read()).get("models", [])


GEMINI_API = "https://generativelanguage.googleapis.com/v1beta"
_STABLE_FLASH = re.compile(r"gemini-(\d+(?:\.\d+)?)-flash")
_STABLE_LITE = re.compile(r"gemini-(\d+(?:\.\d+)?)-flash-lite")


def flash_candidates(models: list[dict]) -> list[str]:
    """Free text models to try, best first: stable Flash newest first, then stable Flash-Lite, then aliases."""
    names = [m.get("name", "").removeprefix("models/") for m in models
             if "generateContent" in m.get("supportedGenerationMethods", [])]

    def newest_first(pattern):
        found = [(float(m.group(1)), n) for n in names if (m := pattern.fullmatch(n))]
        return [n for _, n in sorted(found, reverse=True)]

    aliases = [a for a in ("gemini-flash-latest", "gemini-flash-lite-latest") if a in names]
    return newest_first(_STABLE_FLASH) + newest_first(_STABLE_LITE) + aliases


def pick_flash_model(models: list[dict]) -> str:
    """Newest stable 'gemini-<version>-flash' that supports generateContent, else the flash alias."""
    stable = [n for n in flash_candidates(models) if _STABLE_FLASH.fullmatch(n) or n == "gemini-flash-latest"]
    return stable[0] if stable else ""


def retry_delay(detail: str, default: int = 30) -> int:
    """Seconds Gemini asks us to wait ("retryDelay": "23s"), else a default."""
    m = re.search(r'"retryDelay"\s*:\s*"(\d+)(?:\.\d+)?s"', detail)
    return int(m.group(1)) + 1 if m else default


def is_daily_quota(detail: str) -> bool:
    return "perday" in detail.lower().replace(" ", "")


def gemini_text(data: dict) -> str:
    """The answer text of a generateContent response; EmptyReply when blocked or empty."""
    candidates = data.get("candidates") or []
    if not candidates:
        reason = (data.get("promptFeedback") or {}).get("blockReason", "no candidates")
        raise EmptyReply(f"gemini returned no candidates ({reason})")
    parts = (candidates[0].get("content") or {}).get("parts") or []
    text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
    if not text.strip():
        raise EmptyReply(f"gemini returned no text (finishReason={candidates[0].get('finishReason')})")
    return text


@dataclass
class Anthropic(Provider):
    key: str = ""
    model: str = "claude-haiku-4-5-20251001"

    def complete(self, messages, max_tokens):
        system, rest = _split_system(messages)
        body = {"model": self.model, "max_tokens": max_tokens,
                "messages": [{"role": m.role, "content": m.content} for m in rest]}
        if system:
            body["system"] = system
        data = _post("https://api.anthropic.com/v1/messages", body,
                     {"x-api-key": self.key, "anthropic-version": "2023-06-01"}, timeout=120)
        return "".join(b.get("text", "") for b in data.get("content", []) if b.get("type") == "text")


@dataclass
class OpenAiCompatible(Provider):
    key: str = ""
    model: str = ""
    url: str = ""

    def complete(self, messages, max_tokens):
        body = {"model": self.model, "max_tokens": max_tokens,
                "messages": [{"role": m.role, "content": m.content} for m in messages]}
        data = _post(self.url, body, {"Authorization": f"Bearer {self.key}"}, timeout=120)
        return data["choices"][0]["message"]["content"] or ""


@dataclass
class Ollama(Provider):
    url: str = "http://metatron-ollama:11434"
    model: str = "qwen2.5-coder:7b"

    def complete(self, messages, max_tokens):
        body = {"model": self.model, "stream": False, "options": {"num_predict": max_tokens},
                "messages": [{"role": m.role, "content": m.content} for m in messages]}
        data = _post(f"{self.url}/api/chat", body, {}, timeout=600)
        return data["message"]["content"]


@dataclass
class ProviderChain:
    providers: list[Provider] = field(default_factory=list)
    last_used: str = ""
    # The local model is weak and slow for coding: when free Gemini is only briefly unavailable
    # (overload, per-minute limits), wait for it up to this long before falling back.
    fallback_wait: float = 300
    sleep: object = time.sleep

    @classmethod
    def from_env(cls) -> "ProviderChain":
        env = os.environ
        chain: list[Provider] = []
        if env.get("GEMINI_API_KEY"):
            chain.append(Gemini("gemini", key=env["GEMINI_API_KEY"],
                                model=env.get("CORE_GEMINI_MODEL", "gemini-2.5-flash")))
        if env.get("METATRON_ALLOW_PREPAID_CREDIT", "").lower() == "true":
            if env.get("ANTHROPIC_API_KEY"):
                chain.append(Anthropic("anthropic", key=env["ANTHROPIC_API_KEY"],
                                       model=env.get("CORE_ANTHROPIC_MODEL", "claude-haiku-4-5-20251001")))
            if env.get("OPENAI_API_KEY"):
                chain.append(OpenAiCompatible("openai", key=env["OPENAI_API_KEY"],
                                              model=env.get("CORE_OPENAI_MODEL", "gpt-4.1-mini"),
                                              url="https://api.openai.com/v1/chat/completions"))
        if env.get("OPENROUTER_FREE_API_KEY"):
            model = env.get("OPENROUTER_FREE_MODEL", "")
            if not model.endswith(":free"):
                raise ValueError(f"OpenRouter model '{model}' is not ':free' - refusing (zero-cost rule)")
            chain.append(OpenAiCompatible("openrouter", key=env["OPENROUTER_FREE_API_KEY"], model=model,
                                          url="https://openrouter.ai/api/v1/chat/completions"))
        chain.append(Ollama("ollama", url=env.get("OLLAMA_URL", "http://metatron-ollama:11434"),
                            model=env.get("CORE_OLLAMA_MODEL", "qwen2.5-coder:7b")))
        return cls(chain)

    def complete(self, messages: list[Message], max_tokens: int = 4096) -> str:
        errors: list[str] = []
        primary = [p for p in self.providers if p.name != "ollama"]
        last_resort = [p for p in self.providers if p.name == "ollama"]
        deadline = time.time() + self.fallback_wait
        told = False
        while primary:
            for p in primary:
                if p.available():
                    out = self._try(p, messages, max_tokens, errors)
                    if out is not None:
                        return out
            if any(p.available() for p in primary):
                break  # a free provider answered badly for this request: waiting will not help
            wait = min(p.cooldown_until for p in primary) - time.time()
            if time.time() + wait > deadline:
                break  # none of them is back before the deadline (daily quota, bad key)
            if not told:
                print(f"llm: free models busy, waiting up to {self.fallback_wait:.0f}s before the local model",
                      flush=True)
                told = True
            self.sleep(max(1.0, min(wait, 30.0)))
        for p in last_resort:
            if p.available():
                out = self._try(p, messages, max_tokens, errors)
                if out is not None:
                    return out
        raise LlmUnavailable("; ".join(errors) or "no provider available")

    def _try(self, p: Provider, messages, max_tokens, errors: list[str]) -> str | None:
        """One provider attempt: its text, or None after recording why it failed."""
        began = time.time()
        try:
            out = p.complete(messages, max_tokens)
            self.last_used = f"{p.name}:{getattr(p, 'model', '')}"
            if time.time() - began > 30:
                print(f"llm: {self.last_used} took {time.time() - began:.0f}s", flush=True)
            return out
        except urllib.error.HTTPError as e:
            try:
                detail = e.read().decode(errors="replace")[:2000]
            except Exception:
                detail = ""
            p.cool_down(cooldown_for(e.code, detail, e.headers.get("Retry-After") if e.headers else None))
            errors.append(f"{p.name}: HTTP {e.code} {detail[:200]}".rstrip())
        except EmptyReply as e:
            errors.append(f"{p.name}: {e}")
        except Exception as e:  # timeout, bad payload, connection refused
            p.cool_down(120)
            errors.append(f"{p.name}: {type(e).__name__}: {e}")
        print(f"llm: {errors[-1][:200]} after {time.time() - began:.0f}s; trying the next provider",
              flush=True)
        return None
