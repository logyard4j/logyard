# Integrations

[Logyard](README.md) · [Configuration](CONFIGURATION.md)

Use Java 21+ and the same version for every Logyard dependency.

## Dependencies

Choose the artifact for your application:

| Application | Artifact |
| --- | --- |
| SLF4J 2, Vert.x, Micronaut | `logyard-slf4j2` |
| Spring Boot | `logyard-spring-boot-starter` |
| Quarkus | `logyard-quarkus` |
| Java API | `logyard-runtime` |
| JUL | `logyard-jul` |
| System.Logger | `logyard-system-logger` |
| OpenTelemetry | `logyard-opentelemetry` |
| Tests | `logyard-test` |

These examples use `logyard-runtime`. Replace the artifact name with your choice above.

<details>
<summary>Zolt</summary>

```toml
[dependencies]
"com.logyard4j:logyard-runtime" = "0.1.0-rc.2"
```

</details>

<details>
<summary>Gradle Kotlin DSL</summary>

```kotlin
implementation("com.logyard4j:logyard-runtime:0.1.0-rc.2")
```

</details>

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>com.logyard4j</groupId>
  <artifactId>logyard-runtime</artifactId>
  <version>0.1.0-rc.2</version>
</dependency>
```

</details>

## SLF4J, Vert.x, and Micronaut

Add `logyard-slf4j2` and remove other SLF4J providers. Keep using `LoggerFactory`, fluent logging, and MDC. The adapter starts Logyard lazily.

Lombok's `@Slf4j` and Kafka Streams use the same provider; see their examples below.

To capture MDC fields, list them in `logyard.toml`:

```toml
[context]
mdc = ["request.id", "tenant.id"]
```

If provider selection goes wrong, check your application's deployed classpath:

```sh
zcheck --root /path/to/logyard run provider -- --classpath "$(zolt classpath runtime)"
```

For a Spring Boot executable JAR, use `--jar path/to/application.jar`.

## Spring Boot

Add `logyard-spring-boot-starter`. Exclude `spring-boot-starter-logging` from each dependency that brings it in, including web and Actuator starters.

<details>
<summary>Zolt exclusion</summary>

```toml
[dependencies]
"org.springframework.boot:spring-boot-starter-web" = { managed = true, exclude = ["org.springframework.boot:spring-boot-starter-logging"] }
```

</details>

<details>
<summary>Gradle Kotlin DSL exclusion</summary>

```kotlin
implementation("org.springframework.boot:spring-boot-starter-web") {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}
```

</details>

<details>
<summary>Maven exclusion</summary>

```xml
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
```

</details>

These examples use Boot 3's MVC starter. For Boot 4, use `spring-boot-starter-webmvc`. Keep your application's Boot dependency management.

Put `logyard.toml` in `src/main/resources`. To use another source, set `logyard.config` in Boot properties; `logging.config` is an alias. Set `logyard.required=true` to require a file.

Boot manages startup and shutdown. The integration also captures JUL and exposes runtime health and logger levels through Actuator when present.

To opt out before Boot starts, use `-Dlogyard.enabled=false` or `LOGYARD_ENABLED=false`.

## Quarkus

Add `logyard-quarkus` and keep using Quarkus's logging API. In `application.properties`:

```properties
quarkus.log.console.enabled=false
quarkus.logyard.config=classpath:logyard.toml
```

This avoids duplicate console output. Use `quarkus.logyard.required=true` to require the config, or `quarkus.logyard.enabled=false` to disable Logyard.

Quarkus filters events before Logyard sees them. Preserve levels you may want to enable later:

```properties
quarkus.log.category."com.example.checkout".min-level=DEBUG
```

Use the packaged JVM application. Native images, dev and test profiles, and SmallRye readiness are not supported yet.

## Java API

Add `logyard-runtime` and keep the runtime open for your application's lifetime:

```java
import com.logyard4j.logyard.runtime.bootstrap.LogyardBootstrap;

try (var logyard = LogyardBootstrap.start()) {
    var log = logyard.runtime().logger("com.example.checkout");
    log.atInfo().event("order.accepted").add("order.id", "ord-1042")
            .log("order accepted");
}
```

Use `addLazy("plan", this::expensivePlan)` or `argumentLazy(supplier)` for work that should only run when logging is enabled.

For request context:

```java
import com.logyard4j.logyard.api.context.LogContext;

