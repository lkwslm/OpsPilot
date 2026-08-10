from __future__ import annotations

import json
import hashlib
from datetime import datetime, timezone
from pathlib import Path

from fault_lab.release.failure_catalog import FailureCatalog
from fault_lab.release.failure_matrix import FailureControlPlane, FailureMatrixRunner
from fault_lab.release.failure_plan import (
    FailurePlan,
    FailureRouteTopology,
    FailureTriggerCatalog,
)


CATALOG = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "phase8"
    / "failure-catalog-v1.yaml"
)
FIXTURES = CATALOG.parent


class RecordingPlane:
    def __init__(self, *, fail_recovery: bool = False):
        self.fail_recovery = fail_recovery
        self.active: str | None = None
        self.events: list[tuple[str, str]] = []

    def health(self, case):
        self.events.append(("health", case["caseId"]))
        return {"healthy": self.active is None, "residualFaults": []}

    def activate(self, case):
        self.active = case["caseId"]
        self.events.append(("activate", case["caseId"]))
        return {
            "activationId": "activation-" + case["caseId"],
            "routeRef": case["routeRef"],
            "faultType": case["faultType"],
            "changedRoutes": [case["routeRef"]],
            "activatedAt": "2026-08-10T00:00:00.000Z",
        }

    def recover(self, case, activation):
        self.events.append(("recover", case["caseId"]))
        self.active = None
        return {
            "healthy": not self.fail_recovery,
            "residualFaults": [case["routeRef"]] if self.fail_recovery else [],
            "restoredRouteRef": case["routeRef"],
        }


def test_runs_complete_matrix_with_isolated_routes_and_sealed_case_evidence(tmp_path: Path) -> None:
    planes = {kind: RecordingPlane() for kind in _route_kinds()}
    identities = iter(f"run-{index:03d}" for index in range(100))
    runner = FailureMatrixRunner(
        FailureControlPlane(planes),
        _passing_observation,
        tmp_path,
        preconditioner=_passing_preconditions,
        now=lambda: datetime(2026, 8, 10, tzinfo=timezone.utc),
        identity=lambda: next(identities),
    )

    matrix = runner.execute("phase8-failure-01", _plan())

    assert matrix["status"] == "PASSED"
    assert matrix["totalCases"] == 100
    assert matrix["counts"] == {"PASSED": 100, "FAILED": 0, "BLOCKED": 0}
    assert set(matrix["routeKinds"]) == _route_kinds()
    assert len(list((tmp_path / "cases").glob("*/case-result.json"))) == 100
    assert all(
        plane.events[index][0] == (
            "health",
            "activate",
            "recover",
            "health",
        )[index % 4]
        for plane in planes.values()
        for index in range(len(plane.events))
    )
    first = json.loads(
        (tmp_path / "cases" / matrix["cases"][0]["caseId"] / "case-result.json").read_text()
    )
    assert first["runPurpose"] == "FAILURE_INJECTION"
    assert first["recovery"]["residualFaults"] == []
    assert "artifactSha256" not in first
    first_path = tmp_path / "cases" / matrix["cases"][0]["caseId"] / "case-result.json"
    assert hashlib.sha256(first_path.read_bytes()).hexdigest() == matrix["cases"][0]["artifactSha256"]


def test_recovery_failure_stops_mutating_cases_and_blocks_remainder(tmp_path: Path) -> None:
    planes = {kind: RecordingPlane() for kind in _route_kinds()}
    planes["NETWORK_PROXY"].fail_recovery = True
    runner = FailureMatrixRunner(
        FailureControlPlane(planes),
        _passing_observation,
        tmp_path,
        preconditioner=_passing_preconditions,
        now=lambda: datetime(2026, 8, 10, tzinfo=timezone.utc),
        identity=lambda: "fixed",
    )

    matrix = runner.execute("phase8-failure-02", _plan())

    assert matrix["status"] == "FAILED"
    assert matrix["counts"] == {"PASSED": 0, "FAILED": 1, "BLOCKED": 99}
    assert sum(len(plane.events) for plane in planes.values()) == 4
    assert matrix["cases"][1]["status"] == "BLOCKED"


