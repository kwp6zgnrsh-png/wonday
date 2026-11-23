"""Aggregate 10-minute agricultural weather CSVs into 08:00 to 17:00 workdays."""

import argparse
import csv
import math
import statistics
from collections.abc import Iterator
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from tempfile import NamedTemporaryFile

DEFAULT_STATION = "477802A001"  # 경기도 가평군 가평읍
STEP_MINUTES = 10
DAY_END = 24 * 60
WORK_START = 8 * 60
WORK_END = 17 * 60
WORK_INTERVALS = (WORK_END - WORK_START) // STEP_MINUTES
PRIOR_INTERVALS = DAY_END // STEP_MINUTES

INPUT_COLUMNS = (
    "station_code", "날짜", "기온( ℃ )", "습도( % )", "풍속( m/s )", "강수량( mm )",
)
OUTPUT_COLUMNS = (
    "work_date",
    "station_code",
    "work_rain_mm",
    "prior_24h_rain_mm",
    "work_max_temp_c",
    "work_mean_humidity_pct",
    "work_max_wind_mps",
    "work_rain_minutes",
    "work_observation_count",
    "work_rain_interval_count",
    "prior_rain_interval_count",
)


@dataclass(frozen=True, slots=True)
class Observation:
    temperature: float | None
    humidity: float | None
    wind: float | None
    daily_rain: float | None


def parse_number(value: str) -> float | None:
    value = value.strip()
    if value.upper() in {"", "-", "X"}:
        return None
    number = float(value)
    return number if math.isfinite(number) else None


def parse_timestamp(value: str) -> tuple[date, int]:
    # Keep 24:00 with the source date: its cumulative rain belongs to that day.
    day_text, time_text = value.strip().split()
    hour, minute = map(int, time_text.split(":"))
    end_minute = 60 * hour + minute
    if (
        not 0 <= hour <= 24
        or not 0 <= minute < 60
        or not 0 < end_minute <= DAY_END
        or end_minute % STEP_MINUTES
    ):
        raise ValueError(f"invalid 10-minute interval end: {value!r}")
    return date.fromisoformat(day_text), end_minute


def load_station(files: list[Path], station_code: str) -> dict[date, dict[int, Observation]]:
    by_day: dict[date, dict[int, Observation]] = {}
    for path in files:
        with path.open(encoding="utf-8-sig", newline="") as source:
            reader = csv.reader(source)
            header = next(reader, [])
            if any(column not in header for column in INPUT_COLUMNS):
                raise ValueError(f"{path.name}: required weather columns are missing")
            station, timestamp, temperature, humidity, wind, rain = (
                header.index(column) for column in INPUT_COLUMNS
            )
            for row in reader:
                if not row:
                    continue
                try:
                    if row[station].strip() != station_code:
                        continue
                    day, end_minute = parse_timestamp(row[timestamp])
                    observation = Observation(
                        parse_number(row[temperature]),
                        parse_number(row[humidity]),
                        parse_number(row[wind]),
                        parse_number(row[rain]),
                    )
                except (ValueError, IndexError) as exc:
                    raise ValueError(f"{path.name}:{reader.line_num}: {exc}") from exc
                readings = by_day.setdefault(day, {})
                if readings.setdefault(end_minute, observation) != observation:
                    raise ValueError(
                        f"{path.name}:{reader.line_num}: conflicting records for "
                        f"{station_code} at {row[timestamp]}"
                    )
    if not by_day:
        raise ValueError(f"station {station_code} was not found in the input files")
    return by_day


def rain_increments(
    observations: dict[int, Observation], start: int, end: int
) -> Iterator[float]:
    # Daily accumulation starts at zero at midnight. Later windows need a real baseline.
    baseline = observations.get(start)
    previous = 0.0 if start == 0 else (baseline.daily_rain if baseline else None)
    for minute in range(start + STEP_MINUTES, end + 1, STEP_MINUTES):
        observation = observations.get(minute)
        current = observation.daily_rain if observation else None
        if previous is not None and current is not None and 0 <= previous <= current:
            yield current - previous
        previous = current


def summarize_workday(
    day: date, station_code: str, today: dict[int, Observation], yesterday: dict[int, Observation]
) -> tuple:
    shift = [
        today[minute]
        for minute in range(WORK_START + STEP_MINUTES, WORK_END + 1, STEP_MINUTES)
        if minute in today
    ]
    work_rain = list(rain_increments(today, WORK_START, WORK_END))
    prior_rain = [
        *rain_increments(yesterday, WORK_START, DAY_END),
        *rain_increments(today, 0, WORK_START),
    ]
    temperatures = [point.temperature for point in shift if point.temperature is not None]
    humidities = [
        point.humidity for point in shift if point.humidity is not None and 0 <= point.humidity <= 100
    ]
    winds = [point.wind for point in shift if point.wind is not None and point.wind >= 0]
    return (
        day.isoformat(),
        station_code,
        round(math.fsum(work_rain), 3) if len(work_rain) == WORK_INTERVALS else None,
        round(math.fsum(prior_rain), 3) if len(prior_rain) == PRIOR_INTERVALS else None,
        max(temperatures) if len(temperatures) == WORK_INTERVALS else None,
        round(statistics.fmean(humidities), 3) if len(humidities) == WORK_INTERVALS else None,
        max(winds) if len(winds) == WORK_INTERVALS else None,
        STEP_MINUTES * sum(value > 0 for value in work_rain)
        if len(work_rain) == WORK_INTERVALS
        else None,
        len(shift),
        len(work_rain),
        len(prior_rain),
    )


def write_workdays(
    output: Path, by_day: dict[date, dict[int, Observation]], station_code: str
) -> int:
    start, end = min(by_day), max(by_day)
    day_count = (end - start).days + 1
    one_day = timedelta(days=1)
    # Write beside the destination so a failed run leaves an existing CSV intact.
    temp_path = None
    try:
        with NamedTemporaryFile(
            mode="w", encoding="utf-8-sig", newline="", dir=output.parent, delete=False
        ) as destination:
            temp_path = Path(destination.name)
            writer = csv.writer(destination)
            writer.writerow(OUTPUT_COLUMNS)
            for offset in range(day_count):
                day = start + timedelta(days=offset)
                writer.writerow(
                    summarize_workday(day, station_code, by_day.get(day, {}), by_day.get(day - one_day, {}))
                )
        temp_path.replace(output)
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)
    return day_count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", type=Path, required=True, help="folder of raw CSV files")
    parser.add_argument("--output", type=Path, required=True, help="destination CSV path")
    parser.add_argument("--station-code", default=DEFAULT_STATION, help="default: Gapyeong-eup")
    args = parser.parse_args()

    try:
        files = sorted(
            path for path in args.input_dir.glob("*.csv") if path.is_file()
        )
        if not files:
            raise ValueError(f"no CSV files in {args.input_dir}")
        if args.output.resolve() in {path.resolve() for path in files}:
            raise ValueError("choose an output path outside the raw CSV files")
        by_day = load_station(files, args.station_code)
        count = write_workdays(args.output, by_day, args.station_code)
    except (OSError, ValueError, csv.Error) as exc:
        parser.exit(1, f"Error: {exc}\n")
    print(f"{args.station_code}: {count:,} workdays from {len(files):,} CSV files")
    print(args.output.resolve())


if __name__ == "__main__":
    main()
