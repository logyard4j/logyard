"""Run and independently reconcile packaged buffered-file fanout during live reload."""
from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

from .build import BuiltProvider
from .records import decode, reject_constant, unique_object, validate_message, validate_timestamp
from .results import equal_work, validate_capacity


EXCEPTION = {
    "type": "java.lang.IllegalStateException", "message": "representative failure",
    "stacktrace": [f"com.example.orders.OrderService.accept(OrderService.java:{40 + index})" for index in range(8)],
}
CASE = {"arguments": 2, "fields": 4, "format": "native-json"}


def nonnegative(value: Any, name: str) -> int:
    if type(value) is not int or value < 0:
        raise AssertionError("invalid reload counter: " + name)
    return value


def diagnostic(record: dict[str, Any], result: dict[str, Any]) -> dict[str, int]:
    if set(record) != {"timestamp", "level", "message", "attributes"}:
        raise AssertionError("unexpected reload diagnostic shape")
    validate_timestamp(record["timestamp"], result | {"measurement_start_epoch_millis": 0,
                                                     "measurement_end_epoch_millis": result["completion_epoch_millis"]})
    values = record["attributes"]
    if not isinstance(values, dict) or not set(values) <= {"logyard.dropped.info", "logyard.dropped.error", "logyard.dropped.total"}:
        raise AssertionError("unknown reload diagnostic")
    counts = {level: nonnegative(values.get("logyard.dropped." + level, 0), level) for level in ("info", "error", "total")}
    if counts["total"] == 0 or counts["total"] != counts["info"] + counts["error"]:
        raise AssertionError("diagnostic severity totals do not reconcile")
    if record["message"] != f"Dropped {counts['total']} log events because an output was full or closing":
        raise AssertionError("unexpected reload diagnostic message")
    return counts


