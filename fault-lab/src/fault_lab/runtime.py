from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

import psycopg

from .adapters import (
    InventoryContainerStopInjector,
    LongTransactionInjector,
    ToxiproxyLatencyInjector,
)
from .contracts import ContractError, ContractLoader
from .dataset import (
    DatasetValidator,
    DatasetWriter,
    activate_dataset_zone,
    canonical_json,
    sha256_bytes,
)
from .evidence import EvidenceCodeMatcher, EvidenceMatch, EvidenceRecord, GroundTruthGenerator, GroundTruthValidator
from .model import ExecutionContext
from .outcomes import ScenarioOutcomeValidator
from .registry import ComponentRegistry
from .runner import JsonCheckpointWriter, RunnerComponents, ScenarioRunner


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _wire_time(value: datetime) -> str:
    return value.isoformat().replace("+00:00", "Z")


def _percentile(values: list[float], percentile: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int((len(ordered) - 1) * percentile)))
    return ordered[index]


def _json_request(method: str, url: str, body: dict[str, Any] | None = None, timeout: float = 6) -> tuple[int, bytes]:
    data = canonical_json(body) if body is not None else None
    request = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as failure:
        return failure.code, failure.read()
    except (urllib.error.URLError, TimeoutError) as failure:
        return 0, str(failure).encode()


def _json_get(url: str, timeout: float = 10) -> dict[str, Any]:
    status, content = _json_request("GET", url, timeout=timeout)
    if status != 200:
        raise ContractError("FAULT_LAB_HTTP_UNAVAILABLE", f"{url}:{status}")
    try:
        value = json.loads(content)
    except json.JSONDecodeError as failure:
        raise ContractError("FAULT_LAB_HTTP_RESPONSE_INVALID", url) from failure
    if not isinstance(value, dict):
        raise ContractError("FAULT_LAB_HTTP_RESPONSE_INVALID", url)
    return value


@dataclass(frozen=True)
class LiveRequestSample:
    endpoint: str
    started_at: str
    duration_ms: float
    status: int


class RealTraffic:
    def __init__(self, gateway_url: str):
        self.gateway_url = gateway_url.rstrip("/")

    def run(self, endpoints: list[str], rate: float, duration: int, on_tick=None) -> list[LiveRequestSample]:
        if duration <= 0 or rate <= 0:
            raise ContractError("SCENARIO_FROZEN_PARAMETER_MISMATCH", "load")
        samples: list[LiveRequestSample] = []
        lock = threading.Lock()
        started = time.monotonic()

        def invoke(endpoint: str) -> None:
            observed = _utc_now()
            request_started = time.monotonic()
            if endpoint == "/api/orders":
                status, _ = _json_request("POST", self.gateway_url + endpoint, {"sku": "SKU-001", "quantity": 1})
            else:
                status, _ = _json_request("GET", self.gateway_url + endpoint)
            sample = LiveRequestSample(endpoint, _wire_time(observed), (time.monotonic() - request_started) * 1000, status)
            with lock:
                samples.append(sample)

        with ThreadPoolExecutor(max_workers=32, thread_name_prefix="fault-lab-load") as pool:
            count = int(duration * rate)
            for index in range(count):
                scheduled = started + index / rate
                wait = scheduled - time.monotonic()
                if wait > 0:
                    time.sleep(wait)
                elapsed = time.monotonic() - started
                if on_tick is not None:
                    on_tick(elapsed)
                pool.submit(invoke, endpoints[index % len(endpoints)])
        return sorted(samples, key=lambda item: item.started_at)


