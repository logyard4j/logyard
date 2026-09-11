from __future__ import annotations

import re


PREFIX = "com.zsumz.logyard."
MAX_NEW_PRODUCTION_LINES = 220
MAX_GRANDFATHERED_PRODUCTION_LINES = 300
MAX_TEST_OR_EXAMPLE_LINES = 300
PACKAGE = re.compile(r"(?m)^package\s+([A-Za-z_][\w.]*)\s*;")
IMPORT = re.compile(r"(?m)^import\s+(?:static\s+)?([A-Za-z_][\w.*]*)\s*;")
UNBOUNDED = (
    (re.compile(r"Executors\.newCachedThreadPool\s*\("), "cached thread pools"),
    (re.compile(r"new\s+LinkedBlocking(?:Queue|Deque)(?:<[^>]*>)?\s*\(\s*\)"), "unbounded blocking queues"),
    (re.compile(r"CompletableFuture\.(?:run|supply)Async\s*\([^,)]*\)"), "implicit common-pool work"),
)

ALLOWED_IMPORT_PREFIXES = {
    "common": ("java.", "com.sun.management.", "org.slf4j.", PREFIX + "compare."),
    "logyard": ("java.", PREFIX + "api.", PREFIX + "runtime.", PREFIX + "compare."),
    "logback": ("java.", "ch.qos.logback.", "net.logstash.logback.", "org.slf4j.", PREFIX + "compare."),
    "log4j2": ("java.", "org.apache.logging.log4j.", PREFIX + "compare."),
    "logyard-api": ("java.", PREFIX + "api."),
    "logyard-core": ("java.", PREFIX + "api.", PREFIX + "core."),
    "logyard-config-toml": ("java.", PREFIX + "api.", PREFIX + "config."),
    "logyard-output-console": ("java.", PREFIX + "api.", PREFIX + "output.console."),
    "logyard-output-json": ("java.", PREFIX + "api.", PREFIX + "output.json."),
    "logyard-runtime": (
        "java.", "javax.xml.parsers.", "org.w3c.dom.", "org.xml.sax.",
        "".join((PREFIX, "api.")), PREFIX + "config.", PREFIX + "core.",
        PREFIX + "output.console.", PREFIX + "output.json.", PREFIX + "runtime.",
    ),
    "logyard-slf4j2": ("java.", "org.slf4j.", PREFIX + "api.", PREFIX + "runtime.", PREFIX + "slf4j."),
    "logyard-opentelemetry": ("java.", "io.opentelemetry.", PREFIX + "api.", PREFIX + "opentelemetry."),
    "logyard-spring-boot": (
        "java.", "org.springframework.", PREFIX + "api.", PREFIX + "jul.", PREFIX + "runtime.", PREFIX + "spring.boot.",
    ),
    "logyard-spring-boot-starter": ("java.", PREFIX + "api.", PREFIX + "spring.boot.starter."),
    "logyard-jul": ("java.", PREFIX + "api.", PREFIX + "runtime.", PREFIX + "jul."),
    "logyard-system-logger": ("java.", PREFIX + "api.", PREFIX + "runtime.", PREFIX + "systemlogger."),
    "logyard-integration-tests": (
        "java.", PREFIX + "api.", PREFIX + "config.", PREFIX + "core.", PREFIX + "output.console.", PREFIX + "output.json.", PREFIX + "runtime.", PREFIX + "tests.",
    ),
    "logyard-benchmarks": (
        "java.", "org.openjdk.jmh.", "org.slf4j.", PREFIX + "api.", PREFIX + "core.", PREFIX + "output.console.", PREFIX + "output.json.", PREFIX + "runtime.", PREFIX + "slf4j.", PREFIX + "benchmarks.",
    ),
    "runtime": (
        "java.", "io.quarkus.", "io.smallrye.config.", "jakarta.inject.", "org.eclipse.microprofile.health.", "org.jboss.logmanager.", PREFIX + "api.", PREFIX + "quarkus.", PREFIX + "runtime.",
    ),
    "deployment": ("java.", "io.quarkus.", PREFIX + "quarkus."),
    "benchmarks": ("java.", "org.jboss.logmanager.", "org.openjdk.jmh.", PREFIX + "api.", PREFIX + "core.", PREFIX + "quarkus."),
}

