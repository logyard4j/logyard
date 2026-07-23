from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


ALGORITHMS = ("md5", "sha1", "sha256")


def generate_checksums(target: Path) -> None:
    repository_files = sorted(
        path
        for path in target.rglob("*")
        if path.is_file() and path.name != "VERSION" and path.suffix in {".jar", ".pom", ".asc"}
    )
    for artifact in repository_files:
        content = artifact.read_bytes()
        for algorithm in ALGORITHMS:
            digest = hashlib.new(algorithm, content).hexdigest()
            artifact.with_name(f"{artifact.name}.{algorithm}").write_text(digest + "\n", encoding="ascii")


def main() -> None:
    parser = argparse.ArgumentParser(description="Generate release artifact checksums.")
    parser.add_argument("target", type=Path)
    arguments = parser.parse_args()
    generate_checksums(arguments.target.resolve())


if __name__ == "__main__":
    main()
