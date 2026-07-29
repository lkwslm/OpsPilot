#!/usr/bin/env python3
"""Fail-closed scan for sensitive or baseline-expanding AgentProfile configuration."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


SENSITIVE_KEYS = {
    "apikey", "baseurl", "providerurl", "secret", "secretvalue", "password",
    "accesstoken", "bearertoken", "credential", "credentials", "privatekey",
}
FORBIDDEN_TRUE_PATHS = {
    "contextPolicy.factsFromEvidenceOnly": False,
    "toolPolicy.dynamicDiscovery": True,
    "a2aPolicy.peerToPeerDelegation": True,
    "securityPolicy.groundTruthAccess": True,
    "securityPolicy.secretAccess": True,
    "securityPolicy.arbitraryCommandExecution": True,
    "securityPolicy.codeMutation": True,
}


def normalized(value: str) -> str:
    return value.lower().replace("_", "").replace("-", "")


def scan_keys(value: Any, path: str, findings: list[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            field_path = f"{path}.{key}"
            if normalized(key) in SENSITIVE_KEYS:
                findings.append(f"{field_path}: sensitive field name")
            scan_keys(child, field_path, findings)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            scan_keys(child, f"{path}[{index}]", findings)


def nested(document: dict[str, Any], path: str) -> Any:
    value: Any = document
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profiles", type=Path, required=True)
    parser.add_argument("--prompts", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    findings: list[str] = []
    roles: set[str] = set()
    profile_ids: set[str] = set()
    files = sorted(args.profiles.glob("*.json"))
    for profile_path in files:
        document = json.loads(profile_path.read_text(encoding="utf-8"))
        relative = profile_path.name
        scan_keys(document, relative, findings)
        role = document.get("role")
        profile_id = document.get("profileId")
        if role in roles:
            findings.append(f"{relative}.role: duplicate role")
        if profile_id in profile_ids:
            findings.append(f"{relative}.profileId: duplicate profile ID")
        roles.add(role)
        profile_ids.add(profile_id)
        model_ref = nested(document, "model.modelProfileRef")
        if not isinstance(model_ref, str) or "://" in model_ref:
            findings.append(f"{relative}.model.modelProfileRef: must be a logical ID")
        for path, forbidden_value in FORBIDDEN_TRUE_PATHS.items():
            if nested(document, path) == forbidden_value:
                findings.append(f"{relative}.{path}: platform baseline expanded")
        for prompt_field in ("templateId", "systemPolicyTemplateId"):
            prompt_id = nested(document, f"prompt.{prompt_field}")
            if not isinstance(prompt_id, str) or not (args.prompts / f"{prompt_id}.md").is_file():
                findings.append(f"{relative}.prompt.{prompt_field}: prompt resource missing")

    expected_roles = {
        "SUPERVISOR", "EVIDENCE_COLLECTOR", "CODE_ANALYSIS",
        "KNOWLEDGE", "DIAGNOSIS", "REMEDIATION",
    }
    if len(files) != 6 or roles != expected_roles:
        findings.append("profiles: expected exactly the six built-in roles")

    report = {
        "schemaVersion": "1.0.0",
        "status": "PASS" if not findings else "FAIL",
        "profileCount": len(files),
        "roles": sorted(role for role in roles if isinstance(role, str)),
        "sensitiveValueEchoed": False,
        "findingCount": len(findings),
        "findings": findings,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if not findings else 1


if __name__ == "__main__":
    raise SystemExit(main())