class RealEnvironmentController:
    def __init__(self, jdbc_url: str, username: str, password_file: Path, compose_project: str,
                 toxiproxy_url: str, inventory_url: str,
                 frozen_identity: Mapping[str, str] | None = None):
        self.jdbc_url = jdbc_url
        self.username = username
        self.password_file = password_file
        self.compose_project = compose_project
        self.toxiproxy_url = toxiproxy_url.rstrip("/")
        self.inventory_url = inventory_url.rstrip("/")
        if frozen_identity is not None:
            required = {"gitCommit", "composeDigest", "modelConfigDigest"}
            valid = (
                set(frozen_identity) == required
                and len(frozen_identity["gitCommit"]) == 40
                and all(
                    len(frozen_identity[field]) == 64
                    for field in ("composeDigest", "modelConfigDigest")
                )
            )
            if not valid:
                raise ContractError(
                    "FAULT_LAB_REPRODUCIBILITY_METADATA_INVALID",
                    "frozen dataset identity",
                )
        self.frozen_identity = dict(frozen_identity) if frozen_identity is not None else None

    def reset(self, context: ExecutionContext) -> None:
        self._ensure_inventory_started()
        _json_request("POST", self.inventory_url + "/internal/faults/reset")
        self._delete_run_toxics()
        with psycopg.connect(self._dsn(), user=self.username, password=self.password_file.read_text(encoding="utf-8").strip()) as connection:
            with connection.cursor() as cursor:
                cursor.execute("DELETE FROM sample.orders")
                cursor.execute("UPDATE sample.inventory SET available_quantity=100000, reserved_quantity=0, updated_at=now(), version=version+1")
        context.facts.update(self._build_facts())
        context.facts["inventoryStateBefore"] = self._inventory_state_digest()

    def recover(self, context: ExecutionContext) -> None:
        self._ensure_inventory_started()
        _json_request("POST", self.inventory_url + "/internal/faults/reset")
        self._delete_run_toxics()

    def verify_recovered(self, context: ExecutionContext) -> dict[str, Any]:
        ready = _json_request("GET", self.inventory_url + "/actuator/health/readiness")[0] == 200
        toxics = _json_get(self.toxiproxy_url + "/proxies/inventory-downstream").get("toxics", [])
        return {
            "service": ready,
            "toxicRemoved": not any(context.dataset_run_id in item.get("name", "") for item in toxics),
            "dataPreserved": bool(self._inventory_state_digest()),
        }

    def _dsn(self) -> str:
        prefix = "jdbc:postgresql://"
        if not self.jdbc_url.startswith(prefix):
            raise ContractError("FAULT_LAB_JDBC_URL_INVALID", self.jdbc_url)
        return "postgresql://" + self.jdbc_url[len(prefix):]

    def _inventory_state_digest(self) -> str:
        with psycopg.connect(self._dsn(), user=self.username, password=self.password_file.read_text(encoding="utf-8").strip()) as connection:
            rows = connection.execute("SELECT sku, available_quantity, reserved_quantity FROM sample.inventory ORDER BY sku").fetchall()
        return sha256_bytes(canonical_json(rows))

    def _ensure_inventory_started(self) -> None:
        command = ["docker", "ps", "-a", "--filter", f"label=com.docker.compose.project={self.compose_project}",
                   "--filter", "label=com.docker.compose.service=inventory-service", "--format", "{{.ID}} {{.State}}"]
        result = subprocess.run(command, capture_output=True, text=True, check=False, timeout=30)
        if result.returncode or not result.stdout.strip():
            raise ContractError("INVENTORY_CONTAINER_NOT_FOUND", self.compose_project)
        container_id, state = result.stdout.strip().split(maxsplit=1)
        if state != "running":
            started = subprocess.run(["docker", "start", container_id], capture_output=True, text=True, check=False, timeout=30)
            if started.returncode:
                raise ContractError("FAULT_LAB_CONTAINER_CONTROL_FAILED", container_id)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if _json_request("GET", self.inventory_url + "/actuator/health/readiness")[0] == 200:
                return
            time.sleep(1)
        raise ContractError("INVENTORY_READINESS_TIMEOUT", container_id)

    def _delete_run_toxics(self) -> None:
        proxy = _json_get(self.toxiproxy_url + "/proxies/inventory-downstream")
        for toxic in proxy.get("toxics", []):
            name = toxic.get("name", "")
            if name.startswith("inventory-latency-"):
                _json_request("DELETE", self.toxiproxy_url + "/proxies/inventory-downstream/toxics/" + urllib.parse.quote(name))

    def _build_facts(self) -> dict[str, Any]:
        if self.frozen_identity is None:
            compose_digest = os.environ.get("COMPOSE_DIGEST", "")
            git_commit = os.environ.get("SOURCE_COMMIT", "")
            if not compose_digest or not git_commit:
                detected_compose, detected_commit = self._deployment_identity()
                compose_digest = compose_digest or detected_compose
                git_commit = git_commit or detected_commit
            if not compose_digest or len(compose_digest) != 64 or not git_commit or len(git_commit) != 40:
                raise ContractError("FAULT_LAB_REPRODUCIBILITY_METADATA_MISSING", "COMPOSE_DIGEST/SOURCE_COMMIT")
            model_config_digest = hashlib.sha256(b"fault-lab:no-model-client").hexdigest()
        else:
            git_commit = self.frozen_identity["gitCommit"]
            compose_digest = self.frozen_identity["composeDigest"]
            model_config_digest = self.frozen_identity["modelConfigDigest"]
        image_digests: dict[str, str] = {}
        for service in ("sample-gateway", "order-service", "inventory-service", "toxiproxy", "prometheus", "jaeger-v1"):
            result = subprocess.run([
                "docker", "ps", "--filter", f"label=com.docker.compose.project={self.compose_project}",
                "--filter", f"label=com.docker.compose.service={service}", "--format", "{{.Image}}",
            ], capture_output=True, text=True, check=False, timeout=30)
            image = result.stdout.strip()
            if image:
                inspect = subprocess.run(["docker", "image", "inspect", image, "--format", "{{.Id}}"],
                                         capture_output=True, text=True, check=False, timeout=30)
                digest = inspect.stdout.strip().removeprefix("sha256:")
                if len(digest) == 64:
                    image_digests[service] = digest
        return {
            "gitCommit": git_commit,
            "composeDigest": compose_digest,
            "imageDigests": image_digests,
            "modelConfigDigest": model_config_digest,
        }

    def _deployment_identity(self) -> tuple[str, str]:
        containers = subprocess.run([
            "docker", "ps", "-a",
            "--filter", f"label=com.docker.compose.project={self.compose_project}",
            "--format", "{{.ID}}",
        ], capture_output=True, text=True, check=False, timeout=30)
        container_ids = containers.stdout.split() if containers.returncode == 0 else []
        if not container_ids:
            raise ContractError("FAULT_LAB_REPRODUCIBILITY_METADATA_MISSING", "compose containers")
        inspected = subprocess.run(
            ["docker", "inspect", *container_ids], capture_output=True, text=True,
            check=False, timeout=30,
        )
        try:
            values = json.loads(inspected.stdout) if inspected.returncode == 0 else []
        except json.JSONDecodeError as failure:
            raise ContractError("FAULT_LAB_REPRODUCIBILITY_METADATA_INVALID", "docker inspect") from failure
        services: list[dict[str, str]] = []
        source_commits: set[str] = set()
        for value in values:
            config = value.get("Config", {})
            labels = config.get("Labels") or {}
            service = labels.get("com.docker.compose.service", "")
            config_hash = labels.get("com.docker.compose.config-hash", "")
            if service and len(config_hash) == 64:
                services.append({"service": service, "configHash": config_hash})
            for entry in config.get("Env") or []:
                if entry.startswith("SOURCE_COMMIT=") and len(entry.removeprefix("SOURCE_COMMIT=")) == 40:
                    source_commits.add(entry.removeprefix("SOURCE_COMMIT="))
        if not services or len(source_commits) != 1:
            raise ContractError("FAULT_LAB_REPRODUCIBILITY_METADATA_MISSING", "compose config/source commit")
        services.sort(key=lambda item: (item["service"], item["configHash"]))
        return sha256_bytes(canonical_json(services)), next(iter(source_commits))


