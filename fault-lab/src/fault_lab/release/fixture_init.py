from __future__ import annotations

import os
import shutil
from pathlib import Path


def main() -> int:
    root = Path(os.environ.get("PHASE8_FIXTURE_ROOT", "/fixtures")).resolve()
    if root == Path(root.anchor) or root.name != "fixtures":
        raise SystemExit("PHASE8_FIXTURE_ROOT_INVALID")
    seed = Path(os.environ.get("PHASE8_FIXTURE_SEED_ROOT", "/seed")).resolve()

    files = {
        seed / "observability.jsonl": root / "agent-input/current/observability.jsonl",
        seed / "topology.json": root / "topology/sample-compose-topology.json",
        seed / "application.properties": root / "source-fixtures/application.properties",
    }
    for source, target in files.items():
        if not source.is_file():
            raise SystemExit(f"PHASE8_FIXTURE_SEED_MISSING:{source.name}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)

    code_source = seed / "code-snapshot"
    code_target = root / "code-snapshot/sample-system"
    if not code_source.is_dir():
        raise SystemExit("PHASE8_FIXTURE_SEED_MISSING:code-snapshot")
    if code_target.exists():
        shutil.rmtree(code_target)
    shutil.copytree(code_source, code_target)

    process = root / "process/maven-sandbox/run"
    process.parent.mkdir(parents=True, exist_ok=True)
    if process.exists():
        process.chmod(0o750)
    process.write_text(
        "#!/bin/sh\nset -eu\nexec /opt/maven/bin/mvn \"$@\"\n",
        encoding="utf-8",
        newline="\n",
    )
    process.chmod(0o550)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
