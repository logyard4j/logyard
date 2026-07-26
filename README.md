# Logyard

Structured logging for Java 21+, with one runtime shared by native, SLF4J 2, JUL, and `System.Logger` callers.

Logyard captures an immutable event once, applies the configured enrichers and filters, then sends that event to synchronous or asynchronous console and JSON outputs. Configuration is explicit TOML, reloads are atomic, and disabled log levels do not evaluate arguments, suppliers, context, clocks, or thread metadata.

Logyard is under active development. The `0.1.0-rc.1` coordinates below describe the first release candidate. Availability begins after the release workflow completes.

## Install

Most applications should use the SLF4J 2 provider:

```xml
<dependency>
  <groupId>com.zsumz.logyard</groupId>
  <artifactId>logyard-slf4j2</artifactId>
  <version>0.1.0-rc.1</version>
</dependency>
```

```kotlin
implementation("com.zsumz.logyard:logyard-slf4j2:0.1.0-rc.1")
```

Use `logyard-runtime` instead when the application calls Logyard's native API directly. Both artifacts include the runtime, TOML configuration, console output, and JSON file output.

Spring Boot 3.5 and 4.1 applications should use the starter:

```xml
<dependency>
  <groupId>com.zsumz.logyard</groupId>
  <artifactId>logyard-spring-boot-starter</artifactId>
  <version>0.1.0-rc.1</version>
</dependency>
```

Exclude Boot's default logging starter from every higher-level starter that brings it. For MVC:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId> <!-- spring-boot-starter-webmvc on Boot 4 -->
  <exclusions>
    <exclusion>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-logging</artifactId>
    </exclusion>
  </exclusions>
</dependency>
```

Apply the same exclusion to `spring-boot-starter-webflux`, `spring-boot-starter-actuator`, and any other Boot starter that brings Logback. Startup fails with exact remediation if Logyard is missing or another SLF4J provider remains.

Logyard is process-global, including under Spring. Multiple application contexts, embedded applications, DevTools restarts, and test contexts in one JVM share the same runtime identity and reference-counted ownership; closing one context releases only its lease. They should not run incompatible Logyard configurations concurrently, and parallel tests that require independent logging state should use separate JVMs until a dedicated `logyard-test` isolation module exists.

The equivalent Gradle dependency replacement is:

```kotlin
implementation("org.springframework.boot:spring-boot-starter-web") {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}
implementation("com.zsumz.logyard:logyard-spring-boot-starter:0.1.0-rc.1")
```

Quarkus 3.37 applications use the conventional runtime extension:

```xml
<dependency>
  <groupId>com.zsumz.logyard</groupId>
  <artifactId>logyard-quarkus</artifactId>
  <version>0.1.0-rc.1</version>
</dependency>
```

Keep Quarkus and JBoss Log Manager as the application logging API. Disable the built-in console handler to avoid duplicate delivery, then point the extension at TOML:

```properties
quarkus.log.console.enabled=false
quarkus.logyard.config=classpath:logyard.toml
```

`quarkus.logyard.enabled=false` disables handler installation and `quarkus.logyard.required=true` makes a missing source fatal. When `quarkus-smallrye-health` is present, Logyard contributes the `logyard` readiness check without acquiring another runtime lease.

Quarkus build-time minimum levels remain an upstream ceiling. A category removed during augmentation never reaches Logyard, regardless of its TOML level. Preserve the required floor explicitly:

```properties
quarkus.log.category."com.example.checkout".min-level=DEBUG
```

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
3. A framework-selected source
4. `classpath:logyard.toml`
5. `./logyard.toml`
6. Built-in safe defaults

The built-in default is root `INFO` to a color-aware stderr console through bounded, nonblocking asynchronous delivery. It has no file output or watcher. Set `-Dlogyard.config.required=true` when configuration must exist. Explicit locations may be filesystem paths or classpath resources named `logyard.toml` or `logyard-*.toml`, including paths such as `classpath:logging/logyard-prod.toml`; those names are included automatically in Spring and Quarkus native images. A missing or invalid explicit source always fails.

Native and framework integrations can also supply bounded configuration directly:

```java
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;

LogyardConfigurationSource source = LogyardConfigurationSource.classpath(
        applicationClassLoader,
        "logging/logyard.toml",
        applicationDirectory);

