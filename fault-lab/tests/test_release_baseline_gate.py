from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from fault_lab.release.baseline_gate import BaselineGatePublisher
from fault_lab.release.model import ReleaseErrorCode, ReleaseStatus


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def write_json(path: Path, value: dict[str, Any]) -> Path:
    path.write_text(json.dumps(value), encoding="utf-8")
    return path


def snapshot() -> dict[str, Any]:
    return {
        "schemaVersion": "1.0.0",
        "snapshotDigest": "a" * 64,
        "commit": "b" * 40,
        "evaluationProfile": {"profileId": "mvp-v1", "digest": "c" * 64},
        "compose": {"digest": "d" * 64},
        "hardware": {"cpu": {"model": "test"}},
    }


def baseline_report() -> dict[str, Any]:
    return {
        "schemaVersion": "1.0.0",
        "releaseBatchId": "phase8-baseline-01",
        "runPurpose": "BASELINE_ONLY",
        "includedInReleaseAggregation": False,
        "status": "PASSED",
        "scenario": {
            "scenarioId": "dependency-latency-inventory",
            "scenarioVersion": "1.0.0",
            "datasetRunId": "dataset-01",
        },
        "runIdentity": {"incidentId": "incident-01", "runId": "run-01"},
        "startedAt": "2026-08-01T01:02:03.000Z",
        "endedAt": "2026-08-01T01:03:04.000Z",
        "environment": {"snapshotDigest": "a" * 64},
        "tokenUsage": {"totalTokens": 48646},
    }


def junit_report(path: Path) -> Path:
    path.write_text(
        '<testsuites tests="80" failures="0" errors="0" skipped="0"></testsuites>',
        encoding="utf-8",
    )
    return path


def test_publishes_snapshot_ledger_and_passed_gate(tmp_path: Path) -> None:
    inputs = tmp_path / "inputs"
    inputs.mkdir()
    snapshot_path = write_json(inputs / "snapshot.json", snapshot())
    baseline_path = write_json(inputs / "baseline.json", baseline_report())
    tests_path = junit_report(inputs / "tests.xml")
    output = tmp_path / "outputs" / "08-WP01"

    gate = BaselineGatePublisher(output).publish(
        "phase8-baseline-01",
        snapshot_path=snapshot_path,
        baseline_report_path=baseline_path,
        evaluation_profile_path=(
            REPOSITORY_ROOT / "docs/design/contracts/profiles/mvp-v2.yaml"
        ),
        test_report_path=tests_path,
    )

    assert gate["status"] == ReleaseStatus.PASSED.value
    assert gate["checks"]["faultLabTests"]["tests"] == 80
    assert gate["checks"]["evaluationProfile"]["profileId"] == "mvp-v2"
    assert gate["checks"]["evaluationProfile"]["totalTokens"] == 65536
    assert json.loads((output / "release-baseline-gate.json").read_text()) == gate
    published_snapshot = output / "snapshot" / "phase8-baseline-01-snapshot.json"
    assert json.loads(published_snapshot.read_text()) == snapshot()
    entries = (output / "run-ledger" / "runs.jsonl").read_text().splitlines()
    assert len(entries) == 1
    entry = json.loads(entries[0])
    assert entry["runIdentity"]["runId"] == "run-01"
    assert entry["runIdentity"]["a2aContextId"] is None
    assert entry["runIdentity"]["supervisorSessionId"] is None
    assert entry["runIdentity"]["a2aTasks"] == []
    assert entry["artifactManifest"]["artifacts"]


def test_missing_profile_publishes_blocked_gate(tmp_path: Path) -> None:
    snapshot_path = write_json(tmp_path / "snapshot.json", snapshot())
    baseline_path = write_json(tmp_path / "baseline.json", baseline_report())
    tests_path = junit_report(tmp_path / "tests.xml")
    output = tmp_path / "outputs" / "08-WP01"

    gate = BaselineGatePublisher(output).publish(
        "phase8-baseline-01",
        snapshot_path=snapshot_path,
        baseline_report_path=baseline_path,
        evaluation_profile_path=tmp_path / "missing-profile.yaml",
        test_report_path=tests_path,
    )

    assert gate["status"] == ReleaseStatus.BLOCKED.value
    assert gate["errorCode"] == ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value
    assert not (output / "run-ledger" / "runs.jsonl").exists()
