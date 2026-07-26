from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from .compatibility_baseline import SURFACES, fetch_baseline, read_baseline


class CompatibilityBaselineTest(unittest.TestCase):
    def test_absent_baseline_is_explicit_and_fetchingIsRejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / "baseline.toml"
            manifest.write_text(
                """
schema = 1
version = "none"
first_release = "0.1.0-rc.1"
reason = "first public release"
repository = "https://repo.example.test"
""",
                encoding="utf-8",
            )

            baseline = read_baseline(manifest)

            self.assertTrue(baseline.absent)
            with self.assertRaisesRegex(ValueError, "cannot fetch"):
                fetch_baseline(baseline, Path(directory) / "target")

    def test_fetchesEveryPinnedArtifactAndVerifiesItsChecksum(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository = root / "repository"
            version = "1.2.3"
            artifact_documents: list[str] = []
            for surface in SURFACES:
                artifact_id = f"logyard-{surface}"
                content = f"{surface}-jar".encode()
                path = repository / "com" / "zsumz" / "logyard" / artifact_id / version / f"{artifact_id}-{version}.jar"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
                artifact_documents.append(
                    f'{surface} = {{ artifact = "{artifact_id}", sha256 = "{hashlib.sha256(content).hexdigest()}" }}'
                )
            manifest = root / "baseline.toml"
            manifest.write_text(
                "\n".join(
                    (
                        "schema = 1",
                        f'version = "{version}"',
                        f'repository = "{repository.as_uri()}"',
                        "[artifacts]",
                        *artifact_documents,
                    )
                ),
                encoding="utf-8",
            )

            fetched = fetch_baseline(read_baseline(manifest), root / "target")

            self.assertEqual(len(SURFACES), len(fetched))
            self.assertEqual(
                [f"{surface}-jar" for surface in SURFACES],
                [path.read_text(encoding="utf-8") for path in fetched],
            )

    def test_rejectsAChangedArtifactBeforePublishingItToTheCache(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository = root / "repository"
            artifact_lines: list[str] = []
            for surface in SURFACES:
                artifact_id = f"logyard-{surface}"
                path = repository / "com" / "zsumz" / "logyard" / artifact_id / "1.0.0" / f"{artifact_id}-1.0.0.jar"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"changed")
                artifact_lines.append(f'{surface} = {{ artifact = "{artifact_id}", sha256 = "{"0" * 64}" }}')
            manifest = root / "baseline.toml"
            manifest.write_text(
                "\n".join(
                    (
                        "schema = 1",
                        'version = "1.0.0"',
                        f'repository = "{repository.as_uri()}"',
                        "[artifacts]",
                        *artifact_lines,
                    )
                ),
                encoding="utf-8",
            )
            target = root / "target"

            with self.assertRaisesRegex(ValueError, "checksum mismatch"):
                fetch_baseline(read_baseline(manifest), target)

            self.assertEqual([], list(target.iterdir()))


if __name__ == "__main__":
    unittest.main()
