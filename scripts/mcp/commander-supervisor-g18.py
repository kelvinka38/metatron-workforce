#!/usr/bin/env python3
import json
import os
import signal
import subprocess
import sys
import threading
import time

if len(sys.argv) != 7:
    raise SystemExit("usage: commander-supervisor-g18.py <state_dir> <executable> <args_json> <cwd> <max_output_bytes> <timeout_seconds>")

state_dir, executable, args_json, cwd, max_output_raw, timeout_raw = sys.argv[1:]
args = json.loads(args_json)
if not isinstance(args, list) or any(not isinstance(v, str) for v in args):
    raise SystemExit("invalid args")
max_output = int(max_output_raw)
timeout_seconds = int(timeout_raw)
if max_output < 1024 or max_output > 1048576:
    raise SystemExit("invalid max output")
if timeout_seconds < 1 or timeout_seconds > 1800:
    raise SystemExit("invalid timeout")

os.makedirs(state_dir, exist_ok=True)
input_path = os.path.join(state_dir, "input.jsonl")
output_path = os.path.join(state_dir, "output.log")
control_path = os.path.join(state_dir, "control")
meta_path = os.path.join(state_dir, "meta.json")
for path in (input_path, output_path, control_path):
    if not os.path.exists(path):
        open(path, "ab").close()
    os.chmod(path, 0o600)

state_lock = threading.Lock()
written = 0
truncated = False
started_at = time.time()

proc = subprocess.Popen(
    [executable, *args],
    cwd=cwd or "/",
    stdin=subprocess.PIPE,
    stdout=subprocess.PIPE,
    stderr=subprocess.STDOUT,
    start_new_session=True,
    bufsize=0,
)


def atomic_meta(status, exit_code=None):
    payload = {
        "status": status,
        "supervisorPid": os.getpid(),
        "childPid": proc.pid,
        "executable": executable,
        "args": args,
        "cwd": cwd or "/",
        "startedAt": started_at,
        "updatedAt": time.time(),
        "exitCode": exit_code,
        "outputTruncated": truncated,
    }
    tmp = meta_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, separators=(",", ":"))
        fh.flush()
        os.fsync(fh.fileno())
    os.chmod(tmp, 0o600)
    os.replace(tmp, meta_path)


def drain_output():
    global written, truncated
    assert proc.stdout is not None
    with open(output_path, "ab", buffering=0) as out:
        while True:
            chunk = proc.stdout.read(4096)
            if not chunk:
                break
            with state_lock:
                remaining = max_output - written
                if remaining > 0:
                    emit = chunk[:remaining]
                    out.write(emit)
                    written += len(emit)
                if len(chunk) > max(remaining, 0):
                    truncated = True


reader = threading.Thread(target=drain_output, name="commander-output", daemon=True)
reader.start()
atomic_meta("RUNNING")

input_offset = 0
terminated = False
try:
    while proc.poll() is None:
        if time.time() - started_at > timeout_seconds:
            terminated = True
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            time.sleep(0.5)
            if proc.poll() is None:
                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            break

        try:
            control = open(control_path, "r", encoding="utf-8", errors="replace").read()
        except OSError:
            control = ""
        if "TERMINATE" in control:
            terminated = True
            try:
                os.killpg(proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            break

        try:
            with open(input_path, "r", encoding="utf-8", errors="strict") as inp:
                inp.seek(input_offset)
                lines = inp.readlines()
                input_offset = inp.tell()
        except OSError:
            lines = []
        for line in lines:
            try:
                item = json.loads(line)
                data_hex = item.get("dataHex", "")
                data = bytes.fromhex(data_hex)
                if len(data) > 4096:
                    continue
                if proc.stdin is not None:
                    proc.stdin.write(data)
                    proc.stdin.flush()
            except (ValueError, OSError, BrokenPipeError, json.JSONDecodeError):
                continue
        time.sleep(0.08)
finally:
    if terminated and proc.poll() is None:
        try:
            proc.wait(timeout=2)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
    try:
        exit_code = proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        exit_code = -9
    reader.join(timeout=2)
    atomic_meta("TERMINATED" if terminated else "EXITED", exit_code)
