# Extensions

[Logyard](README.md) · [Configuration](CONFIGURATION.md)

Add `logyard-api` using the [dependency setup](INTEGRATIONS.md#dependencies). Providers are discovered through Java's `ServiceLoader`.

| Provider | Use |
| --- | --- |
| `ContextProvider` | Capture application context |
| `EventProcessorProvider` | Add fields or filter events |
| `TextFormatterProvider` | Format console text |
| `EventEncoderProvider` | Encode an event |
| `OutputProvider` | Send events to a destination |

## Add an enricher

This provider adds a tenant field:

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

Create `src/main/resources/META-INF/services/com.logyard4j.logyard.api.spi.processing.EventProcessorProvider` containing:

```text
com.example.logging.TenantEnricherProvider
```

Add it to `logyard.toml`, alongside your console output:

```toml
[enrichers.tenant]
provider = "tenant"

[enrichers.tenant.config]
name = "north"

[loggers]
root = { level = "info", outputs = ["console"], enrich = ["tenant"] }
```

Custom filters, formatters, encoders, and outputs use `type = "custom"`, a `provider` name, and a `config` table. Provider names must be unique.

## Writing a provider

A reload can create your provider and then reject the candidate. Keep construction reversible.

The runtime closes output sinks when their plan retires. Other provider types have no managed close callback, so they should not own resources that need cleanup.

Make custom encoders thread-safe. Providers may log through callbacks, but must not install, reconfigure, or shut down the runtime from lifecycle callbacks.

Throw `IllegalArgumentException` for invalid configuration and `UncheckedIOException` for temporary I/O failures. The watcher retries temporary failures.

Use the packages and types listed in [supported-api.toml](supported-api.toml). Declarations marked `@InternalApi` are implementation details. See [release notes](RELEASE_NOTES.md#java-namespace-migration) when updating an extension from an earlier RC.
