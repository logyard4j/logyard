from __future__ import annotations

import argparse
from pathlib import Path

from .events import EventExpectation, EventLog
from .maven import MavenExample, MavenExampleRunner
from .server import HttpExample, HttpExampleRunner


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
    print("Published-shaped example verification passed: plain SLF4J, Vert.x, and Micronaut.")


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


if __name__ == "__main__":
    main()
