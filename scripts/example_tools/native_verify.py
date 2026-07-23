from __future__ import annotations

import argparse
from pathlib import Path

from .events import EventLog
from .maven import MavenExample, MavenExampleRunner
from .server import ExecutableHttpExample, HttpExampleRunner
from .verify import require_micronaut_events


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and run Logyard's published-shaped native framework example.")
    parser.add_argument("root", type=Path)
    parser.add_argument("release_repository", type=Path)
    parser.add_argument("version")
    arguments = parser.parse_args()

    root = arguments.root.resolve()
    target = root / "target" / "examples-native-verify"
    maven = MavenExampleRunner(arguments.release_repository.resolve(), arguments.version, target)
    maven.prepare()
    project = MavenExample(
        name="micronaut-native",
        project_directory=root / "examples" / "micronaut",
        main_class="com.zsumz.logyard.examples.micronaut.MicronautExampleApplication",
    )
    executable = maven.build_native(project, "logyard-micronaut-example")
    output = HttpExampleRunner(maven, target).run_and_exercise(
        ExecutableHttpExample(
            name=project.name,
            project_directory=project.project_directory,
            command=(str(executable),),
            additional_environment=(("LOGYARD_CONFIG", "classpath:logyard.toml"),),
        )
    )
    require_micronaut_events(
        EventLog.read(output),
        final_bodies=("Micronaut shutdown flush", "Embedded Application shutting down"),
    )
    print("Published-shaped native example verification passed: Micronaut.")


if __name__ == "__main__":
    main()
