from __future__ import annotations

import copy
import hashlib
import json
import re
from pathlib import Path
from typing import Any, Mapping

import yaml

from ..contracts import ContractError
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose


FAULT_TYPES = ("UNAVAILABLE", "TIMEOUT", "AUTH", "SCHEMA")
_TARGET_FIELDS = {
    "componentId",
    "componentKind",
    "endpoint",
    "sourceId",
    "sourceKind",
    "adapterId",
    "routeKind",
    "routeRef",
    "criticality",
    "expectedTerminalState",
    "restorer",
    "assertions",
}
_IDENTITY_FIELDS = (
    "componentId",
    "componentKind",
    "endpoint",
    "sourceId",
    "sourceKind",
    "adapterId",
)
_COMPONENT_KINDS = {"MODEL_PROVIDER", "A2A_ENDPOINT", "TOOL", "SOURCE"}
_ROUTE_SCHEMES = {
    "NETWORK_PROXY": "network://",
    "FILE_FIXTURE": "file://",
    "PROCESS_FIXTURE": "process://",
}
_CRITICALITIES = {
    "MANDATORY",
    "MANDATORY_WHEN_CANDIDATES_EXIST",
    "CONDITIONAL",
    "OPTIONAL_APPROVED",
}
_TERMINAL_STATES = {"FAILED", "COMPLETED_LIMITED"}
_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$")
_DIGEST = re.compile(r"^[0-9a-f]{64}$")


