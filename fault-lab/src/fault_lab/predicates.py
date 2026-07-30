from __future__ import annotations

from typing import Any

from .contracts import ContractError


def field_value(document: dict[str, Any], dotted: str) -> Any:
    value: Any = document
    for part in dotted.split("."):
        if not isinstance(value, dict) or part not in value:
            raise ContractError("PREDICATE_FIELD_MISSING", dotted)
        value = value[part]
    return value


def matches(document: dict[str, Any], predicate: dict[str, Any]) -> bool:
    try:
        actual = field_value(document, predicate["field"])
    except ContractError:
        return predicate["operator"] == "EXISTS" and predicate.get("value") is False
    expected = predicate.get("value")
    operator = predicate["operator"]
    operations = {
        "EQ": lambda: actual == expected,
        "NE": lambda: actual != expected,
        "GT": lambda: actual > expected,
        "GTE": lambda: actual >= expected,
        "LT": lambda: actual < expected,
        "LTE": lambda: actual <= expected,
        "CONTAINS": lambda: expected in actual,
        "EXISTS": lambda: bool(expected),
    }
    if operator not in operations:
        raise ContractError("PREDICATE_OPERATOR_UNKNOWN", operator)
    try:
        return bool(operations[operator]())
    except (TypeError, ValueError) as exc:
        raise ContractError("PREDICATE_TYPE_INVALID", predicate["field"]) from exc
