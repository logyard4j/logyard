from __future__ import annotations

import argparse
import os
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


STARTED = '"body":"Quarkus application started"'
SUCCEEDED = '"body":"Quarkus request succeeded"'


def main() -> None:
    parser = argparse.ArgumentParser(description="Exercise Quarkus dev mode and hot reload with staged Logyard artifacts.")
    parser.add_argument("root", type=Path)
    parser.add_argument("release_repository", type=Path)
    parser.add_argument("version")
    arguments = parser.parse_args()

    root = arguments.root.resolve()
    project = root / "examples" / "quarkus"
    target = root / "target" / "examples-verify"
    target.mkdir(parents=True, exist_ok=True)
    output = target / "quarkus-dev.jsonl"
    process_log = target / "quarkus-dev.process.log"
    output.unlink(missing_ok=True)
    process_log.unlink(missing_ok=True)
    port = available_port()
    environment = os.environ.copy()
    environment["LOGYARD_EXAMPLE_OUTPUT"] = str(output)
    command = (
        "mvn",
        "--batch-mode",
        "--no-transfer-progress",
        f"-Dmaven.repo.local={target / 'maven-repository'}",
        f"-Dlogyard.repository={arguments.release_repository.resolve().as_uri()}",
        f"-Dlogyard.version={arguments.version}",
        f"-Dquarkus.http.port={port}",
        "-Dquarkus.logyard.config=classpath:logyard-dev.toml",
        "-Dquarkus.analytics.disabled=true",
        "-Dquarkus.console.basic=true",
        "-Dquarkus.test.continuous-testing=disabled",
        "quarkus:dev",
    )

    with process_log.open("wb") as diagnostic:
        process = subprocess.Popen(
            command,
            cwd=project,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=diagnostic,
            stderr=subprocess.STDOUT,
        )
        try:
            await_success(process, port, process_log)
            await_count(output, STARTED, 1, process, process_log)
            initial_starts = count(output, STARTED)
            initial_successes = count(output, SUCCEEDED)
            source = project / "src" / "main" / "java" / "com" / "zsumz" / "logyard" / "examples" / "quarkus" / "QuarkusExampleResource.java"
            os.utime(source, None)
            await_reload(
                output,
                port,
                initial_starts + 1,
                initial_successes + 1,
                process,
                process_log,
            )
        finally:
            stop(process)

    diagnostics = process_log.read_text(encoding="utf-8", errors="replace")
    for message in ("Quarkus application started", "Quarkus request succeeded"):
        if message in diagnostics:
            raise AssertionError(f"Quarkus dev-mode console output duplicated {message!r}")
    print("Quarkus dev-mode hot-reload verification passed.")


def await_reload(
        output: Path,
        port: int,
        expected_starts: int,
        expected_successes: int,
        process: subprocess.Popen[bytes],
        process_log: Path) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        ensure_running(process, process_log)
        try:
            request(port)
        except (urllib.error.URLError, ConnectionError):
            time.sleep(0.1)
            continue
        if count(output, STARTED) >= expected_starts and count(output, SUCCEEDED) >= expected_successes:
            return
        time.sleep(0.1)
    fail("Quarkus dev mode did not hot reload within 60 seconds", process_log)


def await_success(process: subprocess.Popen[bytes], port: int, process_log: Path) -> None:
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        ensure_running(process, process_log)
        try:
            request(port)
            return
        except (urllib.error.URLError, ConnectionError):
            time.sleep(0.1)
    fail("Quarkus dev mode did not start within 60 seconds", process_log)


def await_count(
        output: Path,
        token: str,
        expected: int,
        process: subprocess.Popen[bytes],
        process_log: Path) -> None:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        ensure_running(process, process_log)
        if count(output, token) >= expected:
            return
        time.sleep(0.05)
    fail(f"Quarkus dev output did not contain {token!r}", process_log)


def request(port: int) -> None:
    request_message = urllib.request.Request(
        f"http://127.0.0.1:{port}/success",
        headers={"X-Request-Id": "request-dev"},
    )
    with urllib.request.urlopen(request_message, timeout=5) as response:
        if response.status != 200 or response.read().decode("utf-8") != "success":
            raise ConnectionError("unexpected Quarkus dev-mode response")


def count(path: Path, token: str) -> int:
    if not path.is_file():
        return 0
    return path.read_text(encoding="utf-8", errors="replace").count(token)


def ensure_running(process: subprocess.Popen[bytes], process_log: Path) -> None:
    if process.poll() is not None:
        fail(f"Quarkus dev mode exited early with {process.returncode}", process_log)


def stop(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=15)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def available_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", 0))
        return int(listener.getsockname()[1])


def fail(message: str, process_log: Path) -> None:
    diagnostics = process_log.read_text(encoding="utf-8", errors="replace") if process_log.is_file() else ""
    raise AssertionError(f"{message}\n{diagnostics}")


if __name__ == "__main__":
    main()
