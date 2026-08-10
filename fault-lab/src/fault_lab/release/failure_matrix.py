from __future__ import annotations

import hashlib
import json
import os
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol

from ..contracts import ContractError
from .criticality import CriticalityAssertionEngine
from .failure_injection import verify_a2a_failure_trace, verify_capability_failure_record
from .failure_driver_protocol import PROTOCOL_VERSION
from .failure_invocation import (
    UnavailableFailurePreconditioner,
    verify_precondition_report,
    verify_target_invocation,
)
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose
from .routing_audit import audit_failure_routes


class FailureRouteDataPlane(Protocol):
    def health(self, case: Mapping[str, Any]) -> Mapping[str, Any]: ...

    def activate(self, case: Mapping[str, Any]) -> Mapping[str, Any]: ...

    def recover(
        self, case: Mapping[str, Any], activation: Mapping[str, Any]
    ) -> Mapping[str, Any]: ...


class UnavailableFailureRouteDataPlane:
    """Produces sealed BLOCKED evidence when the real Compose driver is absent."""

    def __init__(self, detail: str):
        self.detail = detail

    def health(self, case: Mapping[str, Any]) -> Mapping[str, Any]:
        return {"healthy": False, "residualFaults": [self.detail]}

    def activate(self, case: Mapping[str, Any]) -> Mapping[str, Any]:
        _route_unavailable(self.detail)

    def recover(
        self, case: Mapping[str, Any], activation: Mapping[str, Any]
    ) -> Mapping[str, Any]:
        _route_unavailable(self.detail)


