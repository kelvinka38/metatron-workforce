import importlib.util
import json
import os
import threading
import unittest
import urllib.error
import urllib.request
from contextlib import contextmanager
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("metatron_frontier_broker", Path(__file__).with_name("server.py"))
broker = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(broker)


@contextmanager
def http_server(handler):
    instance = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    try:
        yield instance
    finally:
        instance.shutdown()
        instance.server_close()
        thread.join(timeout=2)


def valid_request(origin="HUMAN"):
    return {
        "provider": "OPENAI",
        "model": "model-test",
        "systemContext": "system",
        "userInput": "hello",
        "logicalRequestRef": "REQ-1",
        "caseRef": "CASE-1",
        "purpose": "semantic-primary",
        "reasonCode": "",
        "provenance": {
            "originType": origin,
            "actorId": "human" if origin == "HUMAN" else "WORKER-1",
            "workerId": "" if origin == "HUMAN" else "WORKER-1",
            "objectiveId": "OBJ-1" if origin == "WORKER" else "",
            "assignmentId": "ASG-1" if origin == "WORKER" else "",
            "stepId": "STEP-1" if origin == "WORKER" else "",
            "executionAttemptId": "ATT-1" if origin == "WORKER" else "",
        },
    }


class FrontierBrokerTest(unittest.TestCase):
    def setUp(self):
        broker.CONFIG = None

    def test_worker_origin_is_denied_before_provider_transport(self):
        with self.assertRaises(broker.BrokerError) as failure:
            broker.validate_broker_request(valid_request("WORKER"))
        self.assertEqual("worker_external_inference_denied", failure.exception.error_type)
        self.assertEqual(403, failure.exception.http_status)

    def test_system_origin_is_independently_denied(self):
        with self.assertRaises(broker.BrokerError) as failure:
            broker.validate_broker_request(valid_request("SYSTEM"))
        self.assertEqual("frontier_non_human_origin_denied", failure.exception.error_type)

    def test_provider_failure_types_are_stable(self):
        cases = [
            (401, b'{"error":{"message":"bad key"}}', "provider_auth_failure"),
            (402, b'{"error":{"message":"payment required"}}', "provider_billing_exhausted"),
            (429, b'{"error":{"message":"rate limit"}}', "provider_rate_limited"),
            (429, b'{"error":{"message":"quota exceeded"}}', "provider_quota_exhausted"),
            (503, b'{"error":{"message":"unavailable"}}', "provider_server_error"),
            (400, b'{"error":{"message":"invalid request"}}', "provider_invalid_request"),
        ]
        for status, raw, expected in cases:
            with self.subTest(status=status, expected=expected):
                self.assertEqual(expected, broker.classify_http_failure("OPENAI", status, raw).error_type)

    def test_missing_provider_secret_is_disabled_without_leaking_secret_names(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(broker.BrokerError) as failure:
                broker.provider_key("OPENAI")
        self.assertEqual("provider_disabled", failure.exception.error_type)
        self.assertNotIn("OPENAI_API_KEY", json.dumps(failure.exception.body()))

    def test_openai_adapter_returns_typed_usage_without_exposing_api_key(self):
        request = broker.validate_broker_request(valid_request("HUMAN"))
        captured = {}

        def fake_request_json(url, *, headers, body, timeout, provider):
            captured.update(url=url, headers=headers, body=body, provider=provider)
            return ({
                "id": "provider-ref",
                "choices": [{"message": {"content": "frontier answer"}}],
                "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14},
            }, {"x-ratelimit-remaining-requests": "9"})

        with patch.dict(os.environ, {"OPENAI_API_KEY": "super-secret"}, clear=True), \
                patch.object(broker, "request_json", fake_request_json):
            response = broker.openai_complete(request, 3.0)
        self.assertEqual("frontier answer", response["text"])
        self.assertEqual(10, response["usage"]["inputTokens"])
        self.assertEqual(4, response["usage"]["outputTokens"])
        self.assertEqual("Bearer super-secret", captured["headers"]["Authorization"])
        self.assertNotIn("super-secret", json.dumps(response))

    def test_http_broker_rejects_worker_even_when_provider_key_exists(self):
        env = {
            "FRONTIER_BROKER_AUTH_TOKEN": "internal-secret",
            "OPENAI_API_KEY": "provider-secret",
        }
        with patch.dict(os.environ, env, clear=True):
            broker.CONFIG = broker.Config()
            with http_server(broker.Handler) as instance:
                raw = json.dumps(valid_request("WORKER")).encode("utf-8")
                request = urllib.request.Request(
                    f"http://127.0.0.1:{instance.server_port}/v1/frontier/complete",
                    data=raw,
                    method="POST",
                    headers={"Content-Type": "application/json", "Authorization": "Bearer internal-secret"},
                )
                with self.assertRaises(urllib.error.HTTPError) as failure:
                    urllib.request.urlopen(request)
                self.assertEqual(403, failure.exception.code)
                body = json.loads(failure.exception.read().decode("utf-8"))
                self.assertEqual("worker_external_inference_denied", body["error"]["type"])

    def test_health_exposes_only_enabled_provider_names_not_secrets(self):
        env = {
            "FRONTIER_BROKER_AUTH_TOKEN": "internal-secret",
            "OPENAI_API_KEY": "provider-secret",
            "ANTHROPIC_API_KEY": "another-secret",
        }
        with patch.dict(os.environ, env, clear=True):
            broker.CONFIG = broker.Config()
            with http_server(broker.Handler) as instance:
                body = json.load(urllib.request.urlopen(f"http://127.0.0.1:{instance.server_port}/healthz"))
        self.assertEqual("EXTERNAL_PAID", body["computeOwner"])
        self.assertEqual(["HUMAN"], body["allowedOrigins"])
        self.assertEqual(["ANTHROPIC", "OPENAI"], body["enabledProviders"])
        encoded = json.dumps(body)
        self.assertNotIn("provider-secret", encoded)
        self.assertNotIn("another-secret", encoded)


if __name__ == "__main__":
    unittest.main()
