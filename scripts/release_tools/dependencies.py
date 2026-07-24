from __future__ import annotations

from collections import Counter
from pathlib import Path
from typing import Any

from .types import Dependency, Exclusion, PublicationError


SUPPORTED_SCOPES = frozenset({"compile", "runtime", "provided", "test", "import"})


def dependencies(manifest: dict[str, Any], workspace_version: str, path: Path) -> tuple[Dependency, ...]:
    sections = (
        (manifest.get("api", {}).get("dependencies", {}), "compile", True),
        (manifest.get("dependencies", {}), "compile", True),
        (manifest.get("runtime", {}).get("dependencies", {}), "runtime", True),
        (manifest.get("provided", {}).get("dependencies", {}), "provided", True),
        (manifest.get("test", {}).get("dependencies", {}), "test", False),
    )
    result: list[Dependency] = []
    for table, scope, include_all in sections:
        result.extend(dependency_table(table, scope, workspace_version, path, include_all, manifest.get("versions", {})))
    duplicates = sorted(coordinate for coordinate, count in Counter(dependency.coordinate for dependency in result).items() if count > 1)
    if duplicates:
        raise PublicationError(f"{path} publishes duplicate dependencies: {duplicates}")
    return tuple(sorted(result, key=lambda dependency: dependency.coordinate))


def dependency_table(
    table: dict[str, Any],
    scope: str,
    workspace_version: str,
    path: Path,
    include_all: bool,
    version_aliases: dict[str, Any] | None = None,
) -> tuple[Dependency, ...]:
    result: list[Dependency] = []
    for coordinate, declaration in table.items():
        group_id, artifact_id = _coordinate(str(coordinate), path)
        values = declaration if isinstance(declaration, dict) else {}
        if not include_all and not values.get("publishOnly", False):
            continue
        dependency_scope = str(values.get("scope", scope))
        if dependency_scope not in SUPPORTED_SCOPES:
            raise PublicationError(f"{path} gives {coordinate} unsupported Maven scope {dependency_scope!r}")
        exclusions = tuple(
            Exclusion(_required_string(exclusion, "group", path), _required_string(exclusion, "artifact", path))
            for exclusion in values.get("exclusions", ())
        )
        result.append(
            Dependency(
                group_id=group_id,
                artifact_id=artifact_id,
                version=_dependency_version(declaration, workspace_version, path, str(coordinate), version_aliases or {}),
                classifier=str(values["classifier"]) if "classifier" in values else None,
                artifact_type=str(values["type"]) if "type" in values else None,
                scope=dependency_scope,
                optional=bool(values.get("optional", False)),
                exclusions=exclusions,
                workspace_path=str(values["workspace"]) if "workspace" in values else None,
            )
        )
    return tuple(result)


def _dependency_version(
    declaration: Any,
    workspace_version: str,
    path: Path,
    coordinate: str,
    version_aliases: dict[str, Any],
) -> str | None:
    if isinstance(declaration, str):
        return declaration
    if not isinstance(declaration, dict):
        raise PublicationError(f"{path} has an invalid dependency declaration for {coordinate}")
    if "workspace" in declaration:
        return workspace_version
    if "version" in declaration:
        return str(declaration["version"])
    if "versionRef" in declaration:
        alias = str(declaration["versionRef"])
        if alias not in version_aliases:
            raise PublicationError(f"{path} references unknown version alias {alias!r} for {coordinate}")
        return str(version_aliases[alias])
    return None


def _coordinate(value: str, path: Path) -> tuple[str, str]:
    parts = value.split(":")
    if len(parts) != 2 or any(not part for part in parts):
        raise PublicationError(f"{path} has an invalid dependency coordinate: {value!r}")
    return parts[0], parts[1]


def _required_string(table: dict[str, Any], key: str, path: Path) -> str:
    value = table.get(key)
    if not isinstance(value, str) or not value.strip():
        raise PublicationError(f"{path} is missing a non-empty {key}")
    return value.strip()
