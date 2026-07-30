from __future__ import annotations

import json
import uuid
from pathlib import Path

import pytest

import fault_lab.runtime as runtime_module
from fault_lab.adapters import (
    CommandResult,
    InventoryContainerStopInjector,
    LongTransactionInjector,
    ToxiproxyLatencyInjector,
)
from fault_lab.contracts import ContractError
from fault_lab.model import ExecutionContext
from fault_lab.outcomes import ScenarioOutcomeValidator
from fault_lab.runtime import RealArtifactCollector, RealLoadGenerator


def context(injection_type: str, parameters: dict[str, object], duration: int = 120) -> ExecutionContext:
    return ExecutionContext(
        str(uuid.uuid4()),
        1,
        {"injection": {"type": injection_type, "parameters": parameters, "durationSeconds": duration}},
        Path("datasets"),
    )


def test_toxiproxy_uses_run_scoped_toxic_and_idempotent_delete() -> None:
    calls = []
    transport = lambda method, url, body: (calls.append((method, url, body)) or ((201, b"") if method == "POST" else (404, b"")))
    injector = ToxiproxyLatencyInjector(transport=transport)
    run = context("TOXIPROXY_LATENCY", {"latency_ms": 3000, "jitter_ms": 100})
    result = injector.inject(run)
    injector.recover(run)
    assert run.dataset_run_id in result["toxicName"]
    assert [call[0] for call in calls] == ["POST", "DELETE"]


def test_long_transaction_is_fixed_and_run_scoped() -> None:
    calls = []
    active_holds = 0

    def transport(method, url, body):
        nonlocal active_holds
        calls.append((method, url, body))
        if method == "PUT":
            active_holds += 1
            return 200, json.dumps({"activeHolds": min(active_holds, 4)}).encode()
        active_holds = 0
        return 200, b'{"activeHolds":0}'

    injector = LongTransactionInjector(transport=transport)
    run = context("HIKARI_CONNECTION_HOLD", {"maximum_pool_size": 4, "held_connections": 4, "hold_seconds": 90, "connection_timeout_ms": 2000})
    assert injector.inject(run)["heldConnections"] == 4
    injector.recover(run)
    assert [call[0] for call in calls] == ["PUT", "PUT", "PUT", "PUT", "POST"]
    assert calls[0][1].endswith("/internal/faults/db/holds/1")
    assert calls[-1][1].endswith("/internal/faults/db/reset")


def test_container_injector_preserves_identity_and_never_uses_volume_commands() -> None:
    commands = []
    inspect = json.dumps([{
        "Image": "sha256:image",
        "Mounts": [{"Name": "inventory-data"}],
        "Config": {"Labels": {
            "com.docker.compose.project": "opspilot",
            "com.docker.compose.service": "inventory-service",
        }},
    }])

    def runner(command, timeout):
        commands.append(command)
        if command[:2] == ["docker", "inspect"]:
            return CommandResult(0, inspect)
        if "ps" in command:
            return CommandResult(0, "container-1\n")
        return CommandResult(0, "")

    injector = InventoryContainerStopInjector("opspilot", runner)
    run = context("CONTAINER_STOP", {"remove_volume": False}, 90)
    identity = injector.inject(run)
    injector.recover(run)
    assert identity["mounts"] == ["inventory-data"]
    assert [command[:2] for command in commands] == [
        ["docker", "ps"], ["docker", "inspect"], ["docker", "stop"],
        ["docker", "start"], ["docker", "inspect"],
    ]
    assert all(not {"down", "rm", "volume", "-v", "--volumes"}.intersection(command) for command in commands)


