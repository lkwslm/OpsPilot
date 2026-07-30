from __future__ import annotations

from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from fault_lab.contracts import ContractError
from fault_lab.dataset import DatasetValidator, DatasetWriter
from fault_lab.runner import RunnerComponents, ScenarioRunner


class Clock:
    def __init__(self) -> None:
        self.value = datetime(2026, 7, 29, tzinfo=timezone.utc)

    def __call__(self) -> datetime:
        result = self.value
        self.value += timedelta(seconds=1)
        return result


class Environment:
    def __init__(self, recovery_ok: bool = True) -> None:
        self.resets = 0
        self.recoveries = 0
        self.recovery_ok = recovery_ok

    def reset(self, context):
        self.resets += 1
        context.facts["reset"] = True

    def recover(self, context):
        self.recoveries += 1

    def verify_recovered(self, context):
        return {"service": self.recovery_ok, "toxicRemoved": self.recovery_ok, "dataPreserved": self.recovery_ok}


class Health:
    def __init__(self, fail_baseline: bool = False) -> None:
        self.fail_baseline = fail_baseline

    def check(self, context, phase):
        return {"healthy": not (phase == "baseline" and self.fail_baseline)}


class Load:
    def run(self, context):
        return {"requests": 1200}


class Injector:
    def __init__(self, recover_fails: bool = False) -> None:
        self.recover_fails = recover_fails

    def inject(self, context):
        return {"active": True}

    def recover(self, context):
        if self.recover_fails:
            raise ContractError("INJECTOR_RECOVERY_FAILED", "test")


class Collector:
    def collect(self, context):
        return [("observability.json", b'{"records":[]}')]


class Ticket:
    def generate(self, context):
        return {"datasetRunId": context.dataset_run_id, "summary": "incident"}


class Checkpoints:
    def __init__(self) -> None:
        self.saved = []

    def save(self, context, record):
        self.saved.append((record.stage.value, record.ended_at is not None))


def components(tmp_path: Path, environment: Environment, health: Health, injector: Injector) -> RunnerComponents:
    return RunnerComponents(environment, health, Load(), injector, Collector(), Ticket(), Checkpoints(), DatasetWriter(tmp_path), DatasetValidator())


def scenario() -> dict[str, object]:
    return {"scenarioId": "dependency-latency-inventory", "scenarioVersion": "1.0.0", "seed": 101}


def test_successful_sequence_publishes_valid_dataset(tmp_path: Path) -> None:
    environment = Environment()
    result = ScenarioRunner(components(tmp_path, environment, Health(), Injector()), Clock()).execute(scenario(), tmp_path)
    assert result.status.value == "COMPLETED"
    assert [record.stage.value for record in result.timeline] == [
        "RESETTING", "HEALTH_CHECKING", "BASELINING", "INJECTING", "LOADING", "COLLECTING", "RECOVERING", "EXPORTING", "VALIDATING"
    ]
    assert environment.recoveries == 1
    assert (tmp_path / result.dataset_run_id / "input" / "ticket.json").is_file()


def test_baseline_failure_blocks_injection_but_recovers(tmp_path: Path) -> None:
    environment = Environment()
    result = ScenarioRunner(components(tmp_path, environment, Health(True), Injector()), Clock()).execute(scenario(), tmp_path)
    assert result.status.value == "FAILED"
    assert result.primary_failure == "SCENARIO_PHASE_PREDICATE_FAILED"
    assert environment.recoveries == 1
    assert "INJECTING" not in [record.stage.value for record in result.timeline]


def test_primary_and_recovery_failures_are_distinct(tmp_path: Path) -> None:
    environment = Environment(False)
    result = ScenarioRunner(components(tmp_path, environment, Health(True), Injector(True)), Clock()).execute(scenario(), tmp_path)
    assert result.primary_failure == "SCENARIO_PHASE_PREDICATE_FAILED"
    assert result.recovery_failure == "ENVIRONMENT_RECOVERY_INCOMPLETE"


def test_checkpoint_failure_precedes_reset_side_effect_and_still_recovers(tmp_path: Path) -> None:
    environment = Environment()

    class FailingCheckpoint(Checkpoints):
        def save(self, context, record):
            if not self.saved:
                self.saved.append((record.stage.value, False))
                raise ContractError("CHECKPOINT_WRITE_FAILED", record.stage.value)
            super().save(context, record)

    configured = components(tmp_path, environment, Health(), Injector())
    configured = RunnerComponents(
        configured.environment, configured.health, configured.load, configured.injector,
        configured.collector, configured.ticket, FailingCheckpoint(), configured.writer, configured.validator,
    )
    result = ScenarioRunner(configured, Clock()).execute(scenario(), tmp_path)
    assert result.primary_failure == "CHECKPOINT_WRITE_FAILED"
    assert "reset" not in result.facts
    assert environment.recoveries == 1


