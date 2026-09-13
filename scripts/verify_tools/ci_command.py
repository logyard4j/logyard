"""Retain CI command output and expose a bounded failure tail as a check annotation."""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


def escape(value: str, *, is_property: bool = False) -> str:
    result = value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    return result.replace(":", "%3A").replace(",", "%2C") if is_property else result


def failure_annotation(log: Path, title: str) -> str:
    with log.open("rb") as source:
        source.seek(0, 2)
        source.seek(max(0, source.tell() - 4096))
        tail = source.read(4096).decode("utf-8", errors="replace")
    return f"::error title={escape(title, is_property=True)}::{escape(tail or 'Command failed without output.')}"


def run(command: list[str], log: Path, title: str) -> int:
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("wb") as saved:
        try:
            with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
                assert process.stdout is not None
                while chunk := process.stdout.read1(8192):
                    saved.write(chunk)
                    sys.stdout.buffer.write(chunk)
                    sys.stdout.buffer.flush()
                status = process.wait()
        except OSError as failure:
            saved.write((str(failure) + "\n").encode("utf-8"))
            status = 127
    if status:
        # A command may end without a newline; annotations must start on their own line.
        print("\n" + failure_annotation(log, title), flush=True)
    return 128 - status if status < 0 else status


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a command is required")
    raise SystemExit(run(command, args.log, args.title))


if __name__ == "__main__":
    main()
