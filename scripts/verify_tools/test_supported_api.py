from pathlib import Path
import tempfile
import unittest

from verify_tools.supported_api import load


ROOT = Path(__file__).resolve().parents[2]


class SupportedApiTest(unittest.TestCase):
    def test_application_integrations_are_explicit_and_documented(self) -> None:
        annotation, surfaces = load(ROOT)
        by_name = {surface.name: surface for surface in surfaces}
        self.assertEqual("com.logyard4j.api.annotation.InternalApi", annotation)
        self.assertEqual({"api", "runtime", "jul", "spring", "quarkus", "opentelemetry", "test"}, set(by_name))
        otel = by_name["opentelemetry"]
        self.assertEqual(("com.logyard4j.opentelemetry.LogyardOpenTelemetry",), otel.types)
        self.assertEqual((), otel.packages)
        for name in ("LogyardTestKit", "RecordedEvents", "EventExpectation"):
            self.assertIn(f"com.logyard4j.test.{name}", by_name["test"].types)
        self.assertNotIn("OtelOutputProvider.java", {path.name for path in otel.sources(ROOT)})
        self.assertTrue(otel.strict_javadoc)
        self.assertTrue(by_name["test"].strict_javadoc)

    def test_rejects_empty_or_unsupported_manifests(self) -> None:
        for text in ('schema = 2', 'schema = 1\ninternal_annotation = "test.Internal"'):
            with self.subTest(text=text), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "supported-api.toml").write_text(text)
                with self.assertRaises(ValueError):
                    load(root)

    def test_package_selection_does_not_document_subpackages(self) -> None:
        _, surfaces = load(ROOT)
        api = next(surface for surface in surfaces if surface.name == "api")
        self.assertNotIn("com.logyard4j.api.lifecycle", api.supported_packages)
        self.assertNotIn("CloseLifecycle.java", {path.name for path in api.sources(ROOT)})
