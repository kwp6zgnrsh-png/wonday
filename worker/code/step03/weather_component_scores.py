"""Compute all stations' daily rain, wind and heat risk from weather_clean.csv.

Only the Python standard library is required. See worker/docs/step03/README.md in the repository.
"""

import argparse
import csv
import json
import math
from bisect import bisect_right
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from tempfile import NamedTemporaryFile, TemporaryDirectory

STEP = timedelta(minutes=10)
WORKER_DIR = Path(__file__).resolve().parents[2]
DEFAULT_DATA_DIR = WORKER_DIR / "data" / "gyeonggi_processed"
DEFAULT_OUTPUT_DIR = DEFAULT_DATA_DIR / "step03"
REQUIRED_COLUMNS = (
    "station_code", "observed_at", "temperature_c", "humidity_pct", "wind_mps", "rain_10min_mm",
)
GUST_COLUMN = "gust_mps"
CALIBRATION_POLICY = "available_observations_within_training_window"


class CalibrationUnavailableError(ValueError):
    """There is no usable training sample; never fall back to held-out data."""


@dataclass(frozen=True, slots=True)
class Observation:
    rain: float | None
    wind: float | None
    gust: float | None
    apparent_temperature: float | None


def number(value: str, *, minimum=None, maximum=None) -> float | None:
    """Missing markers, nonfinite values and out-of-range measurements are missing."""
    value = value.strip()
    if value.upper() in {"", "-", "X", "NA", "N/A", "NONE", "NULL"}:
        return None
    result = float(value)
    if not math.isfinite(result):
        return None
    if minimum is not None and result < minimum:
        return None
    if maximum is not None and result > maximum:
        return None
    return result


def apparent_temperature(temperature: float | None, humidity: float | None) -> float | None:
    """KMA summer formula (2023), with Stull wet-bulb estimate; no intermediate rounding.

    Source: https://www.weather.go.kr/kma/news/press.jsp?mode=view&num=1194231
    RH is percent, temperatures are Celsius, atan uses radians. The +3.0 is required.
    """
    if temperature is None or humidity is None:
        return None
    if not math.isfinite(temperature) or not math.isfinite(humidity) or not 0 <= humidity <= 100:
        return None
    wet_bulb = (
        temperature * math.atan(0.151977 * math.sqrt(humidity + 8.313659))
        + math.atan(temperature + humidity) - math.atan(humidity - 1.676331)
        + 0.00391838 * humidity**1.5 * math.atan(0.023101 * humidity) - 4.686035
    )
    return (
        -0.2442 + 0.55399 * wet_bulb + 0.45535 * temperature
        - 0.0022 * wet_bulb**2 + 0.00278 * wet_bulb * temperature + 3.0
    )


def load_observations(path: Path, station: str) -> dict[datetime, Observation]:
    """Read one station only. rain_10min_mm is already an increment, never a counter."""
    points = {}
    with path.open(encoding="utf-8-sig", newline="") as source:
        reader = csv.reader(source)
        header = next(reader, [])
        if not set(REQUIRED_COLUMNS).issubset(header):
            raise ValueError(f"{path}: required columns: {', '.join(REQUIRED_COLUMNS)}")
        indices = [header.index(column) for column in REQUIRED_COLUMNS]
        gust_index = header.index(GUST_COLUMN) if GUST_COLUMN in header else None
        for row in reader:
            if not row:
                continue
            try:
                if row[indices[0]].strip() != station:
                    continue
                stamp = datetime.fromisoformat(row[indices[1]].strip())
                if stamp.tzinfo is not None or stamp.second or stamp.microsecond or stamp.minute % 10:
                    raise ValueError("observed_at must be a naive KST 10-minute interval end")
                point = Observation(
                    rain=number(row[indices[5]], minimum=0),
                    wind=number(row[indices[4]], minimum=0),
                    gust=number(row[gust_index], minimum=0) if gust_index is not None else None,
                    apparent_temperature=apparent_temperature(
                        number(row[indices[2]]), number(row[indices[3]], minimum=0, maximum=100),
                    ),
                )
                if points.setdefault(stamp, point) != point:
                    raise ValueError(f"conflicting duplicate at {stamp}")
            except (ValueError, IndexError) as exc:
                raise ValueError(f"{path}:{reader.line_num}: {exc}") from exc
    if not points:
        raise ValueError(f"station {station} not found in {path}")
    return points


