from __future__ import annotations

import copy
import hashlib
import json
import re
from pathlib import Path
from typing import Any, Mapping

import yaml

from ..contracts import ContractError
from .failure_catalog import FAULT_TYPES, FailureCatalog
from .model import ReleaseErrorCode, ReleaseStatus


_ROUTE_KINDS = {
    "NETWORK_PROXY": "network://",
    "FILE_FIXTURE": "file://",
    "PROCESS_FIXTURE": "process://",
}
_DIGEST = re.compile(r"^[0-9a-f]{64}$")
_TOPOLOGY_FIELDS = {
    "routeRef",
    "routeKind",
    "topologyRef",
    "consumerService",
    "consumerConfig",
    "upstream",
    "injectorEndpoint",
    "healthProbe",
    "residualProbe",
    "restoreAction",
}
_TRIGGER_GROUP_FIELDS = {
    "componentId",
    "scenarioVersion",
    "ticketRef",
    "inputFixture",
    "preconditions",
    "expectedInvocation",
    "evidenceSelectors",
    "cases",
}


class FailureRouteTopology:
    """Frozen mapping from catalog routes to real Compose data-plane seams."""

    def __init__(self, document: Mapping[str, Any]):
        if not isinstance(document, Mapping):
            _topology_invalid("document")
        self._document = copy.deepcopy(dict(document))
        _validate_digest(
            self._document,
            "topologyDigest",
            ReleaseErrorCode.FAILURE_TOPOLOGY_DIGEST_INVALID,
        )
        self._routes = self._validate()

    @classmethod
    def load(cls, path: Path) -> "FailureRouteTopology":
        return cls(_load_yaml(path, ReleaseErrorCode.FAILURE_TOPOLOGY_INVALID))

    @property
    def document(self) -> dict[str, Any]:
        return copy.deepcopy(self._document)

    @property
    def routes(self) -> dict[str, dict[str, Any]]:
        return copy.deepcopy(self._routes)

    def _validate(self) -> dict[str, dict[str, Any]]:
        if set(self._document) != {
            "schemaVersion",
            "suiteId",
            "suiteVersion",
            "routes",
            "topologyDigest",
        }:
            _topology_invalid("topology fields")
        if (
            self._document["schemaVersion"] != "1.0.0"
            or self._document["suiteId"] != "phase8-failure-route-topology"
            or self._document["suiteVersion"] != "1.0.0"
        ):
            _topology_invalid("topology identity")
        routes = self._document["routes"]
        if not isinstance(routes, list) or not routes:
            _topology_invalid("routes")
        by_ref: dict[str, dict[str, Any]] = {}
        topology_refs: set[str] = set()
        for route in routes:
            if not isinstance(route, Mapping) or set(route) != _TOPOLOGY_FIELDS:
                _topology_invalid("route fields")
            route_ref = route.get("routeRef")
            route_kind = route.get("routeKind")
            topology_ref = route.get("topologyRef")
            if (
                route_kind not in _ROUTE_KINDS
                or not _text(route_ref)
                or not str(route_ref).startswith(_ROUTE_KINDS[str(route_kind)])
                or not all(_text(route.get(field)) for field in _TOPOLOGY_FIELDS - {"routeKind"})
                or route_ref in by_ref
                or topology_ref in topology_refs
            ):
                _topology_invalid(f"route values: {route_ref}")
            by_ref[str(route_ref)] = copy.deepcopy(dict(route))
            topology_refs.add(str(topology_ref))
        if {route["routeKind"] for route in routes} != set(_ROUTE_KINDS):
            _topology_invalid("route kind coverage")
        return by_ref


