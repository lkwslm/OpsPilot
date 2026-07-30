from __future__ import annotations

import copy
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from .contracts import ContractError, ContractLoader
from .dataset import canonical_json, sha256_bytes
from .predicates import matches


@dataclass(frozen=True)
class EvidenceRecord:
    evidence_id: str
    source_type: str
    resource_id: str
    service: str
    observed_at: datetime
    facts: dict[str, Any]
    artifact_sha256: str


@dataclass(frozen=True)
class EvidenceMatch:
    evidence_id: str
    evidence_code: str
    artifact_sha256: str


class EvidenceCodeMatcher:
    def __init__(self, rules: list[dict[str, Any]]):
        codes = [rule["evidenceCode"] for rule in rules]
        if len(codes) != len(set(codes)):
            raise ContractError("EVIDENCE_RULE_DUPLICATE", "evidenceCode")
        self.rules = copy.deepcopy(rules)

    def match(
        self,
        evidence: EvidenceRecord,
        window_start: datetime,
        window_end: datetime,
    ) -> EvidenceMatch | None:
        if not evidence.resource_id or not (window_start <= evidence.observed_at < window_end):
            return None
        matched = [
            rule
            for rule in self.rules
            if rule["sourceType"] == evidence.source_type
            and rule["service"] == evidence.service
            and evidence.resource_id == f"resource:{rule['service']}"
            and matches(evidence.facts, rule["predicate"])
        ]
        if len(matched) > 1:
            raise ContractError("EVIDENCE_RULE_AMBIGUOUS", evidence.evidence_id)
        if not matched:
            return None
        return EvidenceMatch(evidence.evidence_id, matched[0]["evidenceCode"], evidence.artifact_sha256)

    @staticmethod
    def deduplicate(matches_: list[EvidenceMatch]) -> dict[str, EvidenceMatch]:
        return {match.evidence_code: match for match in sorted(matches_, key=lambda item: item.evidence_id)}


@dataclass(frozen=True)
class GroundTruthArtifact:
    dataset_run_id: str
    content: dict[str, Any]
    sha256: str


class GroundTruthGenerator:
    def __init__(self, contracts: ContractLoader):
        self.contracts = contracts

    def generate(
        self,
        *,
        dataset_run_id: str,
        frozen_ground_truth: dict[str, Any],
        injection_facts: dict[str, Any],
        recovery_facts: dict[str, Any],
        matches_: list[EvidenceMatch],
    ) -> GroundTruthArtifact:
        if not injection_facts or not recovery_facts:
            raise ContractError("GROUND_TRUTH_EXECUTION_FACTS_MISSING", dataset_run_id)
        content = self.contracts.validate("ground-truth", copy.deepcopy(frozen_ground_truth))
        observed = set(EvidenceCodeMatcher.deduplicate(matches_))
        if any(not re.fullmatch(r"[0-9a-f]{64}", match.artifact_sha256) for match in matches_):
            raise ContractError("GROUND_TRUTH_ARTIFACT_HASH_INVALID", dataset_run_id)
        required = {rule["evidenceCode"] for rule in content["requiredEvidence"]}
        if not required.issubset(observed):
            raise ContractError("GROUND_TRUTH_REQUIRED_EVIDENCE_MISSING", ",".join(sorted(required - observed)))
        for group in content["oneOfEvidenceGroups"]:
            if not observed.intersection(group):
                raise ContractError("GROUND_TRUTH_ONE_OF_EVIDENCE_MISSING", ",".join(group))
        return GroundTruthArtifact(dataset_run_id, content, sha256_bytes(canonical_json(content)))


class GroundTruthValidator:
    def __init__(self, contracts: ContractLoader):
        self.contracts = contracts

    def validate(self, artifact: GroundTruthArtifact, *, dataset_run_id: str,
                 observed_codes: set[str]) -> dict[str, Any]:
        if artifact.dataset_run_id != dataset_run_id:
            raise ContractError("GROUND_TRUTH_RUN_ID_MISMATCH", artifact.dataset_run_id)
        content = self.contracts.validate("ground-truth", copy.deepcopy(artifact.content))
        if artifact.sha256 != sha256_bytes(canonical_json(content)):
            raise ContractError("GROUND_TRUTH_DIGEST_INVALID", artifact.dataset_run_id)
        required = {rule["evidenceCode"] for rule in content["requiredEvidence"]}
        if not required.issubset(observed_codes):
            raise ContractError("GROUND_TRUTH_REQUIRED_EVIDENCE_MISSING", dataset_run_id)
        if any(not observed_codes.intersection(group) for group in content["oneOfEvidenceGroups"]):
            raise ContractError("GROUND_TRUTH_ONE_OF_EVIDENCE_MISSING", dataset_run_id)
        if content["rootCauseCode"] in content["forbiddenRootCauseCodes"]:
            raise ContractError("GROUND_TRUTH_ROOT_CAUSE_FORBIDDEN", content["rootCauseCode"])
        return content
