from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.ledger import RunLedger
from fault_lab.release.model import ReleaseErrorCode, ReleaseStatus, RunPurpose


PROFESSIONAL_AGENTS = (
    "evidence-collector",
    "code-analysis",
    "knowledge",
    "diagnosis",
    "remediation",
)


def a2a_tasks(run_id: str) -> list[dict[str, str]]:
    return [
        {
            "agentId": agent_id,
            "a2aTaskId": f"task-{run_id}-{agent_id}",
            "messageId": f"{run_id}:{agent_id}:1",
            "agentScopeSessionId": f"{agent_id}:task-{run_id}-{agent_id}",
        }
        for agent_id in PROFESSIONAL_AGENTS
    ]


def record(
    run_id: str = "run-01",
    *,
    dataset_run_id: str = "dataset-01",
    incident_id: str | None = None,
    a2a_context_id: str | None = None,
    supervisor_session_id: str | None = None,
    tasks: list[dict[str, str]] | None = None,
    environment_digest: str = "a" * 64,
    status: str = ReleaseStatus.PASSED.value,
    reason: dict[str, str] | None = None,
) -> dict[str, Any]:
    return {
        "releaseBatchId": "phase8-release-01",
        "runPurpose": RunPurpose.RELEASE_QUALITY.value,
        "runIdentity": {
            "incidentId": incident_id or f"incident-{run_id}",
            "runId": run_id,
            "datasetRunId": dataset_run_id,
            "a2aContextId": a2a_context_id or run_id,
            "supervisorSessionId": supervisor_session_id or f"supervisor:product-{run_id}",
            "a2aTasks": a2a_tasks(run_id) if tasks is None else tasks,
            "attempt": 1,
        },
        "startedAt": "2026-08-01T01:02:03.000Z",
        "endedAt": "2026-08-01T01:03:04.000Z",
        "environmentDigest": environment_digest,
        "status": status,
        "reason": reason,
    }


def artifact(tmp_path: Path, name: str = "rca.json") -> tuple[str, Path]:
    path = tmp_path / name
    path.write_text('{"rootCause":"pool exhaustion"}\n', encoding="utf-8")
    return f"artifact://phase8/{name}", path


def test_appends_run_with_artifact_manifest_and_atomic_index(tmp_path: Path) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)

    appended = ledger.append(record(), {uri: path})

    content = path.read_bytes()
    assert appended["artifactManifest"]["artifacts"] == [
        {
            "uri": uri,
            "size": len(content),
            "sha256": hashlib.sha256(content).hexdigest(),
        }
    ]
    assert len(appended["artifactManifest"]["manifestDigest"]) == 64
    assert len(appended["entryDigest"]) == 64
    assert ledger.entries() == [appended]
    index = json.loads((ledger.root / "index.json").read_text(encoding="utf-8"))
    assert index["entryCount"] == 1
    assert index["releaseBatches"] == {"phase8-release-01": ["run-01"]}
    assert len(index["ledgerDigest"]) == 64
    assert not list(ledger.root.glob("*.tmp"))


def test_rejects_duplicate_identity_and_existing_entry(tmp_path: Path) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)
    ledger.append(record(), {uri: path})

    with pytest.raises(ContractError) as existing:
        ledger.append(record(), {uri: path})
    assert existing.value.code == ReleaseErrorCode.LEDGER_ENTRY_EXISTS.value

    duplicate_context = record(
        "run-02",
        supervisor_session_id="supervisor:product-run-01",
    )
    with pytest.raises(ContractError) as duplicate:
        ledger.append(duplicate_context, {uri: path})
    assert duplicate.value.code == ReleaseErrorCode.LEDGER_IDENTITY_DUPLICATE.value
    assert ledger.entries()[0]["runIdentity"]["runId"] == "run-01"


def test_rejects_conflicting_digest_for_same_run(tmp_path: Path) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)
    ledger.append(record(), {uri: path})

    with pytest.raises(ContractError) as conflict:
        ledger.append(record(environment_digest="b" * 64), {uri: path})

    assert conflict.value.code == ReleaseErrorCode.LEDGER_RUN_CONFLICT.value
    assert len(ledger.entries()) == 1


def test_allows_dataset_reuse_with_independent_contexts(tmp_path: Path) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)
    ledger.append(record(), {uri: path})
    second = record(
        "run-02",
        dataset_run_id="dataset-01",
    )

    ledger.append(second, {uri: path})

    identities = [entry["runIdentity"] for entry in ledger.entries()]
    assert [identity["datasetRunId"] for identity in identities] == ["dataset-01"] * 2
    assert len({identity["a2aContextId"] for identity in identities}) == 2
    assert len({identity["supervisorSessionId"] for identity in identities}) == 2
    assert len({task["a2aTaskId"] for identity in identities for task in identity["a2aTasks"]}) == 10
    assert len({task["agentScopeSessionId"] for identity in identities for task in identity["a2aTasks"]}) == 10


