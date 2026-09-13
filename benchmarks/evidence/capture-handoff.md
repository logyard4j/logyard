# Capture handoff simplification

[← Benchmarks](../README.md) · [Raw fork results, source hashes, and patch](capture-handoff.json)

Capture now constructs the final immutable event state directly. Removing `CapturedLogEvent` and its field-by-field conversion eliminates one internal type and 41 lines. Detached values, capture budgets, context scope, and lazy rendering retain their existing implementation.

Baseline `7a2b3cfaca21239b3f83f0c1915e670cd1e693f2`, measured on 2026-09-13 with Temurin 21.0.12.1, eight available CPUs, and a 256 MiB heap. Both versions ran the same eight fixtures with one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler.

| Fixture | Before B/op | After B/op |
| --- | ---: | ---: |
| Message-only event | 264.0 | 264.0 |
| Three structured fields | 1,072.0 | 1,072.0 |
| Fluent positional argument | 744.0 | 744.0 |
| Eight-frame exception | 3,400.0 | 3,386.7 |
| Disabled native call | Approximately 0 | Approximately 0 |
| Two-output fanout | 288.0 | 288.0 |
| Empty-MDC SLF4J ingress | 410.7 | 362.7 |
| Populated-MDC SLF4J ingress | 754.7 | 722.7 |

**This is a simplification, not an established allocation win.** The main capture fixtures were unchanged, consistent with the JVM already eliminating the intermediate record. Exception and MDC results varied between forks. Empty-MDC ranges were 400–432 before and 344–400 after; populated-MDC ranges were 680–848 before and 648–760 after. These overlapping ranges do not establish a stable improvement.

API and core tests passed, including detached capture, nested scope restoration, bounded exceptions, attribute provenance, supplier isolation, and shared-event fanout. Raw results preserve allocation and throughput measurements for every fork; no competitive ranking is inferred.
