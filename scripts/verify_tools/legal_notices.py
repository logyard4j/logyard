"""Keep packaged notices byte-identical to the repository's canonical notices."""
from __future__ import annotations

import sys
import tomllib
import zipfile
from pathlib import Path


def verify_sources(root: Path) -> None:
    workspace = tomllib.loads((root / "zolt.toml").read_text())
    members = []
    for member in workspace["workspace"]["members"]["include"]:
        config = tomllib.loads((root / member / "zolt.toml").read_text())
        if "publish" in config and "bom" not in config:
            members.append(member)
    members.extend(f"extensions/logyard-quarkus/{part}" for part in ("runtime", "deployment"))
    for member in members:
        for name in ("LICENSE", "NOTICE"):
            source = root / member / "src/main/resources/META-INF" / name
            if not source.is_file() or source.read_bytes() != (root / name).read_bytes():
                raise ValueError(f"{source}: must match canonical {name}")


def verify_archive(root: Path, archive: Path) -> None:
    with zipfile.ZipFile(archive) as jar:
        for name in ("LICENSE", "NOTICE"):
            entry = f"META-INF/{name}"
            if jar.namelist().count(entry) != 1 or jar.read(entry) != (root / name).read_bytes():
                raise ValueError(f"{archive}: missing, duplicate, or noncanonical {entry}")


def main() -> None:
    root = Path(sys.argv[1])
    if len(sys.argv) == 2:
        verify_sources(root)
    else:
        for archive in sys.argv[2:]:
            verify_archive(root, Path(archive))


if __name__ == "__main__":
    main()
