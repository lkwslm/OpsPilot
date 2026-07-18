#!/usr/bin/env python3
"""Probe Infinity rerank identity, indices, scores, and positive ordering."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import pathlib
import time
import urllib.request


def sha256_json(value: object) -> str:
    encoded = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def validate_response(
    response: dict, expected_model: str, document_count: int, positive_indices: set[int]
) -> list[str]:
    failures: list[str] = []
    results = response.get("results", [])
    indices = [item.get("index") for item in results]
    scores = [item.get("relevance_score") for item in results]
    if response.get("model") != expected_model:
        failures.append("RERANK_MODEL_IDENTITY_MISMATCH")
    if sorted(indices) != list(range(document_count)) or len(indices) != len(set(indices)):
        failures.append("RERANK_INDEX_SET_INVALID")
    if any(not isinstance(score, (int, float)) or not math.isfinite(score) for score in scores):
        failures.append("RERANK_SCORE_NON_FINITE")
    score_by_index = dict(zip(indices, scores))
    negative_indices = set(range(document_count)) - positive_indices
    if positive_indices - score_by_index.keys() or negative_indices - score_by_index.keys():
        failures.append("RERANK_RANKING_INPUT_INCOMPLETE")
    elif min(score_by_index[index] for index in positive_indices) <= max(
        score_by_index[index] for index in negative_indices
    ):
        failures.append("RERANK_POSITIVE_NOT_ABOVE_NEGATIVE")
    return failures


def post_json(url: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP_{response.status}")
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--case", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    case = json.loads(args.case.read_text(encoding="utf-8"))
    started = time.perf_counter()
    response = post_json(
        args.base_url.rstrip("/") + "/rerank",
        {
            "model": args.model,
            "query": case["query"],
            "documents": case["documents"],
            "top_n": len(case["documents"]),
            "return_documents": False,
        },
    )
    elapsed_ms = (time.perf_counter() - started) * 1000
    positive_indices = set(case["positiveIndices"])
    failures = validate_response(
        response, args.model, len(case["documents"]), positive_indices
    )
    results = response.get("results", [])
    score_by_index = {
        str(item["index"]): item["relevance_score"] for item in results
    }
    report = {
        "schemaVersion": "1.0.0",
        "probeVersion": "phase0-rerank-v1",
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "PASS" if not failures else "FAIL",
        "model": args.model,
        "responseModel": response.get("model"),
        "caseSha256": sha256_json(case),
        "documentCount": len(case["documents"]),
        "positiveIndices": sorted(positive_indices),
        "negativeIndices": sorted(set(range(len(case["documents"]))) - positive_indices),
        "returnedIndices": [item.get("index") for item in results],
        "scoresByOriginalIndex": score_by_index,
        "elapsedMs": round(elapsed_ms, 3),
        "usage": response.get("usage"),
        "responseSummarySha256": sha256_json(
            [{"index": item.get("index"), "score": item.get("relevance_score")} for item in results]
        ),
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "failures": failures}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
