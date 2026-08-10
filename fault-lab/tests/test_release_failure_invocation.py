from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.failure_catalog import FailureCatalog
from fault_lab.release.failure_invocation import (
    SubprocessFailurePreconditioner,
    seal_report,
    verify_precondition_report,
    verify_target_invocation,
)
from fault_lab.release.failure_plan import (
    FailurePlan,
    FailureRouteTopology,
    FailureTriggerCatalog,
)


FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "phase8"


def test_preconditions_require_exact_frozen_checks_and_sealed_evidence(
    tmp_path: Path,
) -> None:
    case = _case()
    run = _run()
    report = _preconditions(case, run, tmp_path)

    verified = verify_precondition_report(case, run, report)

    assert verified["status"] == "PASSED"
    assert set(verified["checks"]) == set(case["preconditions"])


def test_failed_or_missing_precondition_blocks_before_activation(tmp_path: Path) -> None:
    case = _case()
    run = _run()
    report = _preconditions(case, run, tmp_path)
    report["checks"][case["preconditions"][0]] = False

    with pytest.raises(ContractError, match="RELEASE_FAILURE_PRECONDITION_UNAVAILABLE"):
        verify_precondition_report(case, run, report)

    report = _preconditions(case, run, tmp_path)
    report["checks"].pop(case["preconditions"][0])
    with pytest.raises(ContractError, match="RELEASE_FAILURE_PRECONDITION_UNAVAILABLE"):
        verify_precondition_report(case, run, report)


def test_target_invocation_must_match_component_route_and_activation_window(
    tmp_path: Path,
) -> None:
    case = _case()
    proof = verify_target_invocation(
        case,
        _run(),
        _activation(),
        _invocation_evidence(case, tmp_path),
    )

    assert proof["status"] == "PASSED"
    assert proof["componentId"] == case["componentId"]
    assert proof["routeRef"] == case["routeRef"]


def test_missing_target_invocation_is_failed(tmp_path: Path) -> None:
    case = _case()
    evidence = _invocation_evidence(case, tmp_path)
    evidence["invocations"] = []

    with pytest.raises(ContractError, match="RELEASE_FAILURE_INVOCATION_NOT_OBSERVED"):
        verify_target_invocation(case, _run(), _activation(), evidence)


def test_wrong_route_and_hidden_fallback_are_failed(tmp_path: Path) -> None:
    case = _case()
    evidence = _invocation_evidence(case, tmp_path)
    evidence["invocations"][0]["routeRef"] = "network://unfrozen-route"
    with pytest.raises(ContractError, match="RELEASE_FAILURE_INVOCATION_ROUTE_MISMATCH"):
        verify_target_invocation(case, _run(), _activation(), evidence)

    evidence = _invocation_evidence(case, tmp_path)
    evidence["invocations"][0]["hiddenFallback"] = True
    with pytest.raises(ContractError, match="RELEASE_FAILURE_HIDDEN_FALLBACK"):
        verify_target_invocation(case, _run(), _activation(), evidence)


def test_invocation_outside_activation_window_or_from_other_run_is_invalid(
    tmp_path: Path,
) -> None:
    case = _case()
    evidence = _invocation_evidence(case, tmp_path)
    evidence["invocations"][0]["startedAt"] = "2026-08-09T23:59:59.000Z"
    with pytest.raises(ContractError, match="RELEASE_FAILURE_INVOCATION_EVIDENCE_INVALID"):
        verify_target_invocation(case, _run(), _activation(), evidence)

    evidence = _invocation_evidence(case, tmp_path)
    evidence["invocations"][0]["runId"] = "other-run"
    with pytest.raises(ContractError, match="RELEASE_FAILURE_INVOCATION_EVIDENCE_INVALID"):
        verify_target_invocation(case, _run(), _activation(), evidence)


def test_subprocess_preconditioner_uses_fixed_argv_and_validates_response(
    tmp_path: Path,
) -> None:
    case = _case()
    run = _run()
    calls = []

    def command(argv, **kwargs):
        calls.append((argv, kwargs))
        return SimpleNamespace(
            returncode=0,
            stdout=json.dumps(_preconditions(case, run, tmp_path)),
        )

    result = SubprocessFailurePreconditioner(
        ("python", "probe.py"), command_runner=command
    )(case, run)

    assert result["status"] == "PASSED"
    assert calls[0][0] == ["python", "probe.py"]
    assert json.loads(calls[0][1]["input"])["case"]["triggerRef"] == case["triggerRef"]


def _case():
    return FailurePlan(
        FailureCatalog.load(FIXTURES / "failure-catalog-v1.yaml"),
        FailureRouteTopology.load(FIXTURES / "failure-route-topology-v1.yaml"),
        FailureTriggerCatalog.load(FIXTURES / "failure-trigger-catalog-v1.yaml"),
    ).cases[0]


def _run():
    return {
        "releaseBatchId": "phase8-failure-01",
        "runPurpose": "FAILURE_INJECTION",
        "runIdentity": "failure:phase8:case-01",
    }


def _activation():
    return {
        "activationId": "activation-01",
        "activatedAt": "2026-08-10T00:00:00.000Z",
    }


def _preconditions(case, run, tmp_path: Path):
    report = {
        "caseId": case["caseId"],
        "triggerRef": case["triggerRef"],
        "runIdentity": run["runIdentity"],
        "runPurpose": "FAILURE_INJECTION",
        "checkedAt": "2026-08-09T23:59:59.000Z",
        "checks": {name: True for name in case["preconditions"]},
    }
    report["evidenceArtifact"] = seal_report(
        tmp_path / "preconditions.json", report
    )
    return report


def _invocation_evidence(case, tmp_path: Path):
    evidence = {
        "runIdentity": _run()["runIdentity"],
        "runPurpose": "FAILURE_INJECTION",
        "activationId": "activation-01",
        "productRun": {
            "incidentId": "incident-01",
            "runId": "product-run-01",
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
                "runId": "product-run-01",
                "invocationId": "invocation-01",
                "hiddenFallback": False,
            }
        ],
    }
    evidence["evidenceArtifact"] = seal_report(
        tmp_path / "invocation.json", evidence
    )
    return evidence
