from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.empty_outcome import (
    EmptyOutcomeManifest,
    verify_empty_outcome_run_result,
)
from fault_lab.release.model import ReleaseErrorCode, RunPurpose


MANIFEST = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "phase8"
    / "empty-outcome-manifest-v1.json"
)


def test_loads_three_versioned_empty_outcome_cases() -> None:
    manifest = EmptyOutcomeManifest.load(MANIFEST)
    document = manifest.document

    assert document["schemaVersion"] == "1.0.0"
    assert document["suiteVersion"] == "1.0.0"
    assert document["runPurpose"] == RunPurpose.EMPTY_OUTCOME.value
    assert {case["caseKind"] for case in document["cases"]} == {
        "KB_EMPTY",
        "NO_MATCH",
        "INSUFFICIENT_HISTORY",
    }
    assert len({case["knowledge"]["revisionId"] for case in document["cases"]}) == 3
    assert all(case["query"]["text"] for case in document["cases"])
    assert all(case["fieldEvidence"] for case in document["cases"])

    kb_empty = manifest.case("KB_EMPTY")
    assert kb_empty["expectations"]["knowledgeOutcome"] == "KB_EMPTY"
    assert kb_empty["expectations"]["calls"]["rerank"] == 0
    no_match = manifest.case("NO_MATCH")
    assert no_match["knowledge"]["searchableChunkCount"] > 0
    assert no_match["expectations"]["candidateCount"] == 0
    assert no_match["expectations"]["calls"]["rerank"] == 0
    no_history = manifest.case("INSUFFICIENT_HISTORY")
    assert no_history["expectations"]["historyResultCount"] == 0
    assert no_history["expectations"]["calls"]["historyLookup"] == 1


@pytest.mark.parametrize(
    "change",
    [
        lambda value: value["cases"].pop(),
        lambda value: value["cases"][1].update(caseKind="KB_EMPTY"),
        lambda value: value["cases"][0]["knowledge"].update(revisionId="mutable"),
        lambda value: value["cases"][1]["expectations"]["calls"].update(rerank=1),
        lambda value: value["cases"][2].update(rootCauseCode="forbidden.answer"),
    ],
    ids=("missing-case", "duplicate-kind", "mutable-revision", "unexpected-rerank", "ground-truth"),
)
def test_rejects_incomplete_drifting_or_ground_truth_fixture(change) -> None:
    document = EmptyOutcomeManifest.load(MANIFEST).document
    change(document)
    document["manifestDigest"] = _digest(document)

    with pytest.raises(ContractError) as exc_info:
        EmptyOutcomeManifest(document)

    assert exc_info.value.code == ReleaseErrorCode.EMPTY_OUTCOME_MANIFEST_INVALID.value


def test_rejects_manifest_digest_mismatch() -> None:
    document = copy.deepcopy(EmptyOutcomeManifest.load(MANIFEST).document)
    document["suiteVersion"] = "1.0.1"

    with pytest.raises(ContractError) as exc_info:
        EmptyOutcomeManifest(document)

    assert exc_info.value.code == ReleaseErrorCode.EMPTY_OUTCOME_MANIFEST_DIGEST_INVALID.value


@pytest.mark.parametrize(
    ("case_kind", "outcome"),
    [
        ("KB_EMPTY", "INCONCLUSIVE"),
        ("NO_MATCH", "PARTIAL"),
        ("INSUFFICIENT_HISTORY", "CONCLUSIVE"),
    ],
)
def test_verifies_each_empty_outcome_run_at_frozen_budget_boundaries(
    case_kind: str, outcome: str
) -> None:
    rca, evaluation, profile = _run_documents(outcome)

    result = verify_empty_outcome_run_result(case_kind, rca, evaluation, profile)

    assert result["status"] == "COMPLETED"
    assert result["outcome"] == outcome
    assert result["budgets"]["wall_clock_seconds"] == {
        "actual": 600_000,
        "limit": 600_000,
        "unit": "milliseconds",
        "passed": True,
    }
    assert all(value["passed"] for value in result["budgets"].values())


