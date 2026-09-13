"""One supported Java surface for compatibility, documentation, and architecture checks."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import sys
import tomllib

JAVA_NAME = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*")


@dataclass(frozen=True)
class Surface:
    name: str
    member: str
    artifact: str
    packages: tuple[str, ...]
    types: tuple[str, ...]
    strict_javadoc: bool
    canary_class: str
    canary_member: str

    @property
    def supported_packages(self) -> set[str]:
        return set(self.packages) | {name.rsplit(".", 1)[0] for name in self.types}

    @property
    def includes(self) -> str:
        return ";".join((*self.packages, *self.types))

    def sources(self, root: Path) -> set[Path]:
        source_root = root / self.member / "src/main/java"
        result: set[Path] = set()
        for package in self.packages:
            result.update((source_root / package.replace(".", "/")).glob("*.java"))
        for name in self.types:
            source = source_root / (name.replace(".", "/") + ".java")
            result.add(source)
            result.add(source.with_name("package-info.java"))
        return result


def load(root: Path) -> tuple[str, tuple[Surface, ...]]:
    config = tomllib.loads((root / "supported-api.toml").read_text(encoding="utf-8"))
    if config.get("schema") != 1:
        raise ValueError("supported API schema must be 1")
    annotation = config.get("internal_annotation", "")
    if not JAVA_NAME.fullmatch(annotation):
        raise ValueError("supported API internal_annotation must be a Java name")
    surfaces = []
    names: set[str] = set()
    artifacts: set[str] = set()
    members: set[str] = set()
    for entry in config.get("surface", []):
        surface = Surface(**{**entry, "packages": tuple(entry["packages"]), "types": tuple(entry["types"])})
        if not re.fullmatch(r"[a-z][a-z0-9-]*", surface.name) or surface.name in names:
            raise ValueError(f"invalid or duplicate supported surface: {surface.name}")
        if not re.fullmatch(r"logyard-[a-z0-9-]+", surface.artifact) or surface.artifact in artifacts:
            raise ValueError(f"invalid or duplicate supported artifact: {surface.artifact}")
        member = Path(surface.member)
        if member.is_absolute() or ".." in member.parts or surface.member in members:
            raise ValueError(f"invalid or duplicate supported member: {member}")
        source_root = root / member / "src/main/java"
        if not source_root.is_dir() or type(surface.strict_javadoc) is not bool:
            raise ValueError(f"invalid supported source or Javadoc policy: {member}")
        selectors = (*surface.packages, *surface.types)
        if not selectors or len(set(selectors)) != len(selectors) or not all(JAVA_NAME.fullmatch(s) for s in selectors):
            raise ValueError(f"invalid supported selectors for {surface.name}")
        for package in surface.packages:
            if not (source_root / package.replace(".", "/") / "package-info.java").is_file():
                raise ValueError(f"supported package is missing its documentation: {package}")
        for source in surface.sources(root):
            if not source.is_file():
                raise ValueError(f"supported source is missing: {source}")
        if not JAVA_NAME.fullmatch(surface.canary_class):
            raise ValueError(f"invalid compatibility canary class: {surface.canary_class}")
        if (surface.canary_class not in surface.types
                and surface.canary_class.rsplit(".", 1)[0] not in surface.packages):
            raise ValueError(f"compatibility canary is outside {surface.name}")
        if surface.canary_member != "<init>" and (not JAVA_NAME.fullmatch(surface.canary_member) or "." in surface.canary_member):
            raise ValueError(f"invalid compatibility canary member: {surface.canary_member}")
        if not (source_root / (surface.canary_class.replace(".", "/") + ".java")).is_file():
            raise ValueError(f"compatibility canary class is missing: {surface.canary_class}")
        names.add(surface.name)
        artifacts.add(surface.artifact)
        members.add(surface.member)
        surfaces.append(surface)
    if not surfaces:
        raise ValueError("supported API manifest has no surfaces")
    return annotation, tuple(surfaces)


def main() -> None:
    root = Path(sys.argv[1])
    annotation, surfaces = load(root)
    mode = sys.argv[2]
    if mode == "records":
        for surface in surfaces:
            print("\t".join((surface.name, surface.artifact, surface.member, surface.includes,
                             surface.canary_class, surface.canary_member)))
    elif mode == "annotation":
        print(annotation)
    elif mode == "javadoc-sources":
        print("\n".join(str(source) for source in sorted({
            path for surface in surfaces if surface.strict_javadoc for path in surface.sources(root)})))
    elif mode == "javadoc-roots":
        config = tomllib.loads((root / "zolt.toml").read_text(encoding="utf-8"))
        for member in config["workspace"]["members"]["include"]:
            path = root / member / "src/main/java"
            if path.is_dir():
                print(path)
    else:
        raise ValueError(f"unknown supported API query: {mode}")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, TypeError, ValueError, OSError) as failure:
        raise SystemExit(f"supported API manifest: {failure}") from failure
