from __future__ import annotations

import hashlib
import json
import os
import re
import uuid
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose


_DIGEST = re.compile(r"^[0-9a-f]{64}$")
_SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$")
_RECORD_FIELDS = {
    "releaseBatchId",
    "runPurpose",
    "runIdentity",
    "startedAt",
    "endedAt",
    "environmentDigest",
    "status",
    "reason",
}
_IDENTITY_FIELDS = {
    "incidentId",
    "runId",
    "datasetRunId",
    "a2aContextId",
    "supervisorSessionId",
    "a2aTasks",
    "attempt",
}
_A2A_TASK_FIELDS = {
    "agentId",
    "a2aTaskId",
    "messageId",
    "agentScopeSessionId",
}
PROFESSIONAL_AGENTS = (
    "evidence-collector",
    "code-analysis",
    "knowledge",
    "diagnosis",
    "remediation",
)
_UNIQUE_RUN_FIELDS = (
    "incidentId",
    "a2aContextId",
    "supervisorSessionId",
)
_UNIQUE_TASK_FIELDS = (
    "a2aTaskId",
    "messageId",
    "agentScopeSessionId",
)


class RunLedger:
    """Append-only release Run ledger with a rebuildable atomic index."""

    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.ledger_path = self.root / "runs.jsonl"
        self.index_path = self.root / "index.json"

    def append(
        self,
        record: Mapping[str, Any],
        artifacts: Mapping[str, Path],
    ) -> dict[str, Any]:
        normalized = _normalize_record(record)
        normalized["artifactManifest"] = _artifact_manifest(artifacts)
        normalized["entryDigest"] = _digest_json(normalized)

        current_bytes = self.ledger_path.read_bytes() if self.ledger_path.exists() else b""
        entries = _parse_ledger(current_bytes)
        _reject_duplicate(entries, normalized)

        next_entries = [*entries, normalized]
        next_bytes = current_bytes + _canonical_json(normalized) + b"\n"
        _atomic_replace(self.ledger_path, next_bytes)
        _atomic_replace(self.index_path, _canonical_json(_index(next_entries, next_bytes)) + b"\n")
        return normalized

    def entries(self) -> list[dict[str, Any]]:
        if not self.ledger_path.exists():
            return []
        return _parse_ledger(self.ledger_path.read_bytes())


def _normalize_record(record: Mapping[str, Any]) -> dict[str, Any]:
    if set(record) != _RECORD_FIELDS:
        _invalid("record fields")
    identity = record.get("runIdentity")
    if not isinstance(identity, Mapping) or set(identity) != _IDENTITY_FIELDS:
        _invalid("runIdentity fields")
    _require_id(record.get("releaseBatchId"), "releaseBatchId")
    try:
        purpose = RunPurpose(record.get("runPurpose"))
        status = ReleaseStatus(record.get("status"))
    except ValueError as exc:
        raise ContractError(ReleaseErrorCode.LEDGER_ENTRY_INVALID.value, "enum") from exc
    for field in ("runId", "datasetRunId"):
        _require_id(identity.get(field), field)
    allow_partial_identity = purpose is RunPurpose.BASELINE_ONLY or status in {
        ReleaseStatus.FAILED,
        ReleaseStatus.BLOCKED,
    }
    for field in ("incidentId", *_UNIQUE_RUN_FIELDS[1:]):
        value = identity.get(field)
        if value is None and allow_partial_identity:
            continue
        _require_id(value, field)
    supervisor_session_id = identity.get("supervisorSessionId")
    if supervisor_session_id is not None \
            and not str(supervisor_session_id).startswith("supervisor:"):
        _invalid("supervisorSessionId")
    if identity.get("a2aContextId") is not None \
            and identity["a2aContextId"] != identity["runId"]:
        _invalid("a2aContextId must equal runId")
    tasks = _normalize_a2a_tasks(identity.get("a2aTasks"), str(identity["runId"]))
    if purpose is RunPurpose.RELEASE_QUALITY and status is ReleaseStatus.PASSED:
        if identity.get("incidentId") is None or identity.get("a2aContextId") is None \
                or identity.get("supervisorSessionId") is None:
            _invalid("complete release Run identity")
        if tuple(task["agentId"] for task in tasks) != PROFESSIONAL_AGENTS:
            _invalid("professional Agent set")
    if tasks and identity.get("a2aContextId") is None:
        _invalid("a2aTasks require context")
    attempt = identity.get("attempt")
    if not isinstance(attempt, int) or isinstance(attempt, bool) or attempt < 1:
        _invalid("attempt")
    started = _timestamp(record.get("startedAt"), "startedAt")
    ended = _timestamp(record.get("endedAt"), "endedAt")
    if ended < started:
        _invalid("timeline")
    environment_digest = record.get("environmentDigest")
    if not isinstance(environment_digest, str) or not _DIGEST.fullmatch(environment_digest):
        _invalid("environmentDigest")
    reason = record.get("reason")
    if status in {ReleaseStatus.FAILED, ReleaseStatus.BLOCKED}:
        if not isinstance(reason, Mapping) or set(reason) != {"errorCode", "detail"}:
            _invalid("reason")
        _require_id(reason.get("errorCode"), "reason.errorCode")
        if not isinstance(reason.get("detail"), str) or not reason["detail"].strip():
            _invalid("reason.detail")
        normalized_reason: dict[str, str] | None = {
            "errorCode": str(reason["errorCode"]),
            "detail": reason["detail"],
        }
    else:
        if reason is not None:
            _invalid("reason")
        normalized_reason = None
    return {
        "schemaVersion": "2.0.0",
        "releaseBatchId": str(record["releaseBatchId"]),
        "runPurpose": purpose.value,
        "runIdentity": {
            "incidentId": identity["incidentId"],
            "runId": identity["runId"],
            "datasetRunId": identity["datasetRunId"],
            "a2aContextId": identity["a2aContextId"],
            "supervisorSessionId": identity["supervisorSessionId"],
            "a2aTasks": tasks,
            "attempt": identity["attempt"],
        },
        "startedAt": str(record["startedAt"]),
        "endedAt": str(record["endedAt"]),
        "environmentDigest": environment_digest,
        "status": status.value,
        "reason": normalized_reason,
    }


