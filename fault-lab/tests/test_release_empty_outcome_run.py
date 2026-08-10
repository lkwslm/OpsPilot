from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from fault_lab.contracts import ContractError
from fault_lab.product import ProductRun
from fault_lab.release.empty_outcome import EmptyOutcomeManifest
from fault_lab.release.empty_outcome_run import (
    EmptyOutcomeRunner,
    KnowledgeControlPlaneClient,
)
from fault_lab.release.model import ReleaseErrorCode


MANIFEST = (
    Path(__file__).resolve().parents[1]
    / "fixtures"
    / "phase8"
    / "empty-outcome-manifest-v1.json"
)


def test_control_client_uses_bearer_and_revision_lifecycle_paths(tmp_path: Path) -> None:
    token = tmp_path / "token"
    token.write_text("control-secret\n", encoding="utf-8")
    calls: list[tuple[str, str, dict[str, str], dict[str, Any]]] = []

    def transport(method: str, url: str, headers: dict[str, str], body: bytes | None):
        document = json.loads(body or b"{}")
        calls.append((method, url, headers, document))
        if url.endswith("/active"):
            return 200, json.dumps({
                "collectionId": document["collectionId"],
                "activeRevisionId": "4af7181d-d049-348e-bf2f-b28cb73e6a95",
            }).encode()
        if url.endswith("/prepare"):
            return 200, json.dumps({"status": "READY", "chunkCount": 0}).encode()
        if url.endswith("/activate"):
            return 200, json.dumps({"receiptId": "ca56a40d-95b7-4578-84b9-0c8eddbd4c6d"}).encode()
        return 200, json.dumps({
            "receiptId": document["receiptId"],
            "restoredAt": "2026-08-01T12:00:00Z",
        }).encode()

    client = KnowledgeControlPlaneClient("http://opspilot-server:8099", token, transport=transport)
    active = client.active("6d23b3aa-853f-3521-b7e2-4880938c946c")
    prepared = client.prepare("batch", EmptyOutcomeManifest.load(MANIFEST).case("KB_EMPTY"), "a" * 64)
    receipt = client.activate(
        "batch", "empty-outcome-kb-empty-v1", prepared_revision_id=
        "01e066b2-ee42-57ea-8311-0efa2395537e", expected_active_revision_id=active
    )
    restored = client.restore("batch", "empty-outcome-kb-empty-v1", receipt["receiptId"])

    assert [call[1].rsplit("/", 1)[1] for call in calls] == [
        "active", "prepare", "activate", "restore"
    ]
    assert all(call[0] == "POST" for call in calls)
    assert all(call[2]["Authorization"] == "Bearer control-secret" for call in calls)
    assert restored["restoredAt"] is not None


