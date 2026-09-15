from io import BytesIO
from pathlib import Path
import os
import tempfile
import unittest
from zipfile import ZipFile

from example_tools.providers import (
    LOGYARD_PROVIDER,
    SERVICE,
    classpath_providers,
    executable_providers,
    require_competing_provider,
    require_single_logyard,
)


def jar(path: Path, provider: str) -> None:
    with ZipFile(path, "w") as archive:
        archive.writestr(SERVICE, f"# service provider\n{provider} # registered\n")


class ProviderPreflightTest(unittest.TestCase):
    def test_runtime_classpath_requires_one_logyard_provider(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            logyard = root / "logyard.jar"
            competing = root / "other.jar"
            duplicate = root / "duplicate.jar"
            jar(logyard, LOGYARD_PROVIDER)
            jar(competing, "org.slf4j.simple.SimpleServiceProvider")
            jar(duplicate, LOGYARD_PROVIDER)

            require_single_logyard(classpath_providers(str(logyard)), "consumer")
            for paths in ((competing,), (logyard, competing), (logyard, duplicate)):
                providers = classpath_providers(os.pathsep.join(map(str, paths)))
                with self.subTest(paths=paths), self.assertRaisesRegex(AssertionError, "exactly one Logyard"):
                    require_single_logyard(providers, "consumer")

    def test_spring_executable_conflict_is_visible_before_startup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / "application.jar"
            nested = BytesIO()
            with ZipFile(nested, "w") as logyard:
                logyard.writestr(SERVICE, LOGYARD_PROVIDER)
            competitor = BytesIO()
            with ZipFile(competitor, "w") as other:
                other.writestr(SERVICE, "org.slf4j.simple.SimpleServiceProvider")
            with ZipFile(executable, "w") as application:
                application.writestr("BOOT-INF/lib/logyard.jar", nested.getvalue())
                application.writestr("BOOT-INF/lib/slf4j-simple.jar", competitor.getvalue())

            providers = executable_providers(executable)
            self.assertEqual(2, len(providers))
            with self.assertRaisesRegex(AssertionError, "exactly one Logyard"):
                require_single_logyard(providers, "packaged application")
            require_competing_provider(providers, "intentional conflict fixture")

    def test_missing_classpath_entry_fails_before_application_startup(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(AssertionError, "entry is missing"):
                classpath_providers(str(Path(temporary) / "missing.jar"))


if __name__ == "__main__":
    unittest.main()
