"""Discovers the JDK home passed to native Zolt for sources and Javadoc packaging."""

from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from collections.abc import Mapping


class JdkDiscoveryError(RuntimeError):
    """Raised when no complete JDK can be found."""


def discover(environment: Mapping[str, str] | None = None) -> Path:
    values = os.environ if environment is None else environment
    for variable in ("ZOLT_JAVA_HOME", "JAVA_HOME"):
        configured = values.get(variable, "").strip()
        if configured:
            return _require_jdk(Path(configured), variable)

    java = shutil.which("java")
    if java:
        discovered = _java_home(java)
        if discovered is not None and _is_jdk(discovered):
            return discovered.resolve()

    javadoc = shutil.which("javadoc")
    if javadoc:
        candidate = Path(javadoc).resolve().parent.parent
        if _is_jdk(candidate):
            return candidate

    raise JdkDiscoveryError(
        "no JDK with javadoc was found; set ZOLT_JAVA_HOME or JAVA_HOME, or place a complete JDK on PATH"
    )


def _java_home(java: str) -> Path | None:
    result = subprocess.run(
        [java, "-XshowSettings:properties", "-version"],
        capture_output=True,
        check=False,
        text=True,
    )
    for line in (result.stderr + "\n" + result.stdout).splitlines():
        match = re.match(r"\s*java\.home\s*=\s*(.+?)\s*$", line)
        if match:
            return Path(match.group(1))
    return None


def _require_jdk(candidate: Path, source: str) -> Path:
    resolved = candidate.expanduser().resolve()
    if not _is_jdk(resolved):
        raise JdkDiscoveryError(f"{source} does not name a JDK containing bin/javadoc: {candidate}")
    return resolved


def _is_jdk(candidate: Path) -> bool:
    executable = "javadoc.exe" if os.name == "nt" else "javadoc"
    return (candidate / "bin" / executable).is_file()


def main() -> int:
    try:
        print(discover())
        return 0
    except JdkDiscoveryError as failure:
        print(f"JDK discovery failed: {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
