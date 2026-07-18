#!/usr/bin/env python3
"""Validate every formal contract instance through an explicit schema mapping."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker


def load_document(path: Path) -> Any:
    text = path.read_text(encoding="utf-8")
    return json.loads(text) if path.suffix == ".json" else yaml.safe_load(text)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def discover_formal_instances(root: Path) -> set[str]:
    paths: set[str] = set()
    for directory in (root / "examples", root / "profiles"):
        for pattern in ("*.json", "*.yaml", "*.yml"):
            paths.update(
                path.relative_to(root).as_posix() for path in directory.rglob(pattern)
            )
    return paths


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contracts", type=Path, required=True)
    parser.add_argument("--mapping", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    root = args.contracts.resolve()
    mapping_path = args.mapping.resolve()
    mapping = yaml.safe_load(mapping_path.read_text(encoding="utf-8"))
    entries = mapping.get("instances", []) if isinstance(mapping, dict) else []
    required_categories = set(mapping.get("requiredCategories", []))
    errors: list[str] = []
    results: list[dict[str, Any]] = []

    mapped_paths = [entry.get("path") for entry in entries if isinstance(entry, dict)]
    duplicates = sorted({path for path in mapped_paths if mapped_paths.count(path) > 1})
    if duplicates:
        errors.append(f"duplicate instance mappings: {duplicates}")

    discovered = discover_formal_instances(root)
    mapped = {path for path in mapped_paths if isinstance(path, str)}
    unmapped = sorted(discovered - mapped)
    unknown = sorted(mapped - discovered)
    if unmapped:
        errors.append(f"unmapped formal instances: {unmapped}")
    if unknown:
        errors.append(f"mapped paths are not formal instances: {unknown}")

    categories = {
        entry.get("category") for entry in entries if isinstance(entry, dict)
    }
    missing_categories = sorted(required_categories - categories)
    if missing_categories:
        errors.append(f"missing required categories: {missing_categories}")

    format_checker = FormatChecker()
    for entry in entries:
        if not isinstance(entry, dict):
            errors.append(f"invalid mapping entry: {entry!r}")
            continue
        instance_relative = entry.get("path")
        schema_relative = entry.get("schema")
        category = entry.get("category")
        if not all(isinstance(value, str) and value for value in (instance_relative, schema_relative, category)):
            errors.append(f"mapping entry has empty fields: {entry!r}")
            continue
        instance_path = root / instance_relative
        schema_path = root / schema_relative
        try:
            instance = load_document(instance_path)
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            validator = Draft202012Validator(schema, format_checker=format_checker)
            validation_errors = sorted(
                validator.iter_errors(instance), key=lambda error: list(error.absolute_path)
            )
            if validation_errors:
                for error in validation_errors:
                    location = "$" + "".join(
                        f"[{part}]" if isinstance(part, int) else f".{part}"
                        for part in error.absolute_path
                    )
                    errors.append(f"{instance_relative}: {location}: {error.message}")
            results.append(
                {
                    "path": instance_relative,
                    "schema": schema_relative,
                    "category": category,
                    "instanceSha256": sha256(instance_path),
                    "schemaSha256": sha256(schema_path),
                    "status": "PASS" if not validation_errors else "FAIL",
                }
            )
        except (OSError, json.JSONDecodeError, yaml.YAMLError) as error:
            errors.append(f"{instance_relative}: cannot validate: {error}")

    report = {
        "status": "PASS" if not errors else "FAIL",
        "mapping": mapping_path.relative_to(root).as_posix(),
        "mappingSha256": sha256(mapping_path),
        "formalInstanceCount": len(discovered),
        "mappedInstanceCount": len(mapped),
        "unmappedInstanceCount": len(unmapped),
        "requiredCategories": sorted(required_categories),
        "coveredCategories": sorted(categories),
        "results": results,
        "errorCount": len(errors),
        "errors": errors,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    sys.exit(main())
