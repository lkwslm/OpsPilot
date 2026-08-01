from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest

from fault_lab.contracts import ContractError
from fault_lab.product import ProductRun
from fault_lab.release.baseline import BaselineRunFacts, BaselineRunner
from fault_lab.release.model import ReleaseErrorCode, ReleaseStatus, RunPurpose


ROLES = (
    "supervisor",
    "evidence-collector",
    "code-analysis",
    "knowledge",
    "diagnosis",
    "remediation",
)


class ProductClient:
    def __init__(self) -> None:
        self.calls: list[tuple[dict[str, Any], int, str, int, str]] = []

    def investigate(
        self,
        ticket: dict[str, Any],
        *,
        deadline_seconds: int,
        evaluation_profile: str,
        token_budget: int,
        run_identity: str,
    ) -> ProductRun:
        self.calls.append((ticket, deadline_seconds, evaluation_profile, token_budget, run_identity))
        return ProductRun(
            "11111111-1111-1111-1111-111111111111",
            "22222222-2222-2222-2222-222222222222",
            "COMPLETED",
            "CONCLUSIVE",
        )


class UsageReader:
    def __init__(self, roles: tuple[str, ...] = ROLES) -> None:
        started = datetime(2026, 8, 1, 1, 2, 3, tzinfo=UTC)
        self.facts = BaselineRunFacts(
            status="COMPLETED",
            outcome="CONCLUSIVE",
            execution_started_at=started,
            execution_ended_at=started + timedelta(seconds=12, milliseconds=345),
            role_usage={
                role: {"inputTokens": index + 10, "outputTokens": index + 1, "modelCalls": 1}
                for index, role in enumerate(roles)
            },
        )

    def read(self, run_id: str) -> BaselineRunFacts:
        assert run_id == "22222222-2222-2222-2222-222222222222"
        return self.facts


def snapshot() -> dict[str, Any]:
    return {
        "schemaVersion": "1.0.0",
        "snapshotDigest": "a" * 64,
        "commit": "b" * 40,
        "evaluationProfile": {"profileId": "mvp-v1", "digest": "c" * 64},
        "hardware": {"cpu": {"model": "test"}, "gpu": [{"name": "test"}]},
        "compose": {"digest": "d" * 64},
        "scenarios": [
            {
                "scenarioId": "dependency-latency-inventory",
                "scenarioVersion": "1.0.0",
                "digest": "e" * 64,
            }
        ],
    }


def test_runs_baseline_only_and_writes_role_token_report(tmp_path: Path) -> None:
    product = ProductClient()
    runner = BaselineRunner(product, UsageReader(), tmp_path)

    report = runner.execute(
        "phase8-baseline-01",
        {
            "datasetRunId": "33333333-3333-3333-3333-333333333333",
            "scenarioId": "dependency-latency-inventory",
            "scenarioVersion": "1.0.0",
        },
        snapshot(),
        deadline_seconds=600,
        token_budget=32768,
    )

    assert product.calls[0][2:] == ("mvp-v1", 32768, "baseline:phase8-baseline-01")
    assert report["status"] == ReleaseStatus.PASSED.value
    assert report["runPurpose"] == RunPurpose.BASELINE_ONLY.value
    assert report["includedInReleaseAggregation"] is False
    assert report["durationMs"] == 12345
    assert [item["role"] for item in report["tokenUsage"]["byRole"]] == sorted(ROLES)
    assert report["tokenUsage"]["totalTokens"] == sum(
        item["inputTokens"] + item["outputTokens"] for item in report["tokenUsage"]["byRole"]
    )
    assert report["environment"] == {
        "snapshotDigest": "a" * 64,
        "commit": "b" * 40,
        "evaluationProfile": {"profileId": "mvp-v1", "digest": "c" * 64},
        "composeDigest": "d" * 64,
        "hardware": snapshot()["hardware"],
    }
    written = json.loads((tmp_path / "phase8-baseline-01-baseline-report.json").read_text())
    assert written == report


def test_resolves_scenario_version_from_frozen_snapshot(tmp_path: Path) -> None:
    runner = BaselineRunner(ProductClient(), UsageReader(), tmp_path)

    report = runner.execute(
        "phase8-baseline-01",
        {
            "datasetRunId": "33333333-3333-3333-3333-333333333333",
            "scenarioId": "dependency-latency-inventory",
        },
        snapshot(),
    )

    assert report["scenario"]["scenarioVersion"] == "1.0.0"


def test_rejects_incomplete_six_role_usage(tmp_path: Path) -> None:
    runner = BaselineRunner(ProductClient(), UsageReader(ROLES[:-1]), tmp_path)

    with pytest.raises(ContractError) as exc_info:
        runner.execute(
            "phase8-baseline-01",
            {
                "datasetRunId": "33333333-3333-3333-3333-333333333333",
                "scenarioId": "dependency-latency-inventory",
                "scenarioVersion": "1.0.0",
            },
            snapshot(),
        )

    assert exc_info.value.code == ReleaseErrorCode.BASELINE_USAGE_INCOMPLETE.value
    assert not list(tmp_path.iterdir())


def test_refuses_to_overwrite_existing_baseline_report(tmp_path: Path) -> None:
    path = tmp_path / "phase8-baseline-01-baseline-report.json"
    path.write_text('{"existing":true}', encoding="utf-8")
    runner = BaselineRunner(ProductClient(), UsageReader(), tmp_path)

    with pytest.raises(ContractError) as exc_info:
        runner.execute(
            "phase8-baseline-01",
            {
                "datasetRunId": "33333333-3333-3333-3333-333333333333",
                "scenarioId": "dependency-latency-inventory",
                "scenarioVersion": "1.0.0",
            },
            snapshot(),
        )

    assert exc_info.value.code == ReleaseErrorCode.BASELINE_REPORT_EXISTS.value
    assert path.read_text(encoding="utf-8") == '{"existing":true}'
