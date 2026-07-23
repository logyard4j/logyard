# Releasing Logyard

The release scripts discover publishable JAR modules from their Zolt `[package.metadata]` tables and publication-only artifacts from `modules/*/publication.toml`. Do not add artifact lists to the scripts.

Build and verify the Maven-layout repository:

```sh
scripts/release-bundle --sign
```

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

The tag-triggered release workflow expects `CENTRAL_TOKEN_USERNAME` and `CENTRAL_TOKEN_PASSWORD` GitHub Actions secrets and waits for automatic publication to reach `PUBLISHED`. The protocol and credential format follow the [Central Portal Publisher API](https://central.sonatype.org/publish/publish-portal-api/).
