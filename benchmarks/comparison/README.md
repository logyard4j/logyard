# Provider delivery comparison

[← Benchmarks](../README.md)

Compare real SLF4J providers with matched file output. Logyard, Logback 1.6.3, and Log4j 2.26.1 run in separate JVMs with exactly one provider each. Log4j uses Disruptor 4.0.0 asynchronous loggers. Zolt resolves, builds, and tests every consumer; Logyard JARs are checked against the local release bundle.

## Run

```sh
./scripts/comparison-verify  # 51 smoke cells through Smoque
./scripts/benchmark-delivery-qualify  # longer Logyard candidate workloads, Linux

./scripts/benchmark-compare --events 100000 --producers 16 --arguments 2 --repeat 3
./scripts/benchmark-compare --format json --fields 16 --virtual --producers 64
./scripts/benchmark-compare --format native-json --fields 16 --arguments 4 --repeat 3
./scripts/benchmark-compare --format native-json --fields 4 --virtual-per-request --producers 16 --repeat 3
./scripts/benchmark-compare --rate 100000 --stall-ms 100 --delay-us 50 --repeat 3
```

Set `LOGYARD_COMPARISON_SKIP_RELEASE=1` to reuse a verified current bundle. Use `--java /path/to/jdk/bin/java` for another JDK, `--cpus 2` for a Linux CPU-affinity experiment, or `--policy default` to observe each library's overload defaults. Affinity limits are distinct from cgroup CPU quotas; both settings are recorded.

| Input | Values |
| --- | --- |
| Producers | 1, 4, 16, 64 |
| Message arguments | 0, 1, 2, 4 |
| JSON MDC fields | 0, 4, 16; includes escaping and Unicode |
| Encoding | `text` or `json` through the shared encoder; `native-json` through each provider's configured encoder |
| Threads | Warmed platform or virtual producers; fresh virtual threads with `--virtual-per-request` |
| Disabled calls | `--disabled classic`, `fluent`, or `supplier` |
| Destination | Real UTF-8 file, one unbuffered stream write per record; optional delay or initial stall |

## Equal work and scope

All output contains severity and the formatted message. Both JSON lanes include the selected MDC fields. Every written record is checked against the requested message and MDC values, including escaping, even when providers drop different identities.

