"""Boundary, missingness, train isolation and end-to-end regression tests."""

import argparse
import csv
import json
import pickle
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import isolation_forest_scores as s
import validate_saved_results as saved


def target(day="2023-07-01", station="001"):
    return {"station_code": station, "station_name": "test", "work_date": day,
            "work_start": "08:00", "work_end": "17:00", "scheduled_minutes": 540,
            "is_mountain": False, "period": s.period_for(day), "S_R": "", "S_W": "", "S_H": "",
            "component_scores_complete": "False", "rain_full_score": False,
            "wind_full_score": False, "heat_full_score": False}


def points(day="2023-07-01", rain=0., wind=0., temperature=25., humidity=60.):
    start = datetime.fromisoformat(day + "T08:00:00")
    return {start + i * s.STEP: (rain, wind, temperature, humidity) for i in range(-143, 55)}


def row(day="2023-07-01", **kwargs):
    return s.aggregate(points(day, **kwargs), target(day), 480, 1020)


def training_rows():
    return [row(f"2023-07-{i + 1:02}", rain=float(i % 4), wind=float(i), temperature=20. + i,
                humidity=40. + i) for i in range(20)]


def bundles(rows):
    return {m: s.fit_bundle(rows, m, {}, trees=10, min_samples=2) for m in s.FEATURES}


class AggregationTests(unittest.TestCase):
    def test_all_zero_weather_is_valid(self):
        r = row(temperature=0., humidity=0.)
        self.assertTrue(r["AI1_input_complete"])
        self.assertTrue(r["AI2_input_complete"])
        for key in s.FEATURES["AI1"]:
            self.assertEqual(r[key], 0)

    def test_dry_strong_wind_and_rain_without_wind(self):
        for kwargs in ({"wind": 25.}, {"rain": 5.}):
            self.assertTrue(row(**kwargs)["AI1_input_complete"])

    def test_work_and_prior_boundaries(self):
        p = points(rain=1.)
        p[datetime(2023, 7, 1, 8)] = (7., 99., 99., 90.)
        p[datetime(2023, 7, 1, 17, 10)] = (100., 100., 100., 100.)
        r = s.aggregate(p, target(), 480, 1020)
        self.assertEqual(r["work_rain_mm"], 54)
        self.assertEqual(r["prior_24h_rain_mm"], 150)
        self.assertEqual(r["work_max_mean_wind_mps"], 0)
        self.assertEqual(r["work_max_temperature_c"], 25)

    def test_missing_rain_does_not_remove_heat(self):
        p = points(); p[datetime(2023, 7, 1, 9)] = (None, 20., 25., 60.)
        r = s.aggregate(p, target(), 480, 1020)
        self.assertFalse(r["AI1_input_complete"])
        self.assertTrue(r["AI2_input_complete"])
        self.assertIsNone(r["work_rain_mm"])
        self.assertIsNone(r["work_rain_minutes"])

    def test_missing_prior_only(self):
        p = points(); del p[datetime(2023, 7, 1, 8)]
        r = s.aggregate(p, target(), 480, 1020)
        self.assertEqual(r["AI1_missing_features"], "prior_24h_rain_mm")
        self.assertEqual(r["prior_rain_interval_count"], 143)

    def test_partial_wind_max_is_not_valid(self):
        p = points(wind=30.); del p[datetime(2023, 7, 1, 9)]
        r = s.aggregate(p, target(), 480, 1020)
        self.assertIsNone(r["work_max_mean_wind_mps"])
        self.assertIsNone(r["work_strong_wind_minutes"])

    def test_partial_humidity_keeps_temperature_but_not_ai2(self):
        p = points(); p[datetime(2023, 7, 1, 9)] = (0., 0., 25., None)
        r = s.aggregate(p, target(), 480, 1020)
        self.assertTrue(r["AI1_input_complete"])
        self.assertFalse(r["AI2_input_complete"])
        self.assertEqual(r["work_max_temperature_c"], 25)
        self.assertIsNone(r["work_mean_humidity_pct"])
        self.assertIsNone(r["work_heat_minutes_ge31"])

    def test_wind_threshold_mountain(self):
        a = s.aggregate(points(wind=14.), target(), 480, 1020)
        t = target(); t["is_mountain"] = True
        b = s.aggregate(points(wind=14.), t, 480, 1020)
        c = s.aggregate(points(wind=17.), t, 480, 1020)
        self.assertEqual((a["work_strong_wind_minutes"], b["work_strong_wind_minutes"], c["work_strong_wind_minutes"]), (540, 0, 540))

    def test_heat_duration_matches_formula(self):
        r = row(temperature=40., humidity=70.)
        self.assertGreater(s.apparent_temperature(40., 70.), 31)
        self.assertEqual(r["work_heat_minutes_ge31"], 540)

    def test_midnight_end(self):
        p = {datetime(2023, 7, 1, 23, 0) + i * s.STEP: (1., 2., 25., 60.) for i in range(-143, 7)}
        r = s.aggregate(p, target(), 1380, 1440)
        self.assertEqual(r["expected_work_interval_count"], 6)
        self.assertEqual(r["work_rain_mm"], 6)

    def test_missing_markers_invalid_ranges(self):
        for text in ("", "X", "-", "NaN", "inf", "-1"):
            self.assertIsNone(s.number(text, minimum=0))
        self.assertIsNone(s.number("101", minimum=0, maximum=100))


