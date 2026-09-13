# Bounded formatter-plan storage

[← Benchmarks](../README.md) · [Raw forks, profiles, source hashes, and patch](formatter-plan.json)

**Rejected format expansion allocates about 12–17% less in these fixtures.** The execution plan now retains its privately owned parsed elements and stores formats alongside them. This removes one wrapper per element and an intermediate list copy. Ordinary string, number, date, and choice fixtures also allocate less. JDK syntax validation, choice selection, argument capture, and work limits remain in the same order.

Baseline `6c44615ffe308cfe047ff4fe8e881b968b135f45`. Both versions used Temurin 21.0.12.1, eight available CPUs, a 256 MiB heap, one producer, three fresh forks, three 1 s warmups, five 1 s measurements, and the GC profiler on 2026-09-13. Runs were serial. Additional choice controls alternated three baseline/candidate pairs with five warmups and a preserved baseline API JAR verified against the qualified bundle.

| Fixture | Before B/op | After B/op |
| --- | ---: | ---: |
| Rejected default-number expansion | 151856.3 | 134128.3 |
| Rejected recursive-choice expansion | 153678.2 | 127640.8 |
| One string | 1176.0 | 1088.0 |
| Two strings | 1416.0 | 1304.0 |
| Number | 2488.0 | 2400.0 |
| Date | 4704.0 | 4581.3 |
| Choice | 2944.0 | 2874.7 |

Candidate allocation stayed near 134128 and 127641 B/op in every rejection-fixture fork. Their throughput measured about 0.0256→0.0275 and 0.00766→0.00815 ops/µs, respectively. Most ordinary throughput intervals overlapped; these results support an allocation improvement, not a general throughput ranking.

Choice throughput initially measured 0.893 before and 0.858 ops/µs after, with overlapping confidence intervals. Alternating controls measured 0.886/0.861, 0.858/0.860, and 0.847/0.916 ops/µs. Their means were 0.864 before and 0.879 after, with all baseline forks allocating 2944 and all candidate forks 2880 B/op. The initial decline did not persist across these controls.

Separate JFR runs identified execution-element wrapper allocations in both baseline rejection fixtures and none after the class was removed. Substantial JDK parser allocation remains. Profiling scores are excluded from the comparisons, and the existing allocation ceilings are unchanged.

All 107 API tests passed on the measured candidate. Nine added cases cover reordered and repeated arguments, independent choices, quotes, missing and null selectors, invalid root syntax before capture, and invalid syntax inside selected versus unselected branches. Existing expansion, date/time, and failure-isolation contracts remain covered. The plan remains private to one render; no cache or shared formatter state was added.

The [generated conformance regression](../../modules/logyard-api/src/test/java/com/logyard4j/api/event/MessageFormatPlanConformanceTest.java) also compares 15,000 bounded patterns with the JDK across US, French, and Japanese locales. A fixed seed combines literals, quotes, reordered arguments, Unicode and signed indexes, numeric styles, choices, nulls, missing arguments, and supplementary characters.
