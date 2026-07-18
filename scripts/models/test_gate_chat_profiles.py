from __future__ import annotations

import copy
import json
import unittest
from pathlib import Path

from gate_chat_profiles import evaluate
from resolve_profiles import resolve


PROJECT_ROOT = Path(__file__).resolve().parents[2]
CONFIG = PROJECT_ROOT / "deployment" / "model-profiles.phase0.yaml"
ACCEPTANCE = PROJECT_ROOT / "outputs" / "phase0" / "01-WP04" / "01-WP04.T03-chat-acceptance-report.json"


class GateChatProfilesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.resolved = resolve(CONFIG)
        self.acceptance = json.loads(ACCEPTANCE.read_text(encoding="utf-8"))

    def test_passes_and_backfills_all_seven_profiles(self) -> None:
        report = evaluate(self.resolved, self.acceptance, True)
        self.assertEqual("PASS", report["status"])
        self.assertEqual(7, len(report["logicalProfiles"]))
        self.assertTrue(all(item["status"] == "PASS" for item in report["logicalProfiles"]))

    def test_fails_when_model_is_empty(self) -> None:
        resolved = copy.deepcopy(self.resolved)
        resolved["logicalProfiles"][0]["modelIdentity"]["modelId"] = ""
        self.assertGap(evaluate(resolved, self.acceptance, True), "MODEL_EMPTY")

    def test_fails_when_key_is_missing(self) -> None:
        self.assertGap(
            evaluate(self.resolved, self.acceptance, False), "SECRET_REF_UNRESOLVED"
        )

    def test_fails_when_context_window_is_unknown(self) -> None:
        resolved = copy.deepcopy(self.resolved)
        resolved["logicalProfiles"][0]["contextWindowTokens"] = 0
        self.assertGap(
            evaluate(resolved, self.acceptance, True), "CONTEXT_WINDOW_UNKNOWN"
        )

    def test_fails_when_required_capability_is_unsupported(self) -> None:
        acceptance = copy.deepcopy(self.acceptance)
        record = next(
            item for item in acceptance["requestRecords"]
            if item["requestType"] == "tool_calling"
        )
        record["status"] = "FAILED"
        self.assertGap(
            evaluate(self.resolved, acceptance, True),
            "CAPABILITY_UNSUPPORTED:toolCalling",
        )

    def test_fails_when_quota_is_insufficient(self) -> None:
        acceptance = copy.deepcopy(self.acceptance)
        acceptance["usage"]["quotaSufficient"] = False
        self.assertGap(evaluate(self.resolved, acceptance, True), "QUOTA_INSUFFICIENT")

    def test_fails_when_logical_profile_is_unverified(self) -> None:
        acceptance = copy.deepcopy(self.acceptance)
        acceptance["logicalProfiles"] = acceptance["logicalProfiles"][:-1]
        self.assertGap(evaluate(self.resolved, acceptance, True), "PROFILE_UNVERIFIED")

    def assertGap(self, report: dict, gap: str) -> None:
        self.assertEqual("FAILED", report["status"])
        self.assertTrue(
            any(gap in item["gaps"] for item in report["logicalProfiles"]),
            report,
        )


if __name__ == "__main__":
    unittest.main()
