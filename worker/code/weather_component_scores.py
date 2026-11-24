"""Compute daily rain, wind and heat risk from STEP01's 10-minute weather_clean.csv.

Only the Python standard library is required. See README_weather_component_scores.md.
"""

import argparse
import csv
import json
import math
from bisect import bisect_right
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from pathlib import Path
from tempfile import NamedTemporaryFile

STEP = timedelta(minutes=10)
DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "gyeonggi_processed"
DEFAULT_STATION = "477802A001"
REQUIRED_COLUMNS = (
    "station_code", "observed_at", "temperature_c", "humidity_pct", "wind_mps", "rain_10min_mm",
)
GUST_COLUMN = "gust_mps"


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
    try:
        anniversary = start.replace(year=start.year + 8)
    except ValueError:
        anniversary = start.replace(year=start.year + 8, day=28)
    if end + timedelta(days=1) != anniversary:
        raise ValueError("training period must be exactly 8 calendar years (inclusive end)")


def fit_calibration(rows: list[dict], start: date, end: date) -> dict:
    validate_training_dates(start, end)
    training = [row for row in rows if start.isoformat() <= row["work_date"] <= end.isoformat()]
    if not training or training[0]["work_date"] != start.isoformat() or training[-1]["work_date"] != end.isoformat():
        raise ValueError("input must cover the entire requested 8-year training period")
    return {
        "schema_version": 1,
        "station_code": training[0]["station_code"],
        "work_start": training[0]["work_start"], "work_end": training[0]["work_end"],
        "train_start": start.isoformat(), "train_end": end.isoformat(),
        "quantile_method": "linear (n-1)*p; positive complete totals only; tied anchors use highest score",
        **{key: quantiles([row[key] for row in training if row[key] is not None])
           for key in ("work_rain_mm", "prior_24h_rain_mm")},
    }


def validate_calibration(model: dict, first_row: dict, start: date, end: date) -> None:
    validate_training_dates(start, end)
    if not isinstance(model, dict):
        raise ValueError("calibration must be a JSON object")
    expected = {
        "schema_version": 1, "station_code": first_row["station_code"],
        "work_start": first_row["work_start"], "work_end": first_row["work_end"],
        "train_start": start.isoformat(), "train_end": end.isoformat(),
    }
    for key, value in expected.items():
        if model.get(key) != value:
            raise ValueError(f"calibration {key} mismatch: expected {value!r}; choose a matching calibration file")
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
    work_rain = rain_score(row["work_rain_mm"], calibration["work_rain_mm"])
    prior_rain = rain_score(row["prior_24h_rain_mm"], calibration["prior_24h_rain_mm"])
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
        "rain_score_status": "override" if row["rain_full_score"] else "ok" if sr is not None else "missing_data",
        "wind_score_status": "override" if row["wind_full_score"] else "ok" if sw is not None else "missing_data",
        "heat_score_status": "override" if row["heat_full_score"] else "ok" if sh is not None else "missing_data",
        # Only these final scores are on the 0..100 scale; subscores stay on 0..1.
        "S_R": None if sr is None else 100 * sr,
        "S_W": None if sw is None else 100 * sw,
        "S_H": None if sh is None else 100 * sh,
        "period": "train" if calibration["train_start"] <= row["work_date"] <= calibration["train_end"] else "validation" if row["work_date"] > calibration["train_end"] else "before_train",
        "rain_train_start": calibration["train_start"], "rain_train_end": calibration["train_end"],
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=DEFAULT_DATA_DIR / "weather_clean.csv")
    parser.add_argument("--output", type=Path, default=DEFAULT_DATA_DIR / "weather_component_scores.csv")
    parser.add_argument("--station-code", default=DEFAULT_STATION)
    parser.add_argument("--work-start", type=parse_clock, default=480)
    parser.add_argument("--work-end", type=parse_clock, default=1020)
    parser.add_argument("--mountain", action="store_true", help="explicitly use mountain wind thresholds")
    parser.add_argument("--train-start", type=date.fromisoformat, default=date(2016, 1, 1))
    parser.add_argument("--train-end", type=date.fromisoformat, default=date(2023, 12, 31))
    parser.add_argument("--calibration", type=Path, help="saved rain quantiles (default: rain_calibration_<station>.json beside output)")
    parser.add_argument("--refit-calibration", action="store_true", help="explicitly replace saved calibration using only the training period")
    args = parser.parse_args()
    if not 0 <= args.work_start < args.work_end <= 1440:
        parser.error("work-start must precede work-end within the same day")
    calibration_path = args.calibration or args.output.with_name(f"rain_calibration_{args.station_code}.json")
    if len({args.input.resolve(), args.output.resolve(), calibration_path.resolve()}) != 3:
        parser.error("input, output and calibration must have different paths")
    try:
        validate_training_dates(args.train_start, args.train_end)
        print(f"Reading station {args.station_code}: {args.input}", flush=True)
        points = load_observations(args.input, args.station_code)
        start_day = (min(points) - STEP).date()
        end_day = (max(points) - STEP).date()
        rows = [collect_features(points, start_day + timedelta(days=i), args.station_code,
                                 args.work_start, args.work_end, args.mountain)
                for i in range((end_day - start_day).days + 1)]
        if calibration_path.exists() and not args.refit_calibration:
            with calibration_path.open(encoding="utf-8-sig") as stream:
                calibration = json.load(stream)
            print(f"Reusing calibration: {calibration_path}", flush=True)
        else:
            calibration = fit_calibration(rows, args.train_start, args.train_end)
            print(f"Fitting calibration: {args.train_start} to {args.train_end}", flush=True)
        validate_calibration(calibration, rows[0], args.train_start, args.train_end)
        scored = [score_features(row, calibration) for row in rows]
        if not calibration_path.exists() or args.refit_calibration:
            atomic_write(calibration_path, lambda stream: json.dump(calibration, stream, ensure_ascii=False, indent=2, allow_nan=False))

        def write_csv(stream):
            writer = csv.DictWriter(stream, fieldnames=list(scored[0]))
            writer.writeheader()
            for row in scored:
                writer.writerow({key: round(value, 6) if isinstance(value, float) else value for key, value in row.items()})

        atomic_write(args.output, write_csv)
    except (OSError, ValueError, csv.Error, KeyError, TypeError) as exc:
        parser.exit(1, f"Error: {exc}\n")
    print(f"Saved {len(scored):,} workdays: {args.output.resolve()}")
    for score in ("S_R", "S_W", "S_H"):
        print(f"{score}: {sum(row[score] is not None for row in scored):,} valid, "
              f"{sum(row[score] == 100 for row in scored):,} full-score days")


if __name__ == "__main__":
    main()
