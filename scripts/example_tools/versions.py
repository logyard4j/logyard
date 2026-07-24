from __future__ import annotations

import tomllib
from pathlib import Path


def framework_versions(root: Path) -> dict[str, str]:
    with (root / "framework-versions.toml").open("rb") as source:
        raw = tomllib.load(source)
    return {name: str(version) for name, version in raw["frameworks"].items()}
