#!/usr/bin/env python3
"""Validate OpenAPI semantics and local Markdown links."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import unquote

import yaml

HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
MARKDOWN_LINK = re.compile(r"(?<!!)\[[^\]]+\]\(([^)]+)\)")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve_pointer(document: Any, reference: str) -> Any:
    if not reference.startswith("#/"):
        raise ValueError(f"only local OpenAPI references are supported: {reference}")
    current = document
    for raw_part in reference[2:].split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or part not in current:
            raise ValueError(f"unresolved OpenAPI reference: {reference}")
        current = current[part]
    return current


def walk_references(value: Any, location: str = "$") -> Iterable[tuple[str, str]]:
    if isinstance(value, dict):
        for key, child in value.items():
            child_location = f"{location}.{key}"
            if key == "$ref" and isinstance(child, str):
                yield child_location, child
            else:
                yield from walk_references(child, child_location)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk_references(child, f"{location}[{index}]")


def dereference(document: dict[str, Any], value: Any) -> Any:
    seen: set[str] = set()
    while isinstance(value, dict) and isinstance(value.get("$ref"), str):
        reference = value["$ref"]
        if reference in seen:
            raise ValueError(f"cyclic OpenAPI reference: {reference}")
        seen.add(reference)
        value = resolve_pointer(document, reference)
    return value


def validate_openapi(document: dict[str, Any]) -> tuple[list[str], dict[str, int]]:
    errors: list[str] = []
    operation_ids: list[str] = []
    operation_count = 0
    response_count = 0
    response_schema_count = 0
    references = list(walk_references(document))

    for location, reference in references:
        try:
            resolve_pointer(document, reference)
        except ValueError as error:
            errors.append(f"{location}: {error}")

    paths = document.get("paths")
    if not isinstance(paths, dict):
        errors.append("$.paths: must be an object")
        paths = {}
    for route, path_item in paths.items():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if method.lower() not in HTTP_METHODS or not isinstance(operation, dict):
                continue
            operation_count += 1
            location = f"$.paths.{route}.{method}"
            operation_id = operation.get("operationId")
            if not isinstance(operation_id, str) or not operation_id:
                errors.append(f"{location}: operationId is required")
            else:
                operation_ids.append(operation_id)
            responses = operation.get("responses")
            if not isinstance(responses, dict) or not responses:
                errors.append(f"{location}.responses: at least one response is required")
                continue
            for status, response in responses.items():
                response_count += 1
                response_location = f"{location}.responses.{status}"
                try:
                    resolved_response = dereference(document, response)
                except ValueError as error:
                    errors.append(f"{response_location}: {error}")
                    continue
                if not isinstance(resolved_response, dict):
                    errors.append(f"{response_location}: response must be an object")
                    continue
                content = resolved_response.get("content")
                if not isinstance(content, dict) or not content:
                    errors.append(f"{response_location}: response content/schema is required")
                    continue
                for media_type, media in content.items():
                    if not isinstance(media, dict) or "schema" not in media:
                        errors.append(f"{response_location}.content.{media_type}: schema is required")
                    else:
                        response_schema_count += 1

    duplicates = sorted({item for item in operation_ids if operation_ids.count(item) > 1})
    if duplicates:
        errors.append(f"duplicate operationId values: {duplicates}")
    return errors, {
        "operationCount": operation_count,
        "uniqueOperationIdCount": len(set(operation_ids)),
        "responseCount": response_count,
        "responseSchemaCount": response_schema_count,
        "referenceCount": len(references),
    }


def validate_markdown_links(roots: Iterable[Path]) -> tuple[list[str], list[Path], int]:
    errors: list[str] = []
    files: list[Path] = []
    link_count = 0
    for root in roots:
        files.extend(sorted(root.resolve().rglob("*.md")))
    for markdown_path in files:
        text = markdown_path.read_text(encoding="utf-8")
        for match in MARKDOWN_LINK.finditer(text):
            raw_target = match.group(1).strip()
            if raw_target.startswith("<") and raw_target.endswith(">"):
                raw_target = raw_target[1:-1]
            target = raw_target.split(maxsplit=1)[0]
            if target.startswith(("http://", "https://", "mailto:", "#")):
                continue
            link_count += 1
            path_part = unquote(target.split("#", 1)[0])
            if not path_part:
                continue
            resolved = (markdown_path.parent / path_part).resolve()
            if not resolved.exists():
                line = text.count("\n", 0, match.start()) + 1
                errors.append(
                    f"{markdown_path.as_posix()}:{line}: broken local link: {raw_target}"
                )
    return errors, files, link_count


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--openapi", type=Path, required=True)
    parser.add_argument("--markdown-root", type=Path, action="append", required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    openapi_path = args.openapi.resolve()
    document = yaml.safe_load(openapi_path.read_text(encoding="utf-8"))
    openapi_errors, openapi_metrics = validate_openapi(document)
    link_errors, markdown_files, link_count = validate_markdown_links(args.markdown_root)
    errors = openapi_errors + link_errors
    report = {
        "status": "PASS" if not errors else "FAIL",
        "openapi": openapi_path.as_posix(),
        "openapiSha256": sha256(openapi_path),
        **openapi_metrics,
        "markdownFileCount": len(markdown_files),
        "localMarkdownLinkCount": link_count,
        "markdownInputSha256": hashlib.sha256(
            "".join(f"{path.as_posix()}:{sha256(path)}\n" for path in markdown_files).encode("utf-8")
        ).hexdigest(),
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
