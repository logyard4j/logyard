# Full capture-allowance reuse

[← Benchmarks](../README.md) · [Raw forks, source hashes, profile, and patch](capture-allowance.json)

**Message-only capture fell from 232 to 208 B/op in every fork, about 10%.** Events with an unused payload allowance now share its immutable snapshot. A consumed node, entry, or character allowance still produces a distinct snapshot. Mutable capture contexts remain independent.

Baseline `e00edd4318d80e1647e5c317716b2cbf95028344`. Measurements reuse the preceding final comparison after verifying every production and benchmark source hash. Both versions used Temurin 21.0.12.1, eight available CPUs, a 256 MiB heap, one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler on 2026-09-13.

| Fixture | Before B/op | After B/op |
| --- | ---: | ---: |
| Message-only event | 232.0 | 208.0 |
| Three structured fields | 936.0 | 856.0 |
| Fluent positional argument | 736.0 | 736.0 |
| Eight-frame exception | 3,368.0 | 3,384.0 |
| Disabled native call | Approximately 0 | Approximately 0 |
| Two-output fanout | 280.0 | 280.0 |
| Empty-MDC SLF4J ingress | 296.0 | 296.0 |
| Populated-MDC SLF4J ingress | 653.4 | 709.4 |
| Runtime through formatted JSON file | 1,233.2 | 1,203.9 |
| Runtime through literal JSON file | 827.9 | 803.6 |

The direct saving is one 24-byte snapshot. Structured-event candidate forks measured 816 / 912 / 840 B/op against 936 in all baseline forks; the additional variation depends on JVM optimization. File and exception results also vary across forks. These measurements do not establish a competitive ranking or a general throughput improvement.

Populated-MDC allocation rose in the main comparison. A longer confirmation with five 1 s warmups measured 709 before and 728 after: baseline forks were 672 / 728 / 728, and candidate forks were all 728. The baseline itself moved from 653 to 709 without a source change. The candidate introduced no higher observed mode; this is not an MDC improvement claim. Confirmation throughput was similar at 3.01 versus 2.98 ops/µs.

Six further fanout forks used smoke timings, default heap ergonomics, and two local CPUs. Every fork measured 280 B/op, within the unchanged 400 B/op ceiling. All measured file records reconciled against calls. The existing literal-file JFR profile locates the snapshot allocation in `CaptureContext.payloadAllowance()`.

Three regression cases prove replacement cannot restore an exhausted node, entry, or text budget. The enrichment regression now checks the payload remaining after argument capture, accounting separately for the fixed truncation marker. All 236 API/core tests passed before canonical qualification.
