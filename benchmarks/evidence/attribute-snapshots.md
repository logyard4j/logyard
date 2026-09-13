# Attribute snapshot allocation

[← Benchmarks](../README.md) · [Measurements](attribute-snapshots.json)

Measured on 2026-09-13 against `d6c5800`, immediately after the [capture text experiment](capture-text.md), with the same host, six fixtures, three-fork command, and heap. Its candidate measurements are this experiment's baseline.

JFR identified attribute snapshot capture as a remaining allocation site. Capture and recapture already allocate private arrays; they now trim those arrays only when a budget omits entries. Full snapshots retain their newly owned arrays directly. Builder storage and caller-owned values remain detached.

| Fixture | Before B/op | After B/op |
| --- | ---: | ---: |
| Captured small event | 264 | 264 |
| Captured three-field event | 1,088 | 1,072 |
| Native two-output fanout | 288 | 288 |
| SLF4J empty MDC | 411 | 421 |
| SLF4J populated MDC | 813 | 691 |
| Native disabled | <0.001 | <0.001 |

Populated MDC allocation fell 15.1% on average; its candidate forks measured 648–744 B/op, below the baseline's 776–832 B/op. Structured-event and empty-MDC differences fall within their observed variation between JVM forks. The empty-MDC forks still use the same two allocation levels, 400 and 432 B/op. These measurements do not establish a throughput win or meet the proposed 25% structured-event target.

The JSON preserves every measurement, throughput score, confidence error, and changed-source hash. Tests exercise immutable snapshots after builder reuse and caller mutation, normalized-key collisions, and exhausted entry budgets.