def _normalize_a2a_tasks(value: Any, run_id: str) -> list[dict[str, str]]:
    if not isinstance(value, list):
        _invalid("a2aTasks")
    tasks: list[dict[str, str]] = []
    seen: dict[str, set[str]] = {
        "agentId": set(),
        **{field: set() for field in _UNIQUE_TASK_FIELDS},
    }
    for raw in value:
        if not isinstance(raw, Mapping) or set(raw) != _A2A_TASK_FIELDS:
            _invalid("a2aTask fields")
        for field in _A2A_TASK_FIELDS:
            _require_id(raw.get(field), f"a2aTasks.{field}")
        agent_id = str(raw["agentId"])
        if agent_id not in PROFESSIONAL_AGENTS:
            _invalid("a2aTasks.agentId")
        task_id = str(raw["a2aTaskId"])
        message_id = str(raw["messageId"])
        session_id = str(raw["agentScopeSessionId"])
        if not message_id.startswith(f"{run_id}:{agent_id}:"):
            _invalid("a2aTasks.messageId")
        if session_id != f"{agent_id}:{task_id}":
            _invalid("a2aTasks.agentScopeSessionId")
        normalized = {
            "agentId": agent_id,
            "a2aTaskId": task_id,
            "messageId": message_id,
            "agentScopeSessionId": session_id,
        }
        for field, field_seen in seen.items():
            if normalized[field] in field_seen:
                _invalid(f"duplicate a2aTasks.{field}")
            field_seen.add(normalized[field])
        tasks.append(normalized)
    order = {agent_id: position for position, agent_id in enumerate(PROFESSIONAL_AGENTS)}
    return sorted(tasks, key=lambda task: order[task["agentId"]])


def _artifact_manifest(artifacts: Mapping[str, Path]) -> dict[str, Any]:
    if not isinstance(artifacts, Mapping):
        raise ContractError(ReleaseErrorCode.ARTIFACT_INVALID.value, "manifest")
    items = []
    for uri, source in sorted(artifacts.items()):
        if not isinstance(uri, str) or not uri.strip() or not isinstance(source, Path):
            raise ContractError(ReleaseErrorCode.ARTIFACT_INVALID.value, "artifact identity")
        try:
            content = source.read_bytes()
        except OSError as exc:
            raise ContractError(ReleaseErrorCode.ARTIFACT_INVALID.value, uri) from exc
        if not source.is_file():
            raise ContractError(ReleaseErrorCode.ARTIFACT_INVALID.value, uri)
        items.append(
            {
                "uri": uri,
                "size": len(content),
                "sha256": hashlib.sha256(content).hexdigest(),
            }
        )
    manifest: dict[str, Any] = {"schemaVersion": "1.0.0", "artifacts": items}
    manifest["manifestDigest"] = _digest_json(manifest)
    return manifest


