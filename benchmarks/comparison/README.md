# Provider comparisons

[Benchmarks](../README.md)

The harness runs Logyard, Logback, and Log4j 2 in separate JVMs against the same file destination.

## Run

```sh
zcheck run comparison
zcheck run compare -- --events 100000 --producers 16 --arguments 2 --repeat 3
```

Choose another workload:

```sh
zcheck run compare -- --format native-json --fields 4 --virtual-per-request --producers 16
zcheck run compare -- --rate 100000 --stall-ms 100 --delay-us 50
```

The default policy matches queue capacity and drops on overflow. Use `--policy default` to test each library's defaults. `--format json` uses a shared encoder; `native-json` uses each library's own encoder.

Set `LOGYARD_COMPARISON_SKIP_RELEASE=1` to reuse the current local bundle. Use `--java /path/to/jdk/bin/java` for another JDK.

## Results

Results and log files are in `target/benchmark-compare/`. `latest.txt` points to the last successful run.

Compare completed records, latency, and loss together. The harness checks file contents after draining. Its destination does not cover each library's production file buffering or rotation.

For longer Logyard delivery and reload runs, use `zcheck run delivery` on Linux. It needs a clean committed checkout. Results go to `target/benchmark-delivery-qualification/`.
