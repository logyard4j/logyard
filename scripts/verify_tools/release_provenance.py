"""Verify an annotated release tag before running code from its commit."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

RELEASE_FINGERPRINT = "B58439871CD2A7275B20CC19EC8E4D26598A0373"


def verify(root: Path, tag: str, trusted_main: str, key: Path,
           fingerprint: str = RELEASE_FINGERPRINT) -> dict[str, str]:
    if not re.fullmatch(r"v[0-9A-Za-z][0-9A-Za-z._-]*", tag):
        raise ValueError("invalid release tag")
    if not re.fullmatch(r"[0-9a-f]{40}", trusted_main):
        raise ValueError("trusted main must be an immutable commit SHA")

    def git(*args: str, env: dict[str, str] | None = None) -> str:
        result = subprocess.run(["git", "-C", str(root), *args], env=env,
                                capture_output=True, text=True, check=True, timeout=30)
        return result.stdout.strip()

    reference = f"refs/tags/{tag}"
    if git("cat-file", "-t", reference) != "tag":
        raise ValueError("release tag must be annotated and PGP signed")
    tag_object = git("rev-parse", reference)
    with tempfile.TemporaryDirectory(prefix="logyard-release-verification-") as directory:
        environment = {**os.environ, "GNUPGHOME": directory}
        subprocess.run(["gpg", "--batch", "--no-options", "--import", str(key)],
                       env=environment, capture_output=True, check=True, timeout=30)
        signature = subprocess.run(
            ["git", "-C", str(root), "-c", "gpg.format=openpgp", "-c", "gpg.program=gpg",
             "verify-tag", "--raw", tag_object], env=environment,
            capture_output=True, text=True, check=True, timeout=30)
        valid = [line.split() for line in signature.stderr.splitlines()
                 if line.startswith("[GNUPG:] VALIDSIG ")]
        if len(valid) != 1 or valid[0][-1] != fingerprint:
            raise ValueError("release tag was not signed by the pinned release key")
    commit = git("rev-parse", f"{tag_object}^{{commit}}")
    git("merge-base", "--is-ancestor", commit, trusted_main)
    manifest = tomllib.loads(git("show", f"{commit}:modules/logyard-api/zolt.toml"))
    version = manifest["project"]["version"]
    if tag != f"v{version}" or version.endswith("-SNAPSHOT"):
        raise ValueError("release tag must match the nonsnapshot artifact version")
    return {"commit": commit, "tag": tag, "tag_object": tag_object, "version": version}


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    result = verify(root, os.environ["RELEASE_TAG"], os.environ["TRUSTED_MAIN_SHA"],
                    root / ".github/release-signing-key.asc")
    if output := os.environ.get("GITHUB_OUTPUT"):
        with Path(output).open("a", encoding="utf-8") as destination:
            for key, value in result.items():
                destination.write(f"{key}={value}\n")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
