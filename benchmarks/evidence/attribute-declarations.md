# Structured-field declaration allocation

[← Benchmarks](../README.md) · [Raw forks, confirmation runs, source hashes, and patch](attribute-declarations.json)

**Three-field capture allocation fell from 1,072 to 968 B/op, about 9.7%.** Every candidate fork measured 968 B/op; baseline forks measured 1,040 / 1,088 / 1,088. Throughput was unchanged within the observed uncertainty.

Each pending attribute now holds its direct value or deferred supplier and supplies the value itself during capture. This removes its `PendingValue` wrapper and method-reference wrapper. The existing JFR profile identified `PendingValue.direct` allocations through `PendingAttributes.add` in the structured-event fixture.

Baseline `bc7fa6d7be1d38f47e17f974dd7d6e1f2070cbe4`. Its measurements reuse the preceding capture-handoff candidate run after verifying that every production and benchmark source hash matches. Both versions used Temurin 21.0.12.1, eight available CPUs, a 256 MiB heap, one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler on 2026-09-13.

| Fixture | Before B/op | After B/op |
| --- | ---: | ---: |
| Three structured fields | 1,072.0 | 968.0 |
| Message-only event | 264.0 | 264.0 |
| Fluent positional argument | 744.0 | 744.0 |
| Eight-frame exception | 3,386.7 | 3,400.0 |
| Disabled native call | Approximately 0 | Approximately 0 |
| Two-output fanout | 288.0 | 288.0 |
| Empty-MDC SLF4J ingress | 362.7 | 360.0 |
| Populated-MDC SLF4J ingress | 722.7 | 661.4 |

The exception difference is within the baseline's 3,360–3,400 B/op fork modes. MDC allocation varies across JVMs; the populated-MDC ranges overlap, so its lower mean is not an established improvement.

Initial empty-MDC throughput fell from 4.63 to 4.06 ops/µs. A separate confirmation with five 1 s warmups, otherwise matching settings, measured 4.50 before and 4.73 after with overlapping ranges; all six confirmation forks allocated 344 B/op. The regression did not reproduce. Both comparisons remain in the evidence. An earlier confirmation launch was rejected by JMH's run lock because the baseline had not finished; that pair was excluded and repeated serially.

All 141 core tests passed. Added coverage distinguishes direct supplier objects from deferred suppliers, skips overwritten suppliers, captures the final declaration once on the caller thread, and detaches mutable values. Existing full-context, dropped-supplier, key-collision, and capture-budget tests remain intact. These host-local results do not establish a competitive ranking.
