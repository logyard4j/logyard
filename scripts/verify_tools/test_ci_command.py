from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from .ci_command import failure_annotation


class CiCommandTest(unittest.TestCase):
    def invoke(self, directory: str, program: str) -> tuple[subprocess.CompletedProcess[str], bytes]:
        log = Path(directory) / "command.log"
        result = subprocess.run([sys.executable, "-m", "verify_tools.ci_command", "--log", str(log),
                                 "--title", "Fixture failed", "--", sys.executable, "-c", program],
                                capture_output=True, text=True, timeout=10)
        return result, log.read_bytes()

    def test_preserves_output_and_success_without_an_error_annotation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result, log = self.invoke(directory, "import sys; print('out', flush=True); print('err', file=sys.stderr)")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(f"out{os.linesep}err{os.linesep}".encode(), log)
            self.assertEqual("out\nerr\n", result.stdout)

    def test_preserves_failure_status_and_annotates_output_without_a_final_newline(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result, log = self.invoke(directory, "import sys; sys.stdout.write('failure 100%'); sys.exit(7)")
            self.assertEqual(7, result.returncode)
            self.assertEqual(b"failure 100%", log)
            self.assertIn("\n::error title=Fixture failed::failure 100%25\n", result.stdout)

    def test_bounds_the_failure_tail_and_escapes_workflow_commands(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "command.log"
            log.write_bytes(b"discard me" * 1000 + b"\r\n::warning::100%\n")
            annotation = failure_annotation(log, "Bad,title:with\nnewline")
            self.assertNotIn("discard me" * 500, annotation)
            self.assertNotIn("\n", annotation)
            self.assertNotIn("\r", annotation)
            self.assertTrue(annotation.startswith("::error title=Bad%2Ctitle%3Awith%0Anewline::"))
            self.assertTrue(annotation.endswith("%0D%0A::warning::100%25%0A"))

    @unittest.skipUnless(os.name == "posix", "signal exit statuses are a POSIX convention")
    def test_preserves_a_signal_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            result, _ = self.invoke(directory, "import os, signal; os.kill(os.getpid(), signal.SIGTERM)")
            self.assertEqual(143, result.returncode)
            self.assertIn("Command failed without output.", result.stdout)

    def test_reports_a_command_that_cannot_start(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "command.log"
            missing = Path(directory) / "missing-executable"
            result = subprocess.run([sys.executable, "-m", "verify_tools.ci_command", "--log", str(log),
                                     "--title", "Missing command", "--", str(missing)],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(127, result.returncode)
            self.assertIn("::error title=Missing command::", result.stdout)
            self.assertIn("missing-executable", log.read_text())
