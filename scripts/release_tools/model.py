from __future__ import annotations

from pathlib import Path
from typing import Iterable

from .publication_reader import read_publications, zolt_test_members
from .publication_validation import release_version, validate_publications
from .types import Publication


def discover_publications(root: Path) -> tuple[Publication, ...]:
    root = root.resolve()
    publications = read_publications(root)
    validate_publications(root, publications)
    return publications


def jar_publications(publications: Iterable[Publication]) -> tuple[Publication, ...]:
    return tuple(publication for publication in publications if publication.packaging == "jar")


def zolt_jar_publications(publications: Iterable[Publication]) -> tuple[Publication, ...]:
    return tuple(
        publication
        for publication in publications
        if publication.packaging == "jar" and publication.build_system == "zolt"
    )


def zolt_publications(publications: Iterable[Publication]) -> tuple[Publication, ...]:
    return tuple(publication for publication in publications if publication.build_system == "zolt")
