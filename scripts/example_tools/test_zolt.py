from pathlib import Path
import tempfile
import tomllib
import unittest
from unittest.mock import Mock, patch

from example_tools.scenarios import spring_boot_example
from example_tools.zolt import ZoltExampleRunner


class ZoltExampleTest(unittest.TestCase):
    def test_new_run_discards_logyard_indexes_but_preserves_shared_blobs_and_other_groups(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            stale = (
                "com/logyard4j/logyard-core/1.0/core.jar",
                "indexes/" + "a" * 64 + "/com/logyard4j/logyard-core/1.0/core.jar.idx",
                "indexes/" + "b" * 64 + "/com/logyard4j/logyard-api/1.0/api.pom.idx",
            )
            retained = (
                "blobs/v2/sha256/" + "c" * 64 + "/core.jar",
                "indexes/" + "a" * 64 + "/org/slf4j/slf4j-api/2.0/api.jar.idx",
            )
            for name in stale + retained:
                path = target / "cache" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"cached")
            with patch("example_tools.zolt.ThreadingHTTPServer"), patch("example_tools.zolt.threading.Thread"):
                with ZoltExampleRunner(target / "bundle", "1.0", target):
                    for name in stale:
                        self.assertFalse((target / "cache" / name).exists(), name)
                    for name in retained:
                        self.assertEqual(b"cached", (target / "cache" / name).read_bytes())

    def test_variant_is_clean_and_preserves_the_original_example(self) -> None:
        root = Path(__file__).resolve().parents[2]
        source = root / "examples/spring-boot/zolt.toml"
        original = source.read_bytes()
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            runner = ZoltExampleRunner(target / "bundle", "9.8.7", target)
            runner._server = Mock(server_port=12345)
            example = spring_boot_example(root, "test", "3.5.16", "spring-boot-starter-webflux",
                                          actuator=False, omit_config=True).zolt
            first = runner.stage(example)
            (first.project_directory / "zolt.lock").write_text("stale lock")
            (first.project_directory / "target").mkdir()
            (first.project_directory / "target/stale.jar").touch()
            staged = runner.stage(example).project_directory
            config = tomllib.loads((staged / "zolt.toml").read_text())
            self.assertEqual("9.8.7", config["platforms"]["com.logyard4j:logyard-bom"])
            self.assertEqual("3.5.16", config["platforms"]["org.springframework.boot:spring-boot-dependencies"])
            self.assertIn("org.springframework.boot:spring-boot-starter-webflux", config["dependencies"])
            self.assertNotIn("org.springframework.boot:spring-boot-starter-actuator", config["dependencies"])
            self.assertFalse((staged / "src/main/resources/logyard.toml").exists())
            self.assertFalse((staged / "target").exists())
            self.assertFalse((staged / "zolt.lock").exists())
        self.assertEqual(original, source.read_bytes())
        self.assertTrue((source.parent / "src/main/resources/logyard.toml").is_file())

    def test_rejects_a_cached_artifact_that_differs_from_the_release_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary)
            artifact = "com/logyard4j/logyard-api/1.0/logyard-api-1.0.jar"
            for location, content in (("bundle", b"current"), ("cache", b"stale")):
                file = target / location / artifact
                file.parent.mkdir(parents=True)
                file.write_bytes(content)
            (target / "zolt.lock").write_text(f'''[[package]]
id = "com.logyard4j:logyard-api"
version = "1.0"
source = "repository"
jar = "{artifact}"
''')
            runner = ZoltExampleRunner(target / "bundle", "1.0", target)
            with self.assertRaisesRegex(AssertionError, "different artifact"):
                runner.verify_artifacts(target)
