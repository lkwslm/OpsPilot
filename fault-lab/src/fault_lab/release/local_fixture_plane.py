from __future__ import annotations

import hashlib
import os
import shutil
import stat
import uuid
from pathlib import Path
from typing import Any, Mapping

from ..contracts import ContractError
from .model import ReleaseErrorCode


class LocalFixtureDataPlane:
    """Injects real file and process-boundary faults inside the isolated fixture volume."""

    def __init__(self, fixture_root: Path):
        self.root = fixture_root.resolve()
        if self.root == Path(self.root.anchor) or self.root.name not in {
            "fixtures",
            "phase8-fixtures",
        }:
            _invalid("fixture root")

    def health(self, case: Mapping[str, Any]) -> dict[str, Any]:
        target = self._target(case)
        residuals = self._residuals(target)
        healthy = target.exists() and not residuals
        if case.get("routeKind") == "PROCESS_FIXTURE" and healthy and os.name != "nt":
            healthy = target.is_file() and bool(target.stat().st_mode & stat.S_IXUSR)
        return {
            "healthy": healthy,
            "routeRef": case.get("routeRef"),
            "routeSnapshot": _snapshot(target),
            "residualFaults": residuals,
        }

    def activate(self, case: Mapping[str, Any]) -> dict[str, Any]:
        target = self._target(case)
        initial = self.health(case)
        if not initial["healthy"]:
            _invalid(f"route not healthy: {case.get('routeRef')}")
        fault_type = case.get("faultType")
        if fault_type not in {"UNAVAILABLE", "TIMEOUT", "AUTH", "SCHEMA"}:
            _invalid("fault type")
        token = uuid.uuid4().hex
        backup = target.with_name(f".{target.name}.phase8-{token}.backup")
        marker = target.with_name(f".{target.name}.phase8-{token}.active")
        original_mode = stat.S_IMODE(target.stat().st_mode)

        try:
            if fault_type == "AUTH":
                marker.write_text("AUTH\n", encoding="utf-8")
                target.chmod(0)
            else:
                target.replace(backup)
                if fault_type == "TIMEOUT":
                    self._activate_timeout(case, target)
                elif fault_type == "SCHEMA":
                    self._activate_schema(case, target)
        except Exception:
            if marker.exists():
                target.chmod(original_mode)
                marker.unlink()
            if backup.exists():
                _remove_fault_node(target)
                backup.replace(target)
            raise

        return {
            "activationId": f"fixture-{token}",
            "routeRef": case["routeRef"],
            "faultType": fault_type,
            "changedRoutes": [case["routeRef"]],
            "before": initial["routeSnapshot"],
            "after": _snapshot(target),
            "recoveryCredential": {
                "token": token,
                "backup": str(backup),
                "marker": str(marker),
                "originalMode": original_mode,
            },
        }

    def recover(
        self, case: Mapping[str, Any], activation: Mapping[str, Any]
    ) -> dict[str, Any]:
        target = self._target(case)
        credential = activation.get("recoveryCredential")
        if not isinstance(credential, Mapping):
            _invalid("recovery credential")
        backup = self._credential_path(credential.get("backup"), target.parent)
        marker = self._credential_path(credential.get("marker"), target.parent)
        fault_type = activation.get("faultType")

        if fault_type == "AUTH":
            if marker.exists():
                target.chmod(int(credential.get("originalMode", 0o550)))
                marker.unlink()
        elif backup.exists():
            _remove_fault_node(target)
            backup.replace(target)

        health = self.health(case)
        if not health["healthy"]:
            raise ContractError(
                ReleaseErrorCode.FAILURE_RECOVERY_FAILED.value,
                str(case.get("routeRef")),
            )
        return {
            "healthy": True,
            "residualFaults": [],
            "restoredRouteRef": case["routeRef"],
            "routeSnapshot": health["routeSnapshot"],
        }

    def _target(self, case: Mapping[str, Any]) -> Path:
        route_kind = case.get("routeKind")
        route_ref = case.get("routeRef")
        if route_kind == "FILE_FIXTURE" and isinstance(route_ref, str) and route_ref.startswith("file://"):
            relative = route_ref.removeprefix("file://")
        elif route_kind == "PROCESS_FIXTURE" and isinstance(route_ref, str) and route_ref.startswith("process://"):
            relative = "process/" + route_ref.removeprefix("process://") + "/run"
        else:
            _invalid("fixture route")
        target = (self.root / relative).resolve()
        if self.root not in target.parents:
            _invalid("fixture route escape")
        return target

    def _credential_path(self, value: Any, parent: Path) -> Path:
        if not isinstance(value, str):
            _invalid("recovery path")
        path = Path(value).resolve()
        if path.parent != parent:
            _invalid("recovery path escape")
        return path

    def _activate_timeout(self, case: Mapping[str, Any], target: Path) -> None:
        if case.get("routeKind") == "PROCESS_FIXTURE":
            target.write_text(
                "#!/bin/sh\nset -eu\nsleep 3600\n",
                encoding="utf-8",
                newline="\n",
            )
            target.chmod(0o550)
            return
        if not hasattr(os, "mkfifo"):
            raise ContractError(
                ReleaseErrorCode.FAILURE_ROUTE_UNAVAILABLE.value,
                "FIFO_UNAVAILABLE",
            )
        os.mkfifo(target, 0o440)

    @staticmethod
    def _activate_schema(case: Mapping[str, Any], target: Path) -> None:
        if case.get("routeKind") == "PROCESS_FIXTURE":
            target.write_text(
                "#!/bin/sh\nset -eu\nprintf '{'\n",
                encoding="utf-8",
                newline="\n",
            )
            target.chmod(0o550)
            return
        if target.suffix in {".json", ".jsonl"}:
            target.write_bytes(b"{\n")
        elif target.suffix == ".properties":
            target.write_bytes(b"\\u00ZZ=phase8-invalid-properties\n")
        else:
            target.write_bytes(b"PHASE8_INVALID_BOUNDARY_SCHEMA\n")

    @staticmethod
    def _residuals(target: Path) -> list[str]:
        return sorted(
            path.name
            for path in target.parent.glob(f".{target.name}.phase8-*")
        )


def _snapshot(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"exists": False, "kind": "MISSING", "mode": None, "sha256": None}
    mode = stat.S_IMODE(path.stat().st_mode)
    if path.is_fifo():
        kind = "FIFO"
        digest = None
    elif path.is_dir():
        kind = "DIRECTORY"
        digest = _directory_digest(path)
    else:
        kind = "FILE"
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return {"exists": True, "kind": kind, "mode": mode, "sha256": digest}


def _directory_digest(path: Path) -> str:
    digest = hashlib.sha256()
    for child in sorted(item for item in path.rglob("*") if item.is_file()):
        digest.update(child.relative_to(path).as_posix().encode())
        digest.update(hashlib.sha256(child.read_bytes()).digest())
    return digest.hexdigest()


def _remove_fault_node(path: Path) -> None:
    if not path.exists():
        return
    if not path.is_fifo():
        path.chmod(0o700)
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink()


def _invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.FAILURE_DATA_PLANE_INVALID.value, detail)
