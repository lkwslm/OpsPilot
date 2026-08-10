from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from types import SimpleNamespace

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.failure_catalog import FailureCatalog
from fault_lab.release.failure_driver_protocol import (
    PROTOCOL_VERSION,
    FailureDriverProtocol,
    SubprocessCaseExecutor,
)
from fault_lab.release.failure_matrix import SubprocessFailureDriver
from fault_lab.release.failure_plan import (
    FailurePlan,
    FailureRouteTopology,
    FailureTriggerCatalog,
)


FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "phase8"


class RecordingPlane:
    def __init__(self) -> None:
        self.active: str | None = None
        self.changed_routes: list[str] | None = None
        self.recovery_residuals: list[str] = []
        self.events: list[str] = []

    def health(self, case):
        self.events.append("health")
        return {
            "healthy": self.active is None,
            "residualFaults": [] if self.active is None else [self.active],
            "routeSnapshot": {"active": self.active},
        }

    def activate(self, case):
        self.events.append("activate")
        self.active = case["routeRef"]
        return {
            "activationId": "activation-01",
            "routeRef": case["routeRef"],
            "faultType": case["faultType"],
            "changedRoutes": self.changed_routes or [case["routeRef"]],
            "before": {"active": None},
            "after": {"active": case["routeRef"]},
            "recoveryCredential": {"token": "restore-01"},
        }

    def recover(self, case, activation):
        self.events.append("recover")
        if not self.recovery_residuals:
            self.active = None
        return {
            "healthy": not self.recovery_residuals,
            "residualFaults": self.recovery_residuals,
            "restoredRouteRef": case["routeRef"],
            "routeSnapshot": {"active": self.active},
        }


def test_protocol_serializes_lease_executes_and_recovers_idempotently(
    tmp_path: Path,
) -> None:
    protocol, case, plane = _protocol(tmp_path)
    request = _request(case)

    health = protocol.handle("health", request)
    activation = protocol.handle("activate", request)
    execution = protocol.handle("execute", request)
    recovery = protocol.handle("recover", request)
    repeated = protocol.handle("recover", request)

    assert health["status"] == "PASSED"
    assert activation["changedRoutes"] == [case["routeRef"]]
    assert execution["observation"] == {"invoked": case["componentId"]}
    assert recovery["healthy"] is True
    assert repeated["alreadyRecovered"] is True
    assert plane.events == [
        "health",
        "health",
        "activate",
        "recover",
        "health",
    ]
    for response in (health, activation, execution, recovery, repeated):
        artifact = response["evidenceArtifact"]
        data = Path(artifact["uri"]).read_bytes()
        assert artifact["size"] == len(data)
        assert artifact["sha256"] == hashlib.sha256(data).hexdigest()


def test_protocol_rejects_conflicting_or_mismatched_lease(tmp_path: Path) -> None:
    protocol, case, _ = _protocol(tmp_path)
    request = _request(case)
    protocol.handle("activate", request)

    with pytest.raises(ContractError, match="RELEASE_FAILURE_LEASE_CONFLICT"):
        protocol.handle("activate", {**request, "leaseToken": "lease-0002"})
    with pytest.raises(ContractError, match="RELEASE_FAILURE_LEASE_INVALID"):
        protocol.handle("execute", {**request, "leaseToken": "lease-0002"})
    with pytest.raises(ContractError, match="RELEASE_FAILURE_LEASE_INVALID"):
        protocol.handle("recover", {**request, "runIdentity": "failure:other"})


def test_non_target_change_is_recovered_and_releases_lease(tmp_path: Path) -> None:
    protocol, case, plane = _protocol(tmp_path)
    plane.changed_routes = [case["routeRef"], "network://other"]

    with pytest.raises(ContractError, match="RELEASE_FAILURE_DATA_PLANE_INVALID"):
        protocol.handle("activate", _request(case))

    assert plane.events == ["health", "activate", "recover"]
    assert plane.active is None
    assert not (tmp_path / "active-lease.json").exists()


def test_execution_failure_still_allows_recovery(tmp_path: Path) -> None:
    def fail_execute(case, request, activation):
        raise RuntimeError("product failed")

    protocol, case, plane = _protocol(tmp_path, executor=fail_execute)
    request = _request(case)
    protocol.handle("activate", request)

    with pytest.raises(ContractError, match="RELEASE_FAILURE_EXECUTION_FAILED"):
        protocol.handle("execute", request)

    assert protocol.handle("recover", request)["status"] == "PASSED"
    assert plane.active is None


def test_recovery_residual_keeps_lease_and_blocks_next_case(tmp_path: Path) -> None:
    protocol, case, plane = _protocol(tmp_path)
    request = _request(case)
    protocol.handle("activate", request)
    plane.recovery_residuals = [case["routeRef"]]

    with pytest.raises(ContractError, match="RELEASE_FAILURE_RECOVERY_FAILED"):
        protocol.handle("recover", request)
    with pytest.raises(ContractError, match="RELEASE_FAILURE_LEASE_CONFLICT"):
        protocol.handle("activate", {**request, "leaseToken": "lease-0002"})

    assert (tmp_path / "active-lease.json").exists()


@pytest.mark.parametrize(
    "mutation",
    [
        lambda request: {key: value for key, value in request.items() if key != "triggerRef"},
        lambda request: {**request, "protocolVersion": "2.0.0"},
        lambda request: {**request, "routeRef": "network://missing"},
        lambda request: {**request, "leaseToken": "short"},
    ],
)
def test_protocol_rejects_invalid_request(tmp_path: Path, mutation) -> None:
    protocol, case, _ = _protocol(tmp_path)

    with pytest.raises(ContractError, match="RELEASE_FAILURE_DRIVER_PROTOCOL_INVALID"):
        protocol.handle("health", mutation(_request(case)))