def _passing_observation(case, run, activation):
    if case["criticality"] == "CONDITIONAL" and case["expectedTerminalState"] != "FAILED":
        criticality = {
            "attempts": [1, 2],
            "parentDeadlineMillis": 10_000,
            "elapsedMillis": 1_000,
            "technicalFailure": True,
            "terminalState": "COMPLETED_LIMITED",
            "continuationAllowed": True,
            "independentSourceSucceeded": True,
            "chainFailures": [case["componentId"] + ":TIMEOUT"],
            "missingEvidence": [case["componentId"]],
            "limitations": ["capability unavailable"],
        }
    else:
        criticality = {
            "attempts": [1, 2],
            "parentDeadlineMillis": 10_000,
            "elapsedMillis": 1_000,
            "technicalFailure": True,
            "terminalState": "FAILED",
        }
        if case["criticality"] == "MANDATORY_WHEN_CANDIDATES_EXIST":
            criticality["candidatesExist"] = True
        if case["criticality"] == "OPTIONAL_APPROVED":
            criticality.update(approvalState="APPROVED", invocationStarted=True)
    failure_record = {
        "sourceId": case["sourceId"],
        "sourceKind": case["sourceKind"],
        "adapterId": case["adapterId"],
        "attempt": 2,
        "checkpoint": "step-started-v1",
        "requestId": "request-01",
        "traceId": "trace-01",
        "runId": run["runIdentity"],
        "stepId": "step-01",
        "invocationId": activation["activationId"],
        "logArtifactId": "artifact-01",
        "upstreamStatus": case["upstreamStatus"],
        "chainFailure": True,
    }
    observed = {
        "criticality": criticality,
        "failureRecord": failure_record,
        "routingLedger": {
            "runPurpose": "FAILURE_INJECTION",
            "qualityDenominatorEligible": False,
            "providerIds": ["deepseek"],
            "sourceIds": ["phase0-prometheus"],
            "algorithms": ["EXACT_VECTOR"],
            "fallbacks": {"provider": False, "source": False},
            "candidatesExist": False,
            "steps": [
                {"name": "EXACT_RECALL", "status": "FAILED", "reasonRecorded": True}
            ],
        },
        "frozenRoutes": {
            "providerIds": ["deepseek"],
            "sourceIds": ["phase0-prometheus"],
            "algorithms": ["EXACT_VECTOR"],
            "requiredSteps": ["EXACT_RECALL"],
        },
        "invocationEvidence": {
            "runIdentity": run["runIdentity"],
            "runPurpose": "FAILURE_INJECTION",
            "activationId": activation["activationId"],
            "productRun": {
                "incidentId": "incident-01",
                "runId": run["runIdentity"],
                "status": "FAILED",
            },
            "invocations": [
                {
                    "componentId": case["componentId"],
                    "routeRef": case["routeRef"],
                    "evidenceSelector": case["evidenceSelectors"][0],
                    "startedAt": "2026-08-10T00:00:01.000Z",
                    "endedAt": "2026-08-10T00:00:02.000Z",
                    "requestId": "request-01",
                    "traceId": "trace-01",
                    "runId": run["runIdentity"],
                    "invocationId": "invocation-01",
                    "hiddenFallback": False,
                }
            ],
            "evidenceArtifact": {
                "uri": "artifact://phase8/invocation.json",
                "size": 1,
                "sha256": "0" * 64,
            },
        },
    }
    if case["componentKind"] == "A2A_ENDPOINT":
        observed["a2aTrace"] = {
            "originalTaskId": "task-01",
            "originalMessageId": "message-01",
            "operations": [
                {"operation": "TASKS_GET", "taskId": "task-01", "status": "COMPLETED"}
            ],
        }
    return observed


def _route_kinds() -> set[str]:
    return {"NETWORK_PROXY", "FILE_FIXTURE", "PROCESS_FIXTURE"}


def _passing_preconditions(case, run):
    return {
        "caseId": case["caseId"],
        "triggerRef": case["triggerRef"],
        "runIdentity": run["runIdentity"],
        "runPurpose": "FAILURE_INJECTION",
        "checkedAt": "2026-08-10T00:00:00.000Z",
        "checks": {name: True for name in case["preconditions"]},
        "evidenceArtifact": {
            "uri": "artifact://phase8/preconditions.json",
            "size": 1,
            "sha256": "0" * 64,
        },
    }


def _plan() -> FailurePlan:
    return FailurePlan(
        FailureCatalog.load(CATALOG),
        FailureRouteTopology.load(FIXTURES / "failure-route-topology-v1.yaml"),
        FailureTriggerCatalog.load(FIXTURES / "failure-trigger-catalog-v1.yaml"),
    )
