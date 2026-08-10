from __future__ import annotations

import hashlib
import json
import re
import subprocess
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose


_DIGEST = re.compile(r"^[0-9a-f]{64}$")


class UnavailableFailurePreconditioner:
    def __init__(self, detail: str):
        self.detail = detail

    def __call__(self, case: Mapping[str, Any], run: Mapping[str, Any]) -> Mapping[str, Any]:
        raise ContractError(
            ReleaseErrorCode.FAILURE_PRECONDITION_UNAVAILABLE.value,
            self.detail,
        )


class SubprocessFailurePreconditioner:
    """Runs the pre-activation probe with fixed argv and structured JSON I/O."""

    def __init__(
        self,
        command: tuple[str, ...],
        *,
        command_runner: Callable[..., Any] = subprocess.run,
    ):
        if not command:
            _invalid("precondition command")
        self.command = command
        self.command_runner = command_runner

    def __call__(self, case: Mapping[str, Any], run: Mapping[str, Any]) -> Mapping[str, Any]:
        try:
            completed = self.command_runner(
                list(self.command),
                input=json.dumps(
                    {"case": case, "run": run},
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                capture_output=True,
                text=True,
                check=False,
                timeout=120,
            )
        except (OSError, subprocess.TimeoutExpired) as error:
            raise ContractError(
                ReleaseErrorCode.FAILURE_PRECONDITION_UNAVAILABLE.value,
                type(error).__name__,
            ) from error
        if completed.returncode != 0:
            raise ContractError(
                ReleaseErrorCode.FAILURE_PRECONDITION_UNAVAILABLE.value,
                f"exit={completed.returncode}",
            )
        try:
            report = json.loads(completed.stdout)
        except json.JSONDecodeError as error:
            raise ContractError(
                ReleaseErrorCode.FAILURE_PRECONDITION_UNAVAILABLE.value,
                "invalid precondition response",
            ) from error
        return verify_precondition_report(case, run, report)


def verify_precondition_report(
    case: Mapping[str, Any],
    run: Mapping[str, Any],
    report: Mapping[str, Any],
) -> dict[str, Any]:
    checks = report.get("checks") if isinstance(report, Mapping) else None
    expected = case.get("preconditions")
    if (
        not isinstance(expected, list)
        or not expected
        or not isinstance(checks, Mapping)
        or set(checks) != set(expected)
        or report.get("caseId") != case.get("caseId")
        or report.get("triggerRef") != case.get("triggerRef")
        or report.get("runIdentity") != run.get("runIdentity")
        or report.get("runPurpose") != RunPurpose.FAILURE_INJECTION.value
        or not _timestamp(report.get("checkedAt"))
    ):
        _precondition("precondition report identity")
    failed = sorted(name for name, passed in checks.items() if passed is not True)
    if failed:
        _precondition("failed checks: " + ",".join(failed))
    artifact = _artifact(report.get("evidenceArtifact"))
    return {
        "status": ReleaseStatus.PASSED.value,
        "caseId": case["caseId"],
        "triggerRef": case["triggerRef"],
        "runIdentity": run["runIdentity"],
        "runPurpose": RunPurpose.FAILURE_INJECTION.value,
        "checkedAt": report["checkedAt"],
        "checks": dict(checks),
        "evidenceArtifact": artifact,
    }


def verify_target_invocation(
    case: Mapping[str, Any],
    run: Mapping[str, Any],
    activation: Mapping[str, Any],
    evidence: Mapping[str, Any],
) -> dict[str, Any]:
    invocations = evidence.get("invocations") if isinstance(evidence, Mapping) else None
    product_run = evidence.get("productRun") if isinstance(evidence, Mapping) else None
    activated_at = activation.get("activatedAt")
    if (
        not isinstance(invocations, list)
        or not isinstance(product_run, Mapping)
        or evidence.get("runIdentity") != run.get("runIdentity")
        or evidence.get("runPurpose") != RunPurpose.FAILURE_INJECTION.value
        or activation.get("activationId") != evidence.get("activationId")
        or not _timestamp(activated_at)
        or not _text(product_run.get("incidentId"))
        or not _text(product_run.get("runId"))
        or product_run.get("status") not in {"COMPLETED", "FAILED"}
    ):
        _invalid("invocation evidence identity")
    expected = case.get("expectedInvocation")
    selectors = case.get("evidenceSelectors")
    if not isinstance(expected, Mapping) or not isinstance(selectors, list):
        _invalid("frozen invocation contract")
    normalized = []
    for invocation in invocations:
        if not isinstance(invocation, Mapping):
            _invalid("invocation entry")
        required = {
            "componentId",
            "routeRef",
            "evidenceSelector",
            "startedAt",
            "endedAt",
            "requestId",
            "traceId",
            "runId",
            "invocationId",
            "hiddenFallback",
        }
        if not required.issubset(invocation):
            _invalid("invocation fields")
        if (
            invocation["evidenceSelector"] not in selectors
            or not all(
                _text(invocation.get(field))
                for field in required
                - {"hiddenFallback"}
            )
            or invocation["runId"] != product_run["runId"]
            or invocation["hiddenFallback"] not in {True, False}
            or not _within_window(
                str(activated_at),
                str(invocation["startedAt"]),
                str(invocation["endedAt"]),
            )
        ):
            _invalid("invocation values")
        normalized.append(dict(invocation))
    component_matches = [
        item for item in normalized if item["componentId"] == expected.get("componentId")
    ]
    if not component_matches:
        raise ContractError(
            ReleaseErrorCode.FAILURE_INVOCATION_NOT_OBSERVED.value,
            str(expected.get("componentId")),
        )
    route_matches = [
        item for item in component_matches if item["routeRef"] == expected.get("routeRef")
    ]
    if not route_matches:
        raise ContractError(
            ReleaseErrorCode.FAILURE_INVOCATION_ROUTE_MISMATCH.value,
            str(expected.get("routeRef")),
        )
    if any(item["hiddenFallback"] is True for item in normalized):
        raise ContractError(
            ReleaseErrorCode.FAILURE_HIDDEN_FALLBACK.value,
            str(case.get("caseId")),
        )
    artifact = _artifact(evidence.get("evidenceArtifact"))
    return {
        "status": ReleaseStatus.PASSED.value,
        "caseId": case["caseId"],
        "componentId": expected["componentId"],
        "routeRef": expected["routeRef"],
        "activationId": activation["activationId"],
        "productRun": dict(product_run),
        "matches": route_matches,
        "evidenceArtifact": artifact,
    }


def _artifact(value: Any) -> dict[str, Any]:
    if (
        not isinstance(value, Mapping)
        or not _text(value.get("uri"))
        or not isinstance(value.get("size"), int)
        or isinstance(value.get("size"), bool)
        or value["size"] < 1
        or not isinstance(value.get("sha256"), str)
        or not _DIGEST.fullmatch(value["sha256"])
    ):
        _invalid("evidence artifact")
    return dict(value)


def seal_report(path: Path, report: Mapping[str, Any]) -> dict[str, Any]:
    payload = json.dumps(
        report, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode() + b"\n"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return {
        "uri": path.as_posix(),
        "size": len(payload),
        "sha256": hashlib.sha256(payload).hexdigest(),
    }


def _within_window(activated: str, started: str, ended: str) -> bool:
    values = [_parse_time(value) for value in (activated, started, ended)]
    return all(value is not None for value in values) and values[0] <= values[1] <= values[2]


def _timestamp(value: Any) -> bool:
    return isinstance(value, str) and _parse_time(value) is not None


def _parse_time(value: str) -> datetime | None:
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, ValueError):
        return None


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _precondition(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_PRECONDITION_UNAVAILABLE.value, detail)


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_INVOCATION_EVIDENCE_INVALID.value, detail)
