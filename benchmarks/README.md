# Benchmarks

[← Contributing](../CONTRIBUTING.md)

Logyard does not currently claim to outperform Logback or Log4j 2. These benchmarks isolate costs; an admission score is not a delivery rate.

The [capture text experiment](evidence/capture-text.md) and [attribute snapshot experiment](evidence/attribute-snapshots.md) record measured allocation changes with unchanged capture semantics. The [fluent argument experiments](evidence/fluent-arguments.md) document two rejected optimizations and the added allocation coverage.

The [capture handoff simplification](evidence/capture-handoff.md) removes an intermediate event type; its main allocation fixtures were unchanged.

The [structured-field declaration experiment](evidence/attribute-declarations.md) removes per-field wrappers and records its allocation benefit and MDC follow-up checks.

The [literal rendering experiment](evidence/literal-rendering.md) removes unnecessary capture and rendering wrappers, with file-delivery reconciliation and a fanout allocation investigation.

The [capture-allowance experiment](evidence/capture-allowance.md) reuses an immutable full-budget snapshot and records its small-event saving and MDC variation.

The [UTF-8 file experiment](evidence/utf8-file-output.md) measures reusable record storage and direct integer formatting through real file output.

The [provider comparison](comparison/README.md) adds isolated Logyard, Logback, and Log4j asynchronous delivery experiments with shared or native JSON encoding, matched fields, actual file writes, scheduled arrivals, and drain reconciliation.

The [virtual-thread experiment](evidence/virtual-delivery.md) records completed work, severity drops, CPU, and latency across six longer forks using the default delivery policies.

## Run

Use JDK 21+ and the repository's pinned Zolt:

```sh
./scripts/benchmark-smoke  # short harness, delivery-accounting, and allocation gate
./scripts/benchmark        # complete Logyard JMH suite
./scripts/benchmark-delivery-qualify  # 27 longer packaged delivery/reload forks on Linux
```

The smoke gate also exercises the official Quarkus extension benchmark. Its packaging requires Maven and resolves the extension from the verified release bundle. The Logyard benchmarks run through Zolt.

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

Budgets are calibrated on JDK 21. Four independent hosted Temurin runs measured one-producer async delivery at 48–99 B/op, the enabled one-argument native path at 360–416 B/op, the empty-MDC SLF4J path at 496–608 B/op, and fresh zero-MDC Quarkus mapping at 5,105–5,201 B/op. Their ceilings retain modest alignment headroom at 112, 432, 640, and 5,376 B/op. The copied Quarkus record with four MDC fields has a 3,328 B/op ceiling after three independent JVMs measured about 3,000–3,200 B/op. Disabled-path ceilings remain 1 B/op.
