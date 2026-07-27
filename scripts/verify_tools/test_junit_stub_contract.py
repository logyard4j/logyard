from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ASSERTIONS = ROOT / "scripts/verify-support/junit-stub-src/org/junit/jupiter/api/Assertions.java"
STATIC_ASSERTION = re.compile(r"import\s+static\s+org\.junit\.jupiter\.api\.Assertions\.(?P<method>[A-Za-z0-9_]+)\s*;")
STUB_METHOD = re.compile(r"public\s+static\s+(?:<[^>]+>\s+)?[A-Za-z0-9_<>, ?]+\s+(?P<method>assert[A-Za-z0-9_]+)\s*\(")


class JunitStubContractTest(unittest.TestCase):
    def test_fallback_assertions_cover_every_static_assertion_import(self) -> None:
        declared = set(STUB_METHOD.findall(ASSERTIONS.read_text(encoding="utf-8")))
        used: set[str] = set()
        for source_root in (ROOT / "modules", ROOT / "extensions", ROOT / "tests"):
            for source in source_root.rglob("*.java"):
                if "target" not in source.parts:
                    used.update(STATIC_ASSERTION.findall(source.read_text(encoding="utf-8")))
        self.assertEqual(set(), used - declared, f"fallback JUnit Assertions is missing: {sorted(used - declared)}")
