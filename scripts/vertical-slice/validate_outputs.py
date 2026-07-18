#!/usr/bin/env python3
"""Validate the persisted Phase 0 vertical-slice evidence without exposing secrets."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
from urllib.parse import unquote, urlparse

from jsonschema import Draft202012Validator, FormatChecker


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def file_uri(value: str) -> Path:
    parsed = urlparse(value)
    if parsed.scheme != "file":
        raise ValueError(f"non-file artifact URI: {value}")
    path = unquote(parsed.path)
    if parsed.netloc:
        path = f"//{parsed.netloc}{path}"
    if os.name == "nt" and path.startswith("/"):
        path = path[1:]
    return Path(path)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    report_path = args.report.resolve()
    report = json.loads(report_path.read_text(encoding="utf-8"))
    errors: list[str] = []
    checked: dict[str, str] = {}

    if report.get("status") != "PASSED":
        errors.append("vertical slice report is not PASSED")
    evaluation_path = file_uri(report["evaluationArtifactUri"])
    if digest(evaluation_path) != report["evaluationArtifactSha256"]:
        errors.append("evaluation artifact hash mismatch")
    checked["evaluation"] = digest(evaluation_path)
    evaluation = json.loads(evaluation_path.read_text(encoding="utf-8"))
    links = evaluation["links"]

    for name in ("observation", "rca", "audit"):
        path = file_uri(links[f"{name}ArtifactUri"])
        actual = digest(path)
        if actual != links[f"{name}ArtifactSha256"]:
            errors.append(f"{name} artifact hash mismatch")
        checked[name] = actual

    raw_path = root / "deployment" / "agent-input" / "observability.jsonl"
    raw_expected = links["rawArtifactSha256"].removeprefix("sha256:")
    if digest(raw_path) != raw_expected:
        errors.append("raw source artifact hash mismatch")
    checked["rawSource"] = digest(raw_path)

    rca = json.loads(file_uri(links["rcaArtifactUri"]).read_text(encoding="utf-8"))
    rca_schema = json.loads((root / "docs/design/contracts/schemas/rca.schema.json").read_text(encoding="utf-8"))
    schema_errors = sorted(
        Draft202012Validator(rca_schema, format_checker=FormatChecker()).iter_errors(rca),
        key=lambda error: list(error.path),
    )
    errors.extend(
        f"RCA schema at $.{'.'.join(str(part) for part in error.path)}: {error.message}"
        for error in schema_errors
    )

    secret = os.environ.get("DEEPSEEK_API_KEY", "")
    for path in (report_path, evaluation_path, file_uri(links["rcaArtifactUri"]), file_uri(links["auditArtifactUri"])):
        content = path.read_text(encoding="utf-8")
        if secret and secret in content:
            errors.append(f"secret leaked in {path.name}")
        if "must-redact" in content:
            errors.append(f"source credential leaked in {path.name}")
    if report.get("modelPromptStored") is not False or report.get("modelRawResponseStored") is not False:
        errors.append("prompt/raw provider response retention policy violated")
    if report.get("groundTruthAccessed") is not False:
        errors.append("ground truth access policy violated")

    validation = {
        "schemaVersion": "1.0.0",
        "status": "PASS" if not errors else "FAIL",
        "rcaSchema": "https://opspilot.local/schemas/rca/1.0.0",
        "rcaOutcome": rca.get("outcome"),
        "rootCauseNull": rca.get("rootCause") is None,
        "artifactHashes": checked,
        "secretLeakCount": sum("leaked" in error for error in errors),
        "groundTruthAccessed": report.get("groundTruthAccessed"),
        "errorCount": len(errors),
        "errors": errors,
    }
    destination = report_path.parent / "01-WP10.T01-T03-output-validation.json"
    destination.write_text(json.dumps(validation, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(validation, indent=2, ensure_ascii=False))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
