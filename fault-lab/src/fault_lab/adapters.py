from __future__ import annotations

import json
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import urljoin, urlparse

from .contracts import ContractError
from .model import ExecutionContext


HttpTransport = Callable[[str, str, bytes | None], tuple[int, bytes]]


def _http_transport(method: str, url: str, body: bytes | None) -> tuple[int, bytes]:
    request = urllib.request.Request(url, data=body, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()
    except urllib.error.URLError as error:
        raise ContractError("FAULT_LAB_HTTP_UNAVAILABLE", str(error.reason)) from error


def _controlled_base_url(value: str, expected_host: str) -> str:
    parsed = urlparse(value)
    if parsed.scheme != "http" or parsed.hostname != expected_host or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ContractError("FAULT_LAB_ENDPOINT_INVALID", value)
    return value.rstrip("/") + "/"


class ToxiproxyLatencyInjector:
    def __init__(self, base_url: str = "http://toxiproxy:8474", transport: HttpTransport = _http_transport):
        self.base_url = _controlled_base_url(base_url, "toxiproxy")
        self.transport = transport

    def inject(self, context: ExecutionContext) -> dict[str, Any]:
        parameters = context.scenario["injection"]["parameters"]
        if parameters != {"latency_ms": 3000, "jitter_ms": 100} or context.scenario["injection"]["durationSeconds"] != 120:
            raise ContractError("SCENARIO_FROZEN_PARAMETER_MISMATCH", "toxiproxy")
        name = self._name(context.dataset_run_id)
        body = json.dumps({
            "name": name,
            "type": "latency",
            "stream": "downstream",
            "toxicity": 1,
            "attributes": {"latency": 3000, "jitter": 100},
        }, separators=(",", ":")).encode()
        status, _ = self.transport("POST", urljoin(self.base_url, "proxies/inventory-downstream/toxics"), body)
        if status not in {200, 201}:
            raise ContractError("TOXIPROXY_INJECTION_FAILED", str(status))
        return {"type": "TOXIPROXY_LATENCY", "toxicName": name, "latencyMs": 3000, "jitterMs": 100}

    def recover(self, context: ExecutionContext) -> None:
        status, _ = self.transport(
            "DELETE",
            urljoin(self.base_url, f"proxies/inventory-downstream/toxics/{self._name(context.dataset_run_id)}"),
            None,
        )
        if status not in {204, 404}:
            raise ContractError("TOXIPROXY_RECOVERY_FAILED", str(status))

    @staticmethod
    def _name(dataset_run_id: str) -> str:
        return f"inventory-latency-{dataset_run_id}"


class LongTransactionInjector:
    def __init__(self, base_url: str = "http://order-service:8080", transport: HttpTransport = _http_transport):
        self.base_url = _controlled_base_url(base_url, "order-service")
        self.transport = transport

    def inject(self, context: ExecutionContext) -> dict[str, Any]:
        expected = {"maximum_pool_size": 4, "held_connections": 4, "hold_seconds": 90, "connection_timeout_ms": 2000}
        if context.scenario["injection"]["parameters"] != expected:
            raise ContractError("SCENARIO_FROZEN_PARAMETER_MISMATCH", "connection-hold")
        active_holds = 0
        for slot in range(1, 5):
            status, response = self.transport(
                "PUT", urljoin(self.base_url, f"internal/faults/db/holds/{slot}"), None
            )
            if status != 200:
                raise ContractError("LONG_TRANSACTION_INJECTION_FAILED", str(status))
            try:
                active_holds = int(json.loads(response)["activeHolds"])
            except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
                raise ContractError("LONG_TRANSACTION_STATUS_INVALID", response.decode(errors="replace")) from exc
        deadline = time.monotonic() + 10
        while active_holds != 4 and time.monotonic() < deadline:
            time.sleep(0.1)
            for slot in range(1, 5):
                status, response = self.transport(
                    "PUT", urljoin(self.base_url, f"internal/faults/db/holds/{slot}"), None
                )
                if status != 200:
                    raise ContractError("LONG_TRANSACTION_INJECTION_FAILED", str(status))
                active_holds = int(json.loads(response)["activeHolds"])
        if active_holds != 4:
            raise ContractError("LONG_TRANSACTION_HOLDS_INCOMPLETE", str(active_holds))
        return {
            "type": "HIKARI_CONNECTION_HOLD",
            "datasetRunId": context.dataset_run_id,
            "heldConnections": active_holds,
            "holdSeconds": 90,
        }

    def recover(self, context: ExecutionContext) -> None:
        status, response = self.transport("POST", urljoin(self.base_url, "internal/faults/db/reset"), None)
        if status != 200:
            raise ContractError("LONG_TRANSACTION_RECOVERY_FAILED", str(status))
        try:
            if int(json.loads(response)["activeHolds"]) != 0:
                raise ContractError("LONG_TRANSACTION_RECOVERY_INCOMPLETE", context.dataset_run_id)
        except (json.JSONDecodeError, KeyError, TypeError, ValueError) as exc:
            raise ContractError("LONG_TRANSACTION_STATUS_INVALID", response.decode(errors="replace")) from exc


@dataclass(frozen=True)
class CommandResult:
    exit_code: int
    stdout: str


CommandRunner = Callable[[list[str], int], CommandResult]


def _command_runner(command: list[str], timeout_seconds: int) -> CommandResult:
    completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout_seconds, check=False)
    return CommandResult(completed.returncode, completed.stdout)


