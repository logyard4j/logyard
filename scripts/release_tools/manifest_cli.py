from __future__ import annotations

import argparse
from pathlib import Path

from .model import discover_publications, jar_publications, release_version, zolt_jar_publications, zolt_publications, zolt_test_members


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect Logyard publication manifests.")
    parser.add_argument(
        "command",
        choices=("jar-members", "jar-records", "maven-records", "summary", "test-members", "version", "zolt-members"),
    )
    parser.add_argument("root", type=Path)
    arguments = parser.parse_args()

    publications = discover_publications(arguments.root)
    jars = jar_publications(publications)
    zolt_jars = zolt_jar_publications(publications)
    if arguments.command == "jar-members":
        print(",".join(publication.relative_module_path for publication in zolt_jars))
    elif arguments.command == "zolt-members":
        print(",".join(publication.relative_module_path for publication in zolt_publications(publications)))
    elif arguments.command == "test-members":
        print(",".join(zolt_test_members(arguments.root)))
    elif arguments.command == "jar-records":
        for publication in zolt_jars:
            print(
                "\t".join(
                    (
                        publication.artifact_id,
                        publication.automatic_module_name or "",
                        publication.version,
                        str(len(publication.artifacts)),
                    )
                )
            )
    elif arguments.command == "maven-records":
        for publication in jars:
            if publication.build_system == "maven":
                print(
                    "\t".join(
                        (
                            publication.relative_module_path,
                            publication.artifact_id,
                            publication.automatic_module_name or "",
                            publication.version,
                            str(len(publication.artifacts)),
                        )
                    )
                )
    elif arguments.command == "version":
        print(release_version(publications))
    else:
        primary_count = sum(len(publication.primary_filenames) for publication in publications)
        print(
            f"{len(publications)} publications, {len(jars)} JAR modules, "
            f"{primary_count} Maven primary artifacts, version {release_version(publications)}"
        )


if __name__ == "__main__":
    main()
