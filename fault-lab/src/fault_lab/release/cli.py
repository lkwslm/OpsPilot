from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
from typing import Any

import yaml

from ..contracts import ContractError
from ..product import ProductInvestigationClient
from .baseline import BaselineRunner, PostgresBaselineUsageReader
from .empty_outcome import EmptyOutcomeManifest
from .empty_outcome_run import EmptyOutcomeRunner, KnowledgeControlPlaneClient
from .ledger import RunLedger
from .model import ReleaseErrorCode, ReleaseStatus, RunPurpose
from .quality import QualityBatchPlanner
from .quality_batch import QualityBatchRunner
from .quality_dataset import QualityDatasetSelector
from .quality_environment import RuntimeRecoveryProbe
from .quality_evidence import (
    PostgresRunEvidenceReader,
    QualityEvidenceCollector,
    QualityRunEvidenceSealer,
)
from .quality_run import PostgresRunIdentityReader, QualityRunExecutor


_BATCH_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")


def release_batch_id(value: str) -> str:
    if not _BATCH_ID.fullmatch(value):
        raise argparse.ArgumentTypeError(
            f"{ReleaseErrorCode.INVALID_BATCH_ID.value}: releaseBatchId 必须是安全的非空标识符"
        )
    return value


def add_release_parser(subcommands: Any) -> None:
    release = subcommands.add_parser("release", help="执行阶段 08 发布门禁")
    workflows = release.add_subparsers(dest="release_command", required=True)
    for name, help_text in (
        ("baseline", "采集并验证发布基线"),
        ("run", "执行发布门禁 Run"),
        ("verify", "验证发布批次证据"),
    ):
        workflow = workflows.add_parser(name, help=help_text)
        workflow.add_argument("--release-batch-id", required=True, type=release_batch_id)
        if name == "baseline":
            workflow.add_argument("--ticket", type=Path)
            workflow.add_argument("--snapshot", type=Path)
            workflow.add_argument(
                "--output-root",
                type=Path,
                default=Path("outputs/phase8/08-WP01/baseline"),
            )
            workflow.add_argument("--product-api", default=os.environ.get("OPSPILOT_PRODUCT_API_URL"))
            workflow.add_argument("--jdbc-url", default=os.environ.get("JDBC_URL"))
            workflow.add_argument("--db-username", default=os.environ.get("DB_USERNAME"))
            workflow.add_argument(
                "--db-password-file",
                type=Path,
                default=Path(os.environ["DB_PASSWORD_FILE"]) if os.environ.get("DB_PASSWORD_FILE") else None,
            )
            workflow.add_argument("--deadline-seconds", type=int, default=600)
            workflow.add_argument("--token-budget", type=int, default=32768)
        if name == "run":
            workflow.add_argument(
                "--run-purpose",
                required=True,
                choices=[purpose.value for purpose in RunPurpose],
            )
            workflow.add_argument("--snapshot", type=Path)
            workflow.add_argument("--ticket", type=Path)
            workflow.add_argument("--wp01-gate", type=Path)
            workflow.add_argument("--evaluation-profile", type=Path)
            workflow.add_argument("--dataset-map", type=Path)
            workflow.add_argument(
                "--dataset-root",
                type=Path,
                default=Path(os.environ.get("DATASET_ROOT", "/datasets")),
            )
            workflow.add_argument(
                "--agent-input-export",
                type=Path,
                default=Path(os.environ.get("AGENT_INPUT_EXPORT_DIR", "/exports/agent-input")),
            )
            workflow.add_argument(
                "--output-root",
                type=Path,
                default=Path("outputs/phase8/08-WP02"),
            )
            workflow.add_argument("--product-api", default=os.environ.get("OPSPILOT_PRODUCT_API_URL"))
            workflow.add_argument("--jdbc-url", default=os.environ.get("JDBC_URL"))
            workflow.add_argument("--db-username", default=os.environ.get("DB_USERNAME"))
            workflow.add_argument(
                "--db-password-file",
                type=Path,
                default=Path(os.environ["DB_PASSWORD_FILE"])
                if os.environ.get("DB_PASSWORD_FILE") else None,
            )
            workflow.add_argument(
                "--compose-project",
                default=os.environ.get("COMPOSE_PROJECT", "opspilot-phase0"),
            )
            workflow.add_argument("--deadline-seconds", type=int, default=600)
            workflow.add_argument("--empty-outcome-manifest", type=Path)
            workflow.add_argument("--quality-run-report", type=Path)
            workflow.add_argument(
                "--knowledge-control-url",
                default=os.environ.get("KNOWLEDGE_CONTROL_URL"),
            )
            workflow.add_argument(
                "--knowledge-control-token-file",
                type=Path,
                default=Path(os.environ["KNOWLEDGE_CONTROL_TOKEN_FILE"])
                if os.environ.get("KNOWLEDGE_CONTROL_TOKEN_FILE") else None,
            )


