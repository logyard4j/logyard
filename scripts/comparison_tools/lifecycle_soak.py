"""One JVM, repeated packaged reload/shutdown, externally reconciled output, bounded retention."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import platform
import queue
import shutil
import signal
import subprocess
import threading
import time
import uuid
from pathlib import Path

from .build import ProviderBuilds
from .qualification import candidate, require_candidate, save
from .records import reject_constant, unique_object
from .reload_workload import validate


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def archive(directory: Path) -> None:
    for source in directory.glob("*.jsonl"):
        compressed = source.with_suffix(".jsonl.gz")
        with source.open("rb") as incoming, gzip.open(compressed, "wb", compresslevel=6) as outgoing:
            shutil.copyfileobj(incoming, outgoing)
        with gzip.open(compressed, "rb") as restored:
            if hashlib.file_digest(restored, "sha256").hexdigest() != digest(source):
                raise AssertionError("compressed evidence differs from validated output")
        source.unlink()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--cycles", type=int, default=12)
    args = parser.parse_args()
    if platform.system() != "Linux":
        parser.error("lifecycle soak requires Linux file-descriptor accounting")
    if not 1 <= args.cycles <= 100_000:
        parser.error("expected 1 to 100000 cycles")
    root = args.root.resolve()
    cycles = args.cycles
    identity = candidate(root)
    run = root / "target/benchmark-lifecycle-soak" / (
        time.strftime("%Y%m%d-%H%M%S", time.gmtime()) + "-" + uuid.uuid4().hex[:8])
    run.mkdir(parents=True)
    sources = subprocess.check_output(
        ["git", "ls-files", "-z", "--", "benchmarks/comparison", "scripts/comparison_tools",
         "scripts/benchmark-lifecycle-soak"], cwd=root, timeout=30).decode().split("\0")
    source_hashes = {name: digest(root / name) for name in sources if name}
    receipt = {**identity, "status": "running", "scope": "one-JVM reload/shutdown lifecycle soak",
               "requested_cycles": cycles, "completed_cycles": 0, "source_sha256": source_hashes,
               "records_per_cycle": 20_000, "events_per_second": 2_000,
               "retained_output": "first and latest two validated cycles; unvalidated output stays raw",
               "heap_growth_limit_bytes": 32 * 1024 * 1024, "baseline_cycle": 10,
               "descriptor_growth_limit": 4, "thread_growth_limit": 4}
    save(run / "receipt.json", receipt)

    def interrupted(signum, frame):
        raise KeyboardInterrupt("soak interrupted")

    signal.signal(signal.SIGTERM, interrupted)
    process = None
    started = time.monotonic()
    try:
        print(f"Lifecycle soak {identity['revision']}: {run}", flush=True)
        with (run / "packaging.log").open("w") as log:
            subprocess.run([str(root / "scripts/release-bundle")], cwd=root, check=True, timeout=1200,
                           env={**os.environ, "LOGYARD_RELEASE_SKIP_PACKAGE": "0",
                                "LOGYARD_RELEASE_TARGET": str(root / "target/release-bundle")},
                           stdout=log, stderr=subprocess.STDOUT)
        require_candidate(root, identity)
        with ProviderBuilds(root, run, run / "cache") as builds:
            built = builds.build("logyard")
        save(run / "artifacts.json", built.artifacts)
        java = shutil.which("java")
        save(run / "environment.json", {
            "platform": platform.platform(), "cpu_affinity": sorted(os.sched_getaffinity(0)),
            "java": subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True, timeout=30),
        })
        command = [java, "-Xms64m", "-Xmx256m", "-XX:ActiveProcessorCount=4", "-cp", built.classpath,
                   "com.logyard4j.logyard.compare.RuntimeLifecycleSoak", str(run), str(cycles)]
        save(run / "command.json", command)
        process = subprocess.Popen(command, cwd=root, text=True, stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, bufsize=1)
        receipt["pid"] = process.pid
        save(run / "receipt.json", receipt)
        lines: queue.Queue[str | None] = queue.Queue(maxsize=128)

        def read_lines() -> None:
            for line in process.stdout:
                lines.put(line)
            lines.put(None)

        reader = threading.Thread(target=read_lines, daemon=True)
        reader.start()
        result = None
        with (run / "jvm.log").open("w") as log, (run / "cycles.jsonl").open("w") as evidence:
            while True:
                line = lines.get(timeout=120)
                if line is None:
                    break
                log.write(line)
                log.flush()
                if line.startswith("RELOAD_RESULT "):
                    if result is not None:
                        raise AssertionError("duplicate reload result before cycle completion")
                    result = json.loads(line.removeprefix("RELOAD_RESULT "),
                                        object_pairs_hook=unique_object, parse_constant=reject_constant)
                if not line.startswith("SOAK_CYCLE "):
                    continue
                metrics = json.loads(line.removeprefix("SOAK_CYCLE "))
                cycle = metrics["cycle"]
                if cycle != receipt["completed_cycles"] + 1 or result is None:
                    raise AssertionError("missing or out-of-order cycle evidence")
                directory = run / f"cycle-{cycle}"
                validate(result, directory, 20_000)
                row = {**metrics, "delivery": result}
                evidence.write(json.dumps(row) + "\n")
                evidence.flush()
                save(directory / "result.json", row)
                archive(directory)
                expired = cycle - 2
                if expired > 1:
                    shutil.rmtree(run / f"cycle-{expired}")
                result = None
                require_candidate(root, identity)
                receipt.update(completed_cycles=cycle, elapsed_seconds=time.monotonic() - started,
                               latest=metrics)
                save(run / "receipt.json", receipt)
                print(f"PASS cycle {cycle}: 20000 attempts reconciled across both outputs; "
                      f"heap={metrics['heap_after_gc']} fds={metrics['open_descriptors']} "
                      f"threads={metrics['live_threads']}", flush=True)
                process.stdin.write("continue\n")
                process.stdin.flush()
        if process.wait(timeout=10) != 0 or receipt["completed_cycles"] != cycles:
            raise AssertionError("soak JVM failed or stopped before completing its cycles")
        receipt["status"] = "passed"
        (run.parent / "latest.txt").write_text(str(run) + "\n")
    except BaseException as failure:
        receipt.update(status="interrupted" if isinstance(failure, KeyboardInterrupt) else "failed",
                       failure=repr(failure))
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
        raise
    finally:
        if process is not None:
            for stream in (process.stdin, process.stdout):
                try:
                    stream.close()
                except OSError:
                    pass
        receipt["elapsed_seconds"] = time.monotonic() - started
        save(run / "receipt.json", receipt)


if __name__ == "__main__":
    main()