def interpolate(value: float, anchors: list[tuple[float, float]]) -> float:
    """Clipped piecewise linear interpolation, rightmost score wins tied anchors."""
    unique = {}
    for x, y in anchors:
        unique[x] = max(y, unique.get(x, y))
    xs = sorted(unique)
    if value < xs[0]:
        return unique[xs[0]]
    position = bisect_right(xs, value)
    if position == len(xs):
        return unique[xs[-1]]
    left, right = xs[position - 1], xs[position]
    return unique[left] + (unique[right] - unique[left]) * (value - left) / (right - left)


def heat_intensity(value: float) -> float:
    # This intentional jump at 31 C follows the project specification.
    return 0.0 if value < 31 else interpolate(value, [(31, .25), (33, .5), (35, .75), (38, 1)])


def rolling_max(values: list[float | None], size: int) -> tuple[float | None, int]:
    """Only complete contiguous windows wholly inside the work shift qualify."""
    totals = []
    for end in range(size, len(values) + 1):
        window = values[end - size:end]
        if all(value is not None for value in window):
            totals.append(math.fsum(window))
    return (max(totals) if totals else None), len(totals)


def collect_features(
    points: dict[datetime, Observation], day: date, station: str,
    start_minute: int = 480, end_minute: int = 1020, mountain: bool = False,
) -> dict:
    if not 0 <= start_minute < end_minute <= 1440 or start_minute % 10 or end_minute % 10:
        raise ValueError("work shift must be within a day and aligned to 10 minutes")
    start = datetime.combine(day, time()) + timedelta(minutes=start_minute)
    interval_count = (end_minute - start_minute) // 10
    shift = [points.get(start + (i + 1) * STEP) for i in range(interval_count)]
    rain = [point.rain if point else None for point in shift]
    winds = [point.wind for point in shift if point and point.wind is not None]
    gusts = [point.gust for point in shift if point and point.gust is not None]
    heat = [point.apparent_temperature for point in shift if point and point.apparent_temperature is not None]
    prior = [points.get(start - i * STEP) for i in range(144)]
    prior_rain = [point.rain for point in prior if point and point.rain is not None]
    rain_count = sum(value is not None for value in rain)
    max_1h, windows_1h = rolling_max(rain, 6)
    max_3h, windows_3h = rolling_max(rain, 18)
    mean_limit, gust_limit = (24, 30) if mountain else (21, 26)
    wind_warning = 17 if mountain else 14
    max_mean = max(winds, default=None)
    max_gust = max(gusts, default=None)
    max_heat = max(heat, default=None)
    return {
        "work_date": day.isoformat(), "station_code": station, "is_mountain": mountain,
        "work_start": f"{start_minute // 60:02}:{start_minute % 60:02}",
        "work_end": f"{end_minute // 60:02}:{end_minute % 60:02}",
        "scheduled_minutes": end_minute - start_minute,
        "work_observation_count": sum(point is not None for point in shift),
        "work_rain_interval_count": rain_count, "prior_rain_interval_count": len(prior_rain),
        "work_wind_interval_count": len(winds), "work_heat_interval_count": len(heat),
        "work_gust_interval_count": len(gusts),
        "gust_evaluation_status": "complete" if len(gusts) == interval_count else "partial" if gusts else "unavailable",
        "work_rain_mm": math.fsum(rain) if rain_count == interval_count else None,
        "prior_24h_rain_mm": math.fsum(prior_rain) if len(prior_rain) == 144 else None,
        "work_max_1h_rain_mm": max_1h, "work_max_3h_rain_mm": max_3h,
        "valid_1h_window_count": windows_1h, "valid_3h_window_count": windows_3h,
        "work_max_wind_mps": max_mean, "work_max_gust_mps": max_gust,
        "work_strong_wind_minutes": 10 * sum(value >= wind_warning for value in winds),
        "work_max_apparent_temp_c": max_heat,
        "work_heat_minutes_ge31": 10 * sum(value >= 31 for value in heat),
        "rain_full_score": (max_1h is not None and max_1h >= 72) or (max_3h is not None and max_3h >= 90),
        "wind_full_score": (max_mean is not None and max_mean >= mean_limit) or (max_gust is not None and max_gust >= gust_limit),
        "heat_full_score": max_heat is not None and max_heat >= 38,
    }


