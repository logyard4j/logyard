from __future__ import annotations

import argparse
from pathlib import Path

from .events import EventLog
from .maven import MavenExample, MavenExampleRunner
from .server import ExecutableHttpExample, HttpExampleRunner
from .verify import require_micronaut_events, require_spring_boot_events, spring_boot_example


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
    micronaut = MavenExample(
        name="micronaut-native",
        project_directory=root / "examples" / "micronaut",
        main_class="com.zsumz.logyard.examples.micronaut.MicronautExampleApplication",
    )
    executable = maven.build_native(micronaut, "logyard-micronaut-example")
    http = HttpExampleRunner(maven, target)
    output = http.run_and_exercise(
        ExecutableHttpExample(
            name=micronaut.name,
            project_directory=micronaut.project_directory,
            command=(str(executable),),
            additional_environment=(("LOGYARD_CONFIG", "classpath:logyard.toml"),),
        )
    )
    require_micronaut_events(
        EventLog.read(output),
        final_bodies=("Micronaut shutdown flush", "Embedded Application shutting down"),
    )

    spring = spring_boot_example(root, "4-native", "4.1.0", "spring-boot-starter-webmvc")
    spring_executable = maven.build_spring_native(spring.maven, "logyard-spring-boot-example")
    spring_output = http.run_and_exercise(
        ExecutableHttpExample(
            name=spring.maven.name,
            project_directory=spring.maven.project_directory,
            command=(str(spring_executable),),
            additional_environment=(("LOGYARD_CONFIG", "classpath:logyard.toml"),),
            additional_requests=spring.additional_requests,
        )
    )
    require_spring_boot_events(
        EventLog.read(spring_output),
        expected_jul_logger="org.apache.catalina",
    )
    print("Published-shaped native example verification passed: Micronaut and Spring Boot 4.")


if __name__ == "__main__":
    main()
