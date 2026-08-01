from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping

import psycopg

from ..contracts import ContractError
from .ledger import PROFESSIONAL_AGENTS
from .model import ReleaseErrorCode, RunPurpose


REQUIRED_RUN_EVIDENCE = (
    "rca-json",
    "rca-markdown",
    "evaluation",
    "provider-calls",
    "tool-calls",
    "a2a-calls",
    "usage-ledger",
    "state-events",
    "citations",
    "artifact-index",
)
_PATH_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$")
_LOGICAL_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")
_EVIDENCE_QUERY = "SELECT * FROM opspilot.read_release_run_evidence(%s::uuid)"


class PostgresRunEvidenceReader:
    """Exports the fixed evidence projection exposed to the read-only Fault Lab identity."""

    def __init__(
        self,
        jdbc_url: str,
        username: str,
        password_file: Path,
        connector: Callable[..., Any] = psycopg.connect,
    ):
        prefix = "jdbc:postgresql://"
        if not jdbc_url.startswith(prefix) or not username or not password_file.is_file():
            raise ContractError(
                ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value,
                "database credentials",
            )
        self.dsn = "postgresql://" + jdbc_url[len(prefix):]
        self.username = username
        self.password_file = password_file
        self.connector = connector

    def export(self, run_id: str, output_root: Path) -> dict[str, Any]:
        values = self._read(run_id)
        return self._export(run_id, output_root, values, _validate_projection)

    def export_empty_outcome(self, run_id: str, output_root: Path) -> dict[str, Any]:
        """Export complete evidence while allowing intentional KB_EMPTY/NO_MATCH results."""
        values = self._read(run_id)
        return self._export(run_id, output_root, values, _validate_empty_outcome_projection)

    @staticmethod
    def _export(
        run_id: str,
        output_root: Path,
        values: Mapping[str, Any],
        validator: Callable[[Mapping[str, Any]], None],
    ) -> dict[str, Any]:
        validator(values)
        target = output_root.resolve() / run_id
        if target.exists():
            raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_EXISTS.value, run_id)
        staging = target.with_name(f".{run_id}.{uuid.uuid4().hex}.tmp")
        staging.mkdir(parents=True, exist_ok=False)
        try:
            artifacts: dict[str, Path] = {}
            for evidence_type in REQUIRED_RUN_EVIDENCE:
                suffix = ".md" if evidence_type == "rca-markdown" else ".json"
                path = staging / f"{evidence_type}{suffix}"
                value = values[evidence_type]
                if suffix == ".md":
                    path.write_text(str(value).rstrip() + "\n", encoding="utf-8", newline="\n")
                else:
                    path.write_bytes(_canonical_json(value) + b"\n")
                artifacts[evidence_type] = target / path.name
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(staging, target)
            for path in target.iterdir():
                path.chmod(0o444)
            exported = {"artifacts": artifacts}
            if validator is _validate_projection:
                exported["executionIntegrity"] = _execution_integrity(values["a2a-calls"])
            return exported
        except BaseException:
            shutil.rmtree(staging, ignore_errors=True)
            raise

    def _read(self, run_id: str) -> dict[str, Any]:
        try:
            password = self.password_file.read_text(encoding="utf-8").strip()
            with self.connector(self.dsn, user=self.username, password=password) as connection:
                row = connection.execute(_EVIDENCE_QUERY, (run_id,)).fetchone()
        except (OSError, psycopg.Error) as exc:
            raise ContractError(
                ReleaseErrorCode.QUALITY_RUN_QUERY_FAILED.value,
                "Run evidence query",
            ) from exc
        if row is None or len(row) != len(REQUIRED_RUN_EVIDENCE):
            raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value, run_id)
        return dict(zip(REQUIRED_RUN_EVIDENCE, row, strict=True))


