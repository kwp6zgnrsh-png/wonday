"""Aggregate original ten-minute observations using STEP03 target rows and settings."""

import csv
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory

from common import (AI_FEATURES, BASE_COLUMNS, FEATURE_COLUMNS, apparent_temperature,
                    boolean, check_rows, number, parse_clock, partition_stations,
                    period_for, rolling_max, sha256, write_json, write_rows,
                    feature_contract, protect_existing_results)

STEP = timedelta(minutes=10)


@dataclass(frozen=True, slots=True)
class Observation:
    rain: float | None
    wind: float | None
    temperature: float | None
    humidity: float | None
    cumulative: float | None = None


def load_points(path, require_cumulative=False):
    points = {}
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        if require_cumulative and "daily_cumulative_rain_mm" not in (reader.fieldnames or []):
            raise ValueError("official reconstruction requires original daily_cumulative_rain_mm")
        for row in reader:
            stamp = datetime.fromisoformat(row["observed_at"])
            if stamp.tzinfo is not None or stamp.second or stamp.microsecond or stamp.minute % 10:
                raise ValueError(f"invalid naive KST ten-minute interval end: {stamp}")
            point = Observation(number(row["rain_10min_mm"], minimum=0),
                                number(row["wind_mps"], minimum=0),
                                number(row["temperature_c"]),
                                number(row["humidity_pct"], minimum=0, maximum=100),
                                number(row.get("daily_cumulative_rain_mm", ""), minimum=0))
            if points.setdefault(stamp, point) != point:
                raise ValueError(f"conflicting observations at {stamp}")
    return points


def aggregate(points, target, official_daily=None):
    row = {column: target.get(column, "") for column in BASE_COLUMNS}
    day = date.fromisoformat(row["work_date"])
    start, end = parse_clock(row["work_start"]), parse_clock(row["work_end"])
    if not 0 <= start < end <= 1440:
        raise ValueError("STEP03 work interval must be within one day")
    row["is_mountain"] = boolean(row["is_mountain"])
    if row["period"] != period_for(day.isoformat()):
        raise ValueError("STEP03 period must use the fixed 2016-2023 / 2024-2025 boundary")
    count = (end - start) // 10
    beginning = datetime.combine(day, time()) + timedelta(minutes=start)
    shift = [points.get(beginning + (i + 1) * STEP) for i in range(count)]
    prior = [points.get(beginning - i * STEP) for i in range(144)]
    values = lambda attribute, observations: [getattr(p, attribute) for p in observations
                                             if p is not None and getattr(p, attribute) is not None]
    rain, wind = values("rain", shift), values("wind", shift)
    temperature, humidity = values("temperature", shift), values("humidity", shift)
    prior_rain = values("rain", prior)
    heat = [apparent_temperature(p.temperature, p.humidity) for p in shift
            if p is not None and p.temperature is not None and p.humidity is not None]
    wind_limit = 17 if row["is_mountain"] else 14
    rain_1h, _ = rolling_max([p.rain if p is not None else None for p in shift],6)
    row.update({
        "scheduled_interval_count": count, "scheduled_minutes": end - start,
        "prior_scheduled_interval_count": 144,
        "work_observation_count": sum(p is not None for p in shift),
        "work_rain_interval_count": len(rain), "prior_rain_interval_count": len(prior_rain),
        "work_wind_interval_count": len(wind), "work_temperature_interval_count": len(temperature),
        "work_humidity_interval_count": len(humidity), "work_heat_interval_count": len(heat),
        "work_rain_mm": sum(rain) if len(rain) == count else None,
        "prior_24h_rain_mm": sum(prior_rain) if len(prior_rain) == 144 else None,
        "work_max_wind_mps": max(wind) if len(wind) == count else None,
        "work_rain_minutes": 10 * sum(v > 0 for v in rain) if len(rain) == count else None,
        "work_strong_wind_minutes": 10 * sum(v >= wind_limit for v in wind) if len(wind) == count else None,
        "work_max_temp_c": max(temperature) if len(temperature) == count else None,
        "work_mean_humidity_pct": sum(humidity) / count if len(humidity) == count else None,
        "work_heat_minutes_ge31": 10 * sum(v >= 31 for v in heat) if len(heat) == count else None,
        "observed_work_max_1h_rain_mm": rain_1h,
        "observed_work_max_wind_mps": max(wind, default=None),
        "observed_work_max_temp_c": max(temperature, default=None),
        "observed_work_max_apparent_temp_c": max(heat, default=None),
    })
    if official_daily is not None:
        from prior_rain_reconstruction import apply
        apply(row, points, official_daily)
    for model, columns in AI_FEATURES.items():
        missing = [k for k in columns if row[k] is None]
        policy = row.get("ai1_policy_exclusion", "") if model == "AI1" else ""
        row[model.lower() + "_eligible"] = not missing and not policy
        row[model.lower() + "_missing_features"] = "|".join(missing)
    row["ai_input_status"] = input_status(row["ai1_eligible"], row["ai2_eligible"])
    return row


