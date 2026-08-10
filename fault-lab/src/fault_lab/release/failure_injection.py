from __future__ import annotations

import json
import http.client
import socket
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Callable, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode, RunPurpose


class _QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address) -> None:  # noqa: ANN001
        return


class ControlledHttpFailureFixture:
    """A real socket fixture for the four frozen HTTP boundary failure types."""

    def __init__(
        self,
        *,
        bind_host: str = "127.0.0.1",
        advertised_host: str | None = None,
        timeout_delay_seconds: float = 0.2,
    ):
        self.bind_host = bind_host
        self.advertised_host = advertised_host or bind_host
        self.timeout_delay_seconds = timeout_delay_seconds
        self._server: _QuietServer | None = None
        self._thread: threading.Thread | None = None
        self._fault_type: str | None = None
        self._endpoint: str | None = None

    def inject(self, case: Mapping[str, Any]) -> dict[str, Any]:
        if self._endpoint is not None:
            _invalid("fixture already active")
        if case.get("runPurpose") != RunPurpose.FAILURE_INJECTION.value:
            _invalid("runPurpose")
        fault_type = case.get("faultType")
        if fault_type not in {"UNAVAILABLE", "TIMEOUT", "AUTH", "SCHEMA"}:
            _invalid("faultType")
        if case.get("componentKind") not in {
            "MODEL_PROVIDER", "A2A_ENDPOINT", "TOOL", "SOURCE"
        }:
            _invalid("componentKind")

        port = _reserve_port(self.bind_host)
        self._fault_type = fault_type
        self._endpoint = f"http://{self.advertised_host}:{port}"
        handler = self._handler(fault_type)
        self._server = _QuietServer((self.bind_host, port), handler)
        self._thread = threading.Thread(
            target=self._server.serve_forever,
            name=f"phase8-failure-{fault_type.lower()}",
            daemon=True,
        )
        self._thread.start()
        return {
            "caseId": case["caseId"],
            "componentId": case["componentId"],
            "faultType": fault_type,
            "fixtureEndpoint": self._endpoint,
            "injector": case["injector"],
        }

    def probe(self, timeout_seconds: float = 0.05) -> dict[str, Any]:
        if self._endpoint is None or self._fault_type is None:
            _invalid("fixture not active")
        started = time.monotonic()
        status: int | None = None
        body = b""
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(self._endpoint, timeout=timeout_seconds) as response:
                status = response.status
                body = response.read()
        except urllib.error.HTTPError as error:
            status = error.code
            body = error.read()
        except (http.client.RemoteDisconnected, ConnectionError) as error:
            return {
                "observedUpstreamStatus": "UNAVAILABLE",
                "httpStatus": None,
                "elapsedMillis": round((time.monotonic() - started) * 1000, 3),
            }
        except (urllib.error.URLError, TimeoutError, socket.timeout) as error:
            reason = getattr(error, "reason", error)
            observed = "TIMEOUT" if isinstance(reason, (TimeoutError, socket.timeout)) else "UNAVAILABLE"
            if self._fault_type == "TIMEOUT" and "timed out" in str(reason).lower():
                observed = "TIMEOUT"
            return {
                "observedUpstreamStatus": observed,
                "httpStatus": None,
                "elapsedMillis": round((time.monotonic() - started) * 1000, 3),
            }

        if status in {401, 403}:
            observed = "AUTH_FAILED"
        else:
            try:
                json.loads(body)
                observed = "VALID_RESPONSE"
            except (json.JSONDecodeError, UnicodeDecodeError):
                observed = "SCHEMA_INVALID"
        return {
            "observedUpstreamStatus": observed,
            "httpStatus": status,
            "elapsedMillis": round((time.monotonic() - started) * 1000, 3),
        }

    def recover(self, health_check: Callable[[], bool]) -> dict[str, Any]:
        if self._endpoint is None:
            _invalid("fixture not active")
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=2)
        healthy = health_check()
        result = {
            "performed": True,
            "healthy": healthy,
            "residualFaults": [] if healthy else [self._fault_type],
        }
        self._server = None
        self._thread = None
        self._fault_type = None
        self._endpoint = None
        if not healthy:
            _invalid("recovery health check failed")
        return result

    def _handler(self, fault_type: str) -> type[BaseHTTPRequestHandler]:
        delay = self.timeout_delay_seconds

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:  # noqa: N802
                if fault_type == "UNAVAILABLE":
                    self.connection.shutdown(socket.SHUT_RDWR)
                    self.connection.close()
                    return
                if fault_type == "TIMEOUT":
                    time.sleep(delay)
                    self.send_response(504)
                    self.end_headers()
                    return
                if fault_type == "AUTH":
                    payload = b'{"code":"AUTH_FAILED"}'
                    self.send_response(401)
                else:
                    payload = b'{invalid-json'
                    self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                try:
                    self.wfile.write(payload)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, format: str, *args: object) -> None:
                return

        return Handler


