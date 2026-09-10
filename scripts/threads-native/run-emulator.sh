#!/usr/bin/env bash
set -euo pipefail
umask 077
: "${EXPECTED_SHA:?Exact source SHA is required}"
: "${RUNNER_TEMP:?Hosted runner temporary directory is required}"
mkdir -p native-evidence
python3 -c 'import sys; sys.path.insert(0, "scripts/threads-native"); from evidence import source_sha; import os; source_sha(os.environ["EXPECTED_SHA"])'
"$RUNNER_TEMP/threads-synapse-venv/bin/python" -B scripts/threads-native/fixture_server.py --evidence native-evidence >"$RUNNER_TEMP/threads-native-server-console.private" 2>&1 &
server_pid=$!
cleanup() {
  kill -TERM "$server_pid" 2>/dev/null || true
  wait "$server_pid" || true
  rm -f "$RUNNER_TEMP/threads-native-server-console.private"
}
trap cleanup EXIT INT TERM
python3 - <<'PY'
import json, time, urllib.request
for attempt in range(180):
    try:
        request = urllib.request.Request('http://127.0.0.1:18949/_fixture/audit', data=b'{}', headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request, timeout=1) as response:
            assert 'native_requests' in json.load(response)
        break
    except (OSError, ValueError):
        time.sleep(.5)
else:
    raise SystemExit('Same-run loopback fixture readiness failed')
PY
python3 -B scripts/threads-native/evidence.py instrument --sha "$EXPECTED_SHA"