try (var scope = LogContext.push("request.id", "req-42")) {
    log.atInfo().log("order accepted");
}
```

Use `log.with("component", "orders")` for fields shared by a logger. Event fields override logger fields, which override scoped fields.

Wrap an executor with `LogContext.wrap(executor)` to carry the caller's context into each submitted task. SLF4J MDC and OpenTelemetry context need their own propagation.

See the [lifecycle example](examples/lifecycle) for a full stop and restart.

## JDK logging

### JUL

Add `logyard-jul` and create `logging.properties`:

```properties
handlers=com.logyard4j.logyard.jul.LogyardHandler
.level=ALL
```

Load it with `-Djava.util.logging.config.file=/path/to/logging.properties`. `ALL` lets Logyard's TOML levels decide what to keep.

### System.Logger

Add `logyard-system-logger`. The provider is discovered automatically:

```java
System.getLogger("com.example.checkout")
        .log(System.Logger.Level.INFO, "order accepted");
```

## OpenTelemetry

Add `logyard-opentelemetry` alongside your logging integration. It captures active trace IDs and selected baggage:

```toml
[context]
trace = true
baggage = ["tenant.id"]
```

Your application or instrumentation must propagate OpenTelemetry context between threads.

To forward logs to your application's Logs SDK, install it before starting Logyard:

```java
import com.logyard4j.logyard.opentelemetry.LogyardOpenTelemetry;

LogyardOpenTelemetry.install(applicationSdk);
```

Then add an output and route logs to it:

```toml
[outputs.telemetry]
type = "custom"
provider = "otel"

[loggers]
root = { level = "info", outputs = ["telemetry"] }
```

Reuse the same SDK across runtime restarts. Configure its resource and exporters in your application, and close Logyard before flushing and shutting down the SDK.

## Testing

Add `logyard-test` as a test dependency: Zolt's `[dependencies.test]`, Gradle `testImplementation`, or Maven `<scope>test</scope>`.

```java
import com.logyard4j.logyard.api.Level;
import com.logyard4j.logyard.test.LogyardTestKit;

try (var kit = LogyardTestKit.isolated()) {
    kit.logger("checkout").atInfo().add("order.id", 7L).log("order accepted");
    kit.events().expect().level(Level.INFO).attribute("order.id", 7L).assertCount(1);
    kit.events().expect().level(Level.ERROR).assertNone();
}
```

Inject the kit's logger or runtime into the code under test. Each kit is independent; static SLF4J calls still use the process runtime.

The kit holds 1,024 events by default. Use `isolated(capacity)` to change the limit. Overflow makes assertions fail until `events().clear()`. Wait for application tasks to finish before asserting.

## Manage dependency versions

When using several modules, import `com.logyard4j:logyard-bom:0.1.0-rc.2` and omit individual Logyard versions.

<details>
<summary>Zolt</summary>

```toml
[platforms]
"com.logyard4j:logyard-bom" = "0.1.0-rc.2"

[dependencies]
"com.logyard4j:logyard-slf4j2" = { managed = true }
```

</details>

<details>
<summary>Gradle Kotlin DSL</summary>

```kotlin
implementation(platform("com.logyard4j:logyard-bom:0.1.0-rc.2"))
implementation("com.logyard4j:logyard-slf4j2")
```

</details>

<details>
<summary>Maven</summary>

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.logyard4j</groupId>
      <artifactId>logyard-bom</artifactId>
      <version>0.1.0-rc.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

</details>

## Examples

| Example | Shows |
| --- | --- |
| [SLF4J](examples/slf4j) | Structured logging |
| [Lombok](examples/lombok) | Logging with `@Slf4j` |
| [Kafka Streams](examples/kafka-streams) | Processor logging and topology tests |
| [Spring Boot](examples/spring-boot) | Web requests, Actuator, and JUL |
| [Quarkus](examples/quarkus) | Packaged JVM application |
| [Vert.x](examples/vertx) | Logging from Vert.x |
| [Micronaut](examples/micronaut) | Startup and shutdown |
| [OpenTelemetry](examples/opentelemetry) | Trace context and Logs SDK export |
| [Test kit](examples/test-kit) | Assertions on captured logs |
| [Lifecycle](examples/lifecycle) | Cached loggers across restarts |
| [Migration](examples/migration) | Moving from Logback or Log4j 2 |

Run them with `zcheck run examples`; see [build setup](CONTRIBUTING.md#run-the-examples).

Spring AOT and native images are not supported yet. Tested framework versions are listed in [framework-versions.toml](framework-versions.toml).
