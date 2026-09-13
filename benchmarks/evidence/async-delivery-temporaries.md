# Async delivery without temporary callbacks

[← Benchmarks](../README.md) · [Measurements and profile evidence](async-delivery-temporaries.json)

A [hosted smoke run](https://github.com/logyard4j/logyard/actions/runs/34764275002/job/103742357081) measured 116.2 B/attempt against the existing 112 B/attempt ceiling. Profiling found diagnostic-label formatting and captured callbacks on successful async delivery. The ceiling remains unchanged.

Single-event delivery now invokes the delegate directly and uses the shared failure policy in the catch path. Admission reuses one callback per sink; each invocation still reads the worker's current lifecycle state. Queue capacity, overflow rules, serialization, batch delivery, and shutdown behavior are preserved.

Measured against `182cef4`, with the unchanged `AsyncDeliveryBenchmark`: Temurin 21.0.12.1+1, fixed 256 MiB heap, three fresh forks, three one-second warmups, and five one-second measurements per fork.

| Producers | Before B/attempt | After B/attempt | Before attempted M/s | After attempted M/s | Before delivered fraction | After delivered fraction |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 65.44 | 17.59 | 6.176 | 4.276 | 35.48% | 53.48% |
| 4 | 38.09 | 16.43 | 10.521 | 10.667 | 10.92% | 15.15% |
| 16 | 32.07 | 16.28 | 12.803 | 13.222 | 3.49% | 3.36% |
| 64 | 23.41 | 16.27 | 14.061 | 13.586 | 1.23% | 1.18% |

This fixture reuses one detached event and saturates a no-op delegate. Throughput counts attempts, including drops. The one-producer candidate attempted fewer calls while completing 34.4 million records, versus 32.9 million before. The 64-producer candidate completed 2.50 million records, versus 2.67 million before. These results establish lower allocation, not a general throughput improvement or an end-to-end logging ranking.

Candidate allocation fork means stayed within 17.42–17.89 B/attempt at one producer and 16.26–16.49 at the other producer counts. All 192 before/after iteration receipts reconcile, including 120 measurement receipts. Every accepted event reached the delegate after draining; no emergency fallback occurred.

Separate JFR runs found no sanitizer or async callback allocation samples on the candidate's healthy path at one or 64 producers. Queue entries and contention still allocate. Profiling scores are excluded, and sampling weights are not exact byte totals. Raw JMH data, delivery receipts, environment, measured-source hashes, and recording hashes are retained in the JSON.

Regressions cover recoverable failures without false delivery counts, interrupted status, fatal error propagation, cross-thread lock release, at-most-once close, fallback/close schedules, and managed publication barriers.

```sh
export LOGYARD_BENCHMARK_EVIDENCE="$PWD/target/async-delivery.delivery.jsonl"
zolt run --workspace --member benchmarks/logyard-benchmarks -- \
  '.*AsyncDeliveryBenchmark.*' \
  -foe true -wi 3 -i 5 -w 1s -r 1s -f 3 \
  -jvmArgs '-Xms256m -Xmx256m' -prof gc \
  -rf json -rff "$PWD/target/async-delivery.json"
```