def validate_file(path: Path, summary: dict[str, Any], result: dict[str, Any]) -> dict[int, str]:
    records: dict[int, str] = {}
    warmup = 0
    warmup_errors = 0
    diagnostics = 0
    reported = {"info": 0, "error": 0, "total": 0}
    measured_bytes = 0
    file_bytes = 0
    digest = hashlib.sha256()
    for key in ("enqueued", "dropped", "warmup_enqueued", "warmup_dropped"):
        nonnegative(summary[key], key)
    capacity = {"policy": "default", "queue_capacity": summary["capacity"],
                "worker_batch_capacity_outside_queue": summary["worker_batch_capacity_outside_queue"]}
    validate_capacity(capacity, "default")
    summary["maximum_in_flight_events"] = capacity["maximum_in_flight_events"]
    with path.open("rb") as source:
        for line in source:
            if not line.endswith(b"\n"):
                raise AssertionError("partial reload output record")
            digest.update(line)
            file_bytes += len(line)
            record = json.loads(line.decode("utf-8"), object_pairs_hook=unique_object, parse_constant=reject_constant)
            if not isinstance(record, dict) or not isinstance(record.get("message"), str):
                raise AssertionError("invalid reload record")
            if record.get("level") == "WARN":
                for level, count in diagnostic(record, result).items():
                    reported[level] += count
                diagnostics += 1
                continue
            captured = hashlib.sha256(json.dumps(record, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
            has_exception = "exception" in record
            exception = record.pop("exception", None)
            if record.get("level") == "ERROR":
                if exception != EXCEPTION:
                    raise AssertionError("exception capture differs from the workload")
            elif has_exception:
                raise AssertionError("unexpected exception on an INFO record")
            is_warmup = record["message"].startswith("warmup ")
            window = result | {"measurement_start_epoch_millis": 0} if is_warmup else result
            level, message, _ = decode(json.dumps(record), "native-json", 4, window)
            if is_warmup:
                if level not in ("INFO", "ERROR"):
                    raise AssertionError("invalid warmup severity")
                validate_message("00000000 " + message.removeprefix("warmup "), 0, CASE)
                warmup += 1
                warmup_errors += level == "ERROR"
                continue
            identity = int(message[:8], 16)
            if identity < 0 or identity >= result["attempted"] or identity in records:
                raise AssertionError("unknown or duplicate reload record")
            if level != ("ERROR" if identity % 8 == 0 else "INFO"):
                raise AssertionError("incorrect reload record severity")
            validate_message(message, identity, CASE)
            records[identity] = captured
            measured_bytes += len(line)
    if not records or len(records) != summary["enqueued"] or warmup != summary["warmup_enqueued"]:
        raise AssertionError("file records and reload admission counters disagree")
    if len(records) + summary["dropped"] != result["attempted"]:
        raise AssertionError("reload attempts and output loss do not reconcile")
    if warmup + summary["warmup_dropped"] != result["warmup_attempted"]:
        raise AssertionError("warmup attempts and output loss do not reconcile")
    errors = sum(identity % 8 == 0 for identity in records)
    missing_errors = (result["attempted"] + 7) // 8 - errors
    expected_warmup_errors = sum(index % result["attempted"] % 8 == 0 for index in range(result["warmup_attempted"]))
    all_missing_errors = expected_warmup_errors - warmup_errors + missing_errors
    all_drops = summary["warmup_dropped"] + summary["dropped"]
    if reported != {"info": all_drops - all_missing_errors, "error": all_missing_errors, "total": all_drops}:
        raise AssertionError("drop diagnostics do not match missing application records")
    summary.update(written=len(records), unwritten_error=missing_errors,
                   unwritten_info=summary["dropped"] - missing_errors, measured_bytes=measured_bytes,
                   file_bytes=file_bytes, file_sha256=digest.hexdigest(), diagnostic_records=diagnostics,
                   diagnostic_drops=reported)
    return records


def validate(result: dict[str, Any], directory: Path, events: int) -> None:
    if nonnegative(result["attempted"], "attempted") != events or nonnegative(result["reloads"], "reloads") == 0:
        raise AssertionError("reload workload did not perform the requested work")
    if type(result["warmup_attempted"]) is not int or result["warmup_attempted"] != 10_000:
        raise AssertionError("unexpected reload warmup workload")
    if result["worker_threads"] != 2 or result["caller_threads"] != 16:
        raise AssertionError("unexpected reload worker or producer count")
    if set(result["outputs"]) != {"first", "second"}:
        raise AssertionError("unexpected reload outputs")
    if {path.name for path in directory.glob("*.jsonl")} != {"first.jsonl", "second.jsonl"}:
        raise AssertionError("unexpected reload output files")
    records = {name: validate_file(directory / f"{name}.jsonl", summary, result)
               for name, summary in result["outputs"].items()}
    common = equal_work(records)
    if common == 0:
        raise AssertionError("no shared records across reload outputs")
    result["equal_output_records"] = common


def execute(built: BuiltProvider, run: Path, java: str, events: int = 50_000, rate: int = 5_000) -> dict[str, Any]:
    if built.name != "logyard":
        raise ValueError("the buffered reload workload requires Logyard")
    directory = run / "reload"
    log = run / "reload.log"
    arguments = ["-Xms256m", "-Xmx256m"]
    with log.open("w", encoding="utf-8") as output:
        completed = subprocess.run([java, *arguments, "-cp", built.classpath,
                                    "com.logyard4j.logyard.compare.ReloadDeliveryMain", str(directory), str(events), str(rate)],
                                   cwd=built.directory, stdout=output, stderr=subprocess.STDOUT, timeout=120)
    if completed.returncode:
        raise RuntimeError(f"reload workload failed; see {log}\n{log.read_text()[-4000:]}")
    rows = [json.loads(line.removeprefix("RELOAD_RESULT "), object_pairs_hook=unique_object,
                       parse_constant=reject_constant) for line in log.read_text().splitlines()
            if line.startswith("RELOAD_RESULT ")]
    if len(rows) != 1:
        raise AssertionError("expected exactly one reload workload result")
    result = rows[0]
    validate(result, directory, events)
    result.update(scenario="buffered-reload-fanout", jvm_arguments=arguments, directory=directory.name,
                  case=CASE | {"events": events, "producers": 16, "rate": rate})
    (run / "reload.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result