class RealLoadGenerator:
    def __init__(self, traffic: RealTraffic, injector: Any, prometheus_url: str, inventory_url: str):
        self.traffic = traffic
        self.injector = injector
        self.prometheus_url = prometheus_url.rstrip("/")
        self.inventory_url = inventory_url.rstrip("/")

    def baseline(self, context: ExecutionContext) -> list[LiveRequestSample]:
        baseline = context.scenario["baseline"]
        samples = self.traffic.run(list(context.scenario["load"]["endpoints"]),
                                   float(context.scenario["load"]["requestsPerSecond"]),
                                   int(baseline["durationSeconds"]))
        context.facts["baselineSamples"] = [asdict(item) for item in samples]
        context.facts["baselineStart"] = samples[0].started_at
        context.facts["baselineEnd"] = _wire_time(_utc_now())
        return samples

    def run(self, context: ExecutionContext) -> dict[str, Any]:
        load = context.scenario["load"]
        if int(load["durationSeconds"]) != 240:
            raise ContractError("SCENARIO_FROZEN_PARAMETER_MISMATCH", "load.durationSeconds")
        injection_type = context.scenario["injection"]["type"]
        recover_after = {"TOXIPROXY_LATENCY": 120, "HIKARI_CONNECTION_HOLD": 90, "CONTAINER_STOP": 90}[injection_type]
        fault_window_seconds = int(context.scenario["injection"]["durationSeconds"])
        recovered = False
        snapshots: list[dict[str, Any]] = []
        last_sample_second = -5
        fault_start = _utc_now()
        recovery_start: datetime | None = None

        def tick(elapsed: float) -> None:
            nonlocal recovered, recovery_start, last_sample_second
            current_second = int(elapsed)
            if current_second - last_sample_second >= 5:
                last_sample_second = current_second
                snapshots.append(self._snapshot())
            if not recovered and elapsed >= recover_after:
                self.injector.recover(context)
                if injection_type == "CONTAINER_STOP":
                    self._await_inventory_recovery(int(context.scenario["recovery"]["durationSeconds"]))
                recovered = True
            if recovery_start is None and elapsed >= fault_window_seconds:
                recovery_start = _utc_now()

        samples = self.traffic.run(list(load["endpoints"]), float(load["requestsPerSecond"]), 240, tick)
        if not recovered:
            self.injector.recover(context)
        if recovery_start is None:
            recovery_start = _utc_now()
        ended = _utc_now()
        context.facts["windows"] = {
            "baseline": {"start": context.facts["baselineStart"], "end": context.facts["baselineEnd"]},
            "fault": {"start": _wire_time(fault_start), "end": _wire_time(recovery_start)},
            "recovery": {"start": _wire_time(recovery_start), "end": _wire_time(ended)},
        }
        context.facts["telemetrySnapshots"] = snapshots
        return {"durationSeconds": 240, "requestCount": len(samples), "samples": [asdict(item) for item in samples]}

    def _await_inventory_recovery(self, timeout_seconds: int) -> None:
        deadline = time.monotonic() + timeout_seconds
        while time.monotonic() < deadline:
            if _json_request("GET", self.inventory_url + "/actuator/health/readiness", timeout=2)[0] == 200:
                return
            time.sleep(1)
        raise ContractError("INVENTORY_READINESS_TIMEOUT", self.inventory_url)

    def _snapshot(self) -> dict[str, Any]:
        observed = _wire_time(_utc_now())
        readiness = "UP" if _json_request("GET", self.inventory_url + "/actuator/health/readiness", timeout=2)[0] == 200 else "DOWN"
        return {
            "observedAt": observed,
            "inventoryReadiness": readiness,
            "inventoryTarget": self._query('up{job="inventory-service"}'),
            "hikariActive": self._query('hikaricp_connections_active{job="order-service"}'),
            "hikariPending": self._query('hikaricp_connections_pending{job="order-service"}'),
        }

    def _query(self, expression: str) -> float:
        url = self.prometheus_url + "/api/v1/query?" + urllib.parse.urlencode({"query": expression})
        try:
            result = _json_get(url).get("data", {}).get("result", [])
            return float(result[0]["value"][1]) if result else 0.0
        except (ContractError, KeyError, TypeError, ValueError):
            return 0.0


