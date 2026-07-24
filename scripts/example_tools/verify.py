from __future__ import annotations

import argparse
from pathlib import Path

from .events import EventExpectation, EventLog
from .maven import MavenExample, MavenExampleRunner
from .server import HttpExample, HttpExampleRunner, HttpRequestExpectation


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and run Logyard's published-shaped consumer examples.")
    parser.add_argument("root", type=Path)
    parser.add_argument("release_repository", type=Path)
    parser.add_argument("version")
    arguments = parser.parse_args()

    root = arguments.root.resolve()
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
        spring_boot_example(root, "3-mvc", "3.5.16", "spring-boot-starter-web"),
        expected_jul_logger="org.apache.catalina",
    )
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "4-mvc", "4.1.0", "spring-boot-starter-webmvc"),
        expected_jul_logger="org.apache.catalina",
    )
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "3-webflux", "3.5.16", "spring-boot-starter-webflux"),
    )
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "4-webflux", "4.1.0", "spring-boot-starter-webflux"),
    )
    verify_spring_boot(
        http_runner,
        spring_boot_example(root, "4-no-actuator", "4.1.0", "spring-boot-starter-webmvc", actuator=False),
        actuator=False,
        expected_jul_logger="org.apache.catalina",
    )
    external = spring_boot_example(
        root,
        "4-external-config",
        "4.1.0",
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
            "4.1.0",
            "spring-boot-starter-webmvc",
            actuator=False,
            build_profiles=("no-logyard-config",),
            runtime_arguments=(),
        ),
    )
    runner.build_aot(spring_boot_example(root, "3-aot", "3.5.16", "spring-boot-starter-web").maven)
    runner.build_aot(spring_boot_example(root, "4-aot", "4.1.0", "spring-boot-starter-webmvc").maven)
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
    runner.build(
        MavenExample(
            name="quarkus-no-health",
            project_directory=root / "examples" / "quarkus",
            main_class="io.quarkus.bootstrap.runner.QuarkusEntryPoint",
            build_arguments=("-DnoHealth",),
            runtime_arguments=(),
        )
    )
    print(
        "Published-shaped example verification passed: plain SLF4J, Vert.x, Micronaut, "
        "Spring Boot 3/4 MVC, WebFlux, no-Actuator, external/default configuration, AOT, "
        "and Quarkus test/packaged JVM modes with health present and absent."
    )


def verify_plain_slf4j(runner: MavenExampleRunner, example: MavenExample) -> None:
    events = EventLog.read(runner.build_and_run(example))
    events.require_real_timestamps()
    logger = "com.zsumz.logyard.examples.slf4j.Slf4jExampleApplication"
    events.require(EventExpectation("plain SLF4J startup", logger, "INFO", (("phase", "startup"),)))
    events.require(
        EventExpectation(
            "plain SLF4J application event",
            logger,
            "INFO",
            (("request.id", "plain-request-1"), ("operation", "example")),
        )
    )
    events.require(
        EventExpectation(
            "plain SLF4J failure",
            logger,
            "ERROR",
            (("request.id", "plain-request-1"),),
            "expected plain example failure",
        )
    )
    events.require_last("plain SLF4J shutdown flush")


def verify_vertx(runner: HttpExampleRunner, example: HttpExample) -> None:
    events = EventLog.read(runner.build_run_and_exercise(example))
    events.require_real_timestamps()
    events.require_logger_prefix("io.vertx")
    application_logger = "com.zsumz.logyard.examples.vertx.VertxExampleApplication"
    routes_logger = "com.zsumz.logyard.examples.vertx.VertxExampleRoutes"
    events.require(EventExpectation("Vert.x application started", application_logger, "INFO", (("phase", "startup"),)))
    events.require(EventExpectation("Vert.x request succeeded", routes_logger, "INFO", (("request.id", "request-success"),)))
    events.require(
        EventExpectation(
            "Vert.x request failed",
            routes_logger,
            "ERROR",
            (("request.id", "request-failure"),),
            "expected Vert.x example failure",
        )
    )
    events.require_last("Vert.x shutdown flush")


def verify_micronaut(runner: HttpExampleRunner, example: HttpExample) -> None:
    require_micronaut_events(EventLog.read(runner.build_run_and_exercise(example)))


