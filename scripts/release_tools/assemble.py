from __future__ import annotations

import argparse
import shutil
import tempfile
from pathlib import Path

from .model import discover_publications, release_version
from .pom import generate_pom
from .types import Publication


def assemble(
    root: Path,
    target: Path,
    excluded_build_systems: frozenset[str] = frozenset(),
    metadata_only_build_systems: frozenset[str] = frozenset(),
) -> tuple[Publication, ...]:
    root = root.resolve()
    target = target.resolve()
    _validate_target(root, target)
    publications = tuple(
        publication
        for publication in discover_publications(root)
        if publication.build_system not in excluded_build_systems
    )
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)

    for publication in publications:
        destination = target / publication.group_path / publication.artifact_id / publication.version
        destination.mkdir(parents=True)
        if publication.packaging == "jar" and publication.build_system not in metadata_only_build_systems:
            source_directory = root / publication.module_directory / "target"
            for filename in publication.primary_filenames[:-1]:
                source = source_directory / filename
                if not source.is_file() or source.stat().st_size == 0:
                    raise SystemExit(f"release bundle failed: missing packaged artifact: {source}")
                shutil.copyfile(source, destination / filename)
        pom = destination / publication.primary_filenames[-1]
        pom.write_bytes(generate_pom(publication))

    (target / "VERSION").write_text(release_version(publications) + "\n", encoding="utf-8")
    return publications


def _validate_target(root: Path, target: Path) -> None:
    allowed_roots = ((root / "target").resolve(), Path(tempfile.gettempdir()).resolve())
    if not any(target == allowed_root or allowed_root in target.parents for allowed_root in allowed_roots):
        raise SystemExit(f"release bundle failed: target must be under {allowed_roots[0]} or {allowed_roots[1]}: {target}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Assemble Logyard's Maven-layout release bundle.")
    parser.add_argument("root", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("--exclude-build-system", action="append", default=[])
    parser.add_argument("--metadata-only-build-system", action="append", default=[])
    arguments = parser.parse_args()
    publications = assemble(
        arguments.root,
        arguments.target,
        frozenset(arguments.exclude_build_system),
        frozenset(arguments.metadata_only_build_system),
    )
    metadata_only_build_systems = frozenset(arguments.metadata_only_build_system)
    artifact_count = sum(
        1 if publication.build_system in metadata_only_build_systems else len(publication.primary_filenames)
        for publication in publications
    )
    print(
        f"Release bundle assembled: {len(publications)} publications and "
        f"{artifact_count} primary artifacts."
    )


if __name__ == "__main__":
    main()
