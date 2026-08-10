from __future__ import annotations

from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.failure_catalog import FailureCatalog
from fault_lab.release.failure_injection import (
    ControlledHttpFailureFixture,
    verify_a2a_failure_trace,
    verify_capability_failure_record,
)
from fault_lab.release.model import ReleaseErrorCode


CATALOG = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "phase8"
    / "failure-catalog-v1.yaml"
)


@pytest.mark.parametrize(
    ("component_id", "fault_type", "expected"),
    [
        ("model:chat", "UNAVAILABLE", "UNAVAILABLE"),
        ("model:embedding", "TIMEOUT", "TIMEOUT"),
        ("model:rerank", "AUTH", "AUTH_FAILED"),
        ("a2a:knowledge", "SCHEMA", "SCHEMA_INVALID"),
    ],
)
def test_real_socket_fixture_injects_model_and_knowledge_failures_and_recovers(
    component_id: str, fault_type: str, expected: str
) -> None:
    case = _case(component_id, fault_type)
    fixture = ControlledHttpFailureFixture(timeout_delay_seconds=0.15)

    injected = fixture.inject(case)
    observed = fixture.probe(timeout_seconds=0.03)
    recovered = fixture.recover(lambda: True)

    assert injected["componentId"] == component_id
    assert injected["fixtureEndpoint"].startswith("http://127.0.0.1:")
    assert observed["observedUpstreamStatus"] == expected
    assert recovered == {"performed": True, "healthy": True, "residualFaults": []}


def test_recovery_fails_closed_when_original_dependency_is_not_healthy() -> None:
    fixture = ControlledHttpFailureFixture()
    fixture.inject(_case("a2a:evidence-collector", "AUTH"))

    with pytest.raises(ContractError) as exc_info:
        fixture.recover(lambda: False)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_INJECTION_INVALID.value


def test_a2a_reconciles_original_completed_task_without_resend() -> None:
    case = _case("a2a:diagnosis", "TIMEOUT")
    trace = {
        "originalTaskId": "task-01",
        "originalMessageId": "message-01",
        "operations": [
            {"operation": "TASKS_GET", "taskId": "task-01", "status": "COMPLETED"},
            {"operation": "TASKS_SUBSCRIBE", "taskId": "task-01", "status": "COMPLETED"},
        ],
    }

    report = verify_a2a_failure_trace(case, trace)

    assert report["status"] == "PASSED"
    assert report["resent"] is False


def test_a2a_recreates_only_after_not_found_with_same_message_id() -> None:
    case = _case("a2a:remediation", "UNAVAILABLE")
    trace = {
        "originalTaskId": "task-02",
        "originalMessageId": "message-02",
        "operations": [
            {"operation": "TASKS_GET", "taskId": "task-02", "status": "NOT_FOUND"},
            {"operation": "MESSAGE_SEND", "taskId": "task-02", "messageId": "message-02", "status": "ACCEPTED"},
        ],
    }

    report = verify_a2a_failure_trace(case, trace)

    assert report["resent"] is True
    assert report["messageIdIdempotent"] is True


def test_a2a_rejects_blind_resend() -> None:
    case = _case("a2a:code-analysis", "TIMEOUT")
    trace = {
        "originalTaskId": "task-03",
        "originalMessageId": "message-03",
        "operations": [
            {"operation": "MESSAGE_SEND", "taskId": "task-03", "messageId": "message-new", "status": "ACCEPTED"},
            {"operation": "TASKS_GET", "taskId": "task-03", "status": "RUNNING"},
        ],
    }

    with pytest.raises(ContractError) as exc_info:
        verify_a2a_failure_trace(case, trace)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_INJECTION_INVALID.value


@pytest.mark.parametrize("component_id", ["tool:MetricQueryTool", "source:phase0-prometheus"])
def test_tool_and_source_failure_records_keep_full_identity_and_correlation(
    component_id: str,
) -> None:
    case = _case(component_id, "SCHEMA")
    record = {
        "sourceId": case["sourceId"],
        "sourceKind": case["sourceKind"],
        "adapterId": case["adapterId"],
        "attempt": 2,
        "checkpoint": "step-started-v1",
        "requestId": "request-01",
        "traceId": "trace-01",
        "runId": "run-01",
        "stepId": "step-01",
        "invocationId": "invocation-01",
        "logArtifactId": "artifact-01",
        "upstreamStatus": "SCHEMA_INVALID",
        "chainFailure": True,
    }

    report = verify_capability_failure_record(case, record)

    assert report == {"status": "PASSED", "caseId": case["caseId"], "attempt": 2}


def test_tool_failure_record_rejects_missing_source_identity() -> None:
    case = _case("source:phase0-jaeger-v1", "AUTH")
    record = {
        "sourceId": "wrong-source",
        "sourceKind": case["sourceKind"],
        "adapterId": case["adapterId"],
        "attempt": 1,
        "checkpoint": "step-started-v1",
        "requestId": "request-01",
        "traceId": "trace-01",
        "runId": "run-01",
        "stepId": "step-01",
        "invocationId": "invocation-01",
        "logArtifactId": "artifact-01",
        "upstreamStatus": "AUTH_FAILED",
        "chainFailure": True,
    }

    with pytest.raises(ContractError) as exc_info:
        verify_capability_failure_record(case, record)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_INJECTION_INVALID.value


def _case(component_id: str, fault_type: str) -> dict:
    return next(
        case
        for case in FailureCatalog.load(CATALOG).cases
        if case["componentId"] == component_id and case["faultType"] == fault_type
    )
