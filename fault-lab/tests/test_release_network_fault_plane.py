from __future__ import annotations

from collections.abc import Mapping

import pytest

from fault_lab.contracts import ContractError
from fault_lab.release import network_fault_plane
from fault_lab.release.network_fault_plane import NetworkProxyDataPlane


class ProxyApi:
    def __init__(self) -> None:
        self.proxy = {
            "name": "phase8-model-provider",
            "listen": "0.0.0.0:8701",
            "upstream": "phase8-response-proxy:18001",
            "enabled": True,
        }
        self.toxics: list[dict] = []
        self.routes = {"model-provider": "NORMAL"}

    def __call__(self, base_url, method, path, body, *, allow_404):
        if base_url == "http://toxiproxy":
            return self._toxiproxy(method, path, body, allow_404)
        return self._response(method, path, body, allow_404)

    def _toxiproxy(self, method, path, body, allow_404):
        if path == "/proxies/phase8-model-provider" and method == "GET":
            return dict(self.proxy)
        if path == "/proxies/phase8-model-provider" and method == "POST":
            self.proxy.update(body)
            return dict(self.proxy)
        if path == "/proxies/phase8-model-provider/toxics" and method == "GET":
            return list(self.toxics)
        if path == "/proxies/phase8-model-provider/toxics" and method == "POST":
            self.toxics.append(dict(body))
            return dict(body)
        prefix = "/proxies/phase8-model-provider/toxics/"
        if path.startswith(prefix) and method == "DELETE":
            name = path.removeprefix(prefix)
            self.toxics = [toxic for toxic in self.toxics if toxic["name"] != name]
            return {}
        raise AssertionError((method, path, body, allow_404))

    def _response(self, method, path, body, allow_404):
        if path == "/health" and method == "GET":
            return {"routes": dict(self.routes)}
        if path == "/routes/model-provider" and method == "PUT":
            assert isinstance(body, Mapping)
            self.routes["model-provider"] = body["mode"]
            return {"mode": body["mode"]}
        if path == "/routes/model-provider" and method == "DELETE":
            self.routes["model-provider"] = "NORMAL"
            return {}
        raise AssertionError((method, path, body, allow_404))


@pytest.mark.parametrize("fault_type", ["UNAVAILABLE", "TIMEOUT", "AUTH", "SCHEMA"])
def test_network_plane_injects_only_target_and_recovers(
    monkeypatch: pytest.MonkeyPatch, fault_type: str
) -> None:
    api = ProxyApi()
    monkeypatch.setattr(network_fault_plane, "_json_request", api)
    plane = NetworkProxyDataPlane("http://toxiproxy", "http://response-proxy")
    case = {
        "routeRef": "network://model-provider",
        "faultType": fault_type,
    }

    activation = plane.activate(case)
    injected = plane.health(case)
    recovery = plane.recover(case, activation)
    repeated = plane.recover(case, activation)

    assert activation["changedRoutes"] == [case["routeRef"]]
    assert injected["healthy"] is False
    assert injected["residualFaults"]
    assert recovery["healthy"] is True
    assert recovery["residualFaults"] == []
    assert repeated["healthy"] is True


def test_network_plane_rejects_missing_route(monkeypatch: pytest.MonkeyPatch) -> None:
    api = ProxyApi()

    def missing(base_url, method, path, body, *, allow_404):
        raise ContractError("RELEASE_FAILURE_ROUTE_UNAVAILABLE", path)

    monkeypatch.setattr(network_fault_plane, "_json_request", missing)
    plane = NetworkProxyDataPlane("http://toxiproxy", "http://response-proxy")

    with pytest.raises(ContractError, match="RELEASE_FAILURE_ROUTE_UNAVAILABLE"):
        plane.health({"routeRef": "network://missing", "faultType": "UNAVAILABLE"})


@pytest.mark.parametrize("fault_type", ["AUTH", "SCHEMA"])
def test_database_route_contract_fault_is_explicitly_unavailable(
    monkeypatch: pytest.MonkeyPatch, fault_type: str
) -> None:
    api = ProxyApi()
    api.proxy["name"] = "phase8-knowledge-retrieval"

    def database_api(base_url, method, path, body, *, allow_404):
        if path == "/proxies/phase8-knowledge-retrieval":
            return dict(api.proxy)
        if path == "/proxies/phase8-knowledge-retrieval/toxics":
            return []
        raise AssertionError((method, path))

    monkeypatch.setattr(network_fault_plane, "_json_request", database_api)
    plane = NetworkProxyDataPlane("http://toxiproxy", "http://response-proxy")
    case = {"routeRef": "network://knowledge-retrieval", "faultType": fault_type}

    with pytest.raises(ContractError, match="response proxy unsupported"):
        plane.activate(case)


def test_recovery_reports_residual_toxic(monkeypatch: pytest.MonkeyPatch) -> None:
    api = ProxyApi()
    monkeypatch.setattr(network_fault_plane, "_json_request", api)
    plane = NetworkProxyDataPlane("http://toxiproxy", "http://response-proxy")
    case = {"routeRef": "network://model-provider", "faultType": "TIMEOUT"}
    activation = plane.activate(case)
    api.toxics.append({"name": "unexpected-residual"})

    recovery = plane.recover(case, activation)

    assert recovery["healthy"] is False
    assert recovery["residualFaults"] == ["unexpected-residual"]
