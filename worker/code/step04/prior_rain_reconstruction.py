"""Conservative prior-rain reconstruction; never edits ten-minute observations."""

import csv
import math
from datetime import date, datetime, time, timedelta
from pathlib import Path

POLICY = "official_daily_midnight_only_v1"
TOLERANCE_MM = 0.001
COLUMNS = (
    "prior_rain_policy", "prior_rain_source", "prior_rain_usable", "prior_rain_status",
    "prior_rain_hold_reason", "prior_rain_original_mm", "prior_rain_candidate_mm",
    "prior_rain_other_interval_count", "prior_rain_partial_sum_mm", "prior_rain_missing_interval_count",
    "prior_rain_cumulative_prev_start_mm", "prior_rain_cumulative_start_mm",
    "prior_rain_cumulative_prev_2350_mm", "prior_rain_last_10min_candidate_mm",
    "prior_rain_official_date", "prior_rain_official_mm", "prior_rain_official_status",
    "prior_rain_official_queries", "prior_rain_reconstruction_eligible",
    "ai1_station_supported", "ai1_policy_exclusion",
)


def value(text):
    if text is None or text == "":
        return None
    try:
        result = float(text)
    except (ValueError, TypeError):
        return None
    return result if math.isfinite(result) and result >= 0 else None


def load_daily(path):
    """The curated daily table must have unique station/date keys, including invalid rows."""
    records = {}
    with Path(path).open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        required = {"station_code", "observation_date", "daily_rain_mm", "data_status"}
        if not required.issubset(reader.fieldnames or []):
            raise ValueError("official daily file lacks station_code, observation_date, daily_rain_mm or data_status")
        for row in reader:
            key = row["station_code"].strip(), date.fromisoformat(row["observation_date"]).isoformat()
            if not key[0] or key in records:
                raise ValueError(f"empty station or duplicate official daily key: {key}")
            records[key] = {
                "value": value(row["daily_rain_mm"]), "status": row["data_status"].strip(),
                "queries": row.get("source_queries", ""),
            }
    return records


