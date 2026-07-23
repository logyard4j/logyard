from __future__ import annotations

import argparse
import zipfile
from pathlib import Path


REPRODUCIBLE_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def create_archive(release_bundle: Path, archive: Path) -> int:
    release_bundle = release_bundle.resolve()
    archive = archive.resolve()
    files = tuple(sorted(path for path in release_bundle.rglob("*") if path.is_file() and path.name != "VERSION"))
    if not files:
        raise SystemExit("Central bundle failed: the release bundle has no Maven artifacts")
    archive.parent.mkdir(parents=True, exist_ok=True)
    if archive.exists():
        archive.unlink()
    with zipfile.ZipFile(archive, mode="w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as destination:
        for source in files:
            relative = source.relative_to(release_bundle).as_posix()
            entry = zipfile.ZipInfo(relative, date_time=REPRODUCIBLE_TIMESTAMP)
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            destination.writestr(entry, source.read_bytes())
    return len(files)


def main() -> None:
    parser = argparse.ArgumentParser(description="Create a deterministic Central Portal deployment bundle.")
    parser.add_argument("release_bundle", type=Path)
    parser.add_argument("archive", type=Path)
    arguments = parser.parse_args()
    file_count = create_archive(arguments.release_bundle, arguments.archive)
    print(f"Central Portal bundle created: {arguments.archive.resolve()} ({file_count} Maven-layout files).")


if __name__ == "__main__":
    main()
