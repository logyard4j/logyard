from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any

PREFIX = "com.logyard4j.logyard.benchmarks."
JSON_METHODS = ("directFile", "directFileMechanics", "directStream", "synchronousRuntimeJson",
                "synchronousRuntimeFile", "synchronousRuntimeFileLiteral")


def required_scenarios(suite: str) -> list[tuple[str, dict[str, str], str]]:
    producers = ("oneProducer",) if suite == "smoke" else ("oneProducer", "fourProducers", "sixteenProducers", "sixtyFourProducers")
    return (
        [(PREFIX + "delivery.AsyncDeliveryBenchmark." + method, {}, "thrpt") for method in producers]
        + [(PREFIX + "delivery.OverflowPolicyBenchmark.overflow", {"action": action}, "thrpt") for action in ("DROP", "WAIT_DROP", "BLOCK", "STDERR")]
        + [(PREFIX + "delivery.SynchronousOverflowBenchmark.synchronousFallback", {}, "ss")]
        + [(PREFIX + "output.JsonSinkBenchmark." + method, {}, "thrpt") for method in JSON_METHODS]
        + [(PREFIX + "ingress.Slf4jProviderFirstCallBenchmark.firstCall", {}, "ss"),
           (PREFIX + "ingress.Slf4jProviderRecoveryBenchmark.recoverAfterManagedShutdown", {}, "ss")]
    )


def check(results: list[dict[str, Any]], evidence: list[dict[str, Any]], suite: str) -> list[str]:
    failures: list[str] = []
    for benchmark, params, mode in required_scenarios(suite):
        matches = [result for result in results if result.get("benchmark") == benchmark and result.get("params", {}) == params]
        label = benchmark + (" " + str(params) if params else "")
        if len(matches) != 1:
            failures.append(f"{label}: expected exactly one result")
            continue
        result = matches[0]
        score = result.get("primaryMetric", {}).get("score")
        if result.get("mode") != mode or not isinstance(score, (int, float)) or not math.isfinite(score) or score <= 0:
            failures.append(f"{label}: expected a positive finite {mode} result")
        if "ProviderFirstCall" in benchmark or "ProviderRecovery" in benchmark:
            continue
        records = [record for record in evidence if record.get("benchmark") == benchmark
                   and record.get("params", {}) == params and record.get("phase") == "MEASUREMENT"]
        expected = result["forks"] * result["measurementIterations"]
        identities = {(record.get("fork_pid"), record.get("sequence")) for record in records}
        if len(records) != expected or len(identities) != expected:
            failures.append(f"{label}: missing or duplicate measurement evidence")
        for record in records:
            if record.get("threads") != result["threads"]:
                failures.append(f"{label}: evidence thread count differs from JMH")
            try:
                reconcile(record)
            except (KeyError, ValueError) as error:
                failures.append(f"{label}: {error}")
    return failures


def reconcile(record: dict[str, Any]) -> None:
    def count(key: str) -> int:
        value = record[key]
        if type(value) is not int or value < 0:
            raise ValueError(f"invalid {key}")
        return value

    calls = count("benchmark_calls")
    if calls == 0:
        raise ValueError("measurement contains no benchmark calls")
    if record["kind"] in ("file", "counting-writer"):
        if calls != count("sink_written"):
            raise ValueError("calls and completed records differ")
        if count("file_bytes" if record["kind"] == "file" else "characters") == 0:
            raise ValueError("completed output is empty")
        return
    attempted, accepted = count("attempted"), count("accepted")
    if attempted != calls + count("primed") or attempted != accepted + count("dropped") + count("emergency_fallbacks"):
        raise ValueError("admission counts do not reconcile")
    if accepted != count("enqueued") + count("synchronous_fallbacks"):
        raise ValueError("accepted count does not reconcile")
    if accepted != count("delegate_accepted") or accepted != count("delegate_observed"):
        raise ValueError("accepted records did not all reach the delegate")
    if record["kind"] == "overflow":
        action = record.get("params", {}).get("action", "SYNC")
        outcome = {"DROP": "dropped", "WAIT_DROP": "dropped", "BLOCK": "emergency_fallbacks", "STDERR": "emergency_fallbacks", "SYNC": "synchronous_fallbacks"}[action]
        if count("enqueued") != count("primed") or count(outcome) != calls:
            raise ValueError("the intended overflow branch was not maintained")
    elif record["kind"] != "admission":
        raise ValueError("unknown evidence kind")


def main() -> None:
    parser = argparse.ArgumentParser(description="Reconcile benchmark output evidence and preserve declared measurement modes.")
    parser.add_argument("results", type=Path)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--suite", choices=("smoke", "all"), default="smoke")
    arguments = parser.parse_args()
    results = json.loads(arguments.results.read_text(encoding="utf-8"))
    evidence = [json.loads(line) for line in arguments.evidence.read_text(encoding="utf-8").splitlines()]
    failures = check(results, evidence, arguments.suite)
    if failures:
        raise SystemExit("Delivery evidence check failed:\n  " + "\n  ".join(failures))
    print("Benchmark delivery counts and measurement modes passed.")


if __name__ == "__main__":
    main()
