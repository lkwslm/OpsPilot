from __future__ import annotations

import copy
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError, ContractLoader
from fault_lab.registry import ComponentRegistry
from fault_lab.scenario import ScenarioLoader, ScenarioValidator


ROOT = Path(__file__).resolve().parents[2]
SCENARIOS = ROOT / "docs" / "design" / "contracts" / "examples" / "scenarios"


def test_frozen_parameter_drift_is_rejected() -> None:
    loader = ScenarioLoader(ContractLoader(ROOT))
    scenario, ground_truth = loader.load(SCENARIOS / "dependency-latency-inventory.yaml")
    changed = copy.deepcopy(scenario)
    changed["injection"]["parameters"]["latency_ms"] = 3200
    with pytest.raises(ContractError, match="SCENARIO_FROZEN_PARAMETER_MISMATCH"):
        ScenarioValidator(loader.contracts).validate(changed, ground_truth)


def test_registry_is_explicit_unique_and_frozen() -> None:
    registry = ComponentRegistry()
    registry.register("injector", "TOXIPROXY_LATENCY", "1.0.0", object())
    with pytest.raises(ContractError, match="REGISTRY_DUPLICATE"):
        registry.register("injector", "TOXIPROXY_LATENCY", "1.0.0", object())
    registry.freeze()
    assert registry.require("injector", "TOXIPROXY_LATENCY") is not None
    with pytest.raises(ContractError, match="REGISTRY_FROZEN"):
        registry.register("injector", "CONTAINER_STOP", "1.0.0", object())
    with pytest.raises(ContractError, match="REGISTRY_COMPONENT_MISSING"):
        registry.require("injector", "HIKARI_CONNECTION_HOLD")
