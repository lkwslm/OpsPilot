from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.failure_catalog import FailureCatalog
from fault_lab.release.failure_plan import (
    FailurePlan,
    FailureRouteTopology,
    FailureTriggerCatalog,
)
from fault_lab.release.model import ReleaseErrorCode


FIXTURES = Path(__file__).resolve().parents[1] / "fixtures" / "phase8"
CATALOG = FIXTURES / "failure-catalog-v1.yaml"
TOPOLOGY = FIXTURES / "failure-route-topology-v1.yaml"
TRIGGERS = FIXTURES / "failure-trigger-catalog-v1.yaml"


def test_binds_every_catalog_route_and_case_bidirectionally() -> None:
    topology = FailureRouteTopology.load(TOPOLOGY)
    triggers = FailureTriggerCatalog.load(TRIGGERS)
    plan = FailurePlan(FailureCatalog.load(CATALOG), topology, triggers)

    assert len(topology.routes) == 19
    assert len(triggers.cases) == 100
    assert len(plan.cases) == 100
    assert len({case["topologyRef"] for case in plan.cases}) == 19
    assert len({case["triggerRef"] for case in plan.cases}) == 100
    assert all(case["topologyRef"] and case["triggerRef"] for case in plan.cases)
    assert plan.coverage_report == {
        "schemaVersion": "1.0.0",
        "status": "PASSED",
        "routeCount": 19,
        "caseCount": 100,
        "routeMissing": [],
        "routeOrphaned": [],
        "routeChanged": [],
        "caseMissing": [],
        "caseOrphaned": [],
        "caseChanged": [],
        "catalogDigest": FailureCatalog.load(CATALOG).document["catalogDigest"],
        "topologyDigest": topology.document["topologyDigest"],
        "triggerCatalogDigest": triggers.document["triggerCatalogDigest"],
        "coverageDigest": plan.coverage_report["coverageDigest"],
    }
    assert len(plan.coverage_report["coverageDigest"]) == 64


def test_topology_records_real_consumer_and_recovery_contract() -> None:
    topology = FailureRouteTopology.load(TOPOLOGY)

    assert {route["routeKind"] for route in topology.routes.values()} == {
        "NETWORK_PROXY",
        "FILE_FIXTURE",
        "PROCESS_FIXTURE",
    }
    assert all(
        route[field]
        for route in topology.routes.values()
        for field in (
            "consumerService",
            "consumerConfig",
            "upstream",
            "injectorEndpoint",
            "healthProbe",
            "residualProbe",
            "restoreAction",
        )
    )


def test_trigger_catalog_records_each_case_precondition_and_expected_invocation() -> None:
    triggers = FailureTriggerCatalog.load(TRIGGERS)

    assert all(trigger["scenarioVersion"].endswith("/1.0.0") for trigger in triggers.cases.values())
    assert all(trigger["ticketRef"].startswith("ticket://") for trigger in triggers.cases.values())
    assert all(trigger["inputFixture"].startswith("fixture://") for trigger in triggers.cases.values())
    assert all(trigger["preconditions"] for trigger in triggers.cases.values())
    assert all(trigger["evidenceSelectors"] for trigger in triggers.cases.values())
    assert all(
        trigger["expectedInvocation"]["componentId"] == trigger["componentId"]
        for trigger in triggers.cases.values()
    )


@pytest.mark.parametrize(
    ("kind", "mutate"),
    [
        ("topology", lambda document: document["routes"].pop()),
        (
            "topology",
            lambda document: document["routes"].append(
                {
                    **copy.deepcopy(document["routes"][0]),
                    "routeRef": "network://orphaned-route",
                    "topologyRef": "topology://phase8/network/orphaned-route",
                }
            ),
        ),
        ("triggers", lambda document: document["triggerGroups"][0]["cases"].pop()),
        (
            "triggers",
            lambda document: document["triggerGroups"][0]["cases"][0].update(
                caseId="p8-failure-orphaned-unavailable",
                triggerRef="trigger://orphaned/unavailable",
            ),
        ),
        (
            "triggers",
            lambda document: document["triggerGroups"][0]["expectedInvocation"].update(
                routeRef="network://wrong-route"
            ),
        ),
    ],
    ids=(
        "missing-route",
        "orphaned-route",
        "missing-case",
        "orphaned-case",
        "changed-invocation",
    ),
)
def test_rejects_catalog_topology_trigger_bidirectional_drift(kind, mutate) -> None:
    catalog = FailureCatalog.load(CATALOG)
    topology_document = FailureRouteTopology.load(TOPOLOGY).document
    trigger_document = FailureTriggerCatalog.load(TRIGGERS).document
    document = topology_document if kind == "topology" else trigger_document
    mutate(document)
    _refresh_digest(document)

    topology = (
        FailureRouteTopology(document)
        if kind == "topology"
        else FailureRouteTopology(topology_document)
    )
    triggers = (
        FailureTriggerCatalog(document)
        if kind == "triggers"
        else FailureTriggerCatalog(trigger_document)
    )

    with pytest.raises(ContractError) as exc_info:
        FailurePlan(catalog, topology, triggers)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_PLAN_DRIFT.value


def test_rejects_route_kind_that_disagrees_with_route_scheme() -> None:
    document = FailureRouteTopology.load(TOPOLOGY).document
    document["routes"][0]["routeKind"] = "FILE_FIXTURE"
    _refresh_digest(document)

    with pytest.raises(ContractError) as exc_info:
        FailureRouteTopology(document)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_TOPOLOGY_INVALID.value


@pytest.mark.parametrize(
    ("loader", "path", "field", "code"),
    [
        (
            FailureRouteTopology,
            TOPOLOGY,
            "topologyDigest",
            ReleaseErrorCode.FAILURE_TOPOLOGY_DIGEST_INVALID,
        ),
        (
            FailureTriggerCatalog,
            TRIGGERS,
            "triggerCatalogDigest",
            ReleaseErrorCode.FAILURE_TRIGGER_CATALOG_DIGEST_INVALID,
        ),
    ],
)
def test_rejects_directory_digest_mismatch(loader, path, field, code) -> None:
    document = loader.load(path).document
    document["suiteVersion"] = "1.0.1"

    with pytest.raises(ContractError) as exc_info:
        loader(document)

    assert exc_info.value.code == code.value
    assert exc_info.value.detail == field


def _refresh_digest(document: dict[str, object]) -> None:
    field = "topologyDigest" if "topologyDigest" in document else "triggerCatalogDigest"
    unsigned = {key: value for key, value in document.items() if key != field}
    document[field] = hashlib.sha256(
        json.dumps(
            unsigned, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode()
    ).hexdigest()
