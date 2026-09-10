#!/usr/bin/env python3
"""Optional real-server infrastructure preflight. NEVER native Android proof.
Run with the matrix-synapse==1.160.0 virtualenv interpreter. No credentials logged.
"""
import contextlib
import http.client
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote


def main():
    with tempfile.TemporaryDirectory(prefix="threads-wire-preflight-") as scratch:
        process = subprocess.Popen([sys.executable, "-B", str(Path(__file__).with_name("fixture_server.py")),
                                    "--evidence", scratch], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def request(path, body=None, token=None, method="POST"):
            connection = http.client.HTTPConnection("127.0.0.1", 18949, timeout=60)
            headers = {"Content-Type": "application/json"}
            if token:
                headers["Authorization"] = "Bearer " + token
            try:
                connection.request(method, path, json.dumps(body) if body is not None else None, headers)
                response = connection.getresponse()
                result = response.read()
                assert response.status == 200, "HTTP status " + str(response.status)
                return json.loads(result)
            finally:
                connection.close()
        try:
            for _ in range(180):
                if process.poll() is not None:
                    raise RuntimeError("Fixture failed before readiness")
                try:
                    request("/_fixture/audit", {})
                    break
                except (OSError, http.client.HTTPException):
                    time.sleep(0.5)
            else:
                raise RuntimeError("Fixture readiness timeout")
            seed = request("/_fixture/new", {"root_count": 12})
            assert len(seed["rooms"]) == 2 and all(len(roots) == 12 for roots in seed["roots"])
            alice = seed["alice"]
            login = request("/_matrix/client/v3/login", {"type": "m.login.password",
                "identifier": {"type": "m.id.user", "user": alice["user_id"]}, "password": alice["password"]})
            token = login["access_token"]
            room = seed["rooms"][0]
            root, reply = seed["roots"][0][0].values()
            rows, cursor, pages = [], None, 0
            while True:
                page = request(f"/_matrix/client/v1/rooms/{quote(room, safe='')}/threads?limit=10" +
                               ("&from=" + quote(cursor, safe='') if cursor else ""), token=token, method="GET")
                rows.extend(event["event_id"] for event in page["chunk"])
                cursor, pages = page.get("next_batch"), pages + 1
                if not cursor:
                    break
                assert pages <= 4
            assert len(rows) == len(set(rows)) == 12 and pages >= 2
            request("/_fixture/receipt", {"fixture": seed["fixture"], "room": room, "role": "alice", "thread": root,
                                         "event": reply, "type": "m.read.private"})
            sss = request("/_matrix/client/unstable/org.matrix.simplified_msc3575/sync", {
                "lists": {"all": {"ranges": [[0, 20]], "required_state": [["m.room.name", ""]], "timeline_limit": 20}},
                "room_subscriptions": {r: {"required_state": [["m.room.name", ""]], "timeline_limit": 20} for r in seed["rooms"]},
                "extensions": {"receipts": {"enabled": True, "rooms": seed["rooms"]}}}, token=token)
            assert set(seed["rooms"]).issubset(sss["rooms"])
            assert "m.receipt" == sss["extensions"]["receipts"]["rooms"][room]["type"]
            assert request("/_fixture/audit", {})["receipt_writes"] == 0
            request(f"/_matrix/client/v3/rooms/{quote(room, safe='')}/receipt/m.read/{quote(reply, safe='')}",
                    {"thread_id": root}, token=token)
            audit = request("/_fixture/audit", {})
            assert audit["receipt_writes"] == 1 and audit["sss_successes"] > 0
            print(json.dumps({"layer": "wire_infrastructure_only", "native_executed": False, "rooms": len(seed["rooms"]),
                              "roots_in_paginated_room": len(rows), "pages": pages, "sss_successes": audit["sss_successes"],
                              "receipt_audit_positive_control": audit["receipt_writes"], "passed": True}))
        finally:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            # Server data is removed by its finally block, separate from this public count-only evidence.
            if process.returncode != 0:
                raise RuntimeError("Fixture process did not shut down cleanly")


if __name__ == "__main__":
    main()