class InventoryContainerStopInjector:
    def __init__(self, compose_project: str, runner: CommandRunner = _command_runner):
        if not compose_project.replace("-", "").replace("_", "").isalnum():
            raise ContractError("COMPOSE_PROJECT_INVALID", compose_project)
        self.compose_project = compose_project
        self.runner = runner
        self.identities: dict[str, dict[str, Any]] = {}

    def inject(self, context: ExecutionContext) -> dict[str, Any]:
        if context.scenario["injection"]["parameters"] != {"remove_volume": False}:
            raise ContractError("DESTRUCTIVE_CONTAINER_ACTION_DENIED", "remove_volume")
        identity = self._run([
            "docker", "ps",
            "--filter", f"label=com.docker.compose.project={self.compose_project}",
            "--filter", "label=com.docker.compose.service=inventory-service",
            "--format", "{{.ID}}",
        ])
        container_id = identity.stdout.strip()
        if not container_id or "\n" in container_id:
            raise ContractError("INVENTORY_CONTAINER_NOT_FOUND", self.compose_project)
        inspect = self._run(["docker", "inspect", container_id])
        try:
            detail = json.loads(inspect.stdout)[0]
            labels = detail["Config"]["Labels"]
            if labels.get("com.docker.compose.project") != self.compose_project \
                    or labels.get("com.docker.compose.service") != "inventory-service":
                raise ContractError("INVENTORY_CONTAINER_IDENTITY_INVALID", container_id)
            snapshot = {
                "containerId": container_id,
                "image": detail["Image"],
                "mounts": sorted(mount.get("Name") or mount.get("Source") for mount in detail.get("Mounts", [])),
            }
        except (json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
            raise ContractError("INVENTORY_CONTAINER_IDENTITY_INVALID", container_id) from exc
        self.identities[context.dataset_run_id] = snapshot
        self._run(["docker", "stop", "--time", "10", container_id])
        return {"type": "CONTAINER_STOP", **snapshot, "durationSeconds": 90}

    def recover(self, context: ExecutionContext) -> None:
        if context.dataset_run_id not in self.identities:
            return
        snapshot = self.identities[context.dataset_run_id]
        self._run(["docker", "start", snapshot["containerId"]])
        inspect = self._run(["docker", "inspect", snapshot["containerId"]])
        try:
            detail = json.loads(inspect.stdout)[0]
            recovered = {
                "containerId": snapshot["containerId"],
                "image": detail["Image"],
                "mounts": sorted(mount.get("Name") or mount.get("Source") for mount in detail.get("Mounts", [])),
            }
        except (json.JSONDecodeError, KeyError, IndexError, TypeError) as exc:
            raise ContractError("INVENTORY_CONTAINER_IDENTITY_INVALID", snapshot["containerId"]) from exc
        if recovered != snapshot:
            raise ContractError("INVENTORY_CONTAINER_IDENTITY_CHANGED", snapshot["containerId"])

    def _run(self, command: list[str]) -> CommandResult:
        forbidden = {"down", "rm", "volume", "-v", "--volumes"}
        if forbidden.intersection(command):
            raise ContractError("DESTRUCTIVE_CONTAINER_ACTION_DENIED", " ".join(command))
        result = self.runner(command, 30)
        if result.exit_code:
            raise ContractError("FAULT_LAB_CONTAINER_CONTROL_FAILED", " ".join(command))
        return result


@dataclass(frozen=True)
class RequestSample:
    endpoint: str
    started_at_epoch: float
    duration_ms: float
    status: int


class HttpLoadGenerator:
    def __init__(
        self,
        base_url: str = "http://sample-gateway:8080",
        transport: HttpTransport = _http_transport,
        monotonic: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ):
        self.base_url = _controlled_base_url(base_url, "sample-gateway")
        self.transport = transport
        self.monotonic = monotonic
        self.sleep = sleep

    def run(self, context: ExecutionContext) -> dict[str, Any]:
        load = context.scenario["load"]
        duration = int(load["durationSeconds"])
        rate = float(load["requestsPerSecond"])
        if duration != 240 or rate <= 0:
            raise ContractError("SCENARIO_FROZEN_PARAMETER_MISMATCH", "load")
        endpoints = list(load["endpoints"])
        interval = 1.0 / rate
        started = self.monotonic()
        samples: list[RequestSample] = []
        request_count = int(duration * rate)
        for index in range(request_count):
            scheduled = started + index * interval
            wait = scheduled - self.monotonic()
            if wait > 0:
                self.sleep(wait)
            endpoint = endpoints[index % len(endpoints)]
            method = "GET"
            body = None
            if endpoint == "/api/orders":
                endpoint = "/api/orders/00000000-0000-0000-0000-000000000000"
            request_started = self.monotonic()
            status, _ = self.transport(method, urljoin(self.base_url, endpoint.lstrip("/")), body)
            samples.append(RequestSample(endpoint, request_started, (self.monotonic() - request_started) * 1000, status))
        successes = sum(1 for sample in samples if sample.status < 500)
        return {
            "durationSeconds": duration,
            "requestCount": len(samples),
            "successRate": successes / max(1, len(samples)),
            "samples": [sample.__dict__ for sample in samples],
        }
