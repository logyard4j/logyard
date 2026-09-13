from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from .results import equal_work, validate, validate_capacity


class ComparisonResultsTest(unittest.TestCase):
    def test_rejects_queue_matching_that_ignores_a_worker_held_batch(self) -> None:
        result = {"policy": "matched-drop", "queue_capacity": 4096, "worker_batch_capacity_outside_queue": 4097}
        with self.assertRaisesRegex(AssertionError, "in-flight capacity.*8193"):
            validate_capacity(result, "matched-drop")

    def test_rejects_a_different_policy_or_invalid_capacity_measurement(self) -> None:
        result = {"policy": "default", "queue_capacity": 256, "worker_batch_capacity_outside_queue": 257}
        with self.assertRaisesRegex(AssertionError, "policies differ"):
            validate_capacity(result, "matched-drop")
        for invalid in (True, -1, None, 1.5):
            with self.subTest(value=invalid), self.assertRaisesRegex(AssertionError, "invalid.*capacity"):
                validate_capacity(result | {"worker_batch_capacity_outside_queue": invalid}, "default")

    def test_counts_only_complete_unique_file_records(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "events.log"
            output.write_text("ERROR 00000000 event\nINFO 00000001 event\n")
            result = summary(output)
            records = validate(result, output, "text", 0)
            self.assertEqual({0, 1}, set(records))
            self.assertEqual(2, result["completed_records_per_second"])

    def test_rejects_duplicate_records_even_when_counts_match(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "events.log"
            output.write_text("ERROR 00000000 event\nERROR 00000000 event\n")
            with self.assertRaisesRegex(AssertionError, "duplicate"):
                validate(summary(output), output, "text", 0)

    def test_rejects_a_partial_final_record(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "events.log"
            output.write_text("ERROR 00000000 event\nINFO 00000001 event")
            with self.assertRaisesRegex(AssertionError, "partial"):
                validate(summary(output), output, "text", 0)

    def test_rejects_forged_loss_by_severity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "events.log"
            output.write_text("ERROR 00000000 event\n")
            result = summary(output)
            result.update(sink_written=1, unwritten_error=1)
            with self.assertRaisesRegex(AssertionError, "severity"):
                validate(result, output, "text", 0)

    def test_compares_content_of_common_identities(self) -> None:
        self.assertEqual(1, equal_work({"a": {0: "same", 1: "extra"}, "b": {0: "same"}}))
        with self.assertRaisesRegex(AssertionError, "different work"):
            equal_work({"a": {0: "first"}, "b": {0: "second"}})

    def test_rejects_a_different_thread_model_before_accepting_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "events.log"
            output.write_text("ERROR 00000000 event\nINFO 00000001 event\n")
            result = summary(output)
            result.update(case={"virtual_per_request": True}, thread_model="VIRTUAL")
            with self.assertRaisesRegex(AssertionError, "thread models differ"):
                validate(result, output, "text", 0)


def summary(path: Path) -> dict[str, object]:
    return {"attempted": 2, "filtered": 0, "sink_written": 2, "unwritten_info": 0, "unwritten_error": 0,
            "written_bytes": path.stat().st_size, "destination_failures": 0, "supplier_evaluations": 0,
            "drained_elapsed_ns": 1_000_000_000}