class QualityEvidenceCollector:
    """Waits for Evaluation, seals one Run, then returns ledger-ready Artifact paths."""

    def __init__(
        self,
        reader: PostgresRunEvidenceReader,
        sealer: "QualityRunEvidenceSealer",
        raw_root: Path,
        sealed_root: Path,
        *,
        evaluation_profile_id: str,
        evaluation_profile_digest: str,
        snapshot_digest: str,
        wait_seconds: int = 120,
        monotonic: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.reader = reader
        self.sealer = sealer
        self.raw_root = raw_root
        self.sealed_root = sealed_root
        self.evaluation_profile_id = evaluation_profile_id
        self.evaluation_profile_digest = evaluation_profile_digest
        self.snapshot_digest = snapshot_digest
        self.wait_seconds = wait_seconds
        self.monotonic = monotonic
        self.sleep = sleep

    def collect(
        self,
        release_batch_id: str,
        slot: Mapping[str, Any],
        ticket: Mapping[str, Any],
        run_id: str,
    ) -> Mapping[str, Path]:
        deadline = self.monotonic() + self.wait_seconds
        while True:
            try:
                exported = self.reader.export(
                    run_id,
                    self.raw_root / release_batch_id,
                )
                break
            except ContractError as exc:
                if exc.code != ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value \
                        or self.monotonic() >= deadline:
                    raise
                self.sleep(1)
        manifest = self.sealer.seal(
            release_batch_id,
            run_id,
            exported["artifacts"],
            evaluation_profile_id=self.evaluation_profile_id,
            evaluation_profile_digest=self.evaluation_profile_digest,
            snapshot_digest=self.snapshot_digest,
            dataset_valid=True,
            slot_id=str(slot["slotId"]),
            scenario_id=str(slot["scenarioId"]),
            dataset_run_id=str(ticket["datasetRunId"]),
            execution_integrity=exported["executionIntegrity"],
        )
        target = self.sealed_root / release_batch_id / run_id
        result = {
            item["uri"]: target / item["uri"].rsplit("/", 1)[1]
            for item in manifest["artifacts"]
        }
        result[
            f"artifact://phase8/{release_batch_id}/{run_id}/evidence-manifest.json"
        ] = target / "evidence-manifest.json"
        return result


class QualityRunEvidenceSealer:
    """Copies one Run's complete evidence set into an immutable atomic directory."""

    def __init__(self, output_root: Path):
        self.output_root = output_root.resolve()

    def seal(
        self,
        release_batch_id: str,
        run_id: str,
        artifacts: Mapping[str, Path],
        *,
        evaluation_profile_id: str,
        evaluation_profile_digest: str,
        snapshot_digest: str,
        dataset_valid: bool,
        slot_id: str,
        scenario_id: str,
        dataset_run_id: str,
        execution_integrity: Mapping[str, bool],
    ) -> dict[str, Any]:
        if not _PATH_ID.fullmatch(release_batch_id) or not _PATH_ID.fullmatch(run_id):
            self._invalid("releaseBatchId/runId")
        if not _PATH_ID.fullmatch(evaluation_profile_id) \
                or not re.fullmatch(r"[0-9a-f]{64}", evaluation_profile_digest) \
                or not re.fullmatch(r"[0-9a-f]{64}", snapshot_digest) \
                or not _LOGICAL_ID.fullmatch(slot_id) \
                or not _LOGICAL_ID.fullmatch(scenario_id) \
                or not _LOGICAL_ID.fullmatch(dataset_run_id) \
                or dataset_valid is not True:
            self._invalid("frozen evaluation identity or dataset validity")
        if set(artifacts) != set(REQUIRED_RUN_EVIDENCE):
            self._invalid("required evidence set")
        expected_integrity = {
            "mockUsed": False,
            "hiddenFallbackUsed": False,
            "vectorOnly": False,
            "keywordFallbackUsed": False,
            "fixedResultUsed": False,
            "rerankRequired": True,
            "rerankExecuted": True,
        }
        if dict(execution_integrity) != expected_integrity:
            self._invalid("release execution integrity")

        target = self.output_root / release_batch_id / run_id
        if target.exists():
            raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_EXISTS.value, run_id)
        staging = target.with_name(f".{run_id}.{uuid.uuid4().hex}.tmp")
        staging.mkdir(parents=True, exist_ok=False)
        try:
            items = [
                self._copy_artifact(staging, release_batch_id, run_id, evidence_type, artifacts[evidence_type])
                for evidence_type in REQUIRED_RUN_EVIDENCE
            ]
            manifest: dict[str, Any] = {
                "schemaVersion": "1.0.0",
                "releaseBatchId": release_batch_id,
                "runPurpose": RunPurpose.RELEASE_QUALITY.value,
                "runId": run_id,
                "slotId": slot_id,
                "scenarioId": scenario_id,
                "datasetRunId": dataset_run_id,
                "evaluationProfileId": evaluation_profile_id,
                "evaluationProfileDigest": evaluation_profile_digest,
                "snapshotDigest": snapshot_digest,
                "datasetValid": True,
                "executionIntegrity": expected_integrity,
                "sealedAt": datetime.now(timezone.utc).isoformat(timespec="milliseconds")
                .replace("+00:00", "Z"),
                "artifacts": items,
            }
            manifest["manifestDigest"] = _digest_json(manifest)
            (staging / "evidence-manifest.json").write_bytes(_canonical_json(manifest) + b"\n")
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(staging, target)
            for path in target.rglob("*"):
                if path.is_file():
                    path.chmod(0o444)
            return manifest
        except BaseException:
            shutil.rmtree(staging, ignore_errors=True)
            raise

    @staticmethod
    def _copy_artifact(
        staging: Path,
        release_batch_id: str,
        run_id: str,
        evidence_type: str,
        source: Path,
    ) -> dict[str, Any]:
        if not isinstance(source, Path) or source.is_symlink() or not source.is_file():
            raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value, evidence_type)
        try:
            content = source.read_bytes()
        except OSError as exc:
            raise ContractError(
                ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value,
                evidence_type,
            ) from exc
        suffix = source.suffix.lower()
        if suffix not in {".json", ".md", ".jsonl"}:
            raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value, evidence_type)
        filename = f"{evidence_type}{suffix}"
        (staging / filename).write_bytes(content)
        return {
            "evidenceType": evidence_type,
            "uri": f"artifact://phase8/{release_batch_id}/{run_id}/{filename}",
            "size": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
        }

    @staticmethod
    def _invalid(detail: str) -> None:
        raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value, detail)


