from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from .reload_workload import EXCEPTION, validate


def fixtures():
    result = {"attempted": 2, "reloads": 1, "worker_threads": 2, "caller_threads": 16,
              "warmup_attempted": 10_000, "measurement_start_epoch_millis": 1000,
              "measurement_end_epoch_millis": 2000, "completion_epoch_millis": 3000,
              "outputs": {name: {"enqueued": 2, "dropped": 0, "warmup_enqueued": 0, "warmup_dropped": 10_000,
                                 "capacity": 256, "worker_batch_capacity_outside_queue": 1}
                          for name in ("first", "second")}}
    attributes = {f"field.{index}": f'field.{index}-value"\\\tλ' for index in range(4)}
    records = [{"timestamp": "1970-01-01T00:00:01Z", "level": "ERROR" if index == 0 else "INFO",
                "message": f'{index:08x} accepted order 42 for customer-7 "escaped"\\path\t\nλ',
                "attributes": attributes.copy()} for index in range(2)]
    records[0]["exception"] = copy.deepcopy(EXCEPTION)
    records.append({"timestamp": "1970-01-01T00:00:03Z", "level": "WARN",
                    "message": "Dropped 10000 log events because an output was full or closing",
                    "attributes": {"logyard.dropped.info": 5000, "logyard.dropped.error": 5000,
                                   "logyard.dropped.total": 10_000}})
    return result, records


class ReloadWorkloadTest(unittest.TestCase):
    def check(self, result, first, second=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, records in (("first", first), ("second", first if second is None else second)):
                (root / f"{name}.jsonl").write_text("".join(json.dumps(record) + "\n" for record in records))
            validate(result, root, 2)
        return result

    def test_reconciles_application_records_and_internal_drop_diagnostics(self):
        result, records = fixtures()
        self.check(result, records)
        self.assertEqual(2, result["equal_output_records"])
        for output in result["outputs"].values():
            self.assertEqual(2, output["written"])
            self.assertEqual(0, output["unwritten_error"] + output["unwritten_info"])
            self.assertEqual(1, output["diagnostic_records"])
            self.assertEqual(10_000, output["diagnostic_drops"]["total"])

    def test_reconciles_different_loss_in_each_output(self):
        result, first = fixtures()
        second = copy.deepcopy(first)
        second.pop(1)
        result["outputs"]["second"].update(enqueued=1, dropped=1)
        second[-1]["attributes"].update({"logyard.dropped.info": 5001, "logyard.dropped.total": 10_001})
        second[-1]["message"] = "Dropped 10001 log events because an output was full or closing"
        self.check(result, first, second)
        self.assertEqual(1, result["equal_output_records"])
        self.assertEqual(1, result["outputs"]["second"]["unwritten_info"])
        self.assertEqual(0, result["outputs"]["second"]["unwritten_error"])

    def test_rejects_missing_or_inaccurate_exception_capture(self):
        for change in (lambda row: row.pop("exception"), lambda row: row["exception"]["stacktrace"].pop()):
            result, records = fixtures()
            change(records[0])
            with self.subTest(change=change), self.assertRaisesRegex(AssertionError, "exception capture"):
                self.check(result, records)

    def test_rejects_a_duplicate_identity_even_when_counts_match(self):
        result, records = fixtures()
        records[1] = copy.deepcopy(records[0])
        with self.assertRaisesRegex(AssertionError, "duplicate reload record"):
            self.check(result, records)

    def test_rejects_recapture_that_changes_a_shared_events_timestamp(self):
        result, first = fixtures()
        second = copy.deepcopy(first)
        second[0]["timestamp"] = "1970-01-01T00:00:01.001Z"
        with self.assertRaisesRegex(AssertionError, "different work"):
            self.check(result, first, second)

    def test_rejects_missing_or_forged_drop_diagnostics(self):
        for missing in (True, False):
            result, records = fixtures()
            if missing:
                records.pop()
            else:
                records[-1]["attributes"].update({"logyard.dropped.info": 4999, "logyard.dropped.total": 9999})
                records[-1]["message"] = "Dropped 9999 log events because an output was full or closing"
            with self.subTest(missing=missing), self.assertRaisesRegex(AssertionError, "drop diagnostics"):
                self.check(result, records)

    def test_rejects_partial_output_and_unknown_warning_records(self):
        result, records = fixtures()
        records[-1]["message"] = "unrelated warning"
        with self.assertRaisesRegex(AssertionError, "diagnostic message"):
            self.check(result, records)
        result, records = fixtures()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("first", "second"):
                (root / f"{name}.jsonl").write_text("\n".join(json.dumps(record) for record in records))
            with self.assertRaisesRegex(AssertionError, "partial reload"):
                validate(result, root, 2)

    def test_rejects_a_workload_without_reload_or_with_changed_admission_counts(self):
        result, records = fixtures()
        result["reloads"] = 0
        with self.assertRaisesRegex(AssertionError, "requested work"):
            self.check(result, records)
        result, records = fixtures()
        result["outputs"]["first"]["enqueued"] = 3
        with self.assertRaisesRegex(AssertionError, "admission counters"):
            self.check(result, records)
