#!/usr/bin/env python3
"""Measure concurrent Infinity embedding/rerank isolation and target-machine resources."""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import os
import pathlib
import platform
import statistics
import subprocess
import time
import urllib.request


def post_json(url: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def timed_call(kind: str, base_url: str, model: str, fixture: object) -> dict:
    started = time.perf_counter()
    if kind == "embedding":
        response = post_json(
            base_url.rstrip("/") + "/embeddings",
            {"model": model, "input": fixture, "encoding_format": "float"},
        )
        valid = response.get("model") == model and all(
            len(item.get("embedding", [])) == 512 for item in response.get("data", [])
        )
        items = len(response.get("data", []))
    else:
        case = fixture
        response = post_json(
            base_url.rstrip("/") + "/rerank",
            {
                "model": model,
                "query": case["query"],
                "documents": case["documents"],
                "top_n": len(case["documents"]),
                "return_documents": False,
            },
        )
        indices = [item.get("index") for item in response.get("results", [])]
        valid = response.get("model") == model and sorted(indices) == list(
            range(len(case["documents"]))
        )
        items = len(indices)
    elapsed_ms = (time.perf_counter() - started) * 1000
    return {
        "kind": kind,
        "expectedModel": model,
        "actualModel": response.get("model"),
        "elapsedMs": round(elapsed_ms, 3),
        "items": items,
        "itemsPerSecond": round(items / (elapsed_ms / 1000), 3),
        "identityAndShapeValid": valid,
    }


def command_json(command: list[str]) -> dict:
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def text_command(command: list[str]) -> str:
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    return result.stdout.strip()


def model_limit(container: str, model_id: str, revision: str) -> dict:
    cache_name = "models--" + model_id.replace("/", "--")
    root = f"/model-cache/hub/{cache_name}/snapshots/{revision}"
    config = command_json(["docker", "exec", container, "cat", root + "/config.json"])
    tokenizer = command_json(
        ["docker", "exec", container, "cat", root + "/tokenizer_config.json"]
    )
    return {
        "modelMaxPositionEmbeddings": config.get("max_position_embeddings"),
        "tokenizerMaxLength": tokenizer.get("model_max_length"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--container", required=True)
    parser.add_argument("--embedding-model", required=True)
    parser.add_argument("--embedding-id", required=True)
    parser.add_argument("--embedding-revision", required=True)
    parser.add_argument("--rerank-model", required=True)
    parser.add_argument("--rerank-id", required=True)
    parser.add_argument("--rerank-revision", required=True)
    parser.add_argument("--embedding-input", required=True, type=pathlib.Path)
    parser.add_argument("--rerank-case", required=True, type=pathlib.Path)
    parser.add_argument("--cold-preload-ms", required=True, type=float)
    parser.add_argument("--warm-cache-startup-ms", required=True, type=float)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    embedding_input = json.loads(args.embedding_input.read_text(encoding="utf-8"))
    rerank_case = json.loads(args.rerank_case.read_text(encoding="utf-8"))
    serial = []
    for _ in range(2):
        serial.append(timed_call(
            "embedding", args.base_url, args.embedding_model, embedding_input
        ))
        serial.append(timed_call(
            "rerank", args.base_url, args.rerank_model, rerank_case
        ))

    call_specs = [
        ("embedding", args.base_url, args.embedding_model, embedding_input),
        ("rerank", args.base_url, args.rerank_model, rerank_case),
        ("embedding", args.base_url, args.embedding_model, embedding_input),
        ("rerank", args.base_url, args.rerank_model, rerank_case),
    ]
    concurrent_started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
        concurrent_results = list(executor.map(lambda spec: timed_call(*spec), call_specs))
    concurrent_wall_ms = (time.perf_counter() - concurrent_started) * 1000

    failures = []
    if any(not result["identityAndShapeValid"] for result in concurrent_results):
        failures.append("CONCURRENT_MODEL_OR_SHAPE_MIXED")
    serial_by_kind = {
        kind: statistics.median(
            result["elapsedMs"] for result in serial if result["kind"] == kind
        )
        for kind in ("embedding", "rerank")
    }
    concurrent_by_kind = {
        kind: statistics.median(
            result["elapsedMs"] for result in concurrent_results if result["kind"] == kind
        )
        for kind in ("embedding", "rerank")
    }
    interference = {
        kind: round(concurrent_by_kind[kind] / serial_by_kind[kind], 3)
        for kind in serial_by_kind
    }

    docker_info = command_json(["docker", "info", "--format", "{{json .}}"])
    inspect = command_json(["docker", "inspect", args.container])[0]
    stats = command_json(
        ["docker", "stats", args.container, "--no-stream", "--format", "{{json .}}"]
    )
    cpu_name = text_command([
        "powershell.exe", "-NoProfile", "-Command",
        "(Get-CimInstance Win32_Processor | Select-Object -First 1).Name",
    ])
    host_memory = int(text_command([
        "powershell.exe", "-NoProfile", "-Command",
        "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory",
    ]))
    try:
        gpu = text_command([
            "nvidia-smi", "--query-gpu=name,memory.total,driver_version",
            "--format=csv,noheader",
        ])
    except (FileNotFoundError, subprocess.CalledProcessError):
        gpu = "NONE"

    report = {
        "schemaVersion": "1.0.0",
        "probeVersion": "phase0-concurrency-v1",
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "PASS" if not failures else "FAIL",
        "machine": {
            "os": platform.platform(),
            "cpu": cpu_name,
            "logicalCpu": os.cpu_count(),
            "hostMemoryBytes": host_memory,
            "dockerCpu": docker_info.get("NCPU"),
            "dockerMemoryBytes": docker_info.get("MemTotal"),
            "gpu": gpu,
            "gpuAssignedToContainer": bool(inspect["HostConfig"].get("DeviceRequests")),
            "inferenceDevice": "cpu",
        },
        "startup": {
            "coldPreloadMs": args.cold_preload_ms,
            "coldPreloadIncludesDownloadAndDefaultWarmup": True,
            "warmCacheStartupMs": args.warm_cache_startup_ms,
            "warmCacheStartupConfiguration": "batch=4,no-model-warmup",
        },
        "maximumLengths": {
            "embedding": model_limit(
                args.container, args.embedding_id, args.embedding_revision
            ),
            "rerank": model_limit(args.container, args.rerank_id, args.rerank_revision),
        },
        "serial": serial,
        "concurrent": {
            "wallMs": round(concurrent_wall_ms, 3),
            "results": concurrent_results,
            "medianInterferenceRatioVsSerial": interference,
        },
        "containerStatsAfter": stats,
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "interference": interference, "failures": failures}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