def run_release_command(args: argparse.Namespace) -> int:
    if args.release_command == "baseline":
        return _run_baseline(args)
    if args.release_command == "run" and args.run_purpose == RunPurpose.RELEASE_QUALITY.value:
        return _run_quality(args)
    if args.release_command == "run" and args.run_purpose == RunPurpose.EMPTY_OUTCOME.value:
        return _run_empty_outcome(args)
    payload = {
        "releaseBatchId": args.release_batch_id,
        "runPurpose": (
            args.run_purpose
            if args.release_command == "run"
            else RunPurpose.BASELINE_ONLY.value if args.release_command == "baseline" else None
        ),
        "status": ReleaseStatus.BLOCKED.value,
        "errorCode": ReleaseErrorCode.WORKFLOW_NOT_IMPLEMENTED.value,
        "workflow": args.release_command,
    }
    if payload["runPurpose"] is None:
        del payload["runPurpose"]
    print(json.dumps(payload, ensure_ascii=False))
    return 2


def _run_quality(args: argparse.Namespace) -> int:
    required = (
        ("snapshot", args.snapshot),
        ("wp01-gate", args.wp01_gate),
        ("evaluation-profile", args.evaluation_profile),
        ("dataset-map", args.dataset_map),
        ("product-api", args.product_api),
        ("jdbc-url", args.jdbc_url),
        ("db-username", args.db_username),
        ("db-password-file", args.db_password_file),
    )
    missing = [name for name, value in required if value is None or value == ""]
    if missing:
        return _blocked_quality(args.release_batch_id, ",".join(missing))
    try:
        snapshot = _json_document(args.snapshot)
        wp01_gate = _json_document(args.wp01_gate)
        dataset_map = _json_document(args.dataset_map)
        profile = _yaml_document(args.evaluation_profile)
        plan = QualityBatchPlanner().plan(
            args.release_batch_id,
            snapshot=snapshot,
            profile=profile,
            wp01_gate=wp01_gate,
        )
        probe = RuntimeRecoveryProbe(
            snapshot,
            args.compose_project,
            (
                args.product_api.rstrip("/") + "/actuator/health/readiness",
                os.environ.get("SAMPLE_GATEWAY_URL", "http://sample-gateway:8080").rstrip("/")
                + "/actuator/health/readiness",
                os.environ.get("INVENTORY_SERVICE_URL", "http://inventory-service:8080").rstrip("/")
                + "/actuator/health/readiness",
            ),
            os.environ.get("TOXIPROXY_URL", "http://toxiproxy:8474"),
        )
        datasets = QualityDatasetSelector(
            args.dataset_root,
            snapshot["datasetIdentity"],
            probe,
            agent_input_root=args.agent_input_export,
        )
        ledger = RunLedger(args.output_root / "run-ledger")
        executor = QualityRunExecutor(
            ProductInvestigationClient(args.product_api, principal="fault-lab:phase8"),
            PostgresRunIdentityReader(
                args.jdbc_url,
                args.db_username,
                args.db_password_file,
            ),
            ledger,
            datasets,
        )
        sealed_root = args.output_root / "runs"
        evidence = QualityEvidenceCollector(
            PostgresRunEvidenceReader(
                args.jdbc_url,
                args.db_username,
                args.db_password_file,
            ),
            QualityRunEvidenceSealer(sealed_root),
            args.output_root / "raw",
            sealed_root,
            evaluation_profile_id=profile["profile_id"],
            evaluation_profile_digest=snapshot["evaluationProfile"]["digest"],
            snapshot_digest=snapshot["snapshotDigest"],
        )
        report = QualityBatchRunner(
            executor,
            evidence,
            args.output_root / "batches",
        ).execute(
            plan,
            snapshot,
            profile,
            args.dataset_root,
            dataset_map,
            deadline_seconds=args.deadline_seconds,
        )
    except ContractError as exc:
        payload = {
            "releaseBatchId": args.release_batch_id,
            "runPurpose": RunPurpose.RELEASE_QUALITY.value,
            "status": ReleaseStatus.BLOCKED.value,
            "errorCode": exc.code,
            "detail": exc.detail,
            "workflow": "run",
        }
        print(json.dumps(payload, ensure_ascii=False))
        return 2
    print(json.dumps(report, ensure_ascii=False))
    if report["status"] == ReleaseStatus.PASSED.value:
        return 0
    return 2 if report["status"] == ReleaseStatus.BLOCKED.value else 1


