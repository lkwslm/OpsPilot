from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release.failure_catalog import (
    FailureCatalog,
    discover_repository_targets,
)
from fault_lab.release.model import ReleaseErrorCode, RunPurpose


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
CATALOG = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "phase8"
    / "failure-catalog-v1.yaml"
)
FAULT_TYPES = {"UNAVAILABLE", "TIMEOUT", "AUTH", "SCHEMA"}


def test_loads_versioned_catalog_and_expands_complete_failure_matrix() -> None:
    catalog = FailureCatalog.load(CATALOG)
    document = catalog.document
    cases = catalog.cases

    assert document["schemaVersion"] == "1.0.0"
    assert document["suiteVersion"] == "1.0.0"
    assert document["runPurpose"] == RunPurpose.FAILURE_INJECTION.value
    assert len(document["targets"]) == 25
    assert len(cases) == 100
    assert len({case["caseId"] for case in cases}) == 100
    assert {case["faultType"] for case in cases} == FAULT_TYPES
    assert {
        (case["componentId"], case["faultType"]) for case in cases
    } == {
        (target["componentId"], fault_type)
        for target in document["targets"]
        for fault_type in FAULT_TYPES
    }
    assert all(case["runPurpose"] == RunPurpose.FAILURE_INJECTION.value for case in cases)
    assert all(case["injector"] and case["restorer"] for case in cases)
    assert all(case["assertions"] for case in cases)
    assert all(
        set(case).issuperset(
            {
                "caseId",
                "componentId",
                "componentKind",
                "endpoint",
                "sourceId",
                "sourceKind",
                "adapterId",
                "faultType",
                "injector",
                "restorer",
                "criticality",
                "expectedTerminalState",
                "assertions",
            }
        )
        for case in cases
    )


def test_catalog_matches_frozen_repository_registries() -> None:
    catalog = FailureCatalog.load(CATALOG)
    discovered = discover_repository_targets(REPOSITORY_ROOT)

    report = catalog.verify_registry_coverage(discovered)

    assert report["status"] == "PASSED"
    assert report["targetCount"] == 25
    assert report["caseCount"] == 100
    assert report["missingTargets"] == []
    assert report["unexpectedTargets"] == []
    assert report["changedTargets"] == []
    assert len(report["registrySnapshotDigest"]) == 64


def test_registry_diff_rejects_unregistered_new_tool() -> None:
    catalog = FailureCatalog.load(CATALOG)
    discovered = discover_repository_targets(REPOSITORY_ROOT)
    discovered.append(
        {
            "componentId": "tool:NewUndeclaredTool",
            "componentKind": "TOOL",
            "endpoint": "tool://NewUndeclaredTool",
            "sourceId": "not-applicable",
            "sourceKind": "NOT_APPLICABLE",
            "adapterId": "tool-runtime",
        }
    )

    with pytest.raises(ContractError) as exc_info:
        catalog.verify_registry_coverage(discovered)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_REGISTRY_DRIFT.value
    assert "tool:NewUndeclaredTool" in exc_info.value.detail


@pytest.mark.parametrize(
    "change",
    [
        lambda value: value["targets"].pop(),
        lambda value: value["targets"][0].pop("restorer"),
        lambda value: value["targets"][0].update(criticality="UNKNOWN"),
        lambda value: value["faultProfiles"].pop("AUTH"),
        lambda value: value["targets"][0].update(componentId=value["targets"][1]["componentId"]),
    ],
    ids=("missing-target", "missing-restorer", "criticality", "missing-fault", "duplicate-target"),
)
def test_rejects_incomplete_or_invalid_catalog(change) -> None:
    document = FailureCatalog.load(CATALOG).document
    change(document)
    document["catalogDigest"] = _digest(document)

    with pytest.raises(ContractError) as exc_info:
        FailureCatalog(document)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_CATALOG_INVALID.value


def test_rejects_catalog_digest_mismatch() -> None:
    document = copy.deepcopy(FailureCatalog.load(CATALOG).document)
    document["suiteVersion"] = "1.0.1"

    with pytest.raises(ContractError) as exc_info:
        FailureCatalog(document)

    assert exc_info.value.code == ReleaseErrorCode.FAILURE_CATALOG_DIGEST_INVALID.value


def _digest(document: dict[str, object]) -> str:
    unsigned = {key: value for key, value in document.items() if key != "catalogDigest"}
    encoded = json.dumps(
        unsigned, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()
