from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def main() -> None:
    parser = argparse.ArgumentParser(description="Enforce stable JMH allocation-per-operation budgets.")
    parser.add_argument("budget_file", type=Path)
    parser.add_argument("result_file", type=Path, nargs="+")
    arguments = parser.parse_args()

    budgets = read_json(arguments.budget_file)["budgets"]
    scaling_budgets = read_json(arguments.budget_file).get("scalingBudgets", [])
    results = [
        result
        for result_file in arguments.result_file
        for result in read_json(result_file)
    ]
    failures: list[str] = []
    for budget in budgets:
        result = find_result(results, budget)
        label = result_label(budget)
        if result is None:
            failures.append(f"{label}: benchmark result is missing")
            continue
        metric = result.get("secondaryMetrics", {}).get("gc.alloc.rate.norm")
        if not isinstance(metric, dict) or not isinstance(metric.get("score"), (int, float)):
            failures.append(f"{label}: gc.alloc.rate.norm is missing")
            continue
        actual = float(metric["score"])
        maximum = float(budget["maxBytesPerOperation"])
        if actual > maximum:
            failures.append(f"{label}: allocated {actual:.3f} B/op; budget is {maximum:.3f} B/op")
    for budget in scaling_budgets:
        check_scaling(results, budget, failures)

    if failures:
        raise SystemExit("Allocation budget check failed:\n  " + "\n  ".join(failures))
    print(
        f"Allocation budgets passed for {len(budgets)} benchmark scenarios "
        f"and {len(scaling_budgets)} scaling relationship(s)."
    )


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def find_result(results: list[dict[str, Any]], budget: dict[str, Any]) -> dict[str, Any] | None:
    expected_params = budget.get("params", {})
    return next(
        (
            result
            for result in results
            if result.get("benchmark") == budget["benchmark"]
            and result.get("params", {}) == expected_params
        ),
        None,
    )


def result_label(budget: dict[str, Any]) -> str:
    params = budget.get("params", {})
    suffix = "" if not params else " " + ",".join(f"{name}={value}" for name, value in sorted(params.items()))
    return budget["benchmark"] + suffix


def check_scaling(
    results: list[dict[str, Any]],
    budget: dict[str, Any],
    failures: list[str],
) -> None:
    small = find_result(results, {"benchmark": budget["benchmark"], "params": budget["smallParams"]})
    large = find_result(results, {"benchmark": budget["benchmark"], "params": budget["largeParams"]})
    label = budget["benchmark"] + " scaling"
    if small is None or large is None:
        failures.append(f"{label}: benchmark result is missing")
        return
    small_allocation = metric_score(small, "secondaryMetrics", "gc.alloc.rate.norm")
    large_allocation = metric_score(large, "secondaryMetrics", "gc.alloc.rate.norm")
    small_time = metric_score(small, "primaryMetric")
    large_time = metric_score(large, "primaryMetric")
    if None in (small_allocation, large_allocation, small_time, large_time):
        failures.append(f"{label}: allocation or primary timing metric is missing")
        return
    if small_allocation <= 0 or small_time <= 0:
        failures.append(f"{label}: small-case allocation and timing metrics must be positive")
        return
    if large_allocation > small_allocation * float(budget["maxAllocationRatio"]):
        failures.append(
            f"{label}: allocation ratio {large_allocation / small_allocation:.3f} exceeds "
            f"{float(budget['maxAllocationRatio']):.3f}"
        )
    if large_time > small_time * float(budget["maxTimeRatio"]):
        failures.append(
            f"{label}: time ratio {large_time / small_time:.3f} exceeds "
            f"{float(budget['maxTimeRatio']):.3f}"
        )


def metric_score(result: dict[str, Any], *path: str) -> float | None:
    current: Any = result
    for component in path:
        if not isinstance(current, dict):
            return None
        current = current.get(component)
    if isinstance(current, dict):
        current = current.get("score")
    return float(current) if isinstance(current, (int, float)) else None


if __name__ == "__main__":
    main()
