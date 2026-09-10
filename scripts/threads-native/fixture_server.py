#!/usr/bin/env python3
"""Disposable Synapse + loopback-only native-test control/auditing proxy.

No supplied server URL, account, token, or persistent data directory is accepted.
All credentials stay in process memory; no request/response bodies are logged.
"""
import argparse
import contextlib
import http.client
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import quote, urlsplit

SYNAPSE_VERSION = "1.160.0"
SERVER_NAME = "matrix-ci.test"
UPSTREAM_PORT = 18948
PROXY_PORT = 18949
SSS_PATH = "/_matrix/client/unstable/org.matrix.simplified_msc3575/sync"


def is_receipt_write(method, path):
    path = urlsplit(path).path
    return method in {"POST", "PUT"} and ("/receipt/" in path or path.endswith("/read_markers"))


class Fixture:
    def __init__(self):
        self.accounts = {}
        self.lock = threading.Lock()
        self.audit = {"receipt_writes": 0, "sss_successes": 0, "thread_pages": 0, "native_requests": 0}

    def api(self, method, path, token=None, data=None):
        connection = http.client.HTTPConnection("127.0.0.1", UPSTREAM_PORT, timeout=30)
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        try:
            connection.request(method, path, None if data is None else json.dumps(data), headers)
            response = connection.getresponse()
            body = response.read()
            if response.status not in range(200, 300):
                raise RuntimeError("Synthetic setup HTTP status " + str(response.status))
            return json.loads(body)
        finally:
            connection.close()

    def send(self, account, room, root=None):
        content: dict = {"msgtype": "m.text", "body": "synthetic fixture"}
        if root:
            content["m.relates_to"] = {"rel_type": "m.thread", "event_id": root,
                                      "is_falling_back": True, "m.in_reply_to": {"event_id": root}}
        return self.api("PUT", f"/_matrix/client/v3/rooms/{quote(room, safe='')}/send/m.room.message/{secrets.token_hex(12)}",
                        account["token"], content)["event_id"]

    def create(self, root_count):
        if root_count not in {1, 12}:
            raise ValueError("Unsupported bounded fixture size")
        key = secrets.token_hex(12)
        users = {}
        for role in ("alice", "bob"):
            username, password = role + "_" + key, secrets.token_urlsafe(32)
            result = self.api("POST", "/_matrix/client/v3/register", data={
                "username": username, "password": password, "auth": {"type": "m.login.dummy"}})
            users[role] = {"user_id": result["user_id"], "password": password, "token": result["access_token"]}
        rooms = []
        roots = []
        for _ in range(2):
            room = self.api("POST", "/_matrix/client/v3/createRoom", users["alice"]["token"],
                            {"preset": "private_chat", "invite": [users["bob"]["user_id"]]})["room_id"]
            self.api("POST", f"/_matrix/client/v3/join/{quote(room, safe='')}", users["bob"]["token"], {})
            rooms.append(room)
            room_roots = []
            for _ in range(root_count):
                root = self.send(users["bob"], room)
                reply = self.send(users["bob"], room, root)
                room_roots.append({"root": root, "reply": reply})
            roots.append(room_roots)
        self.accounts[key] = {"users": users, "rooms": rooms, "roots": roots}
        return {"fixture": key, "rooms": rooms, "roots": roots,
                **{role: {k: v for k, v in user.items() if k != "token"} for role, user in users.items()}}

    def control(self, path, data):
        if path == "/_fixture/new":
            return self.create(data["root_count"])
        if path == "/_fixture/audit":
            with self.lock:
                checkpoint = data.get("checkpoint")
                if checkpoint is not None:
                    if checkpoint not in {"browse_before", "browse_after"} or checkpoint in self.audit:
                        raise ValueError("Invalid or duplicate audit checkpoint")
                    self.audit[checkpoint] = self.audit["receipt_writes"]
                return dict(self.audit)
        state = self.accounts[data["fixture"]]
        room = data["room"]
        if room not in state["rooms"]:
            raise ValueError("Not a fixture room")
        account = state["users"][data.get("role", "bob")]
        if path == "/_fixture/send":
            return {"event_id": self.send(account, room, data.get("root"))}
        if path == "/_fixture/receipt":
            kind = data["type"]
            if kind not in {"m.read", "m.read.private"}:
                raise ValueError("Unsupported receipt")
            body = {} if data.get("thread") is None else {"thread_id": data["thread"]}
            self.api("POST", f"/_matrix/client/v3/rooms/{quote(room, safe='')}/receipt/{kind}/{quote(data['event'], safe='')}",
                     account["token"], body)
            return {"ok": True}
        raise ValueError("Unknown control operation")


