from __future__ import annotations

import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path

from verify_tools.legal_notices import verify_archive


class LegalNoticesTest(unittest.TestCase):
    def test_rejects_missing_modified_or_duplicate_archive_notices(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "LICENSE").write_text("license\n")
            (root / "NOTICE").write_text("notice\n")
            for defect in ("none", "missing", "modified", "duplicate"):
                with self.subTest(defect=defect):
                    archive = root / "artifact.jar"
                    with zipfile.ZipFile(archive, "w") as jar:
                        jar.writestr("META-INF/LICENSE", "license\n")
                        if defect != "missing":
                            jar.writestr("META-INF/NOTICE", "changed\n" if defect == "modified" else "notice\n")
                        if defect == "duplicate":
                            with warnings.catch_warnings():
                                warnings.simplefilter("ignore", UserWarning)
                                jar.writestr("META-INF/NOTICE", "notice\n")
                    if defect == "none":
                        verify_archive(root, archive)
                    else:
                        with self.assertRaisesRegex(ValueError, "noncanonical"):
                            verify_archive(root, archive)
