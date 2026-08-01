from __future__ import annotations

import copy
import hashlib
import json
import re
from pathlib import Path
from typing import Any, Mapping
from uuid import UUID

from ..contracts import ContractError
from .model import ReleaseErrorCode, RunPurpose


_CASE_KINDS = {"KB_EMPTY", "NO_MATCH", "INSUFFICIENT_HISTORY"}
_DIGEST = re.compile(r"^[0-9a-f]{64}$")
_ID = re.compile(r"^[a-z0-9][a-z0-9-]{2,95}$")
_FILTER_KEYS = {"language", "service", "documentType", "tag", "relation"}
_SOURCE_KINDS = {"FILE", "PROMETHEUS", "JAEGER", "HTTP", "DATABASE"}
_CALL_KEYS = {
    "knowledgeAgentA2a",
    "knowledgeSearchTool",
    "catalogCheck",
    "embedding",
    "exactRecall",
    "rerank",
    "historyLookup",
    "fieldEvidenceTools",
}
_FORBIDDEN_GROUND_TRUTH_KEYS = {
    "rootCause",
    "rootCauseCode",
    "forbiddenRootCauseCodes",
    "requiredEvidence",
    "expectedActionCodes",
    "faultComponent",
    "faultService",
}
_OUTCOMES = {"CONCLUSIVE", "PARTIAL", "INCONCLUSIVE"}
_EFFICIENCY_FIELDS = {
    "supervisor_rounds": "supervisorRounds",
    "professional_agent_rounds": "maxProfessionalAgentRounds",
    "professional_a2a_attempts": "a2aAttempts",
    "total_tool_calls": "toolCalls",
    "wall_clock_seconds": "wallClockMillis",
    "total_tokens": None,
}


