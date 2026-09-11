from __future__ import annotations

import re
from pathlib import Path

from .constants import (
    ALLOWED_IMPORT_PREFIXES,
    IMPORT,
    MAX_GRANDFATHERED_PRODUCTION_LINES,
    MAX_NEW_PRODUCTION_LINES,
    MAX_TEST_OR_EXAMPLE_LINES,
    PACKAGE,
    UNBOUNDED,
)
from .state import CheckState


def audit_sources(root: Path, state: CheckState) -> None:
    source_roots = _java_roots(root, "main")
    test_roots = _java_roots(root, "test")
    baseline = _read_size_baseline(root)
    _check_production_sources(root, source_roots, state)
    _check_package_descriptors(root, source_roots, state)
    _check_package_cycles(state)
    _check_test_sources(root, test_roots, state)
    _check_example_sources(root, state)
    _check_sizes(root, baseline, state)


def _java_roots(root: Path, source_set: str) -> list[Path]:
    workspace_roots = {
        path
        for group in ("modules", "tests", "benchmarks")
        for path in (root / group).glob(f"*/src/{source_set}/java")
        if path.is_dir()
    }
    extension_roots = {
        path
        for path in (root / "extensions").glob(f"**/src/{source_set}/java")
        if path.is_dir()
    }
    comparison_roots = {
        path for path in (root / "benchmarks/comparison").glob(f"*/src/{source_set}/java") if path.is_dir()
    }
    return sorted(workspace_roots | extension_roots | comparison_roots)


