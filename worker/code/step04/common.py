"""Shared contracts for STEP04. No fitted preprocessing or missing-value replacement."""

import csv
import hashlib
import json
import math
import sys
from pathlib import Path

CODE_DIR = Path(__file__).resolve().parent
WORKER_DIR = CODE_DIR.parents[1]
sys.path.insert(0, str(CODE_DIR.parent))
from step03.weather_component_scores import (  # noqa: E402
    apparent_temperature, atomic_write, number, parse_clock, partition_stations, rolling_max,
)

DATA_DIR = WORKER_DIR / "data" / "gyeonggi_processed"
OUTPUT_DIR = DATA_DIR / "step04"
RECONSTRUCTED_OUTPUT_DIR = DATA_DIR / "step04_official_daily"
AI_FEATURES = {
    "AI1": ("work_rain_mm", "prior_24h_rain_mm", "work_max_wind_mps",
            "work_rain_minutes", "work_strong_wind_minutes"),
    "AI2": ("work_max_temp_c", "work_mean_humidity_pct", "work_heat_minutes_ge31"),
}
ALL_FEATURES = tuple(dict.fromkeys(k for columns in AI_FEATURES.values() for k in columns))
COUNTS = ("work_observation_count", "work_rain_interval_count", "prior_rain_interval_count",
          "work_wind_interval_count", "work_temperature_interval_count",
          "work_humidity_interval_count", "work_heat_interval_count")
FEATURE_COUNTS = {
    "work_rain_mm":"work_rain_interval_count", "work_rain_minutes":"work_rain_interval_count",
    "prior_24h_rain_mm":"prior_rain_interval_count", "work_max_wind_mps":"work_wind_interval_count",
    "work_strong_wind_minutes":"work_wind_interval_count",
    "work_max_temp_c":"work_temperature_interval_count",
    "work_mean_humidity_pct":"work_humidity_interval_count", "work_heat_minutes_ge31":"work_heat_interval_count",
}
BASE_COLUMNS = ("station_code", "station_name", "work_date", "work_start", "work_end",
                "is_mountain", "period", "S_R", "S_W", "S_H")
PARTIAL_COLUMNS = ("observed_work_max_1h_rain_mm", "observed_work_max_wind_mps",
                   "observed_work_max_temp_c", "observed_work_max_apparent_temp_c")
FEATURE_COLUMNS = (*BASE_COLUMNS, "scheduled_interval_count", "scheduled_minutes",
                   "prior_scheduled_interval_count", *COUNTS, *ALL_FEATURES, *PARTIAL_COLUMNS,
                   "ai1_eligible", "ai1_missing_features", "ai2_eligible", "ai2_missing_features",
                   "ai_input_status")
EXTREME_LIMITS = {
    "observed_work_max_1h_rain_mm": 72.0,
    "observed_work_max_wind_mps": 21.0,
    "observed_work_max_temp_c": 33.0,
    "observed_work_max_apparent_temp_c": 38.0,
}


def period_for(day):
    if "2016-01-01" <= day <= "2023-12-31":
        return "train"
    if "2024-01-01" <= day <= "2025-12-31":
        return "validation"
    return "after_validation" if day > "2025-12-31" else "before_train"


def boolean(value):
    if value in (True, "True", "true", "1"):
        return True
    if value in (False, "False", "false", "0"):
        return False
    raise ValueError(f"invalid boolean: {value!r}")


def present(value):
    return value is not None and value != ""


def row_key(row):
    return row["station_code"], row["work_date"]


def read_rows(path):
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_rows(path, rows, columns=None):
    if columns is None:
        if not rows:
            raise ValueError("cannot infer columns from empty rows")
        columns = list(rows[0])
    def write(stream):
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)
    atomic_write(Path(path), write)


def write_json(path, data):
    atomic_write(Path(path), lambda stream: json.dump(data, stream, ensure_ascii=False,
                                                     indent=2, allow_nan=False))


def sha256(path):
    result = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def is_reconstructed_run(rows):
    flags = {bool(r.get("prior_rain_policy")) for r in rows}
    if len(flags) != 1:
        raise ValueError("mixed legacy and reconstruction feature contracts")
    return True in flags