def test_baseline_records_unassigned_root_a2a_identity_as_null(tmp_path: Path) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)
    baseline = record()
    baseline["runPurpose"] = RunPurpose.BASELINE_ONLY.value
    baseline["runIdentity"] |= {
        "incidentId": "incident-run-01",
        "a2aContextId": None,
        "supervisorSessionId": None,
        "a2aTasks": [],
    }

    appended = ledger.append(baseline, {uri: path})

    assert appended["runIdentity"]["a2aContextId"] is None
    assert appended["runIdentity"]["supervisorSessionId"] is None
    assert appended["runIdentity"]["a2aTasks"] == []


@pytest.mark.parametrize(
    "mutate",
    [
        lambda tasks: tasks.pop(),
        lambda tasks: tasks.__setitem__(4, dict(tasks[3])),
        lambda tasks: tasks.__setitem__(0, {**tasks[0], "agentId": "supervisor"}),
        lambda tasks: tasks.__setitem__(1, {**tasks[1], "a2aTaskId": tasks[0]["a2aTaskId"]}),
        lambda tasks: tasks.__setitem__(1, {**tasks[1], "messageId": tasks[0]["messageId"]}),
        lambda tasks: tasks.__setitem__(1, {**tasks[1], "agentScopeSessionId": tasks[0]["agentScopeSessionId"]}),
    ],
)
def test_rejects_incomplete_or_duplicate_professional_identity(
    tmp_path: Path, mutate: Any,
) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)
    tasks = a2a_tasks("run-01")
    mutate(tasks)

    with pytest.raises(ContractError) as invalid:
        ledger.append(record(tasks=tasks), {uri: path})

    assert invalid.value.code == ReleaseErrorCode.LEDGER_ENTRY_INVALID.value


def test_rejects_professional_identity_reused_by_another_run(tmp_path: Path) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)
    first = record()
    ledger.append(first, {uri: path})
    second_tasks = a2a_tasks("run-02")
    second_tasks[0] = {
        **second_tasks[0],
        "a2aTaskId": first["runIdentity"]["a2aTasks"][0]["a2aTaskId"],
        "agentScopeSessionId": first["runIdentity"]["a2aTasks"][0]["agentScopeSessionId"],
    }

    with pytest.raises(ContractError) as duplicate:
        ledger.append(record("run-02", tasks=second_tasks), {uri: path})

    assert duplicate.value.code == ReleaseErrorCode.LEDGER_IDENTITY_DUPLICATE.value


def test_rejects_professional_session_in_supervisor_slot(tmp_path: Path) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)

    with pytest.raises(ContractError) as invalid:
        ledger.append(record(supervisor_session_id="diagnosis:task-01"), {uri: path})

    assert invalid.value.code == ReleaseErrorCode.LEDGER_ENTRY_INVALID.value


def test_preserves_identity_and_reason_when_environment_restore_is_blocked(
    tmp_path: Path,
) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)
    blocked = record(
        status=ReleaseStatus.BLOCKED.value,
        reason={
            "errorCode": ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value,
            "detail": "environment recovery failed",
        },
    )

    appended = ledger.append(blocked, {uri: path})

    assert appended["runIdentity"] == blocked["runIdentity"]
    assert appended["status"] == ReleaseStatus.BLOCKED.value
    assert appended["reason"] == blocked["reason"]


def test_interrupted_replace_does_not_publish_half_entry(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    ledger = RunLedger(tmp_path / "run-ledger")
    uri, path = artifact(tmp_path)
    first = ledger.append(record(), {uri: path})
    import fault_lab.release.ledger as ledger_module

    original_replace = ledger_module.os.replace
    interrupted = False

    def interrupt_once(source: Path, target: Path) -> None:
        nonlocal interrupted
        if not interrupted and Path(target) == ledger.ledger_path:
            interrupted = True
            raise OSError("simulated process interruption")
        original_replace(source, target)

    monkeypatch.setattr("fault_lab.release.ledger.os.replace", interrupt_once)
    second = record(
        "run-02",
    )

    with pytest.raises(OSError, match="simulated process interruption"):
        ledger.append(second, {uri: path})

    assert ledger.entries() == [first]
    assert not list(ledger.root.glob("*.tmp"))
