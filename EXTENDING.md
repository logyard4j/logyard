# Extensions

[← Logyard](README.md) · [Configuration](CONFIGURATION.md) · [Runtime behavior](RUNTIME.md)

Add custom behavior through the SPI in `logyard-api`. Providers are discovered with `ServiceLoader` and create runtime-owned instances from bounded, immutable configuration.

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

import com.zsumz.logyard.api.event.AttributeSet;
import com.zsumz.logyard.api.spi.config.ProviderConfiguration;
import com.zsumz.logyard.api.spi.config.ProviderConfigurationSpec;
import com.zsumz.logyard.api.spi.processing.EventProcessor;
import com.zsumz.logyard.api.spi.processing.EventProcessorKind;
import com.zsumz.logyard.api.spi.processing.EventProcessorProvider;
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
META-INF/services/com.zsumz.logyard.api.spi.processing.EventProcessorProvider
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

The supported public surface includes all `logyard-api` packages, runtime `bootstrap` and `management`, the public JUL and Spring Boot integration packages, and `com.zsumz.logyard.quarkus.runtime.*`.

Other packages are implementation details marked `@InternalApi`. Use the public SPI when building extensions. Published JARs include stable `Automatic-Module-Name` values, source JARs, and Javadoc JARs.
