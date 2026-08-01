from __future__ import annotations

import uuid
from pathlib import Path
from typing import Any

import pytest

from fault_lab.contracts import ContractError
from fault_lab.dataset import DatasetWriter
from fault_lab.release.model import ReleaseErrorCode, RunPurpose
from fault_lab.release.quality_dataset import QualityDatasetSelector


ENVIRONMENT_DIGEST = "a" * 64
IDENTITY = {
    "gitCommit": "b" * 40,
    "composeDigest": "c" * 64,
    "modelConfigDigest": "d" * 64,
}


def slot(ordinal: int = 1) -> dict[str, Any]:
    return {
        "slotId": f"dependency-latency-inventory:{ordinal:02d}",
        "scenarioId": "dependency-latency-inventory",
        "scenarioVersion": "1.0.0",
        "ordinal": ordinal,
        "runPurpose": RunPurpose.RELEASE_QUALITY.value,
        "snapshotDigest": ENVIRONMENT_DIGEST,
        "status": "PLANNED",
    }


def publish_dataset(
    root: Path,
    *,
    recovery_failure: str | None = None,
    observability: bytes = b'{"evidence":"dependency-latency"}\n',
) -> str:
    dataset_run_id = str(uuid.uuid4())
    writer = DatasetWriter(root)
    staging = writer.begin(dataset_run_id)
    timeline = {
        "datasetRunId": dataset_run_id,
        "status": "RECOVERING" if recovery_failure is None else "FAILED",
        "primaryFailure": None,
        "recoveryFailure": recovery_failure,
        "timeline": [{
            "stage": "RECOVERING",
            "startedAt": "2026-08-01T00:03:00Z",
            "endedAt": "2026-08-01T00:04:00Z",
            "inputDigest": "e" * 64,
            "artifacts": [],
            "errorCode": recovery_failure,
        }],
    }
    artifacts = [
        writer.write_json(staging, "input", "ticket.json", {
            "datasetRunId": dataset_run_id,
            "scenarioId": "dependency-latency-inventory",
        }),
        writer.write_bytes(staging, "input", "observability.jsonl", observability),
        writer.write_json(staging, "execution", "timeline.json", timeline),
    ]
    manifest = {
        "schemaVersion": "1.0.0",
        "datasetRunId": dataset_run_id,
        "scenarioId": "dependency-latency-inventory",
        "scenarioVersion": "1.0.0",
        **IDENTITY,
        "windows": {
            "baseline": {"start": "2026-08-01T00:00:00Z", "end": "2026-08-01T00:01:00Z"},
            "fault": {"start": "2026-08-01T00:01:00Z", "end": "2026-08-01T00:03:00Z"},
            "recovery": {"start": "2026-08-01T00:03:00Z", "end": "2026-08-01T00:04:00Z"},
        },
        "artifacts": [artifact.__dict__ for artifact in artifacts],
    }
    writer.publish(staging, manifest)
    return dataset_run_id


def ticket(dataset_run_id: str) -> dict[str, str]:
    return {
        "datasetRunId": dataset_run_id,
        "scenarioId": "dependency-latency-inventory",
        "scenarioVersion": "1.0.0",
    }


def recovered(_: str) -> dict[str, Any]:
    return {
        "recovered": True,
        "residualFaults": [],
        "environmentDigest": ENVIRONMENT_DIGEST,
    }


def test_explicitly_reuses_one_valid_dataset_for_independent_slots(tmp_path: Path) -> None:
    dataset_run_id = publish_dataset(tmp_path)
    selector = QualityDatasetSelector(
        tmp_path,
        IDENTITY,
        recovered,
        agent_input_root=tmp_path / "agent-input",
    )

    first = selector.select(
        slot(1), ticket(dataset_run_id),
        expected_environment_digest=ENVIRONMENT_DIGEST,
        allow_reuse=False,
    )
    second = selector.select(
        slot(2), ticket(dataset_run_id),
        expected_environment_digest=ENVIRONMENT_DIGEST,
        allow_reuse=True,
    )

    assert first["reused"] is False
    assert second["reused"] is True


def test_rejects_unapproved_dataset_reuse(tmp_path: Path) -> None:
    dataset_run_id = publish_dataset(tmp_path)
    selector = QualityDatasetSelector(
        tmp_path,
        IDENTITY,
        recovered,
        agent_input_root=tmp_path / "agent-input",
    )
    selector.select(
        slot(1), ticket(dataset_run_id),
        expected_environment_digest=ENVIRONMENT_DIGEST,
        allow_reuse=False,
    )

    with pytest.raises(ContractError) as invalid:
        selector.select(
            slot(2), ticket(dataset_run_id),
            expected_environment_digest=ENVIRONMENT_DIGEST,
            allow_reuse=False,
        )

    assert invalid.value.code == ReleaseErrorCode.QUALITY_DATASET_REUSE_NOT_APPROVED.value


