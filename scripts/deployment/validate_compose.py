#!/usr/bin/env python3
"""Validate the frozen Phase 0 Compose topology without exposing secret values."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path


EXPECTED = {
    "opspilot-server": ("supervisor", "8080", "opspilot_app_role", "supervise-incident"),
    "evidence-agent": ("evidence-collector", "8081", "evidence_agent_role", "collect-observability-evidence"),
    "code-agent": ("code-analysis", "8082", "code_agent_role", "analyze-code-location"),
    "knowledge-agent": ("knowledge", "8083", "knowledge_agent_role", "retrieve-incident-knowledge"),
    "diagnosis-agent": ("diagnosis", "8084", "diagnosis_agent_role", "generate-and-verify-hypotheses"),
    "remediation-agent": ("remediation", "8085", "remediation_agent_role", "propose-remediation"),
}

SERVICE_IDENTITIES = {
    "opspilot-server": "svc:opspilot-server",
    "evidence-agent": "svc:evidence-agent",
    "code-agent": "svc:code-agent",
    "knowledge-agent": "svc:knowledge-agent",
    "diagnosis-agent": "svc:diagnosis-agent",
    "remediation-agent": "svc:remediation-agent",
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--compose", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    compose = args.compose.resolve()
    completed = subprocess.run(
        ["docker", "compose", "-f", compose.name, "--profile", "phase0-gate", "config", "--format", "json"],
        cwd=compose.parent,
        check=False,
        capture_output=True,
        text=True,
    )
    errors: list[str] = []
    try:
        config = json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        config = {}
        errors.append(f"docker compose config failed: {completed.stderr.strip() or error}")

    services = config.get("services", {})
    identities: set[str] = set()
    roles: set[str] = set()
    service_token_refs: set[str] = set()
    for service, (agent_id, port, role, skill) in EXPECTED.items():
        definition = services.get(service)
        if not isinstance(definition, dict):
            errors.append(f"missing process service: {service}")
            continue
        environment = definition.get("environment", {})
        for key, expected in {
            "AGENT_ID": agent_id,
            "SERVER_PORT": port,
            "DB_ROLE": role,
            "AGENT_SKILL": skill,
            "A2A_PROTOCOL": "1.0",
            "CHAT_MODEL_ID": "deepseek-v4-flash",
            "SERVICE_IDENTITY": SERVICE_IDENTITIES[service],
        }.items():
            if str(environment.get(key)) != expected:
                errors.append(f"{service}: {key} must be {expected}")
        identities.add(str(environment.get("AGENT_ID")))
        roles.add(str(environment.get("DB_ROLE")))
        token_ref = str(environment.get("SERVICE_TOKEN_FILE", ""))
        if not token_ref.startswith("/run/secrets/"):
            errors.append(f"{service}: invalid SERVICE_TOKEN_FILE")
        service_token_refs.add(token_ref)
        a2a_url = str(environment.get("A2A_BASE_URL", ""))
        if not a2a_url.startswith(f"http://{service}:{port}"):
            errors.append(f"{service}: invalid internal A2A_BASE_URL")
        expected_target = "product-runtime" if service == "opspilot-server" else "agent-runtime"
        if definition.get("build", {}).get("target") != expected_target:
            errors.append(f"{service}: build target must be {expected_target}")
        if environment.get("DIRECTORY_PATH") != "/app/config/agent-directory.yaml":
            errors.append(f"{service}: immutable Directory path is required")
        dependency = definition.get("depends_on", {}).get("db-migrate", {})
        if dependency.get("condition") != "service_completed_successfully":
            errors.append(f"{service}: db-migrate must be a hard startup dependency")
        volumes = definition.get("volumes", [])
        input_mounts = [item for item in volumes if item.get("target") == "/datasets/input"]
        if len(input_mounts) != 1 or not input_mounts[0].get("read_only"):
            errors.append(f"{service}: /datasets/input must be mounted read-only")
        directory_mounts = [item for item in volumes
                            if item.get("target") == "/app/config/agent-directory.yaml"]
        if len(directory_mounts) != 1 or not directory_mounts[0].get("read_only"):
            errors.append(f"{service}: Agent Directory must be mounted read-only")
        serialized = json.dumps(definition).lower()
        for forbidden in ("ground-truth", "ground_truth", "docker.sock", "/execution"):
            if forbidden in serialized:
                errors.append(f"{service}: forbidden mount reference {forbidden}")

        ports = definition.get("ports", [])
        if service == "opspilot-server":
            if len(ports) != 1 or ports[0].get("host_ip") != "127.0.0.1" or ports[0].get("published") != "8080":
                errors.append("opspilot-server: exactly 127.0.0.1:8080 must be published")
        elif ports:
            errors.append(f"{service}: professional port must not be published")

    if len(identities) != 6:
        errors.append("AGENT_ID values are not unique")
    if len(roles) != 6:
        errors.append("DB_ROLE values are not unique")
    if len(service_token_refs) != 6:
        errors.append("the six service Token refs are not unique")

    directory = compose.parent / "agents" / "agent-directory.yaml"
    directory_bytes = directory.read_bytes() if directory.is_file() else b""
    directory_text = directory_bytes.decode("utf-8")
    for service, (agent_id, port, _role, skill) in EXPECTED.items():
        if service == "opspilot-server":
            continue
        for required in (agent_id, f"http://{service}:{port}/.well-known/agent-card.json", skill,
                         "expected_card_sha256", "service_identity"):
            if required not in directory_text:
                errors.append(f"Agent Directory missing {service} contract: {required}")

    retrieval = services.get("retrieval-model-probe", {})
    if "phase0-gate" not in retrieval.get("profiles", []):
        errors.append("retrieval-model-probe must be a phase0-gate profile")
    for service in EXPECTED:
        if "retrieval-model-probe" in services.get(service, {}).get("depends_on", {}):
            errors.append(f"{service}: retrieval probe must not be a startup dependency")

    report = {
        "status": "PASS" if not errors else "FAIL",
        "composeFile": str(compose),
        "processCount": len(EXPECTED),
        "agentIdsUnique": len(identities) == 6,
        "databaseRolesUnique": len(roles) == 6,
        "serviceTokenRefsUnique": len(service_token_refs) == 6,
        "directoryDigest": hashlib.sha256(directory_bytes).hexdigest(),
        "publishedPorts": {"opspilot-server": "127.0.0.1:8080"},
        "errorCount": len(errors),
        "errors": errors,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
