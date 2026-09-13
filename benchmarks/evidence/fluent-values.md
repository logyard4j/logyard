# Eager fluent-argument capture

[← Benchmarks](../README.md) · [Raw forks, source hashes, profiles, and patch](fluent-values.json)

**One-argument fluent capture fell from 736 to 712 B/op in every longer-warmup fork.** Eager arguments now go directly into the bounded declaration list. Only deferred suppliers need a private wrapper. Direct supplier objects remain ordinary values, and capture still detaches values on the caller thread. The change removes 22 net production lines.

Baseline `8206332614b365e46af0ce6f18d9ae510a24c33b`. Both versions used Temurin 21.0.12.1, eight available CPUs, a 256 MiB heap, one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler on 2026-09-13. Runs were serial. The confirmation used five warmups and a preserved baseline JAR verified against the qualified release bundle.

| Fixture | Before B/op | After B/op |
| --- | ---: | ---: |
| Fluent positional argument | 802.7 | 714.7 |
| Message-only event | 208.0 | 208.0 |
| Three structured fields | 888.0 | 896.0 |
| Populated-MDC SLF4J ingress | 690.7 | 709.4 |

The main fluent baseline forks measured 736 / 936 / 736 B/op; candidate forks measured 720 / 712 / 712. Its larger average reduction includes JVM optimization variation. With five warmups, all baseline forks measured 736 and all candidate forks measured 712: a 24-byte saving, about 3.3%. Confirmation throughput was 4.64 versus 4.71 ops/µs, with overlapping confidence intervals.

Structured throughput initially fell from 3.00 to 2.74 ops/µs. The confirmation measured 3.01 before and 3.16 after, with allocation changing from 880 to 813 B/op. The decline did not repeat. Populated-MDC confirmation allocation changed from 728 to 691 B/op, with throughput near 3.09 versus 3.05 ops/µs. These varying control results do not establish separate optimization wins or a general throughput improvement.

Separate JFR runs locate `PendingValue.direct` through `PendingArguments.add` in the baseline. The candidate removes that class and those allocation samples. Profiling scores are excluded from the comparisons above.

All 149 core tests passed. New regressions combine direct supplier objects, deferred results, variadic arguments, shared object identity, and mutable values; another checks omitted lazy arguments and later variadic arguments at the capacity limit. Existing null, failure-isolation, interruption, context, and fanout contracts remain covered. These host-local measurements do not establish a competitive ranking.
