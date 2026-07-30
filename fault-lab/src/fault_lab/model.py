from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Protocol


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


class Stage(str, Enum):
    RESETTING = "RESETTING"
    HEALTH_CHECKING = "HEALTH_CHECKING"
    BASELINING = "BASELINING"
    INJECTING = "INJECTING"
    LOADING = "LOADING"
    COLLECTING = "COLLECTING"
    RECOVERING = "RECOVERING"
    EXPORTING = "EXPORTING"
    VALIDATING = "VALIDATING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


@dataclass(frozen=True)
class ArtifactRef:
    zone: str
    relative_path: str
    sha256: str
    size: int


@dataclass
class StageRecord:
    stage: Stage
    started_at: datetime
    ended_at: datetime | None = None
    input_digest: str = ""
    artifacts: list[ArtifactRef] = field(default_factory=list)
    error_code: str | None = None

    def as_dict(self) -> dict[str, Any]:
        return {
            "stage": self.stage.value,
            "startedAt": self.started_at.isoformat().replace("+00:00", "Z"),
            "endedAt": self.ended_at.isoformat().replace("+00:00", "Z") if self.ended_at else None,
            "inputDigest": self.input_digest,
            "artifacts": [artifact.__dict__ for artifact in self.artifacts],
            "errorCode": self.error_code,
        }


@dataclass
class ExecutionContext:
    dataset_run_id: str
    seed: int
    scenario: dict[str, Any]
    dataset_root: Path
    timeline: list[StageRecord] = field(default_factory=list)
    facts: dict[str, Any] = field(default_factory=dict)
    artifacts: list[ArtifactRef] = field(default_factory=list)
    primary_failure: str | None = None
    recovery_failure: str | None = None
    status: Stage = Stage.RESETTING


class EnvironmentController(Protocol):
    def reset(self, context: ExecutionContext) -> None: ...
    def recover(self, context: ExecutionContext) -> None: ...
    def verify_recovered(self, context: ExecutionContext) -> dict[str, Any]: ...


class HealthChecker(Protocol):
    def check(self, context: ExecutionContext, phase: str) -> dict[str, Any]: ...


class LoadGenerator(Protocol):
    def run(self, context: ExecutionContext) -> dict[str, Any]: ...


class FaultInjector(Protocol):
    def inject(self, context: ExecutionContext) -> dict[str, Any]: ...
    def recover(self, context: ExecutionContext) -> None: ...


class ArtifactCollector(Protocol):
    def collect(self, context: ExecutionContext) -> list[tuple[str, bytes]]: ...


class TicketGenerator(Protocol):
    def generate(self, context: ExecutionContext) -> dict[str, Any]: ...


class CheckpointWriter(Protocol):
    def save(self, context: ExecutionContext, record: StageRecord) -> None: ...
