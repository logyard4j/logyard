from __future__ import annotations

from pathlib import Path
from typing import Iterable

from .types import Publication, PublicationError, SUPPORTED_ARTIFACTS, SUPPORTED_PACKAGING


def release_version(publications: Iterable[Publication]) -> str:
    versions = {publication.version for publication in publications}
    if len(versions) != 1:
        raise PublicationError(f"publication versions do not match: {sorted(versions)}")
    return next(iter(versions))


def validate_publications(root: Path, publications: tuple[Publication, ...]) -> None:
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
        _validate_publication(
            root,
            publication,
            publication_coordinates,
            coordinates_by_module,
        )


def _validate_publication(
    root: Path,
    publication: Publication,
    publication_coordinates: set[str],
    coordinates_by_module: dict[str, str],
) -> None:
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
        raise PublicationError(f"{publication.manifest_path} has unsupported build system {publication.build_system!r}")
    if publication.packaging == "jar" and publication.build_system == "metadata":
        raise PublicationError(f"{publication.manifest_path} must declare the JAR build system")
    if publication.packaging == "jar" and not publication.automatic_module_name:
        raise PublicationError(f"{publication.manifest_path} must declare Automatic-Module-Name")
    if not (root / publication.module_directory).is_dir():
        raise PublicationError(f"publication module directory is missing: {publication.module_directory}")
    _validate_dependencies(publication, publication_coordinates, coordinates_by_module)


def _validate_dependencies(
    publication: Publication,
    publication_coordinates: set[str],
    coordinates_by_module: dict[str, str],
) -> None:
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