def _validate_projection(values: Mapping[str, Any]) -> None:
    evaluation = values.get("evaluation")
    valid = (
        set(values) == set(REQUIRED_RUN_EVIDENCE)
        and isinstance(values.get("rca-json"), Mapping)
        and isinstance(values.get("rca-markdown"), str)
        and bool(values["rca-markdown"].strip())
        and isinstance(evaluation, Mapping)
        and evaluation.get("status") == "COMPLETED"
        and isinstance(evaluation.get("report"), Mapping)
        and isinstance(values.get("provider-calls"), list)
        and len(values["provider-calls"]) >= 6
        and isinstance(values.get("tool-calls"), list)
        and isinstance(values.get("usage-ledger"), list)
        and len(values["usage-ledger"]) >= 6
        and isinstance(values.get("state-events"), list)
        and isinstance(values.get("citations"), list)
        and isinstance(values.get("artifact-index"), list)
    )
    if not valid:
        raise ContractError(
            ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value,
            "incomplete release evidence projection",
        )
    _execution_integrity(values["a2a-calls"])


def _validate_empty_outcome_projection(values: Mapping[str, Any]) -> None:
    evaluation = values.get("evaluation")
    a2a = values.get("a2a-calls")
    tasks = a2a.get("tasks") if isinstance(a2a, Mapping) else None
    knowledge = next(
        (
            task.get("artifact")
            for task in tasks or []
            if isinstance(task, Mapping)
            and task.get("agentId") == "knowledge"
            and task.get("state") == "COMPLETED"
        ),
        None,
    )
    valid = (
        set(values) == set(REQUIRED_RUN_EVIDENCE)
        and isinstance(values.get("rca-json"), Mapping)
        and isinstance(values.get("rca-markdown"), str)
        and bool(values["rca-markdown"].strip())
        and isinstance(evaluation, Mapping)
        and evaluation.get("status") == "COMPLETED"
        and isinstance(evaluation.get("report"), Mapping)
        and isinstance(values.get("provider-calls"), list)
        and len(values["provider-calls"]) >= 6
        and isinstance(values.get("tool-calls"), list)
        and isinstance(values.get("usage-ledger"), list)
        and isinstance(tasks, list)
        and isinstance(knowledge, Mapping)
        and knowledge.get("outcome") in {"KB_EMPTY", "NO_MATCH", "MATCH"}
        and knowledge.get("knowledgeRevisionId")
    )
    if not valid:
        raise ContractError(
            ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value,
            "incomplete empty-outcome evidence projection",
        )


def _execution_integrity(value: Any) -> dict[str, bool]:
    if not isinstance(value, Mapping) or not isinstance(value.get("tasks"), list) \
            or not isinstance(value.get("audit"), list):
        raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value, "A2A evidence")
    tasks = value["tasks"]
    completed_tasks = [
        task for task in tasks
        if isinstance(task, Mapping) and task.get("state") == "COMPLETED"
    ]
    agents = {task.get("agentId") for task in completed_tasks}
    knowledge = next(
        (task for task in completed_tasks if task.get("agentId") == "knowledge"),
        None,
    )
    artifact = knowledge.get("artifact") if isinstance(knowledge, Mapping) else None
    references = artifact.get("references") if isinstance(artifact, Mapping) else None
    valid = (
        agents == set(PROFESSIONAL_AGENTS)
        and len(completed_tasks) == len(PROFESSIONAL_AGENTS)
        and all(
            isinstance(task, Mapping)
            and task.get("agentId") in set(PROFESSIONAL_AGENTS)
            and task.get("state") in {"COMPLETED", "FAILED"}
            for task in tasks
        )
        and len(value["audit"]) == len(PROFESSIONAL_AGENTS)
        and isinstance(artifact, Mapping)
        and artifact.get("embeddingApplied") is True
        and isinstance(references, list)
        and bool(references)
        and all(isinstance(item, Mapping) and isinstance(item.get("rerankRank"), int)
                for item in references)
    )
    if not valid:
        raise ContractError(
            ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value,
            "real A2A/RAG/Rerank evidence",
        )
    return {
        "mockUsed": False,
        "hiddenFallbackUsed": False,
        "vectorOnly": False,
        "keywordFallbackUsed": False,
        "fixedResultUsed": False,
        "rerankRequired": True,
        "rerankExecuted": True,
    }


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _digest_json(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value)).hexdigest()
