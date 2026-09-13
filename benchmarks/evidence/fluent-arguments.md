# Fluent argument allocation experiments

[← Benchmarks](../README.md) · [Raw fork results, source hashes, and prototype patches](fluent-arguments.json)

**Both lazy-container prototypes were rejected.** Neither established a useful reduction in structured-event allocation. The retained changes add a fluent-argument benchmark, an allocation ceiling, and regression coverage for absent, explicit-null, and declared arguments.

Production baseline: `716890c6102cc7ffddbab9d928603e63a06c0349`. The same new `fluentArgument` fixture was present for every measurement. Temurin 21.0.12.1, eight available CPUs, 256 MiB heap, one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler were used throughout.

| Fixture | Baseline B/op | Lazy builder container B/op | Lazy argument list B/op |
| --- | ---: | ---: | ---: |
| Three structured fields, no positional arguments | 989.4 | 984.0 | 984.0 |
| One fluent positional argument | 760.0 | 773.3 | 744.0 |

The baseline structured forks measured 1,040 / 888 / 1,040 B/op. Each prototype produced one fork at 1,016 B/op and two at 968 B/op. Their mean reduction was only 0.5%; throughput differences were also within the observed uncertainty. Removing allocations visible in source did not establish a measurable improvement in this experiment.

The first prototype deferred the entire argument container. The second retained the builder structure and deferred only its list. Existing JFR samples identified `PendingArguments`, its `ArrayList`, and its backing array on the argument-free structured path. These observations motivated the experiment; sampled allocation weights were not treated as exact per-call counts.

Seven fixtures ran before and after the first prototype, including small events, disabled logging, fanout, and empty/populated MDC. The second experiment repeated the two affected fluent fixtures. The evidence preserves every fork and both discarded patches. Core tests passed for both prototypes, including supplier isolation and bounded capture.

The retained fluent-argument ceiling is 896 B/op, above baseline fork means of 744–792 B/op. This is a regression limit, not an optimization target or a competitive performance claim.
