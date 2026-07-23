# Logyard

Structured logging for Java 21+, with one runtime shared by native, SLF4J 2, JUL, and `System.Logger` callers.

Logyard captures an immutable event once, applies the configured enrichers and filters, then sends that event to synchronous or asynchronous console and JSON outputs. Configuration is explicit TOML, reloads are atomic, and disabled log levels do not evaluate arguments, suppliers, context, clocks, or thread metadata.

Logyard is under active development. The `0.7.0-SNAPSHOT` coordinates below describe the current source build and are not yet published to Maven Central.

## Install

Most applications should use the SLF4J 2 provider:

```xml
<dependency>
  <groupId>com.zsumz.logyard</groupId>
  <artifactId>logyard-slf4j2</artifactId>
  <version>0.7.0-SNAPSHOT</version>
</dependency>
```

```kotlin
implementation("com.zsumz.logyard:logyard-slf4j2:0.7.0-SNAPSHOT")
```

Use `logyard-runtime` instead when the application calls Logyard's native API directly. Both artifacts include the runtime, TOML configuration, console output, and JSON file output.

## Configure

Create `logyard.toml` in the process working directory:

```toml
schema = 1

[service]
name = "checkout"
environment = "${APP_ENVIRONMENT:-local}"

[loggers]
root = { level = "info", outputs = ["console"] }
"com.example.checkout" = { level = "debug" }

[outputs.console]
type = "console"
stream = "stderr"
```

Configuration discovery is deterministic, in this order:

1. `-Dlogyard.config=/path/to/logyard.toml`
2. `LOGYARD_CONFIG=/path/to/logyard.toml`
3. `./logyard.toml`

Startup fails with the resolved path when an explicitly selected file is missing or invalid.

## Log

SLF4J applications use the standard API:

```java
private static final Logger LOG = LoggerFactory.getLogger(CheckoutService.class);

LOG.atInfo()
        .addKeyValue("order.id", orderId)
        .addKeyValue("amount", amount)
        .log("order accepted");
```

The provider starts Logyard lazily from the discovered configuration. If the application has already started a global Logyard runtime, the provider borrows it instead of creating a second one.

Native applications own the runtime explicitly:

```java
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;

try (RuntimeBundle logyard = LogyardBootstrap.start()) {
    LogyardLogger log = logyard.runtime().logger(CheckoutService.class);

    log.atInfo()
            .event("order.accepted")
            .add("order.id", orderId)
            .add("amount", amount)
            .log("order accepted");
}
```

Closing `RuntimeBundle` stops configuration watching, flushes buffered events, closes outputs, and removes the runtime from the process-global `Logyard` facade.

## Event model

Every accepted event has a timestamp, level, logger name, message template and arguments, structured attributes, resource metadata, thread metadata, and an optional captured exception. Outputs receive the same immutable event.

Values are captured defensively with depth, collection-size, string-length, and exception limits. Redaction is applied before outputs see an event:

```toml
[context]
mdc = ["request.id", "trace.id"]
redact = ["authorization", "cookie", "password", "*.secret", "*.token"]
```

Supplier arguments and attributes are lazy:

```java
log.atDebug()
        .add("plan", this::expensivePlan)
        .log("selected execution plan");
```

`expensivePlan()` is not called when debug logging is disabled.

## Delivery and overflow

Delivery is asynchronous by default. Each output has an isolated queue and worker, so a slow output does not serialize unrelated outputs:

```toml
[delivery]
mode = "async"
capacity = 65536

[delivery.overflow]
trace = "drop"
debug = "drop"
info = "drop"
warn = { action = "block", timeout = "2ms" }
error = "stderr"
```

Overflow actions are:

- `drop`: discard the event immediately.
- `block`: wait only for the configured timeout, then write the emergency representation to standard error.
- `sync`: wait for the optional timeout, then deliver on the caller thread.
- `stderr`: wait for the optional timeout, then write the emergency representation directly to standard error.

The default policy drops trace, debug, and info events; gives warnings a bounded two-millisecond wait before emergency stderr delivery; and sends errors immediately to emergency stderr. Runtime health exposes queue capacity, depth, dropped-event counts, and failure state.

## Outputs

Console output supports stdout or stderr, templates, and color themes:

```toml
[formatters.console]
type = "template"
template = "{timestamp} {level} {logger} {message} {fields}"

[outputs.console]
type = "console"
stream = "stderr"
formatter = "console"
color = { mode = "auto", theme = "ember" }
```

JSON output writes newline-delimited JSON and supports buffered files, timed flushes, size rotation, retention, and gzip compression:

