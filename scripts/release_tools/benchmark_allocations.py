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

    if failures:
        raise SystemExit("Allocation budget check failed:\n  " + "\n  ".join(failures))
    print(f"Allocation budgets passed for {len(budgets)} benchmark scenarios.")


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


if __name__ == "__main__":
    main()
