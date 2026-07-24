# Releasing Logyard

Zolt owns the eleven Java library publications and the native `logyard-bom` workspace member. The official Quarkus Maven reactor owns `logyard-quarkus` and `logyard-quarkus-deployment`; `modules/logyard-bom/publication-overlay.toml` adds those two artifacts when the repository assembles the final atomic family bundle.

Build and verify the Maven-layout repository:

```sh
scripts/release-bundle --sign
scripts/zolt-publication-check
```

The publication check runs Zolt's whole-workspace Central preflight and the packaged-artifact verifier together. The pinned enterprise revision's family planner does not yet carry the sources and Javadoc supplementals that its package command produces, so the gate accepts exactly those known planner blockers only after independently proving every supplemental artifact. A snapshot additionally requires the release version to be the only other blocker. Any metadata, POM, signing, routing, family, or artifact failure is fatal.

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