def _run_empty_outcome(args: argparse.Namespace) -> int:
    required = (
        ("ticket", args.ticket),
        ("evaluation-profile", args.evaluation_profile),
        ("empty-outcome-manifest", args.empty_outcome_manifest),
        ("quality-run-report", args.quality_run_report),
        ("product-api", args.product_api),
        ("jdbc-url", args.jdbc_url),
        ("db-username", args.db_username),
        ("db-password-file", args.db_password_file),
        ("knowledge-control-url", args.knowledge_control_url),
        ("knowledge-control-token-file", args.knowledge_control_token_file),
    )
    missing = [name for name, value in required if value is None or value == ""]
    if missing:
        return _blocked_empty_outcome(args.release_batch_id, ",".join(missing))
    try:
        report = EmptyOutcomeRunner(
            KnowledgeControlPlaneClient(
                args.knowledge_control_url,
                args.knowledge_control_token_file,
            ),
            ProductInvestigationClient(args.product_api, principal="fault-lab:phase8"),
            PostgresRunEvidenceReader(
                args.jdbc_url,
                args.db_username,
                args.db_password_file,
            ),
            args.output_root,
        ).execute(
            args.release_batch_id,
            EmptyOutcomeManifest.load(args.empty_outcome_manifest),
            _json_document(args.ticket),
            _yaml_document(args.evaluation_profile),
            args.quality_run_report,
            deadline_seconds=args.deadline_seconds,
        )
    except ContractError as exc:
        payload = {
            "releaseBatchId": args.release_batch_id,
            "runPurpose": RunPurpose.EMPTY_OUTCOME.value,
            "status": ReleaseStatus.FAILED.value,
            "errorCode": exc.code,
            "detail": exc.detail,
            "workflow": "run",
        }
        print(json.dumps(payload, ensure_ascii=False))
        return 1
    print(json.dumps(report, ensure_ascii=False))
    return 0


def _run_baseline(args: argparse.Namespace) -> int:
    missing = [
        name
        for name, value in (
            ("ticket", args.ticket),
            ("snapshot", args.snapshot),
            ("product-api", args.product_api),
            ("jdbc-url", args.jdbc_url),
            ("db-username", args.db_username),
            ("db-password-file", args.db_password_file),
        )
        if value is None or value == ""
    ]
    if missing:
        return _blocked(args.release_batch_id, ",".join(missing))
    try:
        ticket = _json_document(args.ticket)
        snapshot = _json_document(args.snapshot)
        product = ProductInvestigationClient(args.product_api, principal="fault-lab:phase8")
        usage = PostgresBaselineUsageReader(args.jdbc_url, args.db_username, args.db_password_file)
        report = BaselineRunner(product, usage, args.output_root).execute(
            args.release_batch_id,
            ticket,
            snapshot,
            deadline_seconds=args.deadline_seconds,
            token_budget=args.token_budget,
        )
    except ContractError as exc:
        blocked_codes = {
            ReleaseErrorCode.CONFIG_INCOMPLETE.value,
            ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value,
        }
        payload = {
            "releaseBatchId": args.release_batch_id,
            "runPurpose": RunPurpose.BASELINE_ONLY.value,
            "status": (
                ReleaseStatus.BLOCKED.value
                if exc.code in blocked_codes or exc.code == "OPSPILOT_PRODUCT_UNAVAILABLE"
                else ReleaseStatus.FAILED.value
            ),
            "errorCode": exc.code,
            "workflow": "baseline",
        }
        print(json.dumps(payload, ensure_ascii=False))
        return 2 if payload["status"] == ReleaseStatus.BLOCKED.value else 1
    print(json.dumps(report, ensure_ascii=False))
    return 0


def _json_document(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ContractError(ReleaseErrorCode.BASELINE_INPUT_INVALID.value, path.name) from exc
    if not isinstance(value, dict):
        raise ContractError(ReleaseErrorCode.BASELINE_INPUT_INVALID.value, path.name)
    return value


def _yaml_document(path: Path) -> dict[str, Any]:
    try:
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (OSError, yaml.YAMLError) as exc:
        raise ContractError(ReleaseErrorCode.QUALITY_PLAN_INVALID.value, path.name) from exc
    if not isinstance(value, dict):
        raise ContractError(ReleaseErrorCode.QUALITY_PLAN_INVALID.value, path.name)
    return value


def _blocked(release_batch_id: str, detail: str) -> int:
    print(
        json.dumps(
            {
                "releaseBatchId": release_batch_id,
                "runPurpose": RunPurpose.BASELINE_ONLY.value,
                "status": ReleaseStatus.BLOCKED.value,
                "errorCode": ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value,
                "workflow": "baseline",
                "missing": detail,
            },
            ensure_ascii=False,
        )
    )
    return 2


def _blocked_quality(release_batch_id: str, detail: str) -> int:
    print(
        json.dumps(
            {
                "releaseBatchId": release_batch_id,
                "runPurpose": RunPurpose.RELEASE_QUALITY.value,
                "status": ReleaseStatus.BLOCKED.value,
                "errorCode": ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value,
                "workflow": "run",
                "missing": detail,
            },
            ensure_ascii=False,
        )
    )
    return 2


def _blocked_empty_outcome(release_batch_id: str, detail: str) -> int:
    print(json.dumps({
        "releaseBatchId": release_batch_id,
        "runPurpose": RunPurpose.EMPTY_OUTCOME.value,
        "status": ReleaseStatus.BLOCKED.value,
        "errorCode": ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value,
        "workflow": "run",
        "missing": detail,
    }, ensure_ascii=False))
    return 2
