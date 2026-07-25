from __future__ import annotations

import socket
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .maven import MavenExample, MavenExampleRunner


@dataclass(frozen=True)
class HttpRequestExpectation:
    path: str
    expected_status: int
    expected_body: str
    method: str = "GET"
    request_body: str | None = None
    body_contains: bool = False


@dataclass(frozen=True)
class HttpExample:
    maven: MavenExample
    success_path: str = "/success"
    failure_path: str = "/failure"
    shutdown_path: str = "/shutdown"
    executable_jar_name: str | None = None
    random_port_environment: str | None = None
    additional_requests: tuple[HttpRequestExpectation, ...] = ()


@dataclass(frozen=True)
class ExecutableHttpExample:
    name: str
    project_directory: Path
    command: tuple[str, ...]
    additional_environment: tuple[tuple[str, str], ...] = ()
    random_port_environment: str | None = None
    success_path: str = "/success"
    failure_path: str = "/failure"
    shutdown_path: str = "/shutdown"
    additional_requests: tuple[HttpRequestExpectation, ...] = ()


class HttpExampleRunner:
    def __init__(self, maven: MavenExampleRunner, target: Path) -> None:
        self._maven = maven
        self._target = target

    def build_run_and_exercise(self, example: HttpExample) -> Path:
        built = self._maven.build(example.maven)
        return self.run_and_exercise(
            ExecutableHttpExample(
                name=example.maven.name,
                project_directory=example.maven.project_directory,
                command=self._maven.java_command(built)
                if example.executable_jar_name is None
                else self._maven.executable_jar_command(built, example.executable_jar_name),
                success_path=example.success_path,
                failure_path=example.failure_path,
                shutdown_path=example.shutdown_path,
                random_port_environment=example.random_port_environment,
                additional_requests=example.additional_requests,
            )
        )

    def process_log_path(self, name: str) -> Path:
        return self._target / f"{name}.process.log"

    def run_and_exercise(self, example: ExecutableHttpExample) -> Path:
        output = self._target / f"{example.name}.jsonl"
        port_file = self._target / f"{example.name}.port"
        process_log = self._target / f"{example.name}.process.log"
        for stale in (output, port_file, process_log):
            stale.unlink(missing_ok=True)
        additional_environment = {
            "LOGYARD_EXAMPLE_PORT_FILE": str(port_file),
            **dict(example.additional_environment),
        }
        if example.random_port_environment is not None:
            additional_environment[example.random_port_environment] = str(self._available_port())
        environment = self._maven.environment(
            output,
            additional_environment,
        )

        with process_log.open("wb") as log:
            process = subprocess.Popen(
                example.command,
                cwd=example.project_directory,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            try:
                port = self._await_port(process, port_file, process_log)
                self._request(port, HttpRequestExpectation(example.success_path, 200, "success"))
                self._request(port, HttpRequestExpectation(example.failure_path, 500, "failure"))
                for request in example.additional_requests:
                    self._request(port, request)
                self._request(port, HttpRequestExpectation(example.shutdown_path, 202, "stopping", method="POST"))
                exit_code = process.wait(timeout=30)
                if exit_code != 0:
                    self._fail(f"server exited with {exit_code}", process_log)
            except BaseException:
                self._stop(process)
                raise
        return output

    @staticmethod
    def _await_port(process: subprocess.Popen[bytes], port_file: Path, process_log: Path) -> int:
        deadline = time.monotonic() + 30
        observed_port: int | None = None
        while time.monotonic() < deadline:
            if process.poll() is not None:
                HttpExampleRunner._fail(f"server exited before publishing its port ({process.returncode})", process_log)
            if port_file.is_file():
                value = port_file.read_text(encoding="utf-8").strip()
                if value:
                    if not value.isdecimal():
                        HttpExampleRunner._fail(f"server published an invalid port {value!r}", process_log)
                    port = int(value)
                    if not 0 < port <= 65_535:
                        HttpExampleRunner._fail(f"server published an invalid port {port}", process_log)
                    if port == observed_port:
                        return port
                    observed_port = port
            time.sleep(0.05)
        HttpExampleRunner._fail("server did not publish its port within 30 seconds", process_log)

    @staticmethod
    def _request(port: int, expectation: HttpRequestExpectation) -> None:
        data = expectation.request_body.encode("utf-8") if expectation.request_body is not None else None
        headers = {"X-Request-Id": f"request-{expectation.path.strip('/').replace('/', '-')}"}
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}{expectation.path}",
            data=data,
            headers=headers,
            method=expectation.method,
        )
        deadline = time.monotonic() + 10
        while True:
            try:
                with urllib.request.urlopen(request, timeout=10) as response:
                    status = response.status
                    body = response.read().decode("utf-8")
                break
            except urllib.error.HTTPError as failure:
                status = failure.code
                body = failure.read().decode("utf-8")
                break
            except urllib.error.URLError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.05)
        body_matches = expectation.expected_body in body if expectation.body_contains else body == expectation.expected_body
        if status != expectation.expected_status or not body_matches:
            mode = "containing" if expectation.body_contains else "equal to"
            raise AssertionError(
                f"{expectation.method} {expectation.path} returned {status} {body!r}; expected "
                f"{expectation.expected_status} with body {mode} {expectation.expected_body!r}"
            )

    @staticmethod
    def _stop(process: subprocess.Popen[bytes]) -> None:
        if process.poll() is not None:
            return
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    @staticmethod
    def _available_port() -> int:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(("127.0.0.1", 0))
            return int(listener.getsockname()[1])

    @staticmethod
    def _fail(message: str, process_log: Path) -> None:
        diagnostics = process_log.read_text(encoding="utf-8", errors="replace") if process_log.is_file() else ""
        raise AssertionError(f"{message}\n{diagnostics}")
