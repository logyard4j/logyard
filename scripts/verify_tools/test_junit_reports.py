from __future__ import annotations

import tempfile
import unittest
import os
import shutil
import subprocess
from pathlib import Path

from verify_tools.junit_reports import verify


class JunitReportsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)

    def write(self, text: str) -> None:
        (self.directory / "TEST-junit-jupiter.xml").write_text(text, encoding="utf-8")

    def test_rejects_missing_reports_after_discovery_failure(self) -> None:
        with self.assertRaisesRegex(ValueError, "no fresh JUnit reports"):
            verify(self.directory)

    def test_rejects_empty_execution(self) -> None:
        self.write('<testsuite tests="0" failures="0" errors="0"/>')
        with self.assertRaisesRegex(ValueError, "executed no tests"):
            verify(self.directory)

    def test_requires_every_selected_class(self) -> None:
        self.write('<testsuite><testcase classname="example.First" name="works"/></testsuite>')
        with self.assertRaisesRegex(ValueError, "example.Missing"):
            verify(self.directory, ("example.First", "example.Missing"))

    def test_rejects_failed_aborted_or_skipped_tests(self) -> None:
        for tag in ("failure", "error", "skipped"):
            with self.subTest(tag=tag):
                self.write(f'<testsuite><testcase name="case"><{tag}/></testcase></testsuite>')
                with self.assertRaisesRegex(ValueError, "did not pass"):
                    verify(self.directory)

    def test_rejects_suite_errors_without_testcase_errors(self) -> None:
        self.write('<testsuite errors="1"><testcase name="otherwise passed"/></testsuite>')
        with self.assertRaisesRegex(ValueError, "suite failed"):
            verify(self.directory)

    def test_accepts_completed_tests_for_all_selected_classes(self) -> None:
        self.write('<testsuite tests="2" failures="0" errors="0">'
                   '<testcase classname="example.First" name="one"/>'
                   '<testcase classname="example.Second" name="two"/></testsuite>')
        self.assertEqual(2, verify(self.directory, ("example.First", "example.Second")))

    def test_gate_rejects_a_success_exit_without_fresh_test_execution(self) -> None:
        root = Path(__file__).resolve().parents[2]
        scripts = self.directory / "scripts"
        tools = scripts / "verify_tools"
        tools.mkdir(parents=True)
        for name in ("__init__.py", "junit_reports.py"):
            shutil.copyfile(root / "scripts/verify_tools" / name, tools / name)
        gate = scripts / "failure-injection-verify"
        shutil.copyfile(root / "scripts/failure-injection-verify", gate)
        runner = self.directory / "fake-zolt"
        runner.write_text("#!/usr/bin/env bash\nprintf 'TestEngine failed to discover tests\\n'\nexit 0\n",
                          encoding="utf-8")
        runner.chmod(0o755)
        stale = self.directory / "modules/logyard-output-json/target/failure-injection-reports"
        stale.mkdir(parents=True)
        (stale / "TEST-old.xml").write_text(
            '<testsuite><testcase classname="old.Run" name="passed"/></testsuite>', encoding="utf-8")
        result = subprocess.run(["bash", gate.as_posix()], cwd=self.directory,
                                env={**os.environ, "ZOLT": runner.as_posix()},
                                text=True, capture_output=True, timeout=10, check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no fresh JUnit reports", result.stderr)
        self.assertNotIn("verification passed", result.stdout)