def feature_contract(rows):
    reconstructed = is_reconstructed_run(rows)
    policies = sorted({r.get("prior_rain_policy", "observed_10min_v1") for r in rows})
    if len(policies) != 1:
        raise ValueError("mixed prior rainfall policies")
    return {"feature_order": {k:list(v) for k,v in AI_FEATURES.items()},
            "prior_rain_policy": policies[0],
            "work_settings": [list(v) for v in sorted({(r["work_start"],r["work_end"],boolean(r["is_mountain"])) for r in rows})],
            "wind_thresholds_mps": [14,17], "heat_threshold_c":31,
            "ai1_station_exclusions": ["487915A001"] if reconstructed else []}


def protect_existing_results(path):
    """Restoration runs must never write into baseline STEP03/STEP04 result trees."""
    path = Path(path).resolve()
    protected = [OUTPUT_DIR.resolve(), (DATA_DIR/"step03").resolve()]
    if path == DATA_DIR.resolve() or any(path == p or p in path.parents for p in protected):
        raise ValueError("official-daily reconstruction requires a separate output folder; baseline STEP03/STEP04 are protected")
    if path in {(DATA_DIR/name).resolve() for name in ("weather_clean.csv","weather_workday_features.csv","official_daily_rainfall.csv")}:
        raise ValueError("cannot overwrite shared weather inputs")


def verify_feature_metadata(path, rows):
    if not is_reconstructed_run(rows):
        return
    metadata = json.loads(Path(path).with_suffix(".metadata.json").read_text(encoding="utf-8-sig"))
    if metadata.get("feature_contract") != feature_contract(rows):
        raise ValueError("feature metadata/input contract mismatch")
    if metadata.get("tolerance_mm") != 0.001:
        raise ValueError("reconstruction tolerance differs from the versioned policy")
    for source, checksum in (("official_daily_path", "official_daily_sha256"), ("source", "clean_sha256")):
        if sha256(metadata[source]) != metadata[checksum]:
            raise ValueError(f"{source} changed after feature reconstruction; rebuild features")


def check_rows(rows):
    if not rows:
        raise ValueError("no target rows")
    seen = set()
    for row in rows:
        key = row_key(row)
        if key in seen:
            raise ValueError(f"duplicate station/work date: {key}")
        seen.add(key)
        if row["period"] != period_for(row["work_date"]):
            raise ValueError(f"period mismatch at {key}")
        start, end = parse_clock(row["work_start"]), parse_clock(row["work_end"])
        if not 0 <= start < end <= 1440:
            raise ValueError(f"invalid work window: {key}")
        count = (end-start)//10
        if (int(row["scheduled_interval_count"]) != count or int(row["scheduled_minutes"]) != end-start
                or int(row["prior_scheduled_interval_count"]) != 144):
            raise ValueError(f"scheduled count mismatch: {key}")
        for field in COUNTS:
            expected = 144 if field == "prior_rain_interval_count" else count
            if not 0 <= int(row[field]) <= expected:
                raise ValueError(f"invalid observation count: {key}/{field}")
        for feature, count_field in FEATURE_COUNTS.items():
            if feature == "prior_24h_rain_mm" and row.get("prior_rain_policy"):
                from prior_rain_reconstruction import validate_provenance
                validate_provenance(row)
                continue
            expected = 144 if count_field == "prior_rain_interval_count" else count
            if present(row[feature]) != (int(row[count_field]) == expected):
                raise ValueError(f"feature completeness/count mismatch: {key}/{feature}")
        for model, columns in AI_FEATURES.items():
            missing = [column for column in columns if not present(row[column])]
            prefix = model.lower()
            policy = row.get("ai1_policy_exclusion", "") if model == "AI1" else ""
            if boolean(row[prefix + "_eligible"]) != (not missing and not policy):
                raise ValueError(f"{model} eligibility mismatch at {key}")
            if row[prefix + "_missing_features"] != "|".join(missing):
                raise ValueError(f"{model} missing reasons mismatch at {key}")
            for column in columns:
                if present(row[column]) and not math.isfinite(float(row[column])):
                    raise ValueError(f"nonfinite input: {key}/{column}")
