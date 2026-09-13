from __future__ import annotations

import re


PREFIX = "com.logyard4j."
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
    "logyard": ("java.", "org.slf4j.", PREFIX + "api.", PREFIX + "runtime.", PREFIX + "compare."),
    "logback": ("java.", "ch.qos.logback.", "net.logstash.logback.", "org.slf4j.", PREFIX + "compare."),
    "log4j2": ("java.", "org.apache.logging.log4j.", PREFIX + "compare."),
    "logyard-test": ("java.", PREFIX + "api.", PREFIX + "core.", PREFIX + "test."),
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
    "logyard-adapter-canaries": (
        "java.", PREFIX + "api.", PREFIX + "core.", PREFIX + "output.console.", PREFIX + "output.json.",
        PREFIX + "runtime.", PREFIX + "jul.", PREFIX + "systemlogger.", PREFIX + "canaries.",
    ),
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
    "modules/logyard-test": {"com.logyard4j:logyard-api", "com.logyard4j:logyard-core"},
    "modules/logyard-api": set(),
    "modules/logyard-core": {"com.logyard4j:logyard-api"},
    "modules/logyard-config-toml": {"com.logyard4j:logyard-api"},
    "modules/logyard-output-console": {"com.logyard4j:logyard-api"},
    "modules/logyard-output-json": {"com.logyard4j:logyard-api"},
    "modules/logyard-runtime": {
        "com.logyard4j:logyard-api", "com.logyard4j:logyard-core", "com.logyard4j:logyard-config-toml", "com.logyard4j:logyard-output-console", "com.logyard4j:logyard-output-json",
    },
    "modules/logyard-slf4j2": {"com.logyard4j:logyard-api", "com.logyard4j:logyard-runtime", "org.slf4j:slf4j-api"},
    "modules/logyard-opentelemetry": {"com.logyard4j:logyard-api", "io.opentelemetry:opentelemetry-api"},
    "modules/logyard-spring-boot": {
        "com.logyard4j:logyard-api", "com.logyard4j:logyard-jul", "com.logyard4j:logyard-runtime", "org.springframework.boot:spring-boot", "org.springframework.boot:spring-boot-autoconfigure",
    },
    "modules/logyard-spring-boot-starter": {
        "com.logyard4j:logyard-api", "com.logyard4j:logyard-slf4j2", "com.logyard4j:logyard-spring-boot", "org.springframework.boot:spring-boot-starter",
    },
    "modules/logyard-jul": {"com.logyard4j:logyard-api", "com.logyard4j:logyard-runtime"},
    "modules/logyard-system-logger": {"com.logyard4j:logyard-api", "com.logyard4j:logyard-runtime"},
    "tests/logyard-integration-tests": {
        "com.logyard4j:logyard-api", "com.logyard4j:logyard-core", "com.logyard4j:logyard-config-toml", "com.logyard4j:logyard-output-console", "com.logyard4j:logyard-output-json", "com.logyard4j:logyard-runtime",
    },
    "tests/logyard-adapter-canaries": {
        "com.logyard4j:logyard-api", "com.logyard4j:logyard-core", "com.logyard4j:logyard-config-toml",
        "com.logyard4j:logyard-output-console", "com.logyard4j:logyard-output-json",
        "com.logyard4j:logyard-runtime", "com.logyard4j:logyard-jul", "com.logyard4j:logyard-system-logger",
        "com.logyard4j:logyard-slf4j2",
    },
    "benchmarks/logyard-benchmarks": {
        "com.logyard4j:logyard-api", "com.logyard4j:logyard-core", "com.logyard4j:logyard-output-console", "com.logyard4j:logyard-output-json", "com.logyard4j:logyard-runtime", "com.logyard4j:logyard-slf4j2", "org.openjdk.jmh:jmh-core", "org.slf4j:slf4j-api",
    },
}

ARTIFACT_MODULES = {
    "modules/logyard-test": "com.logyard4j.test",
    "modules/logyard-api": "com.logyard4j.api",
    "modules/logyard-core": "com.logyard4j.core",
    "modules/logyard-config-toml": "com.logyard4j.config.toml",
    "modules/logyard-output-console": "com.logyard4j.output.console",
    "modules/logyard-output-json": "com.logyard4j.output.json",
    "modules/logyard-runtime": "com.logyard4j.runtime",
    "modules/logyard-slf4j2": "com.logyard4j.slf4j2",
    "modules/logyard-opentelemetry": "com.logyard4j.opentelemetry",
    "modules/logyard-spring-boot": "com.logyard4j.spring.boot",
    "modules/logyard-spring-boot-starter": "com.logyard4j.spring.boot.starter",
    "modules/logyard-jul": "com.logyard4j.jul",
    "modules/logyard-system-logger": "com.logyard4j.system.logger",
}

SERVICE_CONTRACTS = {
    "modules/logyard-opentelemetry/src/main/resources/META-INF/services/com.logyard4j.api.spi.output.OutputProvider": ["com.logyard4j.opentelemetry.OtelOutputProvider"],
    "modules/logyard-opentelemetry/src/main/resources/META-INF/services/com.logyard4j.api.spi.context.ContextProvider": ["com.logyard4j.opentelemetry.OpenTelemetryContextProvider"],
    "modules/logyard-slf4j2/src/main/resources/META-INF/services/org.slf4j.spi.SLF4JServiceProvider": ["com.logyard4j.slf4j.LogyardServiceProvider"],
    "modules/logyard-system-logger/src/main/resources/META-INF/services/java.lang.System$LoggerFinder": ["com.logyard4j.systemlogger.LogyardLoggerFinder"],
    "tests/logyard-integration-tests/src/main/resources/META-INF/services/com.logyard4j.api.spi.formatting.TextFormatterProvider": ["com.logyard4j.tests.extensions.TestFormatterProvider"],
    "tests/logyard-integration-tests/src/main/resources/META-INF/services/com.logyard4j.api.spi.encoding.EventEncoderProvider": ["com.logyard4j.tests.extensions.TestEncoderProvider"],
    "tests/logyard-integration-tests/src/main/resources/META-INF/services/com.logyard4j.api.spi.processing.EventProcessorProvider": ["com.logyard4j.tests.extensions.TestEnricherProvider", "com.logyard4j.tests.extensions.TestFilterProvider"],
    "tests/logyard-integration-tests/src/main/resources/META-INF/services/com.logyard4j.api.spi.output.OutputProvider": ["com.logyard4j.tests.extensions.TestOutputProvider"],
}