def apply(row, points, daily):
    """Keep original values/counts. Only a missing midnight increment may be restored."""
    if row["work_start"] != "08:00":
        raise ValueError("official daily reconstruction currently supports only an 08:00 work start")
    day = date.fromisoformat(row["work_date"])
    start = datetime.combine(day, time(8))
    midnight = datetime.combine(day, time())
    previous_start = start - timedelta(days=1)
    previous_2350 = midnight - timedelta(minutes=10)
    step = timedelta(minutes=10)
    stamps = [start - i * step for i in range(144)]
    other = [points.get(stamp) for stamp in stamps if stamp != midnight]
    valid_other = [p.rain for p in other if p is not None and p.rain is not None]
    original = row["prior_24h_rain_mm"]
    record = daily.get((row["station_code"], (day - timedelta(days=1)).isoformat()),
                       {"value": None, "status": "not_returned", "queries": ""})
    cumulative = lambda stamp: getattr(points.get(stamp), "cumulative", None)
    row.update({
        "prior_rain_policy": POLICY,
        "prior_rain_source": "observed_10min" if original is not None else "unavailable",
        "prior_rain_usable": original is not None,
        "prior_rain_status": "observed_not_compared" if original is not None else "held",
        "prior_rain_hold_reason": "", "prior_rain_original_mm": original,
        "prior_rain_candidate_mm": None, "prior_rain_other_interval_count": len(valid_other),
        "prior_rain_partial_sum_mm": math.fsum(valid_other),
        "prior_rain_missing_interval_count": 144 - row["prior_rain_interval_count"],
        "prior_rain_cumulative_prev_start_mm": cumulative(previous_start),
        "prior_rain_cumulative_start_mm": cumulative(start),
        "prior_rain_cumulative_prev_2350_mm": cumulative(previous_2350),
        "prior_rain_last_10min_candidate_mm": None,
        "prior_rain_official_date": (day - timedelta(days=1)).isoformat(),
        "prior_rain_official_mm": record["value"], "prior_rain_official_status": record["status"],
        "prior_rain_official_queries": record["queries"],
        "prior_rain_reconstruction_eligible": False,
        "ai1_station_supported": row["station_code"] != "487915A001", "ai1_policy_exclusion": "",
    })

    def hold(reason, conflict=False):
        row["prior_rain_hold_reason"] = reason
        if original is not None and conflict:
            row["prior_rain_status"] = "observed_conflict"
            row["prior_rain_usable"] = False
        return finish(row)

    if record["status"] != "valid" or record["value"] is None:
        return hold("official_" + record["status"] if record["status"] != "valid" else "official_invalid_value")
    a, b, last = [row[k] for k in ("prior_rain_cumulative_prev_start_mm",
                                  "prior_rain_cumulative_start_mm", "prior_rain_cumulative_prev_2350_mm")]
    if a is None or b is None or last is None:
        return hold("missing_cumulative_endpoint")
    total = record["value"]
    row["prior_rain_candidate_mm"] = math.fsum((total, -a, b))
    row["prior_rain_last_10min_candidate_mm"] = math.fsum((total, -last))
    if row["prior_rain_last_10min_candidate_mm"] < 0 or row["prior_rain_candidate_mm"] < 0:
        return hold("negative_reconstruction", conflict=True)
    if len(valid_other) != 143:
        return hold("other_prior_intervals_missing")
    # Examine the two within-calendar-day cumulative curves, never across the reset boundary.
    curves = ([cumulative(previous_start + i * step) for i in range(96)],
              [cumulative(midnight + i * step) for i in range(1, 49)])
    if any(v is None for curve in curves for v in curve):
        return hold("incomplete_cumulative_history")
    if any(right < left for curve in curves for left, right in zip(curve, curve[1:])):
        return hold("cumulative_decrease", conflict=True)
    if any(v > total for v in curves[0]):
        return hold("cumulative_exceeds_daily_total", conflict=True)
    independent = math.fsum((row["prior_rain_partial_sum_mm"], row["prior_rain_last_10min_candidate_mm"]))
    if abs(independent - row["prior_rain_candidate_mm"]) > TOLERANCE_MM:
        return hold("formula_interval_mismatch", conflict=True)
    if original is not None:
        if abs(original - row["prior_rain_candidate_mm"]) > TOLERANCE_MM:
            return hold("observed_formula_mismatch", conflict=True)
        row["prior_rain_status"] = "observed_agrees"
        return finish(row)
    midnight_point = points.get(midnight)
    if row["prior_rain_interval_count"] != 143 or (midnight_point is not None and midnight_point.rain is not None):
        return hold("missing_interval_is_not_midnight_only")
    row.update(prior_24h_rain_mm=row["prior_rain_candidate_mm"],
               prior_rain_source="reconstructed_official_daily", prior_rain_usable=True,
               prior_rain_status="reconstructed", prior_rain_reconstruction_eligible=True)
    return finish(row)


def finish(row):
    reasons = []
    if not row["ai1_station_supported"]:
        reasons.append("station_excluded_wind_unavailable")
    if row["prior_rain_status"] == "observed_conflict":
        reasons.append("prior_rain_observed_conflict")
    row["ai1_policy_exclusion"] = "|".join(reasons)
    return row


