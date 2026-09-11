# Releasing Logyard

[← Logyard](README.md) · [Build and test](CONTRIBUTING.md) · [Release workflow](.github/workflows/release.yml)

Logyard publishes one fourteen-artifact family to Maven Central. Use `scripts/central-publish` for uploads.

## Prepare

- Use a complete JDK with `javadoc`. Packaging checks `ZOLT_JAVA_HOME`, then `JAVA_HOME`, then installed JDK discovery.
- Install the [pinned Zolt revision](CONTRIBUTING.md#build-and-test) and configure the release signing key.
- Keep all artifact versions aligned. A release tag must be `v` followed by that exact version.
- Review [compatibility-baseline.toml](compatibility-baseline.toml).

The baseline identifies the previous immutable release and the SHA-256 of its API, runtime, JUL, Spring, and Quarkus JARs. Use `version = "none"` only when no previous public release exists. `scripts/api-compatibility --baseline` verifies that policy; `--self-test` checks tooling only.

## Qualify the release

Run from the repository root before tagging:

```sh
./scripts/ci
./scripts/api-compatibility --baseline
./scripts/examples-verify
./scripts/ecs-verify
./scripts/benchmark-smoke
./scripts/release-bundle --sign
./scripts/zolt-publication-check --signed
./scripts/central-publish
```

These gates cover runtime failures, packaged JVM consumers and ECS ingestion through Smoque, API compatibility, allocation budgets, and the signed Central bundle. The ECS gate needs Docker. Linux, macOS, and Windows [CI](.github/workflows/ci.yml) must also pass.

The final command creates a signed, deterministic ZIP locally. It does not upload.

## Verify the publication family

| Owner | Publications |
| --- | --- |
| Zolt | Eleven Java libraries and `logyard-bom` |
| Official Quarkus Maven reactor | `logyard-quarkus` and `logyard-quarkus-deployment` |
| Central bundle | All fourteen artifacts in one Maven layout |

The BOM includes Quarkus through its `[bom.versions]` table. Quarkus POMs are flattened and standalone; the bundle copies Zolt-produced POMs and artifacts without regenerating them.

The bundle includes applicable JARs, sources, Javadocs, CycloneDX SBOMs, detached PGP signatures, and MD5, SHA-1, and SHA-256 checksums.

`scripts/zolt-publication-check` validates workspace artifacts and Central metadata without release credentials. `--signed` also checks signing and assembles the signed Zolt family locally. Only unsigned checks defer signing; snapshot versions remain blocked from Central.

`scripts/release-verify --require-signatures` validates the complete hybrid bundle. Do not upload with live `zolt publish --workspace --central`: that family excludes the two Maven-built Quarkus artifacts.

## Publish to Central

Supply credentials through your environment:

```sh
export CENTRAL_TOKEN_USERNAME='token username'
export CENTRAL_TOKEN_PASSWORD='token password'
./scripts/central-publish --upload
```

This uploads for validation and manual release in Central Portal.

| Command | Effect |
| --- | --- |
| `./scripts/central-publish` | Build and verify the signed ZIP locally |
| `./scripts/central-publish --upload` | Upload for validation and manual publication |
| `./scripts/central-publish --upload --automatic --wait` | Publish automatically after validation; wait for the terminal state |

`CENTRAL_BEARER_TOKEN` can replace the username/password variables when it contains the base64-encoded `username:password` value. Central releases are immutable; uploads reject snapshot versions and require explicit `--upload`.

## Tagged release workflow

The [release workflow](.github/workflows/release.yml) runs on `v*` tags. It verifies the tag/version match, runs the release gates, waits for Central to reach `PUBLISHED`, then creates the GitHub release.

| GitHub Actions secret | Purpose |
| --- | --- |
| `GPG_PRIVATE_KEY` | Release signing key to import |
| `GPG_KEY_ID` | Signing identity |
| `GPG_PASSPHRASE` | Key passphrase, when required |
| `CENTRAL_TOKEN_USERNAME` | Central token username |
| `CENTRAL_TOKEN_PASSWORD` | Central token password |

Credential details: [Central Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
