from __future__ import annotations

import json
import uuid
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.dataset import DatasetValidator, DatasetWriter, manifest_for
from fault_lab.model import ExecutionContext


def manifest(run_id: str, artifacts: list[object]) -> dict[str, object]:
    return {
        "datasetRunId": run_id,
        "windows": {
            "baseline": {"start": "2026-07-29T00:00:00Z", "end": "2026-07-29T00:01:00Z"},
            "fault": {"start": "2026-07-29T00:01:00Z", "end": "2026-07-29T00:03:00Z"},
            "recovery": {"start": "2026-07-29T00:03:00Z", "end": "2026-07-29T00:04:00Z"},
        },
        "artifacts": [artifact if isinstance(artifact, dict) else artifact.__dict__
                      for artifact in artifacts],
    }


def test_dataset_publish_and_hash_validation(tmp_path: Path) -> None:
    writer = DatasetWriter(tmp_path)
    run_id = str(uuid.uuid4())
    staging = writer.begin(run_id)
    artifacts = [
        writer.write_json(staging, "input", "ticket.json", {"datasetRunId": run_id}),
        writer.write_json(staging, "ground-truth", "truth.json", {"scenarioId": "x"}),
        writer.write_json(staging, "execution", "timeline.json", {"status": "COMPLETED"}),
    ]
    published = writer.publish(staging, manifest(run_id, artifacts))
    assert DatasetValidator().validate(published)["datasetRunId"] == run_id
    assert not (published / "input" / "ticket.json").stat().st_mode & 0o200


def test_hash_tampering_and_restricted_path_exposure_fail(tmp_path: Path) -> None:
    writer = DatasetWriter(tmp_path)
    run_id = str(uuid.uuid4())
    staging = writer.begin(run_id)
    artifact = writer.write_bytes(staging, "input", "bad.txt", b"../ground-truth/truth.json")
    (staging / "dataset-manifest.json").write_text(json.dumps(manifest(run_id, [artifact])), encoding="utf-8")
    with pytest.raises(ContractError, match="DATASET_RESTRICTED_PATH_EXPOSED"):
        DatasetValidator().validate(staging)

    run_id = str(uuid.uuid4())
    staging = writer.begin(run_id)
    artifact = writer.write_bytes(staging, "input", "observation.json", b'{}')
    (staging / "dataset-manifest.json").write_text(
        json.dumps(manifest(run_id, [artifact])), encoding="utf-8",
    )
    (staging / "input" / "observation.json").write_bytes(b'{"tampered":true}')
    with pytest.raises(ContractError, match="DATASET_ARTIFACT_HASH_INVALID"):
        DatasetValidator().validate(staging)


def test_writer_rejects_path_traversal_before_file_creation(tmp_path: Path) -> None:
    writer = DatasetWriter(tmp_path)
    staging = writer.begin(str(uuid.uuid4()))

    with pytest.raises(ContractError, match="DATASET_PATH_INVALID"):
        writer.write_bytes(staging, "input", "../ground-truth/truth.json", b"secret")
    assert not (staging / "ground-truth" / "truth.json").exists()


def test_window_overlap_fails(tmp_path: Path) -> None:
    writer = DatasetWriter(tmp_path)
    run_id = str(uuid.uuid4())
    staging = writer.begin(run_id)
    artifact = writer.write_bytes(staging, "input", "ok.txt", b"ok")
    value = manifest(run_id, [artifact])
    value["windows"]["fault"]["start"] = "2026-07-29T00:00:30Z"
    (staging / "dataset-manifest.json").write_text(json.dumps(value), encoding="utf-8")
    with pytest.raises(ContractError, match="DATASET_WINDOW_INVALID"):
        DatasetValidator().validate(staging)


def test_cross_run_json_and_symlink_are_rejected(tmp_path: Path) -> None:
    writer = DatasetWriter(tmp_path)
    run_id = str(uuid.uuid4())
    staging = writer.begin(run_id)
    artifact = writer.write_json(staging, "input", "ticket.json", {"datasetRunId": str(uuid.uuid4())})
    (staging / "dataset-manifest.json").write_text(json.dumps(manifest(run_id, [artifact])), encoding="utf-8")
    with pytest.raises(ContractError, match="DATASET_RUN_ID_MISMATCH"):
        DatasetValidator().validate(staging)

    if hasattr(Path, "symlink_to"):
        run_id = str(uuid.uuid4())
        staging = writer.begin(run_id)
        external = tmp_path / "external.txt"
        external.write_text("secret", encoding="utf-8")
        link = staging / "input" / "linked.txt"
        try:
            link.symlink_to(external)
        except OSError:
            return
        artifact = {"zone": "input", "relative_path": "linked.txt", "sha256": "0" * 64, "size": 6}
        (staging / "dataset-manifest.json").write_text(json.dumps(manifest(run_id, [artifact])), encoding="utf-8")
        with pytest.raises(ContractError, match="DATASET_SYMLINK_FORBIDDEN"):
            DatasetValidator().validate(staging)


def test_same_environment_manifest_diff_is_limited_to_run_identity(tmp_path: Path) -> None:
    scenario = {"scenarioId": "dependency-latency-inventory", "scenarioVersion": "1.0.0"}
    first = ExecutionContext(str(uuid.uuid4()), 101, scenario, tmp_path)
    second = ExecutionContext(str(uuid.uuid4()), 101, scenario, tmp_path)
    fixed = {
        "git_commit": "a" * 40,
        "compose_digest": "b" * 64,
        "image_digests": {"order": "sha256:" + "c" * 64},
        "model_config_digest": "d" * 64,
        "windows": manifest(first.dataset_run_id, [])["windows"],
    }

    first_manifest = manifest_for(first, **fixed)
    second_manifest = manifest_for(second, **fixed)
    assert first_manifest["datasetRunId"] != second_manifest["datasetRunId"]
    assert {key: value for key, value in first_manifest.items() if key != "datasetRunId"} == {
        key: value for key, value in second_manifest.items() if key != "datasetRunId"
    }
