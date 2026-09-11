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

## Native images

Spring and Quarkus include classpath resources named `logyard.toml` and `logyard-*.toml`, including nested paths such as `logging/logyard-prod.toml`.

Native-image support includes Micronaut, Spring Boot 4, and Quarkus with GraalVM 25.

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
| [Vert.x](examples/vertx) | Logging from a Vert.x application |
| [Micronaut](examples/micronaut) | Startup, structured events, and shutdown flush |
| [Spring Boot](examples/spring-boot) | MVC, WebFlux, Actuator, dynamic levels, JUL, and shutdown flush |
| [Quarkus](examples/quarkus) | JVM packaging, tests, redaction, and shutdown flush |

Run every example against freshly packaged Logyard artifacts:

```sh
./scripts/examples-verify
```

This builds `target/release-bundle`, then runs the Zolt examples through Smoque. Checks cover HTTP responses, structured fields, redaction, framework logging, and shutdown flushes.

Spring Boot covers both supported versions, MVC and WebFlux, Actuator present and absent, and external and default configuration. Quarkus covers tests and packaged JVM applications with Logyard enabled and disabled.

Verification covers JVM applications. Framework native images, Spring AOT, Quarkus dev mode, test profiles, and Logyard readiness integration are outside the pinned Zolt's supported coverage.

See [consumer setup](CONTRIBUTING.md#package-and-exercise-consumers) for prerequisites and reports.

Example configurations use `LOGYARD_EXAMPLE_OUTPUT` for temporary JSON files and may set `append = false`. Choose your own output path and retention settings when adapting them.
