#!/usr/bin/env python3
"""Resolve the default and six sparse Phase 0 Chat model profiles without reading secrets."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
from typing import Any

import yaml


ROLE_IDS = (
    "supervisor",
    "evidence_collector",
    "code_analysis",
    "knowledge",
    "diagnosis",
    "remediation",
)

REQUIRED_FIELDS = (
    "provider",
    "protocol",
    "baseUrl",
    "modelId",
    "secretRef",
    "contextWindowTokens",
    "capabilities",
    "quota",
)

FORBIDDEN_SECRET_FIELDS = {"apiKey", "api_key", "secret", "secretValue", "secret_value"}


def deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(result.get(key), dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = copy.deepcopy(value)
    return result


def reject_embedded_secrets(value: Any, path: str = "$") -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key in FORBIDDEN_SECRET_FIELDS:
                raise ValueError(f"Secret value field is forbidden: {path}.{key}")
            reject_embedded_secrets(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            reject_embedded_secrets(child, f"{path}[{index}]")


def validate_effective(profile_id: str, profile: dict[str, Any]) -> None:
    missing = [field for field in REQUIRED_FIELDS if field not in profile]
    if missing:
        raise ValueError(f"{profile_id} is missing fields: {', '.join(missing)}")
    capabilities = profile["capabilities"]
    quota = profile["quota"]
    for name in ("structuredOutput", "toolCalling", "streaming"):
        if not isinstance(capabilities.get(name), bool):
            raise ValueError(f"{profile_id}.capabilities.{name} must be boolean")
    for name in (
        "maxConcurrency",
        "maxInputTokensPerCall",
        "maxOutputTokensPerCall",
        "maxTaskTokens",
        "maxCalls",
    ):
        if not isinstance(quota.get(name), int) or quota[name] < 1:
            raise ValueError(f"{profile_id}.quota.{name} must be a positive integer")


def public_profile(profile_id: str, profile: dict[str, Any]) -> dict[str, Any]:
    capabilities = profile["capabilities"]
    return {
        "logicalProfileId": profile_id,
        "modelIdentity": {
            "provider": profile["provider"],
            "protocol": profile["protocol"],
            "baseUrl": profile["baseUrl"],
            "modelId": profile["modelId"],
        },
        "secretRef": profile["secretRef"],
        "contextWindowTokens": profile["contextWindowTokens"],
        "requirements": {
            "structuredOutput": capabilities["structuredOutput"],
            "toolCalling": capabilities["toolCalling"],
            "streaming": capabilities["streaming"],
        },
        "quota": copy.deepcopy(profile["quota"]),
    }


def resolve(config_path: Path) -> dict[str, Any]:
    raw_bytes = config_path.read_bytes()
    config = yaml.safe_load(raw_bytes)
    if not isinstance(config, dict):
        raise ValueError("Profile configuration must be an object")
    reject_embedded_secrets(config)
    defaults = config.get("defaults")
    roles = config.get("roles")
    if not isinstance(defaults, dict) or not isinstance(roles, dict):
        raise ValueError("defaults and roles must be objects")
    unknown_roles = sorted(set(roles) - set(ROLE_IDS))
    missing_roles = sorted(set(ROLE_IDS) - set(roles))
    if unknown_roles or missing_roles:
        raise ValueError(f"roles mismatch; missing={missing_roles}, unknown={unknown_roles}")

    effective = [("default", copy.deepcopy(defaults))]
    effective.extend((role, deep_merge(defaults, roles[role])) for role in ROLE_IDS)
    profiles = []
    unique_models: dict[tuple[str, str, str, str], dict[str, Any]] = {}
    for profile_id, profile in effective:
        validate_effective(profile_id, profile)
        public = public_profile(profile_id, profile)
        profiles.append(public)
        identity = public["modelIdentity"]
        key = (
            identity["provider"],
            identity["protocol"],
            identity["baseUrl"],
            identity["modelId"],
        )
        unique_models.setdefault(key, identity)

    return {
        "schemaVersion": "1.0.0",
        "configVersion": config.get("configVersion"),
        "sourceSha256": hashlib.sha256(raw_bytes).hexdigest(),
        "logicalProfiles": profiles,
        "uniqueActualModels": list(unique_models.values()),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(resolve(args.config), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