class Handler(BaseHTTPRequestHandler):
    fixture: Fixture

    def log_message(self, format, *args):
        pass  # Never retain URLs, tokens, bodies or native SDK logs.

    def respond(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def handle_request(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
        if self.path.startswith("/_fixture/"):
            try:
                if self.command != "POST":
                    raise ValueError("Control requires POST")
                result = self.fixture.control(self.path, json.loads(body))
                self.respond(200, json.dumps(result).encode())
            except Exception:
                self.respond(500, b'{"error":"synthetic control failed"}')
            return
        if not self.path.startswith("/_matrix/"):
            self.respond(404, b"{}")
            return
        with self.fixture.lock:
            self.fixture.audit["native_requests"] += 1
            self.fixture.audit["receipt_writes"] += int(is_receipt_write(self.command, self.path))
        connection = http.client.HTTPConnection("127.0.0.1", UPSTREAM_PORT, timeout=75)
        try:
            headers = {k: v for k, v in self.headers.items() if k.lower() not in {"host", "connection", "accept-encoding"}}
            connection.request(self.command, self.path, body, headers)
            response = connection.getresponse()
            result = response.read()
            with self.fixture.lock:
                if response.status == 200:
                    self.fixture.audit["sss_successes"] += int(urlsplit(self.path).path == SSS_PATH)
                    self.fixture.audit["thread_pages"] += int(urlsplit(self.path).path.endswith("/threads"))
            self.respond(response.status, result)
        except (OSError, http.client.HTTPException):
            with contextlib.suppress(OSError):
                self.respond(502, b'{"errcode":"M_UNKNOWN","error":"fixture upstream unavailable"}')
        finally:
            connection.close()

    do_GET = do_POST = do_PUT = do_DELETE = handle_request


def configuration(directory):
    return {"server_name": SERVER_NAME, "public_baseurl": f"http://10.0.2.2:{PROXY_PORT}/",
            "pid_file": str(directory / "synapse.pid"), "report_stats": False,
            "listeners": [{"port": UPSTREAM_PORT, "type": "http", "tls": False,
                           "bind_addresses": ["127.0.0.1"], "resources": [{"names": ["client"], "compress": False}]}],
            "database": {"name": "sqlite3", "args": {"database": str(directory / "homeserver.db")}},
            "media_store_path": str(directory / "media"), "signing_key_path": str(directory / "signing.key"),
            "enable_registration": True, "enable_registration_without_verification": True,
            "password_config": {"enabled": True, "localdb_enabled": True},
            "matrix_authentication_service": {"enabled": False},
            "experimental_features": {"msc3575_enabled": True}, "federation_domain_whitelist": [],
            "send_federation": False, "url_preview_enabled": False, "push": {"enabled": False},
            "rc_registration": {"per_second": 100, "burst_count": 100},
            "rc_login": {"address": {"per_second": 100, "burst_count": 100}, "account": {"per_second": 100, "burst_count": 100}},
            "rc_message": {"per_second": 100, "burst_count": 100}, "suppress_key_server_warning": True}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    args = parser.parse_args()
    from importlib.metadata import version
    if version("matrix-synapse") != SYNAPSE_VERSION:
        raise SystemExit("Wrong Synapse version")
    os.umask(0o077)
    # Never attach to a pre-existing server. Both ports must initially be free.
    for port in (UPSTREAM_PORT, PROXY_PORT):
        with socket.socket() as guard:
            guard.bind(("127.0.0.1", port))
    directory = Path(tempfile.mkdtemp(prefix="threads-native-synapse-"))
    args.evidence.mkdir(parents=True, exist_ok=True)
    fixture = Fixture()
    process = server = None
    stopped = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stopped.set())
    try:
        config = directory / "homeserver.yaml"
        config.write_text(json.dumps(configuration(directory)))
        with (directory / "server.log").open("wb") as log:
            process = subprocess.Popen([sys.executable, "-m", "synapse.app.homeserver", "--config-path", str(config)],
                                       cwd=directory, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None or time.monotonic() > deadline:
                    raise RuntimeError("Disposable Synapse did not become ready; raw log deliberately not uploaded")
                try:
                    capabilities = fixture.api("GET", "/_matrix/client/versions")
                    assert capabilities["unstable_features"]["org.matrix.simplified_msc3575"] is True
                    break
                except (OSError, http.client.HTTPException):
                    stopped.wait(0.2)
            Handler.fixture = fixture
            server = ThreadingHTTPServer(("127.0.0.1", PROXY_PORT), Handler)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            (args.evidence / "server.json").write_text(json.dumps({"synapse": SYNAPSE_VERSION, "native_sss": True,
                                                                  "synthetic_only": True, "loopback_only": True}))
            while not stopped.wait(1):
                if process.poll() is not None:
                    raise RuntimeError("Disposable Synapse stopped unexpectedly")
    finally:
        if server:
            server.shutdown()
            server.server_close()
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        (args.evidence / "audit.json").write_text(json.dumps(fixture.audit))
        shutil.rmtree(directory)


if __name__ == "__main__":
    main()
