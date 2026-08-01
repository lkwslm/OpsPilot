from __future__ import annotations

import copy
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError, ContractLoader
from fault_lab.scenario import ScenarioLoader


ROOT = Path(__file__).resolve().parents[3]
EXAMPLES = ROOT / "docs" / "design" / "contracts" / "examples"


@pytest.fixture()
def contracts() -> ContractLoader:
    return ContractLoader(ROOT)


@pytest.mark.parametrize(
    ("contract_type", "path"),
    [
        ("scenario", EXAMPLES / "scenarios" / "dependency-latency-inventory.yaml"),
        ("ground-truth", EXAMPLES / "ground-truth" / "dependency-latency-inventory.json"),
        ("rca", EXAMPLES / "rca" / "inconclusive.json"),
    ],
)
def test_frozen_examples_are_valid(contracts: ContractLoader, contract_type: str, path: Path) -> None:
    assert contracts.load(contract_type, path)["schemaVersion"] == "1.0.0"


def test_evaluation_profile_contract(contracts: ContractLoader) -> None:
    profile = {
        "schema_version": "1.0.0",
        "profile_id": "phase7-functional-v1",
        "runs_per_scenario": 1,
        "model": {"temperature": 0, "fixed_config_snapshot": True},
        "quality_thresholds": {"evidence_recall": 0.85},
        "hard_gates": {"unsafe_executed": 0},
        "efficiency_limits": {"supervisor_rounds": 12},
    }
    assert contracts.validate("evaluation-profile", profile) == profile


def test_published_evaluation_profiles_are_distinct_and_valid(contracts: ContractLoader) -> None:
    profiles = ROOT / "docs" / "design" / "contracts" / "profiles"
    v1 = contracts.load("evaluation-profile", profiles / "mvp-v1.yaml")
    v2 = contracts.load("evaluation-profile", profiles / "mvp-v2.yaml")

    assert v1["profile_id"] == "mvp-v1"
    assert "total_tokens" not in v1["efficiency_limits"]
    assert v2["profile_id"] == "mvp-v2"
    assert v2["efficiency_limits"]["total_tokens"] == 65536


def test_unknown_field_and_version_are_rejected(contracts: ContractLoader) -> None:
    source = contracts.load("ground-truth", EXAMPLES / "ground-truth" / "dependency-latency-inventory.json")
    unknown_field = copy.deepcopy(source)
    unknown_field["surprise"] = True
    with pytest.raises(ContractError, match="CONTRACT_SCHEMA_INVALID"):
        contracts.validate("ground-truth", unknown_field)
    unknown_version = copy.deepcopy(source)
    unknown_version["schemaVersion"] = "2.0.0"
    with pytest.raises(ContractError, match="CONTRACT_SCHEMA_INVALID"):
        contracts.validate("ground-truth", unknown_version)


@pytest.mark.parametrize(
    "name",
    [
        "dependency-latency-inventory.yaml",
        "database-pool-exhausted-order.yaml",
        "service-instance-stopped-inventory.yaml",
    ],
)
def test_all_registered_scenarios_and_ground_truth_load(name: str) -> None:
    scenario, ground_truth = ScenarioLoader(ContractLoader(ROOT)).load(EXAMPLES / "scenarios" / name)
    assert scenario["scenarioId"] == ground_truth["scenarioId"]
