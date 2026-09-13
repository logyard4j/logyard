# Virtual-thread delivery experiment

[← Benchmarks](../README.md) · [Results, environment, artifact hashes, and runner](virtual-delivery.json)

Candidate `e26accdf6e328c8b30f07f9f2c310f6e1faa0c58`, measured on 2026-09-13 with Temurin 21.0.12.1, eight available CPUs, and a 256 MiB heap. Each mode ran in three fresh JVMs with 64 producers, 12,800 warmup calls, and 200,000 scheduled arrivals over 10 seconds. Calls contained two arguments and four MDC fields.

This is the native JSON comparison destination: public text encoding, UTF-8 conversion, and one unbuffered file write per record. It does not use Logyard's buffered UTF-8 file fast path. Default delivery used 256 queue slots plus one active worker record, with INFO drops and bounded ERROR waits.

| Mode / fork | Written | INFO drops | ERROR drops | Process CPU, s | Caller p99, µs | Arrival-to-write p99, ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Warmed / 1 | 147,267 | 52,657 | 76 | 6.19 | 78.5 | 49.9 |
| Warmed / 2 | 150,219 | 49,704 | 77 | 8.58 | 93.6 | 49.7 |
| Warmed / 3 | 149,593 | 50,290 | 117 | 5.64 | 73.5 | 50.1 |
| Fresh / 1 | 163,088 | 36,561 | 351 | 9.10 | 65.4 | 52.9 |
| Fresh / 2 | 157,006 | 42,589 | 405 | 8.37 | 88.4 | 53.2 |
| Fresh / 3 | 166,330 | 33,419 | 251 | 11.84 | 87.0 | 54.6 |

Every attempt reconciled: **1,200,000 attempted = 933,503 written + 265,220 INFO drops + 1,277 ERROR drops**. Independent file parsing verified framing, unique identities, severity, message arguments, and detached MDC values. Enqueue/drop counters matched the records; no destination failure or disabled-supplier evaluation occurred.

An initial zero-loss assertion failed at this offered load: 148,330 of 200,000 records completed. That run is excluded from the six forks above. The subsequent experiment retained loss explicitly and checked accounting; it did not reinterpret dropped calls as successful delivery.

Fresh requests include virtual-thread creation and MDC preparation in process CPU and arrival latency. Caller latency covers only the logging call. Virtual caller CPU and allocation remain unavailable, represented by `-1`. More completed records with more CPU in this saturated fixture is not an efficiency or latency win.

The embedded runner and raw fork results preserve this experiment. The repository's [longer delivery qualification](../comparison/README.md#longer-candidate-workloads) expands the workload matrix at 5,000 scheduled arrivals per second. These host-local results establish reconciled behavior under load, not a competitive ranking or a zero-loss capacity claim.
