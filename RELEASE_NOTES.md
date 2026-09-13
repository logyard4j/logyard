# Release notes

[← Logyard](README.md) · [Release process](RELEASING.md)

## 0.1.0-rc.2 — candidate changes

This candidate hardens output health, asynchronous delivery, and compatibility checks, and reduces enabled-path allocation. The runtime architecture and public text-encoder SPI are preserved.

- Built-in output health remains observable during stalled write, flush, and close operations, including async wrappers and framework health integration. Snapshots report last-known state; custom health contributors must honor the bounded-snapshot contract.
- Async delivery and delegate closure share the same serialization boundary. Queue retraction identifies an individual admission, including repeated submissions of the same detached event. Retirement and outstanding-work accounting retain their shutdown barriers.
- One supported-API manifest covers API, runtime, JUL, Spring, Quarkus, OpenTelemetry, and test-kit contracts. Deliberate API-break canaries verify that the compatibility gate rejects supported-surface changes.
- Capture avoids unnecessary temporary objects and copies. Built-in JSON files and process streams reuse output-owned UTF-8 storage. Oversized record storage is released after delivery, retaining at most 4 KiB; larger records remain supported within the existing capture and encoding limits.
- Batch assembly reuses private storage while delegates receive independent immutable snapshots. Allocation is lower in the recorded fixtures; throughput results are mixed.

[Benchmark reports](benchmarks/README.md#recorded-experiments) retain individual measurements, source identities, and limitations. These results do not establish a competitive ranking against other logging backends.

## Java namespace migration

Java packages now use `com.logyard4j.logyard.*`. Maven coordinates retain the `com.logyard4j` group and existing artifact names. This is a source and binary compatibility break from earlier RC checkouts.

| Previous reference | New reference |
| --- | --- |
| `com.logyard4j.api.event.AttributeSet` | `com.logyard4j.logyard.api.event.AttributeSet` |
| `com.logyard4j.api.spi.config.ProviderConfiguration` | `com.logyard4j.logyard.api.spi.config.ProviderConfiguration` |
| Module `com.logyard4j.api` | Module `com.logyard4j.logyard.api` |

Update Java imports and fully qualified names, including custom implementations and reflective configuration. Update applicable `META-INF/services` filenames and provider names, and JPMS module declarations and launch options. Rebuild applications and extensions against the new artifacts together; previously compiled classes referencing the old namespace are incompatible.

The Maven group is not a Java package: keep dependency coordinates such as `com.logyard4j:logyard-api`. Use a new release version for changed bytes; an already-published coordinate must never be replaced.
