"""Regression checks for override precedence, boundaries, missing data and calibration."""

import csv
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import date, datetime
from pathlib import Path

import weather_component_scores as scores


DAY = date(2024, 7, 1)
START = datetime(2024, 7, 1, 8)
MODEL = {
    "schema_version": 1, "station_code": "test", "work_start": "08:00", "work_end": "16:00",
    "train_start": "2016-01-01", "train_end": "2023-12-31",
    "work_rain_mm": {"p50": 10, "p90": 50, "p99": 100},
    "prior_24h_rain_mm": {"p50": 20, "p90": 80, "p99": 150},
}


def observations(*, rain=0.0, wind=0.0, heat=20.0, gust=None):
    # Full preceding 24h (ending at work start), followed by a complete 8h shift.
    return {
        START + i * scores.STEP: scores.Observation(rain, wind, gust, heat)
        for i in range(-143, 49)
    }


def summarize(points, mountain=False):
    return scores.collect_features(points, DAY, "test", 480, 960, mountain)


def score(points, mountain=False):
    return scores.score_features(summarize(points, mountain), MODEL)


class RiskScoresTests(unittest.TestCase):
    def test_supplied_heat_example(self):
        points = observations(heat=30)
        for i in range(1, 37):
            points[START + i * scores.STEP] = scores.Observation(0, 0, None, 34)
        result = score(points)
        self.assertAlmostEqual(result["heat_intensity_subscore"], .625)
        self.assertEqual(result["work_heat_minutes_ge31"], 360)
        self.assertEqual(result["heat_exposure_subscore"], 1)
        self.assertAlmostEqual(result["S_H"], 77.5)

    def test_heat_boundary_and_exposure(self):
        self.assertEqual(scores.heat_intensity(30.99999), 0)
        for temperature, expected in [(31, .25), (33, .5), (35, .75), (38, 1), (50, 1)]:
            self.assertEqual(scores.heat_intensity(temperature), expected)
        result = score(observations(heat=31))
        self.assertAlmostEqual(result["S_H"], 55)
        self.assertEqual(score(observations(heat=30))["S_H"], 0)

    def test_heat_override_with_one_valid_interval_and_missing_rest(self):
        points = {START + scores.STEP: scores.Observation(None, None, None, 38)}
        result = score(points)
        self.assertEqual(result["S_H"], 100)
        self.assertEqual(result["heat_score_status"], "override")
        self.assertIsNone(result["heat_exposure_subscore"])

    def test_missing_heat_prevents_weighted_score(self):
        points = observations(heat=34)
        points[START + scores.STEP] = scores.Observation(0, 0, None, None)
        self.assertIsNone(score(points)["S_H"])

    def test_mean_and_gust_overrides_in_both_terrains(self):
        for mountain, mean_limit, gust_limit in [(False, 21, 26), (True, 24, 30)]:
            for mean, gust in [(mean_limit, None), (None, gust_limit)]:
                with self.subTest(mountain=mountain, mean=mean, gust=gust):
                    result = score({START + scores.STEP: scores.Observation(None, mean, gust, None)}, mountain)
                    self.assertEqual(result["S_W"], 100)
                    self.assertEqual(result["wind_score_status"], "override")

    def test_wind_weighting_and_no_gust_fallback(self):
        result = score(observations(wind=14))
        self.assertAlmostEqual(result["wind_intensity_subscore"], .7)
        self.assertEqual(result["wind_duration_subscore"], 1)
        self.assertAlmostEqual(result["S_W"], 77.5)
        self.assertEqual(result["gust_evaluation_status"], "unavailable")
        self.assertAlmostEqual(score(observations(wind=17), True)["S_W"], 77.5)

    def test_gust_higher_intensity_and_partial_availability(self):
        points = observations(wind=0)
        points[START + scores.STEP] = scores.Observation(0, 0, 20, 20)
        result = score(points)
        self.assertAlmostEqual(result["S_W"], 52.5)
        self.assertEqual(result["gust_evaluation_status"], "partial")
        self.assertEqual(score(observations(gust=20))["gust_evaluation_status"], "complete")

    def test_wind_duration_uses_planned_time_and_missing_is_not_calm(self):
        points = observations()
        for i in range(1, 25):
            points[START + i * scores.STEP] = scores.Observation(0, 14, None, 20)
        self.assertEqual(score(points)["wind_duration_subscore"], .5)
        del points[START + 25 * scores.STEP]
        self.assertIsNone(score(points)["S_W"])

    def test_one_hour_rain_override_without_prior_or_full_workday(self):
        points = {START + i * scores.STEP: scores.Observation(12, None, None, None) for i in range(1, 7)}
        result = score(points)
        self.assertEqual(result["work_max_1h_rain_mm"], 72)
        self.assertIsNone(result["work_max_3h_rain_mm"])
        self.assertEqual(result["S_R"], 100)
        self.assertEqual(result["rain_score_status"], "override")

    def test_three_hour_rain_override(self):
        points = {START + i * scores.STEP: scores.Observation(5, 0, None, 20) for i in range(1, 19)}
        result = score(points)
        self.assertEqual(result["work_max_1h_rain_mm"], 30)
        self.assertEqual(result["work_max_3h_rain_mm"], 90)
        self.assertEqual(result["S_R"], 100)

    def test_rain_window_does_not_cross_shift_boundary(self):
        points = observations()
        # One heavy interval ends exactly at 08:00, so it belongs to prior rain.
        for i in range(6):
            points[START + i * scores.STEP] = scores.Observation(12, 0, None, 20)
        result = score(points)
        self.assertEqual(result["prior_24h_rain_mm"], 12)
        self.assertEqual(result["work_rain_mm"], 60)
        self.assertEqual(result["work_max_1h_rain_mm"], 60)
        self.assertFalse(result["rain_full_score"])

    def test_rain_windows_never_bridge_missing_intervals(self):
        values = [12, 12, 12, None, 12, 12, 12]
        self.assertEqual(scores.rolling_max(values, 6), (None, 0))
        self.assertEqual(scores.rolling_max([12] * 6, 6), (72, 1))
        result = score({START + (i + 1) * scores.STEP: scores.Observation(r, 0, None, 20)
                        for i, r in enumerate(values)})
        self.assertIsNone(result["S_R"])

    def test_no_zero_imputation_of_missing_prior(self):
        points = observations()
        del points[START - 143 * scores.STEP]
        self.assertIsNone(score(points)["S_R"])
        self.assertEqual(score(points)["S_H"], 0)

    def test_rain_quantile_anchors_and_ties(self):
        bounds = MODEL["work_rain_mm"]
        for rainfall, expected in [(0, 0), (5, .125), (10, .25), (30, .5), (50, .75), (100, 1), (999, 1)]:
            self.assertAlmostEqual(scores.rain_score(rainfall, bounds), expected)
        tied = {"p50": 2, "p90": 2, "p99": 2}
        self.assertEqual(scores.rain_score(0, tied), 0)
        self.assertEqual(scores.rain_score(2, tied), 1)
        self.assertEqual(scores.quantiles([0, 1, 2, 3])["p50"], 2)
        with self.assertRaises(ValueError):
            scores.quantiles([0, 0])

    def test_training_excludes_validation_extremes_and_missing_totals(self):
        base = summarize(observations())
        rows = [dict(base, work_date="2016-01-01", work_rain_mm=10, prior_24h_rain_mm=20),
                dict(base, work_date="2023-12-31", work_rain_mm=30, prior_24h_rain_mm=None),
                dict(base, work_date="2024-01-01", work_rain_mm=1e9, prior_24h_rain_mm=1e9)]
        model = scores.fit_calibration(rows, date(2016, 1, 1), date(2023, 12, 31))
        self.assertEqual(model["work_rain_mm"]["p50"], 20)
        self.assertEqual(model["prior_24h_rain_mm"]["p99"], 20)
        self.assertEqual(model["work_rain_mm"]["positive_sample_count"], 2)
        self.assertEqual(scores.score_features(base, model)["period"], "validation")

    def test_calibration_period_and_metadata_validation(self):
        scores.validate_calibration(MODEL, summarize(observations()), date(2016, 1, 1), date(2023, 12, 31))
        with self.assertRaises(ValueError):
            scores.validate_training_dates(date(2016, 1, 1), date(2024, 12, 31))
        with self.assertRaises(ValueError):
            scores.validate_calibration(MODEL, dict(summarize(observations()), station_code="other"), date(2016, 1, 1), date(2023, 12, 31))
        with self.assertRaises(ValueError):
            scores.fit_calibration([summarize(observations())], date(2016, 1, 1), date(2023, 12, 31))

    def test_apparent_temperature_formula_reference(self):
        # Fixed regression reference protects the published +3.0 adjustment.
        self.assertAlmostEqual(scores.apparent_temperature(30, 70), 31.2894157838, places=6)
        self.assertIsNone(scores.apparent_temperature(30, None))
        self.assertIsNone(scores.apparent_temperature(30, 101))
        self.assertIsNone(scores.apparent_temperature(float("nan"), 70))

    def test_loader_pairs_measurements_and_rejects_conflicting_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.csv"
            header = [*scores.REQUIRED_COLUMNS, "gust_mps"]
            rows = [
                ["test", "2024-07-01 08:10:00", "35", "", "-1", "0", "26"],
                ["test", "2024-07-01 08:20:00", "", "70", "0", "-1", ""],
            ]
            with path.open("w", newline="", encoding="utf-8-sig") as stream:
                writer = csv.writer(stream)
                writer.writerow(header)
                writer.writerows([*rows, rows[0]])
            points = scores.load_observations(path, "test")
            self.assertEqual(len(points), 2)
            result = score(points)
            self.assertIsNone(result["S_H"])
            self.assertEqual(result["S_W"], 100)
            with path.open("a", newline="", encoding="utf-8") as stream:
                csv.writer(stream).writerow([*rows[0][:-1], "25"])
            with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
                scores.load_observations(path, "test")

    def test_all_missing_and_short_shift(self):
        result = score({})
        for key in ("S_R", "S_W", "S_H"):
            self.assertIsNone(result[key])
        short = scores.collect_features(observations(rain=20), DAY, "test", 480, 530)
        self.assertIsNone(short["work_max_1h_rain_mm"])
        with self.assertRaises(ValueError):
            scores.collect_features({}, DAY, "test", 481, 960)

    def test_cli_reuses_frozen_calibration_for_validation_only_input(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output, model_path = root / "weather.csv", root / "scores.csv", root / "rain.json"
            model_path.write_text(json.dumps(MODEL), encoding="utf-8")
            frozen = model_path.read_bytes()
            with source.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.writer(stream)
                writer.writerow(scores.REQUIRED_COLUMNS)
                for stamp in observations():
                    writer.writerow(["test", stamp.isoformat(), 30, 70, 0, 0])
            command = [sys.executable, str(Path(scores.__file__)), "--input", str(source),
                       "--output", str(output), "--calibration", str(model_path),
                       "--station-code", "test", "--work-end", "16:00"]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Reusing calibration", result.stdout)
            self.assertEqual(model_path.read_bytes(), frozen)
            with output.open(encoding="utf-8-sig", newline="") as stream:
                rows = list(csv.DictReader(stream))
            row = next(row for row in rows if row["work_date"] == DAY.isoformat())
            self.assertEqual(row["period"], "validation")
            self.assertEqual(float(row["S_R"]), 0)
            self.assertEqual(float(row["S_W"]), 0)
            self.assertEqual(row["gust_evaluation_status"], "unavailable")

    def test_all_stations_have_independent_calibrations_and_explicit_missing_history(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "input.csv", root / "scores.csv"
            with source.open("w", newline="", encoding="utf-8") as stream:
                writer = csv.writer(stream)
                writer.writerow(scores.REQUIRED_COLUMNS)
                # Interleave station rows: batch processing must not assume sorted input.
                for day in (date(2016, 1, 1), date(2023, 12, 31), DAY, date(2026, 1, 1)):
                    start = datetime.combine(day, datetime.min.time()).replace(hour=8)
                    for i in range(-143, 49):
                        stamp = start + i * scores.STEP
                        writer.writerow(["001", stamp.isoformat(), 25, 60, 2, .5])
                        writer.writerow(["003", stamp.isoformat(), 25, 60, 2, 2])
                        if day >= DAY:
                            writer.writerow(["002", stamp.isoformat(), 25, 60, 2, .5])
            command = [sys.executable, str(Path(scores.__file__)), "--input", str(source),
                       "--output", str(output), "--work-end", "16:00"]
            result = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            with output.open(encoding="utf-8-sig", newline="") as stream:
                rows = list(csv.DictReader(stream))
            self.assertEqual({r["station_code"] for r in rows}, {"001", "002", "003"})
            self.assertEqual(len(rows), len({(r["station_code"], r["work_date"]) for r in rows}))
            a = next(r for r in rows if r["station_code"] == "001" and r["work_date"] == DAY.isoformat())
            b = next(r for r in rows if r["station_code"] == "002" and r["work_date"] == DAY.isoformat())
            self.assertEqual(a["rain_calibration_status"], "fitted")
            self.assertEqual(b["rain_calibration_reason"], "insufficient_training_span")
            self.assertEqual(b["rain_score_status"], "missing_calibration")
            self.assertEqual(b["S_R"], "")
            self.assertNotEqual(b["S_W"], "")
            self.assertNotEqual(b["S_H"], "")
            self.assertEqual(next(r for r in rows if r["work_date"] == "2026-01-01")["period"], "after_validation")
            report = json.loads((root / "weather_component_validation.json").read_text(encoding="utf-8-sig"))
            self.assertEqual(report["station_count"], 3)
            self.assertEqual(report["mismatch_count"], 0)
            self.assertEqual(report["calibration_unavailable_stations"], ["002"])
            self.assertTrue((root / "rain_calibrations" / "rain_calibration_001.json").is_file())
            m1 = json.loads((root / "rain_calibrations" / "rain_calibration_001.json").read_text(encoding="utf-8-sig"))
            m3 = json.loads((root / "rain_calibrations" / "rain_calibration_003.json").read_text(encoding="utf-8-sig"))
            self.assertEqual(m3["work_rain_mm"]["p50"], 4 * m1["work_rain_mm"]["p50"])
            self.assertFalse((root / "rain_calibrations" / "rain_calibration_002.json").exists())
            self.assertTrue((root / "weather_rain_quantile_validation.csv").is_file())

    def test_independent_validation_detects_wrong_scores(self):
        from validate_weather_scores import audit_validation
        points = observations(heat=34)
        row = score(points)
        self.assertEqual(audit_validation(points, [row], MODEL)["mismatch_count"], 0)
        row["S_H"] = 0
        self.assertEqual(audit_validation(points, [row], MODEL)["mismatch_count"], 1)

    def test_unavailable_calibration_preserves_overrides_and_zero_rain(self):
        model = {"train_start": "2016-01-01", "train_end": "2023-12-31"}
        self.assertEqual(scores.score_features(summarize(observations()), model)["S_R"], 0)
        points = observations()
        for i in range(1, 7):
            points[START + i * scores.STEP] = scores.Observation(12, 0, None, 20)
        self.assertEqual(scores.score_features(summarize(points), model)["S_R"], 100)

    def test_unknown_station_fails_without_replacing_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output = root / "input.csv", root / "scores.csv"
            source.write_text(",".join(scores.REQUIRED_COLUMNS) + "\ntest,2024-07-01 08:10:00,25,60,0,0\n", encoding="utf-8")
            output.write_text("original", encoding="utf-8")
            result = subprocess.run([sys.executable, str(Path(scores.__file__)), "--input", str(source),
                                     "--output", str(output), "--station-code", "unknown"], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_text(encoding="utf-8"), "original")

    def test_held_out_distribution_diagnostics_do_not_refit_training(self):
        from validate_weather_scores import quantile_diagnostics
        rows = [dict(summarize(observations()), period="validation", work_rain_mm=v,
                     prior_24h_rain_mm=v) for v in (0, 10, 30, 60, 120)]
        rows.append(dict(rows[0], period="train", work_rain_mm=9999))
        frozen = json.dumps(MODEL, sort_keys=True)
        result = quantile_diagnostics(rows, MODEL)[0]
        self.assertEqual(result["validation_valid_days"], 5)
        self.assertEqual(result["validation_positive_days"], 4)
        self.assertEqual(result["training_p90_mm"], 50)
        self.assertEqual(result["validation_fraction_above_training_p90"], .5)
        self.assertEqual(result["validation_fraction_equal_training_p50"], .25)
        self.assertEqual(json.dumps(MODEL, sort_keys=True), frozen)


if __name__ == "__main__":
    unittest.main()
