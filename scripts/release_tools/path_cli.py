from __future__ import annotations

import argparse
from pathlib import Path


def file_uri(path: Path) -> str:
    """Return a Maven-compatible file URI on POSIX and Windows."""
    return path.expanduser().resolve().as_uri()


def main() -> None:
    parser = argparse.ArgumentParser(description="Render a filesystem path as a portable file URI.")
    parser.add_argument("path", type=Path)
    arguments = parser.parse_args()
    print(file_uri(arguments.path))


if __name__ == "__main__":
    main()
