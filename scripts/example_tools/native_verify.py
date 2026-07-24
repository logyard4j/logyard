from __future__ import annotations

import argparse
from pathlib import Path

from .events import EventLog
from .maven import MavenExample, MavenExampleRunner
from .server import ExecutableHttpExample, HttpExampleRunner, HttpRequestExpectation
from .verify import require_micronaut_events, require_quarkus_events, require_spring_boot_events, spring_boot_example
from .versions import framework_versions


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and run Logyard's published-shaped native framework example.")
    parser.add_argument("root", type=Path)
    parser.add_argument("release_repository", type=Path)
    parser.add_argument("version")
    parser.add_argument("--framework", action="append", choices=("micronaut", "spring-boot", "quarkus"))
    arguments = parser.parse_args()

    root = arguments.root.resolve()
    target = root / "target" / "examples-native-verify"
    maven = MavenExampleRunner(arguments.release_repository.resolve(), arguments.version, target)
    maven.prepare()
    http = HttpExampleRunner(maven, target)
    selected = arguments.framework or ("micronaut", "spring-boot", "quarkus")
    verifiers = {
        "micronaut": verify_micronaut,
        "spring-boot": verify_spring_boot,
        "quarkus": verify_quarkus,
    }
    for framework in selected:
        verifiers[framework](root, maven, http)
    print(f"Published-shaped native example verification passed: {', '.join(selected)}.")


def verify_micronaut(root: Path, maven: MavenExampleRunner, http: HttpExampleRunner) -> None:
    micronaut = MavenExample(
        name="micronaut-native",
        project_directory=root / "examples" / "micronaut",
        main_class="com.zsumz.logyard.examples.micronaut.MicronautExampleApplication",
    )
    executable = maven.build_native(micronaut, "logyard-micronaut-example")
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


def verify_spring_boot(root: Path, maven: MavenExampleRunner, http: HttpExampleRunner) -> None:
    spring = spring_boot_example(
        root,
        "4-native",
        framework_versions(root)["spring_boot_current"],
        "spring-boot-starter-webmvc",
    )
    spring_executable = maven.build_spring_native(spring.maven, "logyard-spring-boot-example")
    spring_output = http.run_and_exercise(
        ExecutableHttpExample(
            name=spring.maven.name,
            project_directory=spring.maven.project_directory,
            command=(str(spring_executable),),
            additional_environment=(("LOGYARD_CONFIG", "classpath:logging/logyard-prod.toml"),),
            additional_requests=spring.additional_requests,
        )
    )
    require_spring_boot_events(
        EventLog.read(spring_output),
        expected_jul_logger="org.apache.catalina",
    )


def verify_quarkus(root: Path, maven: MavenExampleRunner, http: HttpExampleRunner) -> None:
    quarkus = MavenExample(
        name="quarkus-native",
        project_directory=root / "examples" / "quarkus",
        main_class="io.quarkus.bootstrap.runner.QuarkusEntryPoint",
        runtime_arguments=(),
    )
    quarkus_executable = maven.build_quarkus_native(
        quarkus,
        "logyard-quarkus-example-1.0.0-SNAPSHOT-runner",
    )
    quarkus_output = http.run_and_exercise(
        ExecutableHttpExample(
            name=quarkus.name,
            project_directory=quarkus.project_directory,
            command=(str(quarkus_executable),),
            random_port_environment="QUARKUS_HTTP_PORT",
            additional_environment=(("QUARKUS_LOGYARD_CONFIG", "classpath:logging/logyard-prod.toml"),),
            additional_requests=(
                HttpRequestExpectation("/q/health/ready", 200, '"logyard"', body_contains=True),
            ),
        )
    )
    require_quarkus_events(EventLog.read(quarkus_output))


if __name__ == "__main__":
    main()
