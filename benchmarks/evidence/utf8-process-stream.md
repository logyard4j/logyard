# Reusable UTF-8 process streams

[← Benchmarks](../README.md) · [Measurements and profile evidence](utf8-process-stream.json)

Built-in JSON stdout/stderr now uses the same bounded UTF-8 record writer as file output. Each output owns its storage through delivery; sharing a JSON encoder does not serialize independent outputs. Public text encoders keep their existing framing checks and callback behavior.

Measured against production revision `95209d5`, using the unchanged fixture introduced in `586e678`: Temurin 21.0.12.1+1, a fixed 256 MiB heap, three fresh forks, three one-second warmups, and five one-second measurements per fork.

| Fixture | Before B/op | After B/op | Allocation reduction | Before ops/µs | After ops/µs |
| --- | ---: | ---: | ---: | ---: | ---: |
| Literal message | 2,424 | 784 | 67.6% | 0.272 | 0.401 |
| Two arguments | 2,943 | 1,195 | 59.4% | 0.246 | 0.357 |
| Three structured fields | 3,293 | 1,438 | 56.3% | 0.233 | 0.323 |
| Direct `Writer` control | 1,024 | 1,024 | Unchanged | 0.363 | 0.357 |

The process-stream fixtures include capture, JSON encoding, buffering, process-stream error checks, and delimiter counting. They exclude operating-system I/O. The `Writer` control measures a different boundary; its throughput uncertainty overlaps. Candidate allocation varied across forks: 720–816 B/op for literals, 1,153–1,249 for arguments, and 1,378–1,473 for structured fields. These local synchronous results do not rank logging backends or qualify asynchronous load.

All 192 before/after iteration receipts reconcile attempted calls with completed records, including 120 measurement receipts. Separate JFR runs sampled the old `StreamEncoder.write` character-array and `HeapCharBuffer` conversion allocations; the candidate sampled no `StreamEncoder` allocation stacks. Profiling scores are excluded. The JSON retains raw JMH measurements, delivery receipts, environment, source hashes, and recording hashes.

Regression coverage checks exact parsed output across all JSON profiles, malformed surrogates, truncation followed by small records, concurrent record ownership, encoding rejection recovery, custom encoder reentry, and nonblocking direct, async, and Spring Actuator health during write, explicit flush, scheduled flush, and close. Record storage remains capped at 768 KiB and shrinks to at most 4 KiB after delivery, separately from the fixed 8 KiB transport buffer.

Run each revision with separate result and delivery paths:

```sh
export LOGYARD_BENCHMARK_EVIDENCE="$PWD/target/process-stream.delivery.jsonl"
zolt run --workspace --member benchmarks/logyard-benchmarks -- \
  '.*(JsonProcessStreamBenchmark|JsonSinkBenchmark.directStream).*' \
  -foe true -wi 3 -i 5 -w 1s -r 1s -f 3 \
  -jvmArgs '-Xms256m -Xmx256m' -prof gc \
  -rf json -rff "$PWD/target/process-stream.json"
```
