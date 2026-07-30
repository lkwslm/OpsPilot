from __future__ import annotations

from typing import Any

from .contracts import ContractError


def _require(condition: bool, code: str) -> None:
    if not condition:
        raise ContractError("SCENARIO_OUTCOME_INVALID", code)


class ScenarioOutcomeValidator:
    def validate(self, scenario_id: str, facts: dict[str, Any]) -> None:
        validator = {
            "dependency-latency-inventory": self._latency,
            "database-pool-exhausted-order": self._pool,
            "service-instance-stopped-inventory": self._stopped,
        }.get(scenario_id)
        if validator is None:
            raise ContractError("SCENARIO_VERSION_UNREGISTERED", scenario_id)
        validator(facts)

    @staticmethod
    def _latency(facts: dict[str, Any]) -> None:
        baseline, fault, recovery = facts["baseline"], facts["fault"], facts["recovery"]
        _require(baseline["requests"] >= 100 and baseline["successRate"] >= 0.99 and baseline["p95Ms"] < 500, "LATENCY_BASELINE")
        _require(fault["requests"] >= 30 and fault["clientSpanP95Ms"] >= 2500, "LATENCY_FAULT")
        _require(fault["traceParentChildValid"] and fault["inventoryResourcesNormal"], "LATENCY_TRACE_OR_RESOURCE")
        _require(recovery["successRate"] >= 0.99 and recovery["p95Ms"] < 700 and recovery["toxicRemoved"], "LATENCY_RECOVERY")

    @staticmethod
    def _pool(facts: dict[str, Any]) -> None:
        baseline, fault, recovery = facts["baseline"], facts["fault"], facts["recovery"]
        _require(baseline["pending"] == 0 and baseline["active"] < 4 and baseline["requests"] >= 100 and baseline["successRate"] >= 0.99, "POOL_BASELINE")
        _require(fault["active"] == 4 and fault["pending"] > 0 and fault["saturatedSeconds"] >= 15 and fault["connectionTimeouts"] > 0, "POOL_FAULT")
        _require(not fault.get("postgresStopped", False), "SHARED_POSTGRES_STOPPED")
        _require(recovery["pending"] == 0 and recovery["active"] < 4 and recovery["successRate"] >= 0.99, "POOL_RECOVERY")

    @staticmethod
    def _stopped(facts: dict[str, Any]) -> None:
        baseline, fault, recovery = facts["baseline"], facts["fault"], facts["recovery"]
        _require(baseline["readiness"] == "UP" and baseline["target"] == "UP" and baseline["successRate"] >= 0.99, "STOP_BASELINE")
        _require(fault["readiness"] != "UP" and fault["connectionFailures"] > 0 and fault["targetDownSeconds"] >= 30, "STOP_FAULT")
        _require(recovery["readiness"] == "UP" and recovery["target"] == "UP" and recovery["successRate"] >= 0.99, "STOP_RECOVERY")
        _require(recovery["volumeDigest"] == baseline["volumeDigest"] and recovery["dataPreserved"], "STOP_DATA_PRESERVATION")
