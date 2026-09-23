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
A provider that returns 429/5xx/timeout is skipped for a cool-down and the next is tried.
Insufficient-credit errors (400/401/402/403) disable that provider for the process lifetime.
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field


class LlmUnavailable(RuntimeError):
    pass


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

    def complete(self, messages, max_tokens):
        system, rest = _split_system(messages)
        contents = [{"role": "model" if m.role == "assistant" else "user", "parts": [{"text": m.content}]}
                    for m in rest]
        body = {"contents": contents, "generationConfig": {"maxOutputTokens": max_tokens}}
        if system:
            body["systemInstruction"] = {"parts": [{"text": system}]}
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent"
        data = _post(url, body, {"x-goog-api-key": self.key}, timeout=120)
        parts = data["candidates"][0]["content"].get("parts", [])
        return "".join(p.get("text", "") for p in parts)


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
        errors = []
        for p in self.providers:
            if not p.available():
                continue
            try:
                out = p.complete(messages, max_tokens)
                self.last_used = f"{p.name}:{getattr(p, 'model', '')}"
                return out
            except urllib.error.HTTPError as e:
                if e.code in (400, 401, 402, 403):
                    p.cool_down(10 ** 9)  # bad key / no credit: never retry this process
                else:
                    p.cool_down(3600 if e.code == 429 else 120)
                errors.append(f"{p.name}: HTTP {e.code}")
            except Exception as e:  # timeout, bad payload, connection refused
                p.cool_down(120)
                errors.append(f"{p.name}: {type(e).__name__}: {e}")
        raise LlmUnavailable("; ".join(errors) or "no provider available")
