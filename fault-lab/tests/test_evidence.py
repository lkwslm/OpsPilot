from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError, ContractLoader
from fault_lab.evidence import EvidenceCodeMatcher, EvidenceRecord, GroundTruthArtifact, GroundTruthGenerator, GroundTruthValidator
from fault_lab.dataset import canonical_json, sha256_bytes


ROOT = Path(__file__).resolve().parents[2]
TRUTH = ROOT / "docs" / "design" / "contracts" / "examples" / "ground-truth" / "dependency-latency-inventory.json"


def evidence(evidence_id: str, source: str, service: str, facts: dict[str, object], observed_at: datetime) -> EvidenceRecord:
    return EvidenceRecord(evidence_id, source, f"resource:{service}", service, observed_at, facts, "a" * 64)


def test_exact_service_window_and_predicate_are_required() -> None:
    truth = json.loads(TRUTH.read_text(encoding="utf-8"))
    matcher = EvidenceCodeMatcher(truth["requiredEvidence"])
    start = datetime(2026, 7, 29, tzinfo=timezone.utc)
    valid = evidence("1", "TRACE", "order-service", {"span": {"duration_p95_ms": 2600}}, start + timedelta(seconds=1))
    assert matcher.match(valid, start, start + timedelta(minutes=1)).evidence_code == "trace.order.inventory_span_latency_high"
    wrong_service = evidence("2", "TRACE", "inventory-service", valid.facts, valid.observed_at)
    assert matcher.match(wrong_service, start, start + timedelta(minutes=1)) is None
    wrong_resource = EvidenceRecord("3", "TRACE", "resource:other", "order-service", valid.observed_at, valid.facts, "a" * 64)
    assert matcher.match(wrong_resource, start, start + timedelta(minutes=1)) is None
    assert matcher.match(valid, start + timedelta(minutes=2), start + timedelta(minutes=3)) is None


def test_ambiguous_rules_fail_closed() -> None:
    rule = {"evidenceCode": "a.b.c", "sourceType": "LOG", "service": "x", "predicate": {"field": "x", "operator": "EQ", "value": 1}}
    matcher = EvidenceCodeMatcher([rule, {**rule, "evidenceCode": "a.b.d"}])
    now = datetime.now(timezone.utc)
    with pytest.raises(ContractError, match="EVIDENCE_RULE_AMBIGUOUS"):
        matcher.match(evidence("1", "LOG", "x", {"x": 1}, now), now - timedelta(seconds=1), now + timedelta(seconds=1))


def test_ground_truth_requires_exact_required_and_one_of_codes() -> None:
    contracts = ContractLoader(ROOT)
    truth = contracts.load("ground-truth", TRUTH)
    generator = GroundTruthGenerator(contracts)
    matches = [
        type("M", (), {"evidence_id": "1", "evidence_code": "trace.order.inventory_span_latency_high", "artifact_sha256": "a" * 64})(),
        type("M", (), {"evidence_id": "2", "evidence_code": "metric.gateway.request_latency_high", "artifact_sha256": "b" * 64})(),
        type("M", (), {"evidence_id": "3", "evidence_code": "metric.inventory.resource_normal", "artifact_sha256": "c" * 64})(),
    ]
    artifact = generator.generate(dataset_run_id="run", frozen_ground_truth=truth, injection_facts={"ok": True}, recovery_facts={"ok": True}, matches_=matches)
    assert artifact.content["rootCauseCode"] == "dependency.latency.inventory"
    assert GroundTruthValidator(contracts).validate(
        artifact, dataset_run_id="run", observed_codes={match.evidence_code for match in matches}
    )["scenarioId"] == "dependency-latency-inventory"


def test_ground_truth_rejects_cross_run_and_digest_tampering() -> None:
    contracts = ContractLoader(ROOT)
    content = contracts.load("ground-truth", TRUTH)
    artifact = GroundTruthArtifact("run-a", content, sha256_bytes(canonical_json(content)))
    validator = GroundTruthValidator(contracts)
    with pytest.raises(ContractError, match="GROUND_TRUTH_RUN_ID_MISMATCH"):
        validator.validate(artifact, dataset_run_id="run-b", observed_codes=set())
    corrupted = GroundTruthArtifact("run-a", content, "0" * 64)
    with pytest.raises(ContractError, match="GROUND_TRUTH_DIGEST_INVALID"):
        validator.validate(corrupted, dataset_run_id="run-a", observed_codes=set())


@pytest.mark.parametrize(
    "name",
    [
        "dependency-latency-inventory.json",
        "database-pool-exhausted-order.json",
        "service-instance-stopped-inventory.json",
    ],
)
def test_ground_truth_generation_covers_every_frozen_scenario(name: str) -> None:
    contracts = ContractLoader(ROOT)
    truth = contracts.load("ground-truth", TRUTH.parent / name)
    codes = [rule["evidenceCode"] for rule in truth["requiredEvidence"]]
    codes.extend(group[0] for group in truth["oneOfEvidenceGroups"])
    matches = [
        type("M", (), {"evidence_id": str(index), "evidence_code": code, "artifact_sha256": f"{index:x}" * 64})()
        for index, code in enumerate(codes, 1)
    ]
    artifact = GroundTruthGenerator(contracts).generate(
        dataset_run_id="run", frozen_ground_truth=truth,
        injection_facts={"valid": True}, recovery_facts={"valid": True}, matches_=matches,
    )
    assert artifact.content["scenarioId"] == truth["scenarioId"]
