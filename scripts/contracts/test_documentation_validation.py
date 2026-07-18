#!/usr/bin/env python3
"""Regression tests proving broken OpenAPI refs and Markdown links fail."""

from __future__ import annotations

import copy
import tempfile
import unittest
from pathlib import Path

import yaml

from validate_documentation import validate_markdown_links, validate_openapi


class DocumentationValidationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.project_root = Path(__file__).resolve().parents[2]
        cls.openapi_path = (
            cls.project_root / "docs/design/contracts/openapi/opspilot-v1.yaml"
        )

    def test_broken_openapi_ref_fails(self) -> None:
        document = yaml.safe_load(self.openapi_path.read_text(encoding="utf-8"))
        broken = copy.deepcopy(document)
        broken["paths"]["/incidents"]["post"]["responses"]["201"] = {
            "$ref": "#/components/responses/DoesNotExist"
        }

        errors, _ = validate_openapi(broken)

        self.assertTrue(any("unresolved OpenAPI reference" in error for error in errors))

    def test_broken_markdown_link_fails(self) -> None:
        temp_root = self.project_root / ".tmp" / "contract-link-tests"
        temp_root.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temp_root) as directory:
            markdown = Path(directory) / "broken.md"
            markdown.write_text("[missing](does-not-exist.md)\n", encoding="utf-8")

            errors, _, _ = validate_markdown_links([Path(directory)])

            self.assertEqual(1, len(errors))
            self.assertIn("broken local link", errors[0])


if __name__ == "__main__":
    unittest.main()