| JSON lane | Encoder | Fields |
| --- | --- | --- |
| `json` | Shared harness encoder | `level`, `message`, `attributes` |
| `native-json`: Logyard | Runtime-configured `JsonEncoder` | The same fields plus `timestamp` |
| `native-json`: Logback | [Logstash composite encoder 9.0](https://github.com/logfellow/logstash-logback-encoder/tree/logstash-logback-encoder-9.0) | The same fields plus `timestamp` |
| `native-json`: Log4j | [JSON Template Layout 2.26.1](https://logging.apache.org/log4j/2.x/manual/json-template-layout.html) | The same fields plus `timestamp` |

The native lane omits `attributes` when zero MDC fields are requested. Native timestamps are UTC ISO-8601 with millisecond precision, checked against each experiment's time window. Their values differ between JVMs and are excluded from content equality. Logyard omits a zero fractional second; field order and escaping can also change encoded size. Reports retain actual bytes and file hashes.

Native encoding runs on the output worker. Logyard returns text, which the fixture frames and converts to UTF-8; Logback uses `encode`, and Log4j uses `toByteArray`. All three use the same measured file destination. This covers capture, message formatting, asynchronous dispatch, JSON encoding, and successful writes. Buffered file appenders, direct byte-buffer encoding, rotation, exceptions, and reload remain outside this comparison.

The default comparison policy, `matched-drop`, drops every severity when full and aligns maximum buffered work within one event:

| Provider | Queue slots | Worker-held events outside the queue | Maximum in-flight events |
| --- | ---: | ---: | ---: |
| Logyard | 4,096 | 1 | 4,097 |
| Logback | 2,048 | 2,049 | 4,097 |
| Log4j | 4,096 | 0 | 4,096 |

Logback's worker takes one event and drains the queue into its batch; producers can refill the queue while that batch is delivered. Log4j's active event occupies its ring buffer. The validator includes worker-held work and rejects a matched lane outside 4,096–4,097 events. Record sizes and allocation still differ, and producer-held capture work remains additional. The `default` policy lane preserves each library's own capacity, severity, and waiting policies, so interpret its loss and latency together.

Each experiment warms its producers, schedules the requested arrivals, then records every attempted call. Overload latency includes lateness before a logging call starts. A queue-capacity check and an ordered marker establish the drain barrier after producers finish.

The per-request lane warms provider code using separate requests, then creates a new virtual thread and MDC for every measured call. It keeps at most `--producers` requests alive. Arrival-to-write latency starts before thread creation; caller latency measures the logging call. Thread startup and MDC preparation remain included in process CPU and elapsed time. The reported thread model must match the requested mode.

## Evidence

Results are under `target/benchmark-compare/`; `latest.txt` identifies the last successful run. Each run retains JSON results, actual output files, logs, resolved dependency locks, artifact checksums, source hashes, JVM options, and host constraints.

| Measurement | Meaning |
| --- | --- |
| `sink_written`, `written_bytes` | Successful stream writes, independently checked against complete, unique file records |
| Completed records/bytes per second | Output divided by elapsed time through the final drain barrier |
| Caller p50/p99/p99.9/max | Duration of every logging call, including calls whose records are lost |
| Arrival-to-write percentiles | Scheduled arrival to successful write for delivered records |
| `unwritten_info`, `unwritten_error` | Attempted identities absent after drain, grouped by severity |
| Native counters | Independently reconciled where available: Logyard enqueue/drop totals and the matched Log4j drop policy |
| `maximum_in_flight_events` | Configured queue capacity plus maximum worker-held records outside that queue |
| Caller/worker allocation and CPU | Counters for warmed, live threads across measurement and drain; includes instrumentation overhead |
| Process CPU and peak RSS | Measured-interval process CPU; whole-process sampled peak RSS, including startup and warm-up |

Logback exposes no exact dropped-record counter here, so its absent records remain labelled **unwritten**. Virtual-thread caller allocation and CPU are reported as unavailable (`-1`); carrier activity remains in other platform-thread counters. Missing threads and unavailable RSS are explicit.

The smoke suite checks functionality and accounting, including native JSON with 0/4/16 MDC fields, warmed and fresh virtual threads, and overload. Zolt tests also verify that encoder failures, malformed framing, and duplicate writes fail the destination check. Short smoke timings are not evidence of a competitive win. Repeat experiments on the deployment's JDK, CPU constraints, and filesystem before drawing performance conclusions. Successful writes and drain do not establish durable storage.

## Longer candidate workloads

`benchmark-delivery-qualify` requires a clean committed checkout and rebuilds the complete release bundle. It runs Logyard alone in three fresh JVMs per workload, with a fixed 256 MiB heap, at least 10,000 warmup calls, and 50,000 scheduled arrivals over 10 seconds. Each call has two arguments and four MDC fields, with the default queue capacity and severity policies.

The eight workloads cover 1/4/16/64 platform producers, 64 warmed virtual producers, 64 fresh virtual requests, a slow output, and producers sharing one CPU with the worker. The slow output stalls for 100 ms, then delays each write by 250 µs; it must exercise both completed and dropped records. Healthy-output workloads also report any loss rather than assuming the offered rate is sustainable.

Results under `target/benchmark-delivery-qualification/` retain every file record, source and artifact hashes, JDK and CPU details, heap settings, latency distributions, process CPU, peak RSS, and loss by severity. Changed sources or a different commit invalidate the run. `qualification.json` records success or failure; `latest.txt` advances only after all 24 forks pass reconciliation. The manual [Delivery qualification workflow](../../.github/workflows/benchmark-qualification.yml) runs the same command.

This qualifies delivery accounting for these workloads, without a throughput ranking or latency threshold. Peak RSS includes startup and is not retained-heap measurement. Exceptions, multiple outputs, reload, and buffered-file behavior have separate tests and fixtures; they are outside this lane.
