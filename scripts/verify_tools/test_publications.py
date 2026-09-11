from pathlib import Path
import subprocess
import tempfile
import tomllib
import unittest
from unittest.mock import patch

from verify_tools.publications import prepare, require_unsigned_readiness, unsigned_manifest


class PublicationPlanTest(unittest.TestCase):
    def test_prepare_creates_target_in_a_cold_workspace(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "zolt.toml").write_text("[workspace.members]\ninclude = []\n")
            (root / "zolt.lock").write_text("")
            result = subprocess.CompletedProcess(("zolt", "publish"), 0, stdout="")

            with patch("verify_tools.publications.subprocess.run", return_value=result):
                prepare(root, "zolt")

            self.assertTrue((root / "target").is_dir())

    def test_unsigned_readiness_rejects_missing_metadata_and_missing_members(self) -> None:
        coordinate = "example:library:1.0"
        expected = f"Blockers:\n- {coordinate}: gpg signatures — configure signing\n"
        require_unsigned_readiness(expected, [coordinate])
        for output in ("", expected + f"- {coordinate}: project url — missing\n"):
            with self.subTest(output=output), self.assertRaises(AssertionError):
                require_unsigned_readiness(output, [coordinate])

    def test_unsigned_plan_preserves_all_build_and_publication_metadata(self) -> None:
        root = Path(__file__).resolve().parents[2]
        for manifest in (root / "modules").glob("*/zolt.toml"):
            original = tomllib.loads(manifest.read_text())
            staged = tomllib.loads(unsigned_manifest(manifest.read_text()))
            with self.subTest(member=manifest.parent.name):
                original_publish = original.pop("publish")
                staged_publish = staged.pop("publish")
                self.assertEqual(original, staged)
                self.assertNotIn("signing", staged_publish)
                self.assertNotIn("central", staged_publish)
                self.assertEqual("local-plan", staged_publish["release"])
                self.assertEqual("local-plan", staged_publish["snapshot"])
                self.assertEqual("http://127.0.0.1:9", staged_publish["repositories"]["local-plan"]["url"])