class EmptyOutcomeManifest:
    """Strict, immutable input contract for the three empty-outcome E2E cases."""

    def __init__(self, document: Mapping[str, Any]):
        if not isinstance(document, Mapping):
            _invalid("document")
        self._document = copy.deepcopy(dict(document))
        self._validate_digest()
        self._validate()

    @classmethod
    def load(cls, path: Path) -> "EmptyOutcomeManifest":
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exception:
            raise ContractError(
                ReleaseErrorCode.EMPTY_OUTCOME_MANIFEST_INVALID.value,
                str(exception),
            ) from exception
        return cls(document)

    @property
    def document(self) -> dict[str, Any]:
        return copy.deepcopy(self._document)

    def case(self, case_kind: str) -> dict[str, Any]:
        for value in self._document["cases"]:
            if value["caseKind"] == case_kind:
                return copy.deepcopy(value)
        _invalid(f"unknown case kind: {case_kind}")

    def _validate_digest(self) -> None:
        actual = self._document.get("manifestDigest")
        unsigned = {
            key: value for key, value in self._document.items() if key != "manifestDigest"
        }
        expected = hashlib.sha256(_canonical_json(unsigned)).hexdigest()
        if not isinstance(actual, str) or actual != expected:
            raise ContractError(
                ReleaseErrorCode.EMPTY_OUTCOME_MANIFEST_DIGEST_INVALID.value,
                "manifestDigest",
            )

    def _validate(self) -> None:
        if set(self._document) != {
            "schemaVersion",
            "suiteId",
            "suiteVersion",
            "runPurpose",
            "snapshotDigest",
            "modelIdentity",
            "cases",
            "manifestDigest",
        }:
            _invalid("manifest fields")
        if (
            self._document["schemaVersion"] != "1.0.0"
            or self._document["suiteId"] != "phase8-empty-outcome"
            or self._document["suiteVersion"] != "1.0.0"
            or self._document["runPurpose"] != RunPurpose.EMPTY_OUTCOME.value
            or not _is_digest(self._document["snapshotDigest"])
        ):
            _invalid("manifest identity")
        self._validate_model_identity(self._document["modelIdentity"])
        _reject_ground_truth(self._document)

        cases = self._document["cases"]
        if not isinstance(cases, list) or len(cases) != 3:
            _invalid("case count")
        if {case.get("caseKind") for case in cases if isinstance(case, Mapping)} != _CASE_KINDS:
            _invalid("case kind coverage")
        case_ids: set[str] = set()
        revisions: set[str] = set()
        for case in cases:
            self._validate_case(case, case_ids, revisions)

    @staticmethod
    def _validate_model_identity(value: Any) -> None:
        if not isinstance(value, Mapping) or set(value) != {
            "embeddingModelId",
            "embeddingRevision",
            "rerankModelId",
            "rerankRevision",
        }:
            _invalid("modelIdentity")
        if not all(isinstance(item, str) and item.strip() for item in value.values()):
            _invalid("modelIdentity values")

    @staticmethod
    def _validate_case(case: Any, case_ids: set[str], revisions: set[str]) -> None:
        if not isinstance(case, Mapping) or set(case) != {
            "caseId",
            "caseKind",
            "knowledge",
            "query",
            "fieldEvidence",
            "expectations",
        }:
            _invalid("case fields")
        case_id = case["caseId"]
        case_kind = case["caseKind"]
        if (
            not isinstance(case_id, str)
            or not _ID.fullmatch(case_id)
            or case_id in case_ids
            or case_kind not in _CASE_KINDS
        ):
            _invalid("case identity")
        case_ids.add(case_id)
        EmptyOutcomeManifest._validate_knowledge(case["knowledge"], revisions)
        EmptyOutcomeManifest._validate_query(case["query"])
        tool_count = EmptyOutcomeManifest._validate_evidence(case["fieldEvidence"])
        EmptyOutcomeManifest._validate_expectations(
            case_kind, case["knowledge"], case["expectations"], tool_count
        )

    @staticmethod
    def _validate_knowledge(value: Any, revisions: set[str]) -> None:
        if not isinstance(value, Mapping) or set(value) != {
            "collectionId",
            "revisionId",
            "revisionKey",
            "searchableChunkCount",
        }:
            _invalid("knowledge")
        try:
            UUID(value["collectionId"])
            UUID(value["revisionId"])
        except (ValueError, TypeError, AttributeError):
            _invalid("knowledge immutable identity")
        if value["revisionId"] in revisions:
            _invalid("knowledge revision reuse")
        revisions.add(value["revisionId"])
        if (
            not isinstance(value["revisionKey"], str)
            or not value["revisionKey"].startswith("phase8-empty-outcome/1.0.0/")
            or not isinstance(value["searchableChunkCount"], int)
            or isinstance(value["searchableChunkCount"], bool)
            or value["searchableChunkCount"] < 0
        ):
            _invalid("knowledge revision")

    @staticmethod
    def _validate_query(value: Any) -> None:
        if not isinstance(value, Mapping) or set(value) != {
            "text",
            "filters",
            "aclPrincipals",
            "candidateK",
            "topK",
        }:
            _invalid("query")
        filters = value["filters"]
        principals = value["aclPrincipals"]
        if (
            not isinstance(value["text"], str)
            or not value["text"].strip()
            or len(value["text"]) > 512
            or not isinstance(filters, Mapping)
            or not set(filters).issubset(_FILTER_KEYS)
            or not all(isinstance(item, str) and item.strip() for item in filters.values())
            or not isinstance(principals, list)
            or not principals
            or not all(isinstance(item, str) and item.strip() for item in principals)
            or value["candidateK"] != 20
            or value["topK"] != 5
        ):
            _invalid("query values")

    @staticmethod
    def _validate_evidence(value: Any) -> int:
        if not isinstance(value, list) or not value:
            _invalid("fieldEvidence")
        codes: set[str] = set()
        tools: set[str] = set()
        for evidence in value:
            if not isinstance(evidence, Mapping) or set(evidence) != {
                "evidenceCode",
                "signalType",
                "sourceId",
                "sourceKind",
                "adapterId",
                "toolName",
                "claim",
            }:
                _invalid("fieldEvidence fields")
            code = evidence["evidenceCode"]
            tool = evidence["toolName"]
            if (
                not isinstance(code, str)
                or not code.strip()
                or code in codes
                or not isinstance(tool, str)
                or not tool.strip()
                or not isinstance(evidence["signalType"], str)
                or not evidence["signalType"].strip()
                or not isinstance(evidence["sourceId"], str)
                or not evidence["sourceId"].strip()
                or evidence["sourceKind"] not in _SOURCE_KINDS
                or not isinstance(evidence["adapterId"], str)
                or not evidence["adapterId"].strip()
                or not isinstance(evidence["claim"], str)
                or not evidence["claim"].strip()
            ):
                _invalid("fieldEvidence values")
            codes.add(code)
            tools.add(tool)
        return len(tools)

    @staticmethod
    def _validate_expectations(
        case_kind: str, knowledge: Mapping[str, Any], value: Any, tool_count: int
    ) -> None:
        if not isinstance(value, Mapping) or set(value) != {
            "knowledgeOutcome",
            "candidateCount",
            "rerankCount",
            "historyResultCount",
            "technicalChainFailures",
            "continueFieldInvestigation",
            "fallbacks",
            "calls",
        }:
            _invalid("expectations")
        calls = value["calls"]
        fallbacks = value["fallbacks"]
        if (
            not isinstance(calls, Mapping)
            or set(calls) != _CALL_KEYS
            or not all(isinstance(item, int) and not isinstance(item, bool) and item >= 0
                       for item in calls.values())
            or not isinstance(fallbacks, Mapping)
            or set(fallbacks) != {"keyword", "fixedCandidate", "otherProvider"}
            or not all(isinstance(item, int) and not isinstance(item, bool) and item == 0
                       for item in fallbacks.values())
            or not _expected_numbers_valid(value)
            or value["technicalChainFailures"] != 0
            or value["continueFieldInvestigation"] is not True
            or calls["knowledgeAgentA2a"] != 1
            or calls["knowledgeSearchTool"] != 1
            or calls["catalogCheck"] != 1
            or calls["fieldEvidenceTools"] != tool_count
        ):
            _invalid("expected calls or fallback")

        expected = {
            "KB_EMPTY": ("KB_EMPTY", 0, 0, None, 0, 0, 0, 0),
            "NO_MATCH": ("NO_MATCH", 0, 0, None, 1, 1, 0, 0),
            "INSUFFICIENT_HISTORY": ("MATCH", 3, 3, 0, 1, 1, 1, 1),
        }[case_kind]
        actual = (
            value["knowledgeOutcome"],
            value["candidateCount"],
            value["rerankCount"],
            value["historyResultCount"],
            calls["embedding"],
            calls["exactRecall"],
            calls["rerank"],
            calls["historyLookup"],
        )
        if actual != expected:
            _invalid(f"{case_kind} expectations")
        if (case_kind == "KB_EMPTY") != (knowledge["searchableChunkCount"] == 0):
            _invalid(f"{case_kind} searchableChunkCount")
        if case_kind != "KB_EMPTY" and knowledge["searchableChunkCount"] < 1:
            _invalid(f"{case_kind} searchableChunkCount")


