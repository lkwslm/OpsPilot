from __future__ import annotations

import hashlib
import json
import os
import xml.etree.ElementTree as ET
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from ..contracts import ContractError, ContractLoader
from .ledger import RunLedger
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose


class BaselineGatePublisher:
    """Publish the immutable evidence bundle for the WP01 baseline gate."""

    def __init__(self, output_root: Path):
        self.output_root = output_root.resolve()

    def publish(
        self,
        release_batch_id: str,
        *,
        snapshot_path: Path,
        baseline_report_path: Path,
        evaluation_profile_path: Path,
        test_report_path: Path,
    ) -> dict[str, Any]:
        gate_path = self.output_root / "release-baseline-gate.json"
        try:
            snapshot = _json_document(snapshot_path)
            baseline = _json_document(baseline_report_path)
            if not evaluation_profile_path.is_file():
                raise ContractError(
                    ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value,
                    evaluation_profile_path.name,
                )
            profile = ContractLoader().load("evaluation-profile", evaluation_profile_path)
            test_count = _passing_test_count(test_report_path)
            _validate(release_batch_id, snapshot, baseline, profile)
            gate = self._publish_passed(
                release_batch_id,
                snapshot,
                baseline,
                profile,
                snapshot_path=snapshot_path,
                baseline_report_path=baseline_report_path,
                evaluation_profile_path=evaluation_profile_path,
                test_report_path=test_report_path,
                test_count=test_count,
            )
        except ContractError as exc:
            blocked_codes = {
                ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value,
                ReleaseErrorCode.CONFIG_INCOMPLETE.value,
                "CONTRACT_DOCUMENT_INVALID",
                "CONTRACT_SCHEMA_INVALID",
            }
            gate = {
                "schemaVersion": "1.0.0",
                "releaseBatchId": release_batch_id,
                "status": (
                    ReleaseStatus.BLOCKED.value
                    if exc.code in blocked_codes
                    else ReleaseStatus.FAILED.value
                ),
                "errorCode": exc.code,
                "detail": exc.detail,
                "publishedAt": _now(),
            }
        _write_new_json(gate_path, gate)
        return gate

    def _publish_passed(
        self,
        release_batch_id: str,
        snapshot: dict[str, Any],
        baseline: dict[str, Any],
        profile: dict[str, Any],
        *,
        snapshot_path: Path,
        baseline_report_path: Path,
        evaluation_profile_path: Path,
        test_report_path: Path,
        test_count: int,
    ) -> dict[str, Any]:
        published_snapshot = (
            self.output_root / "snapshot" / f"{release_batch_id}-snapshot.json"
        )
        _write_new_json(published_snapshot, snapshot)
        ledger = RunLedger(self.output_root / "run-ledger")
        entry = ledger.append(
            {
                "releaseBatchId": release_batch_id,
                "runPurpose": RunPurpose.BASELINE_ONLY.value,
                "runIdentity": {
                    "incidentId": baseline["runIdentity"]["incidentId"],
                    "runId": baseline["runIdentity"]["runId"],
                    "datasetRunId": baseline["scenario"]["datasetRunId"],
                    "a2aContextId": None,
                    "supervisorSessionId": None,
                    "a2aTasks": [],
                    "attempt": 1,
                },
                "startedAt": baseline["startedAt"],
                "endedAt": baseline["endedAt"],
                "environmentDigest": snapshot["snapshotDigest"],
                "status": ReleaseStatus.PASSED.value,
                "reason": None,
            },
            {
                f"artifact://phase8/08-WP01/baseline/{baseline_report_path.name}": (
                    baseline_report_path
                ),
                f"artifact://phase8/08-WP01/snapshot/{published_snapshot.name}": (
                    published_snapshot
                ),
                f"artifact://phase8/08-WP01/tests/{test_report_path.name}": test_report_path,
                f"artifact://contracts/profiles/{evaluation_profile_path.name}": (
                    evaluation_profile_path
                ),
            },
        )
        return {
            "schemaVersion": "1.0.0",
            "releaseBatchId": release_batch_id,
            "status": ReleaseStatus.PASSED.value,
            "publishedAt": _now(),
            "checks": {
                "faultLabTests": {
                    "status": ReleaseStatus.PASSED.value,
                    "tests": test_count,
                    "artifact": _artifact_ref(
                        test_report_path,
                        f"artifact://phase8/08-WP01/tests/{test_report_path.name}",
                    ),
                },
                "snapshot": {
                    "status": ReleaseStatus.PASSED.value,
                    "snapshotDigest": snapshot["snapshotDigest"],
                    "artifact": _artifact_ref(
                        published_snapshot,
                        f"artifact://phase8/08-WP01/snapshot/{published_snapshot.name}",
                    ),
                },
                "baselineSmoke": {
                    "status": ReleaseStatus.PASSED.value,
                    "runId": baseline["runIdentity"]["runId"],
                    "totalTokens": baseline["tokenUsage"]["totalTokens"],
                    "artifact": _artifact_ref(
                        baseline_report_path,
                        f"artifact://phase8/08-WP01/baseline/{baseline_report_path.name}",
                    ),
                },
                "evaluationProfile": {
                    "status": ReleaseStatus.PASSED.value,
                    "profileId": profile["profile_id"],
                    "totalTokens": profile["efficiency_limits"]["total_tokens"],
                    "artifact": _artifact_ref(
                        evaluation_profile_path,
                        f"artifact://contracts/profiles/{evaluation_profile_path.name}",
                    ),
                },
                "runLedger": {
                    "status": ReleaseStatus.PASSED.value,
                    "entryDigest": entry["entryDigest"],
                    "ledgerArtifact": _artifact_ref(
                        ledger.ledger_path,
                        "artifact://phase8/08-WP01/run-ledger/runs.jsonl",
                    ),
                    "indexArtifact": _artifact_ref(
                        ledger.index_path,
                        "artifact://phase8/08-WP01/run-ledger/index.json",
                    ),
                },
            },
        }


