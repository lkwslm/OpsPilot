from __future__ import annotations

import hashlib
import json
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

from .contracts import ContractError
from .dataset import DatasetValidator, DatasetWriter, canonical_json
from .model import (
    ArtifactCollector,
    CheckpointWriter,
    EnvironmentController,
    ExecutionContext,
    FaultInjector,
    HealthChecker,
    LoadGenerator,
    Stage,
    StageRecord,
    TicketGenerator,
    utc_now,
)


@dataclass(frozen=True)
class RunnerComponents:
    environment: EnvironmentController
    health: HealthChecker
    load: LoadGenerator
    injector: FaultInjector
    collector: ArtifactCollector
    ticket: TicketGenerator
    checkpoint: CheckpointWriter
    writer: DatasetWriter
    validator: DatasetValidator


class ScenarioRunner:
    def __init__(self, components: RunnerComponents, clock: Callable[[], Any] = utc_now):
        self.components = components
        self.clock = clock

    def execute(self, scenario: dict[str, Any], dataset_root: Path) -> ExecutionContext:
        context = ExecutionContext(str(uuid.uuid4()), int(scenario["seed"]), scenario, dataset_root)
        staging = self.components.writer.begin(context.dataset_run_id)
        cancellation: BaseException | None = None
        try:
            self._step(context, Stage.RESETTING, lambda: self.components.environment.reset(context))
            self._step(context, Stage.HEALTH_CHECKING, lambda: self._require_facts(self.components.health.check(context, "health")))
            self._step(context, Stage.BASELINING, lambda: self._require_facts(self.components.health.check(context, "baseline")))
            self._step(context, Stage.INJECTING, lambda: context.facts.update(injection=self.components.injector.inject(context)))
            self._step(context, Stage.LOADING, lambda: context.facts.update(load=self.components.load.run(context)))
            self._step(context, Stage.COLLECTING, lambda: self._collect(context, staging))
        except (KeyboardInterrupt, SystemExit) as exc:
            context.primary_failure = self._error_code(exc)
            cancellation = exc
        except Exception as exc:
            context.primary_failure = self._error_code(exc)
        finally:
            try:
                self._step(context, Stage.RECOVERING, lambda: self._recover(context))
            except BaseException as exc:
                context.recovery_failure = self._error_code(exc)
            self._write_execution(context, staging)
        if cancellation is not None:
            raise cancellation
        if context.primary_failure or context.recovery_failure:
            context.status = Stage.FAILED
            return context
        try:
            self._step(context, Stage.EXPORTING, lambda: self._export(context, staging))
            self._step(context, Stage.VALIDATING, lambda: self.components.validator.validate(context.dataset_root / context.dataset_run_id))
        except Exception as exc:
            context.primary_failure = self._error_code(exc)
            context.status = Stage.FAILED
            return context
        context.status = Stage.COMPLETED
        return context

    def _step(self, context: ExecutionContext, stage: Stage, action: Callable[[], Any]) -> None:
        record = StageRecord(stage, self.clock(), input_digest=self._input_digest(context, stage))
        self.components.checkpoint.save(context, record)
        context.status = stage
        context.timeline.append(record)
        try:
            action()
        except BaseException as exc:
            record.error_code = self._error_code(exc)
            raise
        finally:
            record.ended_at = self.clock()
            self.components.checkpoint.save(context, record)

    def _recover(self, context: ExecutionContext) -> None:
        injector_error: BaseException | None = None
        try:
            self.components.injector.recover(context)
        except BaseException as exc:
            injector_error = exc
        self.components.environment.recover(context)
        recovered = self.components.environment.verify_recovered(context)
        context.facts["recovery"] = recovered
        if not recovered or any(value is False for value in recovered.values()):
            raise ContractError("ENVIRONMENT_RECOVERY_INCOMPLETE", context.dataset_run_id)
        if injector_error:
            raise injector_error

    def _collect(self, context: ExecutionContext, staging: Path) -> None:
        for relative_path, content in self.components.collector.collect(context):
            context.artifacts.append(self.components.writer.write_bytes(staging, "input", relative_path, content))
        ticket = self.components.ticket.generate(context)
        context.artifacts.append(self.components.writer.write_json(staging, "input", "ticket.json", ticket))
        ground_truth = context.facts.get("groundTruth")
        if ground_truth is not None:
            context.artifacts.append(self.components.writer.write_json(
                staging, "ground-truth", "ground-truth.json", ground_truth))

    def _write_execution(self, context: ExecutionContext, staging: Path) -> None:
        payload = {
            "datasetRunId": context.dataset_run_id,
            "status": Stage.FAILED.value if context.primary_failure or context.recovery_failure else context.status.value,
            "primaryFailure": context.primary_failure,
            "recoveryFailure": context.recovery_failure,
            "timeline": [record.as_dict() for record in context.timeline],
        }
        context.artifacts.append(self.components.writer.write_json(staging, "execution", "timeline.json", payload))

    def _export(self, context: ExecutionContext, staging: Path) -> None:
        windows = self._windows(context)
        manifest = {
            "schemaVersion": "1.0.0",
            "datasetRunId": context.dataset_run_id,
            "scenarioId": context.scenario["scenarioId"],
            "scenarioVersion": context.scenario["scenarioVersion"],
            "seed": context.seed,
            "gitCommit": context.facts.get("gitCommit", "0" * 40),
            "composeDigest": context.facts.get("composeDigest", "0" * 64),
            "imageDigests": context.facts.get("imageDigests", {}),
            "modelConfigDigest": context.facts.get("modelConfigDigest", "0" * 64),
            "windows": windows,
            "artifacts": [artifact.__dict__ for artifact in context.artifacts],
        }
        published = self.components.writer.publish(staging, manifest)
        if published != context.dataset_root.resolve() / context.dataset_run_id:
            raise ContractError("DATASET_PUBLISH_TARGET_INVALID", str(published))

    @staticmethod
    def _windows(context: ExecutionContext) -> dict[str, dict[str, str]]:
        explicit = context.facts.get("windows")
        if explicit is not None:
            return explicit
        by_stage = {record.stage: record for record in context.timeline}
        mapping = {"baseline": Stage.BASELINING, "fault": Stage.LOADING, "recovery": Stage.RECOVERING}
        windows: dict[str, dict[str, str]] = {}
        previous = None
        for name, stage in mapping.items():
            record = by_stage[stage]
            start = record.started_at if previous is None or record.started_at >= previous else previous
            end = record.ended_at
            if end is None or end <= start:
                from datetime import timedelta
                end = start + timedelta(microseconds=1)
            windows[name] = {
                "start": start.isoformat().replace("+00:00", "Z"),
                "end": end.isoformat().replace("+00:00", "Z"),
            }
            previous = end
        return windows

    @staticmethod
    def _require_facts(facts: dict[str, Any]) -> None:
        if not facts or any(value is False for value in facts.values()):
            raise ContractError("SCENARIO_PHASE_PREDICATE_FAILED", str(facts))

    @staticmethod
    def _input_digest(context: ExecutionContext, stage: Stage) -> str:
        return hashlib.sha256(canonical_json({"run": context.dataset_run_id, "stage": stage.value, "facts": context.facts})).hexdigest()

    @staticmethod
    def _error_code(error: BaseException) -> str:
        return error.code if isinstance(error, ContractError) else error.__class__.__name__


class JsonCheckpointWriter:
    def __init__(self, directory: Path):
        self.directory = directory.resolve()

    def save(self, context: ExecutionContext, record: StageRecord) -> None:
        self.directory.mkdir(parents=True, exist_ok=True)
        target = self.directory / f"{context.dataset_run_id}.json"
        target.write_text(json.dumps({"datasetRunId": context.dataset_run_id, "record": record.as_dict()}, ensure_ascii=False), encoding="utf-8")
