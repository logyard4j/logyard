"""Reconcile a lifecycle-soak receipt and emit a compact public summary."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from .records import reject_constant, unique_object


def load(path: Path) -> object:
    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=unique_object,
        parse_constant=reject_constant,
    )


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def latest_run(root: Path) -> Path:
    target = root / "target/benchmark-lifecycle-soak"
    runs = sorted(path.parent for path in target.glob("*/receipt.json"))
    require(bool(runs), "no lifecycle-soak receipt found")
    return runs[-1]


def summarize(run: Path) -> dict[str, object]:
    receipt = load(run / "receipt.json")
    require(isinstance(receipt, dict), "lifecycle receipt must be an object")
    ledger = run / "cycles.jsonl"
    rows = [] if not ledger.is_file() else [
        json.loads(line, object_pairs_hook=unique_object, parse_constant=reject_constant)
        for line in ledger.read_text(encoding="utf-8").splitlines()
    ]
    completed = receipt["completed_cycles"]
    requested = receipt["requested_cycles"]
    require(len(rows) == completed, "cycle ledger does not match completed cycle count")
    require([row["cycle"] for row in rows] == list(range(1, completed + 1)),
            "cycle ledger is not contiguous")
    if receipt["status"] == "passed":
        require(completed == requested, "passed receipt did not complete every requested cycle")

    attempted = 0
    warmup_attempted = 0
    reloads = 0
    threads_missing = 0
    outputs: dict[str, dict[str, int]] = {}
    for row in rows:
        delivery = row["delivery"]
        attempted += delivery["attempted"]
        warmup_attempted += delivery["warmup_attempted"]
        reloads += delivery["reloads"]
        threads_missing += delivery["threads_missing_at_end"]
        for name, output in delivery["outputs"].items():
            require(output["enqueued"] + output["dropped"] == delivery["attempted"],
                    f"measured admission mismatch in cycle {row['cycle']} output {name}")
            require(output["written"] + output["unwritten_info"]
                    + output["unwritten_error"] == output["enqueued"],
                    f"measured delivery mismatch in cycle {row['cycle']} output {name}")
            require(output["warmup_enqueued"] + output["warmup_dropped"]
                    == delivery["warmup_attempted"],
                    f"warmup admission mismatch in cycle {row['cycle']} output {name}")
            total = outputs.setdefault(name, {
                "written": 0, "dropped": 0, "unwritten_info": 0, "unwritten_error": 0,
                "warmup_enqueued": 0, "warmup_dropped": 0,
            })
            for field in total:
                total[field] += output[field]

    baseline_cycle = receipt["baseline_cycle"]
    resources = None
    if completed >= baseline_cycle:
        baseline = rows[baseline_cycle - 1]
        resources = {}
        for field, limit_field in (
                ("heap_after_gc", "heap_growth_limit_bytes"),
                ("open_descriptors", "descriptor_growth_limit"),
                ("live_threads", "thread_growth_limit")):
            maximum = max(row[field] for row in rows[baseline_cycle - 1:])
            require(maximum <= baseline[field] + receipt[limit_field],
                    f"{field} exceeds its lifecycle growth limit")
            resources[field] = {
                "baseline": baseline[field], "maximum": maximum, "limit": receipt[limit_field],
            }

    return {
        "revision": receipt["revision"],
        "tree": receipt["tree"],
        "status": receipt["status"],
        "requested_cycles": requested,
        "completed_cycles": completed,
        "elapsed_seconds": receipt.get("elapsed_seconds"),
        "attempted": attempted,
        "warmup_attempted": warmup_attempted,
        "reloads": reloads,
        "threads_missing_at_end": threads_missing,
        "outputs": outputs,
        "resources": resources,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    arguments = parser.parse_args()
    print(json.dumps(summarize(latest_run(arguments.root.resolve())), separators=(",", ":")))


if __name__ == "__main__":
    main()
