"""Tiny synthetic fits only; never fits or writes into actual IF result folders."""

from datetime import date, timedelta
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

from compare_models import (compare, load, protect_output, score, score_rows,
                            train, validate, verified_scores)
from models import ComparisonModel, FrozenECOD, fingerprint
from common import AI_FEATURES, DATA_DIR, read_rows, sha256, write_json, write_rows
from pooled_isolation_forest import fit_model, score_file as if_score_file, train_models
from test_isolation_forest import row, points, target
from weather_ai_features import Observation, aggregate


def samples():
    result = [row(str(date(2019, 1, 1) + timedelta(days=i)), rain=i % 5 / 10,
                  wind=2 + i % 9, temperature=20 + i % 12, humidity=50 + i % 30)
              for i in range(32)]
    result += [row("2024-06-01", rain=2, wind=18, temperature=35),
               row("2026-06-01", rain=8, wind=25, temperature=40)]
    p = points("2024-06-02")
    stamp = next(k for k in p if str(k.date()) == "2024-06-02" and k.hour == 9 and k.minute == 0)
    p[stamp] = Observation(0, 0, None, 60)
    result.append(aggregate(p, target("2024-06-02")))  # AI1 only
    p = points("2024-06-03")
    stamp = next(k for k in p if k.hour == 8 and k.minute == 0)
    p[stamp] = Observation(None, 0, 25, 60)
    result.append(aggregate(p, target("2024-06-03")))  # AI2 only
    p = points("2024-06-04")
    for stamp in p:
        p[stamp] = Observation(None, None, None, None)
    result.append(aggregate(p, target("2024-06-04")))  # neither
    return result


