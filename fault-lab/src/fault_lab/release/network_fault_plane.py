from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode


class NetworkProxyDataPlane:
    """Controls Toxiproxy for transport faults and the response proxy for HTTP contracts."""

    def __init__(self, toxiproxy_url: str, response_proxy_url: str):
        self.toxiproxy_url = toxiproxy_url.rstrip("/")
        self.response_proxy_url = response_proxy_url.rstrip("/")

    def health(self, case: Mapping[str, Any]) -> dict[str, Any]:
        proxy_name = self._proxy_name(case)
        proxy = self._toxiproxy("GET", f"/proxies/{urllib.parse.quote(proxy_name)}")
        toxics = self._toxiproxy(
            "GET", f"/proxies/{urllib.parse.quote(proxy_name)}/toxics"
        )
        mode = self._response_mode(case)
        residuals = []
        if proxy.get("enabled") is not True:
            residuals.append("PROXY_DISABLED")
        if isinstance(toxics, list):
            residuals.extend(str(toxic.get("name")) for toxic in toxics if isinstance(toxic, Mapping))
        else:
            residuals.append("TOXIC_STATE_INVALID")
        if mode not in {None, "NORMAL"}:
            residuals.append(f"RESPONSE_MODE_{mode}")
        snapshot = {
            "proxyName": proxy_name,
            "enabled": proxy.get("enabled"),
            "listen": proxy.get("listen"),
            "upstream": proxy.get("upstream"),
            "toxics": toxics,
            "responseMode": mode,
        }
        return {
            "healthy": not residuals,
            "routeRef": case.get("routeRef"),
            "routeSnapshot": snapshot,
            "residualFaults": residuals,
        }

    def activate(self, case: Mapping[str, Any]) -> dict[str, Any]:
        before = self.health(case)
        if before["healthy"] is not True:
            _unavailable(str(case.get("routeRef")))
        proxy_name = self._proxy_name(case)
        fault_type = case.get("faultType")
        token = uuid.uuid4().hex
        toxic_name: str | None = None
        response_mode: str | None = None
        if fault_type == "UNAVAILABLE":
            proxy = before["routeSnapshot"]
            self._toxiproxy(
                "POST",
                f"/proxies/{urllib.parse.quote(proxy_name)}",
                {
                    "listen": proxy["listen"],
                    "upstream": proxy["upstream"],
                    "enabled": False,
                },
            )
        elif fault_type == "TIMEOUT":
            toxic_name = f"phase8-timeout-{token}"
            self._toxiproxy(
                "POST",
                f"/proxies/{urllib.parse.quote(proxy_name)}/toxics",
                {
                    "name": toxic_name,
                    "type": "timeout",
                    "stream": "downstream",
                    "toxicity": 1.0,
                    "attributes": {"timeout": 65000},
                },
            )
        elif fault_type in {"AUTH", "SCHEMA"}:
            if self._response_mode(case) is None:
                _unavailable(f"response proxy unsupported: {case.get('routeRef')}")
            response_mode = str(fault_type)
            self._response(
                "PUT",
                f"/routes/{urllib.parse.quote(self._route_id(case))}",
                {"mode": response_mode},
            )
        else:
            _unavailable("fault type")
        after = self.health(case)
        return {
            "activationId": f"network-{token}",
            "routeRef": case["routeRef"],
            "faultType": fault_type,
            "changedRoutes": [case["routeRef"]],
            "before": before["routeSnapshot"],
            "after": after["routeSnapshot"],
            "recoveryCredential": {
                "proxyName": proxy_name,
                "toxicName": toxic_name,
                "responseMode": response_mode,
            },
        }

    def recover(
        self, case: Mapping[str, Any], activation: Mapping[str, Any]
    ) -> dict[str, Any]:
        credential = activation.get("recoveryCredential")
        if not isinstance(credential, Mapping):
            _unavailable("recovery credential")
        proxy_name = self._proxy_name(case)
        if credential.get("proxyName") != proxy_name:
            _unavailable("recovery route")
        fault_type = activation.get("faultType")
        if fault_type == "UNAVAILABLE":
            before = activation.get("before")
            if not isinstance(before, Mapping):
                _unavailable("recovery snapshot")
            self._toxiproxy(
                "POST",
                f"/proxies/{urllib.parse.quote(proxy_name)}",
                {
                    "listen": before["listen"],
                    "upstream": before["upstream"],
                    "enabled": True,
                },
            )
        elif fault_type == "TIMEOUT":
            toxic_name = credential.get("toxicName")
            if isinstance(toxic_name, str):
                self._toxiproxy(
                    "DELETE",
                    f"/proxies/{urllib.parse.quote(proxy_name)}/toxics/{urllib.parse.quote(toxic_name)}",
                    allow_404=True,
                )
        elif fault_type in {"AUTH", "SCHEMA"}:
            self._response(
                "DELETE",
                f"/routes/{urllib.parse.quote(self._route_id(case))}",
                allow_404=True,
            )
        health = self.health(case)
        return {
            "healthy": health["healthy"],
            "residualFaults": health["residualFaults"],
            "restoredRouteRef": case["routeRef"],
            "routeSnapshot": health["routeSnapshot"],
        }

    def _response_mode(self, case: Mapping[str, Any]) -> str | None:
        if case.get("routeRef") == "network://knowledge-retrieval":
            return None
        value = self._response("GET", "/health")
        routes = value.get("routes") if isinstance(value, Mapping) else None
        if not isinstance(routes, Mapping):
            _unavailable("response proxy health")
        mode = routes.get(self._route_id(case))
        if not isinstance(mode, str):
            _unavailable(f"response proxy route: {case.get('routeRef')}")
        return mode

    def _toxiproxy(
        self,
        method: str,
        path: str,
        body: Mapping[str, Any] | None = None,
        *,
        allow_404: bool = False,
    ) -> Any:
        return _json_request(self.toxiproxy_url, method, path, body, allow_404=allow_404)

    def _response(
        self,
        method: str,
        path: str,
        body: Mapping[str, Any] | None = None,
        *,
        allow_404: bool = False,
    ) -> Any:
        return _json_request(self.response_proxy_url, method, path, body, allow_404=allow_404)

    @staticmethod
    def _route_id(case: Mapping[str, Any]) -> str:
        route_ref = case.get("routeRef")
        if not isinstance(route_ref, str) or not route_ref.startswith("network://"):
            _unavailable("routeRef")
        return route_ref.removeprefix("network://")

    def _proxy_name(self, case: Mapping[str, Any]) -> str:
        return "phase8-" + self._route_id(case)


def _json_request(
    base_url: str,
    method: str,
    path: str,
    body: Mapping[str, Any] | None,
    *,
    allow_404: bool,
) -> Any:
    payload = (
        json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        if body is not None
        else None
    )
    request = urllib.request.Request(
        base_url + path,
        data=payload,
        headers={"Content-Type": "application/json"} if payload is not None else {},
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            data = response.read()
    except urllib.error.HTTPError as error:
        if allow_404 and error.code == 404:
            return {}
        raise ContractError(
            ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
            f"{method} {path}: HTTP {error.code}",
        ) from error
    except (OSError, urllib.error.URLError) as error:
        raise ContractError(
            ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
            f"{method} {path}: {type(error).__name__}",
        ) from error
    if not data:
        return {}
    try:
        return json.loads(data)
    except json.JSONDecodeError as error:
        raise ContractError(
            ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
            f"{method} {path}: invalid JSON",
        ) from error


def _unavailable(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value, detail)
