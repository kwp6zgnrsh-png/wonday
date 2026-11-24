"""STEP04: complete-input, train-only Isolation Forest weather anomaly scores."""

import argparse
import csv
import hashlib
import json
import math
import pickle
import platform
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
import sklearn
from sklearn.ensemble import IsolationForest

# Read-only reuse of STEP03 parsing, partitioning and its agreed heat formula.
WORKER = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(WORKER / "code" / "step03"))
from weather_component_scores import apparent_temperature, number, partition_stations, parse_clock

VERSION = "step04-v1"
TRAIN_START, TRAIN_END = "2016-01-01", "2023-12-31"
VALIDATION_END = "2025-12-31"
STEP = timedelta(minutes=10)
FEATURES = {
    "AI1": ("work_rain_mm", "prior_24h_rain_mm", "work_max_mean_wind_mps",
            "work_rain_minutes", "work_strong_wind_minutes"),
    "AI2": ("work_max_temperature_c", "work_mean_humidity_pct", "work_heat_minutes_ge31"),
}
FILES = {"AI1": "isolation_forest_r1.pkl", "AI2": "isolation_forest_r2.pkl"}
COUNTS = ("work_rain_interval_count", "prior_rain_interval_count", "work_wind_interval_count",
          "work_temperature_interval_count", "work_humidity_interval_count", "work_heat_interval_count")


def period_for(day):
    if day < TRAIN_START:
        return "before_train"
    if day <= TRAIN_END:
        return "train"
    if day <= VALIDATION_END:
        return "validation"
    return "after_validation"


def boolean(value):
    if str(value).lower() not in {"true", "false"}:
        raise ValueError(f"invalid boolean: {value!r}")
    return str(value).lower() == "true"


def load_targets(path):
    """STEP03 supplies the work calendar/settings, never AI eligibility."""
    groups, keys, settings = defaultdict(list), set(), set()
    required = {"station_code", "work_date", "work_start", "work_end", "is_mountain", "period",
                "scheduled_minutes", "rain_full_score", "wind_full_score", "heat_full_score"}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if not required.issubset(reader.fieldnames or []):
            raise ValueError(f"target columns missing: {sorted(required - set(reader.fieldnames or []))}")
        for source in reader:
            day = date.fromisoformat(source["work_date"]).isoformat()
            station = source["station_code"].strip()
            key = station, day
            if not station or key in keys:
                raise ValueError(f"empty station or duplicate target: {key}")
            keys.add(key)
            start, end = parse_clock(source["work_start"]), parse_clock(source["work_end"])
            if not 0 <= start < end <= 1440 or start % 10 or end % 10:
                raise ValueError(f"invalid work window: {key}")
            if int(source["scheduled_minutes"]) != end - start:
                raise ValueError(f"scheduled_minutes mismatch: {key}")
            if source["period"] != period_for(day):
                raise ValueError(f"period/date mismatch: {key}")
            mountain = boolean(source["is_mountain"])
            groups[station].append({
                "station_code": station, "station_name": source.get("station_name", ""),
                "work_date": day, "work_start": source["work_start"], "work_end": source["work_end"],
                "scheduled_minutes": end - start, "is_mountain": mountain, "period": period_for(day),
                **{k: source.get(k, "") for k in ("S_R", "S_W", "S_H", "component_scores_complete")},
                **{k: boolean(source[k]) for k in ("rain_full_score", "wind_full_score", "heat_full_score")},
            })
            settings.add((start, end))
    if not keys or len(settings) != 1:
        raise ValueError("targets must be nonempty and have one common work window")
    return groups, next(iter(settings))


def load_points(path):
    """Keep raw temperature/humidity, lost by STEP03's Observation structure."""
    points = {}
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        for row in reader:
            stamp = datetime.fromisoformat(row["observed_at"])
            if stamp.tzinfo is not None or stamp.second or stamp.microsecond or stamp.minute % 10:
                raise ValueError(f"invalid naive KST interval end: {stamp}")
            value = (number(row["rain_10min_mm"], minimum=0),
                     number(row["wind_mps"], minimum=0), number(row["temperature_c"]),
                     number(row["humidity_pct"], minimum=0, maximum=100))
            if points.setdefault(stamp, value) != value:
                raise ValueError(f"conflicting duplicate: {stamp}")
    return points