EXPECTED_DEPENDENCIES = {
    "modules/logyard-api": set(),
    "modules/logyard-core": {"com.zsumz.logyard:logyard-api"},
    "modules/logyard-config-toml": {"com.zsumz.logyard:logyard-api"},
    "modules/logyard-output-console": {"com.zsumz.logyard:logyard-api"},
    "modules/logyard-output-json": {"com.zsumz.logyard:logyard-api"},
    "modules/logyard-runtime": {
        "com.zsumz.logyard:logyard-api", "com.zsumz.logyard:logyard-core", "com.zsumz.logyard:logyard-config-toml", "com.zsumz.logyard:logyard-output-console", "com.zsumz.logyard:logyard-output-json",
    },
    "modules/logyard-slf4j2": {"com.zsumz.logyard:logyard-api", "com.zsumz.logyard:logyard-runtime", "org.slf4j:slf4j-api"},
    "modules/logyard-opentelemetry": {"com.zsumz.logyard:logyard-api", "io.opentelemetry:opentelemetry-api"},
    "modules/logyard-spring-boot": {
        "com.zsumz.logyard:logyard-api", "com.zsumz.logyard:logyard-jul", "com.zsumz.logyard:logyard-runtime", "org.springframework.boot:spring-boot", "org.springframework.boot:spring-boot-autoconfigure",
    },
    "modules/logyard-spring-boot-starter": {
        "com.zsumz.logyard:logyard-api", "com.zsumz.logyard:logyard-slf4j2", "com.zsumz.logyard:logyard-spring-boot", "org.springframework.boot:spring-boot-starter",
    },
    "modules/logyard-jul": {"com.zsumz.logyard:logyard-api", "com.zsumz.logyard:logyard-runtime"},
    "modules/logyard-system-logger": {"com.zsumz.logyard:logyard-api", "com.zsumz.logyard:logyard-runtime"},
    "tests/logyard-integration-tests": {
        "com.zsumz.logyard:logyard-api", "com.zsumz.logyard:logyard-core", "com.zsumz.logyard:logyard-config-toml", "com.zsumz.logyard:logyard-output-console", "com.zsumz.logyard:logyard-output-json", "com.zsumz.logyard:logyard-runtime",
    },
    "benchmarks/logyard-benchmarks": {
        "com.zsumz.logyard:logyard-api", "com.zsumz.logyard:logyard-core", "com.zsumz.logyard:logyard-output-console", "com.zsumz.logyard:logyard-output-json", "com.zsumz.logyard:logyard-runtime", "com.zsumz.logyard:logyard-slf4j2", "org.openjdk.jmh:jmh-core", "org.slf4j:slf4j-api",
    },
}

ARTIFACT_CONTRACTS = {
    "modules/logyard-api": (
        "com.zsumz.logyard.api",
        {
            "com.zsumz.logyard.api", "com.zsumz.logyard.api.annotation", "com.zsumz.logyard.api.delivery", "com.zsumz.logyard.api.diagnostics", "com.zsumz.logyard.api.event", "com.zsumz.logyard.api.failure", "com.zsumz.logyard.api.format", "com.zsumz.logyard.api.ingress", "com.zsumz.logyard.api.reload", "com.zsumz.logyard.api.spi", "com.zsumz.logyard.api.spi.config", "com.zsumz.logyard.api.spi.context", "com.zsumz.logyard.api.spi.diagnostics", "com.zsumz.logyard.api.spi.encoding", "com.zsumz.logyard.api.spi.formatting", "com.zsumz.logyard.api.spi.output", "com.zsumz.logyard.api.spi.processing",
        },
    ),
    "modules/logyard-core": ("com.zsumz.logyard.core", set()),
    "modules/logyard-config-toml": ("com.zsumz.logyard.config.toml", set()),
    "modules/logyard-output-console": ("com.zsumz.logyard.output.console", set()),
    "modules/logyard-output-json": ("com.zsumz.logyard.output.json", set()),
    "modules/logyard-runtime": ("com.zsumz.logyard.runtime", {"com.zsumz.logyard.runtime.bootstrap", "com.zsumz.logyard.runtime.management"}),
    "modules/logyard-slf4j2": ("com.zsumz.logyard.slf4j2", set()),
    "modules/logyard-opentelemetry": ("com.zsumz.logyard.opentelemetry", set()),
    "modules/logyard-spring-boot": (
        "com.zsumz.logyard.spring.boot",
        {"com.zsumz.logyard.spring.boot.autoconfigure", "com.zsumz.logyard.spring.boot.logging", "com.zsumz.logyard.spring.boot.nativeimage"},
    ),
    "modules/logyard-spring-boot-starter": ("com.zsumz.logyard.spring.boot.starter", set()),
    "modules/logyard-jul": ("com.zsumz.logyard.jul", {"com.zsumz.logyard.jul"}),
    "modules/logyard-system-logger": ("com.zsumz.logyard.system.logger", set()),
}

SERVICE_CONTRACTS = {
    "modules/logyard-opentelemetry/src/main/resources/META-INF/services/com.zsumz.logyard.api.spi.context.ContextProvider": ["com.zsumz.logyard.opentelemetry.OpenTelemetryContextProvider"],
    "modules/logyard-slf4j2/src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider": ["com.zsumz.logyard.slf4j.LogyardServiceProvider"],
    "modules/logyard-system-logger/src/main/resources/META-INF/services/java.lang.System$LoggerFinder": ["com.zsumz.logyard.systemlogger.LogyardLoggerFinder"],
    "tests/logyard-integration-tests/src/main/resources/META-INF/services/com.zsumz.logyard.api.spi.formatting.TextFormatterProvider": ["com.zsumz.logyard.tests.extensions.TestFormatterProvider"],
    "tests/logyard-integration-tests/src/main/resources/META-INF/services/com.zsumz.logyard.api.spi.encoding.EventEncoderProvider": ["com.zsumz.logyard.tests.extensions.TestEncoderProvider"],
    "tests/logyard-integration-tests/src/main/resources/META-INF/services/com.zsumz.logyard.api.spi.processing.EventProcessorProvider": ["com.zsumz.logyard.tests.extensions.TestEnricherProvider", "com.zsumz.logyard.tests.extensions.TestFilterProvider"],
    "tests/logyard-integration-tests/src/main/resources/META-INF/services/com.zsumz.logyard.api.spi.output.OutputProvider": ["com.zsumz.logyard.tests.extensions.TestOutputProvider"],
}
