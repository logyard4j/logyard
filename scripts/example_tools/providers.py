"""Check the SLF4J providers shipped in a consumer's runtime package."""

from __future__ import annotations

import os
from io import BytesIO
from pathlib import Path
from zipfile import BadZipFile, ZipFile


SERVICE = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"
LOGYARD_PROVIDER = "com.logyard4j.logyard.slf4j.LogyardServiceProvider"
Provider = tuple[str, str]


def classpath_providers(classpath: str) -> list[Provider]:
    if not classpath:
        raise AssertionError("consumer runtime classpath is empty")
    found: list[Provider] = []
    for entry in classpath.split(os.pathsep):
        if not entry:
            raise AssertionError("consumer runtime classpath has an empty entry")
        archive = Path(entry)
        if archive.is_dir():
            descriptor = archive / SERVICE
            if descriptor.is_file():
                found.extend(_parse(descriptor.read_bytes(), str(descriptor)))
        elif archive.is_file():
            found.extend(_archive_providers(archive))
        else:
            raise AssertionError(f"consumer runtime classpath entry is missing: {archive}")
    return found


def executable_providers(archive: Path) -> list[Provider]:
    if not archive.is_file():
        raise AssertionError(f"consumer executable package is missing: {archive}")
    found: list[Provider] = []
    try:
        with ZipFile(archive) as package:
            for name in package.namelist():
                if name == SERVICE or name == "BOOT-INF/classes/" + SERVICE:
                    found.extend(_parse(package.read(name), f"{archive}!/{name}"))
                elif name.startswith("BOOT-INF/lib/") and name.endswith(".jar"):
                    with ZipFile(BytesIO(package.read(name))) as dependency:
                        if SERVICE in dependency.namelist():
                            found.extend(_parse(dependency.read(SERVICE), f"{archive}!/{name}!/{SERVICE}"))
    except BadZipFile as failure:
        raise AssertionError(f"consumer executable package is not a valid JAR: {archive}") from failure
    return found


def require_single_logyard(providers: list[Provider], source: str) -> None:
    if len(providers) != 1 or providers[0][1] != LOGYARD_PROVIDER:
        raise AssertionError(f"{source} requires exactly one Logyard SLF4J provider; found {_describe(providers)}")


def require_competing_provider(providers: list[Provider], source: str) -> None:
    if not any(name == LOGYARD_PROVIDER for _, name in providers) or not any(
            name != LOGYARD_PROVIDER for _, name in providers):
        raise AssertionError(f"{source} did not package the expected Logyard/provider conflict: {_describe(providers)}")


def _archive_providers(archive: Path) -> list[Provider]:
    try:
        with ZipFile(archive) as package:
            if SERVICE not in package.namelist():
                return []
            return _parse(package.read(SERVICE), f"{archive}!/{SERVICE}")
    except BadZipFile as failure:
        raise AssertionError(f"consumer runtime classpath entry is not a valid JAR: {archive}") from failure


def _parse(content: bytes, source: str) -> list[Provider]:
    try:
        text = content.decode("utf-8")
    except UnicodeDecodeError as failure:
        raise AssertionError(f"SLF4J service descriptor is not UTF-8: {source}") from failure
    return [(source, name) for line in text.splitlines()
            if (name := line.partition("#")[0].strip())]


def _describe(providers: list[Provider]) -> str:
    return ", ".join(f"{name} in {source}" for source, name in providers) or "none"
