#!/usr/bin/env python3
import ipaddress
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FRONTIER_KEYS = ("OPENAI_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY")


def _nonblank(name: str) -> str:
    value = os.getenv(name, "").strip()
    if not value:
        raise RuntimeError(f"{name} must not be blank")
    return value


def _assert_no_frontier_credentials() -> None:
    present = [name for name in FRONTIER_KEYS if os.getenv(name, "").strip()]
    if present:
        raise RuntimeError("external_frontier_credentials_forbidden:" + ",".join(present))


def _allowed_hosts() -> set[str]:
    configured = os.getenv("COGNITION_ALLOWED_BACKEND_HOSTS", "127.0.0.1,localhost,::1")
    return {item.strip().lower() for item in configured.split(",") if item.strip()}


def _is_private_address(value: str) -> bool:
    try:
        ip = ipaddress.ip_address(value)
        return ip.is_private or ip.is_loopback or ip.is_link_local
    except ValueError:
        return False


def _assert_backend_is_private(url: str) -> urllib.parse.ParseResult:
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise RuntimeError("invalid_cognition_backend_url")
    host = parsed.hostname.lower()
    if host in _allowed_hosts() or _is_private_address(host):
        return parsed
    try:
        addresses = {entry[4][0] for entry in socket.getaddrinfo(host, parsed.port or 80, type=socket.SOCK_STREAM)}
    except socket.gaierror as failure:
        raise RuntimeError("cognition_backend_dns_unresolved") from failure
    if not addresses or not all(_is_private_address(address) for address in addresses):
        raise RuntimeError("cognition_backend_must_be_private")
    return parsed


class Config:
    def __init__(self) -> None:
        _assert_no_frontier_credentials()
        self.listen_host = os.getenv("COGNITION_LISTEN_HOST", "0.0.0.0").strip() or "0.0.0.0"
        self.listen_port = int(os.getenv("COGNITION_LISTEN_PORT", "8091"))
        self.auth_token = _nonblank("COGNITION_AUTH_TOKEN")
        self.backend_url = _nonblank("COGNITION_BACKEND_URL").rstrip("/")
        _assert_backend_is_private(self.backend_url)
        self.model = _nonblank("COGNITION_MODEL")
        self.endpoint_id = os.getenv("COGNITION_ENDPOINT_ID", "metatron-cognition-node").strip() or "metatron-cognition-node"
        self.backend_timeout_seconds = float(os.getenv("COGNITION_BACKEND_TIMEOUT_SECONDS", "120"))
        self.max_body_bytes = int(os.getenv("COGNITION_MAX_BODY_BYTES", str(2 * 1024 * 1024)))


CONFIG: Config | None = None


def _require_string(data: dict, field: str) -> str:
    value = data.get(field)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"invalid_{field}")
    return value.strip()


def _validate_request(data: dict) -> dict:
    request_id = _require_string(data, "requestId")
    capability = _require_string(data, "capability")
    objective = _require_string(data, "objective")
    required_output = _require_string(data, "requiredOutput")
    context = data.get("context", "")
    if not isinstance(context, str):
        raise ValueError("invalid_context")
    evidence = data.get("evidenceReferences", [])
    if not isinstance(evidence, list) or any(not isinstance(item, str) for item in evidence):
        raise ValueError("invalid_evidenceReferences")
    provenance = data.get("provenance")
    if not isinstance(provenance, dict):
        raise ValueError("invalid_provenance")
    origin_type = _require_string(provenance, "originType")
    actor_id = _require_string(provenance, "actorId")
    if origin_type == "WORKER":
        _require_string(provenance, "workerId")
    return {
        "requestId": request_id,
        "capability": capability,
        "objective": objective,
        "requiredOutput": required_output,
        "context": context,
        "evidenceReferences": evidence,
        "provenance": provenance,
        "originType": origin_type,
        "actorId": actor_id,
    }


def _prompt(validated: dict) -> str:
    provenance = validated["provenance"]
    return "\n\n".join([
        "You are a Metatron-owned cognition endpoint. Produce only the requested institutional work product.",
        "Never claim an external effect without evidence. Never fabricate sources or execution results.",
        f"Request ID: {validated['requestId']}",
        f"Capability: {validated['capability']}",
        f"Objective: {validated['objective']}",
        f"Required output: {validated['requiredOutput']}",
        "Provenance: " + json.dumps(provenance, separators=(",", ":"), sort_keys=True),
        "Evidence references: " + json.dumps(validated["evidenceReferences"], separators=(",", ":")),
        "Context:\n" + validated["context"],
    ])


