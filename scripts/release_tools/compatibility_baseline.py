from __future__ import annotations

import argparse
import hashlib
import tempfile
import tomllib
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SURFACES = ("api", "runtime", "jul", "spring", "quarkus")


@dataclass(frozen=True)
class BaselineArtifact:
    artifact_id: str
    sha256: str


@dataclass(frozen=True)
class CompatibilityBaseline:
    version: str
    repository: str
    group_id: str
    first_release: str | None
    reason: str | None
    artifacts: dict[str, BaselineArtifact]

    @property
    def absent(self) -> bool:
        return self.version == "none"


def read_baseline(path: Path) -> CompatibilityBaseline:
    with path.open("rb") as source:
        document = tomllib.load(source)
    if document.get("schema") != 1:
        raise ValueError("compatibility baseline schema must be 1")
    version = required_text(document, "version")
    repository = required_text(document, "repository").rstrip("/")
    group_id = optional_text(document, "group", "com.zsumz.logyard")
    first_release = document.get("first_release")
    if first_release is not None and (not isinstance(first_release, str) or not first_release.strip()):
        raise ValueError("compatibility baseline first_release must be a non-blank string")
    reason = document.get("reason")
    if reason is not None and (not isinstance(reason, str) or not reason.strip()):
        raise ValueError("compatibility baseline reason must be a non-blank string")
    artifacts_document = document.get("artifacts", {})
    if not isinstance(artifacts_document, dict):
        raise ValueError("compatibility baseline artifacts must be a table")
    if version == "none":
        if not reason:
            raise ValueError("an absent compatibility baseline requires a reason")
        if not first_release:
            raise ValueError("an absent compatibility baseline requires first_release")
        if artifacts_document:
            raise ValueError("an absent compatibility baseline must not declare artifacts")
        return CompatibilityBaseline(version, repository, group_id, first_release, reason, {})
    if first_release is not None:
        raise ValueError("a released compatibility baseline must not declare first_release")
    if version.endswith("-SNAPSHOT"):
        raise ValueError("compatibility baseline must identify an immutable released version")
    if set(artifacts_document) != set(SURFACES):
        raise ValueError(f"compatibility baseline artifacts must be exactly: {', '.join(SURFACES)}")
    artifacts = {
        surface: artifact(surface, value)
        for surface, value in artifacts_document.items()
    }
    return CompatibilityBaseline(version, repository, group_id, None, reason, artifacts)


def fetch_baseline(baseline: CompatibilityBaseline, target: Path) -> list[Path]:
    if baseline.absent:
        raise ValueError("cannot fetch an absent compatibility baseline")
    target.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for surface in SURFACES:
        artifact = baseline.artifacts[surface]
        filename = f"{artifact.artifact_id}-{baseline.version}.jar"
        group_path = baseline.group_id.replace(".", "/")
        url = f"{baseline.repository}/{group_path}/{artifact.artifact_id}/{baseline.version}/{filename}"
        destination = target / filename
        fetch_verified(url, destination, artifact.sha256)
        paths.append(destination)
    return paths


def fetch_verified(url: str, destination: Path, expected_sha256: str) -> None:
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temporary:
        temporary_path = Path(temporary.name)
        try:
            with urllib.request.urlopen(url) as response:
                while chunk := response.read(64 * 1024):
                    temporary.write(chunk)
        except BaseException:
            temporary_path.unlink(missing_ok=True)
            raise
    actual = hashlib.sha256(temporary_path.read_bytes()).hexdigest()
    if actual != expected_sha256:
        temporary_path.unlink(missing_ok=True)
        raise ValueError(
            f"compatibility baseline checksum mismatch for {destination.name}: "
            f"expected {expected_sha256}, found {actual}"
        )
    temporary_path.replace(destination)


def artifact(surface: str, value: Any) -> BaselineArtifact:
    if not isinstance(value, dict):
        raise ValueError(f"compatibility artifact {surface!r} must be a table")
    artifact_id = required_text(value, "artifact")
    sha256 = required_text(value, "sha256").lower()
    if len(sha256) != 64 or any(character not in "0123456789abcdef" for character in sha256):
        raise ValueError(f"compatibility artifact {surface!r} requires a 64-character SHA-256")
    return BaselineArtifact(artifact_id, sha256)


def required_text(document: dict[str, Any], key: str) -> str:
    value = document.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"compatibility baseline {key!r} must be a non-blank string")
    return value.strip()


def optional_text(document: dict[str, Any], key: str, default: str) -> str:
    return required_text(document, key) if key in document else default


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate and fetch the pinned API compatibility baseline.")
    subparsers = parser.add_subparsers(dest="command", required=True)
    status = subparsers.add_parser("status")
    status.add_argument("manifest", type=Path)
    fetch = subparsers.add_parser("fetch")
    fetch.add_argument("manifest", type=Path)
    fetch.add_argument("target", type=Path)
    arguments = parser.parse_args()

    baseline = read_baseline(arguments.manifest)
    if arguments.command == "status":
        print(baseline.version)
        print(baseline.first_release or "")
        print(baseline.reason or "")
        return
    for path in fetch_baseline(baseline, arguments.target):
        print(path.resolve())


if __name__ == "__main__":
    main()
