from __future__ import annotations

import json
import unittest

from .records import decode, validate_message


class NativeRecordsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.record = {"timestamp": "2026-09-11T00:00:00.123Z", "level": "INFO", "message": "00000001 event"}
        self.interval = {"measurement_start_epoch_millis": 1789084800000,
                         "measurement_end_epoch_millis": 1789084801000}

    def decode(self, record: dict[str, object]) -> tuple[str, str, str]:
        return decode(json.dumps(record), "native-json", 0, self.interval)

    def test_compares_event_content_while_validating_each_real_timestamp(self) -> None:
        first = self.decode(self.record)
        self.record["timestamp"] = "2026-09-11T00:00:00.456Z"
        self.assertEqual(first, self.decode(self.record))
        self.assertNotIn("timestamp", json.loads(first[2]))
        self.record["timestamp"] = "2026-09-11T00:00:00Z"
        self.assertEqual(first, self.decode(self.record))

    def test_rejects_missing_extra_or_invalid_fields(self) -> None:
        for field in ("timestamp", "level", "message"):
            with self.subTest(field=field), self.assertRaisesRegex(AssertionError, "shape"):
                self.decode({key: value for key, value in self.record.items() if key != field})
        with self.assertRaisesRegex(AssertionError, "shape"):
            self.decode(self.record | {"unmatched": True})
        with self.assertRaisesRegex(AssertionError, "strings"):
            self.decode(self.record | {"message": {}})

    def test_rejects_stale_malformed_non_utc_and_different_precision_timestamps(self) -> None:
        for value in ("2026-09-10T00:00:00Z", "2026-09-11T00:00:02Z", "2026-09-11T99:00:00Z",
                      "2026-09-11T00:00:00.123456Z", "2026-09-11T00:00:00+00:00", 1789084800000, None):
            with self.subTest(value=value), self.assertRaises(AssertionError):
                self.decode(self.record | {"timestamp": value})

    def test_rejects_duplicate_and_non_finite_json_fields(self) -> None:
        for text in ('{"level":"INFO","level":"ERROR"}', '{"attributes":{"key":1,"key":2}}', '{"value":NaN}'):
            with self.subTest(text=text), self.assertRaises(AssertionError):
                decode(text, "native-json", 0, self.interval)

    def test_checks_every_mdc_key_and_escaped_value(self) -> None:
        record = self.record | {"attributes": {f"field.{index}": f'field.{index}-value"\\\tλ' for index in range(4)}}
        decode(json.dumps(record), "native-json", 4, self.interval)
        for attributes in ({}, {"wrong": "value"}, {"field.0": "wrong"}, []):
            with self.subTest(attributes=attributes), self.assertRaisesRegex(AssertionError, "MDC"):
                decode(json.dumps(record | {"attributes": attributes}), "native-json", 4, self.interval)

    def test_checks_formatted_message_even_when_providers_drop_different_identities(self) -> None:
        case = {"arguments": 2, "format": "native-json"}
        message = '00000001 accepted order 42 for customer-7 "escaped"\\path\t\nλ'
        validate_message(message, 1, case)
        with self.assertRaisesRegex(AssertionError, "message"):
            validate_message(message.replace("42", "{}"), 1, case)