def verify_a2a_failure_trace(case: Mapping[str, Any], trace: Mapping[str, Any]) -> dict[str, Any]:
    if case.get("componentKind") != "A2A_ENDPOINT":
        _invalid("not an A2A case")
    operations = trace.get("operations")
    original_task_id = trace.get("originalTaskId")
    original_message_id = trace.get("originalMessageId")
    if not isinstance(operations, list) or not operations or not _text(original_task_id) or not _text(original_message_id):
        _invalid("A2A trace identity")
    sends = [item for item in operations if item.get("operation") == "MESSAGE_SEND"]
    reconciliations = [
        item for item in operations
        if item.get("operation") in {"TASKS_GET", "TASKS_SUBSCRIBE"}
    ]
    if not reconciliations:
        _invalid("A2A task was not reconciled before resend decision")
    first_send_index = next(
        (index for index, item in enumerate(operations) if item.get("operation") == "MESSAGE_SEND"),
        None,
    )
    first_reconcile_index = next(
        index for index, item in enumerate(operations)
        if item.get("operation") in {"TASKS_GET", "TASKS_SUBSCRIBE"}
    )
    if first_send_index is not None and first_send_index < first_reconcile_index:
        _invalid("blind A2A resend")
    task_not_found = any(item.get("status") == "NOT_FOUND" for item in reconciliations)
    if sends and not task_not_found:
        _invalid("A2A resend without authoritative NOT_FOUND")
    if any(item.get("messageId") != original_message_id for item in sends):
        _invalid("A2A resend changed messageId")
    if any(item.get("taskId") not in {None, original_task_id} for item in operations):
        _invalid("A2A operation changed taskId")
    return {
        "status": "PASSED",
        "caseId": case["caseId"],
        "reconciled": True,
        "resent": bool(sends),
        "messageIdIdempotent": True,
    }


def verify_capability_failure_record(
    case: Mapping[str, Any], record: Mapping[str, Any]
) -> dict[str, Any]:
    required = {
        "sourceId", "sourceKind", "adapterId", "attempt", "checkpoint",
        "requestId", "traceId", "runId", "stepId", "invocationId",
        "logArtifactId", "upstreamStatus", "chainFailure",
    }
    if not isinstance(record, Mapping) or not required.issubset(record):
        _invalid("capability failure record fields")
    if any(record[field] != case[field] for field in ("sourceId", "sourceKind", "adapterId")):
        _invalid("capability source identity")
    if (
        not isinstance(record["attempt"], int)
        or isinstance(record["attempt"], bool)
        or not 1 <= record["attempt"] <= 2
        or record["chainFailure"] is not True
        or record["upstreamStatus"] != case["upstreamStatus"]
        or not all(_text(record[field]) for field in required - {"attempt", "chainFailure"})
    ):
        _invalid("capability failure record values")
    return {"status": "PASSED", "caseId": case["caseId"], "attempt": record["attempt"]}


def _reserve_port(host: str) -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((host, 0))
        return int(sock.getsockname()[1])


def _text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_INJECTION_INVALID.value, detail)
