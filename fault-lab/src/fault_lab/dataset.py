from __future__ import annotations

import hashlib
import json
import os
import re
import uuid
from pathlib import Path
from typing import Any

from .contracts import ContractError
from .model import ArtifactRef, ExecutionContext


_ZONES = {"input", "ground-truth", "execution"}


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


class DatasetWriter:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.staging_root = self.root / ".staging"

    def begin(self, dataset_run_id: str) -> Path:
        self._validate_run_id(dataset_run_id)
        target = self.staging_root / dataset_run_id
        if target.exists() or (self.root / dataset_run_id).exists():
            raise ContractError("DATASET_RUN_ALREADY_EXISTS", dataset_run_id)
        for zone in sorted(_ZONES):
            (target / zone).mkdir(parents=True, exist_ok=False)
        return target

    def write_bytes(self, staging: Path, zone: str, relative_path: str, content: bytes) -> ArtifactRef:
        if zone not in _ZONES:
            raise ContractError("DATASET_ZONE_INVALID", zone)
        relative = Path(relative_path)
        if relative.is_absolute() or ".." in relative.parts or not relative.parts:
            raise ContractError("DATASET_PATH_INVALID", relative_path)
        zone_root = (staging / zone).resolve()
        target = (zone_root / relative).resolve()
        if zone_root != target.parent and zone_root not in target.parents:
            raise ContractError("DATASET_PATH_ESCAPE", relative_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        return ArtifactRef(zone, relative.as_posix(), sha256_bytes(content), len(content))

    def write_json(self, staging: Path, zone: str, relative_path: str, value: Any) -> ArtifactRef:
        return self.write_bytes(staging, zone, relative_path, canonical_json(value) + b"\n")

    def publish(self, staging: Path, manifest: dict[str, Any]) -> Path:
        run_id = staging.name
        artifacts = [ArtifactRef(**artifact) if isinstance(artifact, dict) else artifact for artifact in manifest["artifacts"]]
        manifest["artifacts"] = [artifact.__dict__ for artifact in artifacts]
        (staging / "dataset-manifest.json").write_bytes(canonical_json(manifest) + b"\n")
        DatasetValidator().validate(staging)
        target = self.root / run_id
        os.replace(staging, target)
        for path in target.rglob("*"):
            if path.is_file():
                path.chmod(0o444)
        return target

    @staticmethod
    def _validate_run_id(dataset_run_id: str) -> None:
        try:
            parsed = uuid.UUID(dataset_run_id)
        except ValueError as exc:
            raise ContractError("DATASET_RUN_ID_INVALID", dataset_run_id) from exc
        if str(parsed) != dataset_run_id.lower():
            raise ContractError("DATASET_RUN_ID_INVALID", dataset_run_id)


class DatasetValidator:
    def validate(self, dataset_dir: Path) -> dict[str, Any]:
        root = dataset_dir.resolve()
        manifest_path = root / "dataset-manifest.json"
        if not manifest_path.is_file():
            raise ContractError("DATASET_MANIFEST_MISSING", str(manifest_path))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        DatasetWriter._validate_run_id(manifest.get("datasetRunId", ""))
        if root.name != manifest["datasetRunId"]:
            raise ContractError("DATASET_RUN_ID_MISMATCH", root.name)
        self._validate_windows(manifest.get("windows", {}))
        seen: set[tuple[str, str]] = set()
        for artifact in manifest.get("artifacts", []):
            zone = artifact.get("zone")
            relative = artifact.get("relative_path")
            if zone not in _ZONES or not isinstance(relative, str):
                raise ContractError("DATASET_ARTIFACT_INVALID", str(artifact))
            key = (zone, relative)
            if key in seen:
                raise ContractError("DATASET_ARTIFACT_DUPLICATE", f"{zone}/{relative}")
            seen.add(key)
            unresolved = root / zone / relative
            zone_root = (root / zone).resolve()
            if self._contains_symlink(unresolved, zone_root):
                raise ContractError("DATASET_SYMLINK_FORBIDDEN", f"{zone}/{relative}")
            path = unresolved.resolve()
            if zone_root != path.parent and zone_root not in path.parents:
                raise ContractError("DATASET_PATH_ESCAPE", f"{zone}/{relative}")
            if not path.is_file():
                raise ContractError("DATASET_ARTIFACT_MISSING", f"{zone}/{relative}")
            content = path.read_bytes()
            if len(content) != artifact.get("size") or sha256_bytes(content) != artifact.get("sha256"):
                raise ContractError("DATASET_ARTIFACT_HASH_INVALID", f"{zone}/{relative}")
            if zone == "input" and re.search(rb"(?:ground-truth|execution)[/\\]", content, re.IGNORECASE):
                raise ContractError("DATASET_RESTRICTED_PATH_EXPOSED", relative)
            if path.suffix == ".json":
                try:
                    document = json.loads(content)
                except (UnicodeDecodeError, json.JSONDecodeError) as exc:
                    raise ContractError("DATASET_JSON_INVALID", f"{zone}/{relative}") from exc
                if isinstance(document, dict) and "datasetRunId" in document \
                        and document["datasetRunId"] != manifest["datasetRunId"]:
                    raise ContractError("DATASET_RUN_ID_MISMATCH", f"{zone}/{relative}")
        return manifest

    @staticmethod
    def _contains_symlink(path: Path, zone_root: Path) -> bool:
        current = path
        while current != zone_root and zone_root in current.parents:
            if current.is_symlink():
                return True
            current = current.parent
        return False

    @staticmethod
    def _validate_windows(windows: dict[str, Any]) -> None:
        from datetime import datetime

        previous_end = None
        for name in ("baseline", "fault", "recovery"):
            window = windows.get(name)
            if not isinstance(window, dict) or "start" not in window or "end" not in window:
                raise ContractError("DATASET_WINDOW_INVALID", name)
            try:
                start = datetime.fromisoformat(window["start"].replace("Z", "+00:00"))
                end = datetime.fromisoformat(window["end"].replace("Z", "+00:00"))
            except (TypeError, ValueError) as exc:
                raise ContractError("DATASET_WINDOW_INVALID", name) from exc
            if start >= end or (previous_end is not None and start < previous_end):
                raise ContractError("DATASET_WINDOW_INVALID", name)
            previous_end = end


def manifest_for(
    context: ExecutionContext,
    *,
    git_commit: str,
    compose_digest: str,
    image_digests: dict[str, str],
    model_config_digest: str,
    windows: dict[str, dict[str, str]],
) -> dict[str, Any]:
    return {
        "schemaVersion": "1.0.0",
        "datasetRunId": context.dataset_run_id,
        "scenarioId": context.scenario["scenarioId"],
        "scenarioVersion": context.scenario["scenarioVersion"],
        "seed": context.seed,
        "gitCommit": git_commit,
        "composeDigest": compose_digest,
        "imageDigests": dict(sorted(image_digests.items())),
        "modelConfigDigest": model_config_digest,
        "windows": windows,
        "artifacts": [artifact.__dict__ for artifact in context.artifacts],
    }
