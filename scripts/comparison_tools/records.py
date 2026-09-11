from __future__ import annotations

import json
import re
from datetime import datetime, timedelta, timezone
from typing import Any


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise AssertionError("duplicate JSON field: " + key)
        result[key] = value
    return result


def reject_constant(value: str) -> None:
    raise AssertionError("non-finite JSON value: " + value)


def decode(text: str, format: str, fields: int, result: dict[str, Any]) -> tuple[str, str, str]:
    if format == "text":
        level, message = text.split(" ", 1)
        return level, message, text
    data = json.loads(text, object_pairs_hook=unique_object, parse_constant=reject_constant)
    expected = {"level", "message", "attributes"}
    if format == "native-json":
        expected.add("timestamp")
        if fields == 0:
            expected.remove("attributes")
    if not isinstance(data, dict) or set(data) != expected:
        raise AssertionError("unexpected JSON output shape")
    if not isinstance(data["message"], str) or not isinstance(data["level"], str):
        raise AssertionError("message and severity must be strings")
    attributes = {f"field.{index}": f'field.{index}-value"\\\tλ' for index in range(fields)}
    if data.get("attributes", {}) != attributes:
        raise AssertionError("JSON MDC keys or values differ from the workload")
    if format == "native-json":
        validate_timestamp(data.pop("timestamp"), result)
    return data["level"], data["message"], json.dumps(data, sort_keys=True, ensure_ascii=False)


def validate_timestamp(value: Any, result: dict[str, Any]) -> None:
    if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z", value):
        raise AssertionError("native timestamp must be UTC ISO-8601 at millisecond precision")
    try:
        timestamp = datetime.fromisoformat(value)
    except ValueError as error:
        raise AssertionError("invalid native timestamp") from error
    millis = (timestamp - datetime(1970, 1, 1, tzinfo=timezone.utc)) // timedelta(milliseconds=1)
    if not result["measurement_start_epoch_millis"] <= millis <= result["measurement_end_epoch_millis"]:
        raise AssertionError("native timestamp falls outside the measured experiment")


def validate_message(message: str, identity: int, case: dict[str, Any]) -> None:
    bodies = {0: "accepted order", 1: "accepted order 42", 2: "accepted order 42 for customer-7",
              4: "accepted 42 customer-7 true 9.5"}
    expected = f"{identity:08x} " + bodies[case["arguments"]]
    if case["format"] != "text":
        expected += ' "escaped"\\path\t\nλ'
    if message != expected:
        raise AssertionError("formatted message differs from the workload")
