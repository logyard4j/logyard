# Empty fluent attributes

[← Benchmarks](../README.md) · [Raw forks, source hashes, profile stack, and patch](empty-attributes.json)

**Fluent capture with one argument measured 512 B/op in every candidate fork.** When declarations and scoped context are empty and carry no truncation provenance, capture now returns the immutable empty set directly. Four lines avoid creating an attribute builder and its storage. Nonempty and truncated sets retain their existing capture path.

Baseline `0c7ff40b5773495d99c6d1447eb20261bdf90318`. Both versions used Temurin 21.0.12.1, eight available CPUs, a 256 MiB heap, one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler on 2026-09-13. Runs were serial. MDC confirmation used five warmups and a preserved baseline core JAR verified against the qualified bundle.

| Fixture | Before B/op | After B/op |
| --- | ---: | ---: |
| Fluent positional argument | 698.7 | 512.0 |
| Exception event | 3290.7 | 3104.0 |
| Message-only event | 237.3 | 208.0 |
| Three structured fields | 896.0 | 888.0 |
| Empty-MDC SLF4J ingress | 304.0 | 288.0 |
| Populated-MDC SLF4J ingress | 690.7 | 728.0 |

Fluent baseline forks measured 712 / 712 / 672 B/op; candidate forks all measured 512. Mean allocation fell about 27%. Throughput rose from 4.72 to 5.36 ops/µs, with nonoverlapping JMH confidence intervals in this host-local experiment. Exception forks also allocated less, with variation in both versions.

Message-only logging does not use the changed fluent attribute path: its 208 / 296 / 208 baseline forks versus 208 in the candidate are JVM variation, not a separate optimization claim. Structured results were similar.

Empty-MDC throughput initially fell from 4.40 to 4.27 ops/µs. Longer-warmup controls measured 4.48 before and 4.38 after, with overlapping confidence intervals; allocation changed from 304 to 333 B/op because one candidate fork measured 392. Populated-MDC confirmation measured 680 versus 672 B/op and 2.98 versus 3.08 ops/µs. These controls do not establish an MDC improvement or a consistent allocation regression; all raw forks are retained.

The earlier fluent JFR profile identified `AttributeAccumulator` allocation through `AttributeSet.builder` and `PendingAttributes.capture`. The relevant source hashes match this baseline. Profiling scores are excluded from the comparisons.

All 155 core tests passed. Four cases preserve truncation from empty scoped, declared, and preset attributes, plus argument-capture truncation when attributes are otherwise empty. These measurements do not establish a competitive ranking.
