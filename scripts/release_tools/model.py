from __future__ import annotations

import tomllib
from pathlib import Path
from typing import Any, Iterable

from .dependencies import dependencies, dependency_table
from .types import Dependency, Metadata, Publication, PublicationError, SUPPORTED_ARTIFACTS, SUPPORTED_PACKAGING


def discover_publications(root: Path) -> tuple[Publication, ...]:
    root = root.resolve()
    zolt_manifests = tuple(sorted((root / "modules").glob("*/zolt.toml")))
    publishable_zolt_manifests = tuple(path for path in zolt_manifests if _is_publishable_zolt_manifest(path))
    raw_zolt = tuple((path, _read_toml(path)) for path in publishable_zolt_manifests)
    versions = {str(manifest["project"]["version"]) for _, manifest in raw_zolt}
    if len(versions) != 1:
        raise PublicationError(f"publishable Zolt module versions do not match: {sorted(versions)}")
    workspace_version = next(iter(versions))

    publications = [
        _zolt_publication(root, path, manifest, workspace_version)
        for path, manifest in raw_zolt
    ]
    publications.extend(
        _standalone_publication(root, path, _read_toml(path), workspace_version)
        for path in sorted(root.glob("modules/*/publication.toml"))
    )
    publications.extend(
        _standalone_publication(root, path, _read_toml(path), workspace_version)
        for path in sorted(root.glob("extensions/**/publication.toml"))
    )
    result = tuple(sorted(publications, key=lambda publication: publication.coordinate))
    _validate_publications(root, result)
    return result


def jar_publications(publications: Iterable[Publication]) -> tuple[Publication, ...]:
    return tuple(publication for publication in publications if publication.packaging == "jar")


def zolt_jar_publications(publications: Iterable[Publication]) -> tuple[Publication, ...]:
    return tuple(
        publication
        for publication in publications
        if publication.packaging == "jar" and publication.build_system == "zolt"
    )


def zolt_publications(publications: Iterable[Publication]) -> tuple[Publication, ...]:
    return tuple(publication for publication in publications if publication.build_system == "zolt")


def zolt_test_members(root: Path) -> tuple[str, ...]:
    workspace = _required_table(_read_toml(root / "zolt.toml"), "workspace", root / "zolt.toml")
    members = workspace.get("members")
    if not isinstance(members, list):
        raise PublicationError(f"{root / 'zolt.toml'} must declare [workspace].members")
    testable_members: list[str] = []
    for member in members:
        if not isinstance(member, str) or not member.strip():
            raise PublicationError(f"{root / 'zolt.toml'} has an invalid workspace member {member!r}")
        manifest = _read_toml(root / member / "zolt.toml")
        test_source = manifest.get("build", {}).get("test")
        if isinstance(test_source, str) and test_source.strip():
            testable_members.append(member)
    return tuple(testable_members)


def release_version(publications: Iterable[Publication]) -> str:
    versions = {publication.version for publication in publications}
    if len(versions) != 1:
        raise PublicationError(f"publication versions do not match: {sorted(versions)}")
    return next(iter(versions))


def _is_publishable_zolt_manifest(path: Path) -> bool:
    manifest = _read_toml(path)
    return bool(manifest.get("package", {}).get("metadata") and manifest.get("publish"))


def _read_toml(path: Path) -> dict[str, Any]:
    try:
        with path.open("rb") as source:
            return tomllib.load(source)
    except (OSError, tomllib.TOMLDecodeError) as failure:
        raise PublicationError(f"cannot read publication manifest {path}: {failure}") from failure


