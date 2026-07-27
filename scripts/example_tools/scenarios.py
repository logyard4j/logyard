from __future__ import annotations

from pathlib import Path

from .maven import MavenExample
from .server import HttpExample, HttpRequestExpectation


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
