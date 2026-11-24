"""Read saved STEP04 artifacts and compare retained rows/aggregates with STEP03."""

import argparse
import csv
import hashlib
import json
import math
from collections import Counter
from pathlib import Path


def read_rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def validate(targets, output):
    report = json.loads((output / "run_report.json").read_text(encoding="utf-8"))
    for name, expected in report["output_files"].items():
        if digest(output / name) != expected:
            raise ValueError(f"artifact hash mismatch: {name}")
    original = read_rows(targets)
    result = read_rows(output / "weather_ai_scores.csv")
    key = lambda r: (r["station_code"], r["work_date"])
    source = {key(r): r for r in original}
    if len(source) != len(original) or len({key(r) for r in result}) != len(result) or set(source) != {key(r) for r in result}:
        raise ValueError("target rows not preserved exactly")
    comparisons = Counter()
    for row in result:
        previous = source[key(row)]
        for column in ("period", "work_start", "work_end", "scheduled_minutes", "S_R", "S_W", "S_H"):
            if row[column] != previous[column]:
                raise ValueError(f"retained value mismatch: {key(row)}, {column}")
        expected = int(row["expected_work_interval_count"])
        # Compare only complete features; STEP03 can retain partial maxima/exposures.
        pairs = [("work_rain_mm", "work_rain_mm"), ("prior_24h_rain_mm", "prior_24h_rain_mm")]
        if int(row["work_wind_interval_count"]) == expected:
            pairs.extend([("work_max_mean_wind_mps", "work_max_wind_mps"),
                          ("work_strong_wind_minutes", "work_strong_wind_minutes")])
        if int(row["work_heat_interval_count"]) == expected:
            pairs.append(("work_heat_minutes_ge31", "work_heat_minutes_ge31"))
        for new, old in pairs:
            a, b = row[new], previous[old]
            if (a == "") != (b == "") or (a != "" and not math.isclose(float(a), float(b), rel_tol=0., abs_tol=1e-6)):
                raise ValueError(f"aggregate mismatch: {key(row)}, {new}: {a} != {b}")
            comparisons[new] += 1
    for period, filename in (("train", "weather_ai_train.csv"), ("validation", "weather_ai_holdout.csv"),
                             ("after_validation", "weather_ai_additional.csv")):
        split = read_rows(output / filename)
        if split != [r for r in result if r["period"] == period]:
            raise ValueError(f"split mismatch: {filename}")
    summaries = read_rows(output / "model_diagnostics.csv")
    for summary in summaries:
        group = [r for r in result if r["period"] == summary["period"]
                 and (summary["station_code"] == "ALL" or r["station_code"] == summary["station_code"])]
        model = summary["model"]
        excluded = sum(r[f"{model}_input_complete"] == "False" for r in group)
        if int(summary["total_rows"]) != len(group) or int(summary["input_excluded_rows"]) != excluded:
            raise ValueError("diagnostic counts mismatch")
        if not math.isclose(float(summary["input_exclusion_pct"]), 100. * excluded / len(group), abs_tol=1e-10):
            raise ValueError("exclusion denominator mismatch")
    return {"rows_checked": len(result), "artifact_hashes": "passed", "target_row_and_score_preservation": "passed",
            "period_splits": "passed", "exclusion_counts_and_denominators": "passed",
            "step03_aggregate_comparisons": dict(comparisons), "mismatch_count": 0,
            "scope": "Saved STEP03/STEP04 aggregate comparison; not a second independent pass over raw 10-minute observations."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    data = Path(__file__).resolve().parents[2] / "data" / "gyeonggi_processed"
    parser.add_argument("--targets", type=Path, default=data / "step03" / "weather_component_scores.csv")
    parser.add_argument("--output-dir", type=Path, default=data / "step04")
    args = parser.parse_args()
    try:
        result = validate(args.targets, args.output_dir)
    except (ValueError, OSError, KeyError) as exc:
        parser.exit(1, f"Error: {exc}\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