class FailureCatalog:
    """Versioned Phase 8 failure catalog expanded from frozen registry targets."""

    def __init__(self, document: Mapping[str, Any]):
        if not isinstance(document, Mapping):
            _invalid("document")
        self._document = copy.deepcopy(dict(document))
        self._validate_digest()
        self._validate()
        self._cases = self._expand_cases()

    @classmethod
    def load(cls, path: Path) -> "FailureCatalog":
        try:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
        except (OSError, yaml.YAMLError) as exception:
            raise ContractError(
                ReleaseErrorCode.FAILURE_CATALOG_INVALID.value, str(exception)
            ) from exception
        return cls(document)

    @property
    def document(self) -> dict[str, Any]:
        return copy.deepcopy(self._document)

    @property
    def cases(self) -> list[dict[str, Any]]:
        return copy.deepcopy(self._cases)

    def verify_registry_coverage(
        self, discovered_targets: list[Mapping[str, Any]]
    ) -> dict[str, Any]:
        expected = {
            target["componentId"]: _identity(target)
            for target in self._document["targets"]
        }
        actual: dict[str, dict[str, str]] = {}
        for target in discovered_targets:
            if not isinstance(target, Mapping):
                _drift("discovered target is not an object")
            identity = _identity(target)
            component_id = identity["componentId"]
            if component_id in actual:
                _drift(f"duplicate discovered target: {component_id}")
            actual[component_id] = identity

        missing = sorted(set(actual) - set(expected))
        unexpected = sorted(set(expected) - set(actual))
        changed = sorted(
            component_id
            for component_id in set(expected).intersection(actual)
            if expected[component_id] != actual[component_id]
        )
        if missing or unexpected or changed:
            _drift(
                f"missing catalog targets={missing}; missing registry targets={unexpected}; "
                f"changed targets={changed}"
            )

        snapshot = [actual[key] for key in sorted(actual)]
        return {
            "schemaVersion": "1.0.0",
            "status": ReleaseStatus.PASSED.value,
            "targetCount": len(snapshot),
            "caseCount": len(self._cases),
            "missingTargets": [],
            "unexpectedTargets": [],
            "changedTargets": [],
            "registrySnapshotDigest": hashlib.sha256(
                _canonical_json(snapshot)
            ).hexdigest(),
            "targets": snapshot,
        }

    def _validate_digest(self) -> None:
        actual = self._document.get("catalogDigest")
        unsigned = {
            key: value for key, value in self._document.items() if key != "catalogDigest"
        }
        expected = hashlib.sha256(_canonical_json(unsigned)).hexdigest()
        if not isinstance(actual, str) or not _DIGEST.fullmatch(actual) or actual != expected:
            raise ContractError(
                ReleaseErrorCode.FAILURE_CATALOG_DIGEST_INVALID.value,
                "catalogDigest",
            )

    def _validate(self) -> None:
        if set(self._document) != {
            "schemaVersion",
            "suiteId",
            "suiteVersion",
            "runPurpose",
            "faultProfiles",
            "targets",
            "catalogDigest",
        }:
            _invalid("catalog fields")
        if (
            self._document["schemaVersion"] != "1.0.0"
            or self._document["suiteId"] != "phase8-capability-failure-matrix"
            or self._document["suiteVersion"] != "1.0.0"
            or self._document["runPurpose"] != RunPurpose.FAILURE_INJECTION.value
        ):
            _invalid("catalog identity")

        profiles = self._document["faultProfiles"]
        if not isinstance(profiles, Mapping) or set(profiles) != set(FAULT_TYPES):
            _invalid("fault type coverage")
        for fault_type, profile in profiles.items():
            if (
                not isinstance(profile, Mapping)
                or set(profile) != {"injector", "upstreamStatus"}
                or not _text(profile["injector"])
                or not _text(profile["upstreamStatus"])
            ):
                _invalid(f"fault profile: {fault_type}")

        targets = self._document["targets"]
        if not isinstance(targets, list) or len(targets) != 25:
            _invalid("target count")
        seen: set[str] = set()
        for target in targets:
            self._validate_target(target, seen)
        if {target["componentKind"] for target in targets} != _COMPONENT_KINDS:
            _invalid("component kind coverage")
        if {target["routeKind"] for target in targets} != set(_ROUTE_SCHEMES):
            _invalid("route kind coverage")

    @staticmethod
    def _validate_target(target: Any, seen: set[str]) -> None:
        if not isinstance(target, Mapping) or set(target) != _TARGET_FIELDS:
            _invalid("target fields")
        component_id = target["componentId"]
        if (
            not isinstance(component_id, str)
            or not _ID.fullmatch(component_id)
            or component_id in seen
            or target["componentKind"] not in _COMPONENT_KINDS
            or target["routeKind"] not in _ROUTE_SCHEMES
            or target["criticality"] not in _CRITICALITIES
            or target["expectedTerminalState"] not in _TERMINAL_STATES
            or not all(_text(target[field]) for field in ("endpoint", "sourceId", "sourceKind", "adapterId", "routeRef", "restorer"))
            or not _unique_non_empty_strings(target["assertions"])
        ):
            _invalid(f"target values: {component_id}")
        if not target["routeRef"].startswith(_ROUTE_SCHEMES[target["routeKind"]]):
            _invalid(f"target route: {component_id}")
        if target["componentKind"] == "TOOL" and target["routeRef"] == target["endpoint"]:
            _invalid(f"tool route is not a downstream boundary: {component_id}")
        if target["componentKind"] == "SOURCE" and target["sourceId"] == "not-applicable":
            _invalid(f"source identity: {component_id}")
        if target["criticality"] in {"MANDATORY", "MANDATORY_WHEN_CANDIDATES_EXIST", "OPTIONAL_APPROVED"} and target["expectedTerminalState"] != "FAILED":
            _invalid(f"critical target terminal state: {component_id}")
        seen.add(component_id)

    def _expand_cases(self) -> list[dict[str, Any]]:
        cases: list[dict[str, Any]] = []
        for target in self._document["targets"]:
            for fault_type in FAULT_TYPES:
                profile = self._document["faultProfiles"][fault_type]
                case_id = "p8-failure-" + re.sub(
                    r"[^a-z0-9]+", "-", target["componentId"].lower()
                ).strip("-") + "-" + fault_type.lower()
                cases.append(
                    {
                        "caseId": case_id,
                        "runPurpose": RunPurpose.FAILURE_INJECTION.value,
                        **copy.deepcopy(dict(target)),
                        "faultType": fault_type,
                        "injector": profile["injector"],
                        "upstreamStatus": profile["upstreamStatus"],
                    }
                )
        return cases


