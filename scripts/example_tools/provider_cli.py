"""Command-line preflight for one packaged SLF4J application."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .providers import classpath_providers, executable_providers, require_single_logyard


def main() -> int:
    parser = argparse.ArgumentParser(description="Check an application's packaged SLF4J 2 providers.")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--classpath", help="resolved runtime classpath, including dependency JARs")
    source.add_argument("--jar", type=Path, help="Spring Boot executable JAR with nested dependencies")
    arguments = parser.parse_args()

    try:
        if arguments.jar is not None:
            providers = executable_providers(arguments.jar)
            label = str(arguments.jar)
        else:
            providers = classpath_providers(arguments.classpath)
            label = "application runtime classpath"
        require_single_logyard(providers, label)
    except (AssertionError, OSError) as failure:
        print(f"SLF4J provider preflight failed: {failure}", file=sys.stderr)
        return 1
    print(f"SLF4J provider preflight passed: exactly one Logyard provider in {label}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