def _validate(
    release_batch_id: str,
    snapshot: dict[str, Any],
    baseline: dict[str, Any],
    profile: dict[str, Any],
) -> None:
    try:
        valid = (
            baseline["releaseBatchId"] == release_batch_id
            and baseline["runPurpose"] == RunPurpose.BASELINE_ONLY.value
            and baseline["includedInReleaseAggregation"] is False
            and baseline["status"] == ReleaseStatus.PASSED.value
            and baseline["environment"]["snapshotDigest"] == snapshot["snapshotDigest"]
            and isinstance(baseline["runIdentity"]["runId"], str)
            and isinstance(baseline["scenario"]["datasetRunId"], str)
            and isinstance(baseline["tokenUsage"]["totalTokens"], int)
            and isinstance(profile["efficiency_limits"]["total_tokens"], int)
            and profile["efficiency_limits"]["total_tokens"] > 0
        )
    except (KeyError, TypeError):
        valid = False
    if not valid:
        raise ContractError(
            ReleaseErrorCode.BASELINE_GATE_INPUT_INVALID.value,
            "baseline, snapshot, or evaluation profile",
        )


def _passing_test_count(path: Path) -> int:
    if not path.is_file():
        raise ContractError(ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value, path.name)
    try:
        root = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise ContractError(ReleaseErrorCode.BASELINE_GATE_INPUT_INVALID.value, path.name) from exc
    suites = [root] if root.tag == "testsuite" or root.get("tests") else root.findall("testsuite")
    try:
        tests = sum(int(suite.get("tests", "0")) for suite in suites)
        failures = sum(int(suite.get("failures", "0")) for suite in suites)
        errors = sum(int(suite.get("errors", "0")) for suite in suites)
    except ValueError as exc:
        raise ContractError(ReleaseErrorCode.BASELINE_GATE_INPUT_INVALID.value, path.name) from exc
    if tests < 1 or failures or errors:
        raise ContractError(
            ReleaseErrorCode.BASELINE_TESTS_FAILED.value,
            f"tests={tests},failures={failures},errors={errors}",
        )
    return tests


def _json_document(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise ContractError(ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value, path.name)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(ReleaseErrorCode.BASELINE_GATE_INPUT_INVALID.value, path.name) from exc
    if not isinstance(value, dict):
        raise ContractError(ReleaseErrorCode.BASELINE_GATE_INPUT_INVALID.value, path.name)
    return value


def _artifact_ref(path: Path, uri: str) -> dict[str, Any]:
    content = path.read_bytes()
    return {
        "uri": uri,
        "size": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
    }


def _write_new_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with temporary.open("x", encoding="utf-8", newline="\n") as handle:
            json.dump(value, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        if path.exists():
            raise ContractError(ReleaseErrorCode.BASELINE_GATE_EXISTS.value, path.name)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
