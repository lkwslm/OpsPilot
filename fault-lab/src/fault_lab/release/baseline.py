from __future__ import annotations

import json
import os
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Mapping, Protocol

import psycopg

from ..contracts import ContractError
from ..product import ProductRun
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose


REQUIRED_ROLES = frozenset(
    {
        "supervisor",
        "evidence-collector",
        "code-analysis",
        "knowledge",
        "diagnosis",
        "remediation",
    }
)


@dataclass(frozen=True)
class BaselineRunFacts:
    status: str
    outcome: str | None
    execution_started_at: datetime
    execution_ended_at: datetime
    role_usage: Mapping[str, Mapping[str, int]]


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


class UsageReader(Protocol):
    def read(self, run_id: str) -> BaselineRunFacts: ...


class PostgresBaselineUsageReader:
    def __init__(self, jdbc_url: str, username: str, password_file: Path):
        prefix = "jdbc:postgresql://"
        if not jdbc_url.startswith(prefix):
            raise ContractError(ReleaseErrorCode.CONFIG_INCOMPLETE.value, "JDBC_URL")
        if not username or not password_file.is_file():
            raise ContractError(ReleaseErrorCode.CONFIG_INCOMPLETE.value, "database credentials")
        self.dsn = "postgresql://" + jdbc_url[len(prefix):]
        self.username = username
        self.password_file = password_file

    def read(self, run_id: str) -> BaselineRunFacts:
        password = self.password_file.read_text(encoding="utf-8").strip()
        try:
            with psycopg.connect(self.dsn, user=self.username, password=password) as connection:
                run = connection.execute(
                    """
                    SELECT status, outcome, execution_started_at, execution_ended_at
                    FROM opspilot.incident_run
                    WHERE run_id = %s
                    """,
                    (run_id,),
                ).fetchone()
                usage = connection.execute(
                    """
                    SELECT mu.agent_key,
                           sum(mu.input_tokens)::bigint,
                           sum(mu.output_tokens)::bigint,
                           sum(mu.cached_tokens)::bigint,
                           count(*)::bigint
                    FROM opspilot.model_call mc
                    JOIN opspilot.model_usage mu ON mu.model_call_id = mc.model_call_id
                    WHERE mc.run_id = %s
                    GROUP BY mu.agent_key
                    ORDER BY mu.agent_key
                    """,
                    (run_id,),
                ).fetchall()
        except (OSError, psycopg.Error) as exc:
            raise ContractError(
                ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value, "baseline usage ledger"
            ) from exc
        if run is None or run[2] is None or run[3] is None:
            raise ContractError(ReleaseErrorCode.BASELINE_USAGE_INCOMPLETE.value, "run timeline")
        return BaselineRunFacts(
            status=str(run[0]),
            outcome=None if run[1] is None else str(run[1]),
            execution_started_at=run[2],
            execution_ended_at=run[3],
            role_usage={
                str(row[0]): {
                    "inputTokens": int(row[1]),
                    "outputTokens": int(row[2]),
                    "cachedTokens": int(row[3]),
                    "modelCalls": int(row[4]),
                }
                for row in usage
            },
        )


class BaselineRunner:
    def __init__(self, product: ProductClient, usage: UsageReader, output_root: Path):
        self.product = product
        self.usage = usage
        self.output_root = output_root

    def execute(
        self,
        release_batch_id: str,
        ticket: dict[str, Any],
        snapshot: dict[str, Any],
        *,
        deadline_seconds: int = 600,
        token_budget: int = 32768,
    ) -> dict[str, Any]:
        _validate_inputs(ticket, snapshot, deadline_seconds, token_budget)
        report_path = self.output_root / f"{release_batch_id}-baseline-report.json"
        if report_path.exists():
            raise ContractError(ReleaseErrorCode.BASELINE_REPORT_EXISTS.value, report_path.name)
        profile_id = str(snapshot["evaluationProfile"]["profileId"])
        product_run = self.product.investigate(
            ticket,
            deadline_seconds=deadline_seconds,
            evaluation_profile=profile_id,
            token_budget=token_budget,
            run_identity=f"baseline:{release_batch_id}",
        )
        facts = self.usage.read(product_run.run_id)
        report = _report(release_batch_id, ticket, snapshot, product_run, facts)
        _write_new_json(report_path, report)
        return report


def _validate_inputs(
    ticket: dict[str, Any], snapshot: dict[str, Any], deadline_seconds: int, token_budget: int
) -> None:
    ticket_fields = ("datasetRunId", "scenarioId")
    if any(not isinstance(ticket.get(field), str) or not ticket[field].strip() for field in ticket_fields):
        raise ContractError(ReleaseErrorCode.BASELINE_INPUT_INVALID.value, "ticket")
    try:
        required_snapshot = (
            snapshot["snapshotDigest"],
            snapshot["commit"],
            snapshot["evaluationProfile"]["profileId"],
            snapshot["evaluationProfile"]["digest"],
            snapshot["compose"]["digest"],
            snapshot["hardware"],
            snapshot["scenarios"],
        )
    except (KeyError, TypeError) as exc:
        raise ContractError(ReleaseErrorCode.BASELINE_INPUT_INVALID.value, "snapshot") from exc
    if any(value in (None, "", {}, []) for value in required_snapshot):
        raise ContractError(ReleaseErrorCode.BASELINE_INPUT_INVALID.value, "snapshot")
    if not 60 <= deadline_seconds <= 3600 or token_budget < 1:
        raise ContractError(ReleaseErrorCode.BASELINE_INPUT_INVALID.value, "run budget")
    _scenario_version(ticket, snapshot)


