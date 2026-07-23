from __future__ import annotations

import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

from .maven import MavenExample, MavenExampleRunner


@dataclass(frozen=True)
class HttpExample:
    maven: MavenExample
    success_path: str = "/success"
    failure_path: str = "/failure"
    shutdown_path: str = "/shutdown"


@dataclass(frozen=True)
class ExecutableHttpExample:
    name: str
    project_directory: Path
    command: tuple[str, ...]
    additional_environment: tuple[tuple[str, str], ...] = ()
    success_path: str = "/success"
    failure_path: str = "/failure"
    shutdown_path: str = "/shutdown"


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
                command=self._maven.java_command(built),
                success_path=example.success_path,
                failure_path=example.failure_path,
                shutdown_path=example.shutdown_path,
            )
        )

    def run_and_exercise(self, example: ExecutableHttpExample) -> Path:
        output = self._target / f"{example.name}.jsonl"
        port_file = self._target / f"{example.name}.port"
        process_log = self._target / f"{example.name}.process.log"
        for stale in (output, port_file, process_log):
            stale.unlink(missing_ok=True)
        environment = self._maven.environment(
            output,
            {
                "LOGYARD_EXAMPLE_PORT_FILE": str(port_file),
                **dict(example.additional_environment),
            },
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
                self._request(port, example.success_path, 200, "success")
                self._request(port, example.failure_path, 500, "failure")
                self._request(port, example.shutdown_path, 202, "stopping", method="POST")
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
        while time.monotonic() < deadline:
            if process.poll() is not None:
                HttpExampleRunner._fail(f"server exited before publishing its port ({process.returncode})", process_log)
            if port_file.is_file():
                return int(port_file.read_text(encoding="utf-8").strip())
            time.sleep(0.05)
        HttpExampleRunner._fail("server did not publish its port within 30 seconds", process_log)

    @staticmethod
    def _request(port: int, path: str, expected_status: int, expected_body: str, method: str = "GET") -> None:
        request = urllib.request.Request(
            f"http://127.0.0.1:{port}{path}",
            headers={"X-Request-Id": f"request-{path.strip('/')}"},
            method=method,
        )
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                status = response.status
                body = response.read().decode("utf-8")
        except urllib.error.HTTPError as failure:
            status = failure.code
            body = failure.read().decode("utf-8")
        if status != expected_status or body != expected_body:
            raise AssertionError(f"{method} {path} returned {status} {body!r}; expected {expected_status} {expected_body!r}")

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
    def _fail(message: str, process_log: Path) -> None:
        diagnostics = process_log.read_text(encoding="utf-8", errors="replace") if process_log.is_file() else ""
        raise AssertionError(f"{message}\n{diagnostics}")