def discover_repository_targets(repository_root: Path) -> list[dict[str, str]]:
    """Read the four frozen registry sources without starting product services."""
    root = repository_root.resolve()
    targets: list[dict[str, str]] = []

    versions = _load_yaml(root / "deployment" / "versions.lock.yaml")
    chat = versions["chatModel"]
    models = {model["id"]: model for model in versions["models"]}
    targets.extend(
        [
            _target_identity(
                "model:chat", "MODEL_PROVIDER", "https://api.deepseek.com",
                chat["provider"], "MODEL_PROVIDER", "openai-chat-completions",
            ),
            _target_identity(
                "model:embedding", "MODEL_PROVIDER", f"model://{models['embedding']['modelId']}",
                "infinity-local", "MODEL_PROVIDER", "infinity-embedding",
            ),
            _target_identity(
                "model:rerank", "MODEL_PROVIDER", f"model://{models['reranker']['modelId']}",
                "infinity-local", "MODEL_PROVIDER", "infinity-rerank",
            ),
        ]
    )

    directory = _load_yaml(root / "deployment" / "agents" / "agent-directory.yaml")
    for agent in directory["agents"]:
        targets.append(
            _target_identity(
                f"a2a:{agent['id']}", "A2A_ENDPOINT", agent["card_url"],
                "not-applicable", "NOT_APPLICABLE", "a2a-http-json",
            )
        )

    tool_schema = json.loads(
        (root / "docs" / "design" / "contracts" / "schemas" / "tool-contracts.schema.json")
        .read_text(encoding="utf-8")
    )
    for tool_id in tool_schema["properties"]["toolName"]["enum"]:
        targets.append(
            _target_identity(
                f"tool:{tool_id}", "TOOL", f"tool://{tool_id}",
                "not-applicable", "NOT_APPLICABLE", "tool-runtime",
            )
        )

    composition = (
        root / "opspilot-server" / "src" / "main" / "java" / "io" / "github"
        / "opspilot" / "server" / "OpsPilotCompositionRoot.java"
    ).read_text(encoding="utf-8")
    source_registrations = {
        "SecretResolvingSourceAdapter.prometheus(": ("phase0-prometheus", "observability-source://phase0/prometheus", "PROMETHEUS", "prometheus-metric"),
        "SecretResolvingSourceAdapter.jaegerV1(": ("phase0-jaeger-v1", "observability-source://phase0/jaeger-v1", "JAEGER", "jaeger-trace-v1"),
        "SecretResolvingSourceAdapter.jaegerV2(": ("phase0-jaeger-v2", "observability-source://phase0/jaeger-v2", "JAEGER", "jaeger-trace-v2"),
        "new JsonlLogAdapter(": ("phase0-jsonl", "observability-source://phase0/jsonl", "FILE", "jsonl-log"),
        "SecretResolvingSourceAdapter.actuator(": ("sample-actuator", "observability-source://sample/actuator", "HTTP", "spring-actuator-health"),
        "new StaticComposeTopologyAdapter(": ("phase0-compose", "observability-source://phase0/compose", "FILE", "static-compose-topology"),
        "SecretResolvingSourceAdapter.httpHealth(": ("sample-http-health", "observability-source://sample/http-health", "HTTP", "http-health"),
        "new ControlledConfigAdapter(": ("sample-file-config", "observability-source://sample/file-config", "FILE", "controlled-file-config"),
    }
    for marker, (source_id, endpoint, source_kind, adapter_id) in source_registrations.items():
        if composition.count(marker) != 1:
            _drift(f"source registry composition changed: {marker}")
        targets.append(
            _target_identity(
                f"source:{source_id}", "SOURCE", endpoint,
                source_id, source_kind, adapter_id,
            )
        )
    return targets


def _load_yaml(path: Path) -> dict[str, Any]:
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exception:
        _drift(f"cannot read registry source {path}: {exception}")
    if not isinstance(value, dict):
        _drift(f"registry source is not an object: {path}")
    return value


def _target_identity(
    component_id: str,
    component_kind: str,
    endpoint: str,
    source_id: str,
    source_kind: str,
    adapter_id: str,
) -> dict[str, str]:
    return {
        "componentId": component_id,
        "componentKind": component_kind,
        "endpoint": endpoint,
        "sourceId": source_id,
        "sourceKind": source_kind,
        "adapterId": adapter_id,
    }


def _identity(target: Mapping[str, Any]) -> dict[str, str]:
    try:
        identity = {field: target[field] for field in _IDENTITY_FIELDS}
    except KeyError as exception:
        _drift(f"target identity missing {exception.args[0]}")
    if not all(_text(value) for value in identity.values()):
        _drift("target identity contains an empty value")
    return identity


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _unique_non_empty_strings(value: Any) -> bool:
    return (
        isinstance(value, list)
        and bool(value)
        and all(_text(item) for item in value)
        and len(value) == len(set(value))
    )


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_CATALOG_INVALID.value, detail)


def _drift(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_REGISTRY_DRIFT.value, detail)