def _reject_duplicate(entries: list[dict[str, Any]], candidate: dict[str, Any]) -> None:
    identity = candidate["runIdentity"]
    for entry in entries:
        existing = entry["runIdentity"]
        if existing["runId"] == identity["runId"]:
            code = (
                ReleaseErrorCode.LEDGER_ENTRY_EXISTS
                if entry["entryDigest"] == candidate["entryDigest"]
                else ReleaseErrorCode.LEDGER_RUN_CONFLICT
            )
            raise ContractError(code.value, identity["runId"])
        for field in _UNIQUE_RUN_FIELDS:
            if identity[field] is not None and existing[field] == identity[field]:
                raise ContractError(
                    ReleaseErrorCode.LEDGER_IDENTITY_DUPLICATE.value,
                    f"{field}={identity[field]}",
                )
        existing_task_values = {
            field: {task[field] for task in existing["a2aTasks"]}
            for field in _UNIQUE_TASK_FIELDS
        }
        for task in identity["a2aTasks"]:
            for field in _UNIQUE_TASK_FIELDS:
                if task[field] in existing_task_values[field]:
                    raise ContractError(
                        ReleaseErrorCode.LEDGER_IDENTITY_DUPLICATE.value,
                        f"{field}={task[field]}",
                    )


def _parse_ledger(content: bytes) -> list[dict[str, Any]]:
    if not content:
        return []
    if not content.endswith(b"\n"):
        raise ContractError(ReleaseErrorCode.LEDGER_CORRUPT.value, "truncated entry")
    entries = []
    for number, line in enumerate(content.splitlines(), start=1):
        try:
            entry = json.loads(line)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ContractError(ReleaseErrorCode.LEDGER_CORRUPT.value, f"line {number}") from exc
        if not isinstance(entry, dict):
            raise ContractError(ReleaseErrorCode.LEDGER_CORRUPT.value, f"line {number}")
        entry_digest = entry.get("entryDigest")
        unsigned = {key: value for key, value in entry.items() if key != "entryDigest"}
        if not isinstance(entry_digest, str) or entry_digest != _digest_json(unsigned):
            raise ContractError(ReleaseErrorCode.LEDGER_CORRUPT.value, f"line {number} digest")
        manifest = entry.get("artifactManifest")
        if not isinstance(manifest, dict):
            raise ContractError(ReleaseErrorCode.LEDGER_CORRUPT.value, f"line {number} manifest")
        manifest_digest = manifest.get("manifestDigest")
        unsigned_manifest = {
            key: value for key, value in manifest.items() if key != "manifestDigest"
        }
        if not isinstance(manifest_digest, str) or manifest_digest != _digest_json(unsigned_manifest):
            raise ContractError(
                ReleaseErrorCode.LEDGER_CORRUPT.value,
                f"line {number} manifest digest",
            )
        entries.append(entry)
    return entries


def _index(entries: list[dict[str, Any]], ledger_bytes: bytes) -> dict[str, Any]:
    batches: dict[str, list[str]] = {}
    for entry in entries:
        batches.setdefault(entry["releaseBatchId"], []).append(entry["runIdentity"]["runId"])
    return {
        "schemaVersion": "1.0.0",
        "entryCount": len(entries),
        "ledgerDigest": hashlib.sha256(ledger_bytes).hexdigest(),
        "releaseBatches": {batch: batches[batch] for batch in sorted(batches)},
    }


def _atomic_replace(path: Path, content: bytes) -> None:
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("xb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _timestamp(value: Any, field: str) -> datetime:
    if not isinstance(value, str):
        _invalid(field)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ContractError(ReleaseErrorCode.LEDGER_ENTRY_INVALID.value, field) from exc
    if parsed.tzinfo is None:
        _invalid(field)
    return parsed


def _require_id(value: Any, field: str) -> None:
    if not isinstance(value, str) or not _SAFE_ID.fullmatch(value):
        _invalid(field)


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.LEDGER_ENTRY_INVALID.value, detail)


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _digest_json(value: Any) -> str:
    return hashlib.sha256(_canonical_json(value)).hexdigest()
