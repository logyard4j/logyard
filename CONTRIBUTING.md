# Contributing

[← Logyard](README.md) · [Examples](INTEGRATIONS.md#examples) · [Releasing](RELEASING.md)

## Build and test

Use a complete JDK 21+ with `javac` and `javadoc`. Bootstrap the repository's pinned Zolt revision, then run the main gate:

```sh
./scripts/bootstrap-zolt
export PATH="$HOME/.zolt/bin:$PATH"
zolt resolve --workspace
./scripts/ci
```

`scripts/ci` runs repository and architecture checks, JDK-only verification, strict Javadocs, the locked Zolt build and full test family, executable integration checks, and failure injection.

## Choose a check

Run commands from the repository root:

| Command | Checks |
| --- | --- |
| `./scripts/repository-check` | Layout, tooling tests, framework versions, architecture, and whitespace |
| `./scripts/verify` | Core compilation, executable integration checks, boundedness, and tests |
| `./scripts/javadoc` | Public documentation with strict diagnostics |
| `./scripts/failure-injection-verify` | Durable-resource, delivery, and reload failure cases |
| `./scripts/benchmark-smoke` | Benchmark harness and allocation budgets |
| `./scripts/api-compatibility --baseline` | Reviewed compatibility policy against immutable release artifacts |

`scripts/verify` uses local JUnit and SLF4J artifacts when available. To run its dependency-free fallback, use `LOGYARD_VERIFY_FORCE_FALLBACK=1 ./scripts/verify`. Use `scripts/ci` for the complete local gate.

## Package and exercise consumers

```sh
./scripts/release-bundle
./scripts/examples-verify
./scripts/examples-native-verify
```

The native examples require GraalVM 25. After building a bundle, set `LOGYARD_EXAMPLES_SKIP_RELEASE=1` to reuse it with either examples command. See [Examples](INTEGRATIONS.md#examples) for the applications.

| Command | Produces or verifies |
| --- | --- |
| `./scripts/package` | Native Zolt packages and Maven-built Quarkus artifacts; runs extension tests |
| `./scripts/package-verify` | Produced JARs, module names, sources, Javadocs, descriptors, service discovery, and logging canaries |
| `./scripts/release-bundle` | Complete Maven-layout repository in `target/release-bundle` |
| `./scripts/zolt-publication-check` | Native workspace Central preflight plus packaged-artifact verification |

## Repository conventions

- Zolt owns the core build and publication model. Maven is isolated to consumer examples and the official Quarkus extension reactor.
- New production classes stay at or below 220 lines. Existing larger classes may not grow beyond their recorded ceiling.
- Tests and examples stay at or below 300 lines.
- Reload state stays free of I/O and extension callbacks.
- Keep generated Python bytecode out of the repository.
- Use concise, single-subject Conventional Commits.
- Document dependency installation with Maven, Gradle Kotlin DSL, and Zolt examples.

The [architecture checker](scripts/architecture-check) enforces source boundaries and size limits. [Framework versions](framework-versions.toml) and the [compatibility baseline](compatibility-baseline.toml) are checked inputs.

[CI](.github/workflows/ci.yml) covers Java 21 on Linux, macOS, and Windows, forward compatibility on Java 25, and GraalVM 25 native consumers. For publication, run the [complete release matrix](RELEASING.md#qualify-the-release).
