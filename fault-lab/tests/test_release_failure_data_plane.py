from __future__ import annotations

import copy
import json
import os
import shutil
import subprocess
from functools import lru_cache
from pathlib import Path

import pytest
import yaml

from fault_lab.contracts import ContractError
from fault_lab.release.failure_data_plane import FailureDataPlaneVerifier
from fault_lab.release.failure_plan import FailureRouteTopology
from fault_lab.release.fixture_init import main as initialize_fixtures
from fault_lab.release.local_fixture_plane import LocalFixtureDataPlane
from fault_lab.release.model import ReleaseErrorCode
from fault_lab.release.response_proxy import RouteState


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PHASE8_FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "phase8"
TOPOLOGY = PHASE8_FIXTURES / "failure-route-topology-v1.yaml"
TOXIPROXY = REPOSITORY_ROOT / "deployment" / "toxiproxy" / "toxiproxy.phase8.json"
DIRECTORY = REPOSITORY_ROOT / "deployment" / "agents" / "agent-directory.phase8.yaml"


def test_compose_data_plane_routes_every_frozen_boundary_through_real_consumer_config() -> None:
    report = _verifier().verify()

    assert report["status"] == "PASSED"
    assert report["routeCount"] == 19
    assert report["networkRouteCount"] == 14
    assert report["fileRouteCount"] == 4
    assert report["processRouteCount"] == 1
    assert len(report["routes"]) == 19
    assert all(route["status"] == "PASSED" for route in report["routes"])
    assert len(report["reportDigest"]) == 64


@pytest.mark.parametrize(
    "mutate",
    [
        lambda compose, proxies: proxies.pop(),
        lambda compose, proxies: compose["services"]["opspilot-server"]["environment"].update(
            CHAT_MODEL_BASE_URL="https://api.deepseek.com"
        ),
        lambda compose, proxies: compose["services"]["phase8-response-proxy"]["environment"].update(
            PHASE8_RESPONSE_ROUTES=compose["services"]["phase8-response-proxy"]["environment"][
                "PHASE8_RESPONSE_ROUTES"
            ].replace("http://prometheus:9090", "http://unfrozen-prometheus:9090")
        ),
        lambda compose, proxies: compose["services"]["evidence-agent"]["environment"].update(
            SOURCE_PATH="/datasets/input/current/observability.jsonl"
        ),
    ],
    ids=("missing-proxy", "consumer-bypass", "upstream-drift", "fixture-bypass"),
)
def test_rejects_proxy_consumer_or_fixture_bypass(mutate) -> None:
    compose = copy.deepcopy(_rendered_compose())
    proxies = copy.deepcopy(_toxiproxy())
    mutate(compose, proxies)

    with pytest.raises(ContractError) as exc_info:
        FailureDataPlaneVerifier(
            FailureRouteTopology.load(TOPOLOGY),
            compose,
            proxies,
            _agent_directory(),
        ).verify()

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_DATA_PLANE_INVALID.value


def test_response_proxy_modes_are_route_isolated() -> None:
    state = RouteState(
        {
            "18001": {"routeId": "route-a", "upstream": "http://upstream-a"},
            "18002": {"routeId": "route-b", "upstream": "http://upstream-b"},
        }
    )

    state.set_mode("route-a", "AUTH")

    assert state.snapshot() == {"route-a": "AUTH", "route-b": "NORMAL"}
    state.set_mode("route-a", "NORMAL")
    assert state.snapshot() == {"route-a": "NORMAL", "route-b": "NORMAL"}


