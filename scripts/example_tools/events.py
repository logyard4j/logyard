from __future__ import annotations

import json
from datetime import datetime
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class EventExpectation:
    body: str
    logger: str
    severity: str
    attributes: tuple[tuple[str, Any], ...] = ()
    exception_message: str | None = None


class EventLog:
    def __init__(self, events: tuple[dict[str, Any], ...]) -> None:
        self._events = events

    @classmethod
    def read(cls, path: Path) -> EventLog:
        if not path.is_file():
            raise AssertionError(f"example did not create its structured event file: {path}")
        events = tuple(json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip())
        if not events:
            raise AssertionError(f"example produced no structured events: {path}")
        return cls(events)

    def require(self, expectation: EventExpectation) -> None:
        matches = [event for event in self._events if event.get("body") == expectation.body]
        if len(matches) != 1:
            raise AssertionError(f"expected one event with body {expectation.body!r}, found {len(matches)}")
        event = matches[0]
        self._equal(expectation.logger, event.get("logger"), expectation.body, "logger")
        self._equal(expectation.severity, event.get("severity_text"), expectation.body, "severity")
        attributes = event.get("attributes", {})
        for name, expected in expectation.attributes:
            self._equal(expected, attributes.get(name), expectation.body, f"attribute {name}")
        if expectation.exception_message is not None:
            exception = event.get("exception") or {}
            self._equal(expectation.exception_message, exception.get("message"), expectation.body, "exception message")

    def require_last(self, body: str) -> None:
        actual = self._events[-1].get("body")
        self._equal(body, actual, body, "final flushed event")

    def require_last_one_of(self, bodies: tuple[str, ...]) -> None:
        actual = self._events[-1].get("body")
        if actual not in bodies:
            raise AssertionError(f"final flushed event {actual!r} was not one of {bodies!r}")

    def require_real_timestamps(self) -> None:
        for event in self._events:
            timestamp = str(event.get("timestamp", ""))
            parsed = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
            if parsed.year < 2000:
                raise AssertionError(f"event {event.get('body')!r} has an invalid source timestamp: {timestamp}")

    def require_logger_prefix(self, prefix: str) -> None:
        if not any(str(event.get("logger", "")).startswith(prefix) for event in self._events):
            raise AssertionError(f"no structured event was emitted by a logger under {prefix}")

    @staticmethod
    def _equal(expected: Any, actual: Any, body: str, field: str) -> None:
        if actual != expected:
            raise AssertionError(f"event {body!r} has {field} {actual!r}; expected {expected!r}")