try (RuntimeBundle logyard = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, source)) {
    // use logyard.runtime()
}
```

Spring Boot keeps the TOML model intact. `logyard.config` selects a classpath or filesystem source, `logyard.required=true` disables the safe fallback, and `logging.config` is accepted as an alias when it does not conflict with `logyard.config`. Set `-Dlogyard.enabled=false` or `LOGYARD_ENABLED=false` to opt out before Boot selects its logging system.

## Log

SLF4J applications use the standard API:

```java
private static final Logger LOG = LoggerFactory.getLogger(CheckoutService.class);

LOG.atInfo()
        .addKeyValue("order.id", orderId)
        .addKeyValue("amount", amount)
        .log("order accepted");
```

The provider acquires an adapter lease lazily. Applications, frameworks, and adapters all converge on one process-wide runtime: an early adapter can be reconfigured in place by a later application or framework without invalidating loggers already returned to libraries, while a late adapter cannot overwrite a framework-selected source.

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

Closing `RuntimeBundle` releases that owner’s lease. Configuration watching, buffered delivery, outputs, and the process-global `Logyard` reference are closed only when the final ownership lease is released.

JUL, `System.Logger`, and Quarkus `MESSAGE_FORMAT` records use bounded `MessageFormat` rendering. Exact `Date` values and epoch-millisecond `Long` values render in deterministic UTC with proleptic-Gregorian semantics. Date/time elements support only the localized `short`, `medium`, `long`, and `full` styles; custom `SimpleDateFormat` patterns are rejected and the original template is retained. Temporal `printf` conversions follow the same UTC and safe-value policy.

## Event model

Every accepted event has a timestamp, level, logger name, message template and arguments, structured attributes, resource metadata, thread metadata, and an optional captured exception. Outputs receive the same immutable event.

Values are detached at ingress as bounded trees. Cycles become `[circular reference]`; a second reference to the same container or throwable becomes `[shared reference]` or `[shared exception reference]` instead of preserving an alias that an output could expand again. Capture stops at 8 levels, 2,048 value nodes, and 4,096 aggregate entries.

The numeric values published by `CaptureLimits` are stable API constants; releases may add new limits but do not change an existing inlined value.

One event can retain at most 65,536 UTF-16 characters across explicit partitions: 4,096 for identity, 8,192 for the template, 16,384 for the rendered message, 16,384 for exception diagnostics, and 20,480 for arguments, attributes, and processor enrichment. Exception text is further reserved for types, messages, and frame fields so a large payload or exception message cannot erase the useful failure identity. Processor replacements are recaptured under the original allowance. Ingress loss sets `logyard.capture.truncated=true`; lazy message loss is exposed by `LogEvent.renderedMessageTruncated()` and the built-in outputs emit `logyard.output.truncated=true`.

Built-in rendering is independently defensive: one JSON record is capped at 262,144 characters and one complete console event at 131,072 characters. Both outputs enforce their own depth, identity, entry, and character limits even if a future processor violates the captured-value model.

The `logyard.*` attribute namespace is reserved for these system diagnostics. Application attributes, SLF4J key values, and captured context must use application-owned names.

Attribute keys are retained atomically: if the remaining event allowance cannot hold a complete normalized key, that attribute is omitted and capture truncation is reported. Distinct long keys that normalize to the same bounded storage form are disambiguated, including when independently built attribute sets are merged.

Redaction is recursive and runs before outputs see an event. Map keys extend a dot-separated path; zero-based list indexes use brackets, so the first token below `request.users` has the path `request.users[0].token`. Globs can match either the complete path or a map-key leaf:

```toml
[context]
mdc = ["request.id", "trace.id"]
redact = ["authorization", "cookie", "password", "*.secret", "*.token"]
```

Finite MDC allowlists are the recommended policy. `mdc = ["*"]` asks Quarkus/JBoss Log Manager for a complete MDC copy on every accepted event, so its capture cost grows with the source MDC even though the resulting Logyard event remains bounded.

Supplier arguments and attributes are evaluated only when an enabled event enters the protected publication boundary:

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
capacity = 2048

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

The default policy drops trace, debug, and info events; sends warnings and errors immediately to emergency stderr; and uses 2,048 slots per async output. The queue's reference array is small, but a completely full queue of maximum-text events can still approach 256 MiB before object overhead. Rich-event or memory-constrained services should choose a smaller capacity. Runtime health exposes queue capacity, depth, dropped-event counts, and failure state.

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

For a positive `flush` interval, Logyard schedules one flush after the first unflushed record; sparse traffic is pushed to the underlying stream or file channel without waiting for another event. Additional records share that pending flush, while `flush = "0s"` flushes synchronously after every record. Flush bounds Logyard's process buffering only: it does not currently promise an operating-system `fsync` or durable-storage barrier.

File output fails closed when an active write, flush, or rotation-close failure makes the final record boundary uncertain; buffered bytes are discarded, the channel is closed without retrying them, health remains failed, and later records are rejected. Archive-move and replacement-open failures remain recoverable only after the old active file closed cleanly. A zero runtime shutdown timeout starts no-wait daemon cleanup instead of reporting a deterministic timeout, while rollback of an output that never completed initialization always waits for its worker and lease to be released.

## Atomic reload

Enable file watching explicitly:

```toml
[runtime]
watch = true
reload_debounce = "250ms"
internal_status = "warn"
```

A reload reads and parses a complete candidate configuration, assembles its resources, and publishes the new routing plan atomically. Invalid candidates are rejected while the current runtime remains active. Only deterministic failures tied to the candidate bytes, such as parsing, provider validation, duplicate exclusive output paths, and restart-required policy changes, are memoized. Source I/O remains retryable with bounded exponential backoff, known lifecycle contention remains dirty until the owner releases it, and an unexpected source-reader runtime failure opens a circuit until another source-change signal arrives. An unchanged active file is ignored.

Initial installation and framework handoff stabilize a reloadable source before commit so the active routing plan, watcher state, debounce, shutdown timeout, and internal diagnostics all come from one snapshot. If the source keeps changing through eight preparation attempts, installation fails explicitly instead of publishing a knowingly stale policy.

Levels, routes, processors, and outputs may be added or removed at runtime. File candidates reserve exclusive ownership during assembly but do not open, truncate, reconcile, or rotate the durable data file until the complete candidate has committed and accepts its first record. Flush and close never perform the first data-file open. Preparation may create missing parent directories and a `.logyard.lock` sidecar, but a discarded, rejected, failed, or activated-but-unused candidate cannot modify existing data-file contents. Two outputs in one candidate or one JVM cannot claim the same normalized path or two existing hard-link aliases of the same file. Cross-process hard-link alias detection is filesystem-dependent and is not guaranteed by pathname sidecar locks. An existing file output holds its path lock; changing that output's path identity, buffering, rotation, encoder, delivery, or other resource-owning settings at the same path is rejected and requires a process restart.

The `[runtime]` fields `watch`, `reload_debounce`, `shutdown_timeout`, and `internal_status` belong to the installation rather than an individual routing plan. Adapter-owned `context.mdc` capture policy has the same first-release boundary. An in-place reload that changes any of these settings is rejected atomically; apply them through an application/framework configuration handoff or a process restart. Logyard never reports a candidate as applied while retaining watcher or MDC capture policy from an older snapshot.

Applications can trigger the same decision directly:

```java
ReloadResult result = logyard.reloadNow();
```

Reload observer and diagnostic failures are isolated from the reload outcome. A recoverable logging-component failure is sent to emergency stderr; fatal VM failures are rethrown, and interrupted status is preserved.

## Extensions

The stable SPI supports context providers, event processors, text formatters, event encoders, outputs, and health contributors. Providers are discovered with `ServiceLoader`, receive a bounded immutable configuration, and create runtime-owned instances. Provider creation may occur for a candidate that is later rejected or superseded; construction must not irreversibly modify durable external state, and every provider-created closeable component must release its resources from `close()`. Logyard invokes providers outside its lifecycle and reload state locks. Recursive Logyard installation, reconfiguration, or shutdown from provider lifecycle callbacks is unsupported.

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

Provider names are explicit; duplicate names, unknown keys, missing required keys, and ambiguous providers fail during assembly. A provider should throw `IllegalArgumentException` for deterministically invalid candidate configuration and `UncheckedIOException` for a temporary I/O or resource condition; other provider failures are isolated and retried only with bounded watcher backoff.

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
| `logyard-spring-boot-starter` | Opinionated Boot starter without Logback |
| `logyard-spring-boot` | Early logging, lifecycle, level controls, Actuator, and native hints |
| `logyard-quarkus` | Quarkus runtime handler, configuration, lifecycle, and optional readiness |
| `logyard-quarkus-deployment` | Quarkus augmentation artifact selected by the runtime descriptor |
| `logyard-runtime` | Native runtime, bootstrap, configuration, console, and JSON |
| `logyard-jul` | JUL handler |
| `logyard-system-logger` | `System.LoggerFinder` provider |
| `logyard-api` | Native API and extension SPI |

The supported public surface is every package in `logyard-api`, `com.zsumz.logyard.runtime.bootstrap`, `com.zsumz.logyard.runtime.management`, `com.zsumz.logyard.jul`, the public Spring Boot integration packages, and `com.zsumz.logyard.quarkus.runtime.*`. Other packages are implementation details marked `@InternalApi`; do not import them. Every published JAR has a stable `Automatic-Module-Name`, source JAR, and Javadoc JAR.

All Logyard artifacts use the same version. Java 21 is the minimum runtime, SLF4J 2.x is supported, and no compatibility claim is made for SLF4J 1.x.

Published-shaped CI consumers certify plain SLF4J 2, Vert.x 5.1.5, Micronaut 4.10.9, Spring Boot 3.5.16 and 4.1.0, and Quarkus 3.37.3. Spring Boot verification covers executable JAR startup, MVC, WebFlux, Actuator present and absent, AOT generation, structured events, dynamic levels, JUL capture, context restart, and shutdown flush. Quarkus verification covers test mode, packaged JVM startup, dev-mode hot reload, complete JBoss record mapping, duplicate-handler prevention, SmallRye Health, and shutdown flush. Micronaut, Spring Boot 4, and Quarkus are also built and exercised as GraalVM 25 native images.

Compatibility checks cover the public API, runtime bootstrap and management packages, JUL bridge, Spring Boot integration, and Quarkus runtime integration. No documented framework surface is treated as implicitly stable without appearing in that check.

Allocation gates use observable sinks so accepted events escape JIT scalar replacement. They separately measure an intentionally elidable pipeline, real native and SLF4J ingress, fanout, and representative small, structured, and exception-bearing events. Management benchmarks enforce near-linear complete listings and configuration-size-independent point queries from 1,000 to 10,000 configured logger rules.

## Build and verify

The repository pins and bootstraps Zolt:

```shell
./scripts/bootstrap-zolt
export PATH="$HOME/.zolt/bin:$PATH"
zolt resolve
./scripts/ci
./scripts/package
./scripts/package-verify
./scripts/examples-verify
./scripts/examples-native-verify
```

`scripts/ci` runs architecture checks, strict Javadocs, dependency-free verification, and the complete Zolt test suite. The architecture check caps new production classes at 240 lines, prevents grandfathered large classes from growing beyond their recorded ceiling, caps tests and examples at 300 lines, and keeps reload state free of I/O and extension callbacks. Repository checks reject generated Python bytecode. `scripts/package` also runs the official Quarkus extension tests. `scripts/zolt-publication-check` combines Zolt's atomic workspace-family Central preflight with packaged-artifact verification. Packaged verification uses only produced JARs to check module names, sources, Javadocs, generated extension descriptors, `ServiceLoader` contracts, native logging, SLF4J, and `System.Logger`. CI runs on Linux, macOS, and Windows with Java 21, plus a forward-compatibility lane on Java 25.

Zolt remains the repository build model and owns the native `logyard-bom` plus the eleven Java library publications. `extensions/logyard-quarkus` is the single isolated Maven reactor because Quarkus requires its official extension descriptor and augmentation tooling. A small explicit BOM overlay adds those two Maven-built artifacts to the final fourteen-publication family; they still flow through the same Central bundle, checksums, signatures, source JARs, and Javadocs.

Tagged releases assemble one Maven Central layout with complete POM metadata, dependency declarations, detached PGP signatures, and MD5, SHA-1, and SHA-256 checksums. Zolt proves that its native workspace family is Central-ready; `scripts/release-verify --require-signatures` proves the complete hybrid bundle before the sole upload path, `scripts/central-publish`, sends it to the Portal.

Apache-2.0 licensed.