class ModelTests(unittest.TestCase):
    def test_ecdf_ties_and_bounds(self):
        np.testing.assert_array_equal(s.percentile(np.array([1., 2., 2., 4.]), [-1., 1., 2., 5.]), [0., 25., 75., 100.])

    def test_future_rows_cannot_change_models_or_reference(self):
        rows = training_rows()
        future = row("2024-07-01", rain=999., wind=999., temperature=90., humidity=99.)
        extra = row("2026-07-01", rain=9999., wind=9999.)
        future["period"] = "train"  # fit still checks dates, not this untrusted label.
        for m in s.FEATURES:
            a = s.fit_bundle(rows, m, {}, trees=10, min_samples=2)
            b = s.fit_bundle(rows + [future, extra], m, {}, trees=10, min_samples=2)
            self.assertEqual(pickle.dumps(a), pickle.dumps(b))

    def test_input_scores_not_required_and_partial_rows_retained(self):
        rows = training_rows()
        rows[0]["prior_24h_rain_mm"] = None
        rows[0]["AI1_input_complete"] = False
        rows[0]["AI1_missing_features"] = "prior_24h_rain_mm"
        result = s.score_rows(rows, bundles(rows))
        self.assertEqual(len(result), 20)
        self.assertEqual(result[0]["calculation_status"], "AI2_only")
        self.assertIsNone(result[0]["S_AI1"])
        self.assertTrue(result[1]["AI1_training_used"])
        self.assertEqual(s.audit(result, bundles(rows))["rows"], 20)

    def test_unavailable_model_does_not_disable_other_model(self):
        rows = training_rows(); models = bundles(rows)
        models["AI1"] = s.fit_bundle([], "AI1", {}, min_samples=2)
        s.score_rows(rows, models)
        self.assertEqual(rows[0]["calculation_status"], "AI2_only")
        self.assertEqual(rows[0]["AI1_unavailable_reason"], "insufficient_training_rows")

    def test_constant_training_unavailable(self):
        rows = [row(), row("2023-07-02")]
        self.assertEqual(s.fit_bundle(rows, "AI1", {}, min_samples=2)["metadata"]["reason"], "no_training_variation")

    def test_model_reload_and_score_direction(self):
        rows = training_rows(); models = bundles(rows)
        s.score_rows(rows, models)
        scores = [r["S_AI1"] for r in rows]
        s.score_rows(rows, pickle.loads(pickle.dumps(models)))
        self.assertEqual(scores, [r["S_AI1"] for r in rows])
        x = np.array([[r[k] for k in s.FEATURES["AI1"]] for r in rows])
        np.testing.assert_array_equal([r["AI1_raw_anomaly"] for r in rows], -models["AI1"]["forest"].score_samples(x))

    def test_exclusion_denominator_and_overlapping_reasons(self):
        rows = training_rows()
        r = rows[0]
        for k in ("work_rain_mm", "work_rain_minutes"):
            r[k] = None
        r["AI1_input_complete"] = False
        r["AI1_missing_features"] = "work_rain_mm|work_rain_minutes"
        r["wind_full_score"] = True
        reports = s.diagnostics(s.score_rows(rows, bundles(rows)))
        summary = next(r for r in reports["model_diagnostics.csv"] if r["station_code"] == "ALL" and r["model"] == "AI1")
        self.assertEqual(summary["input_exclusion_pct"], 5.)
        self.assertEqual(summary["excluded_observed_wind_extreme_rows"], 1)
        self.assertEqual(summary["excluded_observed_wind_extreme_pct"], 100.)


