from __future__ import annotations

import hashlib
import json
from typing import Any, Mapping

from ..contracts import ContractError
from .failure_plan import FailureRouteTopology
from .model import ReleaseErrorCode, ReleaseStatus


class FailureDataPlaneVerifier:
    """Proves frozen routes are consumed through the Phase 8 Compose data plane."""

    def __init__(
        self,
        topology: FailureRouteTopology,
        compose: Mapping[str, Any],
        toxiproxy: list[Mapping[str, Any]],
        agent_directory: Mapping[str, Any],
    ):
        self.topology = topology
        self.compose = compose
        self.toxiproxy = toxiproxy
        self.agent_directory = agent_directory

    def verify(self) -> dict[str, Any]:
        services = self.compose.get("services")
        if not isinstance(services, Mapping):
            _invalid("compose services")
        response_routes = self._response_routes(services)
        proxies = self._proxies()
        directory_urls = self._directory_urls()
        route_results: list[dict[str, Any]] = []

        for route_ref, route in self.topology.routes.items():
            service_name = route["consumerService"]
            service = services.get(service_name)
            if not isinstance(service, Mapping):
                _invalid(f"consumer service: {route_ref}")
            route_kind = route["routeKind"]
            if route_kind == "NETWORK_PROXY":
                proxy = proxies.get(route_ref)
                if proxy is None:
                    _invalid(f"missing proxy: {route_ref}")
                port = _port(proxy.get("listen"), "listen")
                self._verify_network_upstream(route_ref, route, proxy, response_routes)
                self._verify_network_consumer(route_ref, route, service, port, directory_urls)
            elif route_kind in {"FILE_FIXTURE", "PROCESS_FIXTURE"}:
                self._verify_fixture_consumer(route_ref, route, service)
            else:
                _invalid(f"route kind: {route_ref}")
            route_results.append(
                {
                    "routeRef": route_ref,
                    "routeKind": route_kind,
                    "consumerService": service_name,
                    "consumerConfig": route["consumerConfig"],
                    "status": ReleaseStatus.PASSED.value,
                }
            )

        expected_network = {
            route_ref
            for route_ref, route in self.topology.routes.items()
            if route["routeKind"] == "NETWORK_PROXY"
        }
        if set(proxies) != expected_network:
            _invalid(
                f"proxy coverage missing={sorted(expected_network - set(proxies))} "
                f"orphaned={sorted(set(proxies) - expected_network)}"
            )
        self._verify_fixture_initializer(services)

        report = {
            "schemaVersion": "1.0.0",
            "status": ReleaseStatus.PASSED.value,
            "routeCount": len(route_results),
            "networkRouteCount": len(expected_network),
            "fileRouteCount": sum(
                result["routeKind"] == "FILE_FIXTURE" for result in route_results
            ),
            "processRouteCount": sum(
                result["routeKind"] == "PROCESS_FIXTURE" for result in route_results
            ),
            "routes": route_results,
        }
        report["reportDigest"] = hashlib.sha256(_canonical_json(report)).hexdigest()
        return report

    def _proxies(self) -> dict[str, Mapping[str, Any]]:
        proxies: dict[str, Mapping[str, Any]] = {}
        listens: set[str] = set()
        for proxy in self.toxiproxy:
            if not isinstance(proxy, Mapping) or set(proxy) != {
                "name",
                "listen",
                "upstream",
                "enabled",
            }:
                _invalid("toxiproxy entry")
            name = proxy.get("name")
            if not isinstance(name, str) or not name.startswith("phase8-"):
                _invalid(f"toxiproxy name: {name}")
            route_ref = "network://" + name.removeprefix("phase8-")
            if route_ref in proxies or proxy.get("listen") in listens or proxy.get("enabled") is not True:
                _invalid(f"toxiproxy identity: {name}")
            _port(proxy.get("listen"), "listen")
            _port(proxy.get("upstream"), "upstream")
            proxies[route_ref] = proxy
            listens.add(str(proxy["listen"]))
        return proxies

    @staticmethod
    def _response_routes(services: Mapping[str, Any]) -> dict[str, dict[str, str]]:
        service = services.get("phase8-response-proxy")
        if not isinstance(service, Mapping):
            _invalid("response proxy service")
        environment = service.get("environment")
        try:
            routes = json.loads(environment["PHASE8_RESPONSE_ROUTES"])
        except (KeyError, TypeError, json.JSONDecodeError) as exception:
            raise ContractError(
                ReleaseErrorCode.FAILURE_DATA_PLANE_INVALID.value,
                "response proxy routes",
            ) from exception
        if not isinstance(routes, dict):
            _invalid("response proxy routes")
        return routes

    def _directory_urls(self) -> set[str]:
        agents = self.agent_directory.get("agents")
        if not isinstance(agents, list) or not agents:
            _invalid("agent directory")
        urls = {agent.get("card_url") for agent in agents if isinstance(agent, Mapping)}
        if len(urls) != len(agents) or not all(isinstance(url, str) for url in urls):
            _invalid("agent directory URLs")
        return urls

    @staticmethod
    def _verify_network_upstream(
        route_ref: str,
        route: Mapping[str, Any],
        proxy: Mapping[str, Any],
        response_routes: Mapping[str, Any],
    ) -> None:
        upstream = str(proxy["upstream"])
        if upstream == "postgres:5432":
            if route_ref != "network://knowledge-retrieval" or not str(route["upstream"]).startswith(
                "jdbc:postgresql://postgres:5432/"
            ):
                _invalid(f"direct network upstream: {route_ref}")
            return
        if not upstream.startswith("phase8-response-proxy:"):
            _invalid(f"network proxy bypasses response proxy: {route_ref}")
        port = str(_port(upstream, "response proxy upstream"))
        response = response_routes.get(port)
        route_id = route_ref.removeprefix("network://")
        if (
            not isinstance(response, Mapping)
            or response.get("routeId") != route_id
            or response.get("upstream") != route["upstream"]
        ):
            _invalid(f"response proxy route: {route_ref}")

    @staticmethod
    def _verify_network_consumer(
        route_ref: str,
        route: Mapping[str, Any],
        service: Mapping[str, Any],
        port: int,
        directory_urls: set[str],
    ) -> None:
        config = str(route["consumerConfig"])
        expected = f"toxiproxy:{port}"
        if config.startswith("env:"):
            environment = service.get("environment")
            value = environment.get(config.removeprefix("env:")) if isinstance(environment, Mapping) else None
            if not isinstance(value, str) or expected not in value:
                _invalid(f"network consumer config: {route_ref}")
            return
        if config.startswith("file:/app/config/agent-directory.yaml#"):
            if not any(expected in url for url in directory_urls):
                _invalid(f"A2A directory route: {route_ref}")
            if not _has_volume(service, "/app/config/agent-directory.yaml", "agent-directory.phase8.yaml"):
                _invalid(f"A2A directory mount: {route_ref}")
            return
        _invalid(f"unsupported network consumer config: {route_ref}")

    @staticmethod
    def _verify_fixture_consumer(
        route_ref: str, route: Mapping[str, Any], service: Mapping[str, Any]
    ) -> None:
        config = str(route["consumerConfig"])
        if not config.startswith("env:"):
            _invalid(f"fixture consumer config: {route_ref}")
        environment = service.get("environment")
        value = environment.get(config.removeprefix("env:")) if isinstance(environment, Mapping) else None
        relative = route_ref.split("://", 1)[1]
        expected = (
            f"/phase8-fixtures/process/{relative}/run"
            if route["routeKind"] == "PROCESS_FIXTURE"
            else f"/phase8-fixtures/{relative}"
        )
        if value != expected:
            _invalid(f"fixture consumer path: {route_ref}")
        if not _has_volume(service, "/phase8-fixtures", "phase8-failure-fixtures", read_only=True):
            _invalid(f"fixture consumer volume: {route_ref}")

    @staticmethod
    def _verify_fixture_initializer(services: Mapping[str, Any]) -> None:
        service = services.get("phase8-fixture-init")
        if not isinstance(service, Mapping):
            _invalid("fixture initializer")
        if service.get("entrypoint") != ["python", "-m", "fault_lab.release.fixture_init"]:
            _invalid("fixture initializer entrypoint")
        if not _has_volume(service, "/fixtures", "phase8-failure-fixtures"):
            _invalid("fixture initializer volume")


def _has_volume(
    service: Mapping[str, Any],
    target: str,
    source_suffix: str,
    *,
    read_only: bool | None = None,
) -> bool:
    volumes = service.get("volumes")
    if not isinstance(volumes, list):
        return False
    for volume in volumes:
        if not isinstance(volume, Mapping) or volume.get("target") != target:
            continue
        if not str(volume.get("source", "")).replace("\\", "/").endswith(source_suffix):
            continue
        if read_only is not None and volume.get("read_only", False) is not read_only:
            continue
        return True
    return False


def _port(value: Any, field: str) -> int:
    if not isinstance(value, str) or ":" not in value:
        _invalid(field)
    try:
        port = int(value.rsplit(":", 1)[1])
    except ValueError as exception:
        raise ContractError(
            ReleaseErrorCode.FAILURE_DATA_PLANE_INVALID.value, field
        ) from exception
    if port < 1 or port > 65535:
        _invalid(field)
    return port


def _canonical_json(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode()


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_DATA_PLANE_INVALID.value, detail)
