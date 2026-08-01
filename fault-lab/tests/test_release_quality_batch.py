from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from fault_lab.contracts import ContractError
from fault_lab.release.model import ReleaseErrorCode, ReleaseStatus
from fault_lab.release.quality import FROZEN_SCENARIOS
from fault_lab.release.quality_batch import QualityBatchRunner


def plan() -> dict[str, Any]:
    slots = [
        {
            "slotId": f"{scenario_id}:{ordinal:02d}",
            "scenarioId": scenario_id,
            "scenarioVersion": version,
            "ordinal": ordinal,
        }
        for scenario_id, version in FROZEN_SCENARIOS
        for ordinal in range(1, 6)
    ]
    return {
        "releaseBatchId": "batch-01",
        "snapshotDigest": "a" * 64,
        "evaluationProfileId": "mvp-v2",
        "totalSlots": 15,
        "slots": slots,
    }


def inputs(tmp_path: Path) -> tuple[Path, dict[str, str]]:
    datasets = tmp_path / "datasets"
    mapping: dict[str, str] = {}
    for scenario_id, version in FROZEN_SCENARIOS:
        dataset_id = f"dataset-{scenario_id}"
        mapping[scenario_id] = dataset_id
        ticket = datasets / dataset_id / "input" / "ticket.json"
        ticket.parent.mkdir(parents=True)
        ticket.write_text(json.dumps({
            "datasetRunId": dataset_id,
            "scenarioId": scenario_id,
            "scenarioVersion": version,
        }), encoding="utf-8")
    return datasets, mapping


class Evidence:
    def collect(self, *_: Any) -> dict[str, Path]:
        return {}


def test_executes_exact_plan_order_without_replacements(tmp_path: Path) -> None:
    calls: list[str] = []

    class Executor:
        def execute_slot(self, _batch: str, slot: dict[str, Any], _ticket: dict[str, Any], **_: Any):
            calls.append(slot["slotId"])
            number = len(calls)
            return {
                "runIdentity": {"incidentId": f"incident-{number}", "runId": f"run-{number}"},
                "entryDigest": f"digest-{number}",
                "status": "PASSED",
            }

    datasets, mapping = inputs(tmp_path)
    report = QualityBatchRunner(Executor(), Evidence(), tmp_path / "out").execute(
        plan(),
        {"snapshotDigest": "a" * 64},
        {"profile_id": "mvp-v2", "efficiency_limits": {"total_tokens": 65536}},
        datasets,
        mapping,
    )

    assert report["status"] == ReleaseStatus.PASSED.value
    assert report["completedRuns"] == 15
    assert report["replacementRuns"] == 0
    assert calls == [slot["slotId"] for slot in plan()["slots"]]


def test_stops_at_first_failed_slot_and_does_not_replace_it(tmp_path: Path) -> None:
    calls: list[str] = []

    class Executor:
        def execute_slot(self, _batch: str, slot: dict[str, Any], _ticket: dict[str, Any], **_: Any):
            calls.append(slot["slotId"])
            if len(calls) == 3:
                raise ContractError(ReleaseErrorCode.QUALITY_ENVIRONMENT_DRIFT.value, slot["slotId"])
            return {
                "runIdentity": {"incidentId": f"incident-{len(calls)}", "runId": f"run-{len(calls)}"},
                "entryDigest": f"digest-{len(calls)}",
                "status": "PASSED",
            }

    datasets, mapping = inputs(tmp_path)
    report = QualityBatchRunner(Executor(), Evidence(), tmp_path / "out").execute(
        plan(),
        {"snapshotDigest": "a" * 64},
        {"profile_id": "mvp-v2", "efficiency_limits": {"total_tokens": 65536}},
        datasets,
        mapping,
    )

    assert report["status"] == ReleaseStatus.FAILED.value
    assert report["completedRuns"] == 2
    assert report["attemptedSlot"] == plan()["slots"][2]["slotId"]
    assert report["replacementRuns"] == 0
    assert len(calls) == 3
