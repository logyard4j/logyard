# Literal capture and rendering

[← Benchmarks](../README.md) · [Raw forks, source hashes, profiles, and patches](literal-rendering.json)

**Literal JSON-file allocation fell from 1,081 to 828 B/op, about 23%.** Message-only capture fell from 275 to 232 B/op. All measured file iterations reconciled completed records against calls.

Captured literals reuse their already bounded text. Formatted messages retain their shared lazy renderer, with one fixed rendering limit instead of a per-event copy. Direct arguments and attributes enter capture without supplier wrappers; deferred suppliers still execute inside the caller's capture scope.

Baseline `5a95ac1cfe2845c30613a48c0f748f018efc40d1`, with the same new literal-file fixture on both versions. Temurin 21.0.12.1, eight available CPUs, 256 MiB heap, one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler on 2026-09-13. Six ingress/output fixtures and four capture fixtures ran in separate, serial groups.

| Fixture | Before B/op | After B/op |
| --- | ---: | ---: |
| Message-only event | 274.7 | 232.0 |
| Three structured fields | 968.0 | 936.0 |
| Fluent positional argument | 744.0 | 736.0 |
| Eight-frame exception | 3,464.0 | 3,368.0 |
| Disabled native call | Approximately 0 | Approximately 0 |
| Two-output fanout | 288.0 | 280.0 |
| Empty-MDC SLF4J ingress | 333.3 | 296.0 |
| Populated-MDC SLF4J ingress | 701.4 | 653.4 |
| Runtime through formatted JSON file | 1,230.6 | 1,233.2 |
| Runtime through literal JSON file | 1,080.8 | 827.9 |

Small-event baseline forks allocated 264 / 296 / 264 B/op; all candidate forks allocated 232. Structured-event forks were consistently 968 before and 936 after. Literal-file ranges were 1,033–1,160 before and 768–867 after. Exception and MDC results vary across JVMs; their lower means are not separate established wins. Throughput intervals overlap, so this experiment makes no speed claim.

An intermediate change skipped literal rendering but retained direct-value supplier wrappers. Its MDC results prompted a longer confirmation: populated MDC measured 725 B/op on both versions, and empty MDC fell by 8 B/op. Throughput differences changed direction between comparisons. Those runs remain in the evidence.

The baseline hosted smoke gate also exposed 424 B/op fanout against its 400 B/op ceiling. A local two-CPU repeat used six fresh forks and smoke timings. The intermediate candidate measured 416 B/op in two forks and 280 in four. Passing direct values into capture removed the unnecessary wrappers: the final repeat measured 392 B/op in one fork and 280 in five. The ceiling remains 400 B/op; these local runs do not establish the hosted runner's root cause.

Separate JFR runs located temporary render results and text buffers in the baseline literal-file path. Neither candidate profile sampled allocations through that rendering path. JFR scores are excluded from the performance comparison. Additional fanout profiles identified temporary publication objects and capture suppliers; their sampling weights are not per-event counts.

Fourteen regression cases cover literal escaping, surrogate boundaries, null-template replacement, deferred capture, detached values, truncation, and concurrent rendering across event copies. All 233 API/core tests passed before final qualification. No public SPI or delivery policy changes.