def aggregate(points, target, start_minute, end_minute):
    start = datetime.combine(date.fromisoformat(target["work_date"]), datetime.min.time())
    start += timedelta(minutes=start_minute)
    expected = (end_minute - start_minute) // 10
    shift = [points.get(start + (i + 1) * STEP, (None,) * 4) for i in range(expected)]
    prior = [points.get(start - i * STEP, (None,) * 4)[0] for i in range(144)]
    rains, winds, temperatures, humidities = [list(v) for v in zip(*shift)]
    heats = [apparent_temperature(t, h) for t, h in zip(temperatures, humidities)]
    valid = lambda values: [v for v in values if v is not None]
    rain, wind, temp, humidity, heat, previous = map(valid, (rains, winds, temperatures, humidities, heats, prior))
    complete_rain, complete_wind = len(rain) == expected, len(wind) == expected
    result = dict(target)
    result.update(expected_work_interval_count=expected, expected_prior_interval_count=144,
                  work_rain_interval_count=len(rain), prior_rain_interval_count=len(previous),
                  work_wind_interval_count=len(wind), work_temperature_interval_count=len(temp),
                  work_humidity_interval_count=len(humidity), work_heat_interval_count=len(heat),
                  work_rain_mm=math.fsum(rain) if complete_rain else None,
                  prior_24h_rain_mm=math.fsum(previous) if len(previous) == 144 else None,
                  work_max_mean_wind_mps=max(wind) if complete_wind else None,
                  work_rain_minutes=10 * sum(v > 0 for v in rain) if complete_rain else None,
                  work_strong_wind_minutes=10 * sum(v >= (17 if target["is_mountain"] else 14) for v in wind)
                  if complete_wind else None,
                  work_max_temperature_c=max(temp) if len(temp) == expected else None,
                  work_mean_humidity_pct=math.fsum(humidity) / expected if len(humidity) == expected else None,
                  work_heat_minutes_ge31=10 * sum(v >= 31 for v in heat) if len(heat) == expected else None)
    for model, features in FEATURES.items():
        missing = [k for k in features if result[k] is None]
        result[f"{model}_input_complete"] = not missing
        result[f"{model}_missing_features"] = "|".join(missing)
        result[f"{model}_exclusion_reason"] = "incomplete_observations" if missing else ""
    return result


def build_features(weather, targets):
    groups, (start, end) = load_targets(targets)
    rows = []
    with TemporaryDirectory(prefix="step04_stations_") as temporary:
        print("Partitioning 10-minute observations (temporary disk ~= input size)", flush=True)
        partitions = partition_stations(weather, Path(temporary), set(groups))
        for i, station in enumerate(sorted(groups), 1):
            print(f"[{i}/{len(groups)}] Aggregating {station}", flush=True)
            points = load_points(partitions[station][0])
            rows.extend(aggregate(points, target, start, end) for target in sorted(groups[station], key=lambda r: r["work_date"]))
            del points
    return rows, {"work_start_minute": start, "work_end_minute": end,
                  "wind_threshold_policy": "STEP03_nonmountain14_mountain17",
                  "heat_policy": "STEP03_summer_apparent_temperature_ge31",
                  "rain_duration_policy": "rain_10min_mm_gt0"}


