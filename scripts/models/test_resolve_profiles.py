from __future__ import annotations

import json
import os
import tempfile
import unittest
from pathlib import Path

import yaml

from resolve_profiles import ROLE_IDS, resolve


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONFIG = PROJECT_ROOT / "deployment" / "model-profiles.phase0.yaml"


class ResolveProfilesTest(unittest.TestCase):
    def test_expands_default_and_all_six_roles(self) -> None:
        result = resolve(CONFIG)

        self.assertEqual(7, len(result["logicalProfiles"]))
        self.assertEqual(
            ["default", *ROLE_IDS],
            [profile["logicalProfileId"] for profile in result["logicalProfiles"]],
        )
        self.assertEqual(1, len(result["uniqueActualModels"]))
        for profile in result["logicalProfiles"]:
            self.assertEqual("deepseek-v4-flash", profile["modelIdentity"]["modelId"])
            self.assertEqual("env:DEEPSEEK_API_KEY", profile["secretRef"])
            self.assertEqual(1000000, profile["contextWindowTokens"])

    def test_nested_role_override_wins_without_erasing_defaults(self) -> None:
        profiles = {
            item["logicalProfileId"]: item for item in resolve(CONFIG)["logicalProfiles"]
        }

        self.assertEqual(4, profiles["default"]["quota"]["maxConcurrency"])
        self.assertEqual(1, profiles["supervisor"]["quota"]["maxConcurrency"])
        self.assertEqual(12000, profiles["supervisor"]["quota"]["maxInputTokensPerCall"])
        self.assertTrue(profiles["supervisor"]["requirements"]["streaming"])
        self.assertFalse(profiles["knowledge"]["requirements"]["toolCalling"])
        self.assertTrue(profiles["knowledge"]["requirements"]["structuredOutput"])

    def test_secret_value_is_never_read_or_emitted(self) -> None:
        marker = "phase0-secret-value-must-not-appear"
        previous = os.environ.get("DEEPSEEK_API_KEY")
        os.environ["DEEPSEEK_API_KEY"] = marker
        try:
            serialized = json.dumps(resolve(CONFIG), ensure_ascii=False)
        finally:
            if previous is None:
                os.environ.pop("DEEPSEEK_API_KEY", None)
            else:
                os.environ["DEEPSEEK_API_KEY"] = previous

        self.assertNotIn(marker, serialized)
        self.assertNotIn("apiKey", serialized)
        self.assertIn("env:DEEPSEEK_API_KEY", serialized)

    def test_rejects_embedded_secret_field(self) -> None:
        config = yaml.safe_load(CONFIG.read_text(encoding="utf-8"))
        config["defaults"]["apiKey"] = "forbidden"
        with tempfile.TemporaryDirectory(dir=PROJECT_ROOT / ".tmp") as directory:
            path = Path(directory) / "invalid.yaml"
            path.write_text(yaml.safe_dump(config), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "Secret value field is forbidden"):
                resolve(path)


if __name__ == "__main__":
    unittest.main()
