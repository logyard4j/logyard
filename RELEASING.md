# Releasing

[Logyard](README.md) · [Build setup](CONTRIBUTING.md)

## Prepare

Set one version across the libraries, BOM, and Quarkus extension. Update [release notes](RELEASE_NOTES.md) and review [compatibility-baseline.toml](compatibility-baseline.toml).

Commit the changes on `main`. Run the checks on that commit; rerun them if it changes.

```sh
./scripts/ci
./scripts/api-compatibility --baseline
./scripts/examples-verify
./scripts/ecs-verify
./scripts/benchmark-smoke
./scripts/comparison-verify
./scripts/benchmark-delivery-qualify
```

The delivery check needs Linux and a clean checkout. ECS needs Docker or `LOGYARD_ECS_URL` pointing to a disposable local Elasticsearch instance. The [CI matrix](.github/workflows/ci.yml) must also pass.

## Publish

Configure the GitHub `release` environment with `GPG_PRIVATE_KEY`, `GPG_KEY_ID`, `GPG_PASSPHRASE` if needed, `CENTRAL_TOKEN_USERNAME`, and `CENTRAL_TOKEN_PASSWORD`.

Create an annotated tag, signed with the [release key](.github/release-signing-key.asc), named `v` followed by the version. Run the [Release workflow](.github/workflows/release.yml) from `main` with that tag. It publishes to Maven Central and creates the GitHub release.

For a local signed bundle:

```sh
export LOGYARD_GPG_KEY_ID='your signing key'
./scripts/central-publish
```

The ZIP is built locally. To upload it for manual publication in Central Portal, set `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD`, then run `./scripts/central-publish --upload`.

Use the complete bundle: it includes the Zolt libraries and the Maven-built Quarkus artifacts. Published coordinates cannot be replaced.

## After publication

Check that all 16 artifacts resolve from Maven Central, then run the consumer examples with fresh caches against Central.

Update `compatibility-baseline.toml` to the published version. For each surface in [supported-api.toml](supported-api.toml), add its artifact name and the SHA-256 of the published JAR. `version = "none"` is only for the first release.
