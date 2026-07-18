#!/usr/bin/env python3
"""Compare exact cosine retrieval with real rerank on a fixed Chinese set."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import pathlib
import urllib.request

import yaml


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


def ndcg(ranking: list[int], relevances: list[int], k: int = 10) -> float:
    def dcg(indices: list[int]) -> float:
        return sum(
            (2 ** relevances[index] - 1) / math.log2(rank + 2)
            for rank, index in enumerate(indices[:k])
        )

    ideal = sorted(range(len(relevances)), key=lambda index: relevances[index], reverse=True)
    ideal_dcg = dcg(ideal)
    return 0.0 if ideal_dcg == 0.0 else dcg(ranking) / ideal_dcg


def mrr(ranking: list[int], relevances: list[int]) -> float:
    for rank, index in enumerate(ranking, start=1):
        if relevances[index] > 0:
            return 1.0 / rank
    return 0.0


def relative_improvement(after: float, before: float) -> float:
    return 0.0 if before == 0.0 else (after - before) / before


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--embedding-model", required=True)
    parser.add_argument("--rerank-model", required=True)
    parser.add_argument("--lock", required=True, type=pathlib.Path)
    parser.add_argument("--dataset", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    args = parser.parse_args()

    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    lock = yaml.safe_load(args.lock.read_text(encoding="utf-8"))
    model_lock = {model["id"]: model for model in lock["models"]}
    case_reports = []
    baseline_ndcg = []
    rerank_ndcg = []
    baseline_mrr = []
    rerank_mrr = []
    failures = []

    for case in dataset["cases"]:
        texts = [dataset["queryInstruction"] + case["query"]] + [
            document["text"] for document in case["documents"]
        ]
        embedding_response = post_json(
            args.base_url.rstrip("/") + "/embeddings",
            {"model": args.embedding_model, "input": texts, "encoding_format": "float"},
        )
        vectors = [item["embedding"] for item in embedding_response["data"]]
        baseline = sorted(
            range(len(case["documents"])),
            key=lambda index: cosine(vectors[0], vectors[index + 1]),
            reverse=True,
        )
        rerank_response = post_json(
            args.base_url.rstrip("/") + "/rerank",
            {
                "model": args.rerank_model,
                "query": case["query"],
                "documents": [document["text"] for document in case["documents"]],
                "top_n": len(case["documents"]),
                "return_documents": False,
            },
        )
        reranked = [item["index"] for item in rerank_response["results"]]
        relevances = [document["relevance"] for document in case["documents"]]
        metrics = {
            "baselineNdcgAt10": ndcg(baseline, relevances),
            "rerankNdcgAt10": ndcg(reranked, relevances),
            "baselineMrr": mrr(baseline, relevances),
            "rerankMrr": mrr(reranked, relevances),
        }
        baseline_ndcg.append(metrics["baselineNdcgAt10"])
        rerank_ndcg.append(metrics["rerankNdcgAt10"])
        baseline_mrr.append(metrics["baselineMrr"])
        rerank_mrr.append(metrics["rerankMrr"])
        case_reports.append(
            {
                "id": case["id"],
                "baselineRanking": baseline,
                "rerankRanking": reranked,
                **metrics,
            }
        )

    aggregate = {
        "baselineNdcgAt10": sum(baseline_ndcg) / len(baseline_ndcg),
        "rerankNdcgAt10": sum(rerank_ndcg) / len(rerank_ndcg),
        "baselineMrr": sum(baseline_mrr) / len(baseline_mrr),
        "rerankMrr": sum(rerank_mrr) / len(rerank_mrr),
    }
    aggregate["ndcgRelativeImprovement"] = relative_improvement(
        aggregate["rerankNdcgAt10"], aggregate["baselineNdcgAt10"]
    )
    aggregate["mrrRelativeImprovement"] = relative_improvement(
        aggregate["rerankMrr"], aggregate["baselineMrr"]
    )
    if aggregate["rerankNdcgAt10"] < aggregate["baselineNdcgAt10"]:
        failures.append("RERANK_NDCG_REGRESSION")
    if aggregate["rerankMrr"] < aggregate["baselineMrr"]:
        failures.append("RERANK_MRR_REGRESSION")
    if max(
        aggregate["ndcgRelativeImprovement"], aggregate["mrrRelativeImprovement"]
    ) < 0.05:
        failures.append("RERANK_RELATIVE_IMPROVEMENT_BELOW_5_PERCENT")

    report = {
        "schemaVersion": "1.0.0",
        "probeVersion": "phase0-chinese-quality-v1",
        "generatedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "status": "PASS" if not failures else "FAIL",
        "datasetSha256": sha256_json(dataset),
        "language": "zh-CN",
        "queryCount": len(dataset["cases"]),
        "documentsPerQuery": 10,
        "models": {
            "embedding": model_lock["embedding"],
            "reranker": model_lock["reranker"],
        },
        "caseResults": case_reports,
        "aggregate": aggregate,
        "failures": failures,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": report["status"], "aggregate": aggregate, "failures": failures}))
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
