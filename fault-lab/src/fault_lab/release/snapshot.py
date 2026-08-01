from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Protocol

import yaml

from ..contracts import ContractError
from .model import ReleaseErrorCode


AGENT_PROFILE_ROOT = Path("docs/design/contracts/examples/agent-profiles")
PROMPT_ROOT = Path("opspilot-agent-runtime-agentscope/src/main/resources/agent-profiles/prompts")
MODEL_LOCK = Path("deployment/versions.lock.yaml")
SCENARIO_ROOT = Path("docs/design/contracts/examples/scenarios")
COMPOSE_FILES = (Path("deployment/docker-compose.yml"), Path("deployment/docker-compose.phase7.yml"))
AGENT_PROFILE_FILES = (
    "code-analysis.json",
    "diagnosis.json",
    "evidence-collector.json",
    "knowledge.json",
    "remediation.json",
    "supervisor.json",
)
SCENARIO_FILES = (
    "database-pool-exhausted-order.yaml",
    "dependency-latency-inventory.yaml",
    "service-instance-stopped-inventory.yaml",
)
_HEX_DIGEST = re.compile(r"^[0-9a-f]{40}([0-9a-f]{24})?$")
_IMAGE_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
_UNKNOWN_REVISIONS = {"head", "latest", "main", "master", "unknown", "unset"}
_SENSITIVE_KEYS = {
    "apikey",
    "accesstoken",
    "connectionref",
    "credential",
    "credentials",
    "password",
    "privatekey",
    "secret",
    "secretref",
    "secretvalue",
    "token",
}


class HardwareCollector(Protocol):
    def collect(self) -> dict[str, Any]: ...


@dataclass(frozen=True)
class SnapshotConfig:
    repository_root: Path
    evaluation_profile: Path
    temperature: float
    knowledge_collection_id: str
    knowledge_collection_revision: str
    image_digests: Mapping[str, str]


