# Releasing Logyard

[← Logyard](README.md) · [Build and test](CONTRIBUTING.md) · [Release workflow](.github/workflows/release.yml)

Logyard publishes one sixteen-artifact family to Maven Central. Use `scripts/central-publish` for uploads.

## Prepare

- Use a complete JDK with `javadoc`. Packaging checks `ZOLT_JAVA_HOME`, then `JAVA_HOME`, then installed JDK discovery.
- Install the [pinned Zolt revision](CONTRIBUTING.md#build-and-test) and configure the release signing key.
- Keep all artifact versions aligned. A release tag must be `v` followed by that exact version.
- Review [compatibility-baseline.toml](compatibility-baseline.toml).

[supported-api.toml](supported-api.toml) defines the supported packages and types for API, runtime, JUL, Spring, Quarkus, OpenTelemetry, and test-kit artifacts. Compatibility, strict Javadoc selection, and package-boundary checks consume it. `@InternalApi` declarations remain outside the compatibility promise.

The baseline identifies the previous immutable release and the SHA-256 of all seven JARs. Use `version = "none"` only before the first public release. After publication, set `version` to that release and add an `[artifacts]` entry for every manifest surface name, each with its `artifact` and the `sha256` of the JAR retrieved from Maven Central. The gate downloads and verifies those exact artifacts; current build outputs cannot substitute for them.

`scripts/api-compatibility --baseline` enforces that policy. `--self-test` compares packaged artifacts against themselves, then removes a real supported declaration from a disposable copy of each JAR and requires rejection. It also checks that removing an internal method passes. These canaries validate the gate, not compatibility with a previous release.

## Qualify the release

Run from the repository root before tagging:

```sh
./scripts/ci
./scripts/api-compatibility --baseline
./scripts/examples-verify
./scripts/ecs-verify
./scripts/benchmark-smoke
./scripts/comparison-verify
./scripts/benchmark-delivery-qualify
./scripts/release-bundle --sign
./scripts/zolt-publication-check --signed
./scripts/central-publish
```

These gates cover runtime failures, packaged JVM consumers and ECS ingestion through Smoque, API compatibility, allocation budgets, and the signed Central bundle. The ECS gate needs Docker or `LOGYARD_ECS_URL` pointing to a disposable Elasticsearch instance. Linux, macOS, and Windows [CI](.github/workflows/ci.yml) must also pass.

The final command creates a signed, deterministic ZIP locally. It does not upload.

## Verify the publication family

| Owner | Publications |
| --- | --- |
| Zolt | Thirteen Java libraries and `logyard-bom` |
| Official Quarkus Maven reactor | `logyard-quarkus` and `logyard-quarkus-deployment` |
| Central bundle | All sixteen artifacts in one Maven layout |

The BOM includes Quarkus through its `[bom.versions]` table. Quarkus POMs are flattened and standalone; the bundle copies Zolt-produced POMs and artifacts without regenerating them.

Every library JAR includes canonical `META-INF/LICENSE` and `META-INF/NOTICE` files. The bundle includes applicable JARs, sources, Javadocs, CycloneDX SBOMs, detached PGP signatures, and MD5, SHA-1, and SHA-256 checksums.

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

## Release workflow

Create an annotated release tag signed by the [pinned release key](.github/release-signing-key.asc), then manually run the [release workflow](.github/workflows/release.yml) from `main` with that tag. Configure the GitHub `release` environment and its secrets before use.

The workflow verifies the signature, exact version, and ancestry on `main` before executing the tagged commit. The signed tag binds its commit; individual commits may be unsigned. It runs the release gates, checks that the remote tag is unchanged before each publication, waits for Central to reach `PUBLISHED`, then creates the GitHub release.

To exercise tag verification locally with disposable keys and repositories:

```sh
PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=scripts python3 -m verify_tools.provenance_canary
```

| GitHub Actions secret | Purpose |
| --- | --- |
| `GPG_PRIVATE_KEY` | Release signing key to import |
| `GPG_KEY_ID` | Signing identity |
| `GPG_PASSPHRASE` | Key passphrase, when required |
| `CENTRAL_TOKEN_USERNAME` | Central token username |
| `CENTRAL_TOKEN_PASSWORD` | Central token password |

Credential details: [Central Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
