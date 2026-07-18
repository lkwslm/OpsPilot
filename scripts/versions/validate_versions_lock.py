#!/usr/bin/env python3
"""Validate the immutable OpsPilot supply-chain version lock."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator


FORBIDDEN_VALUE = re.compile(
    r"(?:<[^>]+>|\b(?:latest|placeholder|changeme|todo|tbd)\b)", re.IGNORECASE
)


def document_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_yaml(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as stream:
        return yaml.safe_load(stream)


def json_path(parts: list[Any]) -> str:
    return "$" + "".join(
        f"[{part}]" if isinstance(part, int) else f".{part}" for part in parts
    )


def walk_scalars(value: Any, path: list[Any] | None = None):
    current_path = path or []
    if isinstance(value, dict):
        for key, child in value.items():
            yield from walk_scalars(child, current_path + [key])
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk_scalars(child, current_path + [index])
    else:
        yield current_path, value


def semantic_errors(document: Any) -> list[str]:
    errors: list[str] = []
    for path, value in walk_scalars(document):
        location = json_path(path)
        if value is None or value == "":
            errors.append(f"{location}: empty value is forbidden")
        elif isinstance(value, str) and FORBIDDEN_VALUE.search(value):
            errors.append(f"{location}: placeholder or mutable 'latest' value is forbidden")

    if not isinstance(document, dict):
        return errors

    for section in ("dependencies", "mavenPlugins"):
        for index, entry in enumerate(document.get(section, [])):
            version = entry.get("version") if isinstance(entry, dict) else None
            if isinstance(version, str) and not re.fullmatch(
                r"[0-9]+\.[0-9]+\.[0-9]+(?:[.-][A-Za-z0-9]+)*", version
            ):
                errors.append(
                    f"$.{section}[{index}].version: exact version required; floating minor/range is forbidden"
                )

    for index, image in enumerate(document.get("containers", [])):
        if not isinstance(image, dict):
            continue
        immutable_ref = image.get("immutableRef", "")
        digest = image.get("digest", "")
        if isinstance(immutable_ref, str) and "@sha256:" not in immutable_ref:
            errors.append(
                f"$.containers[{index}].immutableRef: image reference must include an immutable sha256 digest"
            )
        if isinstance(immutable_ref, str) and isinstance(digest, str):
            ref_digest = immutable_ref.rsplit("@", 1)[-1]
            if ref_digest != digest:
                errors.append(
                    f"$.containers[{index}]: immutableRef digest does not match digest"
                )
    return errors


def validate(lock_path: Path, schema_path: Path) -> list[str]:
    try:
        document = load_yaml(lock_path)
    except (OSError, yaml.YAMLError) as error:
        return [f"$: cannot load lock file: {error}"]

    try:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
    except (OSError, json.JSONDecodeError, Exception) as error:
        return [f"$: cannot load schema: {error}"]

    validator = Draft202012Validator(schema)
    schema_errors = [
        f"{json_path(list(error.absolute_path))}: {error.message}"
        for error in sorted(validator.iter_errors(document), key=lambda item: list(item.absolute_path))
    ]
    return schema_errors + semantic_errors(document)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    errors = validate(args.lock, args.schema)
    lock_hash = document_sha256(args.lock) if args.lock.is_file() else None
    report = {
        "status": "PASS" if not errors else "FAIL",
        "lockFile": str(args.lock),
        "schemaFile": str(args.schema),
        "lockSha256": lock_hash,
        "errorCount": len(errors),
        "errors": errors,
    }
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )

    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if not errors else 2


if __name__ == "__main__":
    sys.exit(main())
