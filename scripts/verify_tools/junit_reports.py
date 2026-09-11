from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def verify(directory: Path, required_classes: tuple[str, ...] = ()) -> int:
    reports = sorted(directory.rglob("TEST-*.xml"))
    if not reports:
        raise ValueError(f"no fresh JUnit reports in {directory}")
    completed = 0
    classes: set[str] = set()
    for report in reports:
        root = ET.parse(report).getroot()
        for suite in root.iter("testsuite"):
            if int(suite.get("failures", "0")) or int(suite.get("errors", "0")):
                raise ValueError(f"JUnit suite failed in {report}")
        for case in root.iter("testcase"):
            if any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
                raise ValueError(f"JUnit test did not pass in {report}: {case.get('name')}")
            completed += 1
            classes.add(case.get("classname", ""))
    if completed == 0:
        raise ValueError(f"JUnit executed no tests in {directory}")
    missing = sorted(set(required_classes) - classes)
    if missing:
        raise ValueError(f"JUnit did not execute selected classes: {', '.join(missing)}")
    return completed


if __name__ == "__main__":
    try:
        count = verify(Path(sys.argv[1]), tuple(sys.argv[2:]))
    except (ValueError, OSError, ET.ParseError) as failure:
        raise SystemExit(f"JUnit evidence verification failed: {failure}") from failure
    print(f"Verified fresh JUnit evidence: {count} passed tests in {sys.argv[1]}")