def fit_bundle(rows, model, config, *, seed=42, trees=300, min_samples=100):
    features = FEATURES[model]
    training = [r for r in rows if TRAIN_START <= r["work_date"] <= TRAIN_END and r[f"{model}_input_complete"]]
    metadata = {"schema_version": VERSION, "model": model, "features": list(features), "config": config,
                "train_start": TRAIN_START, "train_end": TRAIN_END, "training_rows": len(training),
                "actual_train_start": min((r["work_date"] for r in training), default=None),
                "actual_train_end": max((r["work_date"] for r in training), default=None),
                "versions": {"python": platform.python_version(), "numpy": np.__version__, "sklearn": sklearn.__version__},
                "parameters": {"n_estimators": trees, "max_samples": "auto", "contamination": "auto",
                               "max_features": 1.0, "bootstrap": False, "random_state": seed, "n_jobs": 1},
                "minimum_training_rows": min_samples, "preprocessing": "raw_units_no_imputation_no_clipping",
                "percentile": "100 * count(train_anomaly <= anomaly) / n; anomaly=-score_samples"}
    bundle = {"metadata": metadata, "forest": None, "reference": np.array([], dtype=float)}
    if len(training) < min_samples:
        metadata.update(status="unavailable", reason="insufficient_training_rows")
        return bundle
    matrix = np.asarray([[r[k] for k in features] for r in training], dtype=float)
    if not np.isfinite(matrix).all():
        raise ValueError("nonfinite training matrix")
    if len(np.unique(matrix, axis=0)) < 2:
        metadata.update(status="unavailable", reason="no_training_variation")
        return bundle
    forest = IsolationForest(**metadata["parameters"]).fit(matrix)
    reference = np.sort(-forest.score_samples(matrix))
    digest = hashlib.sha256()
    for row in training:
        digest.update(json.dumps([row["station_code"], row["work_date"], *[row[k] for k in features]],
                                 separators=(",", ":")).encode())
    metadata.update(status="fitted", reason="", training_input_sha256=digest.hexdigest())
    bundle.update(forest=forest, reference=reference)
    return bundle


def percentile(reference, values):
    if len(reference) == 0:
        raise ValueError("empty training reference")
    return 100.0 * np.searchsorted(reference, values, side="right") / len(reference)


def score_rows(rows, bundles):
    for model, features in FEATURES.items():
        bundle = bundles[model]
        indices = [i for i, row in enumerate(rows) if row[f"{model}_input_complete"]]
        computed = {}
        if indices and bundle["forest"] is not None:
            matrix = np.asarray([[rows[i][k] for k in features] for i in indices], dtype=float)
            raw = -bundle["forest"].score_samples(matrix)
            scores = percentile(bundle["reference"], raw)
            computed = dict(zip(indices, zip(raw, scores)))
        for i, row in enumerate(rows):
            row[f"{model}_model_version"] = VERSION
            row[f"{model}_used_features"] = "|".join(features) if i in computed else ""
            row[f"{model}_training_used"] = bool(bundle["forest"] is not None and row["period"] == "train"
                                                    and row[f"{model}_input_complete"])
            row[f"{model}_status"] = "computed" if i in computed else "unavailable"
            row[f"{model}_raw_anomaly"], row[f"S_{model}"] = map(float, computed[i]) if i in computed else (None, None)
            row[f"{model}_unavailable_reason"] = ("" if i in computed else
                "incomplete_observations" if not row[f"{model}_input_complete"] else bundle["metadata"]["reason"])
    for row in rows:
        a, b = row["AI1_status"] == "computed", row["AI2_status"] == "computed"
        row["calculation_status"] = "both" if a and b else "AI1_only" if a else "AI2_only" if b else "unavailable"
    return rows