class FailureTriggerCatalog:
    """Frozen per-case inputs, preconditions, and invocation evidence selectors."""

    def __init__(self, document: Mapping[str, Any]):
        if not isinstance(document, Mapping):
            _trigger_invalid("document")
        self._document = copy.deepcopy(dict(document))
        _validate_digest(
            self._document,
            "triggerCatalogDigest",
            ReleaseErrorCode.FAILURE_TRIGGER_CATALOG_DIGEST_INVALID,
        )
        self._cases = self._validate()

    @classmethod
    def load(cls, path: Path) -> "FailureTriggerCatalog":
        return cls(_load_yaml(path, ReleaseErrorCode.FAILURE_TRIGGER_CATALOG_INVALID))

    @property
    def document(self) -> dict[str, Any]:
        return copy.deepcopy(self._document)

    @property
    def cases(self) -> dict[str, dict[str, Any]]:
        return copy.deepcopy(self._cases)

    def _validate(self) -> dict[str, dict[str, Any]]:
        if set(self._document) != {
            "schemaVersion",
            "suiteId",
            "suiteVersion",
            "triggerGroups",
            "triggerCatalogDigest",
        }:
            _trigger_invalid("trigger catalog fields")
        if (
            self._document["schemaVersion"] != "1.0.0"
            or self._document["suiteId"] != "phase8-failure-trigger-catalog"
            or self._document["suiteVersion"] != "1.0.0"
        ):
            _trigger_invalid("trigger catalog identity")
        groups = self._document["triggerGroups"]
        if not isinstance(groups, list) or not groups:
            _trigger_invalid("trigger groups")
        by_case: dict[str, dict[str, Any]] = {}
        components: set[str] = set()
        trigger_refs: set[str] = set()
        for group in groups:
            if not isinstance(group, Mapping) or set(group) != _TRIGGER_GROUP_FIELDS:
                _trigger_invalid("trigger group fields")
            component_id = group.get("componentId")
            expected = group.get("expectedInvocation")
            if (
                not _text(component_id)
                or component_id in components
                or not all(
                    _text(group.get(field))
                    for field in ("scenarioVersion", "ticketRef", "inputFixture")
                )
                or not _strings(group.get("preconditions"))
                or not _strings(group.get("evidenceSelectors"))
                or not isinstance(expected, Mapping)
                or set(expected) != {"componentId", "routeRef"}
                or expected.get("componentId") != component_id
                or not _text(expected.get("routeRef"))
            ):
                _trigger_invalid(f"trigger group values: {component_id}")
            cases = group.get("cases")
            if not isinstance(cases, list) or not cases:
                _trigger_invalid(f"trigger cases: {component_id}")
            components.add(str(component_id))
            for case in cases:
                if not isinstance(case, Mapping) or set(case) != {
                    "caseId",
                    "triggerRef",
                    "faultType",
                }:
                    _trigger_invalid(f"trigger case fields: {component_id}")
                case_id = case.get("caseId")
                trigger_ref = case.get("triggerRef")
                if (
                    not _text(case_id)
                    or not _text(trigger_ref)
                    or case.get("faultType") not in FAULT_TYPES
                    or case_id in by_case
                    or trigger_ref in trigger_refs
                ):
                    _trigger_invalid(f"trigger case values: {case_id}")
                by_case[str(case_id)] = {
                    **copy.deepcopy(dict(case)),
                    "componentId": component_id,
                    "scenarioVersion": group["scenarioVersion"],
                    "ticketRef": group["ticketRef"],
                    "inputFixture": group["inputFixture"],
                    "preconditions": copy.deepcopy(group["preconditions"]),
                    "expectedInvocation": copy.deepcopy(dict(expected)),
                    "evidenceSelectors": copy.deepcopy(group["evidenceSelectors"]),
                }
                trigger_refs.add(str(trigger_ref))
        return by_case