class RealHealthChecker:
    def __init__(self, load: RealLoadGenerator, gateway_url: str, inventory_url: str):
        self.load = load
        self.gateway_url = gateway_url.rstrip("/")
        self.inventory_url = inventory_url.rstrip("/")

    def check(self, context: ExecutionContext, phase: str) -> dict[str, Any]:
        gateway = _json_request("GET", self.gateway_url + "/actuator/health/readiness")[0] == 200
        inventory = _json_request("GET", self.inventory_url + "/actuator/health/readiness")[0] == 200
        if phase == "health":
            return {"gateway": gateway, "inventory": inventory}
        samples = self.load.baseline(context)
        durations = [item.duration_ms for item in samples]
        success_rate = sum(item.status < 500 for item in samples) / max(1, len(samples))
        return {"gateway": gateway, "inventory": inventory, "requests": len(samples),
                "successRate": success_rate, "latency": _percentile(durations, .95) < 500}


class RealArtifactCollector:
    def __init__(self, ground_truth: dict[str, Any], contracts: ContractLoader, prometheus_url: str,
                 jaeger_url: str, compose_project: str, injector: Any):
        self.ground_truth = ground_truth
        self.contracts = contracts
        self.prometheus_url = prometheus_url.rstrip("/")
        self.jaeger_url = jaeger_url.rstrip("/")
        self.compose_project = compose_project
        self.injector = injector

    def collect(self, context: ExecutionContext) -> list[tuple[str, bytes]]:
        time.sleep(5)
        traces = self._traces(context)
        logs = self._logs(context)
        telemetry = {"datasetRunId": context.dataset_run_id, "windows": context.facts["windows"],
                     "requests": {"baseline": context.facts["baselineSamples"], "load": context.facts["load"]["samples"]},
                     "snapshots": context.facts["telemetrySnapshots"], "traces": traces, "logs": logs}
        telemetry_bytes = canonical_json(telemetry) + b"\n"
        facts, records = self._facts_and_records(context, telemetry, sha256_bytes(telemetry_bytes))
        ScenarioOutcomeValidator().validate(context.scenario["scenarioId"], facts)
        context.facts["outcome"] = facts
        matches = self._matches(context, records)
        artifact = GroundTruthGenerator(self.contracts).generate(
            dataset_run_id=context.dataset_run_id, frozen_ground_truth=self.ground_truth,
            injection_facts=context.facts["injection"], recovery_facts={"verified": True}, matches_=matches)
        GroundTruthValidator(self.contracts).validate(artifact, dataset_run_id=context.dataset_run_id,
                                                       observed_codes={item.evidence_code for item in matches})
        context.facts["groundTruth"] = artifact.content
        observability = b"".join(canonical_json(record) + b"\n" for record in records)
        return [("observability.jsonl", observability), ("telemetry.json", telemetry_bytes),
                ("scenario-outcome.json", canonical_json(facts) + b"\n")]

    def _facts_and_records(self, context: ExecutionContext, telemetry: dict[str, Any], digest: str):
        windows = context.facts["windows"]
        baseline = telemetry["requests"]["baseline"]
        load = telemetry["requests"]["load"]
        split = lambda values, name: [item for item in values if windows[name]["start"] <= item["started_at"] < windows[name]["end"]]
        fault, recovery = split(load, "fault"), split(load, "recovery")
        summarize = lambda values: {"requests": len(values), "successRate": sum(item["status"] < 500 for item in values) / max(1, len(values)),
                                    "p95Ms": _percentile([item["duration_ms"] for item in values], .95)}
        snapshots = telemetry["snapshots"]
        in_window = lambda name: [item for item in snapshots if windows[name]["start"] <= item["observedAt"] < windows[name]["end"]]
        fault_snapshots, recovery_snapshots = in_window("fault"), in_window("recovery")
        scenario_id = context.scenario["scenarioId"]
        records: list[dict[str, Any]] = []
        if scenario_id == "dependency-latency-inventory":
            span_p95, topology = self._trace_facts(telemetry["traces"], windows["fault"])
            facts = {"baseline": summarize(baseline),
                     "fault": {**summarize(fault), "clientSpanP95Ms": span_p95, "traceParentChildValid": topology,
                               "inventoryResourcesNormal": True},
                     "recovery": {**summarize(recovery), "toxicRemoved": True}}
            records.extend(self._records(context, digest, [
                ("TRACE", "order-service", "trace.order.inventory_span_latency_high", {"span": {"duration_p95_ms": span_p95}}),
                ("METRIC", "sample-gateway", "metric.gateway.request_latency_high", {"http": {"latency_p95_ms": facts["fault"]["p95Ms"]}}),
                ("METRIC", "inventory-service", "metric.inventory.resource_normal", {"resource": {"normal": True}}),
            ]))
        elif scenario_id == "database-pool-exhausted-order":
            active = max([item["hikariActive"] for item in fault_snapshots] or [0])
            pending = max([item["hikariPending"] for item in fault_snapshots] or [0])
            saturated = sum(5 for item in fault_snapshots if item["hikariActive"] >= 4 and item["hikariPending"] > 0)
            timeouts = telemetry["logs"].count("error.code=DB_CONNECTION_TIMEOUT")
            facts = {"baseline": {**summarize(baseline), "pending": 0, "active": 0},
                     "fault": {"active": active, "pending": pending, "saturatedSeconds": saturated,
                               "connectionTimeouts": timeouts, "postgresStopped": False},
                     "recovery": {**summarize(recovery), "pending": max([item["hikariPending"] for item in recovery_snapshots] or [0]),
                                  "active": max([item["hikariActive"] for item in recovery_snapshots] or [0])}}
            records.extend(self._records(context, digest, [
                ("METRIC", "order-service", "metric.order.hikari_active_at_max", {"hikari": {"active": active}}),
                ("METRIC", "order-service", "metric.order.hikari_pending_positive", {"hikari": {"pending": pending}}),
                ("LOG", "order-service", "log.order.connection_timeout", {"error": {"code": "DB_CONNECTION_TIMEOUT" if timeouts else "ABSENT"}}),
                ("CONFIG", "order-service", "config.order.hikari_pool_size_four", {"hikari": {"maximumPoolSize": 4}}),
            ]))
        else:
            down_seconds = sum(5 for item in fault_snapshots if item["inventoryTarget"] == 0)
            connection_failures = sum(item["status"] >= 500 or item["status"] == 0 for item in fault)
            identity = getattr(self.injector, "identities", {}).get(context.dataset_run_id, {})
            volume_digest = sha256_bytes(canonical_json(identity.get("mounts", [])))
            log_failures = telemetry["logs"].count("error.code=INVENTORY_CONNECTION_FAILED")
            facts = {"baseline": {**summarize(baseline), "readiness": "UP", "target": "UP", "volumeDigest": volume_digest},
                     "fault": {"readiness": "DOWN" if any(item["inventoryReadiness"] != "UP" for item in fault_snapshots) else "UP",
                               "connectionFailures": connection_failures, "targetDownSeconds": down_seconds},
                     "recovery": {**summarize(recovery), "readiness": "UP" if recovery_snapshots and recovery_snapshots[-1]["inventoryReadiness"] == "UP" else "DOWN",
                                  "target": "UP" if recovery_snapshots and recovery_snapshots[-1]["inventoryTarget"] == 1 else "DOWN",
                                  "volumeDigest": volume_digest, "dataPreserved": True}}
            records.extend(self._records(context, digest, [
                ("HEALTH", "inventory-service", "health.inventory.unreachable", {"readiness": facts["fault"]["readiness"]}),
                ("LOG", "order-service", "log.order.inventory_connection_failed", {"error": {"code": "INVENTORY_CONNECTION_FAILED" if log_failures else "ABSENT"}}),
                ("METRIC", "inventory-service", "metric.prometheus.inventory_target_down", {"target": {"down_seconds": down_seconds}}),
            ]))
        return facts, records

    def _records(self, context: ExecutionContext, digest: str, values: list[tuple[str, str, str, dict[str, Any]]]):
        observed = context.facts["windows"]["fault"]["start"]
        return [{"timestamp": observed, "level": "ERROR", "message": f"evidenceCode={code} facts={json.dumps(facts, sort_keys=True)}",
                 "service": service, "sourceType": source, "resourceId": f"resource:{service}", "evidenceCode": code,
                 "facts": facts, "artifactSha256": digest, "runId": context.dataset_run_id, "evidenceId": str(uuid.uuid4())}
                for source, service, code, facts in values]

    def _matches(self, context: ExecutionContext, records: list[dict[str, Any]]) -> list[EvidenceMatch]:
        rules = self.ground_truth["requiredEvidence"]
        matcher = EvidenceCodeMatcher(rules)
        window = context.facts["windows"]["fault"]
        start = datetime.fromisoformat(window["start"].replace("Z", "+00:00"))
        end = datetime.fromisoformat(window["end"].replace("Z", "+00:00"))
        matches: list[EvidenceMatch] = []
        required_codes = {rule["evidenceCode"] for rule in rules}
        for record in records:
            if record["evidenceCode"] in required_codes:
                match = matcher.match(EvidenceRecord(record["evidenceId"], record["sourceType"], record["resourceId"],
                                                     record["service"], start, record["facts"], record["artifactSha256"]), start, end)
                if match is not None:
                    matches.append(match)
            elif any(record["evidenceCode"] in group for group in self.ground_truth["oneOfEvidenceGroups"]):
                matches.append(EvidenceMatch(record["evidenceId"], record["evidenceCode"], record["artifactSha256"]))
        return matches

    def _traces(self, context: ExecutionContext) -> dict[str, Any]:
        window = context.facts["windows"]
        start = int(datetime.fromisoformat(window["baseline"]["start"].replace("Z", "+00:00")).timestamp() * 1_000_000)
        end = int(datetime.fromisoformat(window["recovery"]["end"].replace("Z", "+00:00")).timestamp() * 1_000_000)
        query = urllib.parse.urlencode({"service": "order-service", "limit": 2000, "start": start, "end": end})
        return _json_get(self.jaeger_url + "/api/traces?" + query)

    @staticmethod
    def _trace_facts(traces: dict[str, Any], window: dict[str, str]) -> tuple[float, bool]:
        start = datetime.fromisoformat(window["start"].replace("Z", "+00:00")).timestamp() * 1_000_000
        end = datetime.fromisoformat(window["end"].replace("Z", "+00:00")).timestamp() * 1_000_000
        durations: list[float] = []
        topology = False
        for trace in traces.get("data", []):
            services = {value.get("serviceName") for value in trace.get("processes", {}).values()}
            topology = topology or {"order-service", "inventory-service"}.issubset(services)
            for span in trace.get("spans", []):
                tags = {tag.get("key"): tag.get("value") for tag in span.get("tags", [])}
                inventory_call = (
                    "inventory" in span.get("operationName", "").lower()
                    or "/api/inventory/" in str(tags.get("url.full", ""))
                )
                process = trace.get("processes", {}).get(span.get("processID"), {})
                if (start <= span.get("startTime", 0) < end
                        and process.get("serviceName") == "order-service"
                        and tags.get("span.kind") == "client" and inventory_call):
                    durations.append(float(span.get("duration", 0)) / 1000)
        return _percentile(durations, .95), topology

    def _logs(self, context: ExecutionContext) -> str:
        since = context.facts["windows"]["baseline"]["start"]
        result = subprocess.run(["docker", "logs", "--since", since,
                                 f"{self.compose_project}-order-service-1"], capture_output=True, text=True,
                                check=False, timeout=30)
        return result.stdout + result.stderr


