from __future__ import annotations

import argparse
import sys
import tomllib
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Check framework versions against the repository catalog.")
    parser.add_argument("root", type=Path)
    root = parser.parse_args().root.resolve()
    try:
        versions = load_versions(root)
        checks = expected_fragments(versions)
        for path, fragment in checks:
            require(root, path, fragment)
    except (AssertionError, OSError, KeyError, tomllib.TOMLDecodeError) as failure:
        print(f"framework version check failed: {failure}", file=sys.stderr)
        raise SystemExit(1) from failure
    print(f"Framework versions are aligned across {len(checks)} declarations.")


def load_versions(root: Path) -> dict[str, str]:
    with (root / "framework-versions.toml").open("rb") as source:
        return {name: str(value) for name, value in tomllib.load(source)["frameworks"].items()}


def expected_fragments(versions: dict[str, str]) -> tuple[tuple[str, str], ...]:
    return (
        ("modules/logyard-spring-boot/zolt.toml", f'"org.springframework.boot:spring-boot" = "{versions["spring_boot_baseline"]}"'),
        ("modules/logyard-spring-boot-starter/zolt.toml", f'version = "{versions["spring_boot_baseline"]}"'),
        ("examples/spring-boot/pom.xml", f"<spring-boot.version>{versions['spring_boot_current']}</spring-boot.version>"),
        ("extensions/logyard-quarkus/pom.xml", f"<quarkus.version>{versions['quarkus']}</quarkus.version>"),
        ("examples/quarkus/pom.xml", f"<quarkus.version>{versions['quarkus']}</quarkus.version>"),
        ("extensions/logyard-quarkus/runtime/publication.toml", f'"io.quarkus:quarkus-core" = "{versions["quarkus"]}"'),
        ("extensions/logyard-quarkus/deployment/publication.toml", f'"io.quarkus:quarkus-core-deployment" = "{versions["quarkus"]}"'),
        ("examples/micronaut/pom.xml", f"<micronaut.version>{versions['micronaut']}</micronaut.version>"),
        ("examples/vertx/pom.xml", f"<vertx.version>{versions['vertx']}</vertx.version>"),
        ("benchmarks/logyard-benchmarks/zolt.toml", f'"org.openjdk.jmh:jmh-core" = "{versions["jmh"]}"'),
        ("extensions/logyard-quarkus/benchmarks/pom.xml", f"<jmh.version>{versions['jmh']}</jmh.version>"),
        (
            "README.md",
            "plain SLF4J 2, "
            f"Vert.x {versions['vertx']}, "
            f"Micronaut {versions['micronaut']}, "
            "Spring Boot "
            f"{versions['spring_boot_baseline']} and {versions['spring_boot_current']}, "
            f"and Quarkus {versions['quarkus']}",
        ),
    )


def require(root: Path, path: str, fragment: str) -> None:
    text = (root / path).read_text(encoding="utf-8")
    if fragment not in text:
        raise AssertionError(f"{path} does not match framework-versions.toml: missing {fragment!r}")


if __name__ == "__main__":
    main()
