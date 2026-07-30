from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

import yaml
from jsonschema import Draft202012Validator, FormatChecker


class ContractError(ValueError):
    """具有稳定错误码的合同拒绝。"""

    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


class ContractLoader:
    SCHEMAS = {
        "scenario": "scenario.schema.json",
        "ground-truth": "ground-truth.schema.json",
        "rca": "rca.schema.json",
        "evaluation-profile": "evaluation-profile.schema.json",
    }

    def __init__(self, repository_root: Path | None = None):
        configured_root = os.environ.get("OPSPILOT_REPOSITORY_ROOT")
        self.repository_root = (repository_root or (Path(configured_root) if configured_root else Path(__file__).resolve().parents[3])).resolve()
        self.schema_root = self.repository_root / "docs" / "design" / "contracts" / "schemas"
        self._validators: dict[str, Draft202012Validator] = {}

    def load(self, contract_type: str, path: Path) -> dict[str, Any]:
        resolved = path.resolve()
        try:
            text = resolved.read_text(encoding="utf-8")
            data = yaml.safe_load(text) if resolved.suffix.lower() in {".yaml", ".yml"} else json.loads(text)
        except (OSError, json.JSONDecodeError, yaml.YAMLError) as exc:
            raise ContractError("CONTRACT_DOCUMENT_INVALID", str(exc)) from exc
        return self.validate(contract_type, data)

    def validate(self, contract_type: str, data: Any) -> dict[str, Any]:
        if contract_type not in self.SCHEMAS:
            raise ContractError("CONTRACT_TYPE_UNKNOWN", contract_type)
        validator = self._validators.get(contract_type)
        if validator is None:
            schema_path = self.schema_root / self.SCHEMAS[contract_type]
            schema = json.loads(schema_path.read_text(encoding="utf-8"))
            validator = Draft202012Validator(schema, format_checker=FormatChecker())
            self._validators[contract_type] = validator
        errors = sorted(validator.iter_errors(data), key=lambda error: list(error.absolute_path))
        if errors:
            error = errors[0]
            location = ".".join(str(part) for part in error.absolute_path) or "$"
            raise ContractError("CONTRACT_SCHEMA_INVALID", f"{location}: {error.message}")
        if not isinstance(data, dict):
            raise ContractError("CONTRACT_SCHEMA_INVALID", "document must be an object")
        return data
