"""Materialize Zolt publication POMs without needing release credentials."""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path


def zolt_command(zolt: str, *arguments: str) -> tuple[str, ...]:
    if sys.platform == "win32" and Path(zolt).suffix.lower() not in {".bat", ".cmd", ".com", ".exe"}:
        return ("bash", zolt, *arguments)
    return (zolt, *arguments)


def unsigned_manifest(content: str) -> str:
    config = tomllib.loads(content)
    if "publish" not in config:
        return content
    lines = []
    publishing = False
    for line in content.splitlines():
        if line.startswith("["):
            publishing = line == "[publish]" or line.startswith("[publish.")
        if not publishing:
            lines.append(line)
    return "\n".join(lines) + '''
[publish]
release = "local-plan"
snapshot = "local-plan"

[publish.repositories.local-plan]
url = "http://127.0.0.1:9"
'''


def require_unsigned_readiness(output: str, coordinates: list[str]) -> None:
    blockers = []
    inside = False
    for line in output.splitlines():
        if line == "Blockers:":
            inside = True
        elif inside and line.startswith("- "):
            blockers.append(line[2:].split(" — ", 1)[0])
        elif inside:
            inside = False
    expected = [f"{coordinate}: gpg signatures" for coordinate in coordinates]
    expected += [f"{coordinate}: release version" for coordinate in coordinates if coordinate.endswith("-SNAPSHOT")]
    if sorted(blockers) != sorted(expected):
        raise AssertionError(f"unexpected unsigned Central readiness blockers: {blockers}")


def prepare(root: Path, zolt: str, central: bool = False) -> None:
    members = tomllib.loads((root / "zolt.toml").read_text())["workspace"]["members"]["include"]
    coordinates = []
    publication_target = root / "target"
    publication_target.mkdir(exist_ok=True)
    # Only the temporary publication routing changes. Zolt reads the real package
    # metadata, lock and built outputs; it owns POM generation and dependency scopes.
    with tempfile.TemporaryDirectory(prefix="publication-plan-", dir=publication_target) as temporary:
        staged = Path(temporary)
        for name in ("zolt.toml", "zolt.lock"):
            shutil.copyfile(root / name, staged / name)
        for member in members:
            source = root / member
            destination = staged / member
            destination.mkdir(parents=True)
            content = (source / "zolt.toml").read_text()
            config = tomllib.loads(content)
            if "publish" in config:
                project = config["project"]
                coordinates.append(f"{project['group']}:{project['name']}:{project['version']}")
            (destination / "zolt.toml").write_text(unsigned_manifest(content))
            for name in ("src", "target"):
                if (source / name).exists():
                    shutil.copytree(source / name, destination / name)
        command = zolt_command(zolt, "publish", "--directory", str(staged), "--workspace", "--all",
                               "--dry-run", "--no-progress", "--color", "never",
                               *(("--central",) if central else ()))
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
        if central and result.returncode == 1:
            try:
                require_unsigned_readiness(result.stdout, coordinates)
            except AssertionError:
                print(result.stdout)
                raise
            print(f"Unsigned Central metadata and artifact checks passed for {len(coordinates)} publications; signing is deferred.")
        else:
            print(result.stdout)
            result.check_returncode()
            if central:
                raise AssertionError("unsigned Central plan unexpectedly omitted signing blockers")
        for member in members:
            for pom in (staged / member / "target/publish").glob("*.pom"):
                destination = root / member / "target/publish" / pom.name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(pom, destination)


if __name__ == "__main__":
    prepare(Path(sys.argv[1]).resolve(), sys.argv[2], central="--central" in sys.argv[3:])
