from __future__ import annotations

import argparse
import sys
from pathlib import Path

from .layout import check_package_layout
from .manifest_contracts import check_manifest_contracts
from .runtime_contracts import check_runtime_contracts
from .sources import audit_sources
from .state import CheckState


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify Logyard's source, package, and publication architecture.")
    parser.add_argument("root", type=Path)
    arguments = parser.parse_args()
    root = arguments.root.resolve()
    state = CheckState()
    audit_sources(root, state)
    check_package_layout(root, state)
    check_manifest_contracts(root, state)
    check_runtime_contracts(root, state)
    if state.errors:
        for error in state.errors:
            print(f"architecture check failed: {error}", file=sys.stderr)
        raise SystemExit(1)
    print(
        "Architecture checks passed: "
        f"{len(state.main_files)} main Java files, {state.test_file_count} test files, "
        f"{state.test_method_count} JUnit tests, bounded runtime and extension contracts."
    )


if __name__ == "__main__":
    main()
