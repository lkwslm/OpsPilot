from __future__ import annotations

import copy
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable, Mapping

from ..contracts import ContractError
from ..product import ProductInvestigationClient
from .empty_outcome import EmptyOutcomeManifest, verify_empty_outcome_run_result
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose


Transport = Callable[[str, str, dict[str, str], bytes | None], tuple[int, bytes]]


def _transport(
    method: str, url: str, headers: dict[str, str], body: bytes | None
) -> tuple[int, bytes]:
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except urllib.error.URLError as error:
        raise ContractError("KNOWLEDGE_CONTROL_UNAVAILABLE", str(error.reason)) from error


class KnowledgeControlPlaneClient:
    """Narrow Fault Lab adapter for the product Knowledge revision lifecycle."""

    def __init__(
        self,
        base_url: str,
        token_file: Path,
        *,
        transport: Transport = _transport,
        now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ):
        parsed = urllib.parse.urlparse(base_url)
        if (
            parsed.scheme != "http"
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
            or not token_file.is_file()
        ):
            raise ContractError("KNOWLEDGE_CONTROL_CONFIG_INVALID", base_url)
        token = token_file.read_text(encoding="utf-8").strip()
        if not token:
            raise ContractError("KNOWLEDGE_CONTROL_CONFIG_INVALID", "token")
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.transport = transport
        self.now = now

    def active(self, collection_id: str) -> str:
        response = self._post("active", {"collectionId": collection_id})
        active = response.get("activeRevisionId")
        if not isinstance(active, str) or not active:
            raise ContractError("KNOWLEDGE_CONTROL_ACTIVE_INVALID", collection_id)
        return active

    def prepare(
        self, release_batch_id: str, case: Mapping[str, Any], manifest_digest: str
    ) -> dict[str, Any]:
        knowledge = case["knowledge"]
        return self._post(
            "prepare",
            {
                "operationId": f"{release_batch_id}:{case['caseId']}:prepare",
                "collectionId": knowledge["collectionId"],
                "revisionId": knowledge["revisionId"],
                "revisionKey": knowledge["revisionKey"],
                "manifestSha256": manifest_digest,
                "expiresAt": self._expires_at(600),
                "chunks": knowledge["chunks"],
            },
        )

    def activate(
        self,
        release_batch_id: str,
        case_id: str,
        *,
        prepared_revision_id: str,
        expected_active_revision_id: str,
        collection_id: str = "6d23b3aa-853f-3521-b7e2-4880938c946c",
    ) -> dict[str, Any]:
        return self._post(
            "activate",
            {
                "operationId": f"{release_batch_id}:{case_id}:activate",
                "collectionId": collection_id,
                "revisionId": prepared_revision_id,
                "expectedActiveRevisionId": expected_active_revision_id,
                "receiptExpiresAt": self._expires_at(600),
            },
        )

    def restore(self, release_batch_id: str, case_id: str, receipt_id: str) -> dict[str, Any]:
        return self._post(
            "restore",
            {
                "operationId": f"{release_batch_id}:{case_id}:restore",
                "receiptId": receipt_id,
            },
        )

    def _post(self, operation: str, body: Mapping[str, Any]) -> dict[str, Any]:
        payload = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        status, raw = self.transport(
            "POST",
            f"{self.base_url}/internal/knowledge/revisions/{operation}",
            {
                "Accept": "application/json",
                "Authorization": f"Bearer {self.token}",
                "Content-Type": "application/json",
            },
            payload,
        )
        try:
            value = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ContractError("KNOWLEDGE_CONTROL_RESPONSE_INVALID", operation) from error
        if status != 200 or not isinstance(value, dict):
            detail = value.get("errorCode", status) if isinstance(value, dict) else status
            raise ContractError("KNOWLEDGE_CONTROL_OPERATION_FAILED", f"{operation}:{detail}")
        return value

    def _expires_at(self, seconds: int) -> str:
        return (self.now() + timedelta(seconds=seconds)).isoformat().replace("+00:00", "Z")


