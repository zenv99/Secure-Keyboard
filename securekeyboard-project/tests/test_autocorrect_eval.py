import random
import unittest

from eval.autocorrect_eval import Metrics, run, touch_noise

class IdentityCorrector:
    def correct(self, typed, context):
        return typed

class EvalTests(unittest.TestCase):
    def test_noise_is_reproducible(self):
        self.assertEqual(touch_noise("keyboard", random.Random(42)),
                         touch_noise("keyboard", random.Random(42)))
    def test_non_qwerty_characters_are_preserved(self):
        self.assertEqual(touch_noise("hello!", random.Random(3), sigma=0,
                                     p_swap=0, p_drop=0, p_insert=0), "hello!")
    def test_empty_denominators_are_safe(self):
        metrics = Metrics()
        self.assertEqual(metrics.fix_rate, 0.0)
        self.assertEqual(metrics.false_correction_rate, 0.0)
    def test_run_returns_metrics(self):
        result = run(["hello world"], IdentityCorrector(), seed=1, sigma=0)
        self.assertEqual(result.clean_tokens + result.noisy_tokens, 2)
        self.assertEqual(result.false_corrections, 0)

if __name__ == "__main__":
    unittest.main()
