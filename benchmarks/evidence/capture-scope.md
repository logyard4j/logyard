# Capture-scope closure experiment

[← Benchmarks](../README.md) · [Raw forks, profiles, source hashes, and rejected patch](capture-scope.json)

**Rejected.** Replacing the event-capture lambda with explicit context installation and `try/finally` improved the message-only fixture, but MDC controls repeatedly trended slower. The production change was reverted. The added nested-capture regressions remain.

Baseline `69043a314cfbb71881570a6ba7e6193f90ffe11a`. Measurements used Temurin 21.0.12.1, eight available CPUs, a 256 MiB heap, one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler on 2026-09-13. Runs were serial. Confirmation used five warmups and a preserved baseline API JAR.

| Fixture | Before B/op | Candidate B/op | Before ops/µs | Candidate ops/µs |
| --- | ---: | ---: | ---: | ---: |
| Fluent positional argument | 512.0 | 512.0 | 5.49 | 5.73 |
| Message-only event | 208.0 | 208.0 | 6.29 | 7.29 |
| Three structured fields | 864.0 | 848.0 | 3.03 | 2.90 |
| Native two-output fanout | 280.0 | 280.0 | 5.43 | 6.30 |
| Populated-MDC SLF4J ingress | 728.0 | 632.0 | 3.10 | 3.06 |

The longer-warmup comparison repeated the small-event speedup: 6.34 to 7.39 ops/µs, with unchanged 208 B/op and nonoverlapping JMH confidence intervals. Structured throughput measured 2.89 before and 3.08 after, reversing the initial decline. Its allocation measured 896 versus 864 B/op.

MDC confirmation measured 3.12 before and 2.88 ops/µs after, with overlapping confidence intervals and allocation near 653 versus 651 B/op. Three further alternating baseline/candidate pairs measured 3.15/3.08, 3.11/2.90, and 2.88/2.94 ops/µs. Pair means were 3.04 before and 2.97 after. Across all nine forks per version, mean MDC throughput was about 3.8% lower in the candidate. Variation prevents a precise general slowdown claim, but these results did not justify retaining the change.

Separate JFR runs found eight capture-lambda allocation samples before and none after. Removing that allocation site did not reduce the steady-state small-event, fluent, or fanout allocation in the main comparison. Profiling scores are excluded from the table.

All 98 API tests passed on the candidate. Three new cases exercise nested event capture after exhausting the inner payload budget: success, an ordinary failure, and a fatal error. They verify that the outer capture keeps its allowance, shared-value identity, and detached values. These cases also passed before the experiment. Existing tests cover thread-local graph release, class-loader release, and virtual-thread isolation.
