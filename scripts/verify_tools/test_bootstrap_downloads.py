from __future__ import annotations

import hashlib
import http.server
import os
import shutil
import signal
import subprocess
import tempfile
import threading
import unittest
from contextlib import contextmanager
from pathlib import Path


HELPER = Path(__file__).resolve().parents[1] / "lib/logyard-bootstrap.sh"
ARTIFACT = b"complete bootstrap artifact\n"


@contextmanager
def download_server(replies: list[bytes | None]):
    requests: list[str] = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            reply = replies[min(len(requests), len(replies) - 1)]
            requests.append(self.path)
            self.send_response(200)
            self.send_header("Content-Length", str(100 if reply is None else len(reply)))
            self.end_headers()
            self.wfile.write(b"partial" if reply is None else reply)
            self.close_connection = True

        def log_message(self, *_):
            pass

    with http.server.HTTPServer(("127.0.0.1", 0), Handler) as server:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield f"http://127.0.0.1:{server.server_port}/artifact.jar", requests
        finally:
            server.shutdown()
            worker.join(5)


@unittest.skipUnless(os.name == "posix" and shutil.which("bash") and shutil.which("curl"),
                     "bootstrap fault probes require POSIX Bash and curl")
class BootstrapDownloadsTest(unittest.TestCase):
    def shell(self, script: str, *args: str) -> subprocess.CompletedProcess[str]:
        command = [shutil.which("bash"), "-c", script, "bootstrap-probe", str(HELPER), *args]
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              text=True, start_new_session=True) as process:
            try:
                stdout, stderr = process.communicate(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
                raise
        return subprocess.CompletedProcess(command, process.returncode, stdout, stderr)

    def download(self, url: str, output: Path) -> subprocess.CompletedProcess[str]:
        # The child mimics the pinned bootstrap: file output, then an independent checksum check.
        child = output.parent / "bootstrap-child.sh"
        child.write_text('''#!/usr/bin/env bash
set -euo pipefail
curl -fsSL "$1" -o "$2"
python3 - "$2" "$3" <<'PY'
import hashlib, pathlib, sys
raise SystemExit(0 if hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest() == sys.argv[2] else 43)
PY
''')
        return self.shell('source "$1"; shift; logyard_bootstrap_run "$BASH" "$@"',
                          str(child), url, str(output), hashlib.sha256(ARTIFACT).hexdigest())

    def test_retries_a_partial_download_without_appending_corrupt_bytes(self):
        with tempfile.TemporaryDirectory() as directory, download_server([None, ARTIFACT]) as (url, requests):
            output = Path(directory) / "artifact.jar"
            result = self.download(url, output)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(2, len(requests))
            self.assertEqual(ARTIFACT, output.read_bytes())

    def test_stops_after_three_failed_transfer_attempts(self):
        with tempfile.TemporaryDirectory() as directory, download_server([None]) as (url, requests):
            result = self.download(url, Path(directory) / "artifact.jar")
            self.assertEqual(18, result.returncode, result.stderr)
            self.assertEqual(3, len(requests))

    def test_does_not_retry_or_accept_a_checksum_failure(self):
        with tempfile.TemporaryDirectory() as directory, download_server([b"corrupt"]) as (url, requests):
            result = self.download(url, Path(directory) / "artifact.jar")
            self.assertEqual(43, result.returncode, result.stderr)
            self.assertEqual(1, len(requests))

    def test_preserves_child_failure_and_keeps_curl_policy_inside_the_subshell(self):
        result = self.shell('''source "$1"
logyard_bootstrap_run "$BASH" -c 'exit 23'
status=$?
[[ "$(type -t curl)" != function ]] || exit 99
exit "$status"
''')
        self.assertEqual(23, result.returncode, result.stderr)
