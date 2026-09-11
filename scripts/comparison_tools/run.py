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
from dataclasses import asdict, dataclass
from pathlib import Path

from .build import BuiltProvider, ProviderBuilds
from .results import equal_work, validate


@dataclass(frozen=True)
class Case:
    name: str
    events: int = 2000
    producers: int = 4
    arguments: int = 2
    fields: int = 0
    format: str = "text"
    rate: int = 0
    stall_ms: int = 0
    delay_us: int = 0
    virtual: bool = False
    policy: str = "matched-drop"
    disabled: str = "none"


def smoke_cases() -> list[Case]:
    return [
        *(Case("disabled-" + kind, events=10_000, producers=1, arguments=1, disabled=kind)
          for kind in ("classic", "fluent", "supplier")),
        Case("text"),
        Case("text-zero", producers=1, arguments=0),
        Case("text-64", producers=64, arguments=1),
        Case("json-empty", producers=1, format="json"),
        Case("json-mdc", arguments=4, fields=4, format="json"),
        Case("virtual-json", producers=16, arguments=1, fields=16, format="json", virtual=True),
        Case("overload", events=20_000, arguments=1, fields=4, format="json", rate=100_000, stall_ms=100, delay_us=50),
        Case("default-policy", policy="default"),
        Case("native-json-empty", producers=1, arguments=0, format="native-json"),
        Case("native-json-mdc", arguments=4, fields=4, format="native-json"),
        Case("native-json-virtual", producers=16, arguments=1, fields=16, format="native-json", virtual=True),
        Case("native-json-overload", events=20_000, arguments=2, fields=16, format="native-json",
             rate=100_000, stall_ms=100, delay_us=50),
    ]