@pytest.mark.parametrize(
    ("observed_field", "over_limit"),
    [
        ("supervisorRounds", 13),
        ("maxProfessionalAgentRounds", 9),
        ("a2aAttempts", 11),
        ("toolCalls", 31),
        ("wallClockMillis", 600_001),
        ("outputTokens", 5_537),
    ],
)
def test_rejects_any_empty_outcome_budget_overrun_even_if_recorded_as_within_limit(
    observed_field: str, over_limit: int
) -> None:
    rca, evaluation, profile = _run_documents("PARTIAL")
    evaluation["report"]["investigationEfficiency"]["observed"][observed_field] = over_limit

    with pytest.raises(ContractError) as exc_info:
        verify_empty_outcome_run_result("NO_MATCH", rca, evaluation, profile)

    assert exc_info.value.code == ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value


@pytest.mark.parametrize(
    ("outcome", "missing", "limitations", "root_cause"),
    [
        ("INCONCLUSIVE", ["trace.inventory.client"], [], None),
        ("PARTIAL", [], ["Knowledge returned NO_MATCH"], {"rootCauseCode": "dependency.latency"}),
        ("INCONCLUSIVE", ["trace.inventory.client"], ["Trace is missing"], {"rootCauseCode": "x.y"}),
        ("PARTIAL", ["trace.inventory.client"], ["Trace is missing"], None),
    ],
)
def test_rejects_inconsistent_outcome_limitations_and_missing_evidence(
    outcome: str, missing: list[str], limitations: list[str], root_cause: object
) -> None:
    rca, evaluation, profile = _run_documents(outcome)
    rca["rootCause"] = root_cause
    rca["evidenceAssessment"]["missingEvidenceCodes"] = missing
    rca["limitations"] = limitations

    with pytest.raises(ContractError) as exc_info:
        verify_empty_outcome_run_result("KB_EMPTY", rca, evaluation, profile)

    assert exc_info.value.code == ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value


def _digest(document: dict[str, object]) -> str:
    unsigned = {key: value for key, value in document.items() if key != "manifestDigest"}
    encoded = json.dumps(
        unsigned, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _run_documents(outcome: str) -> tuple[dict, dict, dict]:
    run_id = "8a7826b4-9de5-4c36-a71e-96dd3db3af65"
    limits = {
        "supervisor_rounds": 12,
        "professional_agent_rounds": 8,
        "total_tool_calls": 30,
        "professional_a2a_attempts": 10,
        "wall_clock_seconds": 600,
        "total_tokens": 65_536,
    }
    profile = {"profile_id": "mvp-v2", "efficiency_limits": limits}
    rca = {
        "runId": run_id,
        "outcome": outcome,
        "rootCause": None if outcome == "INCONCLUSIVE" else {"rootCauseCode": "dependency.latency"},
        "evidenceAssessment": {
            "missingEvidenceCodes": ["trace.inventory.client"],
            "unavailableCapabilities": [],
        },
        "limitations": ["Knowledge was empty and the client trace was unavailable"],
    }
    evaluation = {
        "profileId": "mvp-v2",
        "status": "COMPLETED",
        "report": {
            "runId": run_id,
            "valid": True,
            "hardGateFailures": [],
            "taskCompletionRate": {"value": 1.0},
            "investigationEfficiency": {
                "observed": {
                    "supervisorRounds": 12,
                    "maxProfessionalAgentRounds": 8,
                    "a2aAttempts": 10,
                    "toolCalls": 30,
                    "inputTokens": 60_000,
                    "outputTokens": 5_536,
                    "wallClockMillis": 600_000,
                },
                "withinLimits": {name: True for name in limits},
            },
        },
    }
    return rca, evaluation, profile
