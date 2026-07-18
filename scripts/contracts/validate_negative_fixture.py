#!/usr/bin/env python3
"""Apply one negative-fixture mutation and validate it through JSON Schema."""

from __future__ import annotations

import argparse
import copy
import json
import sys
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker


def load_document(path: Path) -> Any:
    text = path.read_text(encoding="utf-8")
    return json.loads(text) if path.suffix == ".json" else yaml.safe_load(text)


def pointer_parts(pointer: str) -> list[str]:
    if not pointer.startswith("/"):
        raise ValueError(f"JSON Pointer must start with '/': {pointer}")
    return [part.replace("~1", "/").replace("~0", "~") for part in pointer[1:].split("/")]


def mutate(document: Any, operation: dict[str, Any]) -> None:
    parts = pointer_parts(operation["path"])
    target = document
    for part in parts[:-1]:
        target = target[int(part)] if isinstance(target, list) else target[part]
    key = parts[-1]
    if operation["op"] == "set":
        if isinstance(target, list):
            target[int(key)] = operation["value"]
        else:
            target[key] = operation["value"]
    elif operation["op"] == "remove":
        if isinstance(target, list):
            del target[int(key)]
        else:
            del target[key]
    else:
        raise ValueError(f"unsupported mutation operation: {operation['op']}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--contracts", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    args = parser.parse_args()

    root = args.contracts.resolve()
    fixture_path = args.fixture.resolve()
    try:
        fixture = yaml.safe_load(fixture_path.read_text(encoding="utf-8"))
        instance = copy.deepcopy(load_document(root / fixture["base"]))
        schema = json.loads((root / fixture["schema"]).read_text(encoding="utf-8"))
        for operation in fixture["mutations"]:
            mutate(instance, operation)
    except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError, yaml.YAMLError) as error:
        print(json.dumps({"status": "FIXTURE_ERROR", "error": str(error)}, ensure_ascii=False))
        return 2

    errors = sorted(
        Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(instance),
        key=lambda error: list(error.absolute_path),
    )
    messages = []
    for error in errors:
        location = "$" + "".join(
            f"[{part}]" if isinstance(part, int) else f".{part}"
            for part in error.absolute_path
        )
        messages.append(f"{location}: {error.message}")
    print(
        json.dumps(
            {
                "fixtureId": fixture["id"],
                "status": "REJECTED" if errors else "UNEXPECTEDLY_ACCEPTED",
                "errors": messages,
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
