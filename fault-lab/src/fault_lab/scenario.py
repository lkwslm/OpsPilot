from __future__ import annotations

from pathlib import Path
from typing import Any

from .contracts import ContractError, ContractLoader


FROZEN_SCENARIOS: dict[tuple[str, str], dict[str, Any]] = {
    ("dependency-latency-inventory", "1.0.0"): {
        "injection": ("TOXIPROXY_LATENCY", "order-to-inventory", 120),
        "parameters": {"latency_ms": 3000, "jitter_ms": 100},
        "load_seconds": 240,
        "baseline_seconds": 60,
        "recovery_seconds": 60,
        "ground_truth": "dependency-latency-inventory.json",
    },
    ("database-pool-exhausted-order", "1.0.0"): {
        "injection": ("HIKARI_CONNECTION_HOLD", "order-service", 120),
        "parameters": {
            "maximum_pool_size": 4,
            "held_connections": 4,
            "hold_seconds": 90,
            "connection_timeout_ms": 2000,
        },
        "load_seconds": 240,
        "baseline_seconds": 60,
        "recovery_seconds": 60,
        "ground_truth": "database-pool-exhausted-order.json",
    },
    ("service-instance-stopped-inventory", "1.0.0"): {
        "injection": ("CONTAINER_STOP", "inventory-service", 90),
        "parameters": {"remove_volume": False},
        "load_seconds": 240,
        "baseline_seconds": 60,
        "recovery_seconds": 90,
        "ground_truth": "service-instance-stopped-inventory.json",
    },
}


class ScenarioValidator:
    def __init__(self, contracts: ContractLoader):
        self.contracts = contracts

    def validate(self, scenario: dict[str, Any], ground_truth: dict[str, Any]) -> None:
        self.contracts.validate("scenario", scenario)
        self.contracts.validate("ground-truth", ground_truth)
        key = (scenario["scenarioId"], scenario["scenarioVersion"])
        frozen = FROZEN_SCENARIOS.get(key)
        if frozen is None:
            raise ContractError("SCENARIO_VERSION_UNREGISTERED", "/".join(key))
        injection = scenario["injection"]
        if (injection["type"], injection["target"], injection["durationSeconds"]) != frozen["injection"]:
            raise ContractError("SCENARIO_FROZEN_PARAMETER_MISMATCH", "injection")
        if injection["parameters"] != frozen["parameters"]:
            raise ContractError("SCENARIO_FROZEN_PARAMETER_MISMATCH", "injection.parameters")
        expected = (frozen["baseline_seconds"], frozen["load_seconds"], frozen["recovery_seconds"])
        actual = (
            scenario["baseline"]["durationSeconds"],
            scenario["load"]["durationSeconds"],
            scenario["recovery"]["durationSeconds"],
        )
        if actual != expected:
            raise ContractError("SCENARIO_WINDOW_INVALID", f"expected={expected}, actual={actual}")
        if Path(scenario["groundTruthRef"]).name != frozen["ground_truth"]:
            raise ContractError("SCENARIO_GROUND_TRUTH_REF_INVALID", scenario["groundTruthRef"])
        if (ground_truth["scenarioId"], ground_truth["scenarioVersion"]) != key:
            raise ContractError("SCENARIO_GROUND_TRUTH_VERSION_MISMATCH", "/".join(key))
        if not ground_truth["requiredEvidence"] or not all(scenario["collection"].values()):
            raise ContractError("SCENARIO_REQUIRED_SOURCE_OR_EVIDENCE_MISSING", "/".join(key))


class ScenarioLoader:
    def __init__(self, contracts: ContractLoader):
        self.contracts = contracts
        self.validator = ScenarioValidator(contracts)

    def load(self, scenario_path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
        scenario = self.contracts.load("scenario", scenario_path)
        ground_truth_path = (scenario_path.parent / scenario["groundTruthRef"]).resolve()
        expected_root = self.contracts.repository_root / "docs" / "design" / "contracts" / "examples" / "ground-truth"
        if expected_root.resolve() not in ground_truth_path.parents:
            raise ContractError("SCENARIO_GROUND_TRUTH_REF_INVALID", str(ground_truth_path))
        ground_truth = self.contracts.load("ground-truth", ground_truth_path)
        self.validator.validate(scenario, ground_truth)
        return scenario, ground_truth
