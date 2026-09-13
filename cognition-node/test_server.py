import importlib.util
import json
import os
import threading
import unittest
import urllib.error
import urllib.request
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("metatron_cognition_server", Path(__file__).with_name("server.py"))
server = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(server)


class BackendHandler(BaseHTTPRequestHandler):
    requests = []

    def do_POST(self):
        if self.path != "/v1/chat/completions":
            self.send_response(404)
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", "0"))
        body = json.loads(self.rfile.read(length).decode("utf-8"))
        self.__class__.requests.append({"headers": dict(self.headers), "body": body})
        payload = json.dumps({
            "id": "local-request-1",
            "model": "qualified-open-weight-test",
            "choices": [{"message": {"content": "useful internal cognition"}}],
            "usage": {"prompt_tokens": 17, "completion_tokens": 4},
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        pass


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


def valid_request():
    return {
        "requestId": "REQ-1",
        "capability": "worker.cognition",
        "objective": "complete governed work",
        "context": "context",
        "evidenceReferences": ["evidence:1"],
        "requiredOutput": "strict result",
        "provenance": {
            "originType": "WORKER",
            "actorId": "WORKER-ONE",
            "workerId": "WORKER-ONE",
            "objectiveId": "OBJ-1",
            "assignmentId": "ASG-1",
            "stepId": "STEP-1",
            "executionAttemptId": "ATT-1",
        },
    }


class CognitionNodeTest(unittest.TestCase):
    def setUp(self):
        BackendHandler.requests = []
        server.CONFIG = None

    def config_env(self, backend_url):
        return {
            "COGNITION_AUTH_TOKEN": "internal-secret",
            "COGNITION_BACKEND_URL": backend_url,
            "COGNITION_ALLOWED_BACKEND_HOSTS": "127.0.0.1,localhost,::1",
            "COGNITION_MODEL": "qualified-open-weight-test",
            "COGNITION_ENDPOINT_ID": "node-test",
        }

    def test_frontier_credentials_fail_startup(self):
        env = self.config_env("http://127.0.0.1:9000") | {"OPENAI_API_KEY": "forbidden"}
        with patch.dict(os.environ, env, clear=True):
            with self.assertRaisesRegex(RuntimeError, "external_frontier_credentials_forbidden"):
                server.Config()

    def test_public_backend_is_rejected(self):
        env = self.config_env("https://8.8.8.8:443")
        with patch.dict(os.environ, env, clear=True):
            with self.assertRaisesRegex(RuntimeError, "cognition_backend_must_be_private"):
                server.Config()

    def test_worker_lineage_is_required(self):
        request = valid_request()
        request["provenance"]["workerId"] = ""
        with self.assertRaisesRegex(ValueError, "invalid_workerId"):
            server._validate_request(request)

    def test_private_backend_inference_preserves_attribution_without_frontier_auth(self):
        with http_server(BackendHandler) as backend:
            env = self.config_env(f"http://127.0.0.1:{backend.server_port}")
            with patch.dict(os.environ, env, clear=True):
                server.CONFIG = server.Config()
                result = server._backend_infer(server._validate_request(valid_request()))
        self.assertEqual("useful internal cognition", result["result"])
        self.assertEqual("node-test", result["endpointId"])
        self.assertEqual("qualified-open-weight-test", result["modelIdentity"])
        self.assertEqual(17, result["usage"]["inputTokens"])
        self.assertEqual(4, result["usage"]["outputTokens"])
        self.assertEqual(1, len(BackendHandler.requests))
        self.assertNotIn("Authorization", BackendHandler.requests[0]["headers"])
        prompt = BackendHandler.requests[0]["body"]["messages"][0]["content"]
        self.assertIn('"workerId":"WORKER-ONE"', prompt)
        self.assertIn("Never claim an external effect without evidence", prompt)

    def test_node_http_requires_internal_bearer_and_returns_metatron_owned_health(self):
        with http_server(BackendHandler) as backend:
            env = self.config_env(f"http://127.0.0.1:{backend.server_port}")
            with patch.dict(os.environ, env, clear=True):
                server.CONFIG = server.Config()
                with http_server(server.Handler) as node:
                    health = json.load(urllib.request.urlopen(f"http://127.0.0.1:{node.server_port}/healthz"))
                    self.assertEqual("METATRON_OWNED", health["computeOwner"])
                    self.assertFalse(health["externalFrontierCredentials"])
                    raw = json.dumps(valid_request()).encode("utf-8")
                    unauthorized = urllib.request.Request(
                        f"http://127.0.0.1:{node.server_port}/v1/cognition", data=raw,
                        headers={"Content-Type": "application/json"}, method="POST")
                    with self.assertRaises(urllib.error.HTTPError) as failure:
                        urllib.request.urlopen(unauthorized)
                    self.assertEqual(401, failure.exception.code)
                    authorized = urllib.request.Request(
                        f"http://127.0.0.1:{node.server_port}/v1/cognition", data=raw,
                        headers={"Content-Type": "application/json", "Authorization": "Bearer internal-secret"}, method="POST")
                    response = json.load(urllib.request.urlopen(authorized))
                    self.assertEqual("useful internal cognition", response["result"])


if __name__ == "__main__":
    unittest.main()