def execute(built: BuiltProvider, case: Case, run: Path, java: str, cpus: int) -> dict[str, object]:
    output = run / f"{case.name}-{built.name}.jsonl"
    log = run / f"{case.name}-{built.name}.log"
    command = [java, "-Xms256m", "-Xmx256m"]
    affinity: list[int] = []
    if cpus:
        affinity = sorted(os.sched_getaffinity(0))[:cpus]
        if len(affinity) != cpus:
            raise ValueError("requested CPU count exceeds available affinity")
        command.extend([f"-XX:ActiveProcessorCount={cpus}"])
    command.extend(["-cp", built.classpath, "com.logyard4j.compare.ComparisonMain", str(output), str(case.events),
                    str(case.producers), str(case.arguments), str(case.fields), case.format, str(case.rate),
                    str(case.stall_ms), str(case.delay_us), str(case.virtual).lower(), case.policy, case.disabled])
    peak_rss = None
    started = time.monotonic()
    with log.open("w", encoding="utf-8") as stdout:
        with subprocess.Popen(command, cwd=built.directory, stdout=stdout, stderr=subprocess.STDOUT,
                              preexec_fn=(lambda: os.sched_setaffinity(0, affinity)) if affinity else None) as process:
            while process.poll() is None:
                if time.monotonic() - started > 120:
                    process.kill()
                    raise TimeoutError(f"comparison timed out: {case.name}/{built.name}; see {log}")
                status = Path(f"/proc/{process.pid}/status")
                try:
                    for line in status.read_text().splitlines():
                        if line.startswith("VmHWM:"):
                            peak_rss = max(peak_rss or 0, int(line.split()[1]) * 1024)
                except FileNotFoundError:
                    pass
                time.sleep(0.01)
            if process.returncode != 0:
                raise RuntimeError(f"comparison failed: {case.name}/{built.name}; see {log}\n{log.read_text()[-4000:]}")
    results = [json.loads(line.removeprefix("COMPARISON_RESULT ")) for line in log.read_text().splitlines()
               if line.startswith("COMPARISON_RESULT ")]
    if len(results) != 1:
        raise AssertionError(f"expected exactly one comparison result in {log}")
    result = results[0]
    result.update(case=asdict(case), whole_process_peak_rss_bytes=peak_rss, whole_process_elapsed_seconds=time.monotonic() - started,
                  cpu_affinity=affinity, jvm_arguments=command[1:command.index("-cp")], output=output.name)
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare isolated SLF4J providers using identical measured file output.")
    parser.add_argument("root", type=Path)
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--cpus", type=int, default=0, choices=(0, 1, 2, 4))
    parser.add_argument("--events", type=int, default=100_000)
    parser.add_argument("--producers", type=int, choices=(1, 4, 16, 64), default=4)
    parser.add_argument("--arguments", type=int, choices=(0, 1, 2, 4), default=2)
    parser.add_argument("--fields", type=int, choices=(0, 4, 16), default=0)
    parser.add_argument("--format", choices=("text", "json", "native-json"), default="text")
    parser.add_argument("--rate", type=int, default=0)
    parser.add_argument("--stall-ms", type=int, default=0)
    parser.add_argument("--delay-us", type=int, default=0)
    parser.add_argument("--virtual", action="store_true")
    parser.add_argument("--policy", choices=("matched-drop", "default"), default="matched-drop")
    parser.add_argument("--disabled", choices=("none", "classic", "fluent", "supplier"), default="none")
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()
    if not args.java or not 1 <= args.repeat <= 20:
        parser.error("a JDK and 1 to 20 repetitions are required")
    if args.cpus and not hasattr(os, "sched_getaffinity"):
        parser.error("CPU affinity experiments require Linux")
    root = args.root.resolve()
    target = (root / "target/benchmark-compare").resolve()
    run = target / (time.strftime("%Y%m%d-%H%M%S", time.gmtime()) + "-" + uuid.uuid4().hex[:8])
    run.mkdir(parents=True)
    cases = smoke_cases() if args.smoke else [Case("delivery", args.events, args.producers, args.arguments, args.fields, args.format,
                                                 args.rate, args.stall_ms, args.delay_us, args.virtual, args.policy, args.disabled)]
    versions: dict[str, object] = {}
    sources = {}
    for directory in (root / "benchmarks/comparison", root / "scripts/comparison_tools"):
        for source in sorted(directory.rglob("*")):
            if source.is_file() and "__pycache__" not in source.parts:
                sources[str(source.relative_to(root))] = hashlib.sha256(source.read_bytes()).hexdigest()
    environment = {
        "revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip(),
        "working_tree_changes": subprocess.check_output(["git", "status", "--porcelain"], cwd=root, text=True).splitlines(),
        "platform": platform.platform(), "cpu_count": os.cpu_count(), "sources_sha256": sources,
    }
    if hasattr(os, "sched_getaffinity"):
        environment["available_cpu_affinity"] = sorted(os.sched_getaffinity(0))
    for name in ("cpu.max", "memory.max"):
        path = Path("/sys/fs/cgroup") / name
        if path.is_file():
            environment["cgroup_" + name.replace(".", "_")] = path.read_text().strip()
    (run / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    with ProviderBuilds(root, run, target / "cache") as builds:
        providers = [builds.build(name) for name in ("logyard", "logback", "log4j2")]
    for provider in providers:
        versions[provider.name] = provider.artifacts
    (run / "artifacts.json").write_text(json.dumps(versions, indent=2) + "\n")
    results = []
    for repetition in range(args.repeat):
        iteration = run / f"iteration-{repetition + 1}"
        iteration.mkdir()
        for case in cases:
            outputs = {}
            case_results = []
            # Rotate order across repetitions to reduce persistent ordering bias.
            ordered = providers[repetition % 3:] + providers[:repetition % 3]
            for provider in ordered:
                result = execute(provider, case, iteration, args.java, args.cpus)
                records = validate(result, iteration / str(result["output"]), case.format, case.fields)
                if args.smoke and case.policy == "matched-drop" and case.disabled == "none" and case.stall_ms == 0 and len(records) != case.events:
                    raise AssertionError("unsaturated smoke workload lost records")
                if args.smoke and case.stall_ms and not 0 < len(records) < case.events:
                    raise AssertionError("overload smoke did not exercise both delivery and loss")
                outputs[provider.name] = records
                result["iteration"] = repetition + 1
                case_results.append(result)
            common = equal_work(outputs)
            if case.disabled == "none" and common == 0:
                raise AssertionError("no common delivered identities for equal-output verification")
            for result in case_results:
                result["equal_output_records"] = common
            results.extend(case_results)
            (run / "results.json").write_text(json.dumps(results, indent=2) + "\n")
            print(f"PASS {case.name}: equal output and reconciled completion for three isolated providers", flush=True)
    (target / "latest.txt").write_text(str(run) + "\n")
    print(f"Comparison results: {run}", flush=True)


if __name__ == "__main__":
    main()
