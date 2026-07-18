#!/usr/bin/env python3
"""Combine the versioned retrieval probe reports into the Phase 0 gate report."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import pathlib


def read(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    for name in ("identity", "embedding", "rerank", "concurrency", "quality", "latency"):
        parser.add_argument(f"--{name}", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()
    paths = {name: getattr(args, name) for name in (
        "identity", "embedding", "rerank", "concurrency", "quality", "latency"
    )}
    reports = {name: read(path) for name, path in paths.items()}
    failures = [
        f"SOURCE_NOT_PASS:{name}" for name, report in reports.items()
        if report.get("status") != "PASS"
    ]
    identity = reports["identity"]
    concurrency = reports["concurrency"]
    quality = reports["quality"]
    latency = reports["latency"]
    report = {
        "schemaVersion": "1.0.0",
        "probeVersion": "phase0-retrieval-gate-v1",
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "PASS" if not failures else "FAIL",
        "infrastructure": {
            "imageDigest": identity["container"]["imageDigest"],
            "imageId": identity["container"]["actualImageId"],
            "infinityVersion": identity["container"]["infinityVersion"],
        },
        "models": [
            {
                "modelId": model["modelId"],
                "revision": model["revision"],
                "servedName": model["servedName"],
                "license": quality["models"][model_id]["license"],
            }
            for model, model_id in zip(identity["models"], ("embedding", "reranker"))
        ],
        "machine": concurrency["machine"],
        "resource": {
            "startup": concurrency["startup"],
            "maximumLengths": concurrency["maximumLengths"],
            "concurrency": concurrency["concurrent"],
            "containerStatsAfter": concurrency["containerStatsAfter"],
        },
        "embedding": {
            "dimension": reports["embedding"]["dimension"],
            "normalizationConvention": reports["embedding"]["normalizationConvention"],
            "cosineNonZeroNorm": reports["embedding"]["cosineNonZeroNorm"],
            "inputSetSha256": reports["embedding"]["inputSetSha256"],
        },
        "rerank": {
            "responseModel": reports["rerank"]["responseModel"],
            "returnedIndices": reports["rerank"]["returnedIndices"],
            "caseSha256": reports["rerank"]["caseSha256"],
        },
        "quality": {
            "datasetSha256": quality["datasetSha256"],
            "queryCount": quality["queryCount"],
            "aggregate": quality["aggregate"],
        },
        "latency": {
            "inputSha256": latency["inputSha256"],
            "sampleCount": latency["sampleCount"],
            "aggregation": latency["aggregation"],
            "latencyMs": latency["latencyMs"],
        },
        "sourceReports": {name: sha256(path) for name, path in paths.items()},
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "failures": failures}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