def quantiles(values: list[float]) -> dict:
    positive = sorted(value for value in values if value > 0)
    if not positive:
        raise ValueError("training data has no positive rainfall; cannot fit rainfall quantiles")
    result = {"positive_sample_count": len(positive)}
    for name, probability in (("p50", .5), ("p90", .9), ("p99", .99)):
        index = (len(positive) - 1) * probability
        lower = int(index)
        fraction = index - lower
        result[name] = positive[lower] + fraction * (positive[min(lower + 1, len(positive) - 1)] - positive[lower])
    return result


def validate_training_dates(start: date, end: date) -> None:
    if start > end:
        raise ValueError("training start must not follow training end")


def fit_calibration(rows: list[dict], start: date, end: date) -> dict:
    validate_training_dates(start, end)
    training = [row for row in rows if start.isoformat() <= row["work_date"] <= end.isoformat()]
    observed = sorted(row["work_date"] for row in training if row["work_observation_count"] > 0)
    if not observed:
        raise CalibrationUnavailableError("no_training_observations")
    bounds = {}
    for key in ("work_rain_mm", "prior_24h_rain_mm"):
        valid = [row for row in training if row[key] is not None]
        if not any(row[key] > 0 for row in valid):
            raise CalibrationUnavailableError(f"no_positive_{key}")
        bounds[key] = {
            **quantiles([row[key] for row in valid]),
            "valid_sample_count": len(valid),
            "first_valid_date": min(row["work_date"] for row in valid),
            "last_valid_date": max(row["work_date"] for row in valid),
        }
    return {
        "schema_version": 2,
        "fit_policy": CALIBRATION_POLICY,
        "station_code": training[0]["station_code"],
        "work_start": training[0]["work_start"], "work_end": training[0]["work_end"],
        "train_start": start.isoformat(), "train_end": end.isoformat(),
        "actual_train_start": observed[0], "actual_train_end": observed[-1],
        "training_observed_days": len(set(observed)),
        "training_calendar_days": (end - start).days + 1,
        "training_history_status": "full_window" if observed[0] == start.isoformat() and observed[-1] == end.isoformat() else "partial_window",
        "quantile_method": "linear (n-1)*p; positive complete totals only; tied anchors use highest score",
        **bounds,
    }


def validate_calibration(model: dict, first_row: dict, start: date, end: date) -> None:
    validate_training_dates(start, end)
    if not isinstance(model, dict):
        raise ValueError("calibration must be a JSON object")
    expected = {
        "station_code": first_row["station_code"],
        "work_start": first_row["work_start"], "work_end": first_row["work_end"],
        "train_start": start.isoformat(), "train_end": end.isoformat(),
    }
    for key, value in expected.items():
        if model.get(key) != value:
            raise ValueError(f"calibration {key} mismatch: expected {value!r}; choose a matching calibration file")
    if model.get("schema_version") not in (1, 2):
        raise ValueError("unsupported calibration schema version")
    if model["schema_version"] == 2:
        if model.get("fit_policy") != CALIBRATION_POLICY:
            raise ValueError("calibration fit policy mismatch")
        actual_start = date.fromisoformat(model["actual_train_start"])
        actual_end = date.fromisoformat(model["actual_train_end"])
        if not start <= actual_start <= actual_end <= end:
            raise ValueError("actual calibration dates must stay inside the training window")
    for key in ("work_rain_mm", "prior_24h_rain_mm"):
        bounds = model.get(key, {})
        anchors = [bounds.get(name) for name in ("p50", "p90", "p99")]
        if not all(isinstance(x, (int, float)) and math.isfinite(x) and x > 0 for x in anchors):
            raise ValueError(f"invalid positive calibration quantiles: {key}")
        if anchors != sorted(anchors):
            raise ValueError(f"calibration quantiles must be nondecreasing: {key}")


def rain_score(value: float | None, bounds: dict) -> float | None:
    if value is None:
        return None
    if value <= 0:
        return 0.0
    return interpolate(value, [(0, 0), (bounds["p50"], .25), (bounds["p90"], .75), (bounds["p99"], 1)])