def spring_boot_example(
    root: Path,
    variant: str,
    version: str,
    web_starter: str,
    actuator: bool = True,
    build_profiles: tuple[str, ...] = (),
    runtime_arguments: tuple[str, ...] | None = None,
) -> HttpExample:
    controller = "com.zsumz.logyard.examples.springboot.SpringBootExampleController"
    profiles = (*build_profiles, *(("no-actuator",) if not actuator else ()))
    actuator_requests = (
        HttpRequestExpectation("/actuator/health", 200, '"logyard"', body_contains=True),
        HttpRequestExpectation(
            f"/actuator/loggers/{controller}",
            204,
            "",
            method="POST",
            request_body='{"configuredLevel":"DEBUG"}',
        ),
        HttpRequestExpectation("/debug", 200, "debug"),
    )
    return HttpExample(
        MavenExample(
            name=f"spring-boot-{variant}",
            project_directory=root / "examples" / "spring-boot",
            main_class="com.zsumz.logyard.examples.springboot.SpringBootExampleApplication",
            build_arguments=(
                f"-Dspring-boot.version={version}",
                f"-Dspring-boot.web-starter={web_starter}",
                *((f"-P{','.join(profiles)}",) if profiles else ()),
            ),
            runtime_arguments=runtime_arguments
            if runtime_arguments is not None
            else ("-Dlogyard.config=classpath:logyard.toml",),
        ),
        executable_jar_name="logyard-spring-boot-example-1.0.0-SNAPSHOT.jar",
        additional_requests=actuator_requests if actuator else (),
    )


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


def require_spring_boot_events(
    events: EventLog,
    actuator: bool = True,
    expected_jul_logger: str | None = None,
) -> None:
    events.require_real_timestamps()
    events.require_logger_prefix("org.springframework")
    if expected_jul_logger is not None:
        events.require_logger_prefix(expected_jul_logger)
    application_logger = "com.zsumz.logyard.examples.springboot.SpringBootExampleApplication"
    controller_logger = "com.zsumz.logyard.examples.springboot.SpringBootExampleController"
    shutdown_logger = "com.zsumz.logyard.examples.springboot.SpringBootExampleShutdown"
    events.require(EventExpectation("Spring Boot application started", application_logger, "INFO", (("phase", "startup"),)))
    events.require(EventExpectation("Spring Boot request succeeded", controller_logger, "INFO", (("request.id", "request-success"),)))
    events.require(
        EventExpectation(
            "Spring Boot request failed",
            controller_logger,
            "ERROR",
            (("request.id", "request-failure"),),
            "expected Spring Boot example failure",
        )
    )
    if actuator:
        events.require(EventExpectation("Spring Boot dynamic debug", controller_logger, "DEBUG", (("request.id", "request-debug"),)))
    events.require(EventExpectation("Spring Boot shutdown flush", shutdown_logger, "INFO", (("phase", "shutdown"),)))


def require_micronaut_events(events: EventLog, final_bodies: tuple[str, ...] = ("Micronaut shutdown flush", "Embedded Application shutting down")) -> None:
    events.require_real_timestamps()
    events.require_logger_prefix("io.micronaut")
    application_logger = "com.zsumz.logyard.examples.micronaut.MicronautExampleApplication"
    controller_logger = "com.zsumz.logyard.examples.micronaut.MicronautExampleController"
    events.require(EventExpectation("Micronaut application started", application_logger, "INFO", (("phase", "startup"),)))
    events.require(EventExpectation("Micronaut request succeeded", controller_logger, "INFO", (("request.id", "request-success"),)))
    events.require(
        EventExpectation(
            "Micronaut request failed",
            controller_logger,
            "ERROR",
            (("request.id", "request-failure"),),
            "expected Micronaut example failure",
        )
    )
    events.require(EventExpectation("Micronaut shutdown flush", "com.zsumz.logyard.examples.micronaut.DeferredShutdown", "INFO", (("phase", "shutdown"),)))
    events.require_last_one_of(final_bodies)


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


def require_quarkus_events(events: EventLog) -> None:
    events.require_real_timestamps()
    events.require_logger_prefix("io.quarkus")
    lifecycle_logger = "com.zsumz.logyard.examples.quarkus.QuarkusExampleLifecycle"
    resource_logger = "com.zsumz.logyard.examples.quarkus.QuarkusExampleResource"
    events.require(EventExpectation("Quarkus application started", lifecycle_logger, "INFO"))
    events.require(
        EventExpectation(
            "Quarkus request succeeded",
            resource_logger,
            "INFO",
            (("quarkus.mdc", {"request.id": "request-success"}),),
        )
    )
    events.require(
        EventExpectation(
            "Quarkus request failed",
            resource_logger,
            "ERROR",
            (("quarkus.mdc", {"request.id": "request-failure"}),),
            "expected Quarkus example failure",
        )
    )
    events.require(EventExpectation("Quarkus shutdown flush", resource_logger, "INFO"))


if __name__ == "__main__":
    main()
