from __future__ import annotations

import argparse
import subprocess
from dataclasses import replace
from pathlib import Path

from .contracts import (
    require_micronaut_events,
    require_plain_slf4j_events,
    require_quarkus_events,
    require_spring_boot_events,
    require_vertx_events,
)
from .events import EventExpectation, EventLog
from .zolt import ZoltExample, ZoltExampleRunner
from .server import HttpExample, HttpExampleRunner, HttpRequestExpectation
from .scenarios import spring_boot_example
from .versions import framework_versions


def main() -> None:
    parser = argparse.ArgumentParser(description="Build and run Logyard's published-shaped consumer examples.")
    parser.add_argument("root", type=Path)
    parser.add_argument("release_repository", type=Path)
    parser.add_argument("version")
    parser.add_argument("--scenario", required=True, choices=(
        "slf4j", "lifecycle", "opentelemetry", "test-kit", "migration", "vertx", "micronaut", "spring-boot-3-mvc", "spring-boot-4-mvc",
        "spring-boot-3-webflux", "spring-boot-4-webflux", "spring-boot-4-no-actuator",
        "spring-boot-4-external-config", "spring-boot-4-safe-defaults", "quarkus", "quarkus-disabled",
        "spring-boot-4-provider-conflict",
    ))
    arguments = parser.parse_args()

    root = arguments.root.resolve()
    target = root / "target" / "examples-verify"
    with ZoltExampleRunner(arguments.release_repository.resolve(), arguments.version, target) as runner:
        verify_scenario(root, runner, HttpExampleRunner(runner, target), arguments.scenario)
    print(f"Published-artifact example passed: {arguments.scenario}.")


def verify_scenario(root: Path, runner: ZoltExampleRunner, http: HttpExampleRunner, scenario: str) -> None:
    versions = framework_versions(root)
    if scenario in ("test-kit", "migration"):
        runner.build(ZoltExample(scenario, root / "examples" / scenario, "", test=True, runtime_arguments=()))
        return
    if scenario == "lifecycle":
        example = ZoltExample(scenario, root / "examples/lifecycle",
                              "com.logyard4j.examples.lifecycle.LifecycleExampleApplication", runtime_arguments=())
        events = EventLog.read(runner.build_and_run(example))
        events.require_real_timestamps()
        for cycle in (0, 1):
            for facade in ("slf4j", "jul", "system", "native"):
                severity = "WARN" if cycle == 1 or facade == "native" else "INFO"
                events.require(EventExpectation(f"{facade} cycle {cycle}", f"lifecycle.{facade}", severity))
        events.require_last("native cycle 1")
        return
    if scenario == "spring-boot-4-provider-conflict":
        base = spring_boot_example(root, "4-provider-conflict", versions["spring_boot_current"],
                                   "spring-boot-starter-webmvc").zolt
        fixture = replace(base, replacements=base.replacements + (
            ('[dependencies]', '[dependencies]\n"org.slf4j:slf4j-simple" = "2.0.18"'),
        ))
        built = runner.build(fixture)
        process = subprocess.run(runner.executable_jar_command(built, "logyard-spring-boot-example-1.0.0-SNAPSHOT.jar"),
                                 cwd=built.example.project_directory, capture_output=True, text=True, timeout=30)
        diagnostics = process.stdout + process.stderr
        (root / "target/examples-verify/spring-boot-4-provider-conflict.process.log").write_text(diagnostics)
        if process.returncode == 0 or "requires Logyard to be the sole SLF4J provider" not in diagnostics:
            raise AssertionError(f"competing provider did not fail startup with remediation:\n{diagnostics}")
        if "spring-boot-starter-logging" not in diagnostics:
            raise AssertionError("competing provider failure omitted starter exclusion guidance")
        return
    if scenario == "opentelemetry":
        logger = "com.logyard4j.examples.opentelemetry.OpenTelemetryExampleApplication"
        example = ZoltExample(scenario, root / "examples/opentelemetry", logger, test=True)
        events = EventLog.read(runner.build_and_run(example))
        events.require_real_timestamps()
        for body in ("trace direct", "trace wrapped"):
            events.require(EventExpectation(body, logger, "INFO", (
                ("trace_id", "0123456789abcdef0123456789abcdef"), ("span_id", "0123456789abcdef"),
                ("trace_flags", "01"), ("baggage.tenant.id", "tenant-7"),
            ), absent_attributes=("baggage.secret",)))
        for body in ("trace unwrapped", "trace outside", "trace shutdown flush"):
            events.require(EventExpectation(body, logger, "INFO", absent_attributes=(
                "trace_id", "span_id", "trace_flags", "baggage.tenant.id", "baggage.secret",
            )))
        events.require_last("trace shutdown flush")
        return
    if scenario in ("slf4j", "vertx", "micronaut"):
        name = {"slf4j": "Slf4j", "vertx": "Vertx", "micronaut": "Micronaut"}[scenario]
        example = ZoltExample(scenario, root / "examples" / scenario,
                              f"com.logyard4j.examples.{scenario}.{name}ExampleApplication")
        if scenario == "slf4j":
            verify_plain_slf4j(runner, example)
        elif scenario == "vertx":
            verify_vertx(http, HttpExample(example))
        else:
            verify_micronaut(http, HttpExample(example))
        return
    if scenario.startswith("quarkus"):
        disabled = scenario == "quarkus-disabled"
        example = HttpExample(
            ZoltExample(
                scenario, root / "examples/quarkus", "io.quarkus.bootstrap.runner.QuarkusEntryPoint",
                test=scenario == "quarkus",
                runtime_arguments=("-Dquarkus.logyard.enabled=false",) if disabled else (),
            ),
            executable_jar_name="quarkus-app/quarkus-run.jar",
            random_port_environment="QUARKUS_HTTP_PORT",
            additional_requests=(HttpRequestExpectation("/q/health/ready", 404, "", body_contains=True),),
        )
        if disabled:
            output = http.build_run_and_exercise(example)
            if output.exists() and output.stat().st_size:
                raise AssertionError("disabled Quarkus extension wrote Logyard events")
        else:
            verify_quarkus(http, example)
        return
    variant = scenario.removeprefix("spring-boot-")
    baseline = variant.startswith("3-")
    version = versions["spring_boot_baseline" if baseline else "spring_boot_current"]
    webflux = variant.endswith("webflux")
    starter = "spring-boot-starter-webflux" if webflux else (
        "spring-boot-starter-web" if baseline else "spring-boot-starter-webmvc")
    defaults = variant.endswith("safe-defaults")
    actuator = not (defaults or variant.endswith("no-actuator"))
    arguments = () if defaults else None
    if variant.endswith("external-config"):
        arguments = (f"-Dlogyard.config={root / 'examples/spring-boot/external-logyard.toml'}",)
    example = spring_boot_example(root, variant, version, starter, actuator=actuator,
                                  omit_config=defaults, runtime_arguments=arguments)
    if defaults:
        verify_spring_boot_defaults(http, example)
    else:
        events = verify_spring_boot(http, example, actuator=actuator,
                                    expected_jul_logger=None if webflux else "org.apache.catalina")
        if variant.endswith("external-config"):
            events.require_resource("deployment.environment.name", "external-verification")


def verify_plain_slf4j(runner: ZoltExampleRunner, example: ZoltExample) -> None:
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
    process_output = runner.process_log_path(example.zolt.name).read_text(encoding="utf-8")
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
    process_output = runner.process_log_path(example.zolt.name).read_text(encoding="utf-8")
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
