# Async batch delivery allocation

[← Benchmarks](../README.md) · [Measurements and profile evidence](async-batch-delivery.json)

Batch delivery now formats diagnostics only after failure and reuses one worker-owned assembly list. Each delegate invocation still receives a separate immutable snapshot. The assembly list clears event references after success or failure; its capacity is bounded by the advertised batch limit, at most 4,096 entries. Outputs without a batch delegate do not allocate this storage.

The new `AsyncBatchDeliveryBenchmark` measures one reused detached event with a counting batch delegate and no I/O. Collection has zero delay. Admission uses bounded BLOCK overflow; every attempt must reach the delegate, with no drop, emergency fallback, or synchronous delivery. Receipts include actual batch sizes, not just the configured limit.

Measured against production revision `104bcdf`, using the same fixture sources: Temurin 21.0.12.1+1, fixed 256 MiB heap, three fresh forks, three one-second warmups, and five one-second measurements per fork.

| Producers | Batch limit | Before B/record | After B/record | Before M records/s | After M records/s | Before mean batch | After mean batch |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 1 | 268.47 | 55.57 | 1.581 | 1.916 | 1.00 | 1.00 |
| 1 | 32 | 47.93 | 36.34 | 2.884 | 2.780 | 30.97 | 27.15 |
| 1 | 256 | 43.06 | 34.96 | 2.835 | 2.851 | 159.64 | 74.72 |
| 16 | 1 | 281.11 | 72.39 | 0.436 | 0.471 | 1.00 | 1.00 |
| 16 | 32 | 40.15 | 28.96 | 1.030 | 1.098 | 32.00 | 32.00 |
| 16 | 256 | 32.17 | 27.55 | 1.247 | 1.165 | 255.91 | 255.91 |

Allocation falls in every case. Timing results are mixed: sixteen producers with a limit of 256 are about 6.6% slower. This is an allocation improvement for the measured fixture, not a general throughput improvement. Different collection rates also change the observed batch size.

All 288 iteration receipts reconcile, including 180 measurement receipts. The JMH score includes admission and counter overhead; final drain and inspection are outside the timed loop. The receipt counts also include transition calls, so they are not the timed-loop operation count.

Separate JFR profiles at batch limits of one and 32 found diagnostic strings, delivery callbacks, and assembly arrays before the change. Candidate profiles contain no sampled sanitizer or assembly-list allocation on the delivery path. Immutable delegate snapshots, queue entries, and queue contention still allocate. JFR scores are excluded; weighted samples include setup and are not exact byte totals.

Regression coverage includes retained immutable batches across success and failure, interrupted collection, completion of worker-held claims, failure accounting, fatal propagation, lock release, and close races. The smoke gate runs all three one-producer cases, reconciles their receipts, and enforces allocation ceilings of 96/64/64 B/record. These ceilings include headroom and are not optimization targets.

```sh
export LOGYARD_BENCHMARK_EVIDENCE="$PWD/target/async-batch.delivery.jsonl"
zolt run --workspace --member benchmarks/logyard-benchmarks -- \
  '.*AsyncBatchDeliveryBenchmark.*' \
  -foe true -wi 3 -i 5 -w 1s -r 1s -f 3 \
  -jvmArgs '-Xms256m -Xmx256m' -prof gc \
  -rf json -rff "$PWD/target/async-batch.json"
```
