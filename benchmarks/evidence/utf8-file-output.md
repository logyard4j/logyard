# Reusable UTF-8 file output

[← Benchmarks](../README.md) · [Measurements and environment](utf8-file-output.json)

Measured on 2026-09-13 against `7f728b1`, with Temurin 21.0.12.1 on an eight-vCPU AMD EPYC KVM host. Each fixture used three fresh JVMs, three 1 s warmups, five 1 s measurements, one producer, and a fixed 256 MiB heap.

Built-in JSON files now write through an output-owned UTF-8 record buffer. Text and byte output share field projection, escaping, truncation, and exception traversal. Primitive integers write directly into both buffers. Custom text encoders retain their framing and size checks.

| Fixture | Before B/op | After B/op | Allocation reduction | Before ops/µs | After ops/µs |
| --- | ---: | ---: | ---: | ---: | ---: |
| JSON text encoding | 1,200 | 1,035 | 13.8% | 0.432 | 0.426 |
| Direct JSON file | 1,721 | 513 | 70.2% | 0.288 | 0.367 |
| Direct JSON stream | 1,216 | 1,024 | 15.8% | 0.358 | 0.360 |
| Runtime through JSON file | 2,402 | 1,199 | 50.1% | 0.245 | 0.306 |

The two file fixtures improved throughput by 27.6% and 25.0%. Text and stream throughput differences are within their measurement uncertainty. Runtime-file allocation varied from 1,161 to 1,258 B/op across candidate forks. These are local synchronous-output fixtures, not a backend ranking or asynchronous load qualification.

File and stream counts reconcile after every iteration; all 90 measurement iterations are retained as totals per fork in the JSON. The file fixture also checks complete final framing. The data includes every JMH measurement, confidence error, host details, and changed-source hash. Separate JFR runs informed allocation work and are excluded from the comparison.

Verification covers randomized UTF-16 and profile parity, valid UTF-8, complete fallback records, decimal boundaries, concurrent record ownership, stalled-output health, exact rotation boundaries, and large records followed by small ones. UTF-8 record storage is capped at 768 KiB and retains at most 4 KiB after delivery; ECS scratch text also releases oversized storage.

Run on each revision with separate result and delivery paths:

```sh
export LOGYARD_BENCHMARK_EVIDENCE="$PWD/target/utf8-delivery.jsonl"
zolt run --workspace --member benchmarks/logyard-benchmarks -- \
  '.*(EncodingBenchmark.jsonEncoding|JsonSinkBenchmark.(directFile|synchronousRuntimeFile|directStream))$' \
  -t 1 -f 3 -wi 3 -w 1s -i 5 -r 1s \
  -jvmArgs '-Xms256m -Xmx256m' -prof gc -foe true \
  -rf json -rff "$PWD/target/utf8-results.json"
```

For separate allocation samples, select `.*JsonSinkBenchmark.directFile$` with one fork, three warmups and measurements, and `-prof 'jfr:dir=target/utf8-jfr;stackDepth=64'`.