class SubprocessFailureDriver:
    """Calls one versioned Compose driver without shell interpolation."""

    def __init__(
        self,
        command: tuple[str, ...],
        route_kind: str,
        *,
        command_runner: Callable[..., Any] = subprocess.run,
    ):
        if not command or route_kind not in {
            "NETWORK_PROXY",
            "FILE_FIXTURE",
            "PROCESS_FIXTURE",
        }:
            _matrix_invalid("driver configuration")
        self.command = command
        self.route_kind = route_kind
        self.command_runner = command_runner
        self._context: dict[str, str] | None = None

    def prepare_run(self, case: Mapping[str, Any], run_identity: str) -> None:
        if not _text(case.get("triggerRef")) or not _text(run_identity):
            _matrix_invalid("driver run context")
        self._context = {
            "caseId": str(case["caseId"]),
            "runIdentity": run_identity,
            "leaseToken": f"lease-{uuid.uuid4().hex}",
        }

    def health(self, case: Mapping[str, Any]) -> Mapping[str, Any]:
        return self._invoke("health", {"case": case})

    def activate(self, case: Mapping[str, Any]) -> Mapping[str, Any]:
        if self._context is None:
            self.prepare_run(case, f"failure-driver:{case['caseId']}:{uuid.uuid4()}")
        try:
            return self._invoke("activate", {"case": case})
        except Exception:
            self._context = None
            raise

    def recover(
        self, case: Mapping[str, Any], activation: Mapping[str, Any]
    ) -> Mapping[str, Any]:
        try:
            return self._invoke("recover", {"case": case, "activation": activation})
        finally:
            self._context = None

    def execute_case(
        self,
        case: Mapping[str, Any],
        run: Mapping[str, Any],
        activation: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        response = self._invoke(
            "execute", {"case": case, "run": run, "activation": activation}, timeout=900
        )
        observation = response.get("observation")
        if not isinstance(observation, Mapping):
            raise ContractError(
                ReleaseErrorCode.FAILURE_EXECUTION_FAILED.value,
                "execute:missing observation",
            )
        return observation

    def _invoke(
        self, operation: str, payload: Mapping[str, Any], *, timeout: int = 60
    ) -> Mapping[str, Any]:
        case = payload.get("case", {})
        if case.get("routeKind") != self.route_kind:
            _matrix_invalid("driver route kind")
        if not _text(case.get("triggerRef")):
            _matrix_invalid("driver triggerRef")
        context = self._context
        if context is None or context.get("caseId") != case.get("caseId"):
            context = {
                "caseId": str(case["caseId"]),
                "runIdentity": f"failure-driver-health:{case['caseId']}:{uuid.uuid4()}",
                "leaseToken": f"health-{uuid.uuid4().hex}",
            }
        request = {
            "protocolVersion": PROTOCOL_VERSION,
            "caseId": case["caseId"],
            "routeRef": case["routeRef"],
            "faultType": case["faultType"],
            "runIdentity": context["runIdentity"],
            "triggerRef": case["triggerRef"],
            "leaseToken": context["leaseToken"],
        }
        try:
            completed = self.command_runner(
                [*self.command, operation],
                input=json.dumps(request, ensure_ascii=False, separators=(",", ":")),
                capture_output=True,
                text=True,
                check=False,
                timeout=timeout,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            code = (
                ReleaseErrorCode.FAILURE_RECOVERY_FAILED.value
                if operation == "recover"
                else ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value
            )
            raise ContractError(code, f"{operation}:{type(error).__name__}") from error
        if completed.returncode != 0:
            code = (
                ReleaseErrorCode.FAILURE_RECOVERY_FAILED.value
                if operation == "recover"
                else ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value
            )
            raise ContractError(code, f"{operation}:{completed.returncode}")
        try:
            value = json.loads(completed.stdout)
        except (json.JSONDecodeError, TypeError) as error:
            raise ContractError(
                ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
                f"{operation}:invalid driver response",
            ) from error
        if not isinstance(value, Mapping):
            _route_unavailable(f"{operation}:invalid driver response")
        if value.get("status") != ReleaseStatus.PASSED.value:
            code = value.get("code")
            detail = value.get("detail", f"{operation}:driver rejected")
            if not _text(code) or not isinstance(detail, str):
                _route_unavailable(f"{operation}:invalid driver response")
            raise ContractError(str(code), detail)
        return value


class FailureControlPlane:
    """Serializes destructive fault leases while preserving route-specific drivers."""

    def __init__(self, data_planes: Mapping[str, FailureRouteDataPlane]):
        expected = {"NETWORK_PROXY", "FILE_FIXTURE", "PROCESS_FIXTURE"}
        if set(data_planes) != expected:
            _matrix_invalid("data plane coverage")
        self._data_planes = dict(data_planes)
        self._active: tuple[Mapping[str, Any], Mapping[str, Any]] | None = None

    def activate(
        self, case: Mapping[str, Any], run_identity: str | None = None
    ) -> dict[str, Any]:
        if self._active is not None:
            _matrix_invalid("another failure case is active")
        if case.get("runPurpose") != RunPurpose.FAILURE_INJECTION.value:
            _matrix_invalid("runPurpose")
        route_kind = case.get("routeKind")
        route_ref = case.get("routeRef")
        data_plane = self._data_planes.get(str(route_kind))
        if data_plane is None or not _text(route_ref):
            _matrix_invalid("route identity")
        prepare_run = getattr(data_plane, "prepare_run", None)
        if callable(prepare_run):
            prepare_run(case, run_identity or f"failure-driver:{case['caseId']}:{uuid.uuid4()}")
        health = data_plane.health(case)
        if health.get("healthy") is not True or health.get("residualFaults") != []:
            _route_unavailable(str(route_ref))
        activation = dict(data_plane.activate(case))
        self._active = (case, activation)
        if (
            not _text(activation.get("activationId"))
            or activation.get("routeRef") != route_ref
            or activation.get("faultType") != case.get("faultType")
            or activation.get("changedRoutes") != [route_ref]
        ):
            self.recover(case)
            _matrix_invalid("activation isolation")
        return activation

    def recover(self, case: Mapping[str, Any]) -> dict[str, Any]:
        if self._active is None or self._active[0].get("caseId") != case.get("caseId"):
            _matrix_invalid("recovery lease")
        active_case, activation = self._active
        data_plane = self._data_planes[str(active_case["routeKind"])]
        try:
            recovery = dict(data_plane.recover(active_case, activation))
        finally:
            self._active = None
        health = data_plane.health(active_case)
        if (
            recovery.get("healthy") is not True
            or recovery.get("residualFaults") != []
            or recovery.get("restoredRouteRef") != active_case.get("routeRef")
            or health.get("healthy") is not True
            or health.get("residualFaults") != []
        ):
            raise ContractError(
                ReleaseErrorCode.FAILURE_RECOVERY_FAILED.value,
                str(active_case.get("routeRef")),
            )
        return recovery


CaseExecutor = Callable[
    [Mapping[str, Any], Mapping[str, Any], Mapping[str, Any]], Mapping[str, Any]
]
Preconditioner = Callable[
    [Mapping[str, Any], Mapping[str, Any]], Mapping[str, Any]
]


class FailureMatrixRunner:
    """Runs catalog cases one at a time and seals per-case and aggregate evidence."""

    def __init__(
        self,
        control: FailureControlPlane,
        executor: CaseExecutor,
        output_root: Path,
        *,
        preconditioner: Preconditioner | None = None,
        now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
        identity: Callable[[], str] = lambda: str(uuid.uuid4()),
    ):
        self.control = control
        self.executor = executor
        self.output_root = output_root.resolve()
        self.preconditioner = preconditioner or UnavailableFailurePreconditioner(
            "real failure precondition probe is not configured"
        )
        self.now = now
        self.identity = identity

    def execute(self, release_batch_id: str, catalog: Any) -> dict[str, Any]:
        cases = catalog.cases
        results: list[dict[str, Any]] = []
        stop: tuple[str, str] | None = None
        for case in cases:
            if stop is not None:
                result = self._blocked_case(release_batch_id, case, *stop)
            else:
                result, stop = self._execute_case(release_batch_id, case)
            self._write_case(result)
            results.append(result)

        counts = {
            status.value: sum(result["status"] == status.value for result in results)
            for status in (ReleaseStatus.PASSED, ReleaseStatus.FAILED, ReleaseStatus.BLOCKED)
        }
        status = (
            ReleaseStatus.FAILED.value
            if counts[ReleaseStatus.FAILED.value]
            else ReleaseStatus.BLOCKED.value
            if counts[ReleaseStatus.BLOCKED.value]
            else ReleaseStatus.PASSED.value
        )
        document = catalog.document
        matrix: dict[str, Any] = {
            "schemaVersion": "1.0.0",
            "suiteId": document["suiteId"],
            "suiteVersion": document["suiteVersion"],
            "releaseBatchId": release_batch_id,
            "runPurpose": RunPurpose.FAILURE_INJECTION.value,
            "catalogDigest": document["catalogDigest"],
            "status": status,
            "totalCases": len(cases),
            "counts": counts,
            "routeKinds": {
                route_kind: sum(case["routeKind"] == route_kind for case in cases)
                for route_kind in ("NETWORK_PROXY", "FILE_FIXTURE", "PROCESS_FIXTURE")
            },
            "cases": [
                {
                    "caseId": result["caseId"],
                    "runIdentity": result["runIdentity"],
                    "routeKind": result["routeKind"],
                    "routeRef": result["routeRef"],
                    "status": result["status"],
                    "artifact": f"cases/{result['caseId']}/case-result.json",
                    "artifactSha256": result["artifactSha256"],
                }
                for result in results
            ],
            "generatedAt": _timestamp(self.now()),
        }
        matrix["matrixDigest"] = _digest(matrix)
        _atomic_json(self.output_root / "criticality-matrix.json", matrix)
        return matrix

    def _execute_case(
        self, release_batch_id: str, case: Mapping[str, Any]
    ) -> tuple[dict[str, Any], tuple[str, str] | None]:
        started_at = _timestamp(self.now())
        run_identity = f"failure:{release_batch_id}:{case['caseId']}:{self.identity()}"
        activation: Mapping[str, Any] | None = None
        recovery: Mapping[str, Any] | None = None
        preconditions: Mapping[str, Any] | None = None
        assertions: Mapping[str, Any] | None = None
        status = ReleaseStatus.PASSED.value
        error_code: str | None = None
        detail: str | None = None
        stop: tuple[str, str] | None = None
        try:
            run = {
                "releaseBatchId": release_batch_id,
                "runPurpose": RunPurpose.FAILURE_INJECTION.value,
                "runIdentity": run_identity,
            }
            preconditions = verify_precondition_report(
                case,
                run,
                self.preconditioner(case, run),
            )
            activation = self.control.activate(case, run_identity)
            observed = self.executor(
                case,
                run,
                activation,
            )
            assertions = _verify_case(case, run, activation, observed)
        except ContractError as error:
            error_code, detail = error.code, error.detail
            status = (
                ReleaseStatus.BLOCKED.value
                if error.code
                in {
                    ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
                    ReleaseErrorCode.FAILURE_PRECONDITION_UNAVAILABLE.value,
                }
                else ReleaseStatus.FAILED.value
            )
            if status == ReleaseStatus.BLOCKED.value:
                stop = (error.code, error.detail)
        finally:
            if activation is not None:
                try:
                    recovery = self.control.recover(case)
                except ContractError as error:
                    error_code, detail = error.code, error.detail
                    status = ReleaseStatus.FAILED.value
                    stop = (error.code, error.detail)

        result = _case_result(
            release_batch_id,
            case,
            run_identity,
            started_at,
            _timestamp(self.now()),
            status,
            activation,
            recovery,
            preconditions,
            assertions,
            error_code,
            detail,
        )
        return result, stop

    def _blocked_case(
        self,
        release_batch_id: str,
        case: Mapping[str, Any],
        error_code: str,
        detail: str,
    ) -> dict[str, Any]:
        timestamp = _timestamp(self.now())
        return _case_result(
            release_batch_id,
            case,
            f"failure:{release_batch_id}:{case['caseId']}:{self.identity()}",
            timestamp,
            timestamp,
            ReleaseStatus.BLOCKED.value,
            None,
            None,
            None,
            None,
            error_code,
            f"not run after mutable environment failure: {detail}",
        )

    def _write_case(self, result: dict[str, Any]) -> None:
        path = self.output_root / "cases" / result["caseId"] / "case-result.json"
        _atomic_json(path, result)
        result["artifactSha256"] = hashlib.sha256(path.read_bytes()).hexdigest()


def _verify_case(
    case: Mapping[str, Any],
    run: Mapping[str, Any],
    activation: Mapping[str, Any],
    observed: Mapping[str, Any],
) -> dict[str, Any]:
    if not isinstance(observed, Mapping):
        _matrix_invalid("case observation")
    criticality = CriticalityAssertionEngine().evaluate(case, observed.get("criticality", {}))
    failure_record = verify_capability_failure_record(case, observed.get("failureRecord", {}))
    routing = audit_failure_routes(
        observed.get("routingLedger", {}), observed.get("frozenRoutes", {})
    )
    invocation = verify_target_invocation(
        case, run, activation, observed.get("invocationEvidence", {})
    )
    a2a = None
    if case.get("componentKind") == "A2A_ENDPOINT":
        a2a = verify_a2a_failure_trace(case, observed.get("a2aTrace", {}))
    return {
        "criticality": criticality,
        "failureRecord": failure_record,
        "routing": routing,
        "invocation": invocation,
        "a2a": a2a,
    }


def _case_result(
    release_batch_id: str,
    case: Mapping[str, Any],
    run_identity: str,
    started_at: str,
    ended_at: str,
    status: str,
    activation: Mapping[str, Any] | None,
    recovery: Mapping[str, Any] | None,
    preconditions: Mapping[str, Any] | None,
    assertions: Mapping[str, Any] | None,
    error_code: str | None,
    detail: str | None,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "schemaVersion": "1.0.0",
        "releaseBatchId": release_batch_id,
        "caseId": case["caseId"],
        "runPurpose": RunPurpose.FAILURE_INJECTION.value,
        "runIdentity": run_identity,
        "componentId": case["componentId"],
        "componentKind": case["componentKind"],
        "faultType": case["faultType"],
        "criticality": case["criticality"],
        "routeKind": case["routeKind"],
        "routeRef": case["routeRef"],
        "startedAt": started_at,
        "endedAt": ended_at,
        "status": status,
        "activation": activation,
        "recovery": recovery,
        "preconditions": preconditions,
        "assertions": assertions,
    }
    if error_code is not None:
        result["errorCode"] = error_code
        result["detail"] = detail
    return result


def _atomic_json(path: Path, value: Mapping[str, Any]) -> None:
    payload = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode() + b"\n"
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with temporary.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _digest(value: Mapping[str, Any]) -> str:
    return hashlib.sha256(
        json.dumps(
            value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode()
    ).hexdigest()


def _timestamp(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z"
    )


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _matrix_invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_MATRIX_INVALID.value, detail)


def _route_unavailable(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value, detail)