class FileTests(unittest.TestCase):
    def test_duplicate_observations_and_conflicts(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp) / "raw.csv"
            header = "observed_at,rain_10min_mm,wind_mps,temperature_c,humidity_pct\n"
            line = "2023-07-01T08:10:00,0,0,25,60\n"
            p.write_text(header + line + line)
            self.assertEqual(len(s.load_points(p)), 1)
            p.write_text(header + line + "2023-07-01T08:10:00,1,0,25,60\n")
            with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
                s.load_points(p)

    def test_periods(self):
        self.assertEqual([s.period_for(d) for d in ("2015-12-31", "2023-12-31", "2024-01-01", "2025-12-31", "2026-01-01")],
                         ["before_train", "train", "validation", "validation", "after_validation"])

    def test_end_to_end_keeps_missing_rows_and_audits_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); raw = root / "raw.csv"; targets = root / "targets.csv"
            days = ("2023-07-01", "2023-07-03", "2024-07-01", "2026-07-01")
            target_rows = [target(d) for d in days]
            for i, t in enumerate(target_rows):
                t.update(work_rain_mm=54. * i, prior_24h_rain_mm=144. * i,
                         work_max_wind_mps=float(i), work_strong_wind_minutes=0,
                         work_heat_minutes_ge31=0)
            target_rows[2].update(work_rain_mm="", work_max_wind_mps=5.)
            s.write_csv(targets, target_rows)
            with raw.open("w", newline="") as stream:
                writer = csv.writer(stream)
                writer.writerow(["station_code", "station_name", "observed_at", "rain_10min_mm", "wind_mps", "temperature_c", "humidity_pct"])
                all_points = {}
                for i, day in enumerate(days):
                    all_points.update(points(day, rain=float(i), wind=float(i), temperature=25. + i))
                all_points[datetime(2024, 7, 1, 9)] = (None, 5., 26., 60.)
                for stamp, values in sorted(all_points.items()):
                    writer.writerow(["001", "test", stamp.isoformat(), *values])
            args = argparse.Namespace(weather=raw, targets=targets, output_dir=root / "output", seed=42, trees=10, min_training_rows=2)
            before = s.sha256(raw), s.sha256(targets)
            report = s.run(args)
            self.assertEqual(report["rows"], 4)
            self.assertEqual(report["models"]["AI1"]["training_rows"], 2)
            with (args.output_dir / "weather_ai_scores.csv").open(encoding="utf-8-sig", newline="") as stream:
                result = list(csv.DictReader(stream))
            self.assertEqual(result[2]["calculation_status"], "AI2_only")
            self.assertEqual(result[2]["S_AI1"], "")
            self.assertEqual(result[0]["station_code"], "001")
            self.assertEqual(before, (s.sha256(raw), s.sha256(targets)))
            for name, digest in report["output_files"].items():
                self.assertEqual(s.sha256(args.output_dir / name), digest)
            self.assertEqual(saved.validate(targets, args.output_dir)["mismatch_count"], 0)
            with (args.output_dir / "weather_ai_holdout.csv").open("a") as stream:
                stream.write("tampered")
            with self.assertRaisesRegex(ValueError, "artifact hash mismatch"):
                saved.validate(targets, args.output_dir)
            with targets.open("a", newline="") as stream:
                csv.writer(stream).writerow(target_rows[0].values())
            with self.assertRaisesRegex(ValueError, "duplicate target"):
                s.load_targets(targets)


if __name__ == "__main__":
    unittest.main()
