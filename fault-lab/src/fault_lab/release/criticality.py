from __future__ import annotations

from typing import Any, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode, RunPurpose


class CriticalityAssertionEngine:
    """Evaluate one technical failure without inventing a second product state machine."""

    def evaluate(self, case: Mapping[str, Any], observed: Mapping[str, Any]) -> dict[str, Any]:
        if case.get("runPurpose") != RunPurpose.FAILURE_INJECTION.value:
            _invalid("case runPurpose")
        attempts = observed.get("attempts")
        deadline = observed.get("parentDeadlineMillis")
        elapsed = observed.get("elapsedMillis")
        if (
            not isinstance(attempts, list)
            or not attempts
            or len(attempts) > 2
            or attempts != list(range(1, len(attempts) + 1))
            or not _positive_int(deadline)
            or not _non_negative_int(elapsed)
            or elapsed > deadline
        ):
            _invalid("attempt or parent deadline")

        criticality = case.get("criticality")
        technical_failure = observed.get("technicalFailure") is True
        terminal = observed.get("terminalState")
        empty_outcome = observed.get("knowledgeOutcome") in {"KB_EMPTY", "NO_MATCH"}

        if criticality == "MANDATORY":
            self._require_failed(technical_failure, terminal)
        elif criticality == "MANDATORY_WHEN_CANDIDATES_EXIST":
            if observed.get("candidatesExist") is True:
                self._require_failed(technical_failure, terminal)
            elif technical_failure or not empty_outcome or terminal != "COMPLETED":
                _invalid("candidate-free business outcome")
        elif criticality == "CONDITIONAL":
            if case.get("expectedTerminalState") == "FAILED":
                self._require_failed(technical_failure, terminal)
            elif (
                not technical_failure
                or terminal != "COMPLETED_LIMITED"
                or observed.get("continuationAllowed") is not True
                or observed.get("independentSourceSucceeded") is not True
                or not _strings(observed.get("chainFailures"))
                or not _strings(observed.get("missingEvidence"))
                or not _strings(observed.get("limitations"))
            ):
                _invalid("conditional continuation")
        elif criticality == "OPTIONAL_APPROVED":
            approval = observed.get("approvalState")
            invoked = observed.get("invocationStarted") is True
            if approval == "APPROVED":
                if not invoked:
                    _invalid("approved optional capability was not invoked")
                self._require_failed(technical_failure, terminal)
            elif approval in {"NOT_REQUESTED", "DENIED", "TIMED_OUT"}:
                if invoked or technical_failure or terminal != "COMPLETED_LIMITED":
                    _invalid("unapproved optional capability executed")
            else:
                _invalid("approvalState")
        else:
            _invalid("criticality")

        return {
            "status": "PASSED",
            "caseId": case["caseId"],
            "criticality": criticality,
            "terminalState": terminal,
            "attemptCount": len(attempts),
            "withinParentDeadline": True,
        }

    @staticmethod
    def _require_failed(technical_failure: bool, terminal: Any) -> None:
        if not technical_failure or terminal != "FAILED":
            _invalid("critical technical failure did not fail the Incident")


def _positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _non_negative_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _strings(value: Any) -> bool:
    return isinstance(value, list) and bool(value) and all(isinstance(item, str) and item.strip() for item in value)


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_ASSERTION_INVALID.value, detail)
