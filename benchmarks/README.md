# Benchmarks

[Contributing](../CONTRIBUTING.md)

Use the repository's JDK and Zolt setup. Maven is needed for the Quarkus cases.

## Run

```sh
./scripts/benchmark-smoke    # short regression checks
./scripts/benchmark          # full JMH suite
./scripts/comparison-verify  # Logyard, Logback, and Log4j 2
```

For longer delivery and restart checks on a clean, committed Linux checkout:

```sh
./scripts/benchmark-delivery-qualify
./scripts/benchmark-lifecycle-soak
```

The soak runs 12 cycles by default. Use `--cycles 600` for a longer run, or the [soak workflow](../.github/workflows/lifecycle-soak.yml).

## Results

JMH output is in `target/benchmarks/`; short runs go to `target/benchmark-smoke/`. Longer runs have their own directories under `target/`.

Use allocation, latency, delivered records, and drops together when reading a result. Admission rate alone does not tell you how much reached the output.

Record the JDK, commit, CPU limits, and filesystem when sharing a run. The smoke suite catches regressions; use longer runs on your deployment setup for performance decisions.

See [provider comparisons](comparison/README.md) for the comparison commands. Historical measurements are stored as JSON in `benchmarks/evidence/`.
