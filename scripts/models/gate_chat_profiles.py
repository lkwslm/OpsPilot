#!/usr/bin/env python3
"""Fail-fast gate that maps real Chat probe evidence back to all logical profiles."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
from typing import Any

from resolve_profiles import resolve


CAPABILITY_PROBES = {
    "structuredOutput": "structured_output",
    "toolCalling": "tool_calling",
    "streaming": "streaming",
}


def evaluate(
    resolved: dict[str, Any], acceptance: dict[str, Any], secret_available: bool
) -> dict[str, Any]:
    records = {
        record.get("requestType"): record.get("status")
        for record in acceptance.get("requestRecords", [])
    }
    accepted_profiles = {
        profile.get("id") for profile in acceptance.get("logicalProfiles", [])
    }
    quota_sufficient = acceptance.get("usage", {}).get("quotaSufficient") is True
    actual_model = acceptance.get("modelIdentity", {}).get("actualModelId")
    results = []
    for profile in resolved.get("logicalProfiles", []):
        gaps = []
        profile_id = profile.get("logicalProfileId")
        identity = profile.get("modelIdentity") or {}
        configured_model = identity.get("modelId")
        if not configured_model:
            gaps.append("MODEL_EMPTY")
        if not secret_available:
            gaps.append("SECRET_REF_UNRESOLVED")
        context = profile.get("contextWindowTokens")
        if not isinstance(context, int) or context < 1:
            gaps.append("CONTEXT_WINDOW_UNKNOWN")
        if configured_model and actual_model != configured_model:
            gaps.append("ACTUAL_MODEL_IDENTITY_MISMATCH")
        for capability, required in (profile.get("requirements") or {}).items():
            if required and records.get(CAPABILITY_PROBES[capability]) != "PASS":
                gaps.append(f"CAPABILITY_UNSUPPORTED:{capability}")
        if not quota_sufficient:
            gaps.append("QUOTA_INSUFFICIENT")
        if profile_id not in accepted_profiles:
            gaps.append("PROFILE_UNVERIFIED")
        results.append(
            {
                "logicalProfileId": profile_id,
                "configuredModelId": configured_model,
                "actualModelId": actual_model,
                "status": "PASS" if not gaps else "FAILED",
                "gaps": gaps,
            }
        )
    return {
        "schemaVersion": "1.0.0",
        "status": "PASS" if len(results) == 7 and all(not item["gaps"] for item in results) else "FAILED",
        "configVersion": resolved.get("configVersion"),
        "logicalProfiles": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--acceptance", type=Path, required=True)
    args = parser.parse_args()
    resolved = resolve(args.config)
    acceptance = json.loads(args.acceptance.read_text(encoding="utf-8"))
    report = evaluate(
        resolved,
        acceptance,
        bool(os.environ.get("DEEPSEEK_API_KEY", "").strip()),
    )
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
