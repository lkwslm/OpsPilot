from __future__ import annotations

from typing import Any, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode, RunPurpose


_FORBIDDEN_ALGORITHMS = {"VECTOR_ONLY", "KEYWORD", "FIXED_RESULT", "FIXED_RANKING", "MOCK"}


def audit_failure_routes(
    ledger: Mapping[str, Any], frozen_routes: Mapping[str, Any]
) -> dict[str, Any]:
    if ledger.get("runPurpose") != RunPurpose.FAILURE_INJECTION.value:
        _invalid("runPurpose")
    if ledger.get("qualityDenominatorEligible") is not False:
        _invalid("failure Run could enter quality denominator")
    for field in ("providerIds", "sourceIds", "algorithms"):
        actual = ledger.get(field)
        allowed = frozen_routes.get(field)
        if not isinstance(actual, list) or not isinstance(allowed, list):
            _invalid(field)
        unexpected = sorted(set(actual) - set(allowed))
        if unexpected:
            _invalid(f"unauthorized {field}: {unexpected}")
    algorithms = set(ledger["algorithms"])
    if algorithms.intersection(_FORBIDDEN_ALGORITHMS):
        _invalid("hidden fallback algorithm")
    fallbacks = ledger.get("fallbacks")
    if not isinstance(fallbacks, Mapping) or any(value for value in fallbacks.values()):
        _invalid("hidden fallback route")

    steps = ledger.get("steps")
    if not isinstance(steps, list) or not steps:
        _invalid("steps")
    names = {step.get("name") for step in steps if isinstance(step, Mapping)}
    required = set(frozen_routes.get("requiredSteps", []))
    if not required.issubset(names):
        _invalid(f"unrecorded required steps: {sorted(required - names)}")
    for step in steps:
        if not isinstance(step, Mapping) or step.get("status") not in {"SUCCEEDED", "FAILED", "SKIPPED"}:
            _invalid("step record")
        if step["status"] == "SKIPPED" and not step.get("reasonRecorded"):
            _invalid(f"unrecorded skipped step: {step.get('name')}")
    if ledger.get("candidatesExist") is True:
        rerank = next((step for step in steps if step.get("name") == "RERANK"), None)
        if rerank is None or rerank.get("status") == "SKIPPED":
            _invalid("Rerank skipped with candidates")
    return {
        "status": "PASSED",
        "runPurpose": RunPurpose.FAILURE_INJECTION.value,
        "qualityDenominatorEligible": False,
        "providerIds": list(ledger["providerIds"]),
        "sourceIds": list(ledger["sourceIds"]),
        "algorithms": list(ledger["algorithms"]),
    }


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_ROUTING_INVALID.value, detail)
