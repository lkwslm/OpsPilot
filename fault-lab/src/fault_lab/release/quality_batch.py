from __future__ import annotations

import json
import os
import uuid
from pathlib import Path
from typing import Any, Mapping, Protocol

from ..contracts import ContractError
from ..product import ProductRun
from .model import ReleaseErrorCode, ReleaseStatus
from .quality import FROZEN_SCENARIOS
from .quality_evidence import QualityEvidenceCollector
from .quality_run import QualityRunExecutor, RunIdentityFacts


class SlotExecutor(Protocol):
    def execute_slot(self, release_batch_id: str, slot: Mapping[str, Any],
                     ticket: dict[str, Any], **arguments: Any) -> dict[str, Any]: ...


class QualityBatchRunner:
    """Executes the immutable 3x5 plan in order and never replaces a failed slot."""

    def __init__(
        self,
        executor: QualityRunExecutor | SlotExecutor,
        evidence: QualityEvidenceCollector,
        output_root: Path,
    ):
        self.executor = executor
        self.evidence = evidence
        self.output_root = output_root.resolve()

    def execute(
        self,
        plan: Mapping[str, Any],
        snapshot: Mapping[str, Any],
        profile: Mapping[str, Any],
        dataset_root: Path,
        dataset_map: Mapping[str, str],
        *,
        deadline_seconds: int = 600,
    ) -> dict[str, Any]:
        _validate_inputs(plan, snapshot, profile, dataset_map)
        release_batch_id = str(plan["releaseBatchId"])
        batch_root = self.output_root / release_batch_id
        plan_path = batch_root / "quality-plan.json"
        report_path = batch_root / "quality-run-report.json"
        if batch_root.exists():
            raise ContractError(ReleaseErrorCode.QUALITY_SLOT_ALREADY_ATTEMPTED.value, release_batch_id)
        batch_root.mkdir(parents=True, exist_ok=False)
        _write_new_json(plan_path, dict(plan))

        completed: list[dict[str, Any]] = []
        attempted_slot: str | None = None
        failure: dict[str, str] | None = None
        for slot in plan["slots"]:
            attempted_slot = str(slot["slotId"])
            dataset_run_id = dataset_map[str(slot["scenarioId"])]
            ticket = _ticket(dataset_root / dataset_run_id / "input" / "ticket.json")
            try:
                entry = self.executor.execute_slot(
                    release_batch_id,
                    slot,
                    ticket,
                    evaluation_profile=str(profile["profile_id"]),
                    token_budget=int(profile["efficiency_limits"]["total_tokens"]),
                    environment_digest=str(snapshot["snapshotDigest"]),
                    deadline_seconds=deadline_seconds,
                    artifact_provider=lambda product, facts, current_slot=slot, current_ticket=ticket: (
                        self._collect(
                            release_batch_id,
                            current_slot,
                            current_ticket,
                            product,
                            facts,
                        )
                    ),
                    allow_dataset_reuse=int(slot["ordinal"]) > 1,
                )
                completed.append({
                    "slotId": slot["slotId"],
                    "incidentId": entry["runIdentity"]["incidentId"],
                    "runId": entry["runIdentity"]["runId"],
                    "entryDigest": entry["entryDigest"],
                    "status": entry["status"],
                })
            except ContractError as exc:
                failure = {"errorCode": exc.code, "detail": exc.detail}
                break

        status = ReleaseStatus.PASSED.value if len(completed) == int(plan["totalSlots"]) else (
            ReleaseStatus.BLOCKED.value if not completed else ReleaseStatus.FAILED.value
        )
        report = {
            "schemaVersion": "1.0.0",
            "releaseBatchId": release_batch_id,
            "status": status,
            "plannedRuns": int(plan["totalSlots"]),
            "completedRuns": len(completed),
            "replacementRuns": 0,
            "attemptedSlot": attempted_slot,
            "failure": failure,
            "runs": completed,
            "snapshotDigest": snapshot["snapshotDigest"],
            "evaluationProfileId": profile["profile_id"],
        }
        _write_new_json(report_path, report)
        return report

    def _collect(
        self,
        release_batch_id: str,
        slot: Mapping[str, Any],
        ticket: Mapping[str, Any],
        product: ProductRun,
        facts: RunIdentityFacts,
    ) -> Mapping[str, Path]:
        if product.run_id != facts.run_id:
            raise ContractError(
                ReleaseErrorCode.QUALITY_RUN_IDENTITY_INVALID.value,
                "evidence Run identity",
            )
        return self.evidence.collect(
            release_batch_id,
            slot,
            ticket,
            product.run_id,
        )


def _validate_inputs(
    plan: Mapping[str, Any],
    snapshot: Mapping[str, Any],
    profile: Mapping[str, Any],
    dataset_map: Mapping[str, str],
) -> None:
    scenarios = {scenario_id for scenario_id, _ in FROZEN_SCENARIOS}
    valid = (
        plan.get("snapshotDigest") == snapshot.get("snapshotDigest")
        and plan.get("evaluationProfileId") == profile.get("profile_id")
        and plan.get("totalSlots") == 15
        and isinstance(plan.get("slots"), list)
        and len(plan["slots"]) == 15
        and set(dataset_map) == scenarios
        and all(isinstance(value, str) and value for value in dataset_map.values())
    )
    if not valid:
        raise ContractError(ReleaseErrorCode.QUALITY_PLAN_INVALID.value, "batch inputs")


def _ticket(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(ReleaseErrorCode.QUALITY_DATASET_INVALID.value, str(path)) from exc
    if not isinstance(value, dict):
        raise ContractError(ReleaseErrorCode.QUALITY_DATASET_INVALID.value, str(path))
    return value


def _write_new_json(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{uuid.uuid4().hex}.tmp")
    try:
        with temporary.open("x", encoding="utf-8", newline="\n") as handle:
            json.dump(value, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        if path.exists():
            raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_EXISTS.value, path.name)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
