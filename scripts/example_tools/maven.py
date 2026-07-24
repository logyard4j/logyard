from __future__ import annotations

import os
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class MavenExample:
    name: str
    project_directory: Path
    main_class: str
    build_arguments: tuple[str, ...] = ()
    runtime_arguments: tuple[str, ...] = ("-Dlogyard.config=classpath:logyard.toml",)


@dataclass(frozen=True)
class BuiltMavenExample:
    example: MavenExample
    runtime_classpath: str


class MavenExampleRunner:
    def __init__(self, release_repository: Path, version: str, target: Path) -> None:
        self._release_repository = release_repository
        self._version = version
        self._target = target
        self._local_repository = target / "maven-repository"

    def prepare(self) -> None:
        self._target.mkdir(parents=True, exist_ok=True)
        staged_logyard_cache = self._local_repository / "com" / "zsumz" / "logyard"
        if staged_logyard_cache.exists():
            shutil.rmtree(staged_logyard_cache)
        for output in self._target.glob("*.jsonl"):
            output.unlink()

    def build_and_run(self, example: MavenExample) -> Path:
        built = self.build(example)
        output = self.output_path(example)
        environment = self.environment(output)
        self._run(self.java_command(built), example.project_directory, environment)
        return output

    def build(self, example: MavenExample) -> BuiltMavenExample:
        project = example.project_directory
        classpath_file = project / "target" / "runtime-classpath.txt"
        self._run(
            self._command(
                "clean",
                "package",
                "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath",
                f"-Dmdep.outputFile={classpath_file}",
                *example.build_arguments,
            ),
            project,
        )
        return BuiltMavenExample(example, classpath_file.read_text(encoding="utf-8").strip())

    def build_native(self, example: MavenExample, executable_name: str) -> Path:
        self._run(self._command("clean", "package", "-Dpackaging=native-image"), example.project_directory)
        executable = example.project_directory / "target" / executable_name
        if not executable.is_file():
            raise AssertionError(f"native example did not produce {executable}")
        return executable

    def build_aot(self, example: MavenExample) -> None:
        output = self._target / f"{example.name}.jsonl"
        self._run(
            self._command("clean", "package", "spring-boot:process-aot", *example.build_arguments),
            example.project_directory,
            self.environment(output),
        )
        generated_sources = example.project_directory / "target" / "spring-aot" / "main" / "sources"
        if not any(generated_sources.rglob("*.java")):
            raise AssertionError(f"AOT example did not produce generated Java sources under {generated_sources}")

    def build_spring_native(self, example: MavenExample, executable_name: str) -> Path:
        output = self._target / f"{example.name}-build.jsonl"
        self._run(
            self._command(
                "clean",
                "package",
                "spring-boot:process-aot",
                "org.graalvm.buildtools:native-maven-plugin:1.1.1:add-reachability-metadata",
                "org.graalvm.buildtools:native-maven-plugin:1.1.1:compile-no-fork",
                *example.build_arguments,
            ),
            example.project_directory,
            self.environment(output),
        )
        executable = example.project_directory / "target" / executable_name
        if not executable.is_file():
            raise AssertionError(f"Spring native example did not produce {executable}")
        return executable

    def output_path(self, example: MavenExample) -> Path:
        return self._target / f"{example.name}.jsonl"

    def _command(self, *goals: str) -> tuple[str, ...]:
        return (
            "mvn",
            "--batch-mode",
            "--no-transfer-progress",
            f"-Dmaven.repo.local={self._local_repository}",
            f"-Dlogyard.repository={self._release_repository.as_uri()}",
            f"-Dlogyard.version={self._version}",
            *goals,
        )

    @staticmethod
    def environment(output: Path, additional: dict[str, str] | None = None) -> dict[str, str]:
        environment = os.environ.copy()
        environment["LOGYARD_EXAMPLE_OUTPUT"] = str(output)
        environment.update(additional or {})
        return environment

    @staticmethod
    def java_command(built: BuiltMavenExample) -> tuple[str, ...]:
        return (
            "java",
            *built.example.runtime_arguments,
            "-cp",
            os.pathsep.join(
                (
                    str(built.example.project_directory / "target" / "classes"),
                    built.runtime_classpath,
                )
            ),
            built.example.main_class,
        )

    @staticmethod
    def executable_jar_command(built: BuiltMavenExample, jar_name: str) -> tuple[str, ...]:
        return (
            "java",
            *built.example.runtime_arguments,
            "-jar",
            str(built.example.project_directory / "target" / jar_name),
        )

    @staticmethod
    def _run(command: tuple[str, ...], directory: Path, environment: dict[str, str] | None = None) -> None:
        try:
            subprocess.run(command, cwd=directory, env=environment, check=True, timeout=240)
        except subprocess.CalledProcessError as failure:
            raise SystemExit(f"example command failed with exit code {failure.returncode}: {' '.join(command)}") from failure
        except subprocess.TimeoutExpired as failure:
            raise SystemExit(f"example command timed out: {' '.join(command)}") from failure
