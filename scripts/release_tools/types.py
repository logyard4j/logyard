from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


SUPPORTED_PACKAGING = frozenset({"jar", "pom"})
SUPPORTED_ARTIFACTS = frozenset({"main", "sources", "javadoc", "pom"})


@dataclass(frozen=True)
class Metadata:
    name: str
    description: str
    url: str
    license: str
    scm: str
    issues: str


@dataclass(frozen=True)
class Exclusion:
    group_id: str
    artifact_id: str


@dataclass(frozen=True)
class Dependency:
    group_id: str
    artifact_id: str
    version: str | None
    scope: str = "compile"
    optional: bool = False
    exclusions: tuple[Exclusion, ...] = ()
    workspace_path: str | None = None

    @property
    def coordinate(self) -> str:
        return f"{self.group_id}:{self.artifact_id}"


@dataclass(frozen=True)
class Publication:
    module_directory: Path
    manifest_path: Path
    group_id: str
    artifact_id: str
    version: str
    packaging: str
    artifacts: tuple[str, ...]
    metadata: Metadata
    automatic_module_name: str | None
    build_system: str
    dependencies: tuple[Dependency, ...]
    managed_dependencies: tuple[Dependency, ...]

    @property
    def coordinate(self) -> str:
        return f"{self.group_id}:{self.artifact_id}"

    @property
    def relative_module_path(self) -> str:
        return self.module_directory.as_posix()

    @property
    def group_path(self) -> Path:
        return Path(*self.group_id.split("."))

    @property
    def primary_filenames(self) -> tuple[str, ...]:
        base = f"{self.artifact_id}-{self.version}"
        filenames: list[str] = []
        if "main" in self.artifacts:
            filenames.append(f"{base}.jar")
        if "sources" in self.artifacts:
            filenames.append(f"{base}-sources.jar")
        if "javadoc" in self.artifacts:
            filenames.append(f"{base}-javadoc.jar")
        filenames.append(f"{base}.pom")
        return tuple(filenames)


class PublicationError(ValueError):
    pass
