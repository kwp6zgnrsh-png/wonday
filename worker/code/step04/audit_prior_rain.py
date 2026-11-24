"""Read-only lineage audit of STEP04 evaluation prior-rain missingness."""
import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from pathlib import Path

STEP = timedelta(minutes=10)


def num(text):
    try:
        value = float(text)
        return value if math.isfinite(value) and value >= 0 else None
    except (TypeError, ValueError):
        return None


def read(path):
    with path.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def write(path, rows):
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader(); writer.writerows(rows)


def audit(repo, output):
    output.mkdir(parents=True, exist_ok=True)
    scores = read(repo / "worker/data/gyeonggi_processed/step04/weather_ai_scores.csv")
    targets = defaultdict(list)
    for r in scores:
        if r["period"] == "validation":
            targets[r["station_code"]].append(r)
    clean = repo / "worker/data/gyeonggi_processed/weather_clean.csv"
    intervals, days, clean_stats = [], [], []
    work_wind_values = Counter()
    visited = set()

    def station_audit(station, points):
        if station not in targets:
            return
        print("Auditing clean station", station, flush=True)
        counts = Counter()
        for target in targets[station]:
            start = datetime.fromisoformat(target["work_date"] + "T" + target["work_start"])
            reasons = Counter()
            valid = 0
            for i in range(144):
                stamp = start - i * STEP
                current = points.get(stamp)
                previous = points.get(stamp - STEP)
                if current is not None and current[1] is not None:
                    valid += 1
                    continue
                if current is None:
                    reason = "observation_absent"
                elif current[0] is None:
                    reason = "current_cumulative_missing"
                elif stamp.hour == 0 and stamp.minute == 0 and current[0] == 0:
                    reason = "midnight_zero_without_daily_total"
                elif not (stamp.hour == 0 and stamp.minute == 10) and (previous is None or previous[0] is None):
                    reason = "previous_cumulative_missing_or_absent"
                elif previous is not None and current[0] < previous[0] and not (stamp.hour == 0 and stamp.minute == 10):
                    reason = "negative_cumulative_difference"
                else:
                    reason = "unexplained_derived_missing"
                reasons[reason] += 1
                intervals.append({"station_code": station, "work_date": target["work_date"],
                                  "interval_end": stamp.isoformat(sep=" "), "reason": reason,
                                  "clean_cumulative": current[0] if current else None,
                                  "previous_clean_cumulative": previous[0] if previous else None})
            if valid != int(target["prior_rain_interval_count"]):
                raise ValueError("STEP04 prior-count mismatch")
            only_midnight = set(reasons) == {"midnight_zero_without_daily_total"}
            other_complete = all(target[k] != "" for k in ("work_rain_mm", "work_rain_minutes", "work_max_mean_wind_mps", "work_strong_wind_minutes"))
            days.append({"station_code": station, "station_name": target["station_name"], "work_date": target["work_date"],
                         "AI1_input_complete": target["AI1_input_complete"], "prior_valid_count": valid,
                         "missing_count": 144 - valid, "missing_reasons": "|".join(sorted(reasons)),
                         "only_midnight_zero_missing": only_midnight,
                         "other_AI1_features_complete": other_complete,
                         "conditional_AI1_recoverable_if_midnight_resolved": only_midnight and other_complete})
            counts.update(reasons)
        for stamp, values in points.items():
            if stamp.year in (2024, 2025) and (stamp.hour > 8 or stamp.hour == 8 and stamp.minute > 0) and stamp.hour <= 17 and not (stamp.hour == 17 and stamp.minute > 0):
                work_wind_values[(station, "missing" if values[2] is None else "valid")] += 1
        clean_stats.append({"station_code": station, "missing_interval_reasons": dict(counts)})

    with clean.open(encoding="utf-8-sig", newline="") as f:
        reader = csv.reader(f); header = next(reader)
        indices = [header.index(k) for k in ("station_code", "observed_at", "daily_cumulative_rain_mm", "rain_10min_mm", "wind_mps")]
        current_station, points = None, {}
        for row in reader:
            station, stamp, cumulative, increment, wind = [row[i] for i in indices]
            if station != current_station:
                if current_station is not None:
                    station_audit(current_station, points); visited.add(current_station)
                if station in visited:
                    raise ValueError("clean CSV is not station-contiguous")
                current_station, points = station, {}
            if "2023-12-30" <= stamp[:10] <= "2025-12-31":
                points[datetime.fromisoformat(stamp)] = (num(cumulative), num(increment), num(wind))
        station_audit(current_station, points)

    requests = set()
    for r in intervals:
        t = datetime.fromisoformat(r["interval_end"])
        requests.add((r["station_code"], t)); requests.add((r["station_code"], t - STEP))
    raw_values, raw_counts, raw_daily, examples = {}, defaultdict(Counter), defaultdict(lambda: {"rows": 0, "valid": 0, "positive": 0, "max": 0.}), []
    files = []
    for p in sorted((repo / "worker/data/gyeonggi-2016-2026-csv").glob("*.csv")):
        bits = p.stem.split("_")
        if len(bits) >= 2 and bits[0] <= "2025-12-31" and bits[1] >= "2023-12-30":
            files.append(p)
    for i, path in enumerate(files):
        if i % 15 == 0:
            print(f"Checking raw source {i + 1}/{len(files)}", flush=True)
        with path.open(encoding="utf-8-sig", newline="") as f:
            reader = csv.reader(f); header = next(reader)
            # Source export schema: station, region, name, date, ..., wind(9), cumulative rain(10).
            if header[0] != "station_code" or len(header) < 11:
                raise ValueError("unexpected raw schema")
            for row in reader:
                station, source_time = row[0], row[3]
                if station not in targets or not "2023-12-30" <= source_time[:10] <= "2025-12-31":
                    continue
                source_day = source_time[:10]
                if source_time.endswith("24:00"):
                    stamp = datetime.fromisoformat(source_day) + timedelta(days=1)
                else:
                    stamp = datetime.fromisoformat(source_time)
                rain, wind = num(row[10]), num(row[9])
                daily = raw_daily[(station, source_day)]
                daily["rows"] += 1
                if rain is not None:
                    daily["valid"] += 1; daily["positive"] += rain > 0; daily["max"] = max(daily["max"], rain)
                if (station, stamp) in requests:
                    record = (rain, row[10], path.name, source_time)
                    existing = raw_values.get((station, stamp))
                    if existing and existing[0] != rain:
                        raise ValueError("conflicting raw rainfall duplicate")
                    raw_values[(station, stamp)] = record
                minute = stamp.hour * 60 + stamp.minute
                if stamp.year in (2024, 2025) and 480 < minute <= 1020:
                    raw_counts[station]["work_wind_valid" if wind is not None else "work_wind_missing"] += 1
                    if wind is None:
                        raw_counts[station]["work_wind_token_" + row[9]] += 1
    matches, mismatch, raw_absent, verified_midnight = Counter(), [], 0, Counter()
    for r in intervals:
        station = r["station_code"]; t = datetime.fromisoformat(r["interval_end"])
        current = raw_values.get((station, t)); previous = raw_values.get((station, t - STEP))
        r.update(raw_current_present=current is not None, raw_current_rain=current[1] if current else "",
                 raw_previous_present=previous is not None, raw_previous_rain=previous[1] if previous else "",
                 raw_file=current[2] if current else "", raw_timestamp=current[3] if current else "")
        if current:
            if current[0] != r["clean_cumulative"]:
                mismatch.append({"station": station, "timestamp": str(t), "raw": current[0], "clean": r["clean_cumulative"]})
            else:
                matches[r["reason"]] += 1
        else:
            raw_absent += 1
        if t.hour == 0 and t.minute == 0:
            source_day = (t - timedelta(days=1)).date().isoformat()
            daily = raw_daily[(station, source_day)]
            r["raw_source_day_rows"] = daily["rows"]
            r["raw_source_day_valid_cumulative"] = daily["valid"]
            r["raw_source_day_max_cumulative"] = daily["max"]
        if r["reason"] == "midnight_zero_without_daily_total":
            if not current or current[0] != 0:
                raise ValueError("midnight-zero hypothesis not supported by raw source")
            kind = "prior_cumulative_positive" if previous and previous[0] is not None and previous[0] > 0 else "prior_cumulative_zero" if previous and previous[0] == 0 else "prior_cumulative_missing"
            verified_midnight[kind] += 1
            if daily["rows"] == 144 and daily["valid"] == 144 and daily["positive"] == 0:
                verified_midnight["all_144_raw_cumulative_zero"] += 1
            if len(examples) < 12 and (kind == "prior_cumulative_positive" or not examples):
                examples.append(r.copy())
    if mismatch:
        raise ValueError(f"raw/clean cumulative mismatch: {mismatch[:3]}")
    stations = []
    for station in sorted(targets):
        group = [r for r in days if r["station_code"] == station]
        relevant = [r for r in intervals if r["station_code"] == station]
        counts = Counter(r["reason"] for r in relevant)
        stations.append({"station_code": station, "station_name": group[0]["station_name"], "total_days": len(group),
                         "AI1_excluded_days": sum(r["AI1_input_complete"] == "False" for r in group),
                         "prior_incomplete_days": sum(r["missing_count"] > 0 for r in group),
                         "only_midnight_zero_missing_days": sum(r["only_midnight_zero_missing"] for r in group),
                         "conditional_AI1_recoverable_if_midnight_resolved": sum(r["conditional_AI1_recoverable_if_midnight_resolved"] for r in group),
                         "missing_reasons": json.dumps(dict(counts), sort_keys=True),
                         "clean_work_wind_missing": work_wind_values[(station, "missing")],
                         "raw_work_wind_missing": raw_counts[station]["work_wind_missing"],
                         "raw_work_wind_valid": raw_counts[station]["work_wind_valid"],
                         "raw_work_wind_missing_tokens": json.dumps({k: v for k, v in raw_counts[station].items() if k.startswith("work_wind_token_")})})
    day_index = {(r["station_code"], r["work_date"]): r for r in days}
    absent_midnight = [r for r in intervals if r["reason"] == "observation_absent" and r["interval_end"][11:16] == "00:00"]
    common_dates = sorted(k for k, n in Counter(r["interval_end"][:10] for r in absent_midnight).items() if n == len(targets))
    single_absent_midnight = [r for r in absent_midnight if day_index[(r["station_code"], r["work_date"])]["missing_count"] == 1]
    summary = {"evaluation_rows": len(days), "AI1_excluded": sum(r["AI1_input_complete"] == "False" for r in days),
               "prior_incomplete_days": sum(r["missing_count"] > 0 for r in days),
               "prior_valid_count_histogram": dict(Counter(r["prior_valid_count"] for r in days)),
               "missing_interval_reasons": dict(Counter(r["reason"] for r in intervals)),
               "only_midnight_zero_missing_days": sum(r["only_midnight_zero_missing"] for r in days),
               "conditional_AI1_recoverable_if_midnight_resolved": sum(r["conditional_AI1_recoverable_if_midnight_resolved"] for r in days),
               "raw_clean_cumulative_matches_at_missing_intervals": dict(matches),
               "raw_clean_cumulative_mismatches": len(mismatch), "raw_absent_at_missing_intervals": raw_absent,
               "verified_midnight_zero": dict(verified_midnight), "raw_files_scanned": len(files),
               "daily_total_file_present": (repo / "worker/data/gyeonggi_daily_rainfall.csv").is_file(),
               "step04_prior_count_mismatches": 0, "examples": examples,
               "single_missing_reason_days": dict(Counter(r["missing_reasons"] for r in days if r["missing_count"] == 1)),
               "absent_midnight_intervals": len(absent_midnight), "single_absent_midnight_days": len(single_absent_midnight),
               "all_station_midnight_absent_dates": common_dates,
               "absent_midnight_source_day_row_histogram": dict(Counter(r["raw_source_day_rows"] for r in absent_midnight)),
               "conditional_AI1_recoverable_if_single_absent_midnight_resolved": sum(day_index[(r["station_code"], r["work_date"])]["other_AI1_features_complete"] for r in single_absent_midnight),
               "counterfactual_note": "Conditional coverage only; no zero imputation, input updates, model refits or policy changes performed."}
    write(output / "prior_rain_station_audit.csv", stations)
    # Extra evidence columns vary by reason.
    keys = list(dict.fromkeys(k for r in intervals for k in r))
    for r in intervals:
        for k in keys:
            r.setdefault(k, "")
    write(output / "prior_rain_missing_intervals.csv", [{k: r[k] for k in keys} for r in intervals])
    write(output / "prior_rain_day_audit.csv", days)
    (output / "prior_rain_audit_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k:v for k,v in summary.items() if k != "examples"}, ensure_ascii=True, indent=2), flush=True)
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    audit(args.repo, args.output_dir)
