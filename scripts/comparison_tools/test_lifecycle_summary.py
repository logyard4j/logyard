import json
import tempfile
import unittest
from pathlib import Path

from .lifecycle_summary import latest_run, summarize


def output(written=20, dropped=0):
    return {
        "enqueued": written,
        "dropped": dropped,
        "written": written,
        "unwritten_info": dropped,
        "unwritten_error": 0,
        "warmup_enqueued": 5,
        "warmup_dropped": 5,
    }


def cycle(number, *, written=20, dropped=0, heap=100):
    return {
        "cycle": number,
        "heap_after_gc": heap,
        "open_descriptors": 10,
        "live_threads": 5,
        "delivery": {
            "attempted": written + dropped,
            "warmup_attempted": 10,
            "reloads": 3,
            "threads_missing_at_end": 0,
            "outputs": {
                "first": output(written, dropped),
                "second": output(written, dropped),
            },
        },
    }


class LifecycleSummaryTest(unittest.TestCase):
    def test_reconciles_completed_delivery_and_resource_evidence(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            run = self.write_run(root, [cycle(1), cycle(2, heap=120)])

            summary = summarize(run)

            self.assertEqual("passed", summary["status"])
            self.assertEqual(40, summary["attempted"])
            self.assertEqual(6, summary["reloads"])
            self.assertEqual(40, summary["outputs"]["first"]["written"])
            self.assertEqual({"baseline": 100, "maximum": 120, "limit": 32},
                             summary["resources"]["heap_after_gc"])
            self.assertEqual(run, latest_run(root))

    def test_reconciles_bounded_output_loss(self):
        with tempfile.TemporaryDirectory() as name:
            run = self.write_run(Path(name), [cycle(1, written=19, dropped=1)],
                                 requested=1, completed=1)

            summary = summarize(run)

            self.assertEqual("passed", summary["status"])
            self.assertEqual(20, summary["attempted"])
            self.assertEqual(19, summary["outputs"]["first"]["written"])
            self.assertEqual(1, summary["outputs"]["first"]["unwritten_info"])

    def test_rejects_passed_receipt_missing_output_pair(self):
        with tempfile.TemporaryDirectory() as name:
            row = cycle(1)
            row["delivery"]["outputs"].pop("second")
            run = self.write_run(Path(name), [row], requested=1, completed=1)

            with self.assertRaisesRegex(AssertionError, "output pair"):
                summarize(run)

    def test_rejects_passed_receipt_without_requested_work(self):
        with tempfile.TemporaryDirectory() as name:
            row = cycle(1, written=0)
            row["delivery"]["reloads"] = 0
            run = self.write_run(Path(name), [row], requested=1, completed=1)

            with self.assertRaisesRegex(AssertionError, "measured work"):
                summarize(run)

    def test_rejects_a_passed_receipt_with_incomplete_cycles(self):
        with tempfile.TemporaryDirectory() as name:
            run = self.write_run(Path(name), [cycle(1)], completed=1)

            with self.assertRaisesRegex(AssertionError, "every requested cycle"):
                summarize(run)

    def test_rejects_inconsistent_output_accounting(self):
        with tempfile.TemporaryDirectory() as name:
            row = cycle(1)
            row["delivery"]["outputs"]["first"]["written"] = 19
            run = self.write_run(Path(name), [row], requested=1, completed=1)

            with self.assertRaisesRegex(AssertionError, "measured delivery mismatch"):
                summarize(run)

    @staticmethod
    def write_run(root, rows, *, requested=2, completed=2):
        run = root / "target/benchmark-lifecycle-soak/20260914-000000-test"
        run.mkdir(parents=True)
        receipt = {
            "revision": "a" * 40,
            "tree": "b" * 40,
            "status": "passed",
            "requested_cycles": requested,
            "completed_cycles": completed,
            "records_per_cycle": 20,
            "elapsed_seconds": 12.5,
            "baseline_cycle": 1,
            "heap_growth_limit_bytes": 32,
            "descriptor_growth_limit": 4,
            "thread_growth_limit": 4,
        }
        (run / "receipt.json").write_text(json.dumps(receipt), encoding="utf-8")
        (run / "cycles.jsonl").write_text(
            "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8")
        return run