def _read_size_baseline(root: Path) -> dict[str, int]:
    baseline_path = root / "scripts/java-size-baseline.tsv"
    baseline: dict[str, int] = {}
    for line_number, line in enumerate(baseline_path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 2 or not fields[1].isdigit():
            raise SystemExit(f"{baseline_path.relative_to(root)}:{line_number}: expected path<TAB>line-count")
        source, limit_text = fields
        limit = int(limit_text)
        if source in baseline:
            raise SystemExit(f"{baseline_path.relative_to(root)}:{line_number}: duplicate {source}")
        if not 220 < limit <= MAX_GRANDFATHERED_PRODUCTION_LINES:
            raise SystemExit(
                f"{baseline_path.relative_to(root)}:{line_number}: baseline must be between 221 and "
                f"{MAX_GRANDFATHERED_PRODUCTION_LINES} lines"
            )
        baseline[source] = limit
    return baseline


def _check_production_sources(root: Path, source_roots: list[Path], state: CheckState) -> None:
    for source_root in source_roots:
        member = source_root.parents[2].name
        allowed_imports = ALLOWED_IMPORT_PREFIXES.get(member)
        if allowed_imports is None:
            state.add_error(f"{source_root.relative_to(root)}: no import boundary is defined")
            continue
        for source in sorted(source_root.rglob("*.java")):
            state.java_sources.add(source.relative_to(root).as_posix())
            if source.name == "module-info.java":
                continue
            _check_production_source(root, source_root, source, member, allowed_imports, state)


def _check_production_source(
    root: Path,
    source_root: Path,
    source: Path,
    member: str,
    allowed_imports: tuple[str, ...],
    state: CheckState,
) -> None:
    text = source.read_text(encoding="utf-8")
    relative = source.relative_to(source_root)
    expected_package = ".".join(relative.parent.parts)
    package_match = PACKAGE.search(text)
    if package_match is None:
        state.add_error(f"{source.relative_to(root)}: missing package declaration")
        return
    actual_package = package_match.group(1)
    if actual_package != expected_package:
        state.add_error(f"{source.relative_to(root)}: package {actual_package!r} does not match source path {expected_package!r}")
    state.package_names.add(actual_package)
    state.main_files.append((source, member))
    _check_imports(root, source, text, member, allowed_imports, state)
    _check_source_safety(root, source, text, member, state)


def _check_imports(
    root: Path,
    source: Path,
    text: str,
    member: str,
    allowed_imports: tuple[str, ...],
    state: CheckState,
) -> None:
    for imported in IMPORT.findall(text):
        if imported.endswith(".*"):
            state.add_error(f"{source.relative_to(root)}: wildcard imports are forbidden")
        if not imported.startswith(allowed_imports):
            state.add_error(f"{source.relative_to(root)}: import {imported!r} violates the {member} dependency boundary")


def _check_source_safety(root: Path, source: Path, text: str, member: str, state: CheckState) -> None:
    relative = source.relative_to(root)
    if "AtomicBoolean" in text or re.search(r"\bvolatile\s+boolean\b", text):
        state.add_error(f"{relative}: lifecycle and ownership state must use named phases, not mutable Boolean flags")
    if "printStackTrace(" in text:
        state.add_error(f"{relative}: printStackTrace is forbidden")
    for pattern, description in UNBOUNDED:
        if pattern.search(text):
            state.add_error(f"{relative}: {description} are forbidden")
    for marker in (
        "com.zsumz.logyard.output.otlp", "org.apache.kafka.",
        "com.zsumz.logyard.kafka.", "com.zsumz.logyard.slf4j17.", "org.slf4j.impl.",
    ):
        if marker in text:
            state.add_error(f"{relative}: removed integration marker {marker!r} remains")
    if "io.opentelemetry." in text and member != "logyard-opentelemetry":
        state.add_error(f"{relative}: OpenTelemetry API access is confined to logyard-opentelemetry")
    if "java.nio.file.WatchService" in text and member != "logyard-runtime":
        state.add_error(f"{relative}: WatchService is confined to logyard-runtime")
    if ("java.nio.channels.FileLock" in text or ".tryLock(" in text) and member != "logyard-output-json":
        state.add_error(f"{relative}: file locking is confined to logyard-output-json")


def _check_package_descriptors(root: Path, source_roots: list[Path], state: CheckState) -> None:
    for source_root in source_roots:
        package_directories = (
            directory
            for directory in source_root.rglob("*")
            if directory.is_dir() and any(source.name != "module-info.java" for source in directory.glob("*.java"))
        )
        for directory in sorted(package_directories):
            if not (directory / "package-info.java").is_file():
                state.add_error(f"{directory.relative_to(root)}: production package is missing package-info.java")


def _check_package_cycles(state: CheckState) -> None:
    production_types = _production_types(state)
    dependencies = _package_dependencies(state, production_types)
    components = _package_components(state.package_names, dependencies)
    allowed = (
        {"com.zsumz.logyard.api", "com.zsumz.logyard.api.diagnostics", "com.zsumz.logyard.api.event", "com.zsumz.logyard.api.ingress"},
        {"com.zsumz.logyard.core.runtime", "com.zsumz.logyard.core.runtime.management", "com.zsumz.logyard.core.runtime.publication", "com.zsumz.logyard.core.runtime.retirement"},
    )
    for component in components:
        if component not in allowed:
            state.add_error("production package dependency cycle: " + " <-> ".join(sorted(component)))


def _production_types(state: CheckState) -> dict[str, str]:
    types: dict[str, str] = {}
    for source, _ in state.main_files:
        if source.name == "package-info.java":
            continue
        package_match = PACKAGE.search(source.read_text(encoding="utf-8"))
        if package_match is not None:
            package = package_match.group(1)
            types[f"{package}.{source.stem}"] = package
    return types


def _package_dependencies(state: CheckState, production_types: dict[str, str]) -> dict[str, set[str]]:
    dependencies: dict[str, set[str]] = {}
    for source, _ in state.main_files:
        text = source.read_text(encoding="utf-8")
        package_match = PACKAGE.search(text)
        if package_match is None:
            continue
        source_package = package_match.group(1)
        for imported in IMPORT.findall(text):
            candidate = imported.removesuffix(".*")
            target = None
            while "." in candidate and target is None:
                target = production_types.get(candidate)
                candidate = candidate.rsplit(".", 1)[0]
            if target is not None and target != source_package:
                dependencies.setdefault(source_package, set()).add(target)
    return dependencies


def _package_components(packages: set[str], dependencies: dict[str, set[str]]) -> list[set[str]]:
    index = 0
    indices: dict[str, int] = {}
    low_links: dict[str, int] = {}
    stack: list[str] = []
    on_stack: set[str] = set()
    components: list[set[str]] = []

    def visit(package: str) -> None:
        nonlocal index
        indices[package] = index
        low_links[package] = index
        index += 1
        stack.append(package)
        on_stack.add(package)
        for dependency in dependencies.get(package, set()):
            if dependency not in indices:
                visit(dependency)
                low_links[package] = min(low_links[package], low_links[dependency])
            elif dependency in on_stack:
                low_links[package] = min(low_links[package], indices[dependency])
        if low_links[package] != indices[package]:
            return
        component: set[str] = set()
        while True:
            member = stack.pop()
            on_stack.remove(member)
            component.add(member)
            if member == package:
                break
        if len(component) > 1:
            components.append(component)

    for package in packages:
        if package not in indices:
            visit(package)
    return components


def _check_test_sources(root: Path, test_roots: list[Path], state: CheckState) -> None:
    for source_root in test_roots:
        for source in sorted(source_root.rglob("*.java")):
            state.java_sources.add(source.relative_to(root).as_posix())
            state.test_file_count += 1
            text = source.read_text(encoding="utf-8")
            state.test_method_count += len(re.findall(r"(?m)^\s*@Test\s*$", text))
            expected_package = ".".join(source.relative_to(source_root).parent.parts)
            package_match = PACKAGE.search(text)
            if package_match is None:
                state.add_error(f"{source.relative_to(root)}: missing test package declaration")
            elif package_match.group(1) != expected_package:
                state.add_error(f"{source.relative_to(root)}: test package {package_match.group(1)!r} does not match source path {expected_package!r}")
            for imported in IMPORT.findall(text):
                if imported.endswith(".*"):
                    state.add_error(f"{source.relative_to(root)}: wildcard imports are forbidden")


def _check_example_sources(root: Path, state: CheckState) -> None:
    examples = root / "examples"
    for source in sorted(examples.glob("**/*.java")):
        if source.is_file() and "target" not in source.relative_to(examples).parts:
            state.java_sources.add(source.relative_to(root).as_posix())


def _check_sizes(root: Path, baseline: dict[str, int], state: CheckState) -> None:
    production_sources = {source.relative_to(root).as_posix() for source, _ in state.main_files}
    baseline_path = root / "scripts/java-size-baseline.tsv"
    for source in sorted(baseline.keys() - production_sources):
        state.add_error(f"{baseline_path.relative_to(root)}: unknown production source {source}")
    for source, limit in sorted(baseline.items()):
        line_count = _line_count(root / source)
        if line_count <= MAX_NEW_PRODUCTION_LINES:
            state.add_error(f"{baseline_path.relative_to(root)}: {source} is {line_count} lines and no longer needs a grandfathered size exception")
    for source, _ in state.main_files:
        relative = source.relative_to(root).as_posix()
        line_count = _line_count(source)
        baseline_limit = baseline.get(relative)
        if baseline_limit is not None and line_count > baseline_limit:
            state.add_error(f"{relative}: {line_count} lines exceeds its grandfathered {baseline_limit}-line ceiling; modified large production files may shrink but never grow")
        elif baseline_limit is None and line_count > MAX_NEW_PRODUCTION_LINES:
            state.add_error(f"{relative}: {line_count} lines exceeds the {MAX_NEW_PRODUCTION_LINES}-line production limit; extract a cohesive collaborator")
    for relative in sorted(state.java_sources - production_sources):
        line_count = _line_count(root / relative)
        if line_count > MAX_TEST_OR_EXAMPLE_LINES:
            state.add_error(f"{relative}: {line_count} lines exceeds the {MAX_TEST_OR_EXAMPLE_LINES}-line test/example limit; keep each file focused on one behavior family")


def _line_count(source: Path) -> int:
    return len(source.read_text(encoding="utf-8").splitlines())
