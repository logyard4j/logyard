# Benchmarks

[← Contributing](../CONTRIBUTING.md)

Logyard does not currently claim to outperform Logback or Log4j 2. These benchmarks isolate costs; an admission score is not a delivery rate.

The [provider comparison](comparison/README.md) adds isolated Logyard, Logback, and Log4j asynchronous delivery experiments with shared or native JSON encoding, matched fields, actual file writes, scheduled arrivals, and drain reconciliation.

## Run

Use JDK 21+ and the repository's pinned Zolt:

```sh
./scripts/benchmark-smoke  # short harness, delivery-accounting, and allocation gate
./scripts/benchmark        # complete Logyard JMH suite
```

The smoke gate also exercises the official Quarkus extension reactor. Its packaging requires Maven; the Logyard benchmarks run through Zolt.

| Benchmark | Measured work |
| --- | --- |
| Native and SLF4J ingress | Disabled calls or capture and admission to the configured fixture |
| `AsyncDeliveryBenchmark` | Attempts to admit one reused captured event, with 1/4/16/64 producers and DROP overflow |
| `OverflowPolicyBenchmark` | DROP, WAIT_DROP, BLOCK, and STDERR while a latched worker keeps the queue full; waits use a 1 ns timeout |
| `SynchronousOverflowBenchmark` | One synchronous fallback after a controlled 1 ms delegate stall |
| `JsonSinkBenchmark.directFileMechanics` | Buffered file writes of pre-encoded `{}` records |
| `JsonSinkBenchmark.directFile` | Captured event → JSON encoding → buffered UTF-8 file |
| `JsonSinkBenchmark.synchronousRuntimeFile` | Native logger call → capture → JSON encoding → buffered UTF-8 file |
| JSON stream cases | Encoding into a counting writer, without operating-system I/O |
| First-call and recovery cases | Provider binding or managed-runtime recovery in an already running JVM |
| `CapturedEventAllocationBenchmark.exceptionEvent` | One exception with eight fixed application frames, without causes or suppression |

Class-specific JMH modes are preserved. First-call, recovery, and synchronous-overflow cases use single-shot timing. Emergency overflow cases write to a null stderr destination; they measure the branch and formatter, not a real pipe.

## Read the evidence

JMH JSON and a companion `*.delivery.jsonl` file are written under `target/benchmarks/` (`target/benchmark-smoke/` for the gate). Set `LOGYARD_BENCHMARK_RESULT` to change the full-suite result path.

Each async iteration reconciles attempted, accepted, dropped, emergency, and delegate-observed records after draining. Each file iteration closes the sink, counts JSONL records and bytes, and removes its temporary file. Missing results, incomplete drains, unexpected overflow branches, and changed measurement modes fail the gate.

These counts include JMH transition calls and identified fixture priming. They validate the workload; they are not the timed-loop operation count. Counter overhead is included in the measured call. Final close, drain, and file scans are outside the JMH score.

Files use a 256 KiB buffer and a 1 s flush interval. Set `LOGYARD_BENCHMARK_DIRECTORY` to choose a filesystem with enough temporary space. Record the filesystem, CPU quota, JVM flags, JDK, and revision when sharing results. Neither sink acceptance nor successful close proves durable storage.

Allocation budgets reject missing, non-finite, and over-budget results. Warmed disabled calls have a 1 B/op ceiling; provider startup and initial thread-local setup are separate workloads. Smoke timings are validation data, not a competitive performance claim.

Enabled-call budgets include modest headroom for JIT decisions and profiler overhead. The smoke gate warms each case for three 1 s iterations before measuring (five for management scaling); exception fixtures use fixed application frames so harness setup does not change the workload.

Budgets are calibrated on JDK 21. The copied Quarkus record with four MDC fields has a 3,328 B/op ceiling: three independent JVMs measured about 3,000–3,200 B/op after eight seconds of warmup. The allowance includes those observed JIT differences; disabled-path ceilings remain 1 B/op.
