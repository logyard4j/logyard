"""Read the OpenTelemetry compile lane from the resolved Zolt lock."""
from __future__ import annotations

import hashlib
import os
import sys
import tomllib
from pathlib import Path


def classpath(root: Path) -> str:
    lock = tomllib.loads((root / "zolt.lock").read_text())
    packages = [entry for entry in lock["package"] if entry.get("scope") == "compile"
                and "modules/logyard-opentelemetry" in entry.get("members", []) and "jar" in entry]
    if not any(entry["id"] == "io.opentelemetry:opentelemetry-api" for entry in packages):
        raise ValueError("OpenTelemetry compile dependencies are missing; run zolt resolve --workspace --locked")
    archives = []
    user_home = os.environ.get("ZOLT_USER_HOME", "").strip()
    user_cache = Path(user_home) / "cache" if user_home else Path.home() / ".zolt/cache"
    for entry in packages:
        relative = Path(entry["jar"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"invalid locked JAR path: {relative}")
        candidates = [base / relative for base in (root / ".zolt/cache", user_cache)]
        archive = next((path for path in candidates if path.is_file()), None)
        if archive is None:
            raise ValueError(f"missing {relative}; run zolt resolve --workspace --locked")
        if hashlib.sha256(archive.read_bytes()).hexdigest() != entry["jarSha256"]:
            raise ValueError(f"Zolt cache checksum mismatch: {archive}")
        archives.append(str(archive))
    return os.pathsep.join(archives)


if __name__ == "__main__":
    print(classpath(Path(sys.argv[1])))
