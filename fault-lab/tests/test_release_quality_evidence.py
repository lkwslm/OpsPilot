from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.model import ReleaseErrorCode, RunPurpose
from fault_lab.release.ledger import PROFESSIONAL_AGENTS
from fault_lab.release.quality_evidence import (
    REQUIRED_RUN_EVIDENCE,
    PostgresRunEvidenceReader,
    QualityRunEvidenceSealer,
    _execution_integrity,
)


SEAL_ARGUMENTS = {
    "evaluation_profile_id": "mvp-v2",
    "evaluation_profile_digest": "a" * 64,
    "snapshot_digest": "b" * 64,
    "dataset_valid": True,
    "slot_id": "dependency-latency-inventory-01",
    "scenario_id": "dependency-latency-inventory",
    "dataset_run_id": "dataset-01",
    "execution_integrity": {
        "mockUsed": False,
        "hiddenFallbackUsed": False,
        "vectorOnly": False,
        "keywordFallbackUsed": False,
        "fixedResultUsed": False,
        "rerankRequired": True,
        "rerankExecuted": True,
    },
}


def evidence_files(root: Path) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for evidence_type in REQUIRED_RUN_EVIDENCE:
        suffix = ".md" if evidence_type == "rca-markdown" else ".json"
        path = root / f"source-{evidence_type}{suffix}"
        content = "# sealed RCA\n" if suffix == ".md" else json.dumps({
            "evidenceType": evidence_type,
            "runId": "run-01",
        })
        path.write_text(content, encoding="utf-8")
        result[evidence_type] = path
    return result


def evidence_projection() -> tuple[object, ...]:
    tasks = [
        {
            "agentId": agent,
            "taskId": f"task-{agent}",
            "messageId": f"run-01:{agent}:1",
            "contextId": "run-01",
            "state": "COMPLETED",
            "artifact": (
                {
                    "embeddingApplied": True,
                    "references": [{"rerankRank": 1}],
                }
                if agent == "knowledge"
                else {"schemaVersion": "1.0.0"}
            ),
        }
        for agent in PROFESSIONAL_AGENTS
    ]
    return (
        {"schemaVersion": "1.0.0", "citations": []},
        "# RCA",
        {"status": "COMPLETED", "report": {"valid": True}},
        [{"modelCallId": index} for index in range(6)],
        [],
        {"audit": [{"outcome": "SUCCEEDED"}] * 5, "tasks": tasks},
        [{"usageId": index} for index in range(6)],
        [],
        [],
        [],
    )


def test_exports_run_scoped_database_projection_as_fixed_evidence_set(tmp_path: Path) -> None:
    password = tmp_path / "password"
    password.write_text("secret\n", encoding="utf-8")

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_: object) -> None:
            return None

        def execute(self, query: str, parameters: tuple[str]):
            assert "read_release_run_evidence" in query
            assert parameters == ("run-01",)
            return self

        def fetchone(self):
            return evidence_projection()

    def connect(dsn: str, *, user: str, password: str):
        assert dsn == "postgresql://postgres:5432/opspilot"
        assert user == "fault_lab_login"
        assert password == "secret"
        return Connection()

    exported = PostgresRunEvidenceReader(
        "jdbc:postgresql://postgres:5432/opspilot",
        "fault_lab_login",
        password,
        connector=connect,
    ).export("run-01", tmp_path / "raw")

    assert set(exported["artifacts"]) == set(REQUIRED_RUN_EVIDENCE)
    assert exported["executionIntegrity"] == SEAL_ARGUMENTS["execution_integrity"]
    assert all(path.is_file() for path in exported["artifacts"].values())


def test_accepts_visible_failed_retry_with_exact_completed_agent_set() -> None:
    projection = evidence_projection()
    a2a = projection[5]
    assert isinstance(a2a, dict)
    a2a["tasks"].append({
        "agentId": "evidence-collector",
        "taskId": "task-evidence-collector-failed",
        "messageId": "run-01:evidence-collector:1",
        "contextId": "run-01",
        "state": "FAILED",
        "artifact": None,
    })

    assert _execution_integrity(a2a) == SEAL_ARGUMENTS["execution_integrity"]


def test_seals_complete_run_evidence_with_uri_size_and_sha256(tmp_path: Path) -> None:
    sources = evidence_files(tmp_path)
    output = tmp_path / "sealed"

    manifest = QualityRunEvidenceSealer(output).seal(
        "batch-01", "run-01", sources, **SEAL_ARGUMENTS,
    )

    assert manifest["runPurpose"] == RunPurpose.RELEASE_QUALITY.value
    assert [item["evidenceType"] for item in manifest["artifacts"]] == list(REQUIRED_RUN_EVIDENCE)
    assert all(item["uri"].startswith("artifact://phase8/batch-01/run-01/")
               for item in manifest["artifacts"])
    for item in manifest["artifacts"]:
        sealed = output / "batch-01" / "run-01" / item["uri"].rsplit("/", 1)[1]
        assert item["size"] == sealed.stat().st_size
        assert item["sha256"] == hashlib.sha256(sealed.read_bytes()).hexdigest()
    persisted = json.loads(
        (output / "batch-01" / "run-01" / "evidence-manifest.json")
        .read_text(encoding="utf-8")
    )
    assert persisted == manifest


def test_sealed_copy_is_not_changed_when_source_changes(tmp_path: Path) -> None:
    sources = evidence_files(tmp_path)
    output = tmp_path / "sealed"
    manifest = QualityRunEvidenceSealer(output).seal(
        "batch-01", "run-01", sources, **SEAL_ARGUMENTS,
    )

    sources["evaluation"].chmod(0o666)
    sources["evaluation"].write_text('{"changed":true}', encoding="utf-8")

    item = next(item for item in manifest["artifacts"] if item["evidenceType"] == "evaluation")
    sealed = output / "batch-01" / "run-01" / "evaluation.json"
    assert hashlib.sha256(sealed.read_bytes()).hexdigest() == item["sha256"]


def test_rejects_missing_evidence_without_publishing_partial_directory(tmp_path: Path) -> None:
    sources = evidence_files(tmp_path)
    del sources["citations"]
    output = tmp_path / "sealed"

    with pytest.raises(ContractError) as invalid:
        QualityRunEvidenceSealer(output).seal(
            "batch-01", "run-01", sources, **SEAL_ARGUMENTS,
        )

    assert invalid.value.code == ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value
    assert not (output / "batch-01" / "run-01").exists()


def test_rejects_overwrite_of_sealed_run(tmp_path: Path) -> None:
    sources = evidence_files(tmp_path)
    sealer = QualityRunEvidenceSealer(tmp_path / "sealed")
    sealer.seal("batch-01", "run-01", sources, **SEAL_ARGUMENTS)

    with pytest.raises(ContractError) as exists:
        sealer.seal("batch-01", "run-01", sources, **SEAL_ARGUMENTS)

    assert exists.value.code == ReleaseErrorCode.QUALITY_EVIDENCE_EXISTS.value
