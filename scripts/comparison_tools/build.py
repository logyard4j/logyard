from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import threading
import tomllib
import zipfile
from dataclasses import dataclass
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


PROVIDERS = {
    "logyard": "com.logyard4j.slf4j.LogyardServiceProvider",
    "logback": "ch.qos.logback.classic.spi.LogbackServiceProvider",
    "log4j2": "org.apache.logging.slf4j.SLF4JServiceProvider",
}


class QuietRepository(SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


@dataclass(frozen=True)
class BuiltProvider:
    name: str
    classpath: str
    directory: Path
    artifacts: list[dict[str, str]]


class ProviderBuilds:
    def __init__(self, root: Path, run: Path, cache: Path) -> None:
        self.root = root
        self.run = run
        self.cache = cache
        self.repository = (root / "target/release-bundle").resolve()
        self.zolt = os.environ.get("ZOLT") or shutil.which("zolt") or str(Path.home() / ".zolt/bin/zolt")

    def __enter__(self) -> ProviderBuilds:
        # Only this harness owns the comparison cache; same-version Logyard artifacts must be refreshed.
        shutil.rmtree(self.cache / "com/logyard4j", ignore_errors=True)
        handler = partial(QuietRepository, directory=str(self.repository))
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *args: object) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def build(self, provider: str) -> BuiltProvider:
        sources = self.root / "benchmarks/comparison"
        directory = self.run / "projects" / provider
        shutil.copytree(sources / provider, directory)
        shutil.copytree(sources / "common/src", directory / "src", dirs_exist_ok=True)
        manifest = directory / "zolt.toml"
        manifest.write_text(manifest.read_text() + f'\n[repositories]\nlogyard = "http://127.0.0.1:{self.server.server_port}"\n'
                            'central = "https://repo.maven.apache.org/maven2"\n', encoding="utf-8")
        with (self.run / f"{provider}-build.log").open("w", encoding="utf-8") as log:
            for command in ("resolve", "build", "test"):
                subprocess.run(self.command(command), cwd=directory, check=True, timeout=300, stdout=log, stderr=subprocess.STDOUT)
        classpath = subprocess.check_output(self.command("classpath", "runtime"), cwd=directory, timeout=30, text=True).strip()
        artifacts = self.artifacts(directory)
        self.check_provider(provider, classpath)
        return BuiltProvider(provider, os.pathsep.join((str(directory / "target/classes"), classpath)), directory, artifacts)

    def command(self, *arguments: str) -> list[str]:
        return [self.zolt, "--no-progress", "--color", "never", *arguments, "--cache-root", str(self.cache)]

    def artifacts(self, directory: Path) -> list[dict[str, str]]:
        artifacts = []
        for package in tomllib.loads((directory / "zolt.lock").read_text())["package"]:
            if package.get("source") == "workspace":
                raise AssertionError("comparison dependencies must resolve packaged artifacts")
            for kind in ("pom", "jar"):
                if kind not in package:
                    continue
                cached = self.cache / package[kind]
                digest = hashlib.sha256(cached.read_bytes()).hexdigest()
                if package["id"].startswith("com.logyard4j:"):
                    group, artifact = package["id"].split(":")[:2]
                    bundled = self.repository / group.replace(".", "/") / artifact / package["version"] / f"{artifact}-{package['version']}.{kind}"
                    if digest != hashlib.sha256(bundled.read_bytes()).hexdigest():
                        raise AssertionError("comparison resolved a different Logyard artifact: " + package["id"])
                artifacts.append({"id": package["id"], "version": package["version"], "kind": kind, "sha256": digest})
        return artifacts

    @staticmethod
    def check_provider(provider: str, classpath: str) -> None:
        discovered = []
        service = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"
        for entry in classpath.split(os.pathsep):
            if not entry.endswith(".jar"):
                continue
            with zipfile.ZipFile(entry) as jar:
                if service in jar.namelist():
                    discovered.extend(line.split("#", 1)[0].strip() for line in jar.read(service).decode().splitlines()
                                      if line.split("#", 1)[0].strip())
        if discovered != [PROVIDERS[provider]]:
            raise AssertionError(f"expected exactly the {provider} SLF4J provider; found {discovered}")
