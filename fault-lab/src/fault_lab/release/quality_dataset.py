from __future__ import annotations

import json
from pathlib import Path
from typing import Any, Callable, Mapping

from ..contracts import ContractError
from ..dataset import DatasetValidator, activate_dataset_zone
from .model import ReleaseErrorCode


_IDENTITY_FIELDS = ("gitCommit", "composeDigest", "modelConfigDigest")


class QualityDatasetSelector:
    """Validates a frozen dataset and recovered environment before a release slot runs."""

    def __init__(
        self,
        dataset_root: Path,
        expected_identity: Mapping[str, str],
        recovery_probe: Callable[[str], Mapping[str, Any]],
        *,
        agent_input_root: Path,
        validator: DatasetValidator | None = None,
    ):
        if any(not isinstance(expected_identity.get(field), str)
               or not expected_identity[field] for field in _IDENTITY_FIELDS):
            raise ContractError(
                ReleaseErrorCode.QUALITY_DATASET_INVALID.value,
                "frozen dataset identity",
            )
        self.dataset_root = dataset_root.resolve()
        self.expected_identity = dict(expected_identity)
        self.recovery_probe = recovery_probe
        self.agent_input_root = agent_input_root.resolve()
        self.validator = validator or DatasetValidator()
        self._attempted_slots: set[str] = set()
        self._selected_datasets: set[str] = set()

    def select(
        self,
        slot: Mapping[str, Any],
        ticket: Mapping[str, Any],
        *,
        expected_environment_digest: str,
        allow_reuse: bool,
    ) -> dict[str, Any]:
        slot_id = str(slot.get("slotId", ""))
        dataset_run_id = str(ticket.get("datasetRunId", ""))
        if not slot_id or slot_id in self._attempted_slots:
            raise ContractError(
                ReleaseErrorCode.QUALITY_SLOT_ALREADY_ATTEMPTED.value,
                slot_id or "slot",
            )
        self._attempted_slots.add(slot_id)

        reused = dataset_run_id in self._selected_datasets
        if reused and not allow_reuse:
            raise ContractError(
                ReleaseErrorCode.QUALITY_DATASET_REUSE_NOT_APPROVED.value,
                dataset_run_id,
            )
        try:
            manifest = self.validator.validate(self.dataset_root / dataset_run_id)
            self._validate_manifest(manifest, slot, dataset_run_id)
            self._validate_timeline(dataset_run_id)
        except (OSError, ValueError, json.JSONDecodeError, ContractError) as exc:
            if isinstance(exc, ContractError) \
                    and exc.code == ReleaseErrorCode.QUALITY_DATASET_INVALID.value:
                raise
            self._invalid(dataset_run_id, "dataset validation failed", exc)

        try:
            activate_dataset_zone(
                self.dataset_root / dataset_run_id,
                "input",
                self.agent_input_root,
            )
        except (OSError, ContractError) as exc:
            raise ContractError(
                ReleaseErrorCode.QUALITY_DATASET_ACTIVATION_FAILED.value,
                dataset_run_id,
            ) from exc

        try:
            recovery = self.recovery_probe(dataset_run_id)
        except Exception as exc:
            raise ContractError(
                ReleaseErrorCode.QUALITY_ENVIRONMENT_RECOVERY_FAILED.value,
                dataset_run_id,
            ) from exc
        if recovery.get("recovered") is not True or recovery.get("residualFaults") != []:
            raise ContractError(
                ReleaseErrorCode.QUALITY_ENVIRONMENT_RECOVERY_FAILED.value,
                dataset_run_id,
            )
        if recovery.get("environmentDigest") != expected_environment_digest:
            raise ContractError(
                ReleaseErrorCode.QUALITY_ENVIRONMENT_DRIFT.value,
                dataset_run_id,
            )

        self._selected_datasets.add(dataset_run_id)
        return {
            "slotId": slot_id,
            "datasetRunId": dataset_run_id,
            "reused": reused,
            "environmentDigest": expected_environment_digest,
        }

    def _validate_manifest(
        self,
        manifest: Mapping[str, Any],
        slot: Mapping[str, Any],
        dataset_run_id: str,
    ) -> None:
        valid = (
            manifest.get("datasetRunId") == dataset_run_id
            and manifest.get("scenarioId") == slot.get("scenarioId")
            and manifest.get("scenarioVersion") == slot.get("scenarioVersion")
            and all(manifest.get(field) == self.expected_identity[field]
                    for field in _IDENTITY_FIELDS)
        )
        if not valid:
            self._invalid(dataset_run_id, "dataset identity drift")

    def _validate_timeline(self, dataset_run_id: str) -> None:
        timeline = json.loads(
            (self.dataset_root / dataset_run_id / "execution" / "timeline.json")
            .read_text(encoding="utf-8")
        )
        recovery_records = [
            record for record in timeline.get("timeline", [])
            if isinstance(record, Mapping) and record.get("stage") == "RECOVERING"
        ]
        valid = (
            timeline.get("datasetRunId") == dataset_run_id
            and timeline.get("primaryFailure") is None
            and timeline.get("recoveryFailure") is None
            and len(recovery_records) == 1
            and recovery_records[0].get("endedAt") is not None
            and recovery_records[0].get("errorCode") is None
        )
        if not valid:
            self._invalid(dataset_run_id, "dataset recovery evidence")

    @staticmethod
    def _invalid(dataset_run_id: str, detail: str, cause: Exception | None = None) -> None:
        error = ContractError(
            ReleaseErrorCode.QUALITY_DATASET_INVALID.value,
            f"{dataset_run_id}: {detail}",
        )
        if cause is None:
            raise error
        raise error from cause
