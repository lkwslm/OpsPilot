from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import re
from typing import Any, Callable, Mapping, Protocol

import psycopg

from ..contracts import ContractError
from ..product import ProductRun
from .ledger import PROFESSIONAL_AGENTS, RunLedger
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose


_BATCH_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_DIGEST = re.compile(r"^[0-9a-f]{64}$")
_IDENTITY_QUERY = """
SELECT *
FROM opspilot.read_release_run_identity(%s::uuid, %s::uuid)
"""


@dataclass(frozen=True)
class RunIdentityFacts:
    incident_id: str
    run_id: str
    started_at: datetime
    ended_at: datetime
    supervisor_session_id: str
    a2a_tasks: tuple[Mapping[str, str], ...]


class ProductClient(Protocol):
    def investigate(
        self,
        ticket: dict[str, Any],
        *,
        deadline_seconds: int,
        evaluation_profile: str,
        token_budget: int,
        run_identity: str,
    ) -> ProductRun: ...


class IdentityReader(Protocol):
    def read(self, incident_id: str, run_id: str) -> RunIdentityFacts: ...


class DatasetSelector(Protocol):
    def select(
        self,
        slot: Mapping[str, Any],
        ticket: Mapping[str, Any],
        *,
        expected_environment_digest: str,
        allow_reuse: bool,
    ) -> Mapping[str, Any]: ...


ArtifactProvider = Callable[[ProductRun, RunIdentityFacts], Mapping[str, Path]]


class PostgresRunIdentityReader:
    """Reads the authoritative Supervisor, A2A Task, and AgentScope identities for one Run."""

    def __init__(
        self,
        jdbc_url: str,
        username: str,
        password_file: Path,
        connector: Callable[..., Any] = psycopg.connect,
    ):
        prefix = "jdbc:postgresql://"
        if not jdbc_url.startswith(prefix) or not username or not password_file.is_file():
            raise ContractError(
                ReleaseErrorCode.QUALITY_RUN_INPUT_INVALID.value,
                "database credentials",
            )
        self.dsn = "postgresql://" + jdbc_url[len(prefix):]
        self.username = username
        self.password_file = password_file
        self.connector = connector

    def read(self, incident_id: str, run_id: str) -> RunIdentityFacts:
        if not incident_id or not run_id:
            _identity_invalid("product identity")
        try:
            password = self.password_file.read_text(encoding="utf-8").strip()
            with self.connector(self.dsn, user=self.username, password=password) as connection:
                rows = connection.execute(_IDENTITY_QUERY, (incident_id, run_id)).fetchall()
        except (OSError, psycopg.Error) as exc:
            raise ContractError(
                ReleaseErrorCode.QUALITY_RUN_QUERY_FAILED.value,
                "Run identity query",
            ) from exc
        return _parse_identity_rows(incident_id, run_id, rows)


class QualityRunExecutor:
    """Executes one frozen release slot through the product API and seals its Run identity."""

    def __init__(
        self,
        product: ProductClient,
        identities: IdentityReader,
        ledger: RunLedger,
        datasets: DatasetSelector,
    ):
        self.product = product
        self.identities = identities
        self.ledger = ledger
        self.datasets = datasets

    def execute_slot(
        self,
        release_batch_id: str,
        slot: Mapping[str, Any],
        ticket: dict[str, Any],
        *,
        evaluation_profile: str,
        token_budget: int,
        environment_digest: str,
        deadline_seconds: int = 600,
        artifacts: Mapping[str, Path] | None = None,
        artifact_provider: ArtifactProvider | None = None,
        allow_dataset_reuse: bool = False,
    ) -> dict[str, Any]:
        _validate_slot(
            release_batch_id,
            slot,
            ticket,
            evaluation_profile,
            token_budget,
            environment_digest,
            deadline_seconds,
        )
        self.datasets.select(
            slot,
            ticket,
            expected_environment_digest=environment_digest,
            allow_reuse=allow_dataset_reuse,
        )
        slot_id = str(slot["slotId"])
        product_run = self.product.investigate(
            ticket,
            deadline_seconds=deadline_seconds,
            evaluation_profile=evaluation_profile,
            token_budget=token_budget,
            run_identity=f"release-quality:{release_batch_id}:{slot_id}",
        )
        facts = self.identities.read(product_run.incident_id, product_run.run_id)
        if product_run.status != "COMPLETED" \
                or facts.incident_id != product_run.incident_id \
                or facts.run_id != product_run.run_id:
            _identity_invalid("product/database identity mismatch")
        if artifacts is not None and artifact_provider is not None:
            raise ContractError(
                ReleaseErrorCode.QUALITY_RUN_INPUT_INVALID.value,
                "artifacts/artifact_provider",
            )
        resolved_artifacts = (
            artifact_provider(product_run, facts)
            if artifact_provider is not None
            else artifacts or {}
        )
        record = {
            "releaseBatchId": release_batch_id,
            "runPurpose": RunPurpose.RELEASE_QUALITY.value,
            "runIdentity": {
                "incidentId": facts.incident_id,
                "runId": facts.run_id,
                "datasetRunId": ticket["datasetRunId"],
                "a2aContextId": facts.run_id,
                "supervisorSessionId": facts.supervisor_session_id,
                "a2aTasks": list(facts.a2a_tasks),
                "attempt": 1,
            },
            "startedAt": _wire_time(facts.started_at),
            "endedAt": _wire_time(facts.ended_at),
            "environmentDigest": environment_digest,
            "status": ReleaseStatus.PASSED.value,
            "reason": None,
        }
        return self.ledger.append(record, resolved_artifacts)


