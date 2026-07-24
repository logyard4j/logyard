from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from release_tools.jdk_home import JdkDiscoveryError, discover


class JdkHomeTest(unittest.TestCase):
    def test_explicit_zolt_home_wins(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home = self._jdk(Path(directory))
            self.assertEqual(home.resolve(), discover({"ZOLT_JAVA_HOME": str(home), "JAVA_HOME": "/ignored"}))

    def test_invalid_explicit_home_fails_clearly(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(JdkDiscoveryError, "ZOLT_JAVA_HOME"):
                discover({"ZOLT_JAVA_HOME": directory})

    def test_discovers_the_home_reported_by_the_current_java(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            home = self._jdk(Path(directory))
            completed = subprocess.CompletedProcess(
                args=["java"],
                returncode=0,
                stdout="",
                stderr=f"    java.home = {home}\n",
            )
            with patch("release_tools.jdk_home.shutil.which", side_effect=lambda tool: "/bin/java" if tool == "java" else None):
                with patch("release_tools.jdk_home.subprocess.run", return_value=completed):
                    self.assertEqual(home.resolve(), discover({}))

    @staticmethod
    def _jdk(home: Path) -> Path:
        binary = home / "bin" / "javadoc"
        binary.parent.mkdir(parents=True)
        binary.touch()
        return home


if __name__ == "__main__":
    unittest.main()