def test_subprocess_executor_uses_fixed_argv_and_structured_io() -> None:
    calls = []

    def run(argv, **kwargs):
        calls.append((argv, kwargs))
        return SimpleNamespace(returncode=0, stdout='{"observed":true}')

    executor = SubprocessCaseExecutor(("python", "adapter.py"), command_runner=run)
    result = executor({"caseId": "case-01"}, {"runIdentity": "run-01"}, {})

    assert result == {"observed": True}
    assert calls[0][0] == ["python", "adapter.py"]
    assert calls[0][1]["timeout"] == 900
    assert json.loads(calls[0][1]["input"])["request"]["runIdentity"] == "run-01"


@pytest.mark.parametrize(
    ("completed", "error"),
    [
        (SimpleNamespace(returncode=2, stdout=""), "exit=2"),
        (SimpleNamespace(returncode=0, stdout="not-json"), "invalid executor response"),
    ],
)
def test_subprocess_executor_rejects_failed_or_invalid_response(completed, error) -> None:
    executor = SubprocessCaseExecutor(
        ("adapter",), command_runner=lambda *args, **kwargs: completed
    )

    with pytest.raises(ContractError, match=error):
        executor({}, {}, {})


def test_subprocess_executor_maps_timeout_to_stable_error() -> None:
    def timeout(*args, **kwargs):
        raise subprocess.TimeoutExpired("adapter", 900)

    executor = SubprocessCaseExecutor(("adapter",), command_runner=timeout)

    with pytest.raises(ContractError, match="RELEASE_FAILURE_EXECUTION_FAILED"):
        executor({}, {}, {})


def test_matrix_subprocess_driver_sends_versioned_request_and_reuses_run_lease() -> None:
    requests = []

    def run(argv, **kwargs):
        request = json.loads(kwargs["input"])
        operation = argv[-1]
        requests.append((operation, request))
        fields = {
            "protocolVersion": PROTOCOL_VERSION,
            "operation": operation,
            "status": "PASSED",
            "code": "OK",
            "healthy": True,
            "residualFaults": [],
        }
        if operation == "activate":
            fields.update(
                activationId="activation-01",
                routeRef=request["routeRef"],
                faultType=request["faultType"],
                changedRoutes=[request["routeRef"]],
            )
        elif operation == "execute":
            fields["observation"] = {"invoked": True}
        elif operation == "recover":
            fields["restoredRouteRef"] = request["routeRef"]
        return SimpleNamespace(returncode=0, stdout=json.dumps(fields))

    case = _plan().cases[0]
    driver = SubprocessFailureDriver(("python", "-m", "driver"), case["routeKind"], command_runner=run)
    driver.prepare_run(case, "failure:batch:case-01")
    driver.health(case)
    activation = driver.activate(case)
    observation = driver.execute_case(case, {"runIdentity": "failure:batch:case-01"}, activation)
    recovery = driver.recover(case, activation)

    assert observation == {"invoked": True}
    assert recovery["restoredRouteRef"] == case["routeRef"]
    assert [operation for operation, _ in requests] == [
        "health",
        "activate",
        "execute",
        "recover",
    ]
    assert {request["runIdentity"] for _, request in requests} == {
        "failure:batch:case-01"
    }
    assert len({request["leaseToken"] for _, request in requests}) == 1
    assert all(set(request) == {
        "protocolVersion",
        "caseId",
        "routeRef",
        "faultType",
        "runIdentity",
        "triggerRef",
        "leaseToken",
    } for _, request in requests)


def test_matrix_subprocess_driver_rejects_structured_blocked_response() -> None:
    case = _plan().cases[0]

    def run(*args, **kwargs):
        return SimpleNamespace(
            returncode=0,
            stdout=json.dumps(
                {
                    "status": "BLOCKED",
                    "code": "RELEASE_FAILURE_ROUTE_UNAVAILABLE",
                    "detail": "missing proxy",
                }
            ),
        )

    driver = SubprocessFailureDriver(("driver",), case["routeKind"], command_runner=run)

    with pytest.raises(ContractError, match="RELEASE_FAILURE_ROUTE_UNAVAILABLE"):
        driver.health(case)


def _protocol(tmp_path: Path, executor=None):
    plan = _plan()
    case = plan.cases[0]
    plane = RecordingPlane()
    return (
        FailureDriverProtocol(
            plan,
            {kind: plane for kind in ("NETWORK_PROXY", "FILE_FIXTURE", "PROCESS_FIXTURE")},
            tmp_path / "active-lease.json",
            tmp_path / "evidence",
            executor or (lambda case, request, activation: {"invoked": case["componentId"]}),
        ),
        case,
        plane,
    )


def _plan():
    return FailurePlan(
        FailureCatalog.load(FIXTURES / "failure-catalog-v1.yaml"),
        FailureRouteTopology.load(FIXTURES / "failure-route-topology-v1.yaml"),
        FailureTriggerCatalog.load(FIXTURES / "failure-trigger-catalog-v1.yaml"),
    )


def _request(case):
    return {
        "protocolVersion": PROTOCOL_VERSION,
        "caseId": case["caseId"],
        "routeRef": case["routeRef"],
        "faultType": case["faultType"],
        "runIdentity": "failure:phase8:case-01",
        "triggerRef": case["triggerRef"],
        "leaseToken": "lease-0001",
    }