class EmptyOutcomeRunner:
    """Runs three real product investigations around reversible revision switches."""

    def __init__(
        self,
        control: KnowledgeControlPlaneClient,
        product: ProductInvestigationClient,
        evidence: Any,
        output_root: Path,
    ):
        self.control = control
        self.product = product
        self.evidence = evidence
        self.output_root = output_root.resolve()

    def execute(
        self,
        release_batch_id: str,
        manifest: EmptyOutcomeManifest,
        ticket: Mapping[str, Any],
        profile: Mapping[str, Any],
        quality_run_report: Path,
        *,
        deadline_seconds: int = 600,
    ) -> dict[str, Any]:
        document = manifest.document
        manifest_digest = document["manifestDigest"]
        case_results = [
            self._execute_case(
                release_batch_id,
                case,
                manifest_digest,
                ticket,
                profile,
                deadline_seconds,
            )
            for case in document["cases"]
        ]
        denominator = _denominator_isolation(quality_run_report, case_results)
        matrix: dict[str, Any] = {
            "schemaVersion": "1.0.0",
            "releaseBatchId": release_batch_id,
            "runPurpose": RunPurpose.EMPTY_OUTCOME.value,
            "manifestDigest": manifest_digest,
            "status": ReleaseStatus.PASSED.value,
            "cases": case_results,
            "denominatorIsolation": denominator,
            "generatedAt": _timestamp(),
        }
        matrix["matrixDigest"] = _digest(matrix)
        self.output_root.mkdir(parents=True, exist_ok=True)
        _atomic_json(self.output_root / "empty-outcome-matrix.json", matrix)
        return matrix

    def _execute_case(
        self,
        release_batch_id: str,
        case: Mapping[str, Any],
        manifest_digest: str,
        ticket: Mapping[str, Any],
        profile: Mapping[str, Any],
        deadline_seconds: int,
    ) -> dict[str, Any]:
        case_id = str(case["caseId"])
        knowledge = case["knowledge"]
        collection_id = str(knowledge["collectionId"])
        original = self.control.active(collection_id)
        prepared = self.control.prepare(release_batch_id, case, manifest_digest)
        if (
            prepared.get("status") not in {"READY", "RETAINED"}
            or prepared.get("revisionId") != knowledge["revisionId"]
            or prepared.get("chunkCount") != knowledge["searchableChunkCount"]
        ):
            raise ContractError(
                ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value,
                f"{case_id}:prepared revision",
            )
        receipt = self.control.activate(
            release_batch_id,
            case_id,
            prepared_revision_id=str(knowledge["revisionId"]),
            expected_active_revision_id=original,
            collection_id=collection_id,
        )
        receipt_id = receipt.get("receiptId")
        if not isinstance(receipt_id, str) or self.control.active(collection_id) != knowledge["revisionId"]:
            raise ContractError(
                ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value,
                f"{case_id}:activation",
            )
        started_at = _timestamp()
        result: dict[str, Any] | None = None
        try:
            case_ticket = copy.deepcopy(dict(ticket))
            case_ticket["knowledgeContext"] = {
                **copy.deepcopy(case["query"]),
                "includeHistory": case["expectations"]["calls"]["historyLookup"] == 1,
            }
            product_run = self.product.investigate(
                case_ticket,
                deadline_seconds=deadline_seconds,
                evaluation_profile=str(profile["profile_id"]),
                token_budget=int(profile["efficiency_limits"]["total_tokens"]),
                run_identity=f"empty-outcome:{release_batch_id}:{case_id}",
            )
            exported = self._export_when_ready(product_run.run_id, case_id)
            actual = _actual_result(exported["artifacts"], case)
            if actual["knowledge"] != _expected_knowledge(case):
                raise ContractError(
                    ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value,
                    f"{case_id}:knowledge trace",
                )
            bounded = verify_empty_outcome_run_result(
                str(case["caseKind"]), actual["rca"], actual["evaluation"], profile
            )
            ended_at = _timestamp()
            result = {
                "caseId": case_id,
                "caseKind": case["caseKind"],
                "status": ReleaseStatus.PASSED.value,
                "incidentId": product_run.incident_id,
                "runId": product_run.run_id,
                "startedAt": started_at,
                "endedAt": ended_at,
                "knowledge": actual["knowledge"],
                "calls": actual["calls"],
                "boundedRca": bounded,
                "revisionLifecycle": {
                    "collectionId": collection_id,
                    "originalRevisionId": original,
                    "fixtureRevisionId": knowledge["revisionId"],
                    "receiptId": receipt_id,
                },
            }
        finally:
            restored = self.control.restore(release_batch_id, case_id, receipt_id)
            if restored.get("restoredAt") is None or self.control.active(collection_id) != original:
                raise ContractError(
                    ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value,
                    f"{case_id}:restore",
                )
            if result is not None:
                result["revisionLifecycle"].update({
                    "restoredAt": restored["restoredAt"],
                    "restoredRevisionId": original,
                    "restoreVerified": True,
                })
        if result is None:
            raise ContractError(
                ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value,
                f"{case_id}:result",
            )
        case_root = self.output_root / "cases" / case_id
        case_root.mkdir(parents=True, exist_ok=True)
        _atomic_json(case_root / "case-result.json", result)
        return result

    def _export_when_ready(self, run_id: str, case_id: str) -> dict[str, Any]:
        deadline = time.monotonic() + 120
        while True:
            try:
                return self.evidence.export_empty_outcome(
                    run_id, self.output_root / "raw" / case_id
                )
            except ContractError as failure:
                if (
                    failure.code != ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value
                    or time.monotonic() >= deadline
                ):
                    raise
                time.sleep(1)