def diagnostics(rows):
    """Model exclusions, feature missingness, joint coverage, observed-extreme bias."""
    groups = defaultdict(list)
    for row in rows:
        for station in ("ALL", row["station_code"]):
            groups[(row["period"], station)].append(row)
    exclusions, missingness, patterns, coverage = [], [], [], []
    for (period, station), group in sorted(groups.items()):
        total = len(group)
        counts = Counter(r["calculation_status"] for r in group)
        coverage.append({"period": period, "station_code": station, "total_rows": total,
                         **{f"{k}_rows": counts[k] for k in ("both", "AI1_only", "AI2_only", "unavailable")},
                         **{f"{k}_pct": 100 * counts[k] / total for k in ("both", "AI1_only", "AI2_only", "unavailable")}})
        for model, features in FEATURES.items():
            eligible = [r for r in group if r[f"{model}_input_complete"]]
            excluded = [r for r in group if not r[f"{model}_input_complete"]]
            used = [r for r in group if r[f"{model}_status"] == "computed"]
            training = [r for r in group if r[f"{model}_training_used"]]
            summary = {"period": period, "station_code": station, "model": model, "total_rows": total,
                       "input_complete_rows": len(eligible), "input_excluded_rows": len(excluded),
                       "input_exclusion_pct": 100 * len(excluded) / total, "computed_rows": len(used),
                       "model_unavailable_complete_rows": len(eligible) - len(used), "training_used_rows": len(training),
                       "actual_train_start": min((r["work_date"] for r in training), default=None),
                       "actual_train_end": max((r["work_date"] for r in training), default=None)}
            # STEP03 observed threshold flags can fire even with partial observations.
            for hazard in ("rain", "wind", "heat"):
                for label, subset in (("included", eligible), ("excluded", excluded)):
                    n = sum(r[f"{hazard}_full_score"] for r in subset)
                    summary[f"{label}_observed_{hazard}_extreme_rows"] = n
                    summary[f"{label}_observed_{hazard}_extreme_pct"] = 100 * n / len(subset) if subset else None
            values = [r[f"S_{model}"] for r in used]
            for label, q in (("p50", .5), ("p90", .9), ("p99", .99)):
                summary[f"score_{label}"] = float(np.quantile(values, q)) if values else None
            summary["score_mean"] = math.fsum(values) / len(values) if values else None
            summary["score_ge95_rows"] = sum(v >= 95 for v in values)
            exclusions.append(summary)
            for feature in features:
                n = sum(r[feature] is None for r in group)
                missingness.append({"period": period, "station_code": station, "model": model,
                                    "feature": feature, "total_rows": total, "missing_rows": n, "missing_pct": 100 * n / total})
            for pattern, n in sorted(Counter(r[f"{model}_missing_features"] for r in excluded).items()):
                patterns.append({"period": period, "station_code": station, "model": model,
                                 "missing_features": pattern, "rows": n, "pct_of_total": 100 * n / total})
    return {"model_diagnostics.csv": exclusions, "feature_missingness.csv": missingness,
            "missing_patterns.csv": patterns, "calculation_coverage.csv": coverage}


def audit(rows, bundles):
    keys = {(r["station_code"], r["work_date"]) for r in rows}
    if len(keys) != len(rows):
        raise ValueError("duplicate output keys")
    for model, features in FEATURES.items():
        bundle = bundles[model]
        for row in rows:
            expected = all(row[k] is not None and math.isfinite(row[k]) for k in features)
            if expected != row[f"{model}_input_complete"]:
                raise ValueError("eligibility mismatch")
            computed = row[f"{model}_status"] == "computed"
            if computed != (expected and bundle["forest"] is not None):
                raise ValueError("score availability mismatch")
            if computed:
                value, raw = row[f"S_{model}"], row[f"{model}_raw_anomaly"]
                if not math.isfinite(raw) or not 0 <= value <= 100:
                    raise ValueError("invalid score")
                # Independent scalar ECDF lookup, separate from vectorized scoring.
                from bisect import bisect_right
                recalculated = 100.0 * bisect_right(bundle["reference"], raw) / len(bundle["reference"])
                if abs(value - recalculated) > 1e-12:
                    raise ValueError("percentile audit mismatch")
    return {"rows": len(rows), "unique_keys": len(keys), "score_and_percentile_checks": "passed"}


