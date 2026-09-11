# Logyard

**Structured logging for Java 21+.**

Readable console logs. Structured JSON. One runtime for native Java, SLF4J 2, JUL, and `System.Logger`.

[Get started](#get-started) · [Configuration](CONFIGURATION.md) · [Integrations](INTEGRATIONS.md) · [Examples](INTEGRATIONS.md#examples)

## Why Logyard

- **Keep your logging API.** Use SLF4J, a JDK logger, or Logyard's native API.
- **Capture once, send to many outputs.** Every output receives the same immutable event.
- **Control your logs with TOML.** Set levels, routes, redaction, formats, and delivery in one file.
- **Keep work bounded.** Event capture has explicit limits; asynchronous outputs have separate queues.
- **Reload safely.** Apply valid configurations atomically while rejected changes leave the current runtime active.
- **See what is happening.** Inspect output health, queue depth, drops, and effective logger routes.

## Get started

### 1. Add Logyard

For an SLF4J 2 application, add the provider using your build tool:

<details open>
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

Keep one SLF4J provider on the classpath. Remove an existing provider such as Logback when adding Logyard.

Using [Spring Boot](INTEGRATIONS.md#spring-boot), [Quarkus](INTEGRATIONS.md#quarkus), or the [native API](INTEGRATIONS.md#native-java)? Start with that integration instead.

### 2. Configure

Create `src/main/resources/logyard.toml`:

```toml
schema = 1

[service]
name = "checkout"

[loggers]
root = { level = "info", outputs = ["console"] }

[outputs.console]
type = "console"
stream = "stderr"
```

That is enough for an INFO console logger. Add [JSON output](CONFIGURATION.md#json-output), [redaction](CONFIGURATION.md#context-and-redaction), or [file watching](CONFIGURATION.md#reload-and-shutdown) as needed.

Without a configuration file, Logyard uses an INFO stderr console and a 256-event asynchronous queue. **When a queue is full, new events are dropped at every severity, including ERROR.** Drops are counted in health; [delivery policies](CONFIGURATION.md#delivery-and-overflow) let you choose another behavior.

### 3. Log

Use the SLF4J API you already know:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

private static final Logger LOG = LoggerFactory.getLogger("com.example.checkout");

LOG.atInfo()
        .addKeyValue("order.id", "ord-1042")
        .addKeyValue("amount", 42)
        .log("order accepted");
```

On the console:

```text
01:00:54.785 INFO  com.example.checkout               order accepted order.id=ord-1042 amount=42 thread=main
```

Messages, structured fields, context, and exceptions travel together. Disabled levels skip capture; use suppliers to defer expensive argument computation.

## Choose your output

| Output | What you get |
| --- | --- |
| Console | Readable text, templates, color themes, and compact or full exceptions |
| JSON stream | Newline-delimited JSON on stdout or stderr |
| JSON file | Buffered writes, timed flushes, size or interval rotation, retention, and optional gzip |
| OpenTelemetry | Forward structured records to your application’s Logs SDK |
| Custom | Your own transport through the [extension SPI](EXTENDING.md) |

JSON supports `logyard`, `ecs`, and `compact` profiles, plus field renaming and attribute transforms. Route one logger to several outputs in the same configuration.

## Fits your application

| Application | Dependency |
| --- | --- |
| SLF4J 2, Vert.x, Micronaut | [logyard-slf4j2](INTEGRATIONS.md#slf4j-vertx-and-micronaut) |
| Spring Boot | [logyard-spring-boot-starter](INTEGRATIONS.md#spring-boot) |
| Quarkus | [logyard-quarkus](INTEGRATIONS.md#quarkus) |
| Tests | [logyard-test](INTEGRATIONS.md#testing) |
| Native Java | [logyard-runtime](INTEGRATIONS.md#native-java) |
| JUL | [logyard-jul](INTEGRATIONS.md#jul) |
| `System.Logger` | [logyard-system-logger](INTEGRATIONS.md#systemlogger) |
| OpenTelemetry | [logyard-opentelemetry](INTEGRATIONS.md#opentelemetry) |

JVM examples cover plain SLF4J 2, Vert.x 5.1.5, Micronaut 4.10.9, Spring Boot 3.5.16 and 4.1.0, and Quarkus 3.37.3. Optional OpenTelemetry integration adds trace correlation, allowlisted baggage, and forwarding to your Logs SDK.

Java 21 is the minimum runtime. Use the same version for all Logyard artifacts. SLF4J 1.x is unsupported.

## Explore

| Guide | Find |
| --- | --- |
| [Configuration](CONFIGURATION.md) | Copyable recipes, defaults, routing, delivery, and reload settings |
| [Integrations](INTEGRATIONS.md) | Framework setup, native Java, JDK adapters, and dependency management |
| [Examples](INTEGRATIONS.md#examples) | Small applications with working build and configuration files |
| [Runtime behavior](RUNTIME.md) | Lifecycle, health, reload boundaries, and event limits |
| [Extensions](EXTENDING.md) | Custom processors, formatters, encoders, and outputs |

[Contributing](CONTRIBUTING.md) · [Release guide](RELEASING.md) · [Apache-2.0 license](LICENSE)
