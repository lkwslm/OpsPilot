from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

from .contracts import ContractLoader
from .scenario import FROZEN_SCENARIOS, ScenarioLoader


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(prog="fault-lab", description="OpsPilot 可复现故障实验工具")
    subcommands = result.add_subparsers(dest="command", required=True)
    subcommands.add_parser("list", help="列出冻结场景")
    validate = subcommands.add_parser("validate", help="校验冻结场景和 Ground Truth")
    validate.add_argument("scenario", type=Path)
    run = subcommands.add_parser("run", help="在真实 Compose 环境运行冻结场景")
    run.add_argument("scenario", type=Path)
    run.add_argument("--repeat", type=int, default=1)
    run.add_argument("--dataset-root", type=Path, default=Path(os.environ.get("DATASET_ROOT", "/datasets")))
    run.add_argument("--agent-input-export", type=Path, default=Path(os.environ.get("AGENT_INPUT_EXPORT_DIR", "/exports/agent-input")))
    run.add_argument("--ground-truth-export", type=Path, default=Path(os.environ.get("GROUND_TRUTH_EXPORT_DIR", "/exports/ground-truth")))
    run.add_argument("--checkpoint-root", type=Path, default=Path(os.environ.get("CHECKPOINT_ROOT", "/datasets/.checkpoints")))
    run.add_argument("--compose-project", default=os.environ.get("COMPOSE_PROJECT", "opspilot-phase0"))
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    if args.command == "list":
        print(json.dumps([{"scenarioId": key[0], "scenarioVersion": key[1]} for key in sorted(FROZEN_SCENARIOS)], ensure_ascii=False))
        return 0
    scenario, ground_truth = ScenarioLoader(ContractLoader()).load(args.scenario)
    if args.command == "run":
        if args.repeat < 1:
            raise ValueError("repeat 必须大于 0")
        from .runtime import build_runner, expose_dataset
        results = []
        for attempt in range(1, args.repeat + 1):
            runner = build_runner(scenario, ground_truth, args.dataset_root, args.checkpoint_root, args.compose_project)
            context = runner.execute(scenario, args.dataset_root)
            result = {"attempt": attempt, "datasetRunId": context.dataset_run_id, "status": context.status.value,
                      "primaryFailure": context.primary_failure, "recoveryFailure": context.recovery_failure}
            results.append(result)
            if context.status.value != "COMPLETED":
                print(json.dumps({"scenarioId": scenario["scenarioId"], "runs": results}, ensure_ascii=False))
                return 1
            expose_dataset(args.dataset_root / context.dataset_run_id, args.agent_input_export, args.ground_truth_export)
        print(json.dumps({"scenarioId": scenario["scenarioId"], "runs": results}, ensure_ascii=False))
        return 0
    print(json.dumps({"valid": True, "scenarioId": scenario["scenarioId"], "rootCauseCode": ground_truth["rootCauseCode"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
