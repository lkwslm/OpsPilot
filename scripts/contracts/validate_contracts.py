#!/usr/bin/env python3
"""Compile OpsPilot OpenAPI and JSON Schema contracts and resolve every $ref."""

from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import sys
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlsplit

import yaml
from jsonschema import Draft202012Validator
from openapi_spec_validator import validate_spec


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def combined_hash(root: Path, paths: list[Path]) -> str:
    digest = hashlib.sha256()
    for path in sorted(paths):
        relative = path.relative_to(root).as_posix()
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(bytes.fromhex(sha256(path)))
        digest.update(b"\0")
    return digest.hexdigest()


def walk_refs(value: Any, path: str = "$"):
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}"
            if key == "$ref" and isinstance(child, str):
                yield child_path, child
            yield from walk_refs(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk_refs(child, f"{path}[{index}]")


def resolve_pointer(document: Any, fragment: str) -> Any:
    if fragment in ("", "#"):
        return document
    pointer = fragment[1:] if fragment.startswith("#") else fragment
    pointer = unquote(pointer)
    if not pointer.startswith("/"):
        raise ValueError(f"unsupported fragment: {fragment}")
    current = document
    for token in pointer[1:].split("/"):
        token = token.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            current = current[int(token)]
        elif isinstance(current, dict):
            current = current[token]
        else:
            raise KeyError(token)
    return current


def resolve_refs(
    source_path: Path,
    document: Any,
    documents_by_id: dict[str, tuple[Path, Any]],
) -> tuple[int, list[str]]:
    count = 0
    errors: list[str] = []
    for location, reference in walk_refs(document):
        count += 1
        try:
            parsed = urlsplit(reference)
            if not parsed.scheme and not parsed.path:
                target_document = document
                fragment = f"#{parsed.fragment}" if parsed.fragment else ""
            elif parsed.scheme:
                base_uri = reference.split("#", 1)[0]
                if base_uri not in documents_by_id:
                    raise KeyError(f"unregistered schema id: {base_uri}")
                _, target_document = documents_by_id[base_uri]
                fragment = f"#{parsed.fragment}" if parsed.fragment else ""
            else:
                target_path = (source_path.parent / unquote(parsed.path)).resolve()
                target_document = json.loads(target_path.read_text(encoding="utf-8"))
                fragment = f"#{parsed.fragment}" if parsed.fragment else ""
            resolve_pointer(target_document, fragment)
        except (OSError, ValueError, KeyError, IndexError, json.JSONDecodeError) as error:
            errors.append(f"{source_path}: {location} -> {reference}: {error}")
    return count, errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contracts", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    contracts_root = args.contracts.resolve()
    schema_paths = sorted((contracts_root / "schemas").glob("*.schema.json"))
    openapi_paths = sorted((contracts_root / "openapi").glob("*.yaml"))
    input_paths = schema_paths + openapi_paths
    errors: list[str] = []
    ref_count = 0

    schema_documents: list[tuple[Path, Any]] = []
    documents_by_id: dict[str, tuple[Path, Any]] = {}
    for path in schema_paths:
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
            Draft202012Validator.check_schema(document)
            schema_documents.append((path, document))
            schema_id = document.get("$id")
            if not isinstance(schema_id, str) or not schema_id:
                errors.append(f"{path}: missing non-empty $id")
            elif schema_id in documents_by_id:
                errors.append(f"{path}: duplicate $id {schema_id}")
            else:
                documents_by_id[schema_id] = (path, document)
        except (OSError, json.JSONDecodeError, Exception) as error:
            errors.append(f"{path}: schema compilation failed: {error}")

    for path, document in schema_documents:
        count, ref_errors = resolve_refs(path, document, documents_by_id)
        ref_count += count
        errors.extend(ref_errors)

    for path in openapi_paths:
        try:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            validate_spec(document)
            count, ref_errors = resolve_refs(path, document, documents_by_id)
            ref_count += count
            errors.extend(ref_errors)
        except (OSError, yaml.YAMLError, Exception) as error:
            errors.append(f"{path}: OpenAPI compilation failed: {error}")

    report = {
        "status": "PASS" if not errors else "FAIL",
        "contractsRoot": str(contracts_root),
        "validatorVersions": {
            "openapi-spec-validator": importlib.metadata.version("openapi-spec-validator"),
            "jsonschema": importlib.metadata.version("jsonschema"),
            "referencing": importlib.metadata.version("referencing"),
            "PyYAML": importlib.metadata.version("PyYAML"),
        },
        "draft": "2020-12",
        "schemaCount": len(schema_paths),
        "openapiCount": len(openapi_paths),
        "inputFileCount": len(input_paths),
        "resolvedRefCount": ref_count,
        "inputSha256": combined_hash(contracts_root, input_paths),
        "files": [
            {
                "path": path.relative_to(contracts_root).as_posix(),
                "sha256": sha256(path),
            }
            for path in input_paths
        ],
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
