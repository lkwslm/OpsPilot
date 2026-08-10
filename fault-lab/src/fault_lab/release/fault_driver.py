from __future__ import annotations

import json
import os
import sys
from pathlib import Path

from ..contracts import ContractError
from .failure_catalog import FailureCatalog
from .failure_driver_protocol import (
    PROTOCOL_VERSION,
    FailureDriverProtocol,
    SubprocessCaseExecutor,
)
from .failure_plan import FailurePlan, FailureRouteTopology, FailureTriggerCatalog
from .local_fixture_plane import LocalFixtureDataPlane
from .model import ReleaseErrorCode, ReleaseStatus
from .network_fault_plane import NetworkProxyDataPlane


def main(argv: list[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    operation = arguments[0] if len(arguments) == 1 else ""
    try:
        request = json.load(sys.stdin)
        protocol = _protocol()
        response = protocol.handle(operation, request)
    except (json.JSONDecodeError, ContractError, OSError, ValueError) as exception:
        if isinstance(exception, ContractError):
            code, detail = exception.code, exception.detail
        else:
            code, detail = (
                ReleaseErrorCode.FAILURE_DRIVER_PROTOCOL_INVALID.value,
                type(exception).__name__,
            )
        status = (
            ReleaseStatus.BLOCKED.value
            if code
            in {
                ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
                ReleaseErrorCode.FAILURE_LEASE_CONFLICT.value,
            }
            else ReleaseStatus.FAILED.value
        )
        response = {
            "protocolVersion": PROTOCOL_VERSION,
            "operation": operation,
            "status": status,
            "code": code,
            "detail": detail,
        }
    sys.stdout.write(json.dumps(response, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")
    return 0


def _protocol() -> FailureDriverProtocol:
    fixture_dir = Path(
        os.environ.get(
            "PHASE8_FAILURE_CATALOG_DIR",
            str(Path(__file__).resolve().parents[3] / "fixtures" / "phase8"),
        )
    )
    catalog = FailureCatalog.load(fixture_dir / "failure-catalog-v1.yaml")
    topology = FailureRouteTopology.load(
        fixture_dir / "failure-route-topology-v1.yaml"
    )
    triggers = FailureTriggerCatalog.load(
        fixture_dir / "failure-trigger-catalog-v1.yaml"
    )
    plan = FailurePlan(catalog, topology, triggers)
    root = Path(os.environ.get("PHASE8_FIXTURE_ROOT", "/phase8-fixtures"))
    local = LocalFixtureDataPlane(root)
    network = NetworkProxyDataPlane(
        os.environ.get("TOXIPROXY_URL", "http://toxiproxy:8474"),
        os.environ.get(
            "PHASE8_RESPONSE_PROXY_URL", "http://phase8-response-proxy:18000"
        ),
    )
    try:
        command_value = json.loads(
            os.environ.get("PHASE8_FAILURE_EXECUTE_COMMAND", "[]")
        )
    except json.JSONDecodeError as exception:
        raise ContractError(
            ReleaseErrorCode.FAILURE_DRIVER_PROTOCOL_INVALID.value,
            "PHASE8_FAILURE_EXECUTE_COMMAND",
        ) from exception
    if not isinstance(command_value, list) or not all(
        isinstance(item, str) and item for item in command_value
    ):
        raise ContractError(
            ReleaseErrorCode.FAILURE_DRIVER_PROTOCOL_INVALID.value,
            "PHASE8_FAILURE_EXECUTE_COMMAND",
        )
    return FailureDriverProtocol(
        plan,
        {
            "NETWORK_PROXY": network,
            "FILE_FIXTURE": local,
            "PROCESS_FIXTURE": local,
        },
        root / ".control/active-lease.json",
        Path(
            os.environ.get(
                "PHASE8_FAILURE_DRIVER_EVIDENCE_ROOT",
                "/workspace/outputs/phase8/08-WP04/driver",
            )
        ),
        SubprocessCaseExecutor(tuple(command_value)),
    )


if __name__ == "__main__":
    raise SystemExit(main())