def _report(
    release_batch_id: str,
    ticket: dict[str, Any],
    snapshot: dict[str, Any],
    product_run: ProductRun,
    facts: BaselineRunFacts,
) -> dict[str, Any]:
    roles = set(facts.role_usage)
    if roles != REQUIRED_ROLES:
        missing = sorted(REQUIRED_ROLES - roles)
        extra = sorted(roles - REQUIRED_ROLES)
        raise ContractError(
            ReleaseErrorCode.BASELINE_USAGE_INCOMPLETE.value,
            f"missing={missing},extra={extra}",
        )
    if facts.status != "COMPLETED" or product_run.status != "COMPLETED":
        raise ContractError(ReleaseErrorCode.BASELINE_RUN_FAILED.value, facts.status)
    if facts.execution_ended_at < facts.execution_started_at:
        raise ContractError(ReleaseErrorCode.BASELINE_USAGE_INCOMPLETE.value, "run timeline")
    by_role = []
    for role in sorted(REQUIRED_ROLES):
        raw = facts.role_usage[role]
        try:
            input_tokens = int(raw["inputTokens"])
            output_tokens = int(raw["outputTokens"])
            cached_tokens = int(raw.get("cachedTokens", 0))
            model_calls = int(raw["modelCalls"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ContractError(ReleaseErrorCode.BASELINE_USAGE_INCOMPLETE.value, role) from exc
        if min(input_tokens, output_tokens, cached_tokens) < 0 or model_calls < 1:
            raise ContractError(ReleaseErrorCode.BASELINE_USAGE_INCOMPLETE.value, role)
        by_role.append(
            {
                "role": role,
                "inputTokens": input_tokens,
                "outputTokens": output_tokens,
                "cachedTokens": cached_tokens,
                "totalTokens": input_tokens + output_tokens,
                "modelCalls": model_calls,
            }
        )
    return {
        "schemaVersion": "1.0.0",
        "releaseBatchId": release_batch_id,
        "runPurpose": RunPurpose.BASELINE_ONLY.value,
        "includedInReleaseAggregation": False,
        "status": ReleaseStatus.PASSED.value,
        "scenario": {
            "scenarioId": ticket["scenarioId"],
            "scenarioVersion": _scenario_version(ticket, snapshot),
            "datasetRunId": ticket["datasetRunId"],
        },
        "runIdentity": {
            "incidentId": product_run.incident_id,
            "runId": product_run.run_id,
        },
        "outcome": facts.outcome,
        "startedAt": _wire_time(facts.execution_started_at),
        "endedAt": _wire_time(facts.execution_ended_at),
        "durationMs": int(
            (facts.execution_ended_at - facts.execution_started_at).total_seconds() * 1000
        ),
        "tokenUsage": {
            "byRole": by_role,
            "inputTokens": sum(item["inputTokens"] for item in by_role),
            "outputTokens": sum(item["outputTokens"] for item in by_role),
            "cachedTokens": sum(item["cachedTokens"] for item in by_role),
            "totalTokens": sum(item["totalTokens"] for item in by_role),
            "modelCalls": sum(item["modelCalls"] for item in by_role),
        },
        "environment": {
            "snapshotDigest": snapshot["snapshotDigest"],
            "commit": snapshot["commit"],
            "evaluationProfile": snapshot["evaluationProfile"],
            "composeDigest": snapshot["compose"]["digest"],
            "hardware": snapshot["hardware"],
        },
    }


def _wire_time(value: datetime) -> str:
    return value.isoformat(timespec="milliseconds").replace("+00:00", "Z")


def _scenario_version(ticket: dict[str, Any], snapshot: dict[str, Any]) -> str:
    matches = [
        item.get("scenarioVersion")
        for item in snapshot["scenarios"]
        if item.get("scenarioId") == ticket["scenarioId"]
    ]
    if len(matches) != 1 or not isinstance(matches[0], str) or not matches[0]:
        raise ContractError(ReleaseErrorCode.BASELINE_INPUT_INVALID.value, "scenario snapshot")
    ticket_version = ticket.get("scenarioVersion")
    if ticket_version is not None and ticket_version != matches[0]:
        raise ContractError(ReleaseErrorCode.BASELINE_INPUT_INVALID.value, "scenario version")
    return matches[0]


def _write_new_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    try:
        with temporary.open("x", encoding="utf-8", newline="\n") as handle:
            json.dump(value, handle, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        if path.exists():
            raise ContractError(ReleaseErrorCode.BASELINE_REPORT_EXISTS.value, path.name)
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)
