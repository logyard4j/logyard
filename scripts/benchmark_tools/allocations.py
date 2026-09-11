from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


def main() -> None:
    parser = argparse.ArgumentParser(description="Enforce stable JMH allocation-per-operation budgets.")
    parser.add_argument("budget_file", type=Path)
    parser.add_argument("result_file", type=Path, nargs="+")
    arguments = parser.parse_args()

    budgets = read_json(arguments.budget_file)["budgets"]
    scaling_budgets = read_json(arguments.budget_file).get("scalingBudgets", [])
    results = [result for result_file in arguments.result_file for result in read_json(result_file)]
    failures: list[str] = []
    for budget in budgets:
        check_allocation(results, budget, failures)
    for budget in scaling_budgets:
        check_scaling(results, budget, failures)

    if failures:
        raise SystemExit("Allocation budget check failed:\n  " + "\n  ".join(failures))
    print(f"Allocation budgets passed for {len(budgets)} benchmark scenarios and {len(scaling_budgets)} scaling relationship(s).")


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def find_results(results: list[dict[str, Any]], budget: dict[str, Any]) -> list[dict[str, Any]]:
    expected_params = budget.get("params", {})
    return [result for result in results if result.get("benchmark") == budget["benchmark"] and result.get("params", {}) == expected_params]


def check_allocation(results: list[dict[str, Any]], budget: dict[str, Any], failures: list[str]) -> None:
    matches = find_results(results, budget)
    label = result_label(budget)
    if not matches:
        failures.append(f"{label}: benchmark result is missing")
    for result in matches:
        actual = metric_score(result, "secondaryMetrics", "gc.alloc.rate.norm")
        if actual is None or actual < 0:
            failures.append(f"{label}: gc.alloc.rate.norm must be finite and nonnegative")
            continue
        maximum = float(budget["maxBytesPerOperation"])
        if actual > maximum:
            failures.append(f"{label}: allocated {actual:.3f} B/op; budget is {maximum:.3f} B/op")


def result_label(budget: dict[str, Any]) -> str:
    params = budget.get("params", {})
    suffix = "" if not params else " " + ",".join(f"{name}={value}" for name, value in sorted(params.items()))
    return budget["benchmark"] + suffix


def check_scaling(results: list[dict[str, Any]], budget: dict[str, Any], failures: list[str]) -> None:
    small_results = find_results(results, {"benchmark": budget["benchmark"], "params": budget["smallParams"]})
    large_results = find_results(results, {"benchmark": budget["benchmark"], "params": budget["largeParams"]})
    label = budget["benchmark"] + " scaling"
    if not small_results or not large_results:
        failures.append(f"{label}: benchmark result is missing")
        return
    modes = {result.get("mode") for result in small_results + large_results}
    for mode in modes:
        small = [result for result in small_results if result.get("mode") == mode]
        large = [result for result in large_results if result.get("mode") == mode]
        if len(small) != 1 or len(large) != 1:
            failures.append(f"{label}: expected one small and large result per mode")
            continue
        check_scaling_pair(small[0], large[0], budget, failures)


def check_scaling_pair(small: dict[str, Any], large: dict[str, Any], budget: dict[str, Any], failures: list[str]) -> None:
    label = budget["benchmark"] + " scaling"
    small_allocation = metric_score(small, "secondaryMetrics", "gc.alloc.rate.norm")
    large_allocation = metric_score(large, "secondaryMetrics", "gc.alloc.rate.norm")
    small_time = metric_score(small, "primaryMetric")
    large_time = metric_score(large, "primaryMetric")
    if None in (small_allocation, large_allocation, small_time, large_time):
        failures.append(f"{label}: allocation and primary timing metrics must be finite")
        return
    if small_allocation <= 0 or large_allocation < 0 or small_time <= 0 or large_time <= 0:
        failures.append(f"{label}: allocation and timing metrics must be positive (large allocation may be zero)")
        return
    if small.get("mode") == "thrpt":
        small_time, large_time = 1 / small_time, 1 / large_time
    if large_allocation > small_allocation * float(budget["maxAllocationRatio"]):
        failures.append(f"{label}: allocation ratio {large_allocation / small_allocation:.3f} exceeds {float(budget['maxAllocationRatio']):.3f}")
    if large_time > small_time * float(budget["maxTimeRatio"]):
        failures.append(f"{label}: time ratio {large_time / small_time:.3f} exceeds {float(budget['maxTimeRatio']):.3f}")


def metric_score(result: dict[str, Any], *path: str) -> float | None:
    current: Any = result
    for component in path:
        if not isinstance(current, dict):
            return None
        current = current.get(component)
    if isinstance(current, dict):
        current = current.get("score")
    return float(current) if isinstance(current, (int, float)) and not isinstance(current, bool) and math.isfinite(current) else None


if __name__ == "__main__":
    main()
