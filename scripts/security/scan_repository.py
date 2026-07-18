#!/usr/bin/env python3
"""Minimal fail-closed Phase 0 repository security gate."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


SECRET = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")
DEPLOY = re.compile(r"\b(kubectl\s+apply|helm\s+(?:install|upgrade)|docker\s+push)\b", re.I)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    findings: list[str] = []
    scanned = 0

    roots = [root / ".github", root / "deployment", root / "scripts", root / "opspilot-server", root / "opspilot-evaluation"]
    for base in roots:
        if not base.exists():
            continue
        for path in base.rglob("*"):
            if not path.is_file() or "target" in path.parts or path.suffix.lower() in {".jar", ".class"}:
                continue
            try:
                content = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            scanned += 1
            relative = path.relative_to(root).as_posix()
            if SECRET.search(content):
                findings.append(f"{relative}: literal provider key")
            if relative.startswith(".github/workflows/"):
                if "secrets." in content:
                    findings.append(f"{relative}: workflow references repository/environment secret")
                if DEPLOY.search(content):
                    findings.append(f"{relative}: workflow contains deployment command")

    compose = (root / "deployment/docker-compose.yml").read_text(encoding="utf-8").lower()
    for forbidden in ("docker.sock", "ground-truth", "ground_truth"):
        if forbidden in compose:
            findings.append(f"deployment/docker-compose.yml: forbidden mount token {forbidden}")

    report = {
        "schemaVersion": "1.0.0",
        "status": "PASS" if not findings else "FAIL",
        "scannedFileCount": scanned,
        "licensePolicy": "WARNING_ONLY_WHEN_UNKNOWN",
        "automaticDeployment": False,
        "findingCount": len(findings),
        "findings": findings,
    }
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if not findings else 1


if __name__ == "__main__":
    raise SystemExit(main())
