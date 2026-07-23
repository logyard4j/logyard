from __future__ import annotations

import argparse
from pathlib import Path

from .events import EventExpectation, EventLog
from .maven import MavenExample, MavenExampleRunner


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
    print("Published-shaped example verification passed: plain SLF4J.")


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


if __name__ == "__main__":
    main()
