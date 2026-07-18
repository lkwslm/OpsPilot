#!/usr/bin/env python3
"""Run three real Infinity embedding calls and validate numeric invariants."""

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


def post_json(url: str, payload: dict) -> dict:
    request = urllib.request.Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP_{response.status}")
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--input", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    texts = json.loads(args.input.read_text(encoding="utf-8"))
    failures: list[str] = []
    runs: list[dict] = []
    expected_dimension: int | None = None
    for run_number in range(1, 4):
        started = time.perf_counter()
        response = post_json(
            args.base_url.rstrip("/") + "/embeddings",
            {"model": args.model, "input": texts, "encoding_format": "float"},
        )
        elapsed_ms = (time.perf_counter() - started) * 1000
        data = response.get("data", [])
        indices = [item.get("index") for item in data]
        vectors = [item.get("embedding", []) for item in data]
        dimensions = [len(vector) for vector in vectors]
        norms = [math.sqrt(sum(value * value for value in vector)) for vector in vectors]

        if response.get("model") != args.model:
            failures.append(f"MODEL_IDENTITY_MISMATCH:run-{run_number}")
        if len(vectors) != len(texts):
            failures.append(f"EMBEDDING_COUNT_MISMATCH:run-{run_number}")
        if indices != list(range(len(texts))):
            failures.append(f"EMBEDDING_ORDER_MISMATCH:run-{run_number}")
        if not dimensions or len(set(dimensions)) != 1:
            failures.append(f"EMBEDDING_DIMENSION_INCONSISTENT:run-{run_number}")
        elif expected_dimension is None:
            expected_dimension = dimensions[0]
        elif dimensions[0] != expected_dimension:
            failures.append(f"EMBEDDING_DIMENSION_DRIFT:run-{run_number}")
        if any(not math.isfinite(value) for vector in vectors for value in vector):
            failures.append(f"EMBEDDING_NON_FINITE:run-{run_number}")
        if any(not math.isfinite(norm) or norm <= 0.0 for norm in norms):
            failures.append(f"EMBEDDING_ZERO_OR_INVALID_NORM:run-{run_number}")

        runs.append(
            {
                "run": run_number,
                "elapsedMs": round(elapsed_ms, 3),
                "count": len(vectors),
                "indices": indices,
                "dimension": None if not dimensions else dimensions[0],
                "l2NormMin": None if not norms else min(norms),
                "l2NormMax": None if not norms else max(norms),
                "vectorSummarySha256": sha256_json(vectors),
                "usage": response.get("usage"),
            }
        )

    all_norms = [value for run in runs for value in (run["l2NormMin"], run["l2NormMax"])]
    normalized = all(value is not None and abs(value - 1.0) <= 1e-3 for value in all_norms)
    if not normalized:
        failures.append("EMBEDDING_NORMALIZATION_CONVENTION_UNEXPECTED")

    report = {
        "schemaVersion": "1.0.0",
        "probeVersion": "phase0-embedding-v1",
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "PASS" if not failures else "FAIL",
        "model": args.model,
        "inputCount": len(texts),
        "inputSetSha256": sha256_json(texts),
        "dimension": expected_dimension,
        "normalizationConvention": "L2_UNIT" if normalized else "UNKNOWN",
        "cosineNonZeroNorm": all(value is not None and value > 0.0 for value in all_norms),
        "runs": runs,
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "dimension": expected_dimension, "failures": failures}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