def input_status(ai1, ai2):
    return "both" if ai1 and ai2 else "AI1_only" if ai1 else "AI2_only" if ai2 else "neither"


def build_features(clean_path, step03_path, output_path, official_path=None):
    if Path(output_path).resolve() in (Path(clean_path).resolve(), Path(step03_path).resolve()):
        raise ValueError("feature output must differ from both inputs")
    daily = None
    if official_path is not None:
        from prior_rain_reconstruction import load_daily
        protect_existing_results(output_path)
        if Path(output_path).resolve() == Path(official_path).resolve():
            raise ValueError("feature output must not overwrite official daily input")
        daily = load_daily(official_path)
    targets = defaultdict(list)
    seen = set()
    with Path(step03_path).open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"station_code", "work_date", "work_start", "work_end", "is_mountain", "period"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError("STEP03 targets lack required work settings")
        for source in reader:
            target = {k: source.get(k, "") for k in BASE_COLUMNS}
            key = target["station_code"], target["work_date"]
            date.fromisoformat(target["work_date"])
            if key in seen:
                raise ValueError(f"duplicate STEP03 target: {key}")
            seen.add(key)
            targets[target["station_code"]].append(target)
    if not targets:
        raise ValueError("no STEP03 target rows")
    print("Partitioning original observations (one station in memory at a time)", flush=True)
    rows = []
    with TemporaryDirectory(prefix="weather_step04_") as folder:
        partitions = partition_stations(Path(clean_path), Path(folder))
        for index, (station, station_targets) in enumerate(sorted(targets.items()), 1):
            print(f"[{index}/{len(targets)}] {station}", flush=True)
            points = load_points(partitions[station][0], require_cumulative=daily is not None) if station in partitions else {}
            rows.extend(aggregate(points, target, daily) for target in sorted(station_targets, key=lambda r:r["work_date"]))
    check_rows(rows)
    columns = FEATURE_COLUMNS
    if daily is not None:
        from prior_rain_reconstruction import COLUMNS
        columns = (*FEATURE_COLUMNS, *COLUMNS)
    write_rows(output_path, rows, columns)
    metadata = {
        "schema_version": 1, "source": str(Path(clean_path).resolve()),
        "step03_targets": str(Path(step03_path).resolve()), "target_sha256": sha256(step03_path),
        "row_count": len(rows), "station_count": len(targets),
        "feature_order": AI_FEATURES, "preprocessing": "original units; no imputation or clipping",
        "heat_formula": "shared STEP03 apparent_temperature; exposure >=31 C",
        "wind_thresholds_mps": {"non_mountain":14,"mountain":17},
    }
    if daily is not None:
        metadata.update(feature_contract=feature_contract(rows),
                        official_daily_path=str(Path(official_path).resolve()),
                        official_daily_sha256=sha256(official_path), clean_sha256=sha256(clean_path),
                        daily_window_contract="same-station RDA KST calendar-day total",
                        tolerance_mm=0.001)
        metadata["preprocessing"] = "original units; verified official-daily P24 reconstruction; no statistical imputation or clipping"
    write_json(Path(output_path).with_suffix(".metadata.json"), metadata)
    print(f"Saved {len(rows):,} target rows: {output_path}", flush=True)
    return rows
