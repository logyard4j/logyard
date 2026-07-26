# Releasing Logyard

Zolt owns the eleven Java library publications and the native `logyard-bom` workspace member. The official Quarkus Maven reactor owns `logyard-quarkus` and `logyard-quarkus-deployment`; `modules/logyard-bom/publication-overlay.toml` adds those two artifacts when the repository assembles the final atomic family bundle.

Build and verify the Maven-layout repository:

```sh
scripts/release-bundle --sign
scripts/zolt-publication-check
```

Release packaging requires a complete JDK with `javadoc`. The scripts first honor
`ZOLT_JAVA_HOME`, then `JAVA_HOME`, then discover the home reported by the current
`java` on `PATH`; set `ZOLT_JAVA_HOME` when the release JDK should differ from the
ordinary shell toolchain. The resolved home is passed explicitly to native Zolt so
sources and Javadoc packaging do not depend on native-image launcher properties.

`compatibility-baseline.toml` is the reviewed source of truth for API compatibility. It explicitly declares `version = "none"` before the first public release. After publication, replace that policy with the previous immutable release and the SHA-256 of its API, runtime, JUL, Spring, and Quarkus JARs; `scripts/api-compatibility --baseline` downloads and verifies those exact artifacts before running japicmp. `--self-test` is only a tooling and filter smoke test.

The publication check runs Zolt's complete whole-workspace Central planner and the packaged-artifact verifier together. Zolt must plan every native workspace member’s main artifact, sources, Javadocs, checksums, signatures, and atomic family metadata. A snapshot requires the release version to be the only blocker; a release candidate must pass without blockers. Any metadata, POM, signing, routing, family, or artifact failure is fatal.

Before tagging a release candidate, run the complete matrix:

```sh
scripts/ci
scripts/api-compatibility --baseline
scripts/examples-verify
scripts/benchmark-smoke
scripts/examples-native-verify
scripts/release-bundle --sign
scripts/zolt-publication-check
scripts/central-publish
```

`scripts/ci` includes the focused durable-resource and reload failure-injection suite after the full Zolt test family. The remaining commands prove packaged consumers, SLF4J and `System.Logger` discovery, Spring Boot 3 and 4, Micronaut, Vert.x, Quarkus JVM/dev/native behavior, allocation budgets, GraalVM native images, the first-release compatibility policy, signatures, checksums, sources, Javadocs, BOM metadata, and the final Central-shaped family. Linux, macOS, and Windows filesystem behavior remains a required CI matrix.

During an RC, accept only fixes for data loss, secret disclosure, deadlock, unbounded resource use on a supported path, lifecycle corruption, lost valid configuration, framework startup failure, red release gates, or measured common-path performance regressions. Do not add APIs, integrations, or broader live-reload behavior during the RC.

Build the signed, deterministic Central Portal ZIP without uploading it:

```sh
scripts/central-publish
```

Upload for validation and manual release:

```sh
export CENTRAL_TOKEN_USERNAME='token username'
export CENTRAL_TOKEN_PASSWORD='token password'
scripts/central-publish --upload
```

Use `scripts/central-publish --upload --automatic --wait` only when the release should publish automatically after Portal validation and the command should wait for the terminal Portal state. `CENTRAL_BEARER_TOKEN` may replace the two token variables when it already contains the base64-encoded `username:password` value expected by the Publisher API.

The upload path rejects snapshot versions. Central releases are immutable, and upload never occurs unless `--upload` is explicit.

Do not use live `zolt publish --workspace --central` for Logyard yet: that Zolt family contains only the native workspace members and cannot include the two artifacts built by Quarkus Maven. `scripts/central-publish` is the sole upload path because it validates and uploads all fourteen publications in one Central Portal bundle.

The tag-triggered release workflow expects `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` GitHub Actions secrets and waits for automatic publication to reach `PUBLISHED`. The protocol and credential format follow the [Central Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
