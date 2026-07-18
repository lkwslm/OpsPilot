#!/usr/bin/env python3
"""Run the complete contract gate used locally and in GitHub Actions."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def input_files(project_root: Path) -> list[Path]:
    patterns = (
        "docs/design/contracts/schemas/*.schema.json",
        "docs/design/contracts/openapi/*.yaml",
        "docs/design/contracts/examples/**/*.json",
        "docs/design/contracts/examples/**/*.yaml",
        "docs/design/contracts/profiles/*.yaml",
        "docs/design/contracts/negative-fixtures/*.yaml",
        "docs/design/**/*.md",
        "docs/implementation-plan/**/*.md",
    )
    return sorted({path.resolve() for pattern in patterns for path in project_root.glob(pattern)})


def run_step(name: str, command: list[str], cwd: Path) -> dict[str, Any]:
    completed = subprocess.run(
        command,
        cwd=cwd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1"},
        check=False,
    )
    return {
        "name": name,
        "status": "PASS" if completed.returncode == 0 else "FAIL",
        "exitCode": completed.returncode,
        "command": command,
        "stdout": completed.stdout.strip(),
        "stderr": completed.stderr.strip(),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, default=Path.cwd())
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    args = parser.parse_args()

    project_root = args.project_root.resolve()
    report_dir = (project_root / args.report_dir).resolve()
    summary_path = (project_root / args.summary).resolve()
    scripts = project_root / "scripts/contracts"
    contracts = project_root / "docs/design/contracts"
    report_dir.mkdir(parents=True, exist_ok=True)

    commands = [
        (
            "contract-compilation",
            [
                sys.executable,
                str(scripts / "validate_contracts.py"),
                "--contracts",
                str(contracts),
                "--report",
                str(report_dir / "contract-compilation.json"),
            ],
        ),
        (
            "formal-instance-validation",
            [
                sys.executable,
                str(scripts / "validate_instances.py"),
                "--contracts",
                str(contracts),
                "--mapping",
                str(contracts / "instance-map.yaml"),
                "--report",
                str(report_dir / "instance-validation.json"),
            ],
        ),
        (
            "negative-fixture-proof",
            [
                sys.executable,
                str(scripts / "verify_negative_fixtures.py"),
                "--contracts",
                str(contracts),
                "--fixtures",
                str(contracts / "negative-fixtures"),
                "--report",
                str(report_dir / "negative-fixtures.json"),
            ],
        ),
        (
            "openapi-and-markdown",
            [
                sys.executable,
                str(scripts / "validate_documentation.py"),
                "--openapi",
                str(contracts / "openapi/opspilot-v1.yaml"),
                "--markdown-root",
                str(project_root / "docs/design"),
                "--markdown-root",
                str(project_root / "docs/implementation-plan"),
                "--report",
                str(report_dir / "documentation-validation.json"),
            ],
        ),
        (
            "failure-regression-tests",
            [
                sys.executable,
                "-m",
                "unittest",
                "discover",
                "-s",
                str(scripts),
                "-p",
                "test_documentation_validation.py",
                "-v",
            ],
        ),
    ]
    results = [run_step(name, command, project_root) for name, command in commands]
    files = input_files(project_root)
    input_digest = hashlib.sha256(
        "".join(
            f"{path.relative_to(project_root).as_posix()}:{sha256(path)}\n" for path in files
        ).encode("utf-8")
    ).hexdigest()
    passed = sum(result["status"] == "PASS" for result in results)
    failed = len(results) - passed
    report = {
        "status": "PASS" if failed == 0 else "FAIL",
        "validatorVersions": {
            "python": sys.version.split()[0],
            "openapi-spec-validator": importlib.metadata.version("openapi-spec-validator"),
            "jsonschema": importlib.metadata.version("jsonschema"),
            "referencing": importlib.metadata.version("referencing"),
            "PyYAML": importlib.metadata.version("PyYAML"),
        },
        "inputFileCount": len(files),
        "inputSha256": input_digest,
        "stepCount": len(results),
        "successCount": passed,
        "failureCount": failed,
        "results": results,
    }
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