def test_fixture_initializer_creates_idempotent_isolated_file_and_process_boundaries(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seed = tmp_path / "seed"
    root = tmp_path / "fixtures"
    (seed / "code-snapshot").mkdir(parents=True)
    (seed / "observability.jsonl").write_text('{"signal":"LOG"}\n', encoding="utf-8")
    (seed / "topology.json").write_text('{"services":[]}\n', encoding="utf-8")
    (seed / "application.properties").write_text("server.port=8080\n", encoding="utf-8")
    (seed / "code-snapshot" / "README.md").write_text("snapshot\n", encoding="utf-8")
    monkeypatch.setenv("PHASE8_FIXTURE_ROOT", str(root))
    monkeypatch.setenv("PHASE8_FIXTURE_SEED_ROOT", str(seed))

    assert initialize_fixtures() == 0
    (root / "agent-input/current/observability.jsonl").write_text("corrupt\n", encoding="utf-8")
    assert initialize_fixtures() == 0

    assert (root / "agent-input/current/observability.jsonl").read_text(encoding="utf-8") == '{"signal":"LOG"}\n'
    assert (root / "topology/sample-compose-topology.json").is_file()
    assert (root / "source-fixtures/application.properties").is_file()
    assert (root / "code-snapshot/sample-system/README.md").is_file()
    process = root / "process/maven-sandbox/run"
    assert process.is_file()
    assert os.access(process, os.X_OK)


@pytest.mark.parametrize("fault_type", ["UNAVAILABLE", "AUTH", "SCHEMA"])
def test_file_fixture_faults_restore_original_bytes_and_allow_repeated_recovery(
    tmp_path: Path, fault_type: str
) -> None:
    root = tmp_path / "fixtures"
    target = root / "topology/sample-compose-topology.json"
    target.parent.mkdir(parents=True)
    target.write_text('{"services":[]}\n', encoding="utf-8")
    plane = LocalFixtureDataPlane(root)
    case = _fixture_case("FILE_FIXTURE", "file://topology/sample-compose-topology.json", fault_type)
    before = plane.health(case)["routeSnapshot"]

    activation = plane.activate(case)
    recovery = plane.recover(case, activation)
    repeated = plane.recover(case, activation)

    assert activation["changedRoutes"] == [case["routeRef"]]
    assert recovery["healthy"] is True
    assert repeated["healthy"] is True
    assert plane.health(case)["routeSnapshot"] == before


@pytest.mark.skipif(not hasattr(os, "mkfifo"), reason="FIFO requires a POSIX runtime")
def test_file_timeout_uses_fifo_and_recovers(tmp_path: Path) -> None:
    root = tmp_path / "fixtures"
    target = root / "agent-input/current/observability.jsonl"
    target.parent.mkdir(parents=True)
    target.write_text('{"signal":"LOG"}\n', encoding="utf-8")
    plane = LocalFixtureDataPlane(root)
    case = _fixture_case("FILE_FIXTURE", "file://agent-input/current/observability.jsonl", "TIMEOUT")

    activation = plane.activate(case)

    assert activation["after"]["kind"] == "FIFO"
    assert plane.recover(case, activation)["healthy"] is True


@pytest.mark.parametrize("fault_type", ["UNAVAILABLE", "TIMEOUT", "AUTH", "SCHEMA"])
def test_process_fixture_faults_restore_executable_wrapper(
    tmp_path: Path, fault_type: str
) -> None:
    root = tmp_path / "fixtures"
    target = root / "process/maven-sandbox/run"
    target.parent.mkdir(parents=True)
    target.write_text("#!/bin/sh\nexec /opt/maven/bin/mvn \"$@\"\n", encoding="utf-8")
    target.chmod(0o550)
    plane = LocalFixtureDataPlane(root)
    case = _fixture_case("PROCESS_FIXTURE", "process://maven-sandbox", fault_type)
    before = plane.health(case)["routeSnapshot"]

    activation = plane.activate(case)
    recovery = plane.recover(case, activation)

    assert recovery["healthy"] is True
    assert plane.health(case)["routeSnapshot"] == before


def test_directory_fixture_schema_fault_restores_directory(tmp_path: Path) -> None:
    root = tmp_path / "fixtures"
    target = root / "code-snapshot/sample-system"
    target.mkdir(parents=True)
    (target / "README.md").write_text("snapshot\n", encoding="utf-8")
    plane = LocalFixtureDataPlane(root)
    case = _fixture_case("FILE_FIXTURE", "file://code-snapshot/sample-system", "SCHEMA")

    activation = plane.activate(case)

    assert activation["after"]["kind"] == "FILE"
    assert plane.recover(case, activation)["healthy"] is True
    assert (target / "README.md").read_text(encoding="utf-8") == "snapshot\n"


def _verifier() -> FailureDataPlaneVerifier:
    return FailureDataPlaneVerifier(
        FailureRouteTopology.load(TOPOLOGY),
        copy.deepcopy(_rendered_compose()),
        copy.deepcopy(_toxiproxy()),
        copy.deepcopy(_agent_directory()),
    )


@lru_cache(maxsize=1)
def _rendered_compose() -> dict:
    if shutil.which("docker") is None:
        pytest.skip("docker compose is required for the Phase 8 data-plane contract")
    completed = subprocess.run(
        [
            "docker",
            "compose",
            "-f",
            "deployment/docker-compose.yml",
            "-f",
            "deployment/docker-compose.phase7.yml",
            "-f",
            "deployment/docker-compose.phase8.yml",
            "--profile",
            "phase7",
            "--profile",
            "phase8-failure",
            "config",
            "--format",
            "json",
        ],
        cwd=REPOSITORY_ROOT,
        capture_output=True,
        text=True,
        check=False,
        timeout=30,
    )
    assert completed.returncode == 0, completed.stderr
    return json.loads(completed.stdout)


@lru_cache(maxsize=1)
def _toxiproxy() -> list[dict]:
    return json.loads(TOXIPROXY.read_text(encoding="utf-8"))


@lru_cache(maxsize=1)
def _agent_directory() -> dict:
    return yaml.safe_load(DIRECTORY.read_text(encoding="utf-8"))


def _fixture_case(route_kind: str, route_ref: str, fault_type: str) -> dict[str, str]:
    return {
        "caseId": f"test-{fault_type.lower()}",
        "routeKind": route_kind,
        "routeRef": route_ref,
        "faultType": fault_type,
    }
