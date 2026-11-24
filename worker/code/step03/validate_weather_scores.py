"""Independent checks on actual held-out weather observations.

Reconstructs shift aggregates and final scores without calling production aggregation,
interpolation or score functions. This audits implementation, not real-world damage.
"""

import math
from datetime import datetime, timedelta


def quantile_diagnostics(rows, model):
    """Describe held-out rain distributions. Never refit or mutate training cutoffs.

    Fractions above a training P50/P90/P99 are descriptive, not accuracy scores.
    Rounded rainfall creates ties, so nominal exceedance rates are not exact targets.
    """
    results = []
    for variable in ("work_rain_mm", "prior_24h_rain_mm"):
        valid = [r[variable] for r in rows if r["period"] == "validation" and r[variable] is not None]
        positive = sorted(v for v in valid if v > 0)
        bounds = model.get(variable)
        row = {"station_code": rows[0]["station_code"], "station_name": rows[0].get("station_name", ""),
               "variable": variable, "train_start": model["train_start"], "train_end": model["train_end"],
               "validation_valid_days": len(valid), "validation_positive_days": len(positive),
               "training_positive_days": bounds.get("positive_sample_count") if bounds else None,
               "comparison_status": "ok" if bounds and positive else "missing_calibration" if not bounds else "no_positive_validation_rain"}
        for key, probability in (("p50", .5), ("p90", .9), ("p99", .99)):
            cutoff = bounds[key] if bounds else None
            index = (len(positive) - 1) * probability
            lower = int(index)
            held_out = (positive[lower] + (index - lower) * (positive[min(lower + 1, len(positive) - 1)] - positive[lower])) if positive else None
            row["training_" + key + "_mm"] = cutoff
            row["validation_" + key + "_mm"] = held_out
            row["validation_fraction_above_training_" + key] = sum(v > cutoff for v in positive) / len(positive) if positive and cutoff is not None else None
            row["validation_fraction_equal_training_" + key] = sum(v == cutoff for v in positive) / len(positive) if positive and cutoff is not None else None
        results.append(row)
    return results


