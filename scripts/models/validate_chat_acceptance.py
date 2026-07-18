#!/usr/bin/env python3
"""Validate Phase 0 Chat acceptance metadata and reject sensitive payloads."""

from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from typing import Any


SECRET_PATTERN = re.compile(r"\bsk-[A-Za-z0-9_-]{16,}\b")
FORBIDDEN_FIELDS = {
    "apiKey",
    "api_key",
    "secretValue",
    "secret_value",
    "prompt",
    "messages",
    "requestBody",
    "responseBody",
    "rawRequest",
    "rawResponse",
    "groundTruth",
}
REQUEST_TYPES = {"identity", "structured_output", "tool_calling", "streaming"}


def walk_fields(value: Any, errors: list[str], path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in FORBIDDEN_FIELDS:
                errors.append(f"forbidden field at {path}.{key}")
            walk_fields(child, errors, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            walk_fields(child, errors, f"{path}[{index}]")


def validate(path: Path, known_secret: str = "") -> list[str]:
    raw = path.read_text(encoding="utf-8")
    errors: list[str] = []
    if SECRET_PATTERN.search(raw):
        errors.append("secret-shaped value detected")
    if known_secret and known_secret in raw:
        errors.append("resolved Secret value detected")
    try:
        report = json.loads(raw)
    except json.JSONDecodeError:
        return ["report is not valid JSON"]
    walk_fields(report, errors)

    if report.get("status") != "PASS":
        errors.append("acceptance status is not PASS")
    if not report.get("configVersion"):
        errors.append("configVersion is missing")
    model = report.get("modelIdentity") or {}
    for field in ("provider", "configuredModelId", "actualModelId"):
        if not model.get(field):
            errors.append(f"modelIdentity.{field} is missing")
    usage = report.get("usage") or {}
    for field in ("promptTokens", "completionTokens", "totalTokens"):
        if not isinstance(usage.get(field), int):
            errors.append(f"usage.{field} is missing")
    records = report.get("requestRecords") or []
    seen = {record.get("requestType") for record in records if record.get("status") == "PASS"}
    if seen != REQUEST_TYPES:
        errors.append(f"request types mismatch: {sorted(seen)}")
    if any(not record.get("timestampUtc") for record in records):
        errors.append("request timestampUtc is missing")
    profiles = report.get("logicalProfiles") or []
    if len(profiles) != 7 or any("quota" not in profile for profile in profiles):
        errors.append("seven logical Profile quotas are required")
    policy = report.get("sensitiveDataPolicy") or {}
    if any(policy.get(field) is not False for field in (
        "secretValuePersisted", "fullPromptPersisted", "fullResponsePersisted", "groundTruthPersisted"
    )):
        errors.append("sensitive data policy is incomplete")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    errors = validate(args.report, os.environ.get("DEEPSEEK_API_KEY", ""))
    result = {
        "status": "PASS" if not errors else "FAILED",
        "report": str(args.report),
        "errorCount": len(errors),
        "errors": errors,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
