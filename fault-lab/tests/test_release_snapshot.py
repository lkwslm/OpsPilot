from __future__ import annotations

from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release import ReleaseErrorCode
from fault_lab.release.snapshot import SnapshotCollector, SnapshotConfig


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class Hardware:
    def collect(self) -> dict[str, object]:
        return {
            "os": {"system": "test-os", "release": "1", "machine": "x86_64"},
            "cpu": {"model": "test-cpu", "logicalCores": 8},
            "memory": {"totalBytes": 16 * 1024**3},
            "gpu": [{"name": "test-gpu", "driverVersion": "1", "memoryMiB": 4096}],
        }


class SensitiveHardware(Hardware):
    def collect(self) -> dict[str, object]:
        result = super().collect()
        result["diagnostics"] = {
            "secret": "release-secret-canary",
            "connectionRef": "connection-ref-canary",
        }
        return result


class FailingHardware:
    def collect(self) -> dict[str, object]:
        raise OSError("hardware unavailable")


def config() -> SnapshotConfig:
    return SnapshotConfig(
        repository_root=REPOSITORY_ROOT,
        evaluation_profile=Path("docs/design/contracts/profiles/mvp-v1.yaml"),
        temperature=0,
        knowledge_collection_id="release-knowledge",
        knowledge_collection_revision="knowledge-revision-1",
        image_digests={
            "infinity": "sha256:" + "1" * 64,
            "java-runtime": "sha256:" + "2" * 64,
            "postgres-pgvector": "sha256:" + "3" * 64,
        },
    )


def test_snapshot_collects_all_frozen_release_identity() -> None:
    snapshot = SnapshotCollector(Hardware()).collect(config())

    assert len(snapshot["commit"]) == 40
    assert snapshot["temperature"] == 0
    assert [agent["role"] for agent in snapshot["agents"]] == [
        "CODE_ANALYSIS",
        "DIAGNOSIS",
        "EVIDENCE_COLLECTOR",
        "KNOWLEDGE",
        "REMEDIATION",
        "SUPERVISOR",
    ]
    assert all(len(agent["profileDigest"]) == 64 for agent in snapshot["agents"])
    assert all(len(agent["promptDigests"]) == 2 for agent in snapshot["agents"])
    assert [model["capability"] for model in snapshot["models"]] == ["CHAT", "EMBEDDING", "RERANK"]
    assert snapshot["knowledgeCollection"] == {
        "collectionId": "release-knowledge",
        "activeRevision": "knowledge-revision-1",
    }
    assert [item["scenarioVersion"] for item in snapshot["scenarios"]] == ["1.0.0"] * 3
    assert snapshot["evaluationProfile"]["profileId"] == "mvp-v1"
    assert snapshot["compose"]["images"] == [
        {"imageId": "infinity", "digest": "sha256:" + "1" * 64},
        {"imageId": "java-runtime", "digest": "sha256:" + "2" * 64},
        {"imageId": "postgres-pgvector", "digest": "sha256:" + "3" * 64},
    ]
    assert snapshot["datasetIdentity"] == {
        "gitCommit": snapshot["commit"],
        "composeDigest": snapshot["compose"]["digest"],
        "modelConfigDigest": snapshot["modelConfigDigest"],
    }
    assert len(snapshot["modelConfigDigest"]) == 64
    assert snapshot["hardware"]["cpu"]["model"] == "test-cpu"
    assert len(snapshot["snapshotDigest"]) == 64


def test_snapshot_digest_is_deterministic() -> None:
    collector = SnapshotCollector(Hardware())

    first = collector.collect(config())
    second = collector.collect(config())

    assert first == second


def test_dataset_identity_uses_the_same_frozen_compose_and_model_digests() -> None:
    snapshot = SnapshotCollector(Hardware()).collect(config())

    assert snapshot["datasetIdentity"]["composeDigest"] == snapshot["compose"]["digest"]
    assert snapshot["datasetIdentity"]["modelConfigDigest"] == snapshot["modelConfigDigest"]


def test_snapshot_redacts_secret_and_connection_ref() -> None:
    snapshot = SnapshotCollector(SensitiveHardware()).collect(config())
    serialized = str(snapshot)

    assert "release-secret-canary" not in serialized
    assert "connection-ref-canary" not in serialized
    diagnostics = snapshot["hardware"]["diagnostics"]
    assert diagnostics["secret"].startswith("redacted:sha256:")
    assert diagnostics["connectionRef"].startswith("redacted:sha256:")


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("knowledge_collection_id", ""),
        ("knowledge_collection_revision", ""),
    ],
)
def test_snapshot_rejects_missing_frozen_field(field: str, value: str) -> None:
    values = vars(config()) | {field: value}

    with pytest.raises(ContractError, match=ReleaseErrorCode.SNAPSHOT_FIELD_MISSING.value):
        SnapshotCollector(Hardware()).collect(SnapshotConfig(**values))


def test_snapshot_rejects_nonzero_temperature() -> None:
    values = vars(config()) | {"temperature": 0.1}

    with pytest.raises(ContractError, match=ReleaseErrorCode.TEMPERATURE_INVALID.value):
        SnapshotCollector(Hardware()).collect(SnapshotConfig(**values))


def test_snapshot_rejects_unknown_model_revision(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = (REPOSITORY_ROOT / "deployment/versions.lock.yaml").read_text(encoding="utf-8")
    lock = tmp_path / "versions.lock.yaml"
    lock.write_text(source.replace('revision: "deepseek-v4-flash"', 'revision: "unknown"'), encoding="utf-8")
    monkeypatch.setattr("fault_lab.release.snapshot.MODEL_LOCK", lock)

    with pytest.raises(ContractError, match=ReleaseErrorCode.MODEL_REVISION_INVALID.value):
        SnapshotCollector(Hardware()).collect(config())


def test_snapshot_rejects_invalid_image_digest() -> None:
    values = vars(config()) | {"image_digests": {"infinity": "latest"}}

    with pytest.raises(ContractError, match=ReleaseErrorCode.IMAGE_DIGEST_INVALID.value):
        SnapshotCollector(Hardware()).collect(SnapshotConfig(**values))


def test_snapshot_fails_closed_when_hardware_collection_fails() -> None:
    with pytest.raises(ContractError, match=ReleaseErrorCode.HARDWARE_COLLECTION_FAILED.value):
        SnapshotCollector(FailingHardware()).collect(config())