def test_cancellation_recovers_then_propagates(tmp_path: Path) -> None:
    environment = Environment()

    class CancelLoad(Load):
        def run(self, context):
            raise KeyboardInterrupt()

    configured = components(tmp_path, environment, Health(), Injector())
    configured = RunnerComponents(
        configured.environment, configured.health, CancelLoad(), configured.injector,
        configured.collector, configured.ticket, configured.checkpoint, configured.writer, configured.validator,
    )
    with pytest.raises(KeyboardInterrupt):
        ScenarioRunner(configured, Clock()).execute(scenario(), tmp_path)
    assert environment.recoveries == 1


def test_export_failure_is_auditable_failed_result(tmp_path: Path) -> None:
    environment = Environment()

    class FailingWriter(DatasetWriter):
        def publish(self, staging, manifest):
            raise ContractError("DATASET_PUBLISH_FAILED", staging.name)

    configured = components(tmp_path, environment, Health(), Injector())
    configured = RunnerComponents(
        configured.environment, configured.health, configured.load, configured.injector,
        configured.collector, configured.ticket, configured.checkpoint,
        FailingWriter(tmp_path), configured.validator,
    )
    result = ScenarioRunner(configured, Clock()).execute(scenario(), tmp_path)
    assert result.status.value == "FAILED"
    assert result.primary_failure == "DATASET_PUBLISH_FAILED"
    assert environment.recoveries == 1


@pytest.mark.parametrize(
    ("failing_stage", "expected_stage"),
    [
        ("reset", "RESETTING"),
        ("health", "HEALTH_CHECKING"),
        ("baseline", "BASELINING"),
        ("inject", "INJECTING"),
        ("load", "LOADING"),
        ("collect", "COLLECTING"),
    ],
)
def test_each_pre_recovery_stage_failure_is_auditable_and_recovers(
    tmp_path: Path, failing_stage: str, expected_stage: str,
) -> None:
    environment = Environment()

    class StageEnvironment(Environment):
        def reset(self, context):
            if failing_stage == "reset":
                raise ContractError("INJECTED_STAGE_FAILURE", failing_stage)
            super().reset(context)

    class StageHealth(Health):
        def check(self, context, phase):
            if failing_stage == phase:
                raise ContractError("INJECTED_STAGE_FAILURE", failing_stage)
            return super().check(context, phase)

    class StageInjector(Injector):
        def inject(self, context):
            if failing_stage == "inject":
                raise ContractError("INJECTED_STAGE_FAILURE", failing_stage)
            return super().inject(context)

    class StageLoad(Load):
        def run(self, context):
            if failing_stage == "load":
                raise ContractError("INJECTED_STAGE_FAILURE", failing_stage)
            return super().run(context)

    class StageCollector(Collector):
        def collect(self, context):
            if failing_stage == "collect":
                raise ContractError("INJECTED_STAGE_FAILURE", failing_stage)
            return super().collect(context)

    if failing_stage == "reset":
        environment = StageEnvironment()
    configured = components(tmp_path, environment, StageHealth(), StageInjector())
    configured = RunnerComponents(
        configured.environment, configured.health, StageLoad(), configured.injector,
        StageCollector(), configured.ticket, configured.checkpoint,
        configured.writer, configured.validator,
    )
    result = ScenarioRunner(configured, Clock()).execute(scenario(), tmp_path)

    assert result.status.value == "FAILED"
    assert result.primary_failure == "INJECTED_STAGE_FAILURE"
    assert environment.recoveries == 1
    failed = next(record for record in result.timeline if record.stage.value == expected_stage)
    assert failed.error_code == "INJECTED_STAGE_FAILURE"


def test_validation_failure_is_auditable_after_recovery(tmp_path: Path) -> None:
    environment = Environment()

    class FailingValidator(DatasetValidator):
        def validate(self, dataset_dir):
            raise ContractError("DATASET_VALIDATION_FAILED", dataset_dir.name)

    configured = components(tmp_path, environment, Health(), Injector())
    configured = RunnerComponents(
        configured.environment, configured.health, configured.load, configured.injector,
        configured.collector, configured.ticket, configured.checkpoint,
        configured.writer, FailingValidator(),
    )
    result = ScenarioRunner(configured, Clock()).execute(scenario(), tmp_path)

    assert result.status.value == "FAILED"
    assert result.primary_failure == "DATASET_VALIDATION_FAILED"
    assert environment.recoveries == 1
    assert next(record for record in result.timeline if record.stage.value == "VALIDATING").error_code \
        == "DATASET_VALIDATION_FAILED"


def test_repeated_reset_and_recovery_leave_no_residual_state(tmp_path: Path) -> None:
    environment = Environment()
    configured = components(tmp_path, environment, Health(), Injector())

    first = ScenarioRunner(configured, Clock()).execute(scenario(), tmp_path)
    second = ScenarioRunner(configured, Clock()).execute(scenario(), tmp_path)

    assert first.status.value == second.status.value == "COMPLETED"
    assert environment.resets == 2
    assert environment.recoveries == 2
    assert first.facts["recovery"] == second.facts["recovery"] == {
        "service": True, "toxicRemoved": True, "dataPreserved": True,
    }