class FrozenECODTests(unittest.TestCase):
    def test_known_tails_skew_and_ties(self):
        model = FrozenECOD().fit([[0], [1], [2]])
        np.testing.assert_array_equal(model.skew_sign_, [0])
        np.testing.assert_allclose(model.anomaly([[0], [1], [2]]),
                                   [np.log(3), -2 * np.log(2 / 3), np.log(3)])
        tied = FrozenECOD().fit([[0], [0], [2]])
        np.testing.assert_array_equal(tied.skew_sign_, [1])
        self.assertEqual(tied.anomaly([[0], [0]])[0], tied.anomaly([[0]])[0])
        self.assertAlmostEqual(tied.anomaly([[0]])[0], -np.log(2 / 3))

    def test_constant_features_and_outside_support_are_finite(self):
        model = FrozenECOD().fit(np.zeros((10, 3)))
        self.assertEqual(model.anomaly([[0, 0, 0]])[0], 0)
        self.assertAlmostEqual(model.anomaly([[1, 1, 1]])[0], 3 * np.log(10))
        self.assertAlmostEqual(model.anomaly([[-1, -1, -1]])[0], 3 * np.log(10))

    def test_queries_never_update_training_cdf_or_skew(self):
        model = FrozenECOD().fit(np.arange(30).reshape(10, 3))
        sorted_before = model.sorted_features_.copy()
        skew_before = model.skew_sign_.copy()
        query = np.array([[7., 8., 9.], [-30., 900., 3.]])
        together = model.anomaly(query)
        np.testing.assert_array_equal(together, np.concatenate([model.anomaly(query[:1]), model.anomaly(query[1:])]))
        np.testing.assert_array_equal(together[::-1], model.anomaly(query[::-1]))
        model.anomaly([[1e9] * 3] * 100)
        np.testing.assert_array_equal(together, model.anomaly(query))
        np.testing.assert_array_equal(sorted_before, model.sorted_features_)
        np.testing.assert_array_equal(skew_before, model.skew_sign_)

    def test_invalid_input(self):
        for values in ([], [[float("nan")]], [[1]]):
            with self.subTest(values=values), self.assertRaises(ValueError):
                FrozenECOD().fit(values)


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.folder = Path(self.tmp.name)
        self.features = self.folder / "features.csv"
        write_rows(self.features, samples())

    def tearDown(self):
        self.tmp.cleanup()

    def test_fit_uses_training_only_and_matches_if_matrix_fingerprint(self):
        rows = read_rows(self.features)
        for algorithm in ("lof", "ecod"):
            folder = self.folder / algorithm
            manifest = train(self.features, folder, algorithm)
            models, _ = load(folder)
            for name in AI_FEATURES:
                _, _, if_info = fit_model(rows, name, dict(n_estimators=4))
                self.assertEqual(manifest["models"][name]["training_matrix_sha256"], if_info["training_matrix_sha256"])
                self.assertEqual(manifest["models"][name]["train_rows"], 32)
                self.assertEqual(len(models[name].reference_), 32)
                self.assertNotIn(("001", "2024-06-01"), models[name].training_values_)
            if algorithm == "lof":
                expected_mean = np.mean([[float(r[k]) for k in AI_FEATURES["AI1"]] for r in rows[:32]], axis=0)
                np.testing.assert_allclose(models["AI1"].scaler_.mean_, expected_mean)

    def test_roundtrip_independent_masks_and_all_rows_preserved(self):
        for algorithm in ("lof", "ecod"):
            folder = self.folder / algorithm
            train(self.features, folder, algorithm)
            models, manifest = load(folder)
            if algorithm == "lof":
                # Training rows use native factors, never the novelty scorer.
                with patch.object(models["AI1"].estimator_, "score_samples", side_effect=AssertionError("training novelty")):
                    score_rows(read_rows(self.features)[:32], models, manifest)
            scored = score(self.features, folder)
            self.assertEqual(len(scored), 37)
            self.assertIsNotNone(scored[-3]["S_AI1"])
            self.assertIsNone(scored[-3]["S_AI2"])
            self.assertIsNone(scored[-2]["S_AI1"])
            self.assertIsNotNone(scored[-2]["S_AI2"])
            self.assertIsNone(scored[-1]["S_AI1"])
            self.assertIsNone(scored[-1]["S_AI2"])
            self.assertEqual(validate(self.features, folder)["status"], "passed")

    def test_future_extremes_do_not_change_fit_or_reference(self):
        for algorithm in ("lof", "ecod"):
            a = self.folder / (algorithm + "a")
            b = self.folder / (algorithm + "b")
            train(self.features, a, algorithm)
            changed = read_rows(self.features)
            changed[32]["work_max_temp_c"] = "90"
            other = self.folder / "changed.csv"
            write_rows(other, changed)
            train(other, b, algorithm)
            models_a, _ = load(a)
            models_b, _ = load(b)
            for name in AI_FEATURES:
                np.testing.assert_array_equal(models_a[name].reference_, models_b[name].reference_)

    def test_changed_training_input_rejected_and_ties_equal(self):
        model = ComparisonModel("lof", neighbors=2).fit([[0], [0], [1], [3]], [("s", str(i)) for i in range(4)])
        with self.assertRaises(ValueError):
            model.anomaly([[2]], [("s", "0")], [True])
        with self.assertRaises(ValueError):
            model.anomaly([[0]], [("s", "0")], [False])
        from pooled_isolation_forest import percentile
        np.testing.assert_array_equal(percentile([1, 1, 3], [1, 1, 0, 5]), [200 / 3, 200 / 3, 0, 100])

    def test_lof_query_batch_independence_and_zero_variance(self):
        values = np.column_stack((np.arange(24), np.zeros(24)))
        model = ComparisonModel("lof", neighbors=5).fit(values, [("s", str(i)) for i in range(24)])
        query = np.array([[3., 0.], [50., 0.], [12., 1.]])
        together = model.novelty_anomaly(query)
        np.testing.assert_allclose(together, np.concatenate([model.novelty_anomaly(query[i:i+1]) for i in range(3)]))
        self.assertTrue(np.isfinite(together).all())

    def test_missing_training_group_never_fits_validation_instead(self):
        rows = [r for r in samples() if r["period"] != "train"]
        path = self.folder / "no_train.csv"
        write_rows(path, rows)
        for algorithm in ("lof", "ecod"):
            with self.assertRaises(ValueError):
                train(path, self.folder / algorithm, algorithm)
            self.assertFalse((self.folder / algorithm / "model_metadata.json").exists())

    def test_output_protection(self):
        for folder in (DATA_DIR, DATA_DIR / "step04", DATA_DIR / "step04_official_daily" / "new",
                       DATA_DIR / "step03", self.features.parent):
            with self.subTest(folder=folder), self.assertRaises(ValueError):
                protect_output(folder, self.features)
        legacy = self.folder / "custom_if"
        legacy.mkdir()
        (legacy / "isolation_forest_r1.pkl").write_bytes(b"protected")
        with self.assertRaises(ValueError):
            protect_output(legacy)

    def test_changed_source_scores_models_are_rejected(self):
        folder = self.folder / "ecod"
        train(self.features, folder, "ecod")
        score(self.features, folder)
        scores_path = folder / "weather_ai_scores.csv"
        scores_path.write_bytes(scores_path.read_bytes() + b"\n")
        with self.assertRaisesRegex(ValueError, "checksum"):
            validate(self.features, folder)
        score(self.features, folder)
        changed = read_rows(self.features)
        changed[32]["work_max_temp_c"] = "80"
        write_rows(self.features, changed)
        with self.assertRaisesRegex(ValueError, "differ"):
            score(self.features, folder)
        model_path = folder / "ecod_ai1.pkl"
        model_path.write_bytes(model_path.read_bytes() + b"tampered")
        with self.assertRaisesRegex(ValueError, "checksum"):
            load(folder)

    def test_three_algorithm_comparison_on_identical_rows(self):
        if_dir = self.folder / "if"
        train_models(self.features, if_dir, dict(n_estimators=4))
        # Synthetic legacy fixture has no official-rain metadata. Upgrade only its
        # manifest to the versioned IF envelope; production data already has v2.
        manifest_path = if_dir / "model_metadata.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        from common import feature_contract
        manifest.update(schema_version=2, model_id="synthetic-if", feature_contract=feature_contract(read_rows(self.features)))
        write_json(manifest_path, manifest)
        if_score_file(self.features, if_dir, if_dir / "weather_ai_scores.csv")
        protected_hash = sha256(if_dir / "weather_ai_scores.csv")
        for algorithm in ("lof", "ecod"):
            folder = self.folder / algorithm
            train(self.features, folder, algorithm)
            score(self.features, folder)
        joined, pairs = compare(self.features, if_dir, self.folder / "lof", self.folder / "ecod", self.folder / "comparison")
        self.assertEqual(len(joined), 37)
        self.assertEqual(len(pairs), 18)
        self.assertEqual(sha256(if_dir / "weather_ai_scores.csv"), protected_hash)
        validation = [p for p in pairs if p["period"] == "validation" and p["model"] == "AI1"]
        self.assertTrue(all(p["compared_rows"] == 2 for p in validation))


if __name__ == "__main__":
    unittest.main()
