# Benchmarks

[← Contributing](../CONTRIBUTING.md)

Logyard does not currently claim to outperform Logback or Log4j 2. These benchmarks isolate costs; an admission score is not a delivery rate.

## Run

Use JDK 21+ and the repository's pinned Zolt:

```sh
./scripts/benchmark-smoke  # short harness, delivery-accounting, and allocation gate
./scripts/benchmark        # complete Logyard JMH suite
./scripts/benchmark-delivery-qualify  # 33 longer packaged delivery/reload forks on Linux
./scripts/benchmark-lifecycle-soak   # 12 reload/shutdown cycles in one Linux JVM
```

The smoke gate also exercises the official Quarkus extension benchmark. Its packaging requires Maven and resolves the extension from the verified release bundle. The Logyard benchmarks run through Zolt.

| Benchmark | Measured work |
| --- | --- |
| Native and SLF4J ingress | Disabled calls or capture and admission to the configured fixture |
| `AsyncDeliveryBenchmark` | Attempts to admit one reused captured event, with 1/4/16/64 producers and DROP overflow |
| `AsyncBatchDeliveryBenchmark` | Reused-event admission with 1/16 producers, batch limits of 1/32/256, and bounded BLOCK overflow; every attempt must reach the delegate |
| `OverflowPolicyBenchmark` | DROP, WAIT_DROP, BLOCK, and STDERR while a latched worker keeps the queue full; waits use a 1 ns timeout |
| `SynchronousOverflowBenchmark` | One synchronous fallback after a controlled 1 ms delegate stall |
| `JsonSinkBenchmark.directFileMechanics` | Buffered file writes of pre-encoded `{}` records |
| `JsonSinkBenchmark.directFile` | Captured event → JSON encoding → buffered UTF-8 file |
| `JsonSinkBenchmark.synchronousRuntimeFile` | Native logger call → capture → JSON encoding → buffered UTF-8 file |
| JSON stream cases | Encoding into a counting writer, without operating-system I/O |
| `JsonProcessStreamBenchmark` | Native runtime → configured JSON process stream → UTF-8 byte counter, including buffering and error checks |
| First-call and recovery cases | Provider binding or managed-runtime recovery in an already running JVM |
| `CapturedEventAllocationBenchmark.exceptionEvent` | One exception with eight fixed application frames, without causes or suppression |

Class-specific JMH modes are preserved. First-call, recovery, and synchronous-overflow cases use single-shot timing. Emergency overflow cases write to a null stderr destination; they measure the branch and formatter, not a real pipe.

## Read the evidence

Historical evidence retains the package names and source hashes used for each measurement. Current Java packages use `com.logyard4j.logyard.*`.

JMH JSON and a companion `*.delivery.jsonl` file are written under `target/benchmarks/` (`target/benchmark-smoke/` for the gate). Set `LOGYARD_BENCHMARK_RESULT` to change the full-suite result path.

Each async iteration reconciles attempted, accepted, dropped, emergency, and delegate-observed records after draining. Each file iteration closes the sink, counts JSONL records and bytes, and removes its temporary file. Missing results, incomplete drains, unexpected overflow branches, and changed measurement modes fail the gate.

Batch iterations also record the number of batches, singleton batches, and largest batch. Their delegate does no I/O; the configured limit is not the observed average batch size. Batch collection does not wait for followers, and admission fails the fixture if a five-second BLOCK wait times out.

These counts include JMH transition calls and identified fixture priming. They validate the workload; they are not the timed-loop operation count. Counter overhead is included in the measured call. Final close, drain, and file scans are outside the JMH score.

Files use a 256 KiB buffer and a 1 s flush interval. Set `LOGYARD_BENCHMARK_DIRECTORY` to choose a filesystem with enough temporary space. Record the filesystem, CPU quota, JVM flags, JDK, and revision when sharing results. Neither sink acceptance nor successful close proves durable storage.

Allocation budgets reject missing, non-finite, and over-budget results. Warmed disabled calls have a 1 B/op ceiling; provider startup and initial thread-local setup are separate workloads. Smoke timings are validation data, not a competitive performance claim.

Enabled-call budgets include modest headroom for JIT decisions and profiler overhead. The smoke gate warms each case for three 1 s iterations before measuring (five for management scaling); exception fixtures use fixed application frames so harness setup does not change the workload.

The ceilings below retain headroom over earlier JDK 21 measurements. They are regression limits, not optimization targets; the experiments below record subsequent changes.

