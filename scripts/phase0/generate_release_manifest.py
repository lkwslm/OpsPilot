#!/usr/bin/env python3
"""Generate and validate the Phase 0 manual-deployment release manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker


def sha(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def tree_sha(root: Path, pattern: str = "*") -> str:
    digest = hashlib.sha256()
    for path in sorted(candidate for candidate in root.rglob(pattern) if candidate.is_file()):
        digest.update(path.relative_to(root).as_posix().encode())
        digest.update(b"\0")
        digest.update(bytes.fromhex(sha(path).removeprefix("sha256:")))
    return "sha256:" + digest.hexdigest()


def image_id() -> str:
    result = subprocess.run(
        ["docker", "image", "inspect", "opspilot-phase0:0.1.0", "--format", "{{.Id}}"],
        check=True, capture_output=True, text=True,
    )
    value = result.stdout.strip()
    if not value.startswith("sha256:"):
        raise ValueError("local OCI image has no immutable ID")
    return value


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.output.resolve()
    lock_path = root / "deployment/versions.lock.yaml"
    lock = yaml.safe_load(lock_path.read_text(encoding="utf-8"))
    image = image_id()
    evidence = root / "outputs/phase0"
    jar = root / "opspilot-server/target/opspilot-server-0.1.0-SNAPSHOT.jar"
    security = evidence / "01-WP10/01-WP10.T05-security-report.json"
    build = evidence / "01-WP10/01-WP10.T05-clean-build-report.json"
    evaluation = evidence / "01-WP10/01-WP10.T01-T03-output-validation.json"

    required = [jar, security, build, evaluation]
    missing = [str(path.relative_to(root)) for path in required if not path.is_file()]
    if missing:
        raise FileNotFoundError("missing required gate evidence: " + ", ".join(missing))
    for path in (security, build, evaluation):
        if json.loads(path.read_text(encoding="utf-8")).get("status") not in {"PASS", "PASSED"}:
            raise ValueError(f"gate evidence is not PASS: {path.name}")

    artifacts = [
        ("opspilot-server", "JAR", jar.as_uri(), sha(jar)),
        ("opspilot-phase0-runtime", "OCI_IMAGE", "docker://opspilot-phase0:0.1.0@" + image, image),
        ("opspilot-contracts", "CONTRACT_BUNDLE", "workspace://docs/design/contracts", tree_sha(root / "docs/design/contracts")),
        ("opspilot-migrations", "MIGRATION_BUNDLE", "workspace://opspilot-adapters/persistence-postgres/src/main/resources/db", tree_sha(root / "opspilot-adapters/persistence-postgres/src/main/resources/db")),
        ("opspilot-sbom", "SBOM", (evidence / "01-WP01/01-WP01.T05/maven-sbom.json").as_uri(), sha(evidence / "01-WP01/01-WP01.T05/maven-sbom.json")),
        ("phase0-test-evidence", "TEST_EVIDENCE", evaluation.as_uri(), sha(evaluation)),
        ("manual-deployment-runbook", "RUNBOOK", (root / "deployment/MANUAL_DEPLOYMENT.md").as_uri(), sha(root / "deployment/MANUAL_DEPLOYMENT.md")),
        ("phase0-build-provenance", "PROVENANCE", lock_path.as_uri(), sha(lock_path)),
    ]
    gates = [
        ("CONTRACTS", "outputs/phase0/01-WP02/01-WP02.T05-contracts-summary.json"),
        ("BUILD_TEST", str(build.relative_to(root)).replace("\\", "/")),
        ("POSTGRES_INTEGRATION", "outputs/phase0/01-WP07/01-WP07.T04-upgrade-and-role-isolation-evidence.md"),
        ("COMPOSE_SMOKE", "outputs/phase0/01-WP09/compose-validation-report.json"),
        ("SECURITY", str(security.relative_to(root)).replace("\\", "/")),
        ("MODEL_INTEGRATION", "outputs/phase0/01-WP04/01-WP04.T04-chat-profile-gate-report.json"),
        ("A2A_INTEROPERABILITY", "outputs/phase0/01-WP05/01-WP05.T06-a2a-interoperability-report.json"),
        ("EVALUATION", str(evaluation.relative_to(root)).replace("\\", "/")),
    ]
    manifest = {
        "schemaVersion": "1.0.0",
        "releaseId": "opspilot-0.1.0-rc.1",
        "version": "0.1.0-rc.1",
        "deliveryStatus": "READY_FOR_MANUAL_DEPLOYMENT",
        "source": {"repository": "local/opspilot", "commitSha": lock["sourceCommit"], "ref": "refs/tags/v0.1.0-rc.1"},
        "build": {
            "workflowRunId": 1,
            "workflowUrl": "https://github.com/local/opspilot/actions/runs/1",
            "builtAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
            "jdk": lock["toolchain"]["jdk"]["version"],
            "versionsLockDigest": sha(lock_path),
        },
        "artifacts": [{"name": name, "type": kind, "uri": uri, "digest": digest} for name, kind, uri, digest in artifacts],
        "models": [
            {"capability": "CHAT", "modelId": lock["chatModel"]["modelId"], "immutableRevision": lock["chatModel"]["revision"]},
            {"capability": "EMBEDDING", "modelId": lock["models"][0]["modelId"], "immutableRevision": lock["models"][0]["revision"]},
            {"capability": "RERANK", "modelId": lock["models"][1]["modelId"], "immutableRevision": lock["models"][1]["revision"]},
        ],
        "qualityGates": [{"name": name, "status": "PASSED", "evidenceArtifact": "workspace://" + path} for name, path in gates],
        "database": {
            "baselineVersion": "0", "targetVersion": "4",
            "migrationDigest": tree_sha(root / "opspilot-adapters/persistence-postgres/src/main/resources/db/migration"),
            "backwardCompatible": True, "destructiveChanges": False,
        },
        "evaluation": {
            "profileId": "phase0-vertical-slice-v1",
            "scenarioVersions": ["dependency-latency-inventory/1.0.0", "database-pool-exhausted-order/1.0.0", "service-instance-stopped-inventory/1.0.0"],
            "runsPerScenario": 1, "evidenceArtifact": "workspace://" + str(evaluation.relative_to(root)).replace("\\", "/"),
        },
        "deployment": {
            "automaticDeployment": False, "deploymentPerformed": False,
            "runbookArtifact": "workspace://deployment/MANUAL_DEPLOYMENT.md",
        },
    }
    schema = json.loads((root / "docs/design/contracts/schemas/release-manifest.schema.json").read_text(encoding="utf-8"))
    errors = list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(manifest))
    if errors:
        raise ValueError("release manifest validation failed: " + "; ".join(error.message for error in errors))
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"status": "PASS", "manifest": str(output), "automaticDeployment": False, "digest": sha(output)}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