def write_csv(path, rows, columns=None):
    if columns is not None:
        columns = list(columns)
    elif not rows:
        # A no-missing run still produces an intelligible header-only report.
        columns = ["period", "station_code", "model", "missing_features", "rows", "pct_of_total"]
    else:
        columns = list(rows[0])
    with path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run(args):
    output = args.output_dir.resolve()
    # Protect STEP03 and source directories from accidental output replacement.
    if output == args.targets.resolve().parent or output in args.weather.resolve().parents:
        raise ValueError("output directory must be separate from input directories")
    for protected in (WORKER / "code" / "step03", WORKER / "docs" / "step03",
                      WORKER / "data" / "gyeonggi_processed" / "step03"):
        if output == protected.resolve() or protected.resolve() in output.parents:
            raise ValueError("STEP03 is read-only")
    source_before = {str(p.resolve()): (p.stat().st_size, p.stat().st_mtime_ns) for p in (args.weather, args.targets)}
    rows, config = build_features(args.weather, args.targets)
    output.mkdir(parents=True, exist_ok=True)
    bundles = {}
    # Publish only after scoring, reload validation and audits have succeeded.
    with TemporaryDirectory(prefix=".step04_run_", dir=output) as temporary:
        stage = Path(temporary)
        for model in FEATURES:
            print(f"Fitting {model} from 2016-2023 complete inputs only", flush=True)
            bundles[model] = fit_bundle(rows, model, config, seed=args.seed, trees=args.trees, min_samples=args.min_training_rows)
            with (stage / FILES[model]).open("wb") as stream:
                pickle.dump(bundles[model], stream, protocol=pickle.HIGHEST_PROTOCOL)
        score_rows(rows, bundles)
        original_scores = [(r["AI1_raw_anomaly"], r["S_AI1"], r["AI2_raw_anomaly"], r["S_AI2"]) for r in rows]
        reloaded = {}
        for model in FEATURES:
            with (stage / FILES[model]).open("rb") as stream:
                reloaded[model] = pickle.load(stream)
        score_rows(rows, reloaded)
        if original_scores != [(r["AI1_raw_anomaly"], r["S_AI1"], r["AI2_raw_anomaly"], r["S_AI2"]) for r in rows]:
            raise ValueError("model reload scores differ")
        report = audit(rows, reloaded)
        report.update(model_reload_check="passed", station_count=len({r["station_code"] for r in rows}),
                      period_rows=dict(Counter(r["period"] for r in rows)), schema_version=VERSION,
                      interpretation="Weather anomaly percentiles, not cancellation probabilities or statistical significance.",
                      extreme_diagnostic_limit="STEP03 observed threshold flags only; false does not rule out unobserved extremes.",
                      feature_audit_scope="Aggregation covered by synthetic boundary tests; real-data audit checks eligibility, scores, ECDF and reload, not independent raw reaggregation.",
                      inputs={str(p.resolve()): {"sha256": sha256(p), "bytes": p.stat().st_size} for p in (args.weather, args.targets)},
                      code_sha256=sha256(Path(__file__)), config=config,
                      models={m: b["metadata"] for m, b in bundles.items()})
        write_csv(stage / "weather_ai_scores.csv", rows)
        score_columns = {k for k in rows[0] if k.startswith("AI") or k.startswith("S_AI") or k == "calculation_status"}
        # Keep input eligibility/missingness in the feature artifact, remove only scoring fields.
        keep = {f"{m}_{suffix}" for m in FEATURES for suffix in ("input_complete", "missing_features", "exclusion_reason")}
        write_csv(stage / "weather_ai_features.csv", [{k: v for k, v in r.items() if k not in score_columns or k in keep} for r in rows])
        for name, data in diagnostics(rows).items():
            write_csv(stage / name, data)
        for period, filename in (("train", "weather_ai_train.csv"), ("validation", "weather_ai_holdout.csv"),
                                 ("after_validation", "weather_ai_additional.csv")):
            selected = [r for r in rows if r["period"] == period]
            write_csv(stage / filename, selected, columns=rows[0])
        for path, signature in source_before.items():
            if signature != (Path(path).stat().st_size, Path(path).stat().st_mtime_ns):
                raise ValueError("input changed during run")
        report["output_files"] = {p.name: sha256(p) for p in sorted(stage.iterdir())}
        (stage / "run_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
        for path in sorted(stage.iterdir(), key=lambda p: p.name == "run_report.json"):
            path.replace(output / path.name)
    print(json.dumps({"rows": len(rows), "models": {m: {k: b["metadata"].get(k) for k in ("status", "training_rows", "reason")} for m, b in bundles.items()},
                      "output": str(output)}, indent=2), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    data = WORKER / "data" / "gyeonggi_processed"
    parser.add_argument("--weather", type=Path, default=data / "weather_clean.csv")
    parser.add_argument("--targets", type=Path, default=data / "step03" / "weather_component_scores.csv")
    parser.add_argument("--output-dir", type=Path, default=data / "step04")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--trees", type=int, default=300)
    parser.add_argument("--min-training-rows", type=int, default=100)
    args = parser.parse_args()
    if args.trees < 1 or args.min_training_rows < 2:
        parser.error("trees >= 1 and min-training-rows >= 2 required")
    try:
        run(args)
    except (ValueError, OSError, KeyError, csv.Error) as exc:
        parser.exit(1, f"Error: {exc}\n")


if __name__ == "__main__":
    main()
