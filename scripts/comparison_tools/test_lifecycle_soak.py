import gzip
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from .lifecycle_soak import archive


class LifecycleArchiveTest(unittest.TestCase):
    def test_retains_exact_output_and_leaves_other_evidence_untouched(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source = directory / "first.jsonl"
            content = '{"message":"captured λ"}\n'.encode()
            source.write_bytes(content)
            evidence = directory / "result.json"
            evidence.write_text("{}")

            archive(directory)

            self.assertFalse(source.exists())
            with gzip.open(directory / "first.jsonl.gz", "rb") as restored:
                self.assertEqual(content, restored.read())
            self.assertEqual("{}", evidence.read_text())

    def test_preserves_raw_output_when_compressed_evidence_fails_verification(self):
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            source = directory / "first.jsonl"
            source.write_bytes(b'{"message":"original"}\n')
            real_open = gzip.open

            def corrupt_read(path, mode, **kwargs):
                return io.BytesIO(b"corrupted") if mode == "rb" else real_open(path, mode, **kwargs)

            with patch("comparison_tools.lifecycle_soak.gzip.open", side_effect=corrupt_read):
                with self.assertRaisesRegex(AssertionError, "compressed evidence differs"):
                    archive(directory)

            self.assertEqual(b'{"message":"original"}\n', source.read_bytes())
