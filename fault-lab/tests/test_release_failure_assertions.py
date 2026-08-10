from __future__ import annotations

from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.criticality import CriticalityAssertionEngine
from fault_lab.release.failure_catalog import FailureCatalog
from fault_lab.release.model import ReleaseErrorCode, RunPurpose
from fault_lab.release.routing_audit import audit_failure_routes


CATALOG = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "phase8"
    / "failure-catalog-v1.yaml"
)


@pytest.mark.parametrize(
    ("component_id", "extra", "terminal"),
    [
        ("model:chat", {}, "FAILED"),
        ("model:rerank", {"candidatesExist": True}, "FAILED"),
        (
            "tool:MetricQueryTool",
            {
                "continuationAllowed": True,
                "independentSourceSucceeded": True,
                "chainFailures": ["phase0-prometheus:TIMEOUT"],
                "missingEvidence": ["metric.inventory.latency"],
                "limitations": ["Metric source unavailable"],
            },
            "COMPLETED_LIMITED",
        ),
        ("tool:CodeSearchTool", {}, "FAILED"),
        (
            "tool:SandboxTestTool",
            {"approvalState": "APPROVED", "invocationStarted": True},
            "FAILED",
        ),
    ],
)
def test_criticality_engine_enforces_each_failure_semantic(
    component_id: str, extra: dict, terminal: str
) -> None:
    case = _case(component_id)
    observed = {
        "attempts": [1, 2],
        "parentDeadlineMillis": 10_000,
        "elapsedMillis": 10_000,
        "technicalFailure": True,
        "terminalState": terminal,
        **extra,
    }

    report = CriticalityAssertionEngine().evaluate(case, observed)

    assert report["status"] == "PASSED"
    assert report["withinParentDeadline"] is True


def test_candidate_free_empty_result_is_not_misclassified_as_technical_failure() -> None:
    case = _case("model:rerank")
    observed = {
        "attempts": [1],
        "parentDeadlineMillis": 1_000,
        "elapsedMillis": 20,
        "technicalFailure": False,
        "terminalState": "COMPLETED",
        "candidatesExist": False,
        "knowledgeOutcome": "NO_MATCH",
    }

    report = CriticalityAssertionEngine().evaluate(case, observed)

    assert report["terminalState"] == "COMPLETED"


@pytest.mark.parametrize(
    "change",
    [
        lambda value: value.update(attempts=[1, 2, 3]),
        lambda value: value.update(elapsedMillis=10_001),
        lambda value: value.update(terminalState="COMPLETED"),
    ],
    ids=("retry-overrun", "deadline-overrun", "critical-continued"),
)
def test_criticality_engine_rejects_retry_deadline_or_terminal_violation(change) -> None:
    case = _case("model:chat")
    observed = {
        "attempts": [1, 2],
        "parentDeadlineMillis": 10_000,
        "elapsedMillis": 10_000,
        "technicalFailure": True,
        "terminalState": "FAILED",
    }
    change(observed)

    with pytest.raises(ContractError) as exc_info:
        CriticalityAssertionEngine().evaluate(case, observed)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_ASSERTION_INVALID.value


def test_optional_capability_not_approved_must_not_execute() -> None:
    case = _case("tool:SandboxTestTool")
    observed = {
        "attempts": [1],
        "parentDeadlineMillis": 1_000,
        "elapsedMillis": 10,
        "technicalFailure": False,
        "terminalState": "COMPLETED_LIMITED",
        "approvalState": "DENIED",
        "invocationStarted": False,
    }

    assert CriticalityAssertionEngine().evaluate(case, observed)["status"] == "PASSED"


def test_routing_audit_accepts_only_frozen_routes_and_records_failure_isolation() -> None:
    ledger = _routing_ledger()

    report = audit_failure_routes(ledger, _frozen_routes())

    assert report["status"] == "PASSED"
    assert report["qualityDenominatorEligible"] is False


@pytest.mark.parametrize(
    "change",
    [
        lambda value: value["providerIds"].append("silent-backup"),
        lambda value: value["algorithms"].append("VECTOR_ONLY"),
        lambda value: value["fallbacks"].update(keyword=True),
        lambda value: value["steps"][2].update(status="SKIPPED", reasonRecorded=False),
        lambda value: value.update(runPurpose=RunPurpose.RELEASE_QUALITY.value),
        lambda value: value.update(qualityDenominatorEligible=True),
    ],
    ids=("provider-failover", "vector-only", "keyword", "skip-rerank", "wrong-purpose", "denominator"),
)
def test_routing_audit_rejects_hidden_fallback_skip_or_denominator_pollution(change) -> None:
    ledger = _routing_ledger()
    change(ledger)

    with pytest.raises(ContractError) as exc_info:
        audit_failure_routes(ledger, _frozen_routes())

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_ROUTING_INVALID.value


def _case(component_id: str) -> dict:
    return next(
        case
        for case in FailureCatalog.load(CATALOG).cases
        if case["componentId"] == component_id and case["faultType"] == "TIMEOUT"
    )


def _routing_ledger() -> dict:
    return {
        "runPurpose": RunPurpose.FAILURE_INJECTION.value,
        "qualityDenominatorEligible": False,
        "providerIds": ["deepseek", "infinity-local"],
        "sourceIds": ["phase0-prometheus", "phase0-jaeger-v1"],
        "algorithms": ["EXACT_VECTOR", "RERANK"],
        "fallbacks": {
            "provider": False,
            "source": False,
            "vectorOnly": False,
            "keyword": False,
            "fixedResult": False,
        },
        "candidatesExist": True,
        "steps": [
            {"name": "EMBEDDING", "status": "SUCCEEDED", "reasonRecorded": True},
            {"name": "EXACT_RECALL", "status": "SUCCEEDED", "reasonRecorded": True},
            {"name": "RERANK", "status": "FAILED", "reasonRecorded": True},
        ],
    }


def _frozen_routes() -> dict:
    return {
        "providerIds": ["deepseek", "infinity-local"],
        "sourceIds": ["phase0-prometheus", "phase0-jaeger-v1", "phase0-jaeger-v2"],
        "algorithms": ["EXACT_VECTOR", "RERANK"],
        "requiredSteps": ["EMBEDDING", "EXACT_RECALL", "RERANK"],
    }