def _zolt_publication(root: Path, path: Path, manifest: dict[str, Any], workspace_version: str) -> Publication:
    project = _required_table(manifest, "project", path)
    package = _required_table(manifest, "package", path)
    metadata = _metadata(_required_table(package, "metadata", path), path)
    bom = manifest.get("bom")
    packaging = "pom" if isinstance(bom, dict) else "jar"
    selectors = tuple(str(value) for value in _required_table(manifest, "publish", path).get("artifacts", ("main",)))
    if packaging == "jar" and (len(selectors) != 1 or selectors[0] not in {"main", "thin"}):
        raise PublicationError(f"{path} must select exactly one Zolt package output: main or thin")
    artifacts = ("pom",) if packaging == "pom" else (
        "main",
        *(("sources",) if bool(package.get("sources", False)) else ()),
        *(("javadoc",) if bool(package.get("javadoc", False)) else ()),
    )
    if packaging == "jar":
        for artifact, package_field in (("sources", "sources"), ("javadoc", "javadoc")):
            if (artifact in artifacts) != bool(package.get(package_field, False)):
                raise PublicationError(f"{path} must derive {artifact} publication from [package].{package_field}")
    automatic_module_name = package.get("manifest", {}).get("Automatic-Module-Name")
    return Publication(
        module_directory=path.parent.relative_to(root),
        manifest_path=path.relative_to(root),
        group_id=_required_string(project, "group", path),
        artifact_id=_required_string(project, "name", path),
        version=_required_string(project, "version", path),
        packaging=packaging,
        artifacts=artifacts,
        metadata=metadata,
        automatic_module_name=str(automatic_module_name) if automatic_module_name else None,
        build_system="zolt",
        dependencies=() if packaging == "pom" else dependencies(manifest, workspace_version, path),
        managed_dependencies=_zolt_bom_dependencies(root, path, manifest, workspace_version)
        if packaging == "pom"
        else (),
    )


def _zolt_bom_dependencies(
    root: Path,
    path: Path,
    manifest: dict[str, Any],
    workspace_version: str,
) -> tuple[Dependency, ...]:
    bom = _required_table(manifest, "bom", path)
    members = bom.get("members", ())
    if members is True:
        member_paths = tuple(
            candidate.parent.relative_to(root).as_posix()
            for candidate in sorted((root / "modules").glob("*/zolt.toml"))
            if candidate != path and _is_publishable_zolt_manifest(candidate)
        )
    elif isinstance(members, list):
        member_paths = tuple(str(member) for member in members)
    else:
        member_paths = ()

    managed: list[Dependency] = []
    for member_path in member_paths:
        member_manifest_path = root / member_path / "zolt.toml"
        member_project = _required_table(_read_toml(member_manifest_path), "project", member_manifest_path)
        managed.append(
            Dependency(
                group_id=_required_string(member_project, "group", member_manifest_path),
                artifact_id=_required_string(member_project, "name", member_manifest_path),
                version=_required_string(member_project, "version", member_manifest_path),
                workspace_path=member_path,
            )
        )
    managed.extend(
        dependency_table(
            bom.get("versions", {}),
            "compile",
            workspace_version,
            path,
            include_all=True,
            version_aliases=manifest.get("versions", {}),
        )
    )
    imports = {
        coordinate: {"version": declaration, "type": "pom", "scope": "import"}
        if isinstance(declaration, str)
        else {**declaration, "type": "pom", "scope": "import"}
        for coordinate, declaration in bom.get("imports", {}).items()
    }
    managed.extend(
        dependency_table(
            imports,
            "import",
            workspace_version,
            path,
            include_all=True,
            version_aliases=manifest.get("versions", {}),
        )
    )
    overlay_path = path.parent / "publication-overlay.toml"
    if overlay_path.is_file():
        overlay = _read_toml(overlay_path)
        managed.extend(
            dependency_table(
                overlay.get("dependencyManagement", {}),
                "compile",
                workspace_version,
                overlay_path,
                include_all=True,
            )
        )
    return tuple(sorted(managed, key=lambda dependency: (dependency.coordinate, dependency.classifier or "")))


def _standalone_publication(root: Path, path: Path, manifest: dict[str, Any], workspace_version: str) -> Publication:
    project = _required_table(manifest, "project", path)
    package = _required_table(manifest, "package", path)
    publication = _required_table(manifest, "publication", path)
    packaging = _required_string(publication, "packaging", path)
    default_artifacts = ("pom",) if packaging == "pom" else ("main",)
    artifacts = tuple(str(value) for value in publication.get("artifacts", default_artifacts))
    automatic_module_name = package.get("manifest", {}).get("Automatic-Module-Name")
    return Publication(
        module_directory=path.parent.relative_to(root),
        manifest_path=path.relative_to(root),
        group_id=_required_string(project, "group", path),
        artifact_id=_required_string(project, "name", path),
        version=_required_string(project, "version", path),
        packaging=packaging,
        artifacts=artifacts,
        metadata=_metadata(_required_table(package, "metadata", path), path),
        automatic_module_name=str(automatic_module_name) if automatic_module_name else None,
        build_system=str(publication.get("buildSystem", "metadata")),
        dependencies=dependencies(manifest, workspace_version, path),
        managed_dependencies=dependency_table(
            manifest.get("dependencyManagement", {}),
            "compile",
            workspace_version,
            path,
            include_all=True,
            version_aliases=manifest.get("versions", {}),
        ),
    )


