# Capture text allocation

[← Benchmarks](../README.md) · [Measurements and environment](capture-text.json)

Measured on 2026-09-13 against `bdae2d7`, using Temurin 21.0.12.1 on an eight-vCPU AMD EPYC KVM host. Each case used three fresh forks, three 1 s warmups, five 1 s measurements, one producer, and a fixed 256 MiB heap.

JFR allocation samples pointed to temporary `CapturedText` objects. Ordinary text capture now returns the captured string directly and records shortening in the existing capture context. Exception fields retain their richer capture results. Budgets, surrogate handling, detached values, and event ownership are unchanged.

| Fixture | Before B/op | After B/op | Reduction |
| --- | ---: | ---: | ---: |
| Captured small event | 360 | 264 | 26.7% |
| Captured three-field event | 1,248 | 1,088 | 12.8% |
| Native two-output fanout | 384 | 288 | 25.0% |
| SLF4J empty MDC | 528 | 411 | 22.2% |
| SLF4J populated MDC | 912 | 813 | 10.8% |
| Native disabled | <0.001 | <0.001 | — |

These are local fixture results. Structured allocation varied from 1,008 to 1,152 B/op across candidate forks; the JSON retains every measurement and throughput score. The proposed 25% allocation target is met for small events, with further work needed for structured events. This experiment does not rank logging backends or change the regression ceilings.

Run the same command on each revision with a different result path:

```sh
zolt run --workspace --member benchmarks/logyard-benchmarks -- \
  '.*(CapturedEventAllocationBenchmark.(smallEvent|structuredEvent)|NativeIngressBenchmark.(disabled|twoOutputFanout)|Slf4jIngressBenchmark.(enabledEmptyMdc|enabledPopulatedMdc))$' \
  -t 1 -f 3 -wi 3 -w 1s -i 5 -r 1s \
  -jvmArgs '-Xms256m -Xmx256m' -prof gc -foe true \
  -rf json -rff "$PWD/target/capture-results.json"
```

Allocation profiling ran separately for the two captured-event cases with one fork, three warmups and measurements, and `-prof 'jfr:dir=target/capture-jfr;stackDepth=64'`. Profiling scores are excluded from this comparison.
