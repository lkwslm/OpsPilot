#!/usr/bin/env python3
"""Validate the live Infinity identity against the immutable Phase 0 lock."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import subprocess
import urllib.request

import yaml


def repeated_values(command: list[str], flag: str) -> list[str]:
    return [command[index + 1] for index, value in enumerate(command[:-1]) if value == flag]


def load_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=10) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP_{response.status}:{url}")
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lock", required=True, type=pathlib.Path)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--container", required=True)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    lock = yaml.safe_load(args.lock.read_text(encoding="utf-8"))
    locked_image = next(
        image for image in lock["containers"] if image["id"] == "infinity"
    )
    locked_models = lock["models"]

    inspect = subprocess.run(
        ["docker", "inspect", args.container],
        check=True,
        capture_output=True,
        text=True,
    )
    container = json.loads(inspect.stdout)[0]
    command = container["Config"]["Cmd"]
    live_models = load_json(args.base_url.rstrip("/") + "/models")["data"]
    live_by_name = {model["id"]: model for model in live_models}

    expected_ids = [model["modelId"] for model in locked_models]
    expected_revisions = [model["revision"] for model in locked_models]
    expected_names = [model["servedName"] for model in locked_models]
    failures: list[str] = []
    if container["Image"] != locked_image["localImageId"]:
        failures.append("IMAGE_ID_MISMATCH")
    if repeated_values(command, "--model-id") != expected_ids:
        failures.append("MODEL_ID_MISMATCH")
    if repeated_values(command, "--revision") != expected_revisions:
        failures.append("MODEL_REVISION_MISMATCH")
    if repeated_values(command, "--served-model-name") != expected_names:
        failures.append("SERVED_NAME_ARGUMENT_MISMATCH")
    if repeated_values(command, "--batch-size") != ["4"]:
        failures.append("BATCH_SIZE_NOT_FIXED")
    if "--no-model-warmup" not in command:
        failures.append("WARMUP_MODE_NOT_FIXED")

    model_results = []
    for model in locked_models:
        live = live_by_name.get(model["servedName"])
        expected_capability = "embed" if model["task"] == "embedding" else "rerank"
        status = "PASS"
        if live is None:
            failures.append(f"MODEL_NOT_SERVED:{model['servedName']}")
            status = "FAIL"
        elif expected_capability not in live["capabilities"]:
            failures.append(f"CAPABILITY_MISMATCH:{model['servedName']}")
            status = "FAIL"
        model_results.append(
            {
                "modelId": model["modelId"],
                "revision": model["revision"],
                "servedName": model["servedName"],
                "expectedCapability": expected_capability,
                "actualCapabilities": [] if live is None else live["capabilities"],
                "backend": None if live is None else live["backend"],
                "batchSize": None if live is None else live["stats"]["batch_size"],
                "status": status,
            }
        )

    report = {
        "schemaVersion": "1.0.0",
        "probeVersion": "phase0-retrieval-identity-v1",
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "PASS" if not failures else "FAIL",
        "baseUrl": args.base_url,
        "container": {
            "name": args.container,
            "imageDigest": locked_image["digest"],
            "expectedImageId": locked_image["localImageId"],
            "actualImageId": container["Image"],
            "infinityVersion": locked_image["components"]["infinity"],
        },
        "models": model_results,
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "failures": failures}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