| Calibration fixture | Historical B/op | Ceiling B/op |
| --- | ---: | ---: |
| One-producer async delivery | 48–99 | 112 |
| Native enabled, one argument | 360–416 | 432 |
| Empty-MDC SLF4J ingress | 496–608 | 640 |
| Fresh Quarkus record, empty MDC | 5105–5201 | 5376 |
| Copied Quarkus record, four MDC fields | About 3000–3200 | 3328 |

The first four ranges came from four hosted Temurin runs; the copied-record range came from three independent JVMs. Disabled-path ceilings remain 1 B/op.

## Lifecycle soak

`./scripts/benchmark-lifecycle-soak` rebuilds a clean committed candidate and runs twelve cycles in one JVM. Each cycle uses sixteen producers, 20,000 attempts at 2,000 events/s, two buffered file outputs, live reload, cached SLF4J loggers, and full shutdown. It also checks executor context propagation and cleanup. `zcheck run soak` runs the same check.

The [Lifecycle soak workflow](../.github/workflows/lifecycle-soak.yml) runs on Linux with JDK 21. Push an exact candidate to a dedicated `soak/**` branch, or start it manually with 12, 600, or 1,200 cycles. Ordinary feature-branch pushes do not start a long soak. The default 1,200 cycles take roughly four hours in one JVM; actual duration depends on the runner. A reconciled summary is published with the job, while full evidence is uploaded on success or failure and retained for 14 days.

[GitHub-hosted jobs stop after six hours](https://docs.github.com/en/actions/reference/limits). The workflow interrupts the soak after 315 minutes to leave time for evidence upload; an interrupted run fails. Longer continuous soaks need a local machine or self-hosted CI runner. Separate jobs start separate JVMs and do not extend one JVM's lifetime.

For roughly seven hours locally, including setup and validation:

```sh
./scripts/benchmark-lifecycle-soak --cycles 2160
```

Every cycle independently reconciles both files, including drop diagnostics. Post-GC resource checks begin after cycle ten: retained heap may grow by at most 32 MiB, and descriptor and thread counts by at most four. JVM heap is capped at 256 MiB.

Reports under `target/benchmark-lifecycle-soak/` record candidate and source hashes, packaged artifacts, JVM arguments, counters, and resource samples. Output retention keeps the first and latest two validated cycles compressed; each archive is verified before raw output is removed. Workload or record-validation failures retain raw files. These are lifecycle stability checks with explicit workload and growth limits.

## Recorded experiments

Each report links its raw measurements, source identity, and limitations. Results from different fixtures should not be added together.

| Area | Report and finding |
| --- | --- |
| Capture text | [Remove temporary text results](evidence/capture-text.md) |
| Attribute snapshots | [Reuse privately owned storage](evidence/attribute-snapshots.md) |
| Capture handoff | [Simpler state transfer; no established allocation saving](evidence/capture-handoff.md) |
| Structured declarations | [Remove field wrappers; record MDC variation](evidence/attribute-declarations.md) |
| Literal rendering | [Reduce literal work; investigate fanout allocation](evidence/literal-rendering.md) |
| Capture allowance | [Reuse the immutable full-budget snapshot](evidence/capture-allowance.md) |
| Fluent arguments | [Store eager values directly](evidence/fluent-values.md) |
| Empty fluent attributes | [Skip unused assembly; retain truncation provenance](evidence/empty-attributes.md) |
| Formatter plans | [Reduce ordinary and rejected-format allocation](evidence/formatter-plan.md) |
| UTF-8 file output | [Measure bounded reusable record storage](evidence/utf8-file-output.md) |
| Process-stream baseline | [Profile production UTF-8 conversion and reconcile completed records](evidence/process-stream-baseline.md) |
| UTF-8 process streams | [Reuse bounded record storage beneath configured stdout/stderr](evidence/utf8-process-stream.md) |
| Async delivery | [Remove temporary callbacks and healthy-path diagnostic formatting](evidence/async-delivery-temporaries.md) |
| Async batches | [Reuse private assembly storage while preserving immutable delegate snapshots](evidence/async-batch-delivery.md) |
| Virtual-thread delivery | [Measure completed work, latency, drops, and CPU](evidence/virtual-delivery.md) |
| Provider comparison | [Match semantics and effective buffered capacity](comparison/README.md) |
| Rejected argument experiments | [Lazy storage attempts and added coverage](evidence/fluent-arguments.md) |
| Rejected capture-scope experiment | [Native speedup with weaker MDC results](evidence/capture-scope.md) |