def test_failed_dataset_slot_cannot_be_silently_retried(tmp_path: Path) -> None:
    dataset_run_id = publish_dataset(
        tmp_path,
        recovery_failure="ENVIRONMENT_RECOVERY_INCOMPLETE",
    )
    selector = QualityDatasetSelector(
        tmp_path,
        IDENTITY,
        recovered,
        agent_input_root=tmp_path / "agent-input",
    )

    with pytest.raises(ContractError) as invalid:
        selector.select(
            slot(), ticket(dataset_run_id),
            expected_environment_digest=ENVIRONMENT_DIGEST,
            allow_reuse=False,
        )
    assert invalid.value.code == ReleaseErrorCode.QUALITY_DATASET_INVALID.value

    with pytest.raises(ContractError) as repeated:
        selector.select(
            slot(), ticket(dataset_run_id),
            expected_environment_digest=ENVIRONMENT_DIGEST,
            allow_reuse=False,
        )
    assert repeated.value.code == ReleaseErrorCode.QUALITY_SLOT_ALREADY_ATTEMPTED.value


@pytest.mark.parametrize(
    ("probe", "expected_code"),
    [
        (
            lambda _: {"recovered": False, "residualFaults": ["toxic"],
                       "environmentDigest": ENVIRONMENT_DIGEST},
            ReleaseErrorCode.QUALITY_ENVIRONMENT_RECOVERY_FAILED,
        ),
        (
            lambda _: {"recovered": True, "residualFaults": [],
                       "environmentDigest": "f" * 64},
            ReleaseErrorCode.QUALITY_ENVIRONMENT_DRIFT,
        ),
    ],
)
def test_rejects_recovery_failure_or_environment_drift(
    tmp_path: Path,
    probe: Any,
    expected_code: ReleaseErrorCode,
) -> None:
    dataset_run_id = publish_dataset(tmp_path)
    selector = QualityDatasetSelector(
        tmp_path,
        IDENTITY,
        probe,
        agent_input_root=tmp_path / "agent-input",
    )

    with pytest.raises(ContractError) as invalid:
        selector.select(
            slot(), ticket(dataset_run_id),
            expected_environment_digest=ENVIRONMENT_DIGEST,
            allow_reuse=False,
        )

    assert invalid.value.code == expected_code.value


def test_activates_each_selected_dataset_before_recovery_probe(tmp_path: Path) -> None:
    first_run_id = publish_dataset(tmp_path, observability=b'{"evidence":"first"}\n')
    second_run_id = publish_dataset(tmp_path, observability=b'{"evidence":"second"}\n')
    agent_input_root = tmp_path / "agent-input"
    expected = {
        first_run_id: b'{"evidence":"first"}\n',
        second_run_id: b'{"evidence":"second"}\n',
    }

    def probe(dataset_run_id: str) -> dict[str, Any]:
        assert (agent_input_root / "current" / "observability.jsonl").read_bytes() \
            == expected[dataset_run_id]
        return recovered(dataset_run_id)

    selector = QualityDatasetSelector(
        tmp_path,
        IDENTITY,
        probe,
        agent_input_root=agent_input_root,
    )

    selector.select(
        slot(1), ticket(first_run_id),
        expected_environment_digest=ENVIRONMENT_DIGEST,
        allow_reuse=False,
    )
    selector.select(
        slot(2), ticket(second_run_id),
        expected_environment_digest=ENVIRONMENT_DIGEST,
        allow_reuse=False,
    )

    assert (agent_input_root / "current" / "observability.jsonl").read_bytes() \
        == expected[second_run_id]
    assert (agent_input_root / first_run_id / "observability.jsonl").read_bytes() \
        == expected[first_run_id]
    assert (agent_input_root / second_run_id / "observability.jsonl").read_bytes() \
        == expected[second_run_id]


def test_blocks_slot_when_dataset_input_cannot_be_activated(tmp_path: Path) -> None:
    dataset_run_id = publish_dataset(tmp_path)
    agent_input_root = tmp_path / "agent-input"
    agent_input_root.write_text("not a directory", encoding="utf-8")
    selector = QualityDatasetSelector(
        tmp_path,
        IDENTITY,
        recovered,
        agent_input_root=agent_input_root,
    )

    with pytest.raises(ContractError) as invalid:
        selector.select(
            slot(), ticket(dataset_run_id),
            expected_environment_digest=ENVIRONMENT_DIGEST,
            allow_reuse=False,
        )

    assert invalid.value.code == ReleaseErrorCode.QUALITY_DATASET_ACTIVATION_FAILED.value