def test_runner_restores_each_revision_and_proves_denominator_isolation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    manifest = EmptyOutcomeManifest.load(MANIFEST)
    original = "4af7181d-d049-348e-bf2f-b28cb73e6a95"
    restored: list[str] = []
    active = original

    class Control:
        def active(self, _collection_id: str) -> str:
            return active

        def prepare(self, _batch: str, case: dict[str, Any], _digest: str) -> dict[str, Any]:
            return {
                "status": "READY",
                "revisionId": case["knowledge"]["revisionId"],
                "chunkCount": case["knowledge"]["searchableChunkCount"],
            }

        def activate(self, *_: Any, prepared_revision_id: str, **__: Any) -> dict[str, Any]:
            nonlocal active
            active = prepared_revision_id
            return {"receiptId": f"receipt-{len(restored)}"}

        def restore(self, _batch: str, case_id: str, _receipt: str) -> dict[str, Any]:
            nonlocal active
            restored.append(case_id)
            active = original
            return {"restoredAt": "2026-08-01T12:00:00Z"}

    runs = iter(["run-kb-empty", "run-no-match", "run-no-history"])
    submitted_tickets: list[dict[str, Any]] = []

    class Product:
        def investigate(self, ticket: dict[str, Any], **_: Any) -> ProductRun:
            submitted_tickets.append(ticket)
            run_id = next(runs)
            return ProductRun(f"incident-{run_id}", run_id, "COMPLETED", "CONCLUSIVE")

    class Evidence:
        attempts: dict[str, int] = {}

        def export_empty_outcome(self, run_id: str, root: Path) -> dict[str, Any]:
            self.attempts[run_id] = self.attempts.get(run_id, 0) + 1
            if self.attempts[run_id] == 1:
                raise ContractError(ReleaseErrorCode.QUALITY_EVIDENCE_INVALID.value, run_id)
            kind = {
                "run-kb-empty": "KB_EMPTY",
                "run-no-match": "NO_MATCH",
                "run-no-history": "INSUFFICIENT_HISTORY",
            }[run_id]
            return _fake_evidence(root, run_id, manifest.case(kind))

    monkeypatch.setattr("fault_lab.release.empty_outcome_run.time.sleep", lambda _: None)

    quality_report = tmp_path / "quality-report.json"
    quality_report.write_text(json.dumps({
        "status": "PASSED",
        "completedRuns": 15,
        "runs": [{"runId": f"quality-{index}", "status": "PASSED"} for index in range(15)],
    }), encoding="utf-8")
    profile = {
        "profile_id": "mvp-v2",
        "efficiency_limits": {
            "supervisor_rounds": 12,
            "professional_agent_rounds": 8,
            "total_tool_calls": 30,
            "professional_a2a_attempts": 10,
            "wall_clock_seconds": 600,
            "total_tokens": 65536,
        },
    }

    report = EmptyOutcomeRunner(Control(), Product(), Evidence(), tmp_path / "out").execute(
        "phase8-empty-01", manifest, {"datasetRunId": "fixture", "scenarioId": "scenario"},
        profile, quality_report
    )

    assert report["status"] == "PASSED"
    assert report["denominatorIsolation"]["qualityRunCount"] == 15
    assert report["denominatorIsolation"]["intersection"] == []
    assert restored == [case["caseId"] for case in manifest.document["cases"]]
    assert [ticket["datasetRunId"] for ticket in submitted_tickets] == ["fixture"] * 3
    assert all(
        case["revisionLifecycle"]["restoreVerified"] is True
        for case in report["cases"]
    )
    assert (tmp_path / "out" / "empty-outcome-matrix.json").is_file()


def _fake_evidence(root: Path, run_id: str, case: dict[str, Any]) -> dict[str, Any]:
    target = root / run_id
    target.mkdir(parents=True)
    expected = case["expectations"]
    knowledge = {
        "outcome": expected["knowledgeOutcome"],
        "candidateCount": expected["candidateCount"],
        "rerankCount": expected["rerankCount"],
        "historyResultCount": expected["historyResultCount"],
        "catalogChecked": True,
        "embeddingApplied": bool(expected["calls"]["embedding"]),
        "exactRecallApplied": bool(expected["calls"]["exactRecall"]),
        "rerankApplied": bool(expected["calls"]["rerank"]),
        "historyLookupApplied": bool(expected["calls"]["historyLookup"]),
        "knowledgeRevisionId": case["knowledge"]["revisionId"],
        "fallbacks": expected["fallbacks"],
        "references": [{}] * expected["rerankCount"],
    }
    a2a = {"tasks": [
        {"agentId": "knowledge", "state": "COMPLETED", "artifact": knowledge},
        {"agentId": "evidence-collector", "state": "COMPLETED", "artifact": {
            "evidence": case["fieldEvidence"]
        }},
    ]}
    limits = {name: True for name in (
        "supervisor_rounds", "professional_agent_rounds", "total_tool_calls",
        "professional_a2a_attempts", "wall_clock_seconds", "total_tokens"
    )}
    documents = {
        "a2a-calls": a2a,
        "tool-calls": [{"tool_name": item["toolName"]} for item in case["fieldEvidence"]]
        + [{"tool_name": "KnowledgeSearchTool"}],
        "rca-json": {
            "runId": run_id, "outcome": "CONCLUSIVE", "rootCause": {},
            "evidenceAssessment": {"missingEvidenceCodes": ["trace.missing"]},
            "limitations": ["Knowledge evidence is bounded"],
        },
        "evaluation": {
            "profileId": "mvp-v2", "status": "COMPLETED", "report": {
                "runId": run_id, "valid": True, "hardGateFailures": [],
                "taskCompletionRate": {"value": 1},
                "investigationEfficiency": {
                    "observed": {"supervisorRounds": 1, "maxProfessionalAgentRounds": 1,
                                 "a2aAttempts": 2, "toolCalls": 4, "inputTokens": 10,
                                 "outputTokens": 10, "wallClockMillis": 100},
                    "withinLimits": limits,
                },
            },
        },
    }
    artifacts: dict[str, Path] = {}
    for name, value in documents.items():
        path = target / f"{name}.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        artifacts[name] = path
    return {"artifacts": artifacts}
