from __future__ import annotations

from pathlib import Path

from .zolt import ZoltExample
from .server import HttpExample, HttpRequestExpectation
from .versions import framework_versions


def spring_boot_example(
    root: Path,
    variant: str,
    version: str,
    web_starter: str,
    actuator: bool = True,
    omit_config: bool = False,
    runtime_arguments: tuple[str, ...] | None = None,
) -> HttpExample:
    controller = "com.logyard4j.examples.springboot.SpringBootExampleController"
    current = framework_versions(root)["spring_boot_current"]
    replacements = [
        (f'"org.springframework.boot:spring-boot-dependencies" = "{current}"',
         f'"org.springframework.boot:spring-boot-dependencies" = "{version}"'),
        ("spring-boot-starter-webmvc", web_starter),
    ]
    if not actuator:
        replacements.append(('"org.springframework.boot:spring-boot-starter-actuator" = '
                             '{ managed = true, exclude = ["org.springframework.boot:spring-boot-starter-logging"] }\n', ""))
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
        ZoltExample(
            name=f"spring-boot-{variant}",
            project_directory=root / "examples" / "spring-boot",
            main_class="com.logyard4j.examples.springboot.SpringBootExampleApplication",
            replacements=tuple(replacements),
            omit_config=omit_config,
            runtime_arguments=runtime_arguments
            if runtime_arguments is not None
            else ("-Dlogyard.config=classpath:logyard.toml",),
        ),
        executable_jar_name="logyard-spring-boot-example-1.0.0-SNAPSHOT.jar",
        additional_requests=(actuator_requests if actuator else ()) + (
            HttpRequestExpectation("/trace", 200, "trace", headers=(
                ("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"),
            )),
        ),
    )