class FailurePlan:
    """Binds the failure catalog to its frozen topology and per-case triggers."""

    def __init__(
        self,
        catalog: FailureCatalog,
        topology: FailureRouteTopology,
        triggers: FailureTriggerCatalog,
    ):
        self._catalog = catalog
        self._topology = topology
        self._triggers = triggers
        self._report = self._verify_coverage()
        topology_by_route = topology.routes
        trigger_by_case = triggers.cases
        self._cases = []
        for case in catalog.cases:
            trigger = trigger_by_case[case["caseId"]]
            self._cases.append(
                {
                    **case,
                    "topologyRef": topology_by_route[case["routeRef"]]["topologyRef"],
                    "triggerRef": trigger["triggerRef"],
                    "scenarioVersion": trigger["scenarioVersion"],
                    "ticketRef": trigger["ticketRef"],
                    "inputFixture": trigger["inputFixture"],
                    "preconditions": copy.deepcopy(trigger["preconditions"]),
                    "expectedInvocation": copy.deepcopy(trigger["expectedInvocation"]),
                    "evidenceSelectors": copy.deepcopy(trigger["evidenceSelectors"]),
                }
            )

    @property
    def cases(self) -> list[dict[str, Any]]:
        return copy.deepcopy(self._cases)

    @property
    def document(self) -> dict[str, Any]:
        return self._catalog.document

    @property
    def coverage_report(self) -> dict[str, Any]:
        return copy.deepcopy(self._report)

    def _verify_coverage(self) -> dict[str, Any]:
        expected_routes: dict[str, str] = {}
        for target in self._catalog.document["targets"]:
            route_ref = target["routeRef"]
            route_kind = target["routeKind"]
            previous = expected_routes.setdefault(route_ref, route_kind)
            if previous != route_kind:
                _plan_drift(f"catalog route kind conflict: {route_ref}")
        actual_routes = {
            route_ref: route["routeKind"]
            for route_ref, route in self._topology.routes.items()
        }
        route_missing = sorted(set(expected_routes) - set(actual_routes))
        route_orphaned = sorted(set(actual_routes) - set(expected_routes))
        route_changed = sorted(
            route_ref
            for route_ref in set(expected_routes).intersection(actual_routes)
            if expected_routes[route_ref] != actual_routes[route_ref]
        )

        expected_cases = {
            case["caseId"]: (
                case["componentId"],
                case["routeRef"],
                case["faultType"],
            )
            for case in self._catalog.cases
        }
        actual_cases = {
            case_id: (
                trigger["componentId"],
                trigger["expectedInvocation"]["routeRef"],
                trigger["faultType"],
            )
            for case_id, trigger in self._triggers.cases.items()
        }
        case_missing = sorted(set(expected_cases) - set(actual_cases))
        case_orphaned = sorted(set(actual_cases) - set(expected_cases))
        case_changed = sorted(
            case_id
            for case_id in set(expected_cases).intersection(actual_cases)
            if expected_cases[case_id] != actual_cases[case_id]
        )
        if any(
            (
                route_missing,
                route_orphaned,
                route_changed,
                case_missing,
                case_orphaned,
                case_changed,
            )
        ):
            _plan_drift(
                "route missing={} orphaned={} changed={}; case missing={} orphaned={} changed={}".format(
                    route_missing,
                    route_orphaned,
                    route_changed,
                    case_missing,
                    case_orphaned,
                    case_changed,
                )
            )
        report = {
            "schemaVersion": "1.0.0",
            "status": ReleaseStatus.PASSED.value,
            "routeCount": len(expected_routes),
            "caseCount": len(expected_cases),
            "routeMissing": [],
            "routeOrphaned": [],
            "routeChanged": [],
            "caseMissing": [],
            "caseOrphaned": [],
            "caseChanged": [],
            "catalogDigest": self._catalog.document["catalogDigest"],
            "topologyDigest": self._topology.document["topologyDigest"],
            "triggerCatalogDigest": self._triggers.document["triggerCatalogDigest"],
        }
        report["coverageDigest"] = hashlib.sha256(_canonical_json(report)).hexdigest()
        return report


def _load_yaml(path: Path, code: ReleaseErrorCode) -> dict[str, Any]:
    try:
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exception:
        raise ContractError(code.value, str(exception)) from exception
    if not isinstance(document, dict):
        raise ContractError(code.value, "document")
    return document


def _validate_digest(
    document: Mapping[str, Any], field: str, code: ReleaseErrorCode
) -> None:
    actual = document.get(field)
    unsigned = {key: value for key, value in document.items() if key != field}
    expected = hashlib.sha256(_canonical_json(unsigned)).hexdigest()
    if not isinstance(actual, str) or not _DIGEST.fullmatch(actual) or actual != expected:
        raise ContractError(code.value, field)


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _strings(value: Any) -> bool:
    return (
        isinstance(value, list)
        and bool(value)
        and all(_text(item) for item in value)
        and len(value) == len(set(value))
    )


def _topology_invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_TOPOLOGY_INVALID.value, detail)


def _trigger_invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_TRIGGER_CATALOG_INVALID.value, detail)


def _plan_drift(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_PLAN_DRIFT.value, detail)
