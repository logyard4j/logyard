from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree

from .model import discover_publications, jar_publications, zolt_jar_publications, zolt_publications, zolt_test_members
from .path_cli import file_uri
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
    def test_file_uri_is_absolute_and_maven_compatible(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            uri = file_uri(Path(directory) / "repository")

        self.assertTrue(uri.startswith("file:"))
        self.assertNotIn("\\", uri)
        self.assertTrue(uri.endswith("/repository"))

    def test_repository_publication_surface_is_discovered_from_manifests(self) -> None:
        root = Path(__file__).resolve().parents[2]
        publications = discover_publications(root)
        expected_zolt_manifests = [
            path
            for path in (root / "modules").glob("*/zolt.toml")
            if "[publish" in path.read_text(encoding="utf-8")
        ]
        standalone_manifests = list((root / "modules").glob("*/publication.toml"))
        extension_manifests = list(root.glob("extensions/**/publication.toml"))

        self.assertEqual(
            len(expected_zolt_manifests) + len(standalone_manifests) + len(extension_manifests),
            len(publications),
        )
        self.assertEqual(len(expected_zolt_manifests), len(zolt_publications(publications)))
        self.assertEqual(len(expected_zolt_manifests) - 1, len(zolt_jar_publications(publications)))
        self.assertGreaterEqual(len(jar_publications(publications)), len(expected_zolt_manifests) - 1)
        bom = next(publication for publication in publications if publication.artifact_id == "logyard-bom")
        self.assertEqual("pom", bom.packaging)
        self.assertEqual(("pom",), bom.artifacts)
        self.assertEqual(
            {
                publication.coordinate
                for publication in publications
                if publication.artifact_id != "logyard-bom"
            },
            {dependency.coordinate for dependency in bom.managed_dependencies},
        )
        self.assertNotIn("modules/logyard-bom", zolt_test_members(root))
        self.assertEqual(13, len(zolt_test_members(root)))


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
artifacts = ["main"]
""",
                encoding="utf-8",
            )
            bom = root / "modules" / "fixture-bom"
            bom.mkdir()
            (bom / "zolt.toml").write_text(
                """
[project]
name = "fixture-bom"
version = "1.2.3"
group = "com.example"

[bom]
members = ["modules/fixture"]

[bom.versions]
"org.example:fixture-tool" = { version = "7.0.0", classifier = "linux-x86_64", type = "zip" }

[bom.imports]
"org.example:fixture-platform" = "8.0.0"

[package]
"""
                + PROJECT_METADATA
                + """

[publish.central]
tokenEnv = "ZOLT_CENTRAL_TOKEN"
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
            managed_dependencies = {
                dependency.findtext("m:artifactId", namespaces=NAMESPACE): dependency
                for dependency in bom_xml.findall("m:dependencyManagement/m:dependencies/m:dependency", namespaces=NAMESPACE)
            }
            self.assertEqual({"fixture", "fixture-platform", "fixture-tool"}, set(managed_dependencies))
            self.assertEqual("1.2.3", managed_dependencies["fixture"].findtext("m:version", namespaces=NAMESPACE))
            self.assertEqual("pom", managed_dependencies["fixture-platform"].findtext("m:type", namespaces=NAMESPACE))
            self.assertEqual("import", managed_dependencies["fixture-platform"].findtext("m:scope", namespaces=NAMESPACE))
            self.assertEqual("zip", managed_dependencies["fixture-tool"].findtext("m:type", namespaces=NAMESPACE))
            self.assertEqual("linux-x86_64", managed_dependencies["fixture-tool"].findtext("m:classifier", namespaces=NAMESPACE))


if __name__ == "__main__":
    unittest.main()
