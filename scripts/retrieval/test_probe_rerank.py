import unittest

from probe_rerank import validate_response


class RerankResponseValidationTest(unittest.TestCase):
    def test_wrong_identity_fails(self):
        response = {
            "model": "wrong-model",
            "results": [
                {"index": 0, "relevance_score": 0.9},
                {"index": 1, "relevance_score": 0.1},
            ],
        }
        self.assertIn(
            "RERANK_MODEL_IDENTITY_MISMATCH",
            validate_response(response, "expected-model", 2, {0}),
        )

    def test_positive_below_negative_fails(self):
        response = {
            "model": "expected-model",
            "results": [
                {"index": 1, "relevance_score": 0.9},
                {"index": 0, "relevance_score": 0.1},
            ],
        }
        self.assertIn(
            "RERANK_POSITIVE_NOT_ABOVE_NEGATIVE",
            validate_response(response, "expected-model", 2, {0}),
        )


if __name__ == "__main__":
    unittest.main()
