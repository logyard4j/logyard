from __future__ import annotations

import argparse
from pathlib import Path

from .contracts import (
    require_micronaut_events,
    require_plain_slf4j_events,
    require_quarkus_events,
    require_spring_boot_events,
    require_vertx_events,
)
from .events import EventLog
from .maven import MavenExample, MavenExampleRunner
from .server import HttpExample, HttpExampleRunner, HttpRequestExpectation
from .scenarios import spring_boot_example
from .versions import framework_versions


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and run Logyard's published-shaped consumer examples.")
    parser.add_argument("root", type=Path)
    parser.add_argument("release_repository", type=Path)
    parser.add_argument("version")
    arguments = parser.parse_args()

    root = arguments.root.resolve()
    versions = framework_versions(root)
    runner = MavenExampleRunner(arguments.release_repository.resolve(), arguments.version, root / "target" / "examples-verify")
    runner.prepare()
    verify_plain_slf4j(
        runner,
        MavenExample(
            name="slf4j",
            project_directory=root / "examples" / "slf4j",
            main_class="com.zsumz.logyard.examples.slf4j.Slf4jExampleApplication",
        ),
    )
    verify_vertx(
        HttpExampleRunner(runner, root / "target" / "examples-verify"),
        HttpExample(
            MavenExample(
                name="vertx",
                project_directory=root / "examples" / "vertx",
                main_class="com.zsumz.logyard.examples.vertx.VertxExampleApplication",
            )
        ),
    )
    verify_micronaut(
        HttpExampleRunner(runner, root / "target" / "examples-verify"),
        HttpExample(
            MavenExample(
                name="micronaut",
                project_directory=root / "examples" / "micronaut",
                main_class="com.zsumz.logyard.examples.micronaut.MicronautExampleApplication",
            )
        ),
    )
    http_runner = HttpExampleRunner(runner, root / "target" / "examples-verify")
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "3-mvc", versions["spring_boot_baseline"], "spring-boot-starter-web"),
        expected_jul_logger="org.apache.catalina",
    )
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "4-mvc", versions["spring_boot_current"], "spring-boot-starter-webmvc"),
        expected_jul_logger="org.apache.catalina",
    )
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "3-webflux", versions["spring_boot_baseline"], "spring-boot-starter-webflux"),
    )
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "4-webflux", versions["spring_boot_current"], "spring-boot-starter-webflux"),
    )
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "4-no-actuator", versions["spring_boot_current"], "spring-boot-starter-webmvc", actuator=False),
        actuator=False,
        expected_jul_logger="org.apache.catalina",
    )
    external = spring_boot_example(
        root,
        "4-external-config",
        versions["spring_boot_current"],
        "spring-boot-starter-webmvc",
        runtime_arguments=(f"-Dlogyard.config={root / 'examples' / 'spring-boot' / 'external-logyard.toml'}",),
    )
    external_events = verify_spring_boot(http_runner, external, expected_jul_logger="org.apache.catalina")
    external_events.require_resource("deployment.environment.name", "external-verification")
    verify_spring_boot_defaults(
        http_runner,
        spring_boot_example(
            root,
            "4-safe-defaults",
            versions["spring_boot_current"],
            "spring-boot-starter-webmvc",
            actuator=False,
            build_profiles=("no-logyard-config",),
            runtime_arguments=(),
        ),
    )
    runner.build_aot(spring_boot_example(root, "3-aot", versions["spring_boot_baseline"], "spring-boot-starter-web").maven)
    runner.build_aot(spring_boot_example(root, "4-aot", versions["spring_boot_current"], "spring-boot-starter-webmvc").maven)
    verify_quarkus(
        http_runner,
        HttpExample(
            MavenExample(
                name="quarkus",
                project_directory=root / "examples" / "quarkus",
                main_class="io.quarkus.bootstrap.runner.QuarkusEntryPoint",
                runtime_arguments=(),
            ),
            executable_jar_name="quarkus-app/quarkus-run.jar",
            random_port_environment="QUARKUS_HTTP_PORT",
            additional_requests=(
                HttpRequestExpectation("/q/health/ready", 200, '"logyard"', body_contains=True),
            ),
        ),
    )
    verify_quarkus(
        http_runner,
        HttpExample(
            MavenExample(
                name="quarkus-no-health",
                project_directory=root / "examples" / "quarkus",
                main_class="io.quarkus.bootstrap.runner.QuarkusEntryPoint",
                build_arguments=("-DnoHealth", "-DskipTests"),
                runtime_arguments=(),
            ),
            executable_jar_name="quarkus-app/quarkus-run.jar",
            random_port_environment="QUARKUS_HTTP_PORT",
            additional_requests=(
                HttpRequestExpectation("/q/health/ready", 404, "", body_contains=True),
            ),
        ),
    )
    print(
        "Published-shaped example verification passed: plain SLF4J, Vert.x, Micronaut, "
        "Spring Boot 3/4 MVC, WebFlux, no-Actuator, external/default configuration, AOT, "
        "and Quarkus test/packaged JVM modes with health present and absent."
    )


def verify_plain_slf4j(runner: MavenExampleRunner, example: MavenExample) -> None:
    require_plain_slf4j_events(EventLog.read(runner.build_and_run(example)))


def verify_vertx(runner: HttpExampleRunner, example: HttpExample) -> None:
    require_vertx_events(EventLog.read(runner.build_run_and_exercise(example)))


def verify_micronaut(runner: HttpExampleRunner, example: HttpExample) -> None:
    require_micronaut_events(EventLog.read(runner.build_run_and_exercise(example)))


def verify_spring_boot(
    runner: HttpExampleRunner,
    example: HttpExample,
    actuator: bool = True,
    expected_jul_logger: str | None = None,
) -> EventLog:
    events = EventLog.read(runner.build_run_and_exercise(example))
    require_spring_boot_events(
        events,
        actuator=actuator,
        expected_jul_logger=expected_jul_logger,
    )
    return events


def verify_spring_boot_defaults(runner: HttpExampleRunner, example: HttpExample) -> None:
    runner.build_run_and_exercise(example)
    process_output = runner.process_log_path(example.maven.name).read_text(encoding="utf-8")
    for expected in (
        "Spring Boot application started",
        "Spring Boot request succeeded",
        "Spring Boot request failed",
        "Spring Boot shutdown flush",
    ):
        if expected not in process_output:
            raise AssertionError(f"safe-default Spring Boot output did not contain {expected!r}")


def verify_quarkus(runner: HttpExampleRunner, example: HttpExample) -> None:
    events = EventLog.read(runner.build_run_and_exercise(example))
    require_quarkus_events(events)
    process_output = runner.process_log_path(example.maven.name).read_text(encoding="utf-8")
    for application_message in (
        "Quarkus application started",
        "Quarkus request succeeded",
        "Quarkus request failed",
        "Quarkus shutdown flush",
    ):
        if application_message in process_output:
            raise AssertionError(
                f"Quarkus console handler was not disabled; process output duplicated {application_message!r}"
            )


if __name__ == "__main__":
    main()
