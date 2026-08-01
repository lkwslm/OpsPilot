from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable

from .contracts import ContractError


Transport = Callable[[str, str, dict[str, str], bytes | None], tuple[int, bytes]]


def _transport(method: str, url: str, headers: dict[str, str], body: bytes | None) -> tuple[int, bytes]:
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except urllib.error.URLError as error:
        raise ContractError("OPSPILOT_PRODUCT_UNAVAILABLE", str(error.reason)) from error


@dataclass(frozen=True)
class ProductRun:
    incident_id: str
    run_id: str
    status: str
    outcome: str | None


class ProductInvestigationClient:
    """Starts the production API path and waits for the sealed RCA Run."""

    def __init__(self, base_url: str, transport: Transport = _transport,
                 monotonic: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], None] = time.sleep,
                 principal: str = "fault-lab:phase7"):
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme != "http" or not parsed.hostname or parsed.username or parsed.password \
                or parsed.query or parsed.fragment:
            raise ContractError("OPSPILOT_PRODUCT_ENDPOINT_INVALID", base_url)
        self.base_url = base_url.rstrip("/")
        self.transport = transport
        self.monotonic = monotonic
        self.sleep = sleep
        self.principal = principal

    def investigate(
        self,
        ticket: dict[str, Any],
        *,
        deadline_seconds: int = 900,
        evaluation_profile: str = "mvp-v1",
        token_budget: int = 32768,
        run_identity: str | None = None,
    ) -> ProductRun:
        dataset_run_id = str(ticket.get("datasetRunId", ""))
        scenario_id = str(ticket.get("scenarioId", ""))
        if not dataset_run_id or not scenario_id:
            raise ContractError("OPSPILOT_PRODUCT_TICKET_INVALID", dataset_run_id)
        incident = self._json("POST", "/api/incidents", {
            "targetSystemId": "sample-system",
            "resourceIds": ["service:sample-gateway", "service:order-service", "service:inventory-service"],
            "scenarioId": scenario_id,
            "title": str(ticket.get("title", "Sample 系统故障调查")),
            "severity": "HIGH",
            "ticket": ticket,
            "inputArtifactIds": [],
        }, self._idempotency_key("create", run_identity or dataset_run_id))
        incident_id = incident["incidentId"]
        run = self._json("POST", f"/api/incidents/{incident_id}/run", {
            "modelConfigVersion": "phase7-frozen-v1",
            "evaluationProfile": evaluation_profile,
            "tokenBudget": token_budget,
            "deadlineSeconds": deadline_seconds,
        }, self._idempotency_key("run", run_identity or dataset_run_id))
        run_id = run["runId"]
        deadline = self.monotonic() + deadline_seconds + 60
        while self.monotonic() < deadline:
            state = self._json("GET", f"/api/incidents/{incident_id}/state?runId={run_id}")
            status = state["status"]
            if status == "COMPLETED":
                return ProductRun(incident_id, run_id, status, state.get("outcome"))
            if status in {"FAILED", "CANCELLED"}:
                raise ContractError("OPSPILOT_PRODUCT_RUN_FAILED", f"{run_id}:{status}")
            self.sleep(1)
        raise ContractError("OPSPILOT_PRODUCT_RUN_TIMEOUT", run_id)

    def _json(self, method: str, path: str, body: dict[str, Any] | None = None,
              idempotency_key: str | None = None) -> dict[str, Any]:
        headers = {"Accept": "application/json", "X-Principal-Id": self.principal}
        payload = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            payload = json.dumps(body, separators=(",", ":"), ensure_ascii=False).encode()
        if idempotency_key is not None:
            headers["Idempotency-Key"] = idempotency_key
        status, response = self.transport(method, self.base_url + path, headers, payload)
        if status not in {200, 201, 202}:
            raise ContractError("OPSPILOT_PRODUCT_HTTP_FAILED", f"{status}:{response[:256]!r}")
        try:
            value = json.loads(response)
        except json.JSONDecodeError as error:
            raise ContractError("OPSPILOT_PRODUCT_RESPONSE_INVALID", path) from error
        if not isinstance(value, dict):
            raise ContractError("OPSPILOT_PRODUCT_RESPONSE_INVALID", path)
        return value

    @staticmethod
    def _idempotency_key(operation: str, identity: str) -> str:
        digest = hashlib.sha256(identity.encode("utf-8")).hexdigest()[:32]
        return f"fault-lab-{operation}-{digest}"