def reference_curve(value, anchors):
    points = sorted(dict(anchors).items())
    if value <= points[0][0]:
        return points[0][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if value <= x1:
            return y0 + (value - x0) / (x1 - x0) * (y1 - y0)
    return points[-1][1]


def audit_validation(points, rows, model):
    checked, comparisons, mismatches = 0, 0, []
    def compare(day, key, actual, expected):
        nonlocal comparisons
        comparisons += 1
        equal = actual is None and expected is None
        if actual is not None and expected is not None:
            equal = math.isclose(actual, expected, abs_tol=1e-8, rel_tol=1e-10)
        if not equal:
            mismatches.append(f"{day}:{key}:{actual}!={expected}")

    for row in rows:
        if row["period"] != "validation":
            continue
        checked += 1
        start = datetime.fromisoformat(row["work_date"] + "T" + row["work_start"])
        count = row["scheduled_minutes"] // 10
        shift = [points.get(start + timedelta(minutes=10 * i)) for i in range(1, count + 1)]
        rain = [p.rain if p else None for p in shift]
        wind = [p.wind for p in shift if p and p.wind is not None]
        gust = [p.gust for p in shift if p and p.gust is not None]
        heat = [p.apparent_temperature for p in shift if p and p.apparent_temperature is not None]
        prior = [points.get(start - timedelta(minutes=10 * i)) for i in range(144)]
        prior = [p.rain for p in prior if p and p.rain is not None]
        work_total = sum(rain) if None not in rain else None
        prior_total = sum(prior) if len(prior) == 144 else None
        maxima = []
        for width in (6, 18):
            windows = [rain[i:i + width] for i in range(count - width + 1)]
            totals = [sum(w) for w in windows if None not in w]
            maxima.append(max(totals) if totals else None)
        mountain = row["is_mountain"]
        mw, ml, gw, gl = (17, 24, 25, 30) if mountain else (14, 21, 20, 26)
        rain_override = (maxima[0] is not None and maxima[0] >= 72) or (maxima[1] is not None and maxima[1] >= 90)
        wind_override = any(v >= ml for v in wind) or any(v >= gl for v in gust)
        heat_override = any(v >= 38 for v in heat)
        def rainfall(total, key):
            if total is None:
                return None
            if total == 0:
                return 0
            bounds = model.get(key)
            if not bounds:
                return None
            return reference_curve(total, [(0, 0), (bounds["p50"], .25), (bounds["p90"], .75), (bounds["p99"], 1)])
        a, b = rainfall(work_total, "work_rain_mm"), rainfall(prior_total, "prior_24h_rain_mm")
        sr = 100 if rain_override else 100 * (.7 * a + .3 * b) if a is not None and b is not None else None
        sw = None
        if wind_override:
            sw = 100
        elif len(wind) == count:
            intensity = [reference_curve(max(wind), [(0, 0), (mw, .7), (ml, 1)])]
            if gust:
                intensity.append(reference_curve(max(gust), [(0, 0), (gw, .7), (gl, 1)]))
            sw = 100 * (.75 * max(intensity) + .25 * sum(v >= mw for v in wind) / count)
        sh = None
        if heat_override:
            sh = 100
        elif len(heat) == count:
            peak = 0 if max(heat) < 31 else reference_curve(max(heat), [(31, .25), (33, .5), (35, .75), (38, 1)])
            exposure = min(sum(v >= 31 for v in heat) / (.75 * count), 1)
            sh = 100 * (.6 * peak + .4 * exposure)
        expected = {
            "work_rain_mm": work_total, "prior_24h_rain_mm": prior_total,
            "work_max_1h_rain_mm": maxima[0], "work_max_3h_rain_mm": maxima[1],
            "work_max_wind_mps": max(wind, default=None), "work_max_gust_mps": max(gust, default=None),
            "work_max_apparent_temp_c": max(heat, default=None),
            "work_strong_wind_minutes": 10 * sum(v >= mw for v in wind),
            "work_heat_minutes_ge31": 10 * sum(v >= 31 for v in heat),
            "rain_full_score": rain_override, "wind_full_score": wind_override, "heat_full_score": heat_override,
            "S_R": sr, "S_W": sw, "S_H": sh,
        }
        for key, expected_value in expected.items():
            compare(row["work_date"], key, row[key], expected_value)
        for key in ("S_R", "S_W", "S_H"):
            if row[key] is not None and not 0 <= row[key] <= 100:
                mismatches.append(f"{row['work_date']}:{key}:out of range")
    return {"checked_days": checked, "comparison_count": comparisons,
            "mismatch_count": len(mismatches), "mismatch_examples": "; ".join(mismatches[:5])}


def main():
    """Standalone distribution comparison of saved scores (does not rescan raw weather)."""
    import argparse
    import csv
    import json
    from collections import defaultdict
    from pathlib import Path
    from weather_component_scores import atomic_write

    parser = argparse.ArgumentParser(description="Compare frozen training rain cutoffs with held-out rainfall in saved score CSV.")
    parser.add_argument("--scores", type=Path, required=True)
    parser.add_argument("--calibration-dir", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    directory = args.calibration_dir or args.scores.parent / "rain_calibrations"
    output = args.output or args.scores.with_name("weather_rain_quantile_validation.csv")
    if args.scores.resolve() == output.resolve():
        parser.error("scores and output must differ")
    try:
        by_station = defaultdict(list)
        with args.scores.open(encoding="utf-8-sig", newline="") as stream:
            for row in csv.DictReader(stream):
                selected = {k: row[k] for k in ("station_code", "station_name", "period", "rain_train_start", "rain_train_end")}
                for key in ("work_rain_mm", "prior_24h_rain_mm"):
                    selected[key] = float(row[key]) if row[key] else None
                by_station[row["station_code"]].append(selected)
        diagnostics = []
        for station, rows in sorted(by_station.items()):
            path = directory / f"rain_calibration_{station}.json"
            model = json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else {
                "train_start": rows[0]["rain_train_start"], "train_end": rows[0]["rain_train_end"]}
            diagnostics.extend(quantile_diagnostics(rows, model))
        if not diagnostics:
            raise ValueError("no station rows found")
        def write(stream):
            writer = csv.DictWriter(stream, fieldnames=list(diagnostics[0]))
            writer.writeheader()
            writer.writerows(diagnostics)
        atomic_write(output, write)
    except (OSError, ValueError, KeyError, csv.Error) as exc:
        parser.exit(1, f"Error: {exc}\n")
    print(f"Saved {len(diagnostics)} station-variable distribution comparisons: {output.resolve()}")


if __name__ == "__main__":
    main()