def _backend_infer(validated: dict) -> dict:
    assert CONFIG is not None
    body = {
        "model": CONFIG.model,
        "messages": [{"role": "user", "content": _prompt(validated)}],
        "temperature": 0.1,
        "stream": False,
    }
    payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        CONFIG.backend_url + "/v1/chat/completions",
        data=payload,
        method="POST",
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=CONFIG.backend_timeout_seconds) as response:
            raw = response.read(CONFIG.max_body_bytes)
            status = response.status
    except urllib.error.HTTPError as failure:
        raise RuntimeError(f"cognition_backend_http_{failure.code}") from failure
    except (urllib.error.URLError, TimeoutError, socket.timeout) as failure:
        raise RuntimeError("cognition_backend_unavailable") from failure
    if status < 200 or status >= 300:
        raise RuntimeError(f"cognition_backend_http_{status}")
    try:
        data = json.loads(raw.decode("utf-8"))
        text = data["choices"][0]["message"]["content"]
    except (ValueError, KeyError, IndexError, TypeError) as failure:
        raise RuntimeError("cognition_backend_malformed_response") from failure
    if not isinstance(text, str) or not text.strip():
        raise RuntimeError("cognition_backend_empty_response")
    usage = data.get("usage") if isinstance(data.get("usage"), dict) else {}
    return {
        "result": text.strip(),
        "endpointId": CONFIG.endpoint_id,
        "modelIdentity": str(data.get("model") or CONFIG.model),
        "requestReference": str(data.get("id") or validated["requestId"]),
        "usage": {
            "inputTokens": max(0, int(usage.get("prompt_tokens") or 0)),
            "outputTokens": max(0, int(usage.get("completion_tokens") or 0)),
        },
        "latencyMillis": max(0, int((time.monotonic() - started) * 1000)),
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "MetatronCognitionNode/1"

    def _json(self, status: int, body: dict) -> None:
        payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def _authorized(self) -> bool:
        assert CONFIG is not None
        return self.headers.get("Authorization", "") == "Bearer " + CONFIG.auth_token

    def do_GET(self) -> None:
        if self.path != "/healthz":
            self._json(404, {"error": "not_found"})
            return
        assert CONFIG is not None
        self._json(200, {
            "status": "UP",
            "endpointId": CONFIG.endpoint_id,
            "modelIdentity": CONFIG.model,
            "computeOwner": "METATRON_OWNED",
            "externalFrontierCredentials": False,
        })

    def do_POST(self) -> None:
        if self.path != "/v1/cognition":
            self._json(404, {"error": "not_found"})
            return
        if not self._authorized():
            self._json(401, {"error": "unauthorized"})
            return
        assert CONFIG is not None
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._json(400, {"error": "invalid_content_length"})
            return
        if length <= 0 or length > CONFIG.max_body_bytes:
            self._json(413, {"error": "invalid_body_size"})
            return
        try:
            incoming = json.loads(self.rfile.read(length).decode("utf-8"))
            if not isinstance(incoming, dict):
                raise ValueError("request_not_object")
            validated = _validate_request(incoming)
            result = _backend_infer(validated)
            self._json(200, result)
        except ValueError as failure:
            self._json(400, {"error": str(failure)[:160]})
        except RuntimeError as failure:
            self._json(503, {"error": str(failure)[:160]})
        except Exception:
            self._json(500, {"error": "cognition_node_internal_error"})

    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("cognition-node " + (fmt % args) + "\n")


def main() -> None:
    global CONFIG
    CONFIG = Config()
    server = ThreadingHTTPServer((CONFIG.listen_host, CONFIG.listen_port), Handler)
    print(json.dumps({
        "event": "COGNITION_NODE_READY",
        "listen": f"{CONFIG.listen_host}:{CONFIG.listen_port}",
        "endpointId": CONFIG.endpoint_id,
        "modelIdentity": CONFIG.model,
        "computeOwner": "METATRON_OWNED",
    }, separators=(",", ":")), flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
