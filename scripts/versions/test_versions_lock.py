#!/usr/bin/env python3
"""Prove that the production validator rejects each forbidden lock form."""

from __future__ import annotations

import argparse
import copy
import hashlib
import importlib.metadata
import json
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Callable

import yaml


Mutation = Callable[[dict[str, Any]], None]


def set_empty(document: dict[str, Any]) -> None:
    document["dependencies"][0]["version"] = ""


def set_placeholder(document: dict[str, Any]) -> None:
    document["models"][0]["revision"] = "<immutable revision>"


def set_latest(document: dict[str, Any]) -> None:
    document["dependencies"][0]["version"] = "latest"


def set_floating_minor(document: dict[str, Any]) -> None:
    document["dependencies"][0]["version"] = "4.1"


def remove_image_digest(document: dict[str, Any]) -> None:
    document["containers"][0]["immutableRef"] = "pgvector/pgvector:pg16"


CASES: list[tuple[str, Mutation, str]] = [
    ("empty-value", set_empty, "empty value is forbidden"),
    ("placeholder", set_placeholder, "placeholder"),
    ("latest", set_latest, "latest"),
    ("floating-minor", set_floating_minor, "floating minor/range is forbidden"),
    ("image-without-digest", remove_image_digest, "must include an immutable sha256 digest"),
]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--validator", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    canonical = yaml.safe_load(args.lock.read_text(encoding="utf-8"))
    results: list[dict[str, Any]] = []
    with tempfile.TemporaryDirectory(prefix="opspilot-version-lock-") as directory:
        fixture_dir = Path(directory)
        for name, mutation, expected in CASES:
            document = copy.deepcopy(canonical)
            mutation(document)
            fixture = fixture_dir / f"{name}.yaml"
            fixture.write_text(
                yaml.safe_dump(document, sort_keys=False, allow_unicode=True),
                encoding="utf-8",
            )
            completed = subprocess.run(
                [
                    sys.executable,
                    str(args.validator),
                    "--lock",
                    str(fixture),
                    "--schema",
                    str(args.schema),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            combined_output = completed.stdout + completed.stderr
            passed = completed.returncode != 0 and expected in combined_output
            results.append(
                {
                    "case": name,
                    "expectedReason": expected,
                    "exitCode": completed.returncode,
                    "passed": passed,
                }
            )

    report = {
        "status": "PASS" if all(item["passed"] for item in results) else "FAIL",
        "lockSha256": sha256(args.lock),
        "schemaSha256": sha256(args.schema),
        "toolVersions": {
            "python": ".".join(str(part) for part in sys.version_info[:3]),
            "PyYAML": importlib.metadata.version("PyYAML"),
            "jsonschema": importlib.metadata.version("jsonschema"),
        },
        "positiveEntry": str(args.validator),
        "negativeCases": results,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
