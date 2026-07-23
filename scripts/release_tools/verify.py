from __future__ import annotations

import argparse
import hashlib
from pathlib import Path

from .checksums import ALGORITHMS
from .model import discover_publications, jar_publications, release_version
from .pom import generate_pom
from .types import Publication


def verify(root: Path, target: Path, require_signatures: bool) -> tuple[int, int, bool]:
    root = root.resolve()
    target = target.resolve()
    publications = discover_publications(root)
    version = release_version(publications)
    version_file = target / "VERSION"
    _require(version_file.is_file() and version_file.read_text(encoding="utf-8").strip() == version, "VERSION does not match the workspace")

    primary_artifacts = tuple(_primary_artifacts(target, publication) for publication in publications)
    primaries = tuple(artifact for publication_artifacts in primary_artifacts for artifact in publication_artifacts)
    for publication, artifacts in zip(publications, primary_artifacts, strict=True):
        for artifact in artifacts:
            _require(artifact.is_file() and artifact.stat().st_size > 0, f"missing or empty artifact: {artifact}")
        pom = artifacts[-1]
        _require(pom.read_bytes() == generate_pom(publication), f"{pom.name} does not match its publication manifest")

    has_any_signature = any(_signature(artifact).is_file() for artifact in primaries)
    signed = require_signatures or has_any_signature
    expected_files = {version_file}
    for artifact in primaries:
        expected_files.add(artifact)
        _verify_checksums(artifact)
        expected_files.update(_checksums(artifact))
        signature = _signature(artifact)
        if signed:
            _require(signature.is_file() and signature.stat().st_size > 0, f"missing signature: {signature}")
            _verify_checksums(signature)
            expected_files.add(signature)
            expected_files.update(_checksums(signature))

    actual_files = {path for path in target.rglob("*") if path.is_file()}
    unexpected = sorted(actual_files - expected_files)
    missing = sorted(expected_files - actual_files)
    _require(not unexpected, f"unexpected bundle files: {[str(path.relative_to(target)) for path in unexpected]}")
    _require(not missing, f"missing bundle files: {[str(path.relative_to(target)) for path in missing]}")
    return len(publications), len(primaries), signed


def _primary_artifacts(target: Path, publication: Publication) -> tuple[Path, ...]:
    directory = target / publication.group_path / publication.artifact_id / publication.version
    return tuple(directory / filename for filename in publication.primary_filenames)


def _verify_checksums(artifact: Path) -> None:
    content = artifact.read_bytes()
    for algorithm, checksum in zip(ALGORITHMS, _checksums(artifact), strict=True):
        _require(checksum.is_file(), f"missing checksum: {checksum}")
        expected = hashlib.new(algorithm, content).hexdigest()
        _require(checksum.read_text(encoding="ascii").strip() == expected, f"checksum mismatch: {checksum}")


def _checksums(artifact: Path) -> tuple[Path, ...]:
    return tuple(artifact.with_name(f"{artifact.name}.{algorithm}") for algorithm in ALGORITHMS)


def _signature(artifact: Path) -> Path:
    return artifact.with_name(f"{artifact.name}.asc")


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"release verification failed: {message}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify Logyard's metadata-driven release bundle.")
    parser.add_argument("root", type=Path)
    parser.add_argument("target", type=Path)
    parser.add_argument("--require-signatures", action="store_true")
    arguments = parser.parse_args()
    publication_count, primary_count, signed = verify(arguments.root, arguments.target, arguments.require_signatures)
    print(
        f"Release bundle verified: {publication_count} publications, {len(jar_publications(discover_publications(arguments.root)))} JAR modules, "
        f"{primary_count} primary artifacts, complete POM metadata and dependency contracts, checksums"
        f"{', and signatures' if signed else ''}."
    )


if __name__ == "__main__":
    main()