def score_features(features: dict, calibration: dict) -> dict:
    row = dict(features)
    count = row["scheduled_minutes"] // 10
    def calibrated_rain(key):
        bounds = calibration.get(key)
        value = row[key]
        # Even without historical quantiles, zero rain is unambiguously zero.
        return rain_score(value, bounds) if bounds else 0.0 if value == 0 else None

    work_rain = calibrated_rain("work_rain_mm")
    prior_rain = calibrated_rain("prior_24h_rain_mm")
    rain_complete = work_rain is not None and prior_rain is not None
    sr = 1.0 if row["rain_full_score"] else .7 * work_rain + .3 * prior_rain if rain_complete else None
    mean_warning, mean_limit, gust_warning, gust_limit = (17, 24, 25, 30) if row["is_mountain"] else (14, 21, 20, 26)
    intensities = [
        interpolate(value, [(0, 0), (warning, .7), (limit, 1)])
        for value, warning, limit in (
            (row["work_max_wind_mps"], mean_warning, mean_limit),
            (row["work_max_gust_mps"], gust_warning, gust_limit),
        ) if value is not None
    ]
    wind_intensity = max(intensities, default=None)
    wind_duration = row["work_strong_wind_minutes"] / row["scheduled_minutes"] if row["work_wind_interval_count"] == count else None
    sw = 1.0 if row["wind_full_score"] else .75 * wind_intensity + .25 * wind_duration if wind_duration is not None else None
    heat_max = row["work_max_apparent_temp_c"]
    heat_peak = heat_intensity(heat_max) if heat_max is not None else None
    heat_duration = min(row["work_heat_minutes_ge31"] / (.75 * row["scheduled_minutes"]), 1.0) if row["work_heat_interval_count"] == count else None
    sh = 1.0 if row["heat_full_score"] else .6 * heat_peak + .4 * heat_duration if heat_duration is not None else None
    row.update({
        "rain_work_subscore": work_rain, "rain_prior_subscore": prior_rain,
        "wind_intensity_subscore": wind_intensity, "wind_duration_subscore": wind_duration,
        "heat_intensity_subscore": heat_peak, "heat_exposure_subscore": heat_duration,
        "rain_score_status": "override" if row["rain_full_score"] else "ok" if sr is not None else "missing_data" if row["work_rain_mm"] is None or row["prior_24h_rain_mm"] is None else "missing_calibration",
        "wind_score_status": "override" if row["wind_full_score"] else "ok" if sw is not None else "missing_data",
        "heat_score_status": "override" if row["heat_full_score"] else "ok" if sh is not None else "missing_data",
        # Only these final scores are on the 0..100 scale; subscores stay on 0..1.
        "S_R": None if sr is None else 100 * sr,
        "S_W": None if sw is None else 100 * sw,
        "S_H": None if sh is None else 100 * sh,
        "period": "train" if calibration["train_start"] <= row["work_date"] <= calibration["train_end"] else "validation" if row["work_date"] > calibration["train_end"] else "before_train",
        "rain_train_start": calibration["train_start"], "rain_train_end": calibration["train_end"],
        "rain_actual_train_start": calibration.get("actual_train_start", ""),
        "rain_actual_train_end": calibration.get("actual_train_end", ""),
        "rain_training_history_status": calibration.get("training_history_status", "unavailable"),
        "component_scores_complete": all(value is not None for value in (sr, sw, sh)),
    })
    return row