def verify_empty_outcome_run_result(
    case_kind: str,
    rca: Mapping[str, Any],
    evaluation: Mapping[str, Any],
    profile: Mapping[str, Any],
) -> dict[str, Any]:
    """Verify one EMPTY_OUTCOME Run against its frozen profile and bounded RCA contract."""
    if case_kind not in _CASE_KINDS:
        _result_invalid("case kind")
    if not all(isinstance(value, Mapping) for value in (rca, evaluation, profile)):
        _result_invalid("documents")
    try:
        limits = profile["efficiency_limits"]
        profile_id = profile["profile_id"]
        report = evaluation["report"]
        efficiency = report["investigationEfficiency"]
        observed = efficiency["observed"]
        recorded_limits = efficiency["withinLimits"]
    except KeyError as exception:
        _result_invalid(f"missing field: {exception.args[0]}")

    if (
        not isinstance(limits, Mapping)
        or set(limits) != set(_EFFICIENCY_FIELDS)
        or not isinstance(profile_id, str)
        or evaluation.get("profileId") != profile_id
        or evaluation.get("status") != "COMPLETED"
        or not isinstance(report, Mapping)
        or not isinstance(efficiency, Mapping)
        or not isinstance(observed, Mapping)
        or not isinstance(recorded_limits, Mapping)
        or set(recorded_limits) != set(_EFFICIENCY_FIELDS)
    ):
        _result_invalid("profile or evaluation contract")

    actual: dict[str, int] = {}
    for limit_name, observed_name in _EFFICIENCY_FIELDS.items():
        limit = limits[limit_name]
        if not _non_negative_int(limit) or limit == 0:
            _result_invalid(f"limit: {limit_name}")
        if limit_name == "total_tokens":
            input_tokens = observed.get("inputTokens")
            output_tokens = observed.get("outputTokens")
            if not _non_negative_int(input_tokens) or not _non_negative_int(output_tokens):
                _result_invalid("observed tokens")
            value = input_tokens + output_tokens
        else:
            value = observed.get(observed_name)
            if not _non_negative_int(value):
                _result_invalid(f"observed: {observed_name}")
        actual[limit_name] = value
        within = value <= (limit * 1000 if limit_name == "wall_clock_seconds" else limit)
        if recorded_limits[limit_name] is not within or not within:
            _result_invalid(f"budget: {limit_name}")

    hard_gate_failures = report.get("hardGateFailures")
    completion = report.get("taskCompletionRate")
    if (
        report.get("valid") is not True
        or hard_gate_failures != []
        or not isinstance(completion, Mapping)
        or completion.get("value") != 1
    ):
        _result_invalid("terminal evaluation state")

    run_id = rca.get("runId")
    outcome = rca.get("outcome")
    assessment = rca.get("evidenceAssessment")
    limitations = rca.get("limitations")
    if (
        not isinstance(run_id, str)
        or not run_id
        or report.get("runId") != run_id
        or outcome not in _OUTCOMES
        or not isinstance(assessment, Mapping)
        or not _non_empty_strings(assessment.get("missingEvidenceCodes"))
        or not _non_empty_strings(limitations)
    ):
        _result_invalid("RCA outcome, limitations, or missingEvidence")
    if (outcome == "INCONCLUSIVE") != (rca.get("rootCause") is None):
        _result_invalid("RCA outcome and rootCause")

    return {
        "caseKind": case_kind,
        "runId": run_id,
        "status": "COMPLETED",
        "outcome": outcome,
        "budgets": {
            name: {
                "actual": actual[name],
                "limit": limits[name] * 1000 if name == "wall_clock_seconds" else limits[name],
                "unit": "milliseconds" if name == "wall_clock_seconds" else "count",
                "passed": True,
            }
            for name in _EFFICIENCY_FIELDS
        },
        "missingEvidence": list(assessment["missingEvidenceCodes"]),
        "limitations": list(limitations),
    }


def _reject_ground_truth(value: Any) -> None:
    if isinstance(value, Mapping):
        forbidden = set(value).intersection(_FORBIDDEN_GROUND_TRUTH_KEYS)
        if forbidden:
            _invalid(f"Ground Truth fields: {sorted(forbidden)}")
        for nested in value.values():
            _reject_ground_truth(nested)
    elif isinstance(value, list):
        for nested in value:
            _reject_ground_truth(nested)


def _expected_numbers_valid(value: Mapping[str, Any]) -> bool:
    for field in ("candidateCount", "rerankCount", "technicalChainFailures"):
        item = value[field]
        if not isinstance(item, int) or isinstance(item, bool) or item < 0:
            return False
    history = value["historyResultCount"]
    return history is None or (
        isinstance(history, int) and not isinstance(history, bool) and history >= 0
    )


def _non_negative_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def _non_empty_strings(value: Any) -> bool:
    return (
        isinstance(value, list)
        and bool(value)
        and all(isinstance(item, str) and item.strip() for item in value)
        and len(value) == len(set(value))
    )


def _is_digest(value: Any) -> bool:
    return isinstance(value, str) and _DIGEST.fullmatch(value) is not None


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_MANIFEST_INVALID.value, detail)


def _result_invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, detail)
