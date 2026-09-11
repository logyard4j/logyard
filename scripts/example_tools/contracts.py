from __future__ import annotations

from .events import EventExpectation, EventLog


def require_plain_slf4j_events(events: EventLog) -> None:
    events.require_real_timestamps()
    logger = "com.logyard4j.examples.slf4j.Slf4jExampleApplication"
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


def require_vertx_events(events: EventLog) -> None:
    events.require_real_timestamps()
    events.require_logger_prefix("io.vertx")
    application_logger = "com.logyard4j.examples.vertx.VertxExampleApplication"
    routes_logger = "com.logyard4j.examples.vertx.VertxExampleRoutes"
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


def require_spring_boot_events(
    events: EventLog,
    actuator: bool = True,
    expected_jul_logger: str | None = None,
) -> None:
    events.require_real_timestamps()
    events.require_logger_prefix("org.springframework")
    if expected_jul_logger is not None:
        events.require_logger_prefix(expected_jul_logger)
    application_logger = "com.logyard4j.examples.springboot.SpringBootExampleApplication"
    controller_logger = "com.logyard4j.examples.springboot.SpringBootExampleController"
    shutdown_logger = "com.logyard4j.examples.springboot.SpringBootExampleShutdown"
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
    trace_logger = "com.logyard4j.examples.springboot.TraceExampleController"
    for body in ("Spring Boot trace request", "Spring Boot trace executor"):
        events.require(EventExpectation(body, trace_logger, "INFO", (
            ("trace_id", "0123456789abcdef0123456789abcdef"), ("span_id", "0123456789abcdef"),
            ("trace_flags", "01"),
        )))
    events.require(EventExpectation("Spring Boot trace scope closed", trace_logger, "INFO",
                                    absent_attributes=("trace_id", "span_id", "trace_flags")))


def require_micronaut_events(
    events: EventLog,
    final_bodies: tuple[str, ...] = ("Micronaut shutdown flush", "Embedded Application shutting down"),
) -> None:
    events.require_real_timestamps()
    events.require_logger_prefix("io.micronaut")
    application_logger = "com.logyard4j.examples.micronaut.MicronautExampleApplication"
    controller_logger = "com.logyard4j.examples.micronaut.MicronautExampleController"
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
    events.require(EventExpectation("Micronaut shutdown flush", "com.logyard4j.examples.micronaut.DeferredShutdown", "INFO", (("phase", "shutdown"),)))
    events.require_last_one_of(final_bodies)


def require_quarkus_events(events: EventLog) -> None:
    events.require_real_timestamps()
    events.require_logger_prefix("io.quarkus")
    lifecycle_logger = "com.logyard4j.examples.quarkus.QuarkusExampleLifecycle"
    resource_logger = "com.logyard4j.examples.quarkus.QuarkusExampleResource"
    events.require(EventExpectation("Quarkus application started", lifecycle_logger, "INFO"))
    events.require(
        EventExpectation(
            "Quarkus request succeeded",
            resource_logger,
            "INFO",
            (
                ("request.id", "request-success"),
                ("authorization", "[REDACTED]"),
                ("session.token", "[REDACTED]"),
            ),
        )
    )
    events.require(
        EventExpectation(
            "Quarkus request failed",
            resource_logger,
            "ERROR",
            (
                ("request.id", "request-failure"),
                ("authorization", "[REDACTED]"),
                ("session.token", "[REDACTED]"),
            ),
            "expected Quarkus example failure",
        )
    )
    events.require_no_attribute_value("authorization", "Bearer quarkus-example-secret")
    events.require_no_attribute_value("session.token", "quarkus-example-token")
    events.require(EventExpectation("Quarkus shutdown flush", resource_logger, "INFO"))
