from __future__ import annotations

import json

import pytest

from fault_lab.cli import main, parser
from fault_lab.release import ReleaseErrorCode, ReleaseStatus, RunPurpose


def test_release_help_lists_all_workflows(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit) as exc_info:
        parser().parse_args(["release", "--help"])

    assert exc_info.value.code == 0
    help_text = capsys.readouterr().out
    assert "baseline" in help_text
    assert "run" in help_text
    assert "verify" in help_text


def test_scenario_run_accepts_explicit_frozen_dataset_identity(tmp_path) -> None:
    identity = tmp_path / "identity.json"
    parsed = parser().parse_args(
        [
            "run",
            "scenario.yaml",
            "--frozen-identity",
            str(identity),
        ]
    )

    assert parsed.frozen_identity == identity


@pytest.mark.parametrize("workflow", ["baseline", "verify"])
def test_release_workflow_requires_batch_id(workflow: str) -> None:
    with pytest.raises(SystemExit) as exc_info:
        parser().parse_args(["release", workflow])

    assert exc_info.value.code == 2


def test_release_run_rejects_unknown_purpose() -> None:
    with pytest.raises(SystemExit) as exc_info:
        parser().parse_args(
            [
                "release",
                "run",
                "--release-batch-id",
                "phase8-20260730",
                "--run-purpose",
                "UNKNOWN",
            ]
        )

    assert exc_info.value.code == 2


def test_release_rejects_unsafe_batch_id() -> None:
    with pytest.raises(SystemExit) as exc_info:
        parser().parse_args(
            ["release", "verify", "--release-batch-id", "../phase8"]
        )

    assert exc_info.value.code == 2


def test_release_quality_run_reports_missing_prerequisites(capsys: pytest.CaptureFixture[str]) -> None:
    exit_code = main(
        [
            "release",
            "run",
            "--release-batch-id",
            "phase8-20260730",
            "--run-purpose",
            RunPurpose.RELEASE_QUALITY.value,
        ]
    )

    assert exit_code == 2
    payload = json.loads(capsys.readouterr().out)
    assert payload["releaseBatchId"] == "phase8-20260730"
    assert payload["runPurpose"] == RunPurpose.RELEASE_QUALITY.value
    assert payload["status"] == ReleaseStatus.BLOCKED.value
    assert payload["errorCode"] == ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value
    assert payload["workflow"] == "run"
    assert "snapshot" in payload["missing"]


def test_release_baseline_without_real_prerequisites_is_blocked(
    capsys: pytest.CaptureFixture[str],
) -> None:
    exit_code = main(
        ["release", "baseline", "--release-batch-id", "phase8-20260730"]
    )

    assert exit_code == 2
    payload = json.loads(capsys.readouterr().out)
    assert payload["releaseBatchId"] == "phase8-20260730"
    assert payload["runPurpose"] == RunPurpose.BASELINE_ONLY.value
    assert payload["status"] == ReleaseStatus.BLOCKED.value
    assert payload["errorCode"] == ReleaseErrorCode.PREREQUISITE_UNAVAILABLE.value


def test_release_empty_outcome_reports_missing_control_prerequisites(
    capsys: pytest.CaptureFixture[str],
) -> None:
    exit_code = main([
        "release", "run", "--release-batch-id", "phase8-empty-01",
        "--run-purpose", RunPurpose.EMPTY_OUTCOME.value,
    ])

    assert exit_code == 2
    payload = json.loads(capsys.readouterr().out)
    assert payload["runPurpose"] == RunPurpose.EMPTY_OUTCOME.value
    assert payload["status"] == ReleaseStatus.BLOCKED.value
    assert "knowledge-control-url" in payload["missing"]


def test_release_failure_matrix_without_real_driver_seals_blocked_evidence(
    tmp_path, capsys: pytest.CaptureFixture[str]
) -> None:
    exit_code = main([
        "release", "run", "--release-batch-id", "phase8-failure-01",
        "--run-purpose", RunPurpose.FAILURE_INJECTION.value,
        "--output-root", str(tmp_path),
    ])

    assert exit_code == 2
    payload = json.loads(capsys.readouterr().out)
    assert payload["runPurpose"] == RunPurpose.FAILURE_INJECTION.value
    assert payload["status"] == ReleaseStatus.BLOCKED.value
    assert payload["counts"] == {"PASSED": 0, "FAILED": 0, "BLOCKED": 100}
    assert (tmp_path / "criticality-matrix.json").is_file()
    assert len(list((tmp_path / "cases").glob("*/case-result.json"))) == 100
