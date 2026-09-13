#!/usr/bin/env python3
import json
import os
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PROVIDERS = {"OPENAI", "GOOGLE", "ANTHROPIC"}


def nonblank(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} must not be blank")
    return value


def provider_key(provider: str) -> str:
    variable = {
        "OPENAI": "OPENAI_API_KEY",
        "GOOGLE": "GEMINI_API_KEY",
        "ANTHROPIC": "ANTHROPIC_API_KEY",
    }[provider]
    value = os.getenv(variable, "").strip()
    if not value:
        raise BrokerError("provider_disabled", 503, provider=provider)
    return value


class BrokerError(RuntimeError):
    def __init__(self, error_type: str, http_status: int, *, provider: str = "", detail: str = ""):
        super().__init__(error_type)
        self.error_type = error_type
        self.http_status = http_status
        self.provider = provider
        self.detail = detail[:240]

    def body(self) -> dict:
        body = {"error": {"type": self.error_type}}
        if self.provider:
            body["error"]["provider"] = self.provider
        if self.detail:
            body["error"]["detail"] = self.detail
        return body


def compact_error(raw: bytes) -> str:
    try:
        data = json.loads(raw.decode("utf-8", "replace"))
        if isinstance(data, dict):
            error = data.get("error")
            if isinstance(error, dict):
                message = error.get("message") or error.get("status") or error.get("type")
                if message:
                    return str(message).replace("\n", " ").replace("\r", " ")[:240]
            if isinstance(error, str):
                return error.replace("\n", " ").replace("\r", " ")[:240]
    except Exception:
        pass
    return raw.decode("utf-8", "replace").replace("\n", " ").replace("\r", " ")[:240]


def classify_http_failure(provider: str, status: int, raw: bytes) -> BrokerError:
    detail = compact_error(raw)
    folded = detail.lower()
    if status in (401, 403):
        return BrokerError("provider_auth_failure", 503, provider=provider, detail=detail)
    if status == 402 or any(token in folded for token in ("billing", "payment required", "credit balance")):
        return BrokerError("provider_billing_exhausted", 503, provider=provider, detail=detail)
    if status == 429:
        if "quota" in folded and "rate" not in folded:
            return BrokerError("provider_quota_exhausted", 503, provider=provider, detail=detail)
        return BrokerError("provider_rate_limited", 503, provider=provider, detail=detail)
    if 500 <= status <= 599:
        return BrokerError("provider_server_error", 503, provider=provider, detail=detail)
    if 400 <= status <= 499:
        return BrokerError("provider_invalid_request", 502, provider=provider, detail=detail)
    return BrokerError("provider_unexpected_http_status", 502, provider=provider, detail=detail)


def request_json(url: str, *, headers: dict, body: dict, timeout: float, provider: str) -> tuple[dict, dict]:
    payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=payload, method="POST", headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read(4 * 1024 * 1024)
            status = response.status
            response_headers = dict(response.headers.items())
    except urllib.error.HTTPError as failure:
        raw = failure.read(1024 * 1024)
        raise classify_http_failure(provider, failure.code, raw) from failure
    except (urllib.error.URLError, TimeoutError, socket.timeout) as failure:
        reason = str(getattr(failure, "reason", failure)).lower()
        kind = "provider_network_timeout" if "timed out" in reason or isinstance(failure, (TimeoutError, socket.timeout)) else "provider_network_failure"
        raise BrokerError(kind, 503, provider=provider) from failure
    if status < 200 or status >= 300:
        raise classify_http_failure(provider, status, raw)
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as failure:
        raise BrokerError("provider_invalid_response", 502, provider=provider) from failure
    if not isinstance(parsed, dict):
        raise BrokerError("provider_invalid_response", 502, provider=provider)
    return parsed, response_headers


def require_text(data: dict, field: str) -> str:
    value = data.get(field)
    if not isinstance(value, str) or not value.strip():
        raise BrokerError("invalid_broker_request", 400, detail=f"missing {field}")
    return value.strip()


