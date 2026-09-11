# Integrations

[← Logyard](README.md) · [Spring Boot](#spring-boot) · [Quarkus](#quarkus) · [Native Java](#native-java) · [JDK logging](#jdk-logging)

All integrations share one process-wide runtime and the same [TOML configuration](CONFIGURATION.md). Use Java 21+ and the same version for every Logyard dependency.

## SLF4J, Vert.x, and Micronaut

Add `com.zsumz.logyard:logyard-slf4j2:0.1.0-rc.1` and keep it as the only SLF4J provider. The dependency includes the runtime, TOML configuration, console output, and JSON output.

Continue using `LoggerFactory`, fluent SLF4J logging, and MDC. Logyard starts lazily when the adapter first needs it.

See the [SLF4J](examples/slf4j), [Vert.x](examples/vertx), and [Micronaut](examples/micronaut) examples.

## Spring Boot

Add the starter:

```xml
<dependency>
  <groupId>com.zsumz.logyard</groupId>
  <artifactId>logyard-spring-boot-starter</artifactId>
  <version>0.1.0-rc.1</version>
</dependency>
```

Exclude Boot's default logging starter from every dependency that brings it. For Boot 3.5 MVC:

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

For Boot 4.1 MVC, use `spring-boot-starter-webmvc`. Apply the same exclusion to WebFlux, Actuator, and any other starter that brings Logback.

With Gradle Kotlin DSL:

```kotlin
implementation("com.zsumz.logyard:logyard-spring-boot-starter:0.1.0-rc.1")
implementation("org.springframework.boot:spring-boot-starter-web") {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
}
```

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

Add the runtime extension; Quarkus selects its deployment companion automatically:

```xml
<dependency>
  <groupId>com.zsumz.logyard</groupId>
  <artifactId>logyard-quarkus</artifactId>
  <version>0.1.0-rc.1</version>
</dependency>
```

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

With `quarkus-smallrye-health`, Logyard adds a `logyard` readiness check without taking another runtime lease.

Quarkus build-time minimum levels apply before Logyard sees an event. Preserve any level you want to enable later:

```properties
quarkus.log.category."com.example.checkout".min-level=DEBUG
```

See the [Quarkus example](examples/quarkus).

## Native Java

Add `com.zsumz.logyard:logyard-runtime:0.1.0-rc.1`. Start the runtime for the lifetime of your application:

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
        .add("plan", this::expensivePlan)
        .log("selected execution plan");
```

`expensivePlan()` runs only when an enabled event enters publication. Closing the final runtime owner shuts down watching, delivery, and outputs.

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

| API | Setup |
| --- | --- |
| JUL | Add `logyard-jul` and install `com.zsumz.logyard.jul.LogyardHandler` |
| `System.Logger` | Add `logyard-system-logger`; its `System.LoggerFinder` is discovered automatically |

For standalone JUL applications, select the handler in `logging.properties`:

```properties
handlers=com.zsumz.logyard.jul.LogyardHandler
.level=ALL
```

Load it with `-Djava.util.logging.config.file=/path/to/logging.properties`. JUL's own levels filter first; `ALL` lets TOML choose the effective threshold.

## Native images

Spring and Quarkus include classpath resources named `logyard.toml` and `logyard-*.toml`, including nested paths such as `logging/logyard-prod.toml`.

Native-image support includes Micronaut, Spring Boot 4, and Quarkus with GraalVM 25.

## Manage dependency versions

Import the BOM when using several Logyard artifacts:

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
```

Then omit versions from individual Logyard dependencies. The BOM covers the complete fourteen-artifact family, including both Quarkus artifacts.

For Gradle, use `implementation(platform("com.zsumz.logyard:logyard-bom:0.1.0-rc.1"))`.

## Examples

Each example includes a Maven build and TOML configuration:

| Application | Shows |
| --- | --- |
| [SLF4J](examples/slf4j) | Fluent structured logging |
| [Vert.x](examples/vertx) | Logging from a Vert.x application |
| [Micronaut](examples/micronaut) | Startup, structured events, and native images |
| [Spring Boot](examples/spring-boot) | MVC, WebFlux, Actuator, dynamic levels, JUL, and shutdown flush |
| [Quarkus](examples/quarkus) | JVM/test/dev modes, readiness, hot reload, and native images |

Use the [consumer commands](CONTRIBUTING.md#package-and-exercise-consumers) to build and run them. Their POMs resolve Logyard from `target/release-bundle` by default; `logyard.repository` selects another Maven repository.

Example configurations use `LOGYARD_EXAMPLE_OUTPUT` for temporary JSON files and may set `append = false`. Choose your own output path and retention settings when adapting them.
