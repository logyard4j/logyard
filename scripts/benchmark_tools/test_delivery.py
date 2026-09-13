from __future__ import annotations

import unittest

from .delivery import check, reconcile, required_scenarios


class BenchmarkDeliveryTest(unittest.TestCase):
    def test_reconciles_drops_without_counting_them_as_delivery(self) -> None:
        reconcile(admission())

    def test_rejects_incomplete_drain(self) -> None:
        record = admission()
        record["delegate_observed"] = 5
        with self.assertRaisesRegex(ValueError, "reach the delegate"):
            reconcile(record)

    def test_requires_maintained_saturation(self) -> None:
        record = admission()
        record.update(kind="overflow", params={"action": "DROP"})
        with self.assertRaisesRegex(ValueError, "overflow branch"):
            reconcile(record)

    def test_rejects_missing_file_records(self) -> None:
        with self.assertRaisesRegex(ValueError, "completed records"):
            reconcile({"kind": "file", "benchmark_calls": 10, "sink_written": 9, "file_bytes": 100})

    def test_requires_all_results_including_single_shot_cases(self) -> None:
        self.assertEqual(len(required_scenarios("smoke")), len(check([], [], "smoke")))
        result = {"benchmark": "com.logyard4j.logyard.benchmarks.ingress.Slf4jProviderFirstCallBenchmark.firstCall",
                  "mode": "thrpt", "primaryMetric": {"score": 1}}
        self.assertTrue(any("finite ss" in failure for failure in check([result], [], "smoke")))

    def test_byte_stream_evidence_requires_every_record_and_nonempty_output(self) -> None:
        record = {"kind": "counting-stream", "benchmark_calls": 10, "sink_written": 10, "bytes": 100}
        reconcile(record)
        with self.assertRaisesRegex(ValueError, "completed records"):
            reconcile(record | {"sink_written": 9})
        with self.assertRaisesRegex(ValueError, "output is empty"):
            reconcile(record | {"bytes": 0})


def admission() -> dict[str, object]:
    return {"kind": "admission", "benchmark_calls": 10, "primed": 0, "attempted": 10,
            "accepted": 6, "enqueued": 6, "dropped": 4, "synchronous_fallbacks": 0,
            "emergency_fallbacks": 0, "delegate_accepted": 6, "delegate_observed": 6}
