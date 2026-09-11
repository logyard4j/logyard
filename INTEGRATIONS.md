# Integrations

[← Logyard](README.md) · [Spring Boot](#spring-boot) · [Quarkus](#quarkus) · [Native Java](#native-java) · [JDK logging](#jdk-logging)

All integrations share one process-wide runtime and the same [TOML configuration](CONFIGURATION.md). Use Java 21+ and the same version for every Logyard dependency.

Choose your build tool below and merge the snippet into your existing build file. Gradle examples use Kotlin DSL.

## SLF4J, Vert.x, and Micronaut

Add the SLF4J provider. It includes the runtime, TOML configuration, console output, and JSON output.

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencies>
  <dependency>
    <groupId>com.zsumz.logyard</groupId>
    <artifactId>logyard-slf4j2</artifactId>
    <version>0.1.0-rc.1</version>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation("com.zsumz.logyard:logyard-slf4j2:0.1.0-rc.1")
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[dependencies]
"com.zsumz.logyard:logyard-slf4j2" = "0.1.0-rc.1"
```

</details>

Keep Logyard as the only SLF4J provider.

Continue using `LoggerFactory`, fluent SLF4J logging, and MDC. Logyard starts lazily when the adapter first needs it.

MDC capture is opt-in through `context.mdc`.

| MDC boundary | Behavior |
| --- | --- |
| Map capacity | 128 entries per thread; excess entries are dropped |
| Key length | Keys over 256 characters are dropped without collisions |
| Bulk import | Inspects at most 128 entries |
| Capture loss | `logyard.capture.truncated`; reset by `clear()` or `setContextMap()` |
| Invalid operations | Null keys and deque overflow throw; push/pop pairs stay balanced |

See the [SLF4J](examples/slf4j), [Vert.x](examples/vertx), and [Micronaut](examples/micronaut) examples.

## Spring Boot

Add the starter and exclude Boot's default logging starter. These snippets target an existing Boot 3.5 MVC application:

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencies>
  <dependency>
    <groupId>com.zsumz.logyard</groupId>
    <artifactId>logyard-spring-boot-starter</artifactId>
    <version>0.1.0-rc.1</version>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <exclusions>
      <exclusion>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-logging</artifactId>
      </exclusion>
    </exclusions>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation("com.zsumz.logyard:logyard-spring-boot-starter:0.1.0-rc.1")
    implementation("org.springframework.boot:spring-boot-starter-web") {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    }
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[platforms]
"org.springframework.boot:spring-boot-dependencies" = "3.5.16"

[dependencies]
"com.zsumz.logyard:logyard-spring-boot-starter" = "0.1.0-rc.1"
"org.springframework.boot:spring-boot-starter-web" = { exclusions = [
    { group = "org.springframework.boot", artifact = "spring-boot-starter-logging" },
] }
```

</details>

For Boot 4.1 MVC, use `spring-boot-starter-webmvc` and update the Boot platform to `4.1.0`. Apply the logging exclusion to WebFlux, Actuator, and every other dependency that brings Logback.

Use `logyard.toml` for logging policy. Boot properties select the source:

| Setting | Purpose |
| --- | --- |
| `logyard.config` | Filesystem or classpath TOML source |
| `logyard.required=true` | Fail if configuration is missing |
| `logging.config` | Alias for `logyard.config`; conflicting values fail |
| `-Dlogyard.enabled=false` or `LOGYARD_ENABLED=false` | Opt out before Boot chooses its logging system |

The integration handles early logging, lifecycle, dynamic levels, JUL capture, Actuator, and native-image resource hints. Startup reports missing or competing SLF4J providers with remediation.

Application contexts, DevTools restarts, and tests in one JVM share runtime ownership. Closing one context releases its lease. Use separate JVMs for parallel tests that need independent logging configurations.

See the [Spring Boot example](examples/spring-boot).

## Quarkus

Add the runtime extension to your Quarkus application; Quarkus selects its deployment companion automatically:

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencies>
  <dependency>
    <groupId>com.zsumz.logyard</groupId>
    <artifactId>logyard-quarkus</artifactId>
    <version>0.1.0-rc.1</version>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation("com.zsumz.logyard:logyard-quarkus:0.1.0-rc.1")
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[dependencies]
"com.zsumz.logyard:logyard-quarkus" = "0.1.0-rc.1"
```

</details>

Keep the Quarkus/JBoss logging API. In `application.properties`, disable the built-in console handler to avoid duplicate output:

```properties
quarkus.log.console.enabled=false
quarkus.logyard.config=classpath:logyard.toml
```

| Setting | Purpose |
| --- | --- |
| `quarkus.logyard.config` | Filesystem or classpath TOML source |
| `quarkus.logyard.required=true` | Fail if configuration is missing |
| `quarkus.logyard.enabled=false` | Disable handler installation |

With `quarkus-smallrye-health`, Logyard adds a `logyard` readiness check without taking another runtime lease. This requires extension capability handling, which the pinned Zolt does not yet provide.

Quarkus build-time minimum levels apply before Logyard sees an event. Preserve any level you want to enable later:

```properties
quarkus.log.category."com.example.checkout".min-level=DEBUG
```

See the [Quarkus example](examples/quarkus).

## Native Java

Add the native runtime:

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencies>
  <dependency>
    <groupId>com.zsumz.logyard</groupId>
    <artifactId>logyard-runtime</artifactId>
    <version>0.1.0-rc.1</version>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation("com.zsumz.logyard:logyard-runtime:0.1.0-rc.1")
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[dependencies]
"com.zsumz.logyard:logyard-runtime" = "0.1.0-rc.1"
```

</details>

Start the runtime for the lifetime of your application:

```java
import com.zsumz.logyard.api.LogyardLogger;
import com.zsumz.logyard.runtime.bootstrap.LogyardBootstrap;
import com.zsumz.logyard.runtime.bootstrap.RuntimeBundle;

try (RuntimeBundle logyard = LogyardBootstrap.start()) {
    LogyardLogger log = logyard.runtime().logger("com.example.checkout");

    log.atInfo()
            .event("order.accepted")
            .add("order.id", "ord-1042")
            .log("order accepted");
}
```

Use suppliers for expensive attributes:

```java
log.atDebug()
        .addLazy("plan", this::expensivePlan)
        .log("selected execution plan");
```

`expensivePlan()` runs only when an enabled event enters publication. Closing the final runtime owner shuts down watching, delivery, and outputs.

Use `argumentLazy(supplier)` for lazy message arguments and `addAll(attributes)` for a captured attribute set. Two-argument and varargs convenience calls extract a trailing exception as the cause, as SLF4J does. Use a builder argument to render an exception as a value.

Attach request context with a scope, and stable component context with `with`:

```java
import com.zsumz.logyard.api.context.LogContext;

var orders = log.with("component", "orders");
try (var scope = LogContext.push("request.id", "req-42")) {
    orders.atInfo().add("order.id", "ord-1042").log("order accepted");
}
```

Explicit event fields override `with` fields, which override scoped fields. Scope values are captured on the caller thread. Use `executor.execute(LogContext.wrap(task))` to propagate a snapshot to another thread; the wrapper restores the worker's previous context even when the task fails.

Applications can pass an explicit source to `LogyardBootstrap.start(source)`. Framework integrations acquire their own lease:

```java
import com.zsumz.logyard.runtime.bootstrap.LogyardConfigurationSource;
import com.zsumz.logyard.runtime.bootstrap.RuntimeOwner;

LogyardConfigurationSource source = LogyardConfigurationSource.classpath(
        applicationClassLoader, "logging/logyard.toml", applicationDirectory);

try (RuntimeBundle logyard = LogyardBootstrap.acquire(RuntimeOwner.FRAMEWORK, source)) {
    logyard.runtime().logger("com.example.framework").atInfo().log("framework started");
    // Run the framework while holding this lease.
}
```

## JDK logging

### JUL

Add the JUL adapter:

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencies>
  <dependency>
    <groupId>com.zsumz.logyard</groupId>
    <artifactId>logyard-jul</artifactId>
    <version>0.1.0-rc.1</version>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation("com.zsumz.logyard:logyard-jul:0.1.0-rc.1")
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[dependencies]
"com.zsumz.logyard:logyard-jul" = "0.1.0-rc.1"
```

</details>

Select the handler in `logging.properties`:

```properties
handlers=com.zsumz.logyard.jul.LogyardHandler
.level=ALL
```

Load it with `-Djava.util.logging.config.file=/path/to/logging.properties`. JUL's own levels filter first; `ALL` lets TOML choose the effective threshold.

### System.Logger

Add the provider; its `System.LoggerFinder` is discovered automatically:

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencies>
  <dependency>
    <groupId>com.zsumz.logyard</groupId>
    <artifactId>logyard-system-logger</artifactId>
    <version>0.1.0-rc.1</version>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation("com.zsumz.logyard:logyard-system-logger:0.1.0-rc.1")
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[dependencies]
"com.zsumz.logyard:logyard-system-logger" = "0.1.0-rc.1"
```

</details>

## OpenTelemetry

Add the optional context module alongside your Logyard integration. It uses the OpenTelemetry API; it does not install an SDK or exporter.

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencies>
  <dependency>
    <groupId>com.zsumz.logyard</groupId>
    <artifactId>logyard-opentelemetry</artifactId>
    <version>0.1.0-rc.1</version>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation("com.zsumz.logyard:logyard-opentelemetry:0.1.0-rc.1")
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[dependencies]
"com.zsumz.logyard:logyard-opentelemetry" = "0.1.0-rc.1"
```

</details>

```toml
[context]
trace = true
baggage = ["tenant.id"]
```

Logyard captures valid active trace IDs, span IDs, flags, and only the named baggage keys on the publishing thread. Unsampled spans still correlate. Baggage keys are literal: `"*"` does not request every key.

Logyard and compact JSON keep `trace_id`, `span_id`, `trace_flags`, and `baggage.tenant.id` in their attribute object. ECS projects trace identity to `trace.id`, `span.id`, and `logyard.trace_flags`; baggage becomes a label.

Your application or instrumentation must propagate context across executors and framework callbacks. Logyard does not create spans or propagate context automatically. See the [executor example](examples/opentelemetry) and the Spring Boot example's [`/trace` endpoint](examples/spring-boot/src/main/java/com/zsumz/logyard/examples/springboot/TraceExampleController.java).

## Native images

Spring and Quarkus include classpath resources named `logyard.toml` and `logyard-*.toml`, including nested paths such as `logging/logyard-prod.toml`.

Framework metadata is present, but native-image execution is outside the pinned Zolt verification scope. The qualified consumer examples are JVM applications.

## Manage dependency versions

Import the BOM when using several Logyard artifacts, then declare dependencies without individual versions:

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.zsumz.logyard</groupId>
      <artifactId>logyard-bom</artifactId>
      <version>0.1.0-rc.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>com.zsumz.logyard</groupId>
    <artifactId>logyard-slf4j2</artifactId>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation(platform("com.zsumz.logyard:logyard-bom:0.1.0-rc.1"))
    implementation("com.zsumz.logyard:logyard-slf4j2")
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[platforms]
"com.zsumz.logyard:logyard-bom" = "0.1.0-rc.1"

[dependencies]
"com.zsumz.logyard:logyard-slf4j2" = {}
```

</details>

The BOM manages the complete fourteen-artifact family, including both Quarkus artifacts.

## Examples

Each example includes a `zolt.toml` build and a Logyard configuration:

| Application | Shows |
| --- | --- |
| [SLF4J](examples/slf4j) | Fluent structured logging |
| [Managed lifecycle](examples/lifecycle) | Cached SLF4J, JUL, and System.Logger instances across restart, level changes, formatting, and close-time drain |
| [OpenTelemetry](examples/opentelemetry) | Active trace identity, allowlisted baggage, explicit executor propagation |
| [Vert.x](examples/vertx) | Logging from a Vert.x application |
| [Micronaut](examples/micronaut) | Startup, structured events, and shutdown flush |
| [Spring Boot](examples/spring-boot) | MVC, WebFlux, Actuator, dynamic levels, JUL, and shutdown flush |
| [Quarkus](examples/quarkus) | JVM packaging, tests, redaction, and shutdown flush |

Run every example against freshly packaged Logyard artifacts:

```sh
./scripts/examples-verify
```

This builds `target/release-bundle`, then runs the Zolt examples through Smoque. Checks cover HTTP responses, structured fields, redaction, framework logging, trace correlation, and shutdown flushes.

Spring Boot covers both supported versions, MVC and WebFlux, Actuator present and absent, external and default configuration, propagated trace context, and rejection of competing SLF4J providers. Quarkus covers tests and packaged JVM applications with Logyard enabled and disabled.

Verification covers JVM applications. Framework native images, Spring AOT, Quarkus dev mode, test profiles, and Logyard readiness integration are outside the pinned Zolt's supported coverage.

See [consumer setup](CONTRIBUTING.md#package-and-exercise-consumers) for prerequisites and reports.

Example configurations use `LOGYARD_EXAMPLE_OUTPUT` for temporary JSON files and may set `append = false`. Choose your own output path and retention settings when adapting them.
