from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import threading
import tomllib
from dataclasses import dataclass, replace
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from .providers import (
    classpath_providers,
    executable_providers,
    require_competing_provider,
    require_single_logyard,
)


@dataclass(frozen=True)
class ZoltExample:
    name: str
    project_directory: Path
    main_class: str
    replacements: tuple[tuple[str, str], ...] = ()
    omit_config: bool = False
    test: bool = False
    provider_conflict_expected: bool = False
    runtime_arguments: tuple[str, ...] = ("-Dlogyard.config=classpath:logyard.toml",)


@dataclass(frozen=True)
class BuiltZoltExample:
    example: ZoltExample
    runtime_classpath: str


class RepositoryHandler(SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


class ZoltExampleRunner:
    def __init__(self, release_repository: Path, version: str, target: Path) -> None:
        self._release_repository = release_repository
        self._version = version
        self._target = target.resolve()
        self._cache = self._target / "cache"
        self._zolt = os.environ.get("ZOLT") or shutil.which("zolt") or str(Path.home() / ".zolt/bin/zolt")

    def __enter__(self) -> ZoltExampleRunner:
        self._target.mkdir(parents=True, exist_ok=True)
        # Each run must resolve Logyard from this bundle, even at the same version.
        shutil.rmtree(self._cache / "com/logyard4j", ignore_errors=True)
        for group_index in (self._cache / "indexes").glob("*/com/logyard4j"):
            shutil.rmtree(group_index)
        handler = partial(RepositoryHandler, directory=str(self._release_repository))
        self._server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *args: object) -> None:
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=5)

    def stage(self, example: ZoltExample) -> ZoltExample:
        project = self._target / "projects" / example.name
        shutil.rmtree(project, ignore_errors=True)
        shutil.copytree(example.project_directory, project, ignore=shutil.ignore_patterns("target", ".zolt", "zolt.lock"))
        manifest = project / "zolt.toml"
        text = manifest.read_text(encoding="utf-8")
        for old, new in example.replacements:
            if old not in text:
                raise AssertionError(f"example variant cannot replace {old!r} in {manifest}")
            text = text.replace(old, new)
        declared = tomllib.loads(text)["platforms"]["com.logyard4j:logyard-bom"]
        text = text.replace(f'"com.logyard4j:logyard-bom" = "{declared}"',
                            f'"com.logyard4j:logyard-bom" = "{self._version}"')
        text += f'\n[repositories.logyard]\nurl = "http://127.0.0.1:{self._server.server_port}"\n'
        manifest.write_text(text, encoding="utf-8")
        if example.omit_config:
            (project / "src/main/resources/logyard.toml").unlink()
        return replace(example, project_directory=project)

    def build(self, example: ZoltExample) -> BuiltZoltExample:
        staged = self.stage(example)
        project = staged.project_directory
        environment = self.environment(self._target / f"{example.name}-build.jsonl")
        for command in ("resolve", "package", *(("test",) if example.test else ())):
            self._run(self.command(command), project, environment)
            if command == "resolve":
                self.verify_artifacts(project)
        classpath = self._run(self.command("classpath", "runtime"), project, capture=True)
        built = BuiltZoltExample(staged, classpath.stdout.strip())
        self.verify_provider(built)
        return built

    def verify_artifacts(self, project: Path) -> None:
        packages = tomllib.loads((project / "zolt.lock").read_text())["package"]
        context_versions = {package["version"] for package in packages
                            if package["id"] in ("io.opentelemetry:opentelemetry-api",
                                                 "io.opentelemetry:opentelemetry-context",
                                                 "io.opentelemetry:opentelemetry-common")}
        if len(context_versions) > 1:
            raise AssertionError(f"example resolved incompatible OpenTelemetry API versions: {context_versions}")
        logyard = [package for package in packages if package["id"].startswith("com.logyard4j:")]
        if not logyard:
            raise AssertionError("example resolved no Logyard artifacts")
        for package in logyard:
            if package.get("source") == "workspace" or package["version"] != self._version:
                raise AssertionError(f"example bypassed the release bundle: {package['id']}")
            artifact = package["id"].split(":")[1]
            directory = self._release_repository / "com/logyard4j" / artifact / self._version
            for kind in ("pom", "jar"):
                if kind not in package:
                    continue
                cached = self._cache / package[kind]
                bundled = directory / f"{artifact}-{self._version}.{kind}"
                if hashlib.sha256(cached.read_bytes()).digest() != hashlib.sha256(bundled.read_bytes()).digest():
                    raise AssertionError(f"example resolved a different artifact from the bundle: {package['id']} {kind}")

    def build_and_run(self, example: ZoltExample) -> Path:
        built = self.build(example)
        output = self._target / f"{example.name}.jsonl"
        output.unlink(missing_ok=True)
        self._run(self.java_command(built), built.example.project_directory, self.environment(output))
        return output

    def command(self, command: str, *arguments: str) -> tuple[str, ...]:
        return (self._zolt, "--no-progress", "--color", "never", command, *arguments,
                "--cache-root", str(self._cache))

    @staticmethod
    def environment(output: Path, additional: dict[str, str] | None = None) -> dict[str, str]:
        return {**os.environ, "LOGYARD_EXAMPLE_OUTPUT": str(output), **(additional or {})}

    @staticmethod
    def java_command(built: BuiltZoltExample) -> tuple[str, ...]:
        classpath = os.pathsep.join((str(built.example.project_directory / "target/classes"), built.runtime_classpath))
        return ("java", *built.example.runtime_arguments, "-cp", classpath, built.example.main_class)

    @staticmethod
    def executable_jar_command(built: BuiltZoltExample, jar_name: str) -> tuple[str, ...]:
        jar = built.example.project_directory / "target" / jar_name
        ZoltExampleRunner.verify_provider(built, jar)
        return ("java", *built.example.runtime_arguments, "-jar", str(jar))

    @staticmethod
    def verify_provider(built: BuiltZoltExample, executable: Path | None = None) -> None:
        packages = tomllib.loads((built.example.project_directory / "zolt.lock").read_text())["package"]
        if not any(package["id"] == "com.logyard4j:logyard-slf4j2" for package in packages):
            return
        source = f"{built.example.name} " + ("executable package" if executable else "runtime classpath")
        providers = (executable_providers(executable) if executable is not None
                     else classpath_providers(built.runtime_classpath))
        if built.example.provider_conflict_expected:
            require_competing_provider(providers, source)
            print(f"SLF4J provider preflight confirmed expected conflict: {source}", flush=True)
        else:
            require_single_logyard(providers, source)
            print(f"SLF4J provider preflight passed: {source}", flush=True)

    @staticmethod
    def _run(command: tuple[str, ...], directory: Path, environment: dict[str, str] | None = None,
             capture: bool = False) -> subprocess.CompletedProcess[str]:
        return subprocess.run(command, cwd=directory, env=environment, check=True, timeout=300,
                              text=True, stdout=subprocess.PIPE if capture else None)