def atomic_write(path: Path, write) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with NamedTemporaryFile("w", encoding="utf-8-sig", newline="", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            write(stream)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def parse_clock(value: str) -> int:
    try:
        hour, minute = map(int, value.split(":"))
        if not 0 <= hour <= 24 or not 0 <= minute < 60 or (hour == 24 and minute != 0) or minute % 10:
            raise ValueError()
        return 60 * hour + minute
    except ValueError as exc:
        raise argparse.ArgumentTypeError("use HH:MM on a 10-minute boundary (00:00 to 24:00)") from exc


def partition_stations(source_path: Path, folder: Path, selected: set[str] | None = None) -> dict:
    """Read the source once; disk partitions keep memory bounded to one station.

    Filenames use generated numbers, never station codes. Input order is arbitrary.
    At most 32 partition streams are kept open at once.
    """
    from collections import OrderedDict
    partitions, opened = {}, OrderedDict()
    try:
        with source_path.open(encoding="utf-8-sig", newline="") as source:
            reader = csv.reader(source)
            header = next(reader, [])
            if not set(REQUIRED_COLUMNS).issubset(header):
                raise ValueError("input is missing required 10-minute weather columns")
            station_index = header.index("station_code")
            name_index = header.index("station_name") if "station_name" in header else None
            for row in reader:
                if not row:
                    continue
                if len(row) != len(header):
                    raise ValueError(f"{source_path}:{reader.line_num}: wrong column count")
                station = row[station_index].strip()
                if not station:
                    raise ValueError(f"{source_path}:{reader.line_num}: empty station code")
                if not station.isascii() or not all(c.isalnum() or c in "_-" for c in station):
                    raise ValueError(f"{source_path}:{reader.line_num}: unsafe station code {station!r}")
                if selected and station not in selected:
                    continue
                if station not in partitions:
                    path = folder / f"station_{len(partitions):04}.csv"
                    partitions[station] = (path, row[name_index].strip() if name_index is not None else "")
                    with path.open("w", encoding="utf-8", newline="") as stream:
                        csv.writer(stream).writerow(header)
                if station not in opened:
                    if len(opened) >= 32:
                        _, stream = opened.popitem(last=False)
                        stream.close()
                    opened[station] = partitions[station][0].open("a", encoding="utf-8", newline="")
                opened.move_to_end(station)
                csv.writer(opened[station]).writerow(row)
    finally:
        for stream in opened.values():
            stream.close()
    if not partitions:
        raise ValueError("no selected stations found in input")
    if selected and selected - partitions.keys():
        raise ValueError(f"stations not found: {sorted(selected - partitions.keys())}")
    return partitions


def station_calibration(rows, args, path):
    if path.exists() and not args.refit_calibration:
        with path.open(encoding="utf-8-sig") as stream:
            model = json.load(stream)
        validate_calibration(model, rows[0], args.train_start, args.train_end)
        if model["schema_version"] == 2:
            return model, "reused", ""
        print(f"Upgrading legacy calibration from training observations only: {path}", flush=True)
    try:
        model = fit_calibration(rows, args.train_start, args.train_end)
    except CalibrationUnavailableError as exc:
        # Do not substitute another station or fit on held-out years.
        return {"train_start": args.train_start.isoformat(), "train_end": args.train_end.isoformat()}, "unavailable", str(exc)
    atomic_write(path, lambda stream: json.dump(model, stream, ensure_ascii=False, indent=2, allow_nan=False))
    return model, "fitted", ""


def run_pipeline(args):
    from validate_weather_scores import audit_validation, quantile_diagnostics

    selected = set(args.station_code) if args.station_code else None
    summaries = []
    distributions = []
    validation_end = args.validation_end
    print(f"Partitioning stations from {args.input}", flush=True)
    with TemporaryDirectory(prefix="weather_stations_") as temporary:
        partitions = partition_stations(args.input, Path(temporary), selected)
        unknown_mountains = set(args.mountain_station) - partitions.keys()
        if unknown_mountains:
            raise ValueError(f"mountain stations not in selected input: {sorted(unknown_mountains)}")

        def write_output(stream):
            writer = None
            for index, (station, (path, name)) in enumerate(sorted(partitions.items()), 1):
                print(f"[{index}/{len(partitions)}] {station} {name}", flush=True)
                points = load_observations(path, station)
                first, last = (min(points) - STEP).date(), (max(points) - STEP).date()
                mountain = args.mountain or station in args.mountain_station
                rows = [collect_features(points, first + timedelta(days=i), station,
                                         args.work_start, args.work_end, mountain)
                        for i in range((last - first).days + 1)]
                calibration_path = args.calibration or args.calibration_dir / f"rain_calibration_{station}.json"
                model, status, reason = station_calibration(rows, args, calibration_path)
                if status == "reused":
                    print(f"Reusing calibration: {calibration_path}", flush=True)
                elif status == "unavailable":
                    print(f"Rain calibration unavailable: {reason}; wind/heat and rain overrides retained", flush=True)
                scored = []
                for row in rows:
                    result = score_features(row, model)
                    if result["work_date"] > validation_end.isoformat():
                        result["period"] = "after_validation"
                    result.update(station_name=name, rain_calibration_status=status,
                                  rain_calibration_reason=reason)
                    scored.append(result)
                audit = audit_validation(points, scored, model)
                distributions.extend(quantile_diagnostics(scored, model))
                if audit["mismatch_count"]:
                    raise ValueError(f"{station}: validation audit failed: {audit}")
                if len({r["work_date"] for r in scored}) != len(scored):
                    raise ValueError(f"{station}: duplicate work dates")
                for row in scored:
                    for prefix, key in (("rain", "S_R"), ("wind", "S_W"), ("heat", "S_H")):
                        value = row[key]
                        if value is not None and not 0 <= value <= 100:
                            raise ValueError(f"{station}: {key} outside 0..100")
                        if row[prefix + "_full_score"] and value != 100:
                            raise ValueError(f"{station}: {key} override mismatch")
                    if writer is None:
                        writer = csv.DictWriter(stream, fieldnames=list(row))
                        writer.writeheader()
                    writer.writerow({k: round(v, 6) if isinstance(v, float) else v for k, v in row.items()})
                summary = {
                    "station_code": station, "station_name": name, "is_mountain": mountain,
                    "first_date": first.isoformat(), "last_date": last.isoformat(),
                    "workday_count": len(scored), "rain_calibration_status": status,
                    "rain_calibration_reason": reason,
                    "rain_actual_train_start": model.get("actual_train_start", ""),
                    "rain_actual_train_end": model.get("actual_train_end", ""),
                    "rain_training_history_status": model.get("training_history_status", "unavailable"),
                    "training_observed_days": model.get("training_observed_days", 0),
                    **{f"{key}_training_positive_count": model.get(key, {}).get("positive_sample_count", 0)
                       for key in ("work_rain_mm", "prior_24h_rain_mm")},
                    **{f"{period}_component_complete_days": sum(r["period"] == period and r["component_scores_complete"] for r in scored)
                       for period in ("train", "validation", "after_validation")},
                    **{f"{period}_days": sum(r["period"] == period for r in scored)
                       for period in ("train", "validation", "after_validation")},
                    **audit,
                }
                for key in ("S_R", "S_W", "S_H"):
                    for period in ("train", "validation", "after_validation"):
                        values = [r[key] for r in scored if r["period"] == period and r[key] is not None]
                        prefix = f"{period}_{key}"
                        summary.update({prefix + "_valid": len(values),
                                        prefix + "_mean": round(math.fsum(values) / len(values), 6) if values else None,
                                        prefix + "_max": max(values) if values else None,
                                        prefix + "_full": sum(v == 100 for v in values)})
                summaries.append(summary)

        atomic_write(args.output, write_output)
    # Preserve every row (including missing scores) for STEP04. No IF is fitted here.
    for period, destination in (("train", args.train_output), ("validation", args.heldout_output)):
        def write_split(stream, selected_period=period):
            with args.output.open(encoding="utf-8-sig", newline="") as source:
                reader = csv.DictReader(source)
                writer = csv.DictWriter(stream, fieldnames=reader.fieldnames)
                writer.writeheader()
                for row in reader:
                    if row["period"] == selected_period:
                        writer.writerow(row)
        atomic_write(destination, write_split)
    def write_summary(stream):
        writer = csv.DictWriter(stream, fieldnames=list(summaries[0]))
        writer.writeheader()
        writer.writerows(summaries)
    atomic_write(args.summary, write_summary)
    def write_distributions(stream):
        writer = csv.DictWriter(stream, fieldnames=list(distributions[0]))
        writer.writeheader()
        writer.writerows(distributions)
    atomic_write(args.quantile_report, write_distributions)
    report = {
        "train_start": args.train_start.isoformat(), "train_end": args.train_end.isoformat(),
        "validation_start": (args.train_end + timedelta(days=1)).isoformat(),
        "validation_end": validation_end.isoformat(), "station_count": len(summaries),
        "workday_count": sum(s["workday_count"] for s in summaries),
        "validation_days": sum(s["validation_days"] for s in summaries),
        "independently_checked_days": sum(s["checked_days"] for s in summaries),
        "mismatch_count": sum(s["mismatch_count"] for s in summaries),
        "calibration_unavailable_stations": [s["station_code"] for s in summaries if s["rain_calibration_status"] == "unavailable"],
        "calibration_policy": CALIBRATION_POLICY,
        "calibration_available_station_count": sum(s["rain_calibration_status"] != "unavailable" for s in summaries),
        "partial_history_stations": [s["station_code"] for s in summaries if s["rain_training_history_status"] == "partial_window"],
        "train_rows": sum(s["train_days"] for s in summaries),
        "train_component_complete_rows": sum(s["train_component_complete_days"] for s in summaries),
        "heldout_component_complete_rows": sum(s["validation_component_complete_days"] for s in summaries),
        "train_output": args.train_output.name, "heldout_output": args.heldout_output.name,
        "heldout_role": "Reserved for downstream Isolation Forest evaluation; never used to fit STEP03 cutoffs.",
        "distribution_checks_gate_scoring": False,
        "scope": "Implementation and held-out weather-data audit; no damage labels, so no predictive accuracy claim.",
        "quantile_distribution_report": args.quantile_report.name,
        "quantile_interpretation": "Held-out distribution diagnostics, not an accuracy or pass/fail claim. Rainfall ties affect exceedance rates.",
        "output_unique_station_date_pairs": sum(s["workday_count"] for s in summaries),
        "output_score_range_and_override_check": "passed",
    }
    atomic_write(args.validation_report, lambda stream: json.dump(report, stream, ensure_ascii=False, indent=2))
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_DATA_DIR / "weather_clean.csv")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT_DIR / "weather_component_scores.csv")
    parser.add_argument("--station-code", action="append", help="optional; repeat for selected stations; default: all stations")
    parser.add_argument("--work-start", type=parse_clock, default=480)
    parser.add_argument("--work-end", type=parse_clock, default=1020)
    parser.add_argument("--mountain", action="store_true", help="single-station mode only")
    parser.add_argument("--mountain-station", action="append", default=[], help="repeat for stations known to use mountain thresholds")
    parser.add_argument("--train-start", type=date.fromisoformat, default=date(2016, 1, 1))
    parser.add_argument("--train-end", type=date.fromisoformat, default=date(2023, 12, 31))
    parser.add_argument("--validation-end", type=date.fromisoformat, help="default: two years after training end")
    parser.add_argument("--calibration", type=Path, help="explicit JSON; single-station mode only")
    parser.add_argument("--calibration-dir", type=Path, help="default: rain_calibrations beside output")
    parser.add_argument("--summary", type=Path, help="default: weather_component_station_summary.csv beside output")
    parser.add_argument("--validation-report", type=Path, help="default: weather_component_validation.json beside output")
    parser.add_argument("--quantile-report", type=Path, help="default: weather_rain_quantile_validation.csv beside output")
    parser.add_argument("--train-output", type=Path, help="STEP04 training input; default: weather_component_train.csv")
    parser.add_argument("--heldout-output", type=Path, help="STEP04 held-out input; default: weather_component_holdout.csv")
    parser.add_argument("--refit-calibration", action="store_true")
    args = parser.parse_args()
    if not 0 <= args.work_start < args.work_end <= 1440:
        parser.error("work-start must precede work-end within the same day")
    if (args.calibration or args.mountain) and len(set(args.station_code or [])) != 1:
        parser.error("--calibration and --mountain require exactly one --station-code")
    args.calibration_dir = args.calibration_dir or args.output.parent / "rain_calibrations"
    args.summary = args.summary or args.output.with_name("weather_component_station_summary.csv")
    args.validation_report = args.validation_report or args.output.with_name("weather_component_validation.json")
    args.quantile_report = args.quantile_report or args.output.with_name("weather_rain_quantile_validation.csv")
    args.train_output = args.train_output or args.output.with_name("weather_component_train.csv")
    args.heldout_output = args.heldout_output or args.output.with_name("weather_component_holdout.csv")
    try:
        args.validation_end = args.validation_end or args.train_end.replace(year=args.train_end.year + 2)
        validate_training_dates(args.train_start, args.train_end)
        if args.validation_end <= args.train_end:
            raise ValueError("validation end must follow training end")
        paths = [args.input, args.output, args.summary, args.validation_report, args.quantile_report,
                 args.train_output, args.heldout_output]
        if args.calibration:
            paths.append(args.calibration)
        if len(set(p.resolve() for p in paths)) != len(paths):
            raise ValueError("input and all output files must have different paths")
        if args.calibration_dir.resolve() in [p.resolve() for p in paths]:
            raise ValueError("calibration-dir must be a directory separate from all files")
        run_pipeline(args)
    except (OSError, ValueError, csv.Error, KeyError, TypeError) as exc:
        parser.exit(1, f"Error: {exc}\n")


if __name__ == "__main__":
    main()
