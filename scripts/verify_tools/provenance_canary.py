"""Exercise release provenance with disposable keys and repositories; never publish."""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

from verify_tools.release_provenance import verify


class ProvenanceCanary(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temporary = tempfile.TemporaryDirectory(prefix="logyard-provenance-canary-")
        cls.root = Path(cls.temporary.name)
        home = cls.root / "gnupg"
        home.mkdir(mode=0o700)
        cls.environment = {**os.environ, "GNUPGHOME": str(home)}
        cls.run_command("gpg", "--batch", "--pinentry-mode", "loopback", "--passphrase", "",
                        "--quick-generate-key", "Logyard canary <canary@invalid.example>", "ed25519", "sign", "0")
        keys = cls.run_command("gpg", "--with-colons", "--list-keys")
        cls.fingerprint = next(line.split(":")[9] for line in keys.splitlines() if line.startswith("fpr:"))
        cls.key = cls.root / "public.asc"
        cls.key.write_text(cls.run_command("gpg", "--armor", "--export", cls.fingerprint))
        cls.run_command("git", "init", "--initial-branch=main", str(cls.root))
        cls.git("config", "user.name", "Release canary")
        cls.git("config", "user.email", "canary@invalid.example")
        cls.git("config", "user.signingkey", cls.fingerprint)
        cls.git("config", "gpg.format", "openpgp")
        manifest = cls.root / "modules/logyard-api/zolt.toml"
        manifest.parent.mkdir(parents=True)
        manifest.write_text('[project]\nversion = "1.0.0"\n')
        cls.git("add", "modules")
        cls.git("-c", "commit.gpgsign=false", "commit", "-m", "test: create unsigned release commit")
        cls.commit = cls.git("rev-parse", "HEAD").strip()
        cls.git("-c", "gpg.program=gpg", "tag", "-s", "v1.0.0", "-m", "Release canary")

    @classmethod
    def tearDownClass(cls) -> None:
        cls.run_command("gpgconf", "--kill", "gpg-agent")
        cls.temporary.cleanup()

    @classmethod
    def run_command(cls, *args: str) -> str:
        return subprocess.run(args, env=cls.environment, check=True, timeout=30,
                              capture_output=True, text=True).stdout

    @classmethod
    def git(cls, *args: str) -> str:
        return cls.run_command("git", "-C", str(cls.root), *args)

    def check(self, tag: str = "v1.0.0", **overrides: str) -> dict[str, str]:
        return verify(self.root, tag, overrides.get("trusted_main", self.commit), self.key,
                      overrides.get("fingerprint", self.fingerprint))

    def test_signed_tag_authorizes_its_unsigned_commit(self) -> None:
        result = self.check()
        self.assertEqual(self.commit, result["commit"])
        self.assertEqual("1.0.0", result["version"])
        self.assertEqual(self.git("rev-parse", "refs/tags/v1.0.0").strip(), result["tag_object"])

    def test_rejects_wrong_pinned_identity(self) -> None:
        with self.assertRaisesRegex(ValueError, "pinned release key"):
            self.check(fingerprint="0" * 40)

    def test_rejects_lightweight_and_unsigned_tags(self) -> None:
        self.git("-c", "tag.gpgsign=false", "tag", "v0.0.0-lightweight")
        self.git("tag", "--no-sign", "-a", "v0.0.0-unsigned", "-m", "Unsigned tag")
        for tag in ("v0.0.0-lightweight", "v0.0.0-unsigned"):
            with self.subTest(tag=tag), self.assertRaises((ValueError, subprocess.CalledProcessError)):
                self.check(tag)

    def test_rejects_version_mismatch(self) -> None:
        self.git("-c", "gpg.program=gpg", "tag", "-s", "v2.0.0", self.commit, "-m", "Mismatched version")
        with self.assertRaisesRegex(ValueError, "artifact version"):
            self.check("v2.0.0")

    def test_rejects_commit_outside_trusted_main(self) -> None:
        self.git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", "test: newer commit")
        self.git("-c", "gpg.program=gpg", "tag", "-s", "v1.0.0-later", "-m", "Outside trusted main")
        with self.assertRaises(subprocess.CalledProcessError):
            self.check("v1.0.0-later")

    def test_rejects_unvalidated_inputs(self) -> None:
        for tag in ("../main", "v1\ninjected=true", "--help"):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "invalid release tag"):
                self.check(tag)
        with self.assertRaisesRegex(ValueError, "immutable commit SHA"):
            self.check(trusted_main="main")


if __name__ == "__main__":
    unittest.main()
