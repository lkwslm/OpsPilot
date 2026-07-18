from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from validate_chat_acceptance import validate


PROJECT_ROOT = Path(__file__).resolve().parents[2]
REAL_REPORT = PROJECT_ROOT / "outputs" / "phase0" / "01-WP04" / "01-WP04.T03-chat-acceptance-report.json"


class ValidateChatAcceptanceTest(unittest.TestCase):
    def test_real_acceptance_report_passes(self) -> None:
        self.assertEqual([], validate(REAL_REPORT, "known-value-not-present"))

    def test_rejects_secret_shaped_value_and_forbidden_payload(self) -> None:
        report = json.loads(REAL_REPORT.read_text(encoding="utf-8"))
        report["apiKey"] = "test-provider-key-value"
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT / ".tmp") as directory:
            path = Path(directory) / "leaking.json"
            path.write_text(json.dumps(report), encoding="utf-8")
            errors = validate(path, "test-provider-key-value")

        self.assertIn("secret-shaped value detected", errors)
        self.assertIn("resolved Secret value detected", errors)
        self.assertTrue(any("forbidden field" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
