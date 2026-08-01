from __future__ import annotations

import hashlib
import json
import re
from typing import Any, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose


FROZEN_SCENARIOS = (
    ("database-pool-exhausted-order", "1.0.0"),
    ("dependency-latency-inventory", "1.0.0"),
    ("service-instance-stopped-inventory", "1.0.0"),
)
RUNS_PER_SCENARIO = 5
_DIGEST = re.compile(r"^[0-9a-f]{64}$")
_BATCH_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


class QualityBatchPlanner:
    def plan(
        self,
        release_batch_id: str,
        *,
        snapshot: Mapping[str, Any],
        profile: Mapping[str, Any],
        wp01_gate: Mapping[str, Any],
    ) -> dict[str, Any]:
        if not _BATCH_ID.fullmatch(release_batch_id):
            _invalid("releaseBatchId")
        snapshot_digest = snapshot.get("snapshotDigest")
        if not isinstance(snapshot_digest, str) or not _DIGEST.fullmatch(snapshot_digest):
            _invalid("snapshotDigest")
        _validate_gate(snapshot_digest, profile, snapshot, wp01_gate)
        _validate_scenarios(snapshot.get("scenarios"))
        if profile.get("runs_per_scenario") != RUNS_PER_SCENARIO:
            _invalid("runs_per_scenario must equal 5")

        slots = [
            {
                "slotId": f"{scenario_id}:{ordinal:02d}",
                "scenarioId": scenario_id,
                "scenarioVersion": scenario_version,
                "ordinal": ordinal,
                "runPurpose": RunPurpose.RELEASE_QUALITY.value,
                "snapshotDigest": snapshot_digest,
                "status": "PLANNED",
            }
            for scenario_id, scenario_version in FROZEN_SCENARIOS
            for ordinal in range(1, RUNS_PER_SCENARIO + 1)
        ]
        plan: dict[str, Any] = {
            "schemaVersion": "1.0.0",
            "releaseBatchId": release_batch_id,
            "runPurpose": RunPurpose.RELEASE_QUALITY.value,
            "snapshotDigest": snapshot_digest,
            "evaluationProfileId": profile["profile_id"],
            "runsPerScenario": RUNS_PER_SCENARIO,
            "totalSlots": len(slots),
            "slots": slots,
        }
        plan["planDigest"] = hashlib.sha256(_canonical_json(plan)).hexdigest()
        return plan


def _validate_gate(
    snapshot_digest: str,
    profile: Mapping[str, Any],
    snapshot: Mapping[str, Any],
    gate: Mapping[str, Any],
) -> None:
    try:
        checks = gate["checks"]
        approved = (
            gate["status"] == ReleaseStatus.PASSED.value
            and checks["snapshot"]["status"] == ReleaseStatus.PASSED.value
            and checks["snapshot"]["snapshotDigest"] == snapshot_digest
            and checks["evaluationProfile"]["status"] == ReleaseStatus.PASSED.value
            and checks["runLedger"]["status"] == ReleaseStatus.PASSED.value
            and checks["evaluationProfile"]["profileId"] == profile["profile_id"]
            and snapshot["evaluationProfile"]["profileId"] == profile["profile_id"]
        )
    except (KeyError, TypeError):
        approved = False
    if not approved:
        raise ContractError(
            ReleaseErrorCode.SNAPSHOT_NOT_APPROVED.value,
            "WP01 gate, snapshot, or profile mismatch",
        )


def _validate_scenarios(value: Any) -> None:
    if not isinstance(value, list):
        _invalid("scenarios")
    try:
        scenarios = [
            (item["scenarioId"], item["scenarioVersion"])
            for item in value
            if isinstance(item, Mapping)
        ]
    except KeyError:
        _invalid("scenarios")
    if len(scenarios) != len(value) or sorted(scenarios) != list(FROZEN_SCENARIOS):
        _invalid("frozen scenario set")


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.QUALITY_PLAN_INVALID.value, detail)


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
