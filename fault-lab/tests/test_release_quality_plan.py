from __future__ import annotations

from typing import Any

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.model import ReleaseErrorCode, ReleaseStatus, RunPurpose
from fault_lab.release.quality import QualityBatchPlanner


SCENARIOS = (
    "database-pool-exhausted-order",
    "dependency-latency-inventory",
    "service-instance-stopped-inventory",
)


def snapshot(scenarios: list[dict[str, str]] | None = None) -> dict[str, Any]:
    return {
        "snapshotDigest": "a" * 64,
        "evaluationProfile": {"profileId": "mvp-v2", "digest": "b" * 64},
        "scenarios": scenarios
        if scenarios is not None
        else [
            {"scenarioId": scenario, "scenarioVersion": "1.0.0", "digest": "c" * 64}
            for scenario in SCENARIOS
        ],
    }


def profile(runs_per_scenario: int = 5) -> dict[str, Any]:
    return {"profile_id": "mvp-v2", "runs_per_scenario": runs_per_scenario}


def baseline_gate(snapshot_digest: str = "a" * 64) -> dict[str, Any]:
    return {
        "status": ReleaseStatus.PASSED.value,
        "checks": {
            "snapshot": {
                "status": ReleaseStatus.PASSED.value,
                "snapshotDigest": snapshot_digest,
            },
            "evaluationProfile": {
                "status": ReleaseStatus.PASSED.value,
                "profileId": "mvp-v2",
            },
            "runLedger": {"status": ReleaseStatus.PASSED.value},
        },
    }


def test_plans_three_frozen_scenarios_with_five_slots_each() -> None:
    plan = QualityBatchPlanner().plan(
        "phase8-quality-01",
        snapshot=snapshot(),
        profile=profile(),
        wp01_gate=baseline_gate(),
    )

    assert plan["runPurpose"] == RunPurpose.RELEASE_QUALITY.value
    assert plan["runsPerScenario"] == 5
    assert plan["totalSlots"] == 15
    assert len(plan["planDigest"]) == 64
    assert [(slot["scenarioId"], slot["ordinal"]) for slot in plan["slots"]] == [
        (scenario, ordinal) for scenario in SCENARIOS for ordinal in range(1, 6)
    ]
    assert len({slot["slotId"] for slot in plan["slots"]}) == 15
    assert all(slot["scenarioVersion"] == "1.0.0" for slot in plan["slots"])
    assert all(slot["snapshotDigest"] == "a" * 64 for slot in plan["slots"])


@pytest.mark.parametrize(
    "scenarios",
    [
        [
            {"scenarioId": scenario, "scenarioVersion": "1.0.0", "digest": "c" * 64}
            for scenario in SCENARIOS[:-1]
        ],
        [
            {"scenarioId": SCENARIOS[0], "scenarioVersion": "1.0.0", "digest": "c" * 64},
            {"scenarioId": SCENARIOS[0], "scenarioVersion": "1.0.0", "digest": "d" * 64},
            {"scenarioId": SCENARIOS[2], "scenarioVersion": "1.0.0", "digest": "e" * 64},
        ],
        [
            *[
                {"scenarioId": scenario, "scenarioVersion": "1.0.0", "digest": "c" * 64}
                for scenario in SCENARIOS
            ],
            {"scenarioId": "extra-scenario", "scenarioVersion": "1.0.0", "digest": "d" * 64},
        ],
        [
            {"scenarioId": scenario, "scenarioVersion": "2.0.0", "digest": "c" * 64}
            for scenario in SCENARIOS
        ],
    ],
    ids=("missing", "duplicate", "extra", "wrong-version"),
)
def test_rejects_non_frozen_scenario_set(scenarios: list[dict[str, str]]) -> None:
    with pytest.raises(ContractError) as exc_info:
        QualityBatchPlanner().plan(
            "phase8-quality-01",
            snapshot=snapshot(scenarios),
            profile=profile(),
            wp01_gate=baseline_gate(),
        )

    assert exc_info.value.code == ReleaseErrorCode.QUALITY_PLAN_INVALID.value


def test_rejects_dynamic_run_count() -> None:
    with pytest.raises(ContractError) as exc_info:
        QualityBatchPlanner().plan(
            "phase8-quality-01",
            snapshot=snapshot(),
            profile=profile(4),
            wp01_gate=baseline_gate(),
        )

    assert exc_info.value.code == ReleaseErrorCode.QUALITY_PLAN_INVALID.value


def test_rejects_snapshot_not_approved_by_wp01_gate() -> None:
    with pytest.raises(ContractError) as exc_info:
        QualityBatchPlanner().plan(
            "phase8-quality-01",
            snapshot=snapshot(),
            profile=profile(),
            wp01_gate=baseline_gate("f" * 64),
        )

    assert exc_info.value.code == ReleaseErrorCode.SNAPSHOT_NOT_APPROVED.value