def test_container_recovery_waits_for_real_readiness(monkeypatch) -> None:
    clock = [0.0]
    statuses = iter([0, 503, 200])
    monkeypatch.setattr(runtime_module.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(runtime_module.time, "sleep", lambda seconds: clock.__setitem__(0, clock[0] + seconds))
    monkeypatch.setattr(runtime_module, "_json_request", lambda *args, **kwargs: (next(statuses), b""))

    load = RealLoadGenerator(None, None, "http://prometheus", "http://inventory-service:8080")
    load._await_inventory_recovery(10)

    assert clock[0] == 2.0


def test_remove_volume_is_denied_before_command() -> None:
    commands = []
    injector = InventoryContainerStopInjector("opspilot", lambda command, timeout: (commands.append(command) or CommandResult(0, "")))
    with pytest.raises(ContractError, match="DESTRUCTIVE_CONTAINER_ACTION_DENIED"):
        injector.inject(context("CONTAINER_STOP", {"remove_volume": True}, 90))
    assert not commands


def test_three_outcome_contracts() -> None:
    validator = ScenarioOutcomeValidator()
    validator.validate("dependency-latency-inventory", {
        "baseline": {"requests": 100, "successRate": 0.99, "p95Ms": 499},
        "fault": {"requests": 30, "clientSpanP95Ms": 2500, "traceParentChildValid": True, "inventoryResourcesNormal": True},
        "recovery": {"successRate": 0.99, "p95Ms": 699, "toxicRemoved": True},
    })
    validator.validate("database-pool-exhausted-order", {
        "baseline": {"pending": 0, "active": 3, "requests": 100, "successRate": 0.99},
        "fault": {"active": 4, "pending": 1, "saturatedSeconds": 15, "connectionTimeouts": 1, "postgresStopped": False},
        "recovery": {"pending": 0, "active": 3, "successRate": 0.99},
    })
    validator.validate("service-instance-stopped-inventory", {
        "baseline": {"readiness": "UP", "target": "UP", "successRate": 0.99, "volumeDigest": "a"},
        "fault": {"readiness": "DOWN", "connectionFailures": 1, "targetDownSeconds": 30},
        "recovery": {"readiness": "UP", "target": "UP", "successRate": 0.99, "volumeDigest": "a", "dataPreserved": True},
    })


def test_http_slow_without_trace_topology_is_invalid() -> None:
    facts = {
        "baseline": {"requests": 100, "successRate": 0.99, "p95Ms": 499},
        "fault": {"requests": 30, "clientSpanP95Ms": 2500, "traceParentChildValid": False, "inventoryResourcesNormal": True},
        "recovery": {"successRate": 0.99, "p95Ms": 699, "toxicRemoved": True},
    }
    with pytest.raises(ContractError, match="LATENCY_TRACE_OR_RESOURCE"):
        ScenarioOutcomeValidator().validate("dependency-latency-inventory", facts)


def test_pool_scenario_never_accepts_shared_postgres_stop() -> None:
    facts = {
        "baseline": {"pending": 0, "active": 3, "requests": 100, "successRate": 0.99},
        "fault": {"active": 4, "pending": 1, "saturatedSeconds": 15, "connectionTimeouts": 1, "postgresStopped": True},
        "recovery": {"pending": 0, "active": 3, "successRate": 0.99},
    }
    with pytest.raises(ContractError, match="SHARED_POSTGRES_STOPPED"):
        ScenarioOutcomeValidator().validate("database-pool-exhausted-order", facts)


def test_stopped_scenario_requires_downtime_and_preserved_volume() -> None:
    slow_only = {
        "baseline": {"readiness": "UP", "target": "UP", "successRate": 0.99, "volumeDigest": "a"},
        "fault": {"readiness": "UP", "connectionFailures": 1, "targetDownSeconds": 30},
        "recovery": {"readiness": "UP", "target": "UP", "successRate": 0.99, "volumeDigest": "a", "dataPreserved": True},
    }
    with pytest.raises(ContractError, match="STOP_FAULT"):
        ScenarioOutcomeValidator().validate("service-instance-stopped-inventory", slow_only)
    changed_volume = {**slow_only, "fault": {"readiness": "DOWN", "connectionFailures": 1, "targetDownSeconds": 30},
                      "recovery": {**slow_only["recovery"], "volumeDigest": "b"}}
    with pytest.raises(ContractError, match="STOP_DATA_PRESERVATION"):
        ScenarioOutcomeValidator().validate("service-instance-stopped-inventory", changed_volume)


def test_real_trace_collector_identifies_generic_http_client_span_by_inventory_url() -> None:
    traces = {"data": [{
        "processes": {
            "p1": {"serviceName": "order-service"},
            "p2": {"serviceName": "inventory-service"},
        },
        "spans": [{
            "processID": "p1",
            "operationName": "POST",
            "startTime": 1_500_000,
            "duration": 3_050_000,
            "tags": [
                {"key": "span.kind", "value": "client"},
                {"key": "url.full", "value": "http://toxiproxy:8666/api/inventory/SKU-001/reserve"},
            ],
        }],
    }]}

    p95, topology = RealArtifactCollector._trace_facts(
        traces, {"start": "1970-01-01T00:00:01Z", "end": "1970-01-01T00:00:02Z"})

    assert p95 == 3050
    assert topology is True
