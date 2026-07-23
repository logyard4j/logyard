from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree

from .model import discover_publications, jar_publications
from .pom import POM_NAMESPACE, generate_pom


NAMESPACE = {"m": POM_NAMESPACE}
PROJECT_METADATA = """
[package.metadata]
name = "Test Artifact"
description = "Publication fixture."
url = "https://example.test/project"
license = "Apache-2.0"
developers = ["zsumz <shawn@zsumz.com>"]
scm = "https://example.test/project"
issues = "https://example.test/project/issues"
"""


class RepositoryPublicationTest(unittest.TestCase):
    def test_repository_publication_surface_is_discovered_from_manifests(self) -> None:
        root = Path(__file__).resolve().parents[2]
        publications = discover_publications(root)
        expected_jar_manifests = [
            path
            for path in (root / "modules").glob("*/zolt.toml")
            if "[publish]" in path.read_text(encoding="utf-8")
        ]
        standalone_manifests = list((root / "modules").glob("*/publication.toml"))

        self.assertEqual(len(expected_jar_manifests) + len(standalone_manifests), len(publications))
        self.assertEqual(len(expected_jar_manifests), len(jar_publications(publications)))
        bom = next(publication for publication in publications if publication.artifact_id == "logyard-bom")
        self.assertEqual("pom", bom.packaging)
        self.assertEqual(("pom",), bom.artifacts)
        self.assertEqual(
            {publication.coordinate for publication in jar_publications(publications)},
            {dependency.coordinate for dependency in bom.managed_dependencies},
        )


class PublicationManifestContractTest(unittest.TestCase):
    def test_pom_supports_scopes_optional_dependencies_exclusions_and_bom_management(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module = root / "modules" / "fixture"
            module.mkdir(parents=True)
            (module / "zolt.toml").write_text(
                """
[project]
name = "fixture"
version = "1.2.3"
group = "com.example"
java = "21"

[api.dependencies]
"com.example:api" = "1.0.0"

[dependencies]
"com.example:compile" = { version = "2.0.0", optional = true, exclusions = [{ group = "legacy", artifact = "logger" }] }

[runtime.dependencies]
"com.example:runtime" = "3.0.0"

[provided.dependencies]
"com.example:provided" = "4.0.0"

[test.dependencies]
"com.example:ignored-test" = "5.0.0"
"com.example:published-test" = { version = "6.0.0", publishOnly = true }

[package]
mode = "thin"
sources = true
javadoc = true
"""
                + PROJECT_METADATA
                + """

[package.manifest]
"Automatic-Module-Name" = "com.example.fixture"

[publish]
artifacts = ["main", "sources", "javadoc"]
""",
                encoding="utf-8",
            )
            bom = root / "modules" / "fixture-bom"
            bom.mkdir()
            (bom / "publication.toml").write_text(
                """
[project]
name = "fixture-bom"
version = "1.2.3"
group = "com.example"

[package]
"""
                + PROJECT_METADATA
                + """

[publication]
packaging = "pom"
artifacts = ["pom"]

[dependencyManagement]
"com.example:fixture" = { workspace = "modules/fixture" }
""",
                encoding="utf-8",
            )

            publications = discover_publications(root)
            fixture = next(publication for publication in publications if publication.artifact_id == "fixture")
            xml = ElementTree.fromstring(generate_pom(fixture))
            dependencies = {
                dependency.findtext("m:artifactId", namespaces=NAMESPACE): dependency
                for dependency in xml.findall("m:dependencies/m:dependency", namespaces=NAMESPACE)
            }

            self.assertEqual({"api", "compile", "runtime", "provided", "published-test"}, set(dependencies))
            self.assertIsNone(dependencies["api"].findtext("m:scope", namespaces=NAMESPACE))
            self.assertEqual("runtime", dependencies["runtime"].findtext("m:scope", namespaces=NAMESPACE))
            self.assertEqual("provided", dependencies["provided"].findtext("m:scope", namespaces=NAMESPACE))
            self.assertEqual("test", dependencies["published-test"].findtext("m:scope", namespaces=NAMESPACE))
            self.assertEqual("true", dependencies["compile"].findtext("m:optional", namespaces=NAMESPACE))
            self.assertEqual("legacy", dependencies["compile"].findtext("m:exclusions/m:exclusion/m:groupId", namespaces=NAMESPACE))
            self.assertEqual("logger", dependencies["compile"].findtext("m:exclusions/m:exclusion/m:artifactId", namespaces=NAMESPACE))

            bom_publication = next(publication for publication in publications if publication.artifact_id == "fixture-bom")
            bom_xml = ElementTree.fromstring(generate_pom(bom_publication))
            self.assertEqual("pom", bom_xml.findtext("m:packaging", namespaces=NAMESPACE))
            self.assertEqual("fixture", bom_xml.findtext("m:dependencyManagement/m:dependencies/m:dependency/m:artifactId", namespaces=NAMESPACE))
            self.assertEqual("1.2.3", bom_xml.findtext("m:dependencyManagement/m:dependencies/m:dependency/m:version", namespaces=NAMESPACE))


if __name__ == "__main__":
    unittest.main()
