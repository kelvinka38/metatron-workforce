#!/usr/bin/env python3
import http.server
import os
import re
import subprocess
import urllib.parse

HOST = "0.0.0.0"
PORT = int(os.environ.get("PORT", "8091"))
MAX_BODY = 8 * 1024 * 1024
SAFE_REPO = r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"

GET_PATTERNS = [
    re.compile(rf"^repos/{SAFE_REPO}$"),
    re.compile(rf"^repos/{SAFE_REPO}/commits/[A-Za-z0-9._/%-]+$"),
    re.compile(rf"^repos/{SAFE_REPO}/zipball/[0-9a-fA-F]{{40}}$"),
    re.compile(rf"^repos/{SAFE_REPO}/git/ref/heads/[A-Za-z0-9._/%-]+$"),
    re.compile(rf"^repos/{SAFE_REPO}/git/commits/[0-9a-fA-F]{{40}}$"),
    re.compile(rf"^repos/{SAFE_REPO}/compare/[0-9a-fA-F]{{40}}\.\.\.[0-9a-fA-F]{{40}}$"),
    re.compile(rf"^repos/{SAFE_REPO}/pulls$"),
]
POST_PATTERNS = [
    re.compile(rf"^repos/{SAFE_REPO}/git/(blobs|trees|commits|refs)$"),
    re.compile(rf"^repos/{SAFE_REPO}/pulls$"),
]

def allowed(method: str, path: str) -> bool:
    decoded = urllib.parse.unquote(path)
    if "\x00" in decoded or "\\" in decoded or "//" in decoded:
        return False
    if decoded == ".." or decoded.startswith("../") or decoded.endswith("/..") or "/../" in decoded:
        return False
    patterns = GET_PATTERNS if method == "GET" else POST_PATTERNS if method == "POST" else []
    return any(p.fullmatch(path) for p in patterns)


def parse_status(stderr: bytes) -> int:
    text = stderr.decode("utf-8", "replace")
    match = re.search(r"HTTP\s+(\d{3})", text)
    return int(match.group(1)) if match else 502


def gh_api(method: str, endpoint: str, body: bytes | None):
    cmd = ["/usr/bin/gh", "api", "--hostname", "github.com", endpoint]
    if method != "GET":
        cmd += ["--method", method, "--input", "-"]
    try:
        proc = subprocess.run(
            cmd,
            input=body if body is not None else None,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=90,
            check=False,
            env={**os.environ, "GH_CONFIG_DIR": "/root/.config/gh"},
        )
    except subprocess.TimeoutExpired:
        return 504, b"", b"github_broker_timeout"
    if proc.returncode == 0:
        return (201 if method == "POST" else 200), proc.stdout, b""
    return parse_status(proc.stderr), b"", proc.stderr[:500]

class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "MetatronGitHubBroker/1.0"

    def log_message(self, fmt, *args):
        return

    def do_GET(self):
        self.handle_request("GET")

    def do_POST(self):
        self.handle_request("POST")

    def handle_request(self, method: str):
        parsed = urllib.parse.urlsplit(self.path)
        path = parsed.path.lstrip("/")
        if path == "health" and method == "GET":
            self.respond(200, b'{"status":"UP"}', "application/json")
            return
        if not allowed(method, path):
            self.respond(403, b'{"error":"github_broker_route_denied"}', "application/json")
            return
        endpoint = path + (("?" + parsed.query) if parsed.query else "")
        body = None
        if method == "POST":
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = -1
            if length < 0 or length > MAX_BODY:
                self.respond(413, b'{"error":"github_broker_body_too_large"}', "application/json")
                return
            body = self.rfile.read(length)
        status, output, error = gh_api(method, endpoint, body)
        if status >= 400:
            payload = b'{"error":"github_broker_upstream_failure","status":' + str(status).encode() + b'}'
            self.respond(status, payload, "application/json")
            return
        content_type = "application/zip" if "/zipball/" in path else "application/json"
        self.respond(status, output, content_type)

    def respond(self, status: int, body: bytes, content_type: str):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

if __name__ == "__main__":
    server = http.server.ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"METATRON_GITHUB_BROKER_READY port={PORT}", flush=True)
    server.serve_forever()
