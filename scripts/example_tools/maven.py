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
        project = example.project_directory
        classpath_file = project / "target" / "runtime-classpath.txt"
        self._run(
            (
                "mvn",
                "--batch-mode",
                "--no-transfer-progress",
                f"-Dmaven.repo.local={self._local_repository}",
                f"-Dlogyard.repository={self._release_repository.as_uri()}",
                f"-Dlogyard.version={self._version}",
                "clean",
                "package",
                "org.apache.maven.plugins:maven-dependency-plugin:3.7.0:build-classpath",
                f"-Dmdep.outputFile={classpath_file}",
            ),
            project,
        )
        output = self._target / f"{example.name}.jsonl"
        environment = os.environ.copy()
        environment["LOGYARD_EXAMPLE_OUTPUT"] = str(output)
        runtime_classpath = classpath_file.read_text(encoding="utf-8").strip()
        self._run(
            (
                "java",
                "-Dlogyard.config=classpath:logyard.toml",
                "-cp",
                os.pathsep.join((str(project / "target" / "classes"), runtime_classpath)),
                example.main_class,
            ),
            project,
            environment,
        )
        return output

    @staticmethod
    def _run(command: tuple[str, ...], directory: Path, environment: dict[str, str] | None = None) -> None:
        try:
            subprocess.run(command, cwd=directory, env=environment, check=True, timeout=240)
        except subprocess.CalledProcessError as failure:
            raise SystemExit(f"example command failed with exit code {failure.returncode}: {' '.join(command)}") from failure
        except subprocess.TimeoutExpired as failure:
            raise SystemExit(f"example command timed out: {' '.join(command)}") from failure