def _actual_result(artifacts: Mapping[str, Path], case: Mapping[str, Any]) -> dict[str, Any]:
    required = {"a2a-calls", "tool-calls", "rca-json", "evaluation"}
    if not required.issubset(artifacts):
        raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, "evidence set")
    a2a = _read_json(artifacts["a2a-calls"])
    tools = _read_json(artifacts["tool-calls"])
    tasks = a2a.get("tasks") if isinstance(a2a, Mapping) else None
    if not isinstance(tasks, list) or not isinstance(tools, list):
        raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, "call trace")
    knowledge_tasks = [
        task for task in tasks
        if isinstance(task, Mapping) and task.get("agentId") == "knowledge"
        and task.get("state") == "COMPLETED"
    ]
    evidence_tasks = [
        task for task in tasks
        if isinstance(task, Mapping) and task.get("agentId") == "evidence-collector"
        and task.get("state") == "COMPLETED"
    ]
    if len(knowledge_tasks) != 1 or len(evidence_tasks) != 1:
        raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, "A2A trace")
    knowledge = knowledge_tasks[0].get("artifact")
    evidence = evidence_tasks[0].get("artifact", {}).get("evidence")
    if not isinstance(knowledge, Mapping) or not isinstance(evidence, list):
        raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, "agent artifacts")
    expected_codes = {item["evidenceCode"] for item in case["fieldEvidence"]}
    actual_codes = {
        item.get("evidenceCode") for item in evidence if isinstance(item, Mapping)
    }
    if not expected_codes.issubset(actual_codes):
        raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, "field evidence")
    tool_names = [item.get("tool_name", item.get("toolName")) for item in tools if isinstance(item, Mapping)]
    calls = {
        "knowledgeAgentA2a": 1,
        "knowledgeSearchTool": tool_names.count("KnowledgeSearchTool"),
        "catalogCheck": int(knowledge.get("catalogChecked") is True),
        "embedding": int(knowledge.get("embeddingApplied") is True),
        "exactRecall": int(knowledge.get("exactRecallApplied") is True),
        "rerank": int(knowledge.get("rerankApplied") is True),
        "historyLookup": int(knowledge.get("historyLookupApplied") is True),
        "fieldEvidenceTools": len({item["toolName"] for item in case["fieldEvidence"]}),
    }
    if calls != case["expectations"]["calls"]:
        raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, "call counts")
    return {
        "knowledge": {
            "revisionId": knowledge.get("knowledgeRevisionId"),
            "outcome": knowledge.get("outcome"),
            "candidateCount": knowledge.get("candidateCount"),
            "rerankCount": knowledge.get("rerankCount"),
            "historyResultCount": knowledge.get("historyResultCount"),
            "fallbacks": knowledge.get("fallbacks"),
            "technicalChainFailures": 0,
            "continuedFieldInvestigation": len(tasks) > 1,
        },
        "calls": calls,
        "rca": _read_json(artifacts["rca-json"]),
        "evaluation": _read_json(artifacts["evaluation"]),
    }


def _expected_knowledge(case: Mapping[str, Any]) -> dict[str, Any]:
    expected = case["expectations"]
    return {
        "revisionId": case["knowledge"]["revisionId"],
        "outcome": expected["knowledgeOutcome"],
        "candidateCount": expected["candidateCount"],
        "rerankCount": expected["rerankCount"],
        "historyResultCount": expected["historyResultCount"],
        "fallbacks": expected["fallbacks"],
        "technicalChainFailures": expected["technicalChainFailures"],
        "continuedFieldInvestigation": expected["continueFieldInvestigation"],
    }


def _denominator_isolation(path: Path, cases: list[Mapping[str, Any]]) -> dict[str, Any]:
    report = _read_json(path)
    runs = report.get("runs") if isinstance(report, Mapping) else None
    quality_ids = {
        item.get("runId") for item in runs or []
        if isinstance(item, Mapping) and item.get("status") == ReleaseStatus.PASSED.value
    }
    empty_ids = {case["runId"] for case in cases}
    intersection = sorted(quality_ids.intersection(empty_ids))
    if (
        report.get("status") != ReleaseStatus.PASSED.value
        or report.get("completedRuns") != 15
        or len(quality_ids) != 15
        or intersection
    ):
        raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, "denominator")
    return {
        "qualityRunPurpose": RunPurpose.RELEASE_QUALITY.value,
        "emptyOutcomeRunPurpose": RunPurpose.EMPTY_OUTCOME.value,
        "qualityRunCount": 15,
        "emptyOutcomeRunCount": len(empty_ids),
        "intersection": intersection,
        "excluded": sorted(empty_ids),
    }


def _read_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ContractError(ReleaseErrorCode.EMPTY_OUTCOME_RESULT_INVALID.value, path.name) from error


def _atomic_json(path: Path, value: Mapping[str, Any]) -> None:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode() + b"\n"
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
    import hashlib

    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()


def _timestamp() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
