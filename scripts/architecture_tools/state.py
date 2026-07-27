from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class CheckState:
    errors: list[str] = field(default_factory=list)
    main_files: list[tuple[Path, str]] = field(default_factory=list)
    package_names: set[str] = field(default_factory=set)
    java_sources: set[str] = field(default_factory=set)
    test_file_count: int = 0
    test_method_count: int = 0

    def add_error(self, message: str) -> None:
        self.errors.append(message)
