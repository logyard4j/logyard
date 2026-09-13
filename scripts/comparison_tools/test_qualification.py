from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from .qualification import candidate, require_candidate


class CandidateQualificationTest(unittest.TestCase):
    def test_rejects_changed_sources_and_a_different_clean_commit(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)

            def git(*args: str) -> None:
                subprocess.run(["git", "-c", "commit.gpgsign=false", "-c", "user.name=Test",
                                "-c", "user.email=test@example.invalid", *args], cwd=root, check=True,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

            git("init")
            source = root / "source.txt"
            source.write_text("first\n")
            git("add", ".")
            git("commit", "-m", "test: create candidate")
            initial = candidate(root)
            require_candidate(root, initial)
            source.write_text("second\n")
            with self.assertRaisesRegex(AssertionError, "clean committed"):
                require_candidate(root, initial)
            git("add", ".")
            with self.assertRaisesRegex(AssertionError, "clean committed"):
                require_candidate(root, initial)
            git("commit", "-m", "test: change candidate")
            with self.assertRaisesRegex(AssertionError, "candidate changed"):
                require_candidate(root, initial)
            (root / "untracked.txt").touch()
            with self.assertRaisesRegex(AssertionError, "clean committed"):
                candidate(root)