def validate_broker_request(data: dict) -> dict:
    provider = require_text(data, "provider").upper()
    if provider not in PROVIDERS:
        raise BrokerError("provider_disabled", 503, provider=provider)
    model = require_text(data, "model")
    system_context = require_text(data, "systemContext")
    user_input = require_text(data, "userInput")
    provenance = data.get("provenance")
    if not isinstance(provenance, dict):
        raise BrokerError("invalid_broker_request", 400, detail="missing provenance")
    origin = str(provenance.get("originType") or "").strip().upper()
    if origin != "HUMAN":
        raise BrokerError("worker_external_inference_denied" if origin == "WORKER" else "frontier_non_human_origin_denied", 403, provider=provider)
    actor_id = str(provenance.get("actorId") or "").strip()
    if not actor_id:
        raise BrokerError("invalid_broker_request", 400, detail="missing actorId")
    return {
        "provider": provider,
        "model": model,
        "systemContext": system_context,
        "userInput": user_input,
        "logicalRequestRef": str(data.get("logicalRequestRef") or "").strip(),
        "caseRef": str(data.get("caseRef") or "").strip(),
        "purpose": str(data.get("purpose") or "").strip(),
        "reasonCode": str(data.get("reasonCode") or "").strip(),
        "provenance": provenance,
    }


def openai_complete(request: dict, timeout: float) -> dict:
    provider = "OPENAI"
    root, headers = request_json(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": "Bearer " + provider_key(provider), "Content-Type": "application/json"},
        body={
            "model": request["model"],
            "messages": [
                {"role": "system", "content": request["systemContext"]},
                {"role": "user", "content": request["userInput"]},
            ],
        }, timeout=timeout, provider=provider)
    try:
        text = root["choices"][0]["message"]["content"]
    except (KeyError, IndexError, TypeError) as failure:
        raise BrokerError("provider_invalid_response", 502, provider=provider) from failure
    if not isinstance(text, str) or not text.strip():
        raise BrokerError("provider_invalid_response", 502, provider=provider)
    usage = root.get("usage") if isinstance(root.get("usage"), dict) else {}
    return response_body(provider, request["model"], text, root.get("id"), usage.get("prompt_tokens"), usage.get("completion_tokens"), usage.get("total_tokens"), headers)


def google_complete(request: dict, timeout: float) -> dict:
    provider = "GOOGLE"
    model = urllib.parse.quote(request["model"], safe="-._")
    key = urllib.parse.quote(provider_key(provider), safe="")
    root, headers = request_json(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}",
        headers={"Content-Type": "application/json"},
        body={
            "systemInstruction": {"parts": [{"text": request["systemContext"]}]},
            "contents": [{"role": "user", "parts": [{"text": request["userInput"]}]}],
        }, timeout=timeout, provider=provider)
    try:
        text = root["candidates"][0]["content"]["parts"][0]["text"]
    except (KeyError, IndexError, TypeError) as failure:
        raise BrokerError("provider_invalid_response", 502, provider=provider) from failure
    if not isinstance(text, str) or not text.strip():
        raise BrokerError("provider_invalid_response", 502, provider=provider)
    usage = root.get("usageMetadata") if isinstance(root.get("usageMetadata"), dict) else {}
    return response_body(provider, request["model"], text, root.get("responseId"), usage.get("promptTokenCount"), usage.get("candidatesTokenCount"), usage.get("totalTokenCount"), headers)


def anthropic_complete(request: dict, timeout: float) -> dict:
    provider = "ANTHROPIC"
    root, headers = request_json(
        "https://api.anthropic.com/v1/messages",
        headers={
            "x-api-key": provider_key(provider),
            "anthropic-version": "2023-06-01",
            "Content-Type": "application/json",
        },
        body={
            "model": request["model"],
            "max_tokens": 2048,
            "system": request["systemContext"],
            "messages": [{"role": "user", "content": request["userInput"]}],
        }, timeout=timeout, provider=provider)
    try:
        text = root["content"][0]["text"]
    except (KeyError, IndexError, TypeError) as failure:
        raise BrokerError("provider_invalid_response", 502, provider=provider) from failure
    if not isinstance(text, str) or not text.strip():
        raise BrokerError("provider_invalid_response", 502, provider=provider)
    usage = root.get("usage") if isinstance(root.get("usage"), dict) else {}
    input_tokens = usage.get("input_tokens")
    output_tokens = usage.get("output_tokens")
    total = input_tokens + output_tokens if isinstance(input_tokens, int) and isinstance(output_tokens, int) else None
    return response_body(provider, request["model"], text, root.get("id"), input_tokens, output_tokens, total, headers)


