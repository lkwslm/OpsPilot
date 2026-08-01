from __future__ import annotations

import json

import pytest

from fault_lab.contracts import ContractError
from fault_lab.product import ProductInvestigationClient


def test_starts_product_run_and_waits_for_completion() -> None:
    calls: list[tuple[str, str, dict[str, str], bytes | None]] = []
    responses = iter([
        (201, b'{"incidentId":"11111111-1111-1111-1111-111111111111"}'),
        (202, b'{"runId":"22222222-2222-2222-2222-222222222222"}'),
        (200, b'{"status":"COLLECTING_EVIDENCE","outcome":null}'),
        (200, b'{"status":"COMPLETED","outcome":"CONCLUSIVE"}'),
    ])

    def transport(method: str, url: str, headers: dict[str, str], body: bytes | None):
        calls.append((method, url, headers, body))
        return next(responses)

    client = ProductInvestigationClient(
        "http://opspilot-server:8080", transport=transport,
        monotonic=iter([0, 1, 2]).__next__, sleep=lambda _: None)
    result = client.investigate({
        "datasetRunId": "33333333-3333-3333-3333-333333333333",
        "scenarioId": "dependency-latency-inventory",
        "title": "test",
    }, deadline_seconds=60, evaluation_profile="mvp-v1", token_budget=32768)

    assert result.status == "COMPLETED"
    assert result.outcome == "CONCLUSIVE"
    assert [call[0] for call in calls] == ["POST", "POST", "GET", "GET"]
    assert calls[0][2]["Idempotency-Key"].startswith("fault-lab-create-")
    assert json.loads(calls[1][3])["evaluationProfile"] == "mvp-v1"
    assert json.loads(calls[1][3])["tokenBudget"] == 32768


def test_rejects_terminal_product_failure() -> None:
    responses = iter([
        (201, b'{"incidentId":"11111111-1111-1111-1111-111111111111"}'),
        (202, b'{"runId":"22222222-2222-2222-2222-222222222222"}'),
        (200, b'{"status":"FAILED","outcome":null}'),
    ])
    client = ProductInvestigationClient(
        "http://opspilot-server:8080", transport=lambda *_: next(responses),
        monotonic=iter([0, 1]).__next__, sleep=lambda _: None)
    with pytest.raises(ContractError, match="OPSPILOT_PRODUCT_RUN_FAILED"):
        client.investigate({
            "datasetRunId": "33333333-3333-3333-3333-333333333333",
            "scenarioId": "dependency-latency-inventory",
        }, deadline_seconds=60)
