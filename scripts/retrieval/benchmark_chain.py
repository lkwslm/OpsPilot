#!/usr/bin/env python3
"""Measure the minimal real query embedding -> exact recall -> rerank chain."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
import pathlib
import platform
import subprocess
import time
import urllib.request


def sha256_json(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def post_json(url: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def cosine(left: list[float], right: list[float]) -> float:
    numerator = sum(a * b for a, b in zip(left, right))
    denominator = math.sqrt(sum(a * a for a in left) * sum(b * b for b in right))
    return numerator / denominator


def percentile_nearest_rank(values: list[float], percentile: float) -> float:
    ordered = sorted(values)
    return ordered[math.ceil(percentile * len(ordered)) - 1]


def powershell(expression: str) -> str:
    return subprocess.run(
        ["powershell.exe", "-NoProfile", "-Command", expression],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def execute_chain(
    base_url: str,
    embedding_model: str,
    rerank_model: str,
    query: str,
    documents: list[str],
    document_vectors: list[list[float]],
) -> dict:
    total_started = time.perf_counter()
    embed_started = time.perf_counter()
    embed_response = post_json(
        base_url.rstrip("/") + "/embeddings",
        {"model": embedding_model, "input": [query], "encoding_format": "float"},
    )
    embed_ms = (time.perf_counter() - embed_started) * 1000
    query_vector = embed_response["data"][0]["embedding"]

    recall_started = time.perf_counter()
    ranking = sorted(
        range(len(documents)),
        key=lambda index: cosine(query_vector, document_vectors[index]),
        reverse=True,
    )
    recall_ms = (time.perf_counter() - recall_started) * 1000

    rerank_started = time.perf_counter()
    candidates = [documents[index] for index in ranking]
    rerank_response = post_json(
        base_url.rstrip("/") + "/rerank",
        {
            "model": rerank_model,
            "query": query,
            "documents": candidates,
            "top_n": len(candidates),
            "return_documents": False,
        },
    )
    rerank_ms = (time.perf_counter() - rerank_started) * 1000
    total_ms = (time.perf_counter() - total_started) * 1000
    valid = (
        embed_response.get("model") == embedding_model
        and rerank_response.get("model") == rerank_model
        and sorted(item["index"] for item in rerank_response["results"])
        == list(range(len(documents)))
    )
    return {
        "embeddingMs": round(embed_ms, 3),
        "exactRecallMs": round(recall_ms, 3),
        "rerankMs": round(rerank_ms, 3),
        "totalMs": round(total_ms, 3),
        "baselineOriginalIndices": ranking,
        "identityAndIndexValid": valid,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--embedding-model", required=True)
    parser.add_argument("--rerank-model", required=True)
    parser.add_argument("--case", required=True, type=pathlib.Path)
    parser.add_argument("--samples", type=int, default=20)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    case = json.loads(args.case.read_text(encoding="utf-8"))
    documents = case["documents"]
    query = case["query"]
    document_response = post_json(
        args.base_url.rstrip("/") + "/embeddings",
        {"model": args.embedding_model, "input": documents, "encoding_format": "float"},
    )
    document_vectors = [item["embedding"] for item in document_response["data"]]
    execute_chain(
        args.base_url, args.embedding_model, args.rerank_model,
        query, documents, document_vectors,
    )
    samples = [
        execute_chain(
            args.base_url, args.embedding_model, args.rerank_model,
            query, documents, document_vectors,
        )
        for _ in range(args.samples)
    ]
    totals = [sample["totalMs"] for sample in samples]
    p50 = percentile_nearest_rank(totals, 0.50)
    p95 = percentile_nearest_rank(totals, 0.95)
    failures = []
    if any(not sample["identityAndIndexValid"] for sample in samples):
        failures.append("CHAIN_IDENTITY_OR_INDEX_INVALID")
    if p95 >= 2000.0:
        failures.append("CHAIN_P95_NOT_BELOW_2000_MS")

    docker_info = json.loads(subprocess.run(
        ["docker", "info", "--format", "{{json .}}"],
        check=True, capture_output=True, text=True,
    ).stdout)
    report = {
        "schemaVersion": "1.0.0",
        "probeVersion": "phase0-real-retrieval-chain-v1",
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "PASS" if not failures else "FAIL",
        "inputSha256": sha256_json(case),
        "models": {
            "embedding": args.embedding_model,
            "rerank": args.rerank_model,
        },
        "candidateCount": len(documents),
        "warmupSamplesExcluded": 1,
        "sampleCount": len(samples),
        "aggregation": "nearest-rank: sorted[ceil(p*N)-1]",
        "latencyMs": {"p50": p50, "p95": p95, "thresholdP95Exclusive": 2000.0},
        "machine": {
            "os": platform.platform(),
            "cpu": powershell("(Get-CimInstance Win32_Processor | Select-Object -First 1).Name"),
            "logicalCpu": os.cpu_count(),
            "hostMemoryBytes": int(powershell(
                "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory"
            )),
            "dockerCpu": docker_info.get("NCPU"),
            "dockerMemoryBytes": docker_info.get("MemTotal"),
            "inferenceDevice": "cpu",
        },
        "samples": samples,
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "p50": p50, "p95": p95, "failures": failures}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
