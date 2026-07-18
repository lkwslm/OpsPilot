#!/usr/bin/env python3
"""Validate the retrieval gate Schema and every frozen semantic assertion."""

from __future__ import annotations

import argparse
import json
import pathlib


def semantic_failures(report: dict, inject_failure: str | None = None) -> list[str]:
    failures: list[str] = []
    if report.get("status") != "PASS" or report.get("failures"):
        failures.append("REPORT_NOT_PASS")
    if report["embedding"]["dimension"] <= 0:
        failures.append("EMBEDDING_DIMENSION_INVALID")
    if report["embedding"]["normalizationConvention"] != "L2_UNIT":
        failures.append("EMBEDDING_NORMALIZATION_INVALID")
    if not report["embedding"]["cosineNonZeroNorm"]:
        failures.append("EMBEDDING_ZERO_NORM")
    aggregate = report["quality"]["aggregate"]
    if aggregate["rerankNdcgAt10"] < aggregate["baselineNdcgAt10"]:
        failures.append("QUALITY_NDCG_REGRESSION")
    if aggregate["rerankMrr"] < aggregate["baselineMrr"]:
        failures.append("QUALITY_MRR_REGRESSION")
    if max(aggregate["ndcgRelativeImprovement"], aggregate["mrrRelativeImprovement"]) < 0.05:
        failures.append("QUALITY_IMPROVEMENT_BELOW_5_PERCENT")
    if report["latency"]["latencyMs"]["p95"] >= 2000.0:
        failures.append("LATENCY_P95_NOT_BELOW_2000_MS")
    if any(
        not result["identityAndShapeValid"]
        for result in report["resource"]["concurrency"]["results"]
    ):
        failures.append("CONCURRENT_MODEL_MIXED")
    if inject_failure == "latency":
        failures.append("INJECTED_LATENCY_ASSERTION_FAILURE")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--schema", type=pathlib.Path)
    parser.add_argument("--report", required=True, type=pathlib.Path)
    parser.add_argument("--skip-schema", action="store_true")
    parser.add_argument("--inject-failure", choices=["latency"])
    args = parser.parse_args()
    report = json.loads(args.report.read_text(encoding="utf-8"))
    if not args.skip_schema:
        if args.schema is None:
            raise SystemExit("--schema is required unless --skip-schema is used")
        import jsonschema
        schema = json.loads(args.schema.read_text(encoding="utf-8"))
        jsonschema.Draft202012Validator(schema).validate(report)
    failures = semantic_failures(report, args.inject_failure)
    print(json.dumps({"status": "PASS" if not failures else "FAIL", "failures": failures}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
