# Extensions

[← Logyard](README.md) · [Configuration](CONFIGURATION.md) · [Runtime behavior](RUNTIME.md)

Add `logyard-api` to your extension project:

<details>
<summary>Maven (pom.xml)</summary>

```xml
<dependencies>
  <dependency>
    <groupId>com.logyard4j</groupId>
    <artifactId>logyard-api</artifactId>
    <version>0.1.0-rc.2</version>
  </dependency>
</dependencies>
```

</details>

<details>
<summary>Gradle (build.gradle.kts)</summary>

```kotlin
dependencies {
    implementation("com.logyard4j:logyard-api:0.1.0-rc.2")
}
```

</details>

<details>
<summary>Zolt (zolt.toml)</summary>

```toml
[dependencies]
"com.logyard4j:logyard-api" = "0.1.0-rc.2"
```

</details>

Providers are discovered with `ServiceLoader` and create runtime-owned instances from bounded, immutable configuration.

| Extension | Purpose |
| --- | --- |
| Context provider | Capture application context |
| Event processor | Enrich or filter events |
| Text formatter | Render console text |
| Event encoder | Encode an event for transport |
| Output | Deliver events to a destination |
| Health contributor | Report component health |

## Add an enricher

This provider adds a configured tenant to every event on its route:

```java
package com.example.logging;

import com.logyard4j.logyard.api.event.AttributeSet;
import com.logyard4j.logyard.api.spi.config.ProviderConfiguration;
import com.logyard4j.logyard.api.spi.config.ProviderConfigurationSpec;
import com.logyard4j.logyard.api.spi.processing.EventProcessor;
import com.logyard4j.logyard.api.spi.processing.EventProcessorKind;
import com.logyard4j.logyard.api.spi.processing.EventProcessorProvider;
import java.util.Set;

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

Create this file under `src/main/resources`:

```text
META-INF/services/com.logyard4j.logyard.api.spi.processing.EventProcessorProvider
```

Its content is the provider's fully qualified class name:

```text
com.example.logging.TenantEnricherProvider
```

Then define the enricher and attach it to a route alongside your existing console output:

```toml
[enrichers.tenant]
provider = "tenant"

[enrichers.tenant.config]
name = "north"

[loggers]
root = { level = "info", outputs = ["console"], enrich = ["tenant"] }
```

Custom filters, formatters, encoders, and outputs use `type = "custom"`, a `provider` name, and a `config` table. Provider names must be unambiguous; duplicate names, unknown keys, and missing required keys fail assembly.

## Provider contract

- **Prepare without irreversible effects.** A candidate may be rejected or superseded after provider creation. Construction must not irreversibly modify durable external state.
- **Release resources in `close()`.** Every provider-created closeable component is owned by the runtime plan.
- **Handle concurrency.** Synchronous outputs may share a custom encoder concurrently. Make it thread-safe and allow reentrant logging callbacks; only the built-in `JsonEncoder` is synchronized by Logyard.
- **Keep lifecycle calls separate.** Providers run outside lifecycle and reload state locks. Recursive installation, reconfiguration, or shutdown from provider lifecycle callbacks is unsupported.

| Failure | Throw |
| --- | --- |
| Deterministically invalid candidate configuration | `IllegalArgumentException` |
| Temporary I/O or resource condition | `UncheckedIOException` |

Other provider failures are isolated and retried with bounded watcher backoff. See [reload behavior](RUNTIME.md#retry-behavior).

## Supported API

Logyard's Java packages use `com.logyard4j.logyard.*`. Maven coordinates use the `com.logyard4j` group, for example `com.logyard4j:logyard-api`.

Earlier RC checkouts used `com.logyard4j.*`. Update imports, fully qualified class names, `META-INF/services` filenames and contents, and module references to the new prefix, then rebuild applications and extensions together. This pre-stable rename changes source and binary names.

See the [namespace migration notes](RELEASE_NOTES.md#java-namespace-migration) for consumer and custom-provider changes, including reflective configuration.

The [supported API manifest](supported-api.toml) lists the supported packages and types across the native API, runtime bootstrap and management, JUL, Spring Boot, Quarkus, OpenTelemetry, and the test kit.

Declarations outside that manifest and declarations marked `@InternalApi` are implementation details. Use the public SPI when building extensions. Published JARs include stable `Automatic-Module-Name` values, source JARs, and Javadoc JARs.
