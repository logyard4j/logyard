# Provider delivery comparison

[← Benchmarks](../README.md)

Compare real SLF4J providers with identical file output. Logyard, Logback 1.6.3, and Log4j 2.26.1 run in separate JVMs with exactly one provider each. Log4j uses Disruptor 4.0.0 asynchronous loggers. Zolt resolves and builds every consumer; Logyard JARs are checked against the local release bundle.

## Run

```sh
./scripts/comparison-verify  # 33 smoke cells through Smoque

./scripts/benchmark-compare --events 100000 --producers 16 --arguments 2 --repeat 3
./scripts/benchmark-compare --format json --fields 16 --virtual --producers 64
./scripts/benchmark-compare --rate 100000 --stall-ms 100 --delay-us 50 --repeat 3
```

Set `LOGYARD_COMPARISON_SKIP_RELEASE=1` to reuse a verified current bundle. Use `--java /path/to/jdk/bin/java` for another JDK, `--cpus 2` for a Linux CPU-affinity experiment, or `--policy default` to observe each library's overload defaults. Affinity limits are distinct from cgroup CPU quotas; both settings are recorded.

| Input | Values |
| --- | --- |
| Producers | 1, 4, 16, 64 |
| Message arguments | 0, 1, 2, 4 |
| JSON MDC fields | 0, 4, 16; includes escaping and Unicode |
| Threads | Warmed platform or virtual threads |
| Disabled calls | `--disabled classic`, `fluent`, or `supplier` |
| Destination | Real UTF-8 file, one unbuffered stream write per record; optional delay or initial stall |

## Equal work and scope

All output contains severity and the formatted message. JSON adds the selected MDC fields through a shared harness encoder. The test checks equal record content across providers. This measures provider capture, native message formatting, asynchronous dispatch, and the common destination; native JSON layouts, buffered file appenders, rotation, exceptions, and reload are outside this comparison.

The default comparison policy uses 4,096 queue slots and drops every severity when full. Logback's drained worker batch can hold another 4,097 records; Logyard's custom output holds one active record outside its queue; Log4j's active record occupies the ring buffer. The report includes these capacities. Queue-slot equality is not a total-memory bound. The `default` policy lane deliberately preserves different severity and waiting policies, so interpret its loss and latency together.

Each experiment warms its producers, schedules the requested arrivals, then records every attempted call. Overload latency includes lateness before a logging call starts. A queue-capacity check and an ordered marker establish the drain barrier after producers finish.

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
| Caller/worker allocation and CPU | Counters for warmed, live threads across measurement and drain; includes instrumentation overhead |
| Process CPU and peak RSS | Measured-interval process CPU; whole-process sampled peak RSS, including startup and warm-up |

Logback exposes no exact dropped-record counter here, so its absent records remain labelled **unwritten**. Virtual-thread caller allocation and CPU are reported as unavailable (`-1`); carrier activity remains in other platform-thread counters. Missing threads and unavailable RSS are explicit.

The smoke suite checks functionality and accounting. Its short timings are not evidence of a competitive win. Repeat experiments on the deployment's JDK, CPU constraints, and filesystem before drawing performance conclusions. Successful writes and drain do not establish durable storage.
