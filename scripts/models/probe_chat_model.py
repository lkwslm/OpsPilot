#!/usr/bin/env python3
"""Run separate real Provider probes without persisting secrets, prompts, or responses."""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from resolve_profiles import resolve


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def secret_name(secret_ref: str) -> str:
    prefix = "env:"
    if not secret_ref.startswith(prefix) or len(secret_ref) == len(prefix):
        raise ValueError("Only non-empty env: Secret refs are supported in Phase 0")
    return secret_ref[len(prefix) :]


def request_json(
    base_url: str, api_key: str, payload: dict[str, Any]
) -> tuple[int, dict[str, Any]]:
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.status, json.load(response)


def request_stream(base_url: str, api_key: str, payload: dict[str, Any]) -> tuple[int, int]:
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
        method="POST",
    )
    chunks = 0
    with urllib.request.urlopen(request, timeout=60) as response:
        for raw_line in response:
            line = raw_line.decode("utf-8", errors="replace").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            json.loads(data)
            chunks += 1
        return response.status, chunks


def probe(unique_model: dict[str, Any], secret_ref: str) -> dict[str, Any]:
    environment_name = secret_name(secret_ref)
    api_key = os.environ.get(environment_name, "")
    identity = {
        "provider": unique_model["provider"],
        "protocol": unique_model["protocol"],
        "configuredModelId": unique_model["modelId"],
    }
    if not api_key.strip():
        return {
            "status": "BLOCKED",
            "requestType": "preflight",
            "timestampUtc": utc_now(),
            "modelIdentity": identity,
            "secretRef": secret_ref,
            "error": {"code": "SECRET_REF_UNRESOLVED", "retryable": False},
        }

    base = {
        "model": unique_model["modelId"],
        "messages": [{"role": "user", "content": "Return the requested minimal result."}],
        "temperature": 0,
        "max_tokens": 64,
    }
    probes = (
        (
            "identity",
            base,
            lambda body: bool(body.get("model")) and bool(body.get("choices")),
        ),
        (
            "structured_output",
            {
                **base,
                "messages": [
                    {
                        "role": "user",
                        "content": "Return JSON only in exactly this shape: {\"ok\": true}. Do not add prose.",
                    }
                ],
                "max_tokens": 128,
                "response_format": {"type": "json_object"},
            },
            lambda body: _valid_structured_output(body),
        ),
        (
            "tool_calling",
            {
                **base,
                "messages": [
                    {
                        "role": "user",
                        "content": "Call phase0_echo with value ok. You must use the tool and must not answer directly.",
                    }
                ],
                "tools": [
                    {
                        "type": "function",
                        "function": {
                            "name": "phase0_echo",
                            "description": "Echo a capability probe value. Always call this tool for the requested probe.",
                            "parameters": {
                                "type": "object",
                                "properties": {"value": {"type": "string"}},
                                "required": ["value"],
                                "additionalProperties": False,
                            },
                        },
                    }
                ],
            },
            lambda body: _has_tool_call(body),
        ),
    )
    results = []
    actual_model = None
    usage = {"promptTokens": 0, "completionTokens": 0, "totalTokens": 0}
    try:
        for request_type, payload, validator in probes:
            status, body = request_json(unique_model["baseUrl"], api_key, payload)
            actual_model = actual_model or body.get("model")
            raw_usage = body.get("usage") or {}
            usage["promptTokens"] += int(raw_usage.get("prompt_tokens", 0))
            usage["completionTokens"] += int(raw_usage.get("completion_tokens", 0))
            usage["totalTokens"] += int(raw_usage.get("total_tokens", 0))
            results.append(
                {
                    "requestType": request_type,
                    "status": "PASS" if status == 200 and validator(body) else "FAILED",
                    "httpStatus": status,
                    "timestampUtc": utc_now(),
                }
            )
        stream_status, stream_chunks = request_stream(
            unique_model["baseUrl"], api_key, {**base, "stream": True}
        )
        results.append(
            {
                "requestType": "streaming",
                "status": "PASS" if stream_status == 200 and stream_chunks > 0 else "FAILED",
                "httpStatus": stream_status,
                "eventChunks": stream_chunks,
                "timestampUtc": utc_now(),
            }
        )
    except urllib.error.HTTPError as error:
        code = "QUOTA_INSUFFICIENT" if error.code == 429 else "PROVIDER_HTTP_ERROR"
        results.append(
            {
                "requestType": "provider_call",
                "status": "BLOCKED" if error.code in (401, 402, 403, 429) else "FAILED",
                "httpStatus": error.code,
                "timestampUtc": utc_now(),
                "error": {"code": code, "retryable": error.code == 429},
            }
        )
    except (OSError, ValueError, json.JSONDecodeError) as error:
        results.append(
            {
                "requestType": "provider_call",
                "status": "FAILED",
                "timestampUtc": utc_now(),
                "error": {"code": type(error).__name__, "retryable": isinstance(error, OSError)},
            }
        )

    statuses = {item["status"] for item in results}
    status = "FAILED" if "FAILED" in statuses else "BLOCKED" if "BLOCKED" in statuses else "PASS"
    return {
        "status": status,
        "modelIdentity": {**identity, "actualModelId": actual_model},
        "secretRef": secret_ref,
        "usage": usage,
        "probes": results,
    }


def _valid_structured_output(body: dict[str, Any]) -> bool:
    try:
        content = body["choices"][0]["message"]["content"]
        return isinstance(json.loads(content), dict)
    except (KeyError, IndexError, TypeError, json.JSONDecodeError):
        return False


def _has_tool_call(body: dict[str, Any]) -> bool:
    try:
        calls = body["choices"][0]["message"]["tool_calls"]
        return len(calls) == 1 and calls[0]["function"]["name"] == "phase0_echo"
    except (KeyError, IndexError, TypeError):
        return False


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    resolved = resolve(args.config)
    secret_ref = resolved["logicalProfiles"][0]["secretRef"]
    results = [probe(model, secret_ref) for model in resolved["uniqueActualModels"]]
    status = "PASS"
    if any(item["status"] == "FAILED" for item in results):
        status = "FAILED"
    elif any(item["status"] == "BLOCKED" for item in results):
        status = "BLOCKED"
    report = {
        "schemaVersion": "1.0.0",
        "status": status,
        "configVersion": resolved["configVersion"],
        "generatedAtUtc": utc_now(),
        "results": results,
        "readiness": "UP" if status == "PASS" else "DOWN",
        "fakeFallbackUsed": False,
        "sensitiveDataPolicy": {
            "secretValuePersisted": False,
            "fullPromptPersisted": False,
            "fullResponsePersisted": False,
        },
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if status == "PASS" else 2 if status == "BLOCKED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
