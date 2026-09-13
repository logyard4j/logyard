"""Longer, isolated Logyard delivery workloads against freshly packaged candidate artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import time
import uuid
from dataclasses import asdict
from pathlib import Path

from .build import ProviderBuilds
from .results import validate
from .reload_workload import execute as execute_reload
from .run import Case, execute


def candidate(root: Path) -> dict[str, str]:
    def git(*args: str) -> str:
        return subprocess.check_output(["git", *args], cwd=root, text=True, timeout=30).strip()

    if git("status", "--porcelain"):
        raise AssertionError("delivery qualification requires a clean committed checkout")
    return {"revision": git("rev-parse", "HEAD"), "tree": git("rev-parse", "HEAD^{tree}")}


def require_candidate(root: Path, expected: dict[str, str]) -> None:
    if candidate(root) != expected:
        raise AssertionError("candidate changed during delivery qualification")


def experiments() -> list[tuple[Case, int]]:
    common = dict(events=50_000, arguments=2, fields=4, format="native-json", rate=5_000, policy="default")
    return [
        *((Case(f"platform-{producers}", producers=producers, **common), 0) for producers in (1, 4, 16, 64)),
        (Case("warmed-virtual-64", producers=64, virtual=True, **common), 0),
        (Case("fresh-virtual-64", producers=64, virtual_per_request=True, **common), 0),
        (Case("slow-output", producers=16, stall_ms=100, delay_us=250, **common), 0),
        (Case("one-cpu", producers=16, **common), 1),
    ]


def save(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def environment(root: Path, java: str, identity: dict[str, str]) -> dict[str, object]:
    sources = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    value: dict[str, object] = {
        **identity, "platform": platform.platform(), "cpu_count": os.cpu_count(),
        "available_cpu_affinity": sorted(os.sched_getaffinity(0)),
        "java": subprocess.run([java, "-version"], check=True, timeout=30, text=True,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout,
        "sources_sha256": {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in sources if name},
        "forks_per_experiment": 3,
        "experiments": [{"case": asdict(case), "cpus": cpus} for case, cpus in experiments()],
        "warmup_calls_per_producer": "max(200, 10000 / producers)",
        "reload_workload": {"forks": 3, "events": 50_000, "rate": 5_000, "producers": 16,
                            "outputs": 2, "buffer_bytes": 4096, "flush_millis": 10,
                            "reload_interval_millis": 250, "exception_every": 8},
    }
    cpu = Path("/proc/cpuinfo")
    value["cpu_model"] = next((line.split(":", 1)[1].strip() for line in cpu.read_text().splitlines()
                               if line.startswith("model name")), None) if cpu.is_file() else None
    for name in ("cpu.max", "memory.max"):
        path = Path("/sys/fs/cgroup") / name
        value["cgroup_" + name.replace(".", "_")] = path.read_text().strip() if path.is_file() else None
    return value


def qualify(root: Path, java: str) -> Path:
    if not hasattr(os, "sched_getaffinity"):
        raise ValueError("delivery qualification requires Linux CPU affinity support")
    identity = candidate(root)
    target = root / "target/benchmark-delivery-qualification"
    run = target / (time.strftime("%Y%m%d-%H%M%S", time.gmtime()) + "-" + uuid.uuid4().hex[:8])
    run.mkdir(parents=True)
    receipt: dict[str, object] = {**identity, "status": "running", "scope": "Logyard native JSON delivery workloads"}
    results = []
    reload_results = []
    save(run / "qualification.json", receipt)
    try:
        save(run / "environment.json", environment(root, java, identity))
        print(f"Qualifying {identity['revision']}: {run}", flush=True)
        with (run / "packaging.log").open("w", encoding="utf-8") as log:
            # Always rebuild: comparing a stale bundle with stale local JARs cannot qualify this source.
            subprocess.run([str(root / "scripts/release-bundle")], cwd=root, check=True, timeout=1200,
                           env={**os.environ, "LOGYARD_RELEASE_SKIP_PACKAGE": "0",
                                "LOGYARD_RELEASE_TARGET": str(root / "target/release-bundle")},
                           stdout=log, stderr=subprocess.STDOUT)
        require_candidate(root, identity)
        with ProviderBuilds(root, run, run / "cache") as builds:
            provider = builds.build("logyard")
        save(run / "artifacts.json", provider.artifacts)
        plan = experiments()
        for repetition in range(3):
            iteration = run / f"iteration-{repetition + 1}"
            iteration.mkdir()
            for case, cpus in plan[repetition:] + plan[:repetition]:
                result = execute(provider, case, iteration, java, cpus)
                records = validate(result, iteration / str(result["output"]), case.format, case.fields)
                if not records:
                    raise AssertionError(f"no records completed: {case.name}")
                if case.stall_ms and len(records) == case.events:
                    raise AssertionError("slow output did not exercise overload")
                result["iteration"] = repetition + 1
                results.append(result)
                save(run / "results.json", results)
                print(f"PASS fork {repetition + 1} {case.name}: {len(records)}/{case.events} written; "
                      f"unwritten INFO={result['unwritten_info']}, ERROR={result['unwritten_error']}", flush=True)
            reloaded = execute_reload(provider, iteration, java)
            reloaded["iteration"] = repetition + 1
            reload_results.append(reloaded)
            save(run / "reload-results.json", reload_results)
            print(f"PASS fork {repetition + 1} buffered reload: {reloaded['reloads']} applied; "
                  f"written first={reloaded['outputs']['first']['written']}, "
                  f"second={reloaded['outputs']['second']['written']}", flush=True)
        require_candidate(root, identity)
        receipt.update(status="passed", forks=len(results),
                       attempted=sum(result["attempted"] for result in results),
                       written=sum(result["sink_written"] for result in results),
                       unwritten_info=sum(result["unwritten_info"] for result in results),
                       unwritten_error=sum(result["unwritten_error"] for result in results),
                       total_forks=len(results) + len(reload_results),
                       reload={"forks": len(reload_results),
                               "attempted": sum(result["attempted"] for result in reload_results),
                               "applied": sum(result["reloads"] for result in reload_results),
                               "outputs": {name: {key: sum(result["outputs"][name][key] for result in reload_results)
                                                  for key in ("written", "unwritten_info", "unwritten_error")}
                                           for name in ("first", "second")}})
    except Exception as failure:
        receipt.update(status="failed", completed_forks=len(results) + len(reload_results), failure=str(failure))
        raise
    finally:
        save(run / "qualification.json", receipt)
    (target / "latest.txt").write_text(str(run) + "\n", encoding="utf-8")
    print(f"Delivery qualification passed: {run}", flush=True)
    return run


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("--java", default=shutil.which("java"))
    args = parser.parse_args()
    if not args.java:
        parser.error("a JDK is required")
    qualify(args.root.resolve(), args.java)


if __name__ == "__main__":
    main()