class SystemHardwareCollector:
    def collect(self) -> dict[str, Any]:
        memory_bytes = _memory_bytes()
        gpu = subprocess.run(
            [
                "nvidia-smi",
                "--query-gpu=name,driver_version,memory.total",
                "--format=csv,noheader,nounits",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        gpus = []
        for line in gpu.stdout.splitlines():
            name, driver, memory = (part.strip() for part in line.split(",", maxsplit=2))
            gpus.append({"name": name, "driverVersion": driver, "memoryMiB": int(memory)})
        return {
            "os": {
                "system": platform.system(),
                "release": platform.release(),
                "machine": platform.machine(),
            },
            "cpu": {
                "model": platform.processor() or os.environ.get("PROCESSOR_IDENTIFIER", ""),
                "logicalCores": os.cpu_count(),
            },
            "memory": {"totalBytes": memory_bytes},
            "gpu": gpus,
        }


class SnapshotCollector:
    def __init__(self, hardware: HardwareCollector | None = None):
        self.hardware = hardware or SystemHardwareCollector()

    def collect(self, config: SnapshotConfig) -> dict[str, Any]:
        _validate_config(config)
        root = config.repository_root.resolve()
        try:
            snapshot = {
                "schemaVersion": "1.0.0",
                "commit": _git_commit(root),
                "temperature": config.temperature,
                "agents": _agents(root),
                "models": _models(root),
                "knowledgeCollection": {
                    "collectionId": config.knowledge_collection_id,
                    "activeRevision": config.knowledge_collection_revision,
                },
                "scenarios": _scenarios(root),
                "evaluationProfile": _evaluation_profile(root, config.evaluation_profile),
                "compose": _compose(root, config.image_digests),
            }
            snapshot["modelConfigDigest"] = _digest_json(
                {
                    "temperature": snapshot["temperature"],
                    "models": snapshot["models"],
                    "agents": [
                        {
                            "role": agent["role"],
                            "profileDigest": agent["profileDigest"],
                            "promptDigests": agent["promptDigests"],
                        }
                        for agent in snapshot["agents"]
                    ],
                }
            )
            snapshot["datasetIdentity"] = {
                "gitCommit": snapshot["commit"],
                "composeDigest": snapshot["compose"]["digest"],
                "modelConfigDigest": snapshot["modelConfigDigest"],
            }
        except (KeyError, OSError, ValueError, subprocess.SubprocessError, yaml.YAMLError, json.JSONDecodeError) as exc:
            raise ContractError(ReleaseErrorCode.SNAPSHOT_SOURCE_INVALID.value, str(exc)) from exc
        try:
            snapshot["hardware"] = self.hardware.collect()
        except Exception as exc:
            raise ContractError(ReleaseErrorCode.HARDWARE_COLLECTION_FAILED.value, type(exc).__name__) from exc
        snapshot = _redact(snapshot)
        _validate_snapshot(snapshot)
        snapshot["snapshotDigest"] = _digest_json(snapshot)
        return snapshot


def _validate_config(config: SnapshotConfig) -> None:
    if isinstance(config.temperature, bool) or config.temperature != 0:
        raise ContractError(ReleaseErrorCode.TEMPERATURE_INVALID.value, "temperature must equal 0")
    required = {
        "knowledgeCollection.collectionId": config.knowledge_collection_id,
        "knowledgeCollection.activeRevision": config.knowledge_collection_revision,
    }
    missing = [name for name, value in required.items() if not isinstance(value, str) or not value.strip()]
    if missing:
        raise ContractError(ReleaseErrorCode.SNAPSHOT_FIELD_MISSING.value, ",".join(missing))
    if not config.image_digests:
        raise ContractError(ReleaseErrorCode.SNAPSHOT_FIELD_MISSING.value, "compose.images")
    for image_id, digest in config.image_digests.items():
        if not image_id or not _IMAGE_DIGEST.fullmatch(digest):
            raise ContractError(ReleaseErrorCode.IMAGE_DIGEST_INVALID.value, str(image_id))


def _validate_snapshot(snapshot: dict[str, Any]) -> None:
    if not _HEX_DIGEST.fullmatch(snapshot["commit"]):
        raise ContractError(ReleaseErrorCode.SNAPSHOT_SOURCE_INVALID.value, "commit")
    if len(snapshot["agents"]) != 6:
        raise ContractError(ReleaseErrorCode.SNAPSHOT_FIELD_MISSING.value, "agents")
    capabilities = {model["capability"] for model in snapshot["models"]}
    if capabilities != {"CHAT", "EMBEDDING", "RERANK"}:
        raise ContractError(ReleaseErrorCode.SNAPSHOT_FIELD_MISSING.value, "models")
    for model in snapshot["models"]:
        revision = model.get("immutableRevision")
        if not isinstance(revision, str) or not revision.strip() or revision.lower() in _UNKNOWN_REVISIONS:
            raise ContractError(ReleaseErrorCode.MODEL_REVISION_INVALID.value, model["capability"])
    if len(snapshot["scenarios"]) != 3:
        raise ContractError(ReleaseErrorCode.SNAPSHOT_FIELD_MISSING.value, "scenarios")
    dataset_identity = snapshot.get("datasetIdentity", {})
    if dataset_identity != {
        "gitCommit": snapshot["commit"],
        "composeDigest": snapshot["compose"]["digest"],
        "modelConfigDigest": snapshot.get("modelConfigDigest"),
    } or not re.fullmatch(r"[0-9a-f]{64}", str(snapshot.get("modelConfigDigest", ""))):
        raise ContractError(ReleaseErrorCode.SNAPSHOT_SOURCE_INVALID.value, "datasetIdentity")
    hardware = snapshot["hardware"]
    required_hardware = (
        hardware.get("os", {}).get("system"),
        hardware.get("os", {}).get("release"),
        hardware.get("os", {}).get("machine"),
        hardware.get("cpu", {}).get("model"),
        hardware.get("cpu", {}).get("logicalCores"),
        hardware.get("memory", {}).get("totalBytes"),
    )
    gpu = hardware.get("gpu")
    if not all(required_hardware) or not isinstance(gpu, list) or not gpu:
        raise ContractError(ReleaseErrorCode.SNAPSHOT_FIELD_MISSING.value, "hardware")
    if any(not item.get("name") or not item.get("driverVersion") for item in gpu):
        raise ContractError(ReleaseErrorCode.SNAPSHOT_FIELD_MISSING.value, "hardware.gpu")


def _redact(value: Any) -> Any:
    if isinstance(value, dict):
        redacted = {}
        for key, item in value.items():
            normalized = str(key).lower().replace("_", "").replace("-", "")
            redacted[key] = _redact_value(item) if normalized in _SENSITIVE_KEYS else _redact(item)
        return redacted
    if isinstance(value, list):
        return [_redact(item) for item in value]
    return value


def _redact_value(value: Any) -> str:
    serialized = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return "redacted:sha256:" + hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def _agents(root: Path) -> list[dict[str, Any]]:
    agents = []
    for filename in AGENT_PROFILE_FILES:
        path = root / AGENT_PROFILE_ROOT / filename
        profile = json.loads(path.read_text(encoding="utf-8"))
        prompt = profile["prompt"]
        prompt_digests = []
        for template_id in (prompt["templateId"], prompt["systemPolicyTemplateId"]):
            prompt_path = root / PROMPT_ROOT / f"{template_id}.md"
            prompt_digests.append({"templateId": template_id, "digest": _digest_file(prompt_path)})
        agents.append(
            {
                "role": profile["role"],
                "profileId": profile["profileId"],
                "profileVersion": profile["profileVersion"],
                "modelProfileRef": profile["model"]["modelProfileRef"],
                "profileDigest": _digest_file(path),
                "promptVersion": prompt["templateVersion"],
                "promptDigests": prompt_digests,
            }
        )
    return sorted(agents, key=lambda item: item["role"])


def _models(root: Path) -> list[dict[str, str]]:
    lock = yaml.safe_load((root / MODEL_LOCK).read_text(encoding="utf-8"))
    chat = lock["chatModel"]
    models = [
        {
            "capability": "CHAT",
            "provider": chat["provider"],
            "modelId": chat["modelId"],
            "immutableRevision": chat["revision"],
        }
    ]
    for model in lock["models"]:
        models.append(
            {
                "capability": str(model["task"]).upper(),
                "modelId": model["modelId"],
                "immutableRevision": model["revision"],
                "servedName": model["servedName"],
            }
        )
    return sorted(models, key=lambda item: item["capability"])


def _scenarios(root: Path) -> list[dict[str, str]]:
    scenarios = []
    for filename in SCENARIO_FILES:
        document = yaml.safe_load((root / SCENARIO_ROOT / filename).read_text(encoding="utf-8"))
        scenarios.append(
            {
                "scenarioId": document["scenarioId"],
                "scenarioVersion": document["scenarioVersion"],
                "digest": _digest_file(root / SCENARIO_ROOT / filename),
            }
        )
    return sorted(scenarios, key=lambda item: item["scenarioId"])


def _evaluation_profile(root: Path, relative_path: Path) -> dict[str, str]:
    path = root / relative_path
    profile = yaml.safe_load(path.read_text(encoding="utf-8"))
    return {
        "profileId": profile["profile_id"],
        "schemaVersion": profile["schema_version"],
        "digest": _digest_file(path),
    }


def _compose(root: Path, image_digests: Mapping[str, str]) -> dict[str, Any]:
    files = [
        {"path": path.as_posix(), "digest": _digest_file(root / path)}
        for path in COMPOSE_FILES
    ]
    images = [
        {"imageId": image_id, "digest": digest}
        for image_id, digest in sorted(image_digests.items())
    ]
    return {"files": files, "images": images, "digest": _digest_json({"files": files, "images": images})}


def _git_commit(root: Path) -> str:
    result = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def _digest_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _digest_json(value: Any) -> str:
    canonical = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _memory_bytes() -> int:
    if os.name == "nt":
        import ctypes

        class MemoryStatus(ctypes.Structure):
            _fields_ = [
                ("length", ctypes.c_ulong),
                ("memoryLoad", ctypes.c_ulong),
                ("totalPhysical", ctypes.c_ulonglong),
                ("availablePhysical", ctypes.c_ulonglong),
                ("totalPageFile", ctypes.c_ulonglong),
                ("availablePageFile", ctypes.c_ulonglong),
                ("totalVirtual", ctypes.c_ulonglong),
                ("availableVirtual", ctypes.c_ulonglong),
                ("availableExtendedVirtual", ctypes.c_ulonglong),
            ]

        status = MemoryStatus()
        status.length = ctypes.sizeof(MemoryStatus)
        if not ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
            raise OSError("GlobalMemoryStatusEx failed")
        return int(status.totalPhysical)
    page_size = os.sysconf("SC_PAGE_SIZE")
    page_count = os.sysconf("SC_PHYS_PAGES")
    return int(page_size * page_count)
