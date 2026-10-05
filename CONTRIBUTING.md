# Contributing

[Logyard](README.md)

## Build and test

You need JDK 21+, Python 3.11+, Git, and curl 7.71+.

On macOS with Homebrew:

```sh
brew install openjdk@21 python@3.13 maven
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$(brew --prefix python@3.13)/libexec/bin:$PATH"
```

From the repository root:

```sh
bash scripts/bootstrap-zcheck
export PATH="$HOME/.zcheck/bin:$HOME/.zolt/bin:$PATH"
zcheck run bootstrap
zcheck run check
```

The bootstrap installs zcheck 0.0.2. `zcheck run bootstrap` installs the pinned Zolt revision; `zcheck run check` builds the workspace and runs the checks.

## Run the examples

Install Maven 3.9+ and Node.js 22.18+ with npm, then run:

```sh
zcheck run examples
```

This packages Logyard and runs the [example applications](INTEGRATIONS.md#examples). Results are in `target/examples-verify/`.

To build the local Maven repository on its own, run `zcheck run bundle`. It writes to `target/release-bundle/`.

## Useful commands

| Command | Use |
| --- | --- |
| `zcheck run repository` | Check layout, tooling, and source boundaries |
| `zcheck run javadoc` | Build the API docs |
| `zcheck run failures` | Run output and reload failure tests |
| `zcheck run api-baseline` | Check against the previous release |
| `zcheck run ecs` | Test Elasticsearch ingestion; needs Docker |
| `zcheck run benchmark-smoke` | Run short performance regression checks |

CI uses the same tasks in [zcheck.toml](zcheck.toml). Use `zcheck list` to see them. See [benchmarks](benchmarks/README.md) for longer runs and [releasing](RELEASING.md) for publication.

## Making changes

Zolt builds the libraries and examples. Maven builds the Quarkus extension.

Keep new production classes at or below 220 lines, and tests and examples at or below 300. Existing larger classes have a ceiling in [scripts/java-size-baseline.tsv](scripts/java-size-baseline.tsv). Keep I/O and extension callbacks out of reload state.

Use the public SPI for extensions. When changing dependencies, update [framework-versions.toml](framework-versions.toml) and the lock file. Show dependency setup with Zolt, Gradle Kotlin DSL, and Maven, in that order.

Use short, single-subject Conventional Commits. Run `zcheck run check` before opening a pull request.
