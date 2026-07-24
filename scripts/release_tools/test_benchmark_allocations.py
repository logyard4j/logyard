from __future__ import annotations

import unittest

from .benchmark_allocations import check_scaling


class BenchmarkAllocationScalingTest(unittest.TestCase):
    def test_acceptsNearLinearGrowth(self) -> None:
        failures: list[str] = []

        check_scaling(
            [result("1000", 1.0, 800_000), result("10000", 10.5, 8_500_000)],
            budget(),
            failures,
        )

        self.assertEqual([], failures)

    def test_rejectsQuadraticTimeAndAllocationGrowth(self) -> None:
        failures: list[str] = []

        check_scaling(
            [result("1000", 1.0, 800_000), result("10000", 80.0, 90_000_000)],
            budget(),
            failures,
        )

        self.assertEqual(2, len(failures))
        self.assertIn("allocation ratio", failures[0])
        self.assertIn("time ratio", failures[1])


def budget() -> dict[str, object]:
    return {
        "benchmark": "example.Management.list",
        "smallParams": {"loggerCount": "1000"},
        "largeParams": {"loggerCount": "10000"},
        "maxAllocationRatio": 12,
        "maxTimeRatio": 15,
    }


def result(logger_count: str, time: float, allocation: float) -> dict[str, object]:
    return {
        "benchmark": "example.Management.list",
        "params": {"loggerCount": logger_count},
        "primaryMetric": {"score": time},
        "secondaryMetrics": {"gc.alloc.rate.norm": {"score": allocation}},
    }


if __name__ == "__main__":
    unittest.main()
