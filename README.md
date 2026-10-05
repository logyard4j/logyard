# Logyard

Logyard is a logging backend for Java 21+. It writes readable console logs and structured JSON, with levels and routes set in TOML.

Use it through SLF4J 2, JUL, `System.Logger`, or the Java API. Spring Boot and Quarkus have their own integrations.

## Get started

Add `logyard-slf4j2` and remove your existing SLF4J provider, such as Logback.

<details open>
<summary>Zolt</summary>

```toml
[dependencies]
"com.logyard4j:logyard-slf4j2" = "0.1.0-rc.2"
```

</details>

<details>
<summary>Gradle Kotlin DSL</summary>

```kotlin
implementation("com.logyard4j:logyard-slf4j2:0.1.0-rc.2")
```

</details>

<details>
<summary>Maven</summary>

```xml
<dependency>
  <groupId>com.logyard4j</groupId>
  <artifactId>logyard-slf4j2</artifactId>
  <version>0.1.0-rc.2</version>
</dependency>
```

</details>

For [Spring Boot](INTEGRATIONS.md#spring-boot) or [Quarkus](INTEGRATIONS.md#quarkus), use the framework integration instead.

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

Keep using your SLF4J logger:

```java
import org.slf4j.LoggerFactory;

var log = LoggerFactory.getLogger("com.example.checkout");
log.atInfo()
        .addKeyValue("order.id", "ord-1042")
        .addKeyValue("amount", 42)
        .log("order accepted");
```

Console output:

```text
01:00:54.785 INFO  com.example.checkout               order accepted order.id=ord-1042 amount=42 thread=main
```

Add a [JSON file](CONFIGURATION.md#json-output), [request context](CONFIGURATION.md#context-and-redaction), or [config reload](CONFIGURATION.md#reload-and-shutdown) when you need it.

## More

| Guide | Start here for |
| --- | --- |
| [Configuration](CONFIGURATION.md) | Levels, outputs, context, and reload |
| [Integrations](INTEGRATIONS.md) | Frameworks, JDK logging, and the Java API |
| [Examples](INTEGRATIONS.md#examples) | Small working applications |
| [Runtime](RUNTIME.md) | Health, shutdown, and event limits |
| [Extensions](EXTENDING.md) | Custom filters and outputs |
| [Contributing](CONTRIBUTING.md) | Building and changing Logyard |

The current candidate is `0.1.0-rc.2`. Use the same version for all Logyard dependencies. See [release notes](RELEASE_NOTES.md) and [framework versions](framework-versions.toml).

[Releasing](RELEASING.md) · [Security](SECURITY.md) · [Apache-2.0](LICENSE)