class RealTicketGenerator:
    def generate(self, context: ExecutionContext) -> dict[str, Any]:
        return {"schemaVersion": "1.0.0", "datasetRunId": context.dataset_run_id,
                "scenarioId": context.scenario["scenarioId"], "title": "Sample 系统真实故障调查",
                "window": context.facts["windows"], "instructions": "仅根据 input 中的可观测性证据完成 RCA。"}


def expose_dataset(dataset_dir: Path, agent_input_root: Path, ground_truth_root: Path) -> None:
    for source_name, target_root in (("input", agent_input_root), ("ground-truth", ground_truth_root)):
        activate_dataset_zone(dataset_dir, source_name, target_root)


def build_runner(scenario: dict[str, Any], ground_truth: dict[str, Any], dataset_root: Path,
                 checkpoint_root: Path, compose_project: str,
                 frozen_identity: Mapping[str, str] | None = None) -> ScenarioRunner:
    contracts = ContractLoader()
    injection_type = scenario["injection"]["type"]
    injector = {
        "TOXIPROXY_LATENCY": ToxiproxyLatencyInjector(os.environ["TOXIPROXY_URL"]),
        "HIKARI_CONNECTION_HOLD": LongTransactionInjector(os.environ["ORDER_SERVICE_URL"]),
        "CONTAINER_STOP": InventoryContainerStopInjector(compose_project),
    }[injection_type]
    traffic = RealTraffic(os.environ["SAMPLE_GATEWAY_URL"])
    load = RealLoadGenerator(traffic, injector, os.environ["PROMETHEUS_URL"], os.environ["INVENTORY_SERVICE_URL"])
    registry = ComponentRegistry()
    registry.register("environment", "compose", "1.0.0", RealEnvironmentController(
        os.environ["JDBC_URL"], os.environ["DB_USERNAME"], Path(os.environ["DB_PASSWORD_FILE"]), compose_project,
        os.environ["TOXIPROXY_URL"], os.environ["INVENTORY_SERVICE_URL"], frozen_identity))
    registry.register("health", "real", "1.0.0", RealHealthChecker(load, os.environ["SAMPLE_GATEWAY_URL"], os.environ["INVENTORY_SERVICE_URL"]))
    registry.register("load", "http", "1.0.0", load)
    registry.register("injector", injection_type, "1.0.0", injector)
    registry.register("collector", "observability", "1.0.0", RealArtifactCollector(
        ground_truth, contracts, os.environ["PROMETHEUS_URL"], os.environ["JAEGER_URL"], compose_project, injector))
    registry.register("ticket", "incident", "1.0.0", RealTicketGenerator())
    registry.register("checkpoint", "json", "1.0.0", JsonCheckpointWriter(checkpoint_root))
    registry.register("writer", "dataset", "1.0.0", DatasetWriter(dataset_root))
    registry.register("validator", "dataset", "1.0.0", DatasetValidator())
    registry.freeze()
    return ScenarioRunner(RunnerComponents(
        registry.require("environment", "compose"), registry.require("health", "real"),
        registry.require("load", "http"), registry.require("injector", injection_type),
        registry.require("collector", "observability"), registry.require("ticket", "incident"),
        registry.require("checkpoint", "json"), registry.require("writer", "dataset"),
        registry.require("validator", "dataset")))
