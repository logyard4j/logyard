from __future__ import annotations

import re
import tomllib
from pathlib import Path

from .constants import ARTIFACT_CONTRACTS, EXPECTED_DEPENDENCIES, PACKAGE, SERVICE_CONTRACTS
from .state import CheckState


def check_manifest_contracts(root: Path, state: CheckState) -> None:
    versions = _check_project_manifests(root, state)
    _check_workspace_version(root, versions, state)
    _check_artifact_contracts(root, state)
    _check_dependency_versions(root, state)
    _check_service_contracts(root, state)


def _check_project_manifests(root: Path, state: CheckState) -> dict[str, str]:
    versions: dict[str, str] = {}
    for project_path, expected_dependencies in EXPECTED_DEPENDENCIES.items():
        manifest = root / project_path / "zolt.toml"
        if not manifest.is_file():
            state.add_error(f"{manifest.relative_to(root)}: missing project manifest")
            continue
        text = manifest.read_text(encoding="utf-8")
        block_match = re.search(r"(?ms)^\[project\]\s*(.*?)(?=^\[|\Z)", text)
        if block_match is None:
            state.add_error(f"{manifest.relative_to(root)}: missing [project] block")
            continue
        _check_project_block(root, manifest, block_match.group(1), versions, state)
        dependency_match = re.search(r"(?ms)^\[dependencies\]\s*(.*?)(?=^\[|\Z)", text)
        dependency_block = dependency_match.group(1) if dependency_match else ""
        actual = set(re.findall(r'(?m)^"([^"]+)"\s*=', dependency_block))
        if actual != expected_dependencies:
            state.add_error(f"{manifest.relative_to(root)}: dependencies {sorted(actual)} do not match {sorted(expected_dependencies)}")
    return versions


def _check_project_block(root: Path, manifest: Path, block: str, versions: dict[str, str], state: CheckState) -> None:
    fields = {
        name: re.search(rf'(?m)^{name}\s*=\s*"([^"]+)"', block)
        for name in ("name", "version", "group", "java")
    }
    expected_name = manifest.parent.name
    if fields["name"] is None or fields["name"].group(1) != expected_name:
        state.add_error(f"{manifest.relative_to(root)}: project name must be {expected_name!r}")
    if fields["version"] is None:
        state.add_error(f"{manifest.relative_to(root)}: missing version")
    else:
        versions[manifest.parent.relative_to(root).as_posix()] = fields["version"].group(1)
    if fields["group"] is None or fields["group"].group(1) != "com.logyard4j":
        state.add_error(f"{manifest.relative_to(root)}: group must be com.logyard4j")
    if fields["java"] is None or fields["java"].group(1) != "21":
        state.add_error(f"{manifest.relative_to(root)}: Java baseline must be 21")


def _check_workspace_version(root: Path, manifest_versions: dict[str, str], state: CheckState) -> None:
    versions = set(manifest_versions.values())
    if len(versions) != 1:
        state.add_error("workspace versions must move together: " + ", ".join(f"{path}={version}" for path, version in sorted(manifest_versions.items())))
        return
    version = next(iter(versions))
    version_source = root / "modules/logyard-api/src/main/java/com/logyard4j/api/LogyardVersion.java"
    if f'public static final String CURRENT = "{version}";' not in version_source.read_text():
        state.add_error("LogyardVersion does not match the workspace version")
    if f"LOGYARD_SERVICE_VERSION:-{version}" not in (root / "logyard.toml").read_text():
        state.add_error("logyard.toml does not expose the workspace version")


def _check_artifact_contracts(root: Path, state: CheckState) -> None:
    required_fragments = (
        'mode = "thin"', "sources = true", "javadoc = true", 'license = "Apache-2.0"',
        'licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0.txt"', 'developers = ["zsumz <shawn@zsumz.com>"]',
        'url = "https://logyard4j.com"', 'scm = "https://github.com/zsumz/logyard"',
        'scmConnection = "scm:git:https://github.com/zsumz/logyard.git"', 'scmDeveloperConnection = "scm:git:ssh://git@github.com/zsumz/logyard.git"',
        'issues = "https://github.com/zsumz/logyard/issues"', 'artifacts = ["main"]', '[publish.signing]',
        'keyId = "EC8E4D26598A0373"', '[publish.central]', 'tokenEnv = "ZOLT_CENTRAL_TOKEN"', 'publishingType = "user-managed"',
    )
    for project_path, (expected_module, supported_packages) in ARTIFACT_CONTRACTS.items():
        descriptor = root / project_path / "src/main/java/module-info.java"
        if descriptor.exists():
            state.add_error(f"{descriptor.relative_to(root)}: JPMS descriptors are deferred until Zolt packages modular Javadocs; use the stable Automatic-Module-Name and InternalApi boundary")
        manifest = root / project_path / "zolt.toml"
        text = manifest.read_text(encoding="utf-8")
        automatic_name = re.search(r'(?ms)^\[package\.manifest\]\s*.*?^"Automatic-Module-Name"\s*=\s*"([^"]+)"', text)
        if automatic_name is None or automatic_name.group(1) != expected_module:
            state.add_error(f"{manifest.relative_to(root)}: Automatic-Module-Name must be {expected_module!r}")
        for fragment in required_fragments:
            if fragment not in text:
                state.add_error(f"{manifest.relative_to(root)}: missing publication contract {fragment!r}")
        _check_internal_packages(root, project_path, supported_packages, state)


def _check_internal_packages(root: Path, project_path: str, supported_packages: set[str], state: CheckState) -> None:
    source_root = root / project_path / "src/main/java"
    for package_info in sorted(source_root.rglob("package-info.java")):
        package_match = PACKAGE.search(package_info.read_text(encoding="utf-8"))
        if package_match is None or package_match.group(1) in supported_packages:
            continue
        if "@com.logyard4j.api.annotation.InternalApi" not in package_info.read_text(encoding="utf-8"):
            state.add_error(f"{package_info.relative_to(root)}: unsupported packages must declare @InternalApi")


def _check_dependency_versions(root: Path, state: CheckState) -> None:
    with (root / "framework-versions.toml").open("rb") as source:
        frameworks = tomllib.load(source)["frameworks"]
    expected = {
        "org.junit.platform:junit-platform-console-standalone": "1.11.4",
        "org.slf4j:slf4j-api": "2.0.18",
        "org.openjdk.jmh:jmh-core": frameworks["jmh"],
        "org.openjdk.jmh:jmh-generator-annprocess": frameworks["jmh"],
    }
    manifests = sorted(path for group in ("modules", "tests", "benchmarks") for path in (root / group).glob("*/zolt.toml"))
    for manifest in manifests:
        text = manifest.read_text(encoding="utf-8")
        for coordinate, version in expected.items():
            match = re.search(rf'(?m)^"{re.escape(coordinate)}"\s*=\s*"([^"]+)"', text)
            if match is not None and match.group(1) != version:
                state.add_error(f"{manifest.relative_to(root)}: {coordinate} must use {version}")


def _check_service_contracts(root: Path, state: CheckState) -> None:
    for relative, expected in SERVICE_CONTRACTS.items():
        descriptor = root / relative
        if not descriptor.is_file():
            state.add_error(f"{relative}: missing ServiceLoader descriptor")
            continue
        actual = [line.strip() for line in descriptor.read_text(encoding="utf-8").splitlines() if line.strip() and not line.lstrip().startswith("#")]
        if actual != expected:
            state.add_error(f"{relative}: expected {expected}, found {actual}")
