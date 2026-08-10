from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Protocol

from ..contracts import ContractError
from .failure_plan import FailurePlan
from .model import ReleaseErrorCode, ReleaseStatus


PROTOCOL_VERSION = "1.0.0"
OPERATIONS = {"health", "activate", "execute", "recover"}
_REQUEST_FIELDS = {
    "protocolVersion",
    "caseId",
    "routeRef",
    "faultType",
    "runIdentity",
    "triggerRef",
    "leaseToken",
}
_TOKEN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")


class FailureRouteDataPlane(Protocol):
    def health(self, case: Mapping[str, Any]) -> Mapping[str, Any]: ...

    def activate(self, case: Mapping[str, Any]) -> Mapping[str, Any]: ...

    def recover(
        self, case: Mapping[str, Any], activation: Mapping[str, Any]
    ) -> Mapping[str, Any]: ...


class FailureDriverProtocol:
    """Versioned, lease-serialized fault driver independent of transport process."""

    def __init__(
        self,
        plan: FailurePlan,
        data_planes: Mapping[str, FailureRouteDataPlane],
        state_path: Path,
        evidence_root: Path,
        executor: Callable[[Mapping[str, Any], Mapping[str, Any], Mapping[str, Any]], Mapping[str, Any]],
    ):
        if set(data_planes) != {"NETWORK_PROXY", "FILE_FIXTURE", "PROCESS_FIXTURE"}:
            _invalid("data plane coverage")
        self.cases = {case["caseId"]: case for case in plan.cases}
        self.data_planes = dict(data_planes)
        self.state_path = state_path
        self.evidence_root = evidence_root
        self.executor = executor

    def handle(self, operation: str, request: Mapping[str, Any]) -> dict[str, Any]:
        if operation not in OPERATIONS:
            _invalid("operation")
        case = self._validate_request(request)
        if operation == "health":
            response = self._health(case, request)
        elif operation == "activate":
            response = self._activate(case, request)
        elif operation == "execute":
            response = self._execute(case, request)
        else:
            response = self._recover(case, request)
        response["evidenceArtifact"] = self._seal(operation, request, response)
        return response

    def _health(self, case: Mapping[str, Any], request: Mapping[str, Any]) -> dict[str, Any]:
        health = dict(self._plane(case).health(case))
        healthy = health.get("healthy") is True and health.get("residualFaults") == []
        return self._response(
            "health",
            request,
            ReleaseStatus.PASSED if healthy else ReleaseStatus.BLOCKED,
            "RELEASE_FAILURE_ROUTE_HEALTHY" if healthy else ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
            healthy=healthy,
            residualFaults=health.get("residualFaults", []),
            routeSnapshot=health.get("routeSnapshot"),
        )

    def _activate(self, case: Mapping[str, Any], request: Mapping[str, Any]) -> dict[str, Any]:
        lease = self._reserve_lease(case, request)
        plane = self._plane(case)
        activation: Mapping[str, Any] | None = None
        try:
            health = plane.health(case)
            if health.get("healthy") is not True or health.get("residualFaults") != []:
                raise ContractError(
                    ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
                    str(case["routeRef"]),
                )
            activation = plane.activate(case)
            if activation.get("changedRoutes") != [case["routeRef"]]:
                raise ContractError(
                    ReleaseErrorCode.FAILURE_DATA_PLANE_INVALID.value,
                    "non-target route changed",
                )
            lease["activation"] = dict(activation)
            lease["state"] = "ACTIVE"
            _atomic_json(self.state_path, lease, replace=True)
        except Exception:
            if activation is not None:
                try:
                    plane.recover(case, activation)
                except Exception:
                    pass
            self.state_path.unlink(missing_ok=True)
            raise
        return self._response(
            "activate",
            request,
            ReleaseStatus.PASSED,
            "RELEASE_FAILURE_ROUTE_ACTIVATED",
            healthy=False,
            routeSnapshotBefore=activation.get("before"),
            routeSnapshotAfter=activation.get("after"),
            changedRoutes=activation["changedRoutes"],
            recoveryCredential=activation.get("recoveryCredential"),
            activationId=activation.get("activationId"),
            activatedAt=_timestamp(),
        )

    def _execute(self, case: Mapping[str, Any], request: Mapping[str, Any]) -> dict[str, Any]:
        lease = self._active_lease(case, request)
        try:
            observation = self.executor(case, request, lease["activation"])
        except ContractError:
            raise
        except Exception as exception:
            raise ContractError(
                ReleaseErrorCode.FAILURE_EXECUTION_FAILED.value,
                type(exception).__name__,
            ) from exception
        if not isinstance(observation, Mapping):
            raise ContractError(
                ReleaseErrorCode.FAILURE_EXECUTION_FAILED.value,
                "execution result",
            )
        return self._response(
            "execute",
            request,
            ReleaseStatus.PASSED,
            "RELEASE_FAILURE_CASE_EXECUTED",
            healthy=False,
            observation=dict(observation),
        )

    def _recover(self, case: Mapping[str, Any], request: Mapping[str, Any]) -> dict[str, Any]:
        if not self.state_path.exists():
            health = dict(self._plane(case).health(case))
            if health.get("healthy") is not True or health.get("residualFaults") != []:
                raise ContractError(
                    ReleaseErrorCode.FAILURE_RECOVERY_FAILED.value,
                    str(case["routeRef"]),
                )
            return self._response(
                "recover",
                request,
                ReleaseStatus.PASSED,
                "RELEASE_FAILURE_ROUTE_ALREADY_RECOVERED",
                healthy=True,
                residualFaults=[],
                alreadyRecovered=True,
                routeSnapshot=health.get("routeSnapshot"),
            )
        lease = self._active_lease(case, request)
        recovery = dict(self._plane(case).recover(case, lease["activation"]))
        if recovery.get("healthy") is not True or recovery.get("residualFaults") != []:
            raise ContractError(
                ReleaseErrorCode.FAILURE_RECOVERY_FAILED.value,
                str(case["routeRef"]),
            )
        self.state_path.unlink()
        return self._response(
            "recover",
            request,
            ReleaseStatus.PASSED,
            "RELEASE_FAILURE_ROUTE_RECOVERED",
            healthy=True,
            residualFaults=[],
            alreadyRecovered=False,
            restoredRouteRef=case["routeRef"],
            routeSnapshot=recovery.get("routeSnapshot"),
        )

    def _validate_request(self, request: Mapping[str, Any]) -> Mapping[str, Any]:
        if not isinstance(request, Mapping) or set(request) != _REQUEST_FIELDS:
            _invalid("request fields")
        if request.get("protocolVersion") != PROTOCOL_VERSION:
            _invalid("protocolVersion")
        if not all(_text(request.get(field)) for field in _REQUEST_FIELDS - {"leaseToken"}):
            _invalid("request values")
        if not isinstance(request.get("leaseToken"), str) or not _TOKEN.fullmatch(request["leaseToken"]):
            _invalid("leaseToken")
        case = self.cases.get(str(request["caseId"]))
        if case is None:
            _invalid("caseId")
        expected = {
            "routeRef": case["routeRef"],
            "faultType": case["faultType"],
            "triggerRef": case["triggerRef"],
        }
        if any(request[field] != value for field, value in expected.items()):
            _invalid("case identity")
        return case

    def _reserve_lease(
        self, case: Mapping[str, Any], request: Mapping[str, Any]
    ) -> dict[str, Any]:
        lease = {
            "protocolVersion": PROTOCOL_VERSION,
            "state": "ACTIVATING",
            "caseId": case["caseId"],
            "routeRef": case["routeRef"],
            "leaseToken": request["leaseToken"],
            "runIdentity": request["runIdentity"],
        }
        try:
            _atomic_json(self.state_path, lease, replace=False)
        except FileExistsError as exception:
            raise ContractError(
                ReleaseErrorCode.FAILURE_LEASE_CONFLICT.value,
                "another case is active",
            ) from exception
        return lease

    def _active_lease(
        self, case: Mapping[str, Any], request: Mapping[str, Any]
    ) -> dict[str, Any]:
        try:
            lease = json.loads(self.state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            raise ContractError(
                ReleaseErrorCode.FAILURE_LEASE_INVALID.value,
                "active lease unavailable",
            ) from exception
        if (
            lease.get("state") != "ACTIVE"
            or lease.get("caseId") != case["caseId"]
            or lease.get("routeRef") != case["routeRef"]
            or lease.get("leaseToken") != request["leaseToken"]
            or lease.get("runIdentity") != request["runIdentity"]
            or not isinstance(lease.get("activation"), Mapping)
        ):
            raise ContractError(
                ReleaseErrorCode.FAILURE_LEASE_INVALID.value,
                "active lease mismatch",
            )
        return lease

    def _plane(self, case: Mapping[str, Any]) -> FailureRouteDataPlane:
        return self.data_planes[case["routeKind"]]

    @staticmethod
    def _response(
        operation: str,
        request: Mapping[str, Any],
        status: ReleaseStatus,
        code: str,
        **fields: Any,
    ) -> dict[str, Any]:
        return {
            "protocolVersion": PROTOCOL_VERSION,
            "operation": operation,
            "caseId": request["caseId"],
            "routeRef": request["routeRef"],
            "faultType": request["faultType"],
            "runIdentity": request["runIdentity"],
            "triggerRef": request["triggerRef"],
            "status": status.value,
            "code": code,
            **fields,
        }

    def _seal(
        self,
        operation: str,
        request: Mapping[str, Any],
        response: Mapping[str, Any],
    ) -> dict[str, Any]:
        safe_run = re.sub(r"[^A-Za-z0-9._-]+", "-", str(request["runIdentity"]))[:96]
        path = self.evidence_root / str(request["caseId"]) / f"{operation}-{safe_run}-{uuid.uuid4().hex}.json"
        payload = _canonical_json(response) + b"\n"
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        return {
            "uri": path.as_posix(),
            "size": len(payload),
            "sha256": hashlib.sha256(payload).hexdigest(),
        }


class SubprocessCaseExecutor:
    """Executes the product case adapter with fixed argv and structured JSON I/O."""

    def __init__(
        self,
        command: tuple[str, ...],
        *,
        command_runner: Callable[..., Any] = subprocess.run,
    ):
        self.command = command
        self.command_runner = command_runner

    def __call__(
        self,
        case: Mapping[str, Any],
        request: Mapping[str, Any],
        activation: Mapping[str, Any],
    ) -> Mapping[str, Any]:
        if not self.command:
            raise ContractError(
                ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
                "failure case executor is not configured",
            )
        try:
            completed = self.command_runner(
                list(self.command),
                input=json.dumps(
                    {"case": case, "request": request, "activation": activation},
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                capture_output=True,
                text=True,
                check=False,
                timeout=900,
            )
        except (OSError, subprocess.TimeoutExpired) as exception:
            raise ContractError(
                ReleaseErrorCode.FAILURE_EXECUTION_FAILED.value,
                type(exception).__name__,
            ) from exception
        if completed.returncode != 0:
            raise ContractError(
                ReleaseErrorCode.FAILURE_EXECUTION_FAILED.value,
                f"exit={completed.returncode}",
            )
        try:
            value = json.loads(completed.stdout)
        except json.JSONDecodeError as exception:
            raise ContractError(
                ReleaseErrorCode.FAILURE_EXECUTION_FAILED.value,
                "invalid executor response",
            ) from exception
        if not isinstance(value, Mapping):
            raise ContractError(
                ReleaseErrorCode.FAILURE_EXECUTION_FAILED.value,
                "invalid executor response",
            )
        return value


def _atomic_json(path: Path, value: Mapping[str, Any], *, replace: bool) -> None:
    payload = _canonical_json(value) + b"\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    if not replace:
        with path.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        return
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("xb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_DRIVER_PROTOCOL_INVALID.value, detail)


def _timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z"
    )