def _parse_identity_rows(
    incident_id: str,
    run_id: str,
    rows: list[tuple[Any, ...]],
) -> RunIdentityFacts:
    if not rows:
        _identity_invalid("Run not found")
    roots = {(row[0], row[1], row[2], row[3], row[9]) for row in rows}
    if len(roots) != 1:
        _identity_invalid("ambiguous Run root identity")
    status, started_at, ended_at, _product_task_id, supervisor_session_id = roots.pop()
    if status != "COMPLETED" or not isinstance(started_at, datetime) \
            or not isinstance(ended_at, datetime) or ended_at < started_at \
            or not isinstance(supervisor_session_id, str) or not supervisor_session_id:
        _identity_invalid("Run root identity")

    by_agent: dict[str, dict[str, str]] = {}
    for row in rows:
        agent_id, task_id, context_id, message_id, session_id = row[4:9]
        if agent_id not in PROFESSIONAL_AGENTS or agent_id in by_agent \
                or context_id != run_id:
            _identity_invalid("professional Agent/context set")
        values = (task_id, message_id, session_id)
        if any(not isinstance(value, str) or not value for value in values):
            _identity_invalid(f"{agent_id} identity")
        if not message_id.startswith(f"{run_id}:{agent_id}:") \
                or session_id != f"{agent_id}:{task_id}":
            _identity_invalid(f"{agent_id} correlation")
        by_agent[agent_id] = {
            "agentId": agent_id,
            "a2aTaskId": task_id,
            "messageId": message_id,
            "agentScopeSessionId": session_id,
        }
    if set(by_agent) != set(PROFESSIONAL_AGENTS):
        _identity_invalid("professional Agent set")
    tasks = tuple(by_agent[agent_id] for agent_id in PROFESSIONAL_AGENTS)
    if any(len({task[field] for task in tasks}) != len(tasks)
           for field in ("a2aTaskId", "messageId", "agentScopeSessionId")):
        _identity_invalid("duplicate professional identity")
    return RunIdentityFacts(
        incident_id=incident_id,
        run_id=run_id,
        started_at=started_at,
        ended_at=ended_at,
        supervisor_session_id=supervisor_session_id,
        a2a_tasks=tasks,
    )


def _validate_slot(
    release_batch_id: str,
    slot: Mapping[str, Any],
    ticket: Mapping[str, Any],
    evaluation_profile: str,
    token_budget: int,
    environment_digest: str,
    deadline_seconds: int,
) -> None:
    required_slot = {
        "slotId",
        "scenarioId",
        "scenarioVersion",
        "ordinal",
        "runPurpose",
        "snapshotDigest",
        "status",
    }
    if not _BATCH_ID.fullmatch(release_batch_id) or set(slot) != required_slot \
            or slot.get("runPurpose") != RunPurpose.RELEASE_QUALITY.value \
            or slot.get("status") != "PLANNED" \
            or not isinstance(slot.get("slotId"), str) or not slot["slotId"] \
            or not isinstance(slot.get("ordinal"), int) or isinstance(slot["ordinal"], bool) \
            or slot["ordinal"] < 1 \
            or slot.get("scenarioId") != ticket.get("scenarioId") \
            or ticket.get("scenarioVersion") not in (None, slot.get("scenarioVersion")) \
            or not isinstance(ticket.get("datasetRunId"), str) \
            or not ticket["datasetRunId"] \
            or not _DIGEST.fullmatch(environment_digest) \
            or environment_digest != slot.get("snapshotDigest") \
            or not evaluation_profile \
            or not isinstance(token_budget, int) or isinstance(token_budget, bool) \
            or token_budget < 1 \
            or not isinstance(deadline_seconds, int) or deadline_seconds < 60:
        raise ContractError(
            ReleaseErrorCode.QUALITY_RUN_INPUT_INVALID.value,
            str(slot.get("slotId", "slot")),
        )


def _wire_time(value: datetime) -> str:
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _identity_invalid(detail: str) -> None:
    raise ContractError(ReleaseErrorCode.QUALITY_RUN_IDENTITY_INVALID.value, detail)