def _metadata(raw: dict[str, Any], path: Path) -> Metadata:
    return Metadata(
        name=_required_string(raw, "name", path),
        description=_required_string(raw, "description", path),
        url=_required_string(raw, "url", path),
        license=_required_string(raw, "license", path),
        scm=_required_string(raw, "scm", path),
        issues=_required_string(raw, "issues", path),
    )


def _validate_publications(root: Path, publications: tuple[Publication, ...]) -> None:
    if not publications:
        raise PublicationError("no publishable modules were discovered")
    release_version(publications)
    coordinates = [publication.coordinate for publication in publications]
    duplicates = sorted(coordinate for coordinate in set(coordinates) if coordinates.count(coordinate) > 1)
    if duplicates:
        raise PublicationError(f"duplicate publication coordinates: {duplicates}")
    publication_coordinates = set(coordinates)
    coordinates_by_module = {publication.relative_module_path: publication.coordinate for publication in publications}
    for publication in publications:
        if publication.packaging not in SUPPORTED_PACKAGING:
            raise PublicationError(f"{publication.manifest_path} has unsupported packaging {publication.packaging!r}")
        unknown_artifacts = set(publication.artifacts) - SUPPORTED_ARTIFACTS
        if unknown_artifacts:
            raise PublicationError(f"{publication.manifest_path} has unsupported artifacts: {sorted(unknown_artifacts)}")
        if publication.packaging == "pom" and publication.artifacts != ("pom",):
            raise PublicationError(f"{publication.manifest_path} must publish only the POM artifact")
        if publication.packaging == "jar" and "main" not in publication.artifacts:
            raise PublicationError(f"{publication.manifest_path} must publish a main JAR")
        if publication.packaging == "jar" and "pom" in publication.artifacts:
            raise PublicationError(f"{publication.manifest_path} must not declare the generated POM as a JAR artifact")
        if publication.build_system not in {"metadata", "maven", "zolt"}:
            raise PublicationError(
                f"{publication.manifest_path} has unsupported build system {publication.build_system!r}"
            )
        if publication.packaging == "jar" and publication.build_system == "metadata":
            raise PublicationError(f"{publication.manifest_path} must declare the JAR build system")
        if publication.packaging == "jar" and not publication.automatic_module_name:
            raise PublicationError(f"{publication.manifest_path} must declare Automatic-Module-Name")
        if not (root / publication.module_directory).is_dir():
            raise PublicationError(f"publication module directory is missing: {publication.module_directory}")
        for dependency in publication.dependencies + publication.managed_dependencies:
            if dependency.workspace_path:
                actual_coordinate = coordinates_by_module.get(dependency.workspace_path)
                if actual_coordinate != dependency.coordinate:
                    raise PublicationError(
                        f"{publication.manifest_path} maps {dependency.coordinate} to workspace member "
                        f"{dependency.workspace_path!r}, which publishes {actual_coordinate or 'nothing'}"
                    )
        for dependency in publication.managed_dependencies:
            if dependency.group_id == publication.group_id and dependency.coordinate not in publication_coordinates:
                raise PublicationError(f"{publication.manifest_path} manages unpublished workspace coordinate {dependency.coordinate}")


def _required_table(table: dict[str, Any], key: str, path: Path) -> dict[str, Any]:
    value = table.get(key)
    if not isinstance(value, dict):
        raise PublicationError(f"{path} is missing [{key}]")
    return value


def _required_string(table: dict[str, Any], key: str, path: Path) -> str:
    value = table.get(key)
    if not isinstance(value, str) or not value.strip():
        raise PublicationError(f"{path} is missing a non-empty {key}")
    return value.strip()
