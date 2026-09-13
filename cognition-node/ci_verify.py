#!/usr/bin/env python3
import importlib.util
import py_compile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NODE = ROOT / "cognition-node"


def require(condition: bool, marker: str) -> None:
    if not condition:
        raise SystemExit(marker + "=FAIL")


def run_tests() -> None:
    spec = importlib.util.spec_from_file_location("cognition_node_test_server", NODE / "test_server.py")
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    suite = unittest.defaultTestLoader.loadTestsFromModule(module)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    require(result.wasSuccessful(), "COGNITION_NODE_UNIT_TESTS")
    print("COGNITION_NODE_UNIT_TESTS=PASS")


def main() -> None:
    py_compile.compile(str(NODE / "server.py"), doraise=True)
    py_compile.compile(str(NODE / "test_server.py"), doraise=True)
    print("COGNITION_NODE_PY_COMPILE=PASS")
    run_tests()

    compose = (NODE / "docker-compose.yml").read_text(encoding="utf-8")
    dockerfile = (NODE / "Dockerfile").read_text(encoding="utf-8")
    server = (NODE / "server.py").read_text(encoding="utf-8")

    for forbidden in ("OPENAI_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY"):
        require(forbidden not in compose, "COGNITION_NODE_SECRET_BOUNDARY")
        require(forbidden not in dockerfile, "COGNITION_NODE_IMAGE_SECRET_BOUNDARY")
    require("COGNITION_AUTH_TOKEN" in compose and "COGNITION_BACKEND_URL" in compose,
            "COGNITION_NODE_INTERNAL_CONFIG")
    require("read_only: true" in compose and "no-new-privileges:true" in compose,
            "COGNITION_NODE_CONTAINER_HARDENING")
    require("FROM python:3.13-slim" in dockerfile, "COGNITION_NODE_IMAGE_BASE")
    require("USER 10002:10002" in dockerfile, "COGNITION_NODE_IMAGE_NONROOT")
    require('ENTRYPOINT ["python3","/app/server.py"]' in dockerfile, "COGNITION_NODE_IMAGE_ENTRYPOINT")
    require("external_frontier_credentials_forbidden" in server, "COGNITION_NODE_FRONTIER_KEY_DENY")
    require("cognition_backend_must_be_private" in server, "COGNITION_NODE_PRIVATE_BACKEND")
    require('"computeOwner": "METATRON_OWNED"' in server, "COGNITION_NODE_COMPUTE_OWNER")
    require("Never claim an external effect without evidence" in server, "COGNITION_NODE_TRUTHFULNESS_PROMPT")

    print("COGNITION_NODE_SECRET_BOUNDARY=PASS")
    print("COGNITION_NODE_PRIVATE_PROVIDER_NEUTRAL_CONTRACT=PASS")
    print("COGNITION_NODE_CI_VERIFY=PASS")


if __name__ == "__main__":
    main()