```toml
[encoders.application]
type = "json"
profile = "application"

[json_profiles.application]
preset = "ecs"

[outputs.application]
type = "file"
path = "logs/application.jsonl"
encoder = "application"
append = true
buffer = "64KiB"
flush = "1s"
rotate = { size = "32MiB", keep = 5, compression = "gzip" }
```

The built-in JSON presets are `logyard`, `ecs`, and `compact`. Profiles can rename or drop fields and can nest, flatten, include, exclude, or prefix attributes.

## Atomic reload

Enable file watching explicitly:

```toml
[runtime]
watch = true
reload_debounce = "250ms"
internal_status = "warn"
```

A reload reads and parses a complete candidate configuration, assembles its resources, and publishes the new routing plan atomically. Invalid candidates are rejected while the current runtime remains active. An unchanged file is ignored.

Levels, routes, processors, context policy, and outputs may be added or removed at runtime. An existing file output holds an exclusive path lock; changing that output's path identity, buffering, rotation, encoder, delivery, or other resource-owning settings at the same path is rejected and requires a process restart. This prevents a reload from briefly running two writers against one file.

Applications can trigger the same decision directly:

```java
ReloadResult result = logyard.reloadNow();
```

Reload observer and diagnostic failures are isolated from the reload outcome. A recoverable logging-component failure is sent to emergency stderr; fatal VM failures are rethrown, and interrupted status is preserved.

## Extensions

The stable SPI supports context providers, event processors, text formatters, event encoders, outputs, and health contributors. Providers are discovered with `ServiceLoader`, receive a bounded immutable configuration, and create runtime-owned instances.

An event processor provider:

```java
public final class TenantEnricherProvider implements EventProcessorProvider {
    @Override
    public String name() {
        return "tenant";
    }

    @Override
    public EventProcessorKind kind() {
        return EventProcessorKind.ENRICHER;
    }

    @Override
    public ProviderConfigurationSpec configurationSpec() {
        return ProviderConfigurationSpec.of(Set.of("name"), Set.of("name"));
    }

    @Override
    public EventProcessor create(ProviderConfiguration configuration) {
        String tenant = configuration.requiredString("name");
        return event -> event.enrich(null, null, AttributeSet.of("tenant", tenant));
    }
}
```

Register it in:

```text
META-INF/services/com.zsumz.logyard.api.spi.processing.EventProcessorProvider
```

Then configure and attach it:

```toml
[enrichers.tenant]
provider = "tenant"

[enrichers.tenant.config]
name = "north"

[loggers]
root = { level = "info", outputs = ["console"], enrich = ["tenant"] }
```

Provider names are explicit; duplicate names, unknown keys, missing required keys, and ambiguous providers fail during assembly.

## Diagnostics

Inspect a running instance without parsing internal state:

```java
RuntimeHealth health = logyard.runtime().health();
EffectiveRoute route = logyard.runtime().explain("com.example.checkout");
```

`health()` reports output and worker status. `explain()` reports the effective level, inherited logger rule, processors, outputs, and context keys for one logger name. Set `runtime.internal_status = "off"` only when reload diagnostics on stderr are intentionally unwanted.

## Artifacts and compatibility

| Artifact | Use |
| --- | --- |
| `logyard-slf4j2` | SLF4J 2 provider and the normal application dependency |
| `logyard-runtime` | Native runtime, bootstrap, configuration, console, and JSON |
| `logyard-jul` | JUL handler |
| `logyard-system-logger` | `System.LoggerFinder` provider |
| `logyard-api` | Native API and extension SPI |

The supported public surface is every package in `logyard-api`, `com.zsumz.logyard.runtime.bootstrap`, and `com.zsumz.logyard.jul`. Other packages are implementation details marked `@InternalApi`; do not import them. Every published JAR has a stable `Automatic-Module-Name`, source JAR, and Javadoc JAR.

All Logyard artifacts use the same version. Java 21 is the minimum runtime, SLF4J 2.x is supported, and no compatibility claim is made for SLF4J 1.x.

## Build and verify

The repository pins and bootstraps Zolt:

```shell
./scripts/bootstrap-zolt
export PATH="$HOME/.zolt/bin:$PATH"
zolt resolve
./scripts/ci
./scripts/package
./scripts/package-verify
```

`scripts/ci` runs architecture checks and the full test suite. Packaged verification uses only produced JARs to check module names, sources, Javadocs, extension `ServiceLoader` contracts, native logging, SLF4J, and `System.Logger`. CI runs on Linux, macOS, and Windows with Java 21, plus a forward-compatibility lane on Java 25.

Tagged releases additionally assemble a Maven Central layout with complete POM metadata, dependency declarations, detached PGP signatures, and MD5, SHA-1, and SHA-256 checksums. `scripts/release-verify --require-signatures` verifies that complete bundle before GitHub release creation.

Apache-2.0 licensed.
