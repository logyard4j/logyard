from __future__ import annotations

import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class ComparisonManifestTest(unittest.TestCase):
    def test_provider_classpaths_are_isolated_and_versions_are_pinned(self) -> None:
        version = tomllib.loads((ROOT / "modules/logyard-api/zolt.toml").read_text())["project"]["version"]
        expected = {
            "logyard": {"com.logyard4j:logyard-slf4j2": version, "com.logyard4j:logyard-runtime": version,
                        "org.slf4j:slf4j-api": "2.0.18"},
            "logback": {"ch.qos.logback:logback-classic": "1.6.3", "org.slf4j:slf4j-api": "2.0.18",
                        "net.logstash.logback:logstash-logback-encoder": "9.0"},
            "log4j2": {"org.apache.logging.log4j:log4j-slf4j2-impl": "2.26.1", "org.apache.logging.log4j:log4j-core": "2.26.1",
                       "org.slf4j:slf4j-api": "2.0.18", "com.lmax:disruptor": "4.0.0",
                       "org.apache.logging.log4j:log4j-layout-template-json": "2.26.1"},
        }
        for provider, dependencies in expected.items():
            with self.subTest(provider=provider):
                manifest = tomllib.loads((ROOT / "benchmarks/comparison" / provider / "zolt.toml").read_text())
                self.assertEqual(dependencies, manifest["dependencies"])
                self.assertEqual("21", manifest["project"]["java"])
