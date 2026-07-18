#!/usr/bin/env python3
"""Prove each negative fixture is rejected by the same validation entry point."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import yaml


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contracts", type=Path, required=True)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    root = args.contracts.resolve()
    fixture_paths = sorted(args.fixtures.resolve().glob("*.yaml"))
    entry = Path(__file__).with_name("validate_negative_fixture.py").resolve()
    results: list[dict[str, Any]] = []
    failures: list[str] = []

    for fixture_path in fixture_paths:
        fixture = yaml.safe_load(fixture_path.read_text(encoding="utf-8"))
        completed = subprocess.run(
            [
                sys.executable,
                str(entry),
                "--contracts",
                str(root),
                "--fixture",
                str(fixture_path),
            ],
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
        )
        expected_reasons = fixture.get("expectedReasons", [])
        reasons_matched = all(reason in completed.stdout for reason in expected_reasons)
        passed = completed.returncode == 1 and reasons_matched
        if not passed:
            failures.append(
                f"{fixture.get('id', fixture_path.stem)}: exit={completed.returncode}, "
                f"expectedReasonsMatched={reasons_matched}"
            )
        results.append(
            {
                "fixtureId": fixture.get("id"),
                "fixture": fixture_path.relative_to(root).as_posix(),
                "fixtureSha256": sha256(fixture_path),
                "exitCode": completed.returncode,
                "expectedReasons": expected_reasons,
                "expectedReasonsMatched": reasons_matched,
                "status": "PASS" if passed else "FAIL",
                "validatorOutput": completed.stdout.strip(),
                "validatorStderr": completed.stderr.strip(),
            }
        )

    report = {
        "status": "PASS" if fixture_paths and not failures else "FAIL",
        "entryPoint": entry.relative_to(Path.cwd()).as_posix(),
        "entryPointSha256": sha256(entry),
        "fixtureCount": len(fixture_paths),
        "expectedNonzeroExitCount": sum(result["exitCode"] == 1 for result in results),
        "results": results,
        "failures": failures or ([] if fixture_paths else ["no negative fixtures found"]),
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