def validate_provenance(row):
    """Check stored reconstruction algebra and truthful original observation counts."""
    from common import boolean, present
    if row["prior_rain_policy"] != POLICY:
        raise ValueError("unknown prior-rain reconstruction policy")
    source = row["prior_rain_source"]
    count = int(row["prior_rain_interval_count"])
    if int(row["prior_rain_missing_interval_count"]) != 144 - count:
        raise ValueError("original prior missing count was altered")
    if boolean(row["ai1_station_supported"]) != (row["station_code"] != "487915A001"):
        raise ValueError("AI1 station policy mismatch")
    reasons = []
    if not boolean(row["ai1_station_supported"]):
        reasons.append("station_excluded_wind_unavailable")
    if row["prior_rain_status"] == "observed_conflict":
        reasons.append("prior_rain_observed_conflict")
    if row["ai1_policy_exclusion"] != "|".join(reasons):
        raise ValueError("AI1 policy exclusion mismatch")
    if source != "reconstructed_official_daily" and boolean(row["prior_rain_reconstruction_eligible"]):
        raise ValueError("non-reconstructed rainfall is marked reconstruction-eligible")
    if source == "reconstructed_official_daily":
        required = ("prior_24h_rain_mm", "prior_rain_candidate_mm", "prior_rain_partial_sum_mm",
                    "prior_rain_official_mm", "prior_rain_cumulative_prev_start_mm",
                    "prior_rain_cumulative_start_mm", "prior_rain_cumulative_prev_2350_mm",
                    "prior_rain_last_10min_candidate_mm")
        if any(value(row[k]) is None for k in required):
            raise ValueError("invalid or missing reconstruction evidence")
        if (row["work_start"] != "08:00" or count != 143
                or int(row["prior_rain_other_interval_count"]) != 143
                or present(row["prior_rain_original_mm"]) or row["prior_rain_official_status"] != "valid"
                or not boolean(row["prior_rain_reconstruction_eligible"])
                or not boolean(row["prior_rain_usable"]) or row["prior_rain_status"] != "reconstructed"
                or row["prior_rain_hold_reason"]):
            raise ValueError("reconstruction completeness/source mismatch")
        previous = (date.fromisoformat(row["work_date"]) - timedelta(days=1)).isoformat()
        if row["prior_rain_official_date"] != previous:
            raise ValueError("official daily date must be the previous day")
        total, a, b, last = [float(row[k]) for k in ("prior_rain_official_mm",
                                                   "prior_rain_cumulative_prev_start_mm",
                                                   "prior_rain_cumulative_start_mm",
                                                   "prior_rain_cumulative_prev_2350_mm")]
        candidate = math.fsum((total, -a, b))
        last_increment = math.fsum((total, -last))
        if candidate < 0 or last_increment < 0:
            raise ValueError("negative reconstructed rainfall")
        expectations = ((float(row["prior_24h_rain_mm"]), candidate),
                        (float(row["prior_rain_candidate_mm"]), candidate),
                        (float(row["prior_rain_last_10min_candidate_mm"]), last_increment),
                        (math.fsum((float(row["prior_rain_partial_sum_mm"]), last_increment)), candidate))
        if any(abs(left - right) > TOLERANCE_MM for left, right in expectations):
            raise ValueError("reconstructed rainfall algebra mismatch")
    elif source == "observed_10min":
        if row["prior_rain_status"] not in ("observed_agrees", "observed_not_compared", "observed_conflict"):
            raise ValueError("unknown observed rainfall comparison status")
        if count != 144 or not present(row["prior_rain_original_mm"]) or not present(row["prior_24h_rain_mm"]):
            raise ValueError("observed prior rainfall must have 144 original intervals")
        if float(row["prior_24h_rain_mm"]) != float(row["prior_rain_original_mm"]):
            raise ValueError("an existing observed prior rainfall value was overwritten")
        if boolean(row["prior_rain_usable"]) != (row["prior_rain_status"] != "observed_conflict"):
            raise ValueError("observed conflict usability mismatch")
        if boolean(row["prior_rain_reconstruction_eligible"]):
            raise ValueError("observed rainfall incorrectly labelled reconstructed")
    elif source == "unavailable":
        if (present(row["prior_24h_rain_mm"]) or present(row["prior_rain_original_mm"])
                or count == 144 or boolean(row["prior_rain_usable"])):
            raise ValueError("unavailable prior rainfall has a value or is marked usable")
    else:
        raise ValueError("unknown prior rainfall source")
