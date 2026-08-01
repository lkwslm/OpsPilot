from __future__ import annotations

import json
import subprocess

from fault_lab.release.quality_environment import RuntimeRecoveryProbe


def snapshot() -> dict[str, object]:
    return {
        "commit": "a" * 40,
        "snapshotDigest": "b" * 64,
        "compose": {
            "images": [
                {"imageId": "opspilot-server", "digest": "sha256:" + "1" * 64},
                {"imageId": "knowledge-agent", "digest": "sha256:" + "2" * 64},
            ]
        },
    }


def command(values):
    def run(arguments, **_):
        if arguments[:3] == ["docker", "ps", "-a"]:
            return subprocess.CompletedProcess(arguments, 0, "one\ntwo\n", "")
        return subprocess.CompletedProcess(arguments, 0, json.dumps(values), "")
    return run


def containers(commit: str = "a" * 40) -> list[dict[str, object]]:
    return [
        {
            "Image": "sha256:" + "1" * 64,
            "Config": {
                "Labels": {"com.docker.compose.service": "opspilot-server"},
                "Env": [f"SOURCE_COMMIT={commit}"],
            },
        },
        {
            "Image": "sha256:" + "2" * 64,
            "Config": {
                "Labels": {"com.docker.compose.service": "knowledge-agent"},
                "Env": [f"SOURCE_COMMIT={commit}"],
            },
        },
    ]


def healthy(url: str):
    if url.endswith("inventory-downstream"):
        return 200, {"toxics": []}
    return 200, {"status": "UP"}


def test_accepts_matching_live_commit_images_health_and_clean_toxiproxy() -> None:
    result = RuntimeRecoveryProbe(
        snapshot(),
        "opspilot",
        ("http://product/health", "http://inventory/health"),
        "http://toxiproxy",
        command_runner=command(containers()),
        http_reader=healthy,
    )("dataset-01")

    assert result == {
        "recovered": True,
        "residualFaults": [],
        "environmentDigest": "b" * 64,
    }


def test_reports_commit_image_and_residual_fault_drift() -> None:
    values = containers("c" * 40)
    values[1]["Image"] = "sha256:" + "9" * 64

    def unhealthy(url: str):
        if url.endswith("inventory-downstream"):
            return 200, {"toxics": [{"name": "inventory-latency-old"}]}
        return 503, {}

    result = RuntimeRecoveryProbe(
        snapshot(),
        "opspilot",
        ("http://product/health",),
        "http://toxiproxy",
        command_runner=command(values),
        http_reader=unhealthy,
    )("dataset-01")

    assert result["recovered"] is False
    assert result["residualFaults"] == [
        "source-commit-drift",
        "image-drift:knowledge-agent",
        "health:http://product/health",
        "toxiproxy-residual-toxic",
    ]
