from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from .records import decode, validate_message


def validate(result: dict[str, Any], output: Path, format: str, fields: int) -> dict[int, str]:
    expected = result["attempted"]
    records: dict[int, str] = {}
    written_bytes = 0
    digest = hashlib.sha256()
    with output.open("rb") as lines:
        for line in lines:
            written_bytes += len(line)
            digest.update(line)
            if not line.endswith(b"\n"):
                raise AssertionError("partial output record")
            text = line.decode("utf-8").rstrip("\n")
            level, message, text = decode(text, format, fields, result)
            identity = int(message[:8], 16)
            if identity < 0 or identity >= expected or identity in records:
                raise AssertionError("unknown or duplicate completed record")
            if level != ("ERROR" if identity % 8 == 0 else "INFO"):
                raise AssertionError("incorrect severity")
            if "case" in result:
                validate_message(message, identity, result["case"])
            records[identity] = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if len(records) != result["sink_written"] or written_bytes != result["written_bytes"]:
        raise AssertionError("file and completion counters disagree")
    if expected != result["filtered"] + len(records) + result["unwritten_info"] + result["unwritten_error"]:
        raise AssertionError("attempted/filtered/written/unwritten counts do not reconcile")
    if result["filtered"] not in (0, expected) or result["filtered"] and records:
        raise AssertionError("unexpected filtering")
    if not result["filtered"]:
        error_count = (expected + 7) // 8
        written_errors = sum(identity % 8 == 0 for identity in records)
        if result["unwritten_error"] != error_count - written_errors or result["unwritten_info"] != expected - error_count - (len(records) - written_errors):
            raise AssertionError("unwritten severity counts disagree with record identities")
    if result["destination_failures"] or result["supplier_evaluations"]:
        raise AssertionError("destination failed or a disabled supplier ran")
    result["file_sha256"] = digest.hexdigest()
    elapsed = result["drained_elapsed_ns"] / 1_000_000_000
    if elapsed <= 0:
        raise AssertionError("invalid experiment duration")
    result["completed_records_per_second"] = len(records) / elapsed
    result["completed_bytes_per_second"] = written_bytes / elapsed
    return records


def equal_work(outputs: dict[str, dict[int, str]]) -> int:
    common = set.intersection(*(set(records) for records in outputs.values()))
    for identity in common:
        if len({records[identity] for records in outputs.values()}) != 1:
            raise AssertionError(f"providers emitted different work for record {identity}")
    return len(common)
