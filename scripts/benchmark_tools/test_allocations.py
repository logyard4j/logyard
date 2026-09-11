from __future__ import annotations

import unittest

from .allocations import check_allocation, check_scaling


class BenchmarkAllocationTest(unittest.TestCase):
    def test_rejects_missing_nonfinite_and_negative_scores(self) -> None:
        for score in (None, float("nan"), float("inf"), -1, True):
            with self.subTest(score=score):
                failures: list[str] = []
                check_allocation([result("1000", 1, score)], allocation_budget(), failures)
                self.assertEqual(1, len(failures))

    def test_checks_every_matching_result(self) -> None:
        failures: list[str] = []
        check_allocation([result("1000", 1, 0), result("1000", 1, 32)], allocation_budget(), failures)
        self.assertEqual(1, len(failures))
        self.assertIn("32.000 B/op", failures[0])

    def test_requires_a_matching_result(self) -> None:
        failures: list[str] = []
        check_allocation([], allocation_budget(), failures)
        self.assertEqual(1, len(failures))


class BenchmarkAllocationScalingTest(unittest.TestCase):
    def test_rejects_nonfinite_metrics(self) -> None:
        failures: list[str] = []
        check_scaling([result("1000", 1, 10), result("10000", float("nan"), 100)], budget(), failures)
        self.assertEqual(1, len(failures))

    def test_rejects_ambiguous_duplicate_results(self) -> None:
        failures: list[str] = []
        check_scaling([result("1000", 1, 10), result("1000", 1, 10), result("10000", 10, 100)], budget(), failures)
        self.assertEqual(1, len(failures))

    def test_compares_time_per_operation_for_throughput_results(self) -> None:
        small, large = result("1000", 100, 10), result("10000", 1, 100)
        small["mode"] = large["mode"] = "thrpt"
        failures: list[str] = []
        check_scaling([small, large], budget(), failures)
        self.assertEqual(1, len(failures))
        self.assertIn("time ratio", failures[0])

    def test_accepts_near_linear_growth(self) -> None:
        failures: list[str] = []
        check_scaling([result("1000", 1.0, 800_000), result("10000", 10.5, 8_500_000)], budget(), failures)
        self.assertEqual([], failures)

    def test_rejects_quadratic_time_and_allocation_growth(self) -> None:
        failures: list[str] = []
        check_scaling([result("1000", 1.0, 800_000), result("10000", 80.0, 90_000_000)], budget(), failures)
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


def allocation_budget() -> dict[str, object]:
    return {"benchmark": "example.Management.list", "params": {"loggerCount": "1000"}, "maxBytesPerOperation": 1}


def result(logger_count: str, time: float, allocation: float) -> dict[str, object]:
    return {
        "benchmark": "example.Management.list",
        "params": {"loggerCount": logger_count},
        "primaryMetric": {"score": time},
        "secondaryMetrics": {"gc.alloc.rate.norm": {"score": allocation}},
    }
