from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pytest

from fault_lab.contracts import ContractError
from fault_lab.product import ProductRun
from fault_lab.release.ledger import PROFESSIONAL_AGENTS, RunLedger
from fault_lab.release.model import ReleaseErrorCode, RunPurpose
from fault_lab.release.quality_run import (
    PostgresRunIdentityReader,
    QualityRunExecutor,
    RunIdentityFacts,
    _parse_identity_rows,
)


STARTED = datetime(2026, 8, 1, 1, 2, 3, tzinfo=timezone.utc)
ENDED = datetime(2026, 8, 1, 1, 3, 4, tzinfo=timezone.utc)


def rows(run_id: str = "run-01", product_task_id: str = "product-01") -> list[tuple[Any, ...]]:
    return [
        (
            "COMPLETED",
            STARTED,
            ENDED,
            product_task_id,
            agent_id,
            f"task-{run_id}-{agent_id}",
            run_id,
            f"{run_id}:{agent_id}:1",
            f"{agent_id}:task-{run_id}-{agent_id}",
            f"supervisor:{product_task_id}",
        )
        for agent_id in PROFESSIONAL_AGENTS
    ]


def facts(incident_id: str, run_id: str, product_task_id: str) -> RunIdentityFacts:
    return _parse_identity_rows(incident_id, run_id, rows(run_id, product_task_id))


def slot(ordinal: int = 1) -> dict[str, Any]:
    return {
        "slotId": f"dependency-latency-inventory:{ordinal:02d}",
        "scenarioId": "dependency-latency-inventory",
        "scenarioVersion": "1.0.0",
        "ordinal": ordinal,
        "runPurpose": RunPurpose.RELEASE_QUALITY.value,
        "snapshotDigest": "a" * 64,
        "status": "PLANNED",
    }


def ticket() -> dict[str, Any]:
    return {
        "datasetRunId": "dataset-01",
        "scenarioId": "dependency-latency-inventory",
    }


class Datasets:
    def select(self, *_: Any, **__: Any) -> dict[str, Any]:
        return {}


def test_reads_authoritative_product_a2a_and_agentscope_identity(tmp_path: Path) -> None:
    password_file = tmp_path / "password"
    password_file.write_text("secret\n", encoding="utf-8")
    calls: list[tuple[str, str, str, tuple[str, str]]] = []

    class Connection:
        def __enter__(self):
            return self

        def __exit__(self, *_: Any) -> None:
            return None

        def execute(self, query: str, parameters: tuple[str, str]):
            calls.append((query, "fault_lab_role", "secret", parameters))
            return self

        def fetchall(self):
            return rows()

    def connect(dsn: str, *, user: str, password: str):
        assert dsn == "postgresql://postgres:5432/opspilot"
        assert user == "fault_lab_role"
        assert password == "secret"
        return Connection()

    reader = PostgresRunIdentityReader(
        "jdbc:postgresql://postgres:5432/opspilot",
        "fault_lab_role",
        password_file,
        connector=connect,
    )

    identity = reader.read("incident-01", "run-01")

    assert identity.supervisor_session_id == "supervisor:product-01"
    assert [task["agentId"] for task in identity.a2a_tasks] == list(PROFESSIONAL_AGENTS)
    assert calls[0][3] == ("incident-01", "run-01")
    assert "opspilot.read_release_run_identity" in calls[0][0]


@pytest.mark.parametrize(
    "mutate",
    [
        lambda value: value.pop(),
        lambda value: value.__setitem__(0, (*value[0][:6], "another-run", *value[0][7:])),
        lambda value: value.__setitem__(0, (*value[0][:9], None)),
        lambda value: value.__setitem__(1, (*value[1][:4], value[0][4], *value[1][5:])),
        lambda value: value.__setitem__(0, (*value[0][:8], "wrong-session", value[0][9])),
    ],
)
def test_rejects_incomplete_or_inconsistent_database_identity(mutate: Any) -> None:
    value = deepcopy(rows())
    mutate(value)

    with pytest.raises(ContractError) as invalid:
        _parse_identity_rows("incident-01", "run-01", value)

    assert invalid.value.code == ReleaseErrorCode.QUALITY_RUN_IDENTITY_INVALID.value


def test_executes_independent_product_runs_and_writes_one_ledger_entry_per_slot(
    tmp_path: Path,
) -> None:
    product_runs = iter(
        [
            ProductRun("incident-01", "run-01", "COMPLETED", "CONCLUSIVE"),
            ProductRun("incident-02", "run-02", "COMPLETED", "CONCLUSIVE"),
        ]
    )
    requested_identities: list[str] = []

    class Product:
        def investigate(self, _ticket: dict[str, Any], **arguments: Any) -> ProductRun:
            requested_identities.append(arguments["run_identity"])
            return next(product_runs)

    identities = {
        "run-01": facts("incident-01", "run-01", "product-01"),
        "run-02": facts("incident-02", "run-02", "product-02"),
    }

    class Identities:
        def read(self, incident_id: str, run_id: str) -> RunIdentityFacts:
            identity = identities[run_id]
            assert identity.incident_id == incident_id
            return identity

    ledger = RunLedger(tmp_path / "run-ledger")
    executor = QualityRunExecutor(Product(), Identities(), ledger, Datasets())

    first = executor.execute_slot(
        "phase8-release-01",
        slot(1),
        ticket(),
        evaluation_profile="mvp-v2",
        token_budget=65536,
        environment_digest="a" * 64,
        allow_dataset_reuse=True,
    )
    second = executor.execute_slot(
        "phase8-release-01",
        slot(2),
        ticket(),
        evaluation_profile="mvp-v2",
        token_budget=65536,
        environment_digest="a" * 64,
    )

    assert requested_identities == [
        "release-quality:phase8-release-01:dependency-latency-inventory:01",
        "release-quality:phase8-release-01:dependency-latency-inventory:02",
    ]
    assert [first["runIdentity"]["runId"], second["runIdentity"]["runId"]] == [
        "run-01",
        "run-02",
    ]
    assert all(len(entry["runIdentity"]["a2aTasks"]) == 5 for entry in ledger.entries())
    assert len({
        task_identity["a2aTaskId"]
        for entry in ledger.entries()
        for task_identity in entry["runIdentity"]["a2aTasks"]
    }) == 10


def test_rejects_slot_snapshot_or_scenario_mismatch_before_product_call(tmp_path: Path) -> None:
    class Product:
        def investigate(self, *_: Any, **__: Any) -> ProductRun:
            raise AssertionError("product API must not be called")

    executor = QualityRunExecutor(
        Product(), object(), RunLedger(tmp_path / "run-ledger"), Datasets(),  # type: ignore[arg-type]
    )
    invalid_slot = slot()
    invalid_slot["snapshotDigest"] = "b" * 64

    with pytest.raises(ContractError) as invalid:
        executor.execute_slot(
            "phase8-release-01",
            invalid_slot,
            ticket(),
            evaluation_profile="mvp-v2",
            token_budget=65536,
            environment_digest="a" * 64,
        )

    assert invalid.value.code == ReleaseErrorCode.QUALITY_RUN_INPUT_INVALID.value
