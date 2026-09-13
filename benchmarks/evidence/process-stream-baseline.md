# JSON process-stream baseline

[Measurements and profile evidence](process-stream-baseline.json)

The production baseline is `95209d5`. The added fixture starts the real runtime with synchronous JSON stderr, a one-second flush interval, and a counting byte destination. It restores process stderr before measurement. Every iteration closes the runtime and requires one newline per attempted event, without closing the process-owned stream.

Temurin 21.0.12.1+1, a 256 MiB fixed heap, three fresh forks, three one-second warmups, and five one-second measurements per fork produced these means:

| Fixture | Allocation, B/op | Throughput, ops/µs |
| --- | ---: | ---: |
| Literal message | 2,424 | 0.272 |
| Two arguments | 2,943 | 0.246 |
| Three structured fields | 3,293 | 0.233 |
| Existing direct `Writer` control | 1,024 | 0.363 |

The byte counter scans record delimiters; these fixtures include that work and exclude operating-system I/O. The `Writer` control measures a different boundary and is tracked separately. New allocation ceilings retain headroom above the measured process-stream costs.

Separate JFR runs sampled substantial temporary character-array allocation in `StreamEncoder.write`, reached through `OutputStreamWriter.write` and `JsonLinesSink.accept`. They also sampled `HeapCharBuffer` allocation in `CharBuffer.wrap` on that conversion path. Profiling scores are excluded from the table. These measurements establish the starting point for testing the existing bounded UTF-8 writer on process streams.