def token(value) -> int:
    return value if isinstance(value, int) and value >= 0 else -1


def response_body(provider: str, model: str, text: str, reference, input_tokens, output_tokens, total_tokens, headers: dict) -> dict:
    telemetry = {}
    for name, value in headers.items():
        lowered = name.lower()
        if "ratelimit" in lowered and isinstance(value, str) and value.strip():
            telemetry[lowered] = value.strip()[:160]
    return {
        "provider": provider,
        "model": model,
        "text": text.strip(),
        "requestReference": str(reference or ""),
        "usage": {
            "inputTokens": token(input_tokens),
            "outputTokens": token(output_tokens),
            "totalTokens": token(total_tokens),
        },
        "telemetry": telemetry,
    }


class Config:
    def __init__(self):
        self.host = os.getenv("FRONTIER_BROKER_LISTEN_HOST", "0.0.0.0").strip() or "0.0.0.0"
        self.port = int(os.getenv("FRONTIER_BROKER_LISTEN_PORT", "8092"))
        self.auth_token = nonblank("FRONTIER_BROKER_AUTH_TOKEN")
        self.timeout = float(os.getenv("FRONTIER_PROVIDER_TIMEOUT_SECONDS", "30"))
        self.max_body_bytes = int(os.getenv("FRONTIER_BROKER_MAX_BODY_BYTES", str(2 * 1024 * 1024)))


CONFIG: Config | None = None


def complete(request: dict) -> dict:
    assert CONFIG is not None
    provider = request["provider"]
    if provider == "OPENAI":
        return openai_complete(request, CONFIG.timeout)
    if provider == "GOOGLE":
        return google_complete(request, CONFIG.timeout)
    if provider == "ANTHROPIC":
        return anthropic_complete(request, CONFIG.timeout)
    raise BrokerError("provider_disabled", 503, provider=provider)


class Handler(BaseHTTPRequestHandler):
    server_version = "MetatronFrontierBroker/1"

    def write_json(self, status: int, body: dict):
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def authorized(self) -> bool:
        assert CONFIG is not None
        return self.headers.get("Authorization", "") == "Bearer " + CONFIG.auth_token

    def do_GET(self):
        if self.path != "/healthz":
            self.write_json(404, {"error": "not_found"})
            return
        enabled = [provider for provider in sorted(PROVIDERS) if os.getenv({"OPENAI":"OPENAI_API_KEY","GOOGLE":"GEMINI_API_KEY","ANTHROPIC":"ANTHROPIC_API_KEY"}[provider], "").strip()]
        self.write_json(200, {"status": "UP", "computeOwner": "EXTERNAL_PAID", "allowedOrigins": ["HUMAN"], "enabledProviders": enabled})

    def do_POST(self):
        if self.path != "/v1/frontier/complete":
            self.write_json(404, {"error": "not_found"})
            return
        if not self.authorized():
            self.write_json(401, {"error": {"type": "broker_unauthorized"}})
            return
        assert CONFIG is not None
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self.write_json(400, {"error": {"type": "invalid_broker_request"}})
            return
        if length <= 0 or length > CONFIG.max_body_bytes:
            self.write_json(413, {"error": {"type": "invalid_broker_request"}})
            return
        try:
            raw = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(raw, dict):
                raise BrokerError("invalid_broker_request", 400)
            request = validate_broker_request(raw)
            self.write_json(200, complete(request))
        except BrokerError as failure:
            self.write_json(failure.http_status, failure.body())
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.write_json(400, {"error": {"type": "invalid_broker_request"}})
        except Exception:
            self.write_json(500, {"error": {"type": "frontier_broker_internal_error"}})

    def log_message(self, fmt: str, *args):
        sys.stderr.write("frontier-broker " + (fmt % args) + "\n")


def main():
    global CONFIG
    CONFIG = Config()
    httpd = ThreadingHTTPServer((CONFIG.host, CONFIG.port), Handler)
    print(json.dumps({"event":"FRONTIER_BROKER_READY","listen":f"{CONFIG.host}:{CONFIG.port}","allowedOrigins":["HUMAN"]}, separators=(",", ":")), flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
