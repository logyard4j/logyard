# Contributing

[← Logyard](README.md) · [Examples](INTEGRATIONS.md#examples) · [Releasing](RELEASING.md)

## Build and test

Use a complete JDK 21+ with `javac` and `javadoc`, and Python 3.11+ for repository tooling. Bootstrap the repository's pinned Zolt revision, then run the main gate:

```sh
./scripts/bootstrap-zolt
export PATH="$HOME/.zolt/bin:$PATH"
zolt resolve --workspace
./scripts/ci
```

A clean bootstrap needs network access and curl 7.71+. Artifact downloads allow two retries, with 15 s connection and 60 s transfer limits; pinned SHA-256 checks remain mandatory.

`scripts/ci` runs repository and architecture checks, strict Javadocs, the locked Zolt build, resolved JUnit tests, executable integrations, constrained-heap canaries, provider discovery, and failure injection. Zolt is required; missing dependencies fail the gate.

With zcheck 0.0.2+, `zcheck run check` runs the same gate. `zcheck run examples` packages and tests JVM consumers; `zcheck run delivery` runs the 33-fork delivery qualification on a clean committed Linux checkout. Tasks selected in one run share a build resource. Use `--receipt target/check-receipt.json` to retain a machine-readable result; the receipt path must be new.

## Choose a check

Run commands from the repository root:

| Command | Checks |
| --- | --- |
| `./scripts/repository-check` | Layout, tooling tests, framework versions, architecture, and whitespace |
| `./scripts/verify` | Alias for the complete `scripts/ci` gate |
| `./scripts/javadoc` | Public documentation with strict diagnostics |
| `./scripts/failure-injection-verify` | Durable-resource, delivery, and reload failure cases |
| `./scripts/ecs-verify` | Zolt-generated JSON, typed Elasticsearch ingestion, and standard ECS queries through Smoque |
| `./scripts/benchmark-smoke` | Benchmark harness, delivery accounting, and allocation budgets |
| `./scripts/benchmark-lifecycle-soak` | Twelve reload/shutdown cycles in one JVM with independent output and resource checks |
| `./scripts/comparison-verify` | Isolated Zolt provider builds and equal-output delivery comparisons through Smoque |
| `./scripts/api-compatibility --baseline` | Reviewed compatibility policy against immutable release artifacts |

Verification has no dependency-free fallback or skipped-adapter success path. Framework consumer coverage is limited to the [Zolt examples](INTEGRATIONS.md#examples); native-image, AOT, and development-mode matrices are outside this gate.

The allocation gate allows at most 1 B/op for warmed disabled native/SLF4J calls and the adapter guard, including classic calls and fluent suppliers. It also checks that disabled suppliers stay unevaluated. Initial thread-local setup and provider startup are separate costs.

See [Benchmarks](benchmarks/README.md) for workload scopes, full runs, and delivery evidence.

The ECS gate needs Node.js/npm and Docker. It starts the [pinned Elasticsearch image](tests/ecs/fixture.toml) on loopback and removes its container afterward. To use an existing local instance of that version, set `LOGYARD_ECS_URL=http://127.0.0.1:9200`. The gate creates and deletes only its unique test index; reports go to `target/ecs-verify/`.

## Package and exercise consumers

```sh
./scripts/examples-verify
```

This builds the release bundle and tests the examples with Zolt and Smoque 0.1.2. Install Node.js 22.18+ with npm alongside the JDK.

Bundle assembly also requires Maven 3.9+ for the official Quarkus extension reactor.

Set `LOGYARD_EXAMPLES_SKIP_RELEASE=1` to reuse an existing bundle. JSON and JUnit reports go to `target/examples-verify/`. See [Examples](INTEGRATIONS.md#examples) for coverage.

CI runs the supported JVM consumers and provider-comparison smoke cases on JDK 21 and 25. These are functional and accounting checks; performance claims require longer, matched experiments.

| Command | Produces or verifies |
| --- | --- |
| `./scripts/package` | Native Zolt packages and Maven-built Quarkus artifacts; runs extension tests |
| `./scripts/package-verify` | Produced JARs, module names, sources, Javadocs, descriptors, service discovery, and logging canaries |
| `./scripts/release-bundle` | Complete Maven-layout repository in `target/release-bundle` |
| `./scripts/zolt-publication-check` | Workspace artifacts and Central metadata; add `--signed` to verify signing |

## Repository conventions

- Zolt owns the core build and publication model. The consumer examples also use Zolt. Maven is isolated to the official Quarkus extension reactor.
- New production classes stay at or below 220 lines. Existing larger classes may not grow beyond their recorded ceiling.
- Tests and examples stay at or below 300 lines.
- Reload state stays free of I/O and extension callbacks.
- Keep generated Python bytecode out of the repository.
- Use concise, single-subject Conventional Commits.
- Document dependency installation with Maven, Gradle Kotlin DSL, and Zolt examples.

The [architecture checker](scripts/architecture-check) enforces source boundaries and size limits. [Framework versions](framework-versions.toml) and the [compatibility baseline](compatibility-baseline.toml) are checked inputs.

[CI](.github/workflows/ci.yml) covers Java 21 on Linux, macOS, and Windows, forward compatibility on Java 25, and the JVM consumer smoke suite. For publication, run the [complete release matrix](RELEASING.md#qualify-the-release).
