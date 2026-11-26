"""STEP04 v2: conservative official-daily rain recovery, train-only IF refit."""
import argparse
import csv
import hashlib
import json
import math
import pickle
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path
from tempfile import TemporaryDirectory

import numpy as np
from scipy.stats import spearmanr

import isolation_forest_scores as base

VERSION = "step04-v2-daily-recovery"
POLICY = "official_daily_midnight_only_143_valid_v1"
EXCLUDED_AI1 = "487915A001"
TOLERANCE_MM = .001
FEATURES = base.FEATURES
DATA = base.WORKER / "data" / "gyeonggi_processed"
NUMERIC = set(sum((list(v) for v in FEATURES.values()), []))
COUNTS = (*base.COUNTS, "scheduled_minutes", "expected_work_interval_count", "expected_prior_interval_count")


def read_csv(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def load_baseline(path):
    rows = read_csv(path)
    keys = set()
    for row in rows:
        key = row["station_code"], row["work_date"]
        if key in keys or row["period"] != base.period_for(date.fromisoformat(row["work_date"]).isoformat()):
            raise ValueError(f"duplicate key or invalid period: {key}")
        keys.add(key)
        if row["work_start"] != "08:00":
            raise ValueError("daily recovery currently requires validated 08:00 start")
        for k in NUMERIC:
            row[k] = base.number(row[k])
        for k in COUNTS:
            row[k] = int(row[k])
        for k in ("is_mountain", "component_scores_complete", "rain_full_score", "wind_full_score", "heat_full_score",
                  "AI1_input_complete", "AI2_input_complete"):
            row[k] = base.boolean(row[k])
        for model, features in FEATURES.items():
            if row[f"{model}_input_complete"] != all(row[k] is not None for k in features):
                raise ValueError("baseline input eligibility mismatch")
    if not rows:
        raise ValueError("empty baseline")
    return rows


def load_official(path):
    official = {}
    for row in read_csv(path):
        key = row["station_code"], date.fromisoformat(row["observation_date"]).isoformat()
        if key in official:
            raise ValueError(f"duplicate official key: {key}")
        status = row["data_status"]
        if status not in {"valid", "missing_value", "not_returned", "conflicting_values"}:
            raise ValueError(f"unknown official status: {status}")
        value = base.number(row["daily_rain_mm"], minimum=0)
        if status == "valid" and value is None:
            raise ValueError("official valid status without valid rainfall")
        if status != "valid" and value is not None:
            raise ValueError("unavailable official record must not contain a chosen numeric value")
        official[key] = {"value": value, "status": status, "queries": row.get("source_queries", "")}
    return official


def update_eligibility(row):
    for model, features in FEATURES.items():
        missing = [k for k in features if row[k] is None]
        complete = not missing
        policy_excluded = model == "AI1" and row["station_code"] == EXCLUDED_AI1
        quality_hold = model == "AI1" and row.get("prior_rain_quality_hold", False)
        row[f"{model}_input_complete"] = complete
        row[f"{model}_missing_features"] = "|".join(missing)
        row[f"{model}_policy_excluded"] = policy_excluded
        row[f"{model}_quality_hold"] = quality_hold
        row[f"{model}_model_eligible"] = complete and not policy_excluded and not quality_hold
        reason = ("station_excluded_wind_unavailable" if policy_excluded else
                  "observed_prior_official_disagreement" if quality_hold else
                  "incomplete_observations" if missing else "")
        row[f"{model}_exclusion_reason"] = reason


def recover_row(row, points, official):
    """points contain (observed cumulative, existing 10-minute increment), never imputed."""
    start = datetime.fromisoformat(row["work_date"] + "T08:00:00")
    previous_start = start - timedelta(days=1)
    midnight = start.replace(hour=0)
    record = official.get((row["station_code"], previous_start.date().isoformat()))
    daily = record["value"] if record else None
    prev8 = points.get(previous_start, (None, None))[0]
    today8 = points.get(start, (None, None))[0]
    prev2350 = points.get(midnight - base.STEP, (None, None))[0]
    intervals = [(start - i * base.STEP, points.get(start - i * base.STEP, (None, None))[1]) for i in range(144)]
    observed = [v for _, v in intervals if v is not None]
    original = row["prior_24h_rain_mm"]
    if len(observed) != row["prior_rain_interval_count"]:
        raise ValueError(f"baseline/raw prior count mismatch: {row['station_code']} {row['work_date']}")
    if original is not None and (len(observed) != 144 or abs(math.fsum(observed) - original) > 1e-6):
        raise ValueError("baseline/raw complete prior sum mismatch")
    if original is None and len(observed) == 144:
        raise ValueError("baseline missing prior despite 144 valid increments")
    nonmid = [v for stamp, v in intervals if stamp != midnight]
    known = [v for v in nonmid if v is not None]
    decreases = 0
    for stamps in ([previous_start + i * base.STEP for i in range(96)],
                   [midnight + i * base.STEP for i in range(1, 49)]):
        values = [points[t][0] for t in stamps if t in points and points[t][0] is not None]
        decreases += sum(b < a - 1e-6 for a, b in zip(values, values[1:]))
    formula = math.fsum([daily, -prev8, today8]) if all(v is not None for v in (daily, prev8, today8)) else None
    last10 = daily - prev2350 if daily is not None and prev2350 is not None else None
    joined = math.fsum(known + [last10]) if len(known) == 143 and last10 is not None else None
    row.update(prior_rain_observed_mm=original, prior_rain_source="observed_10min" if original is not None else "unavailable",
               prior_rain_recovery_status="observed_unchanged" if original is not None else "held",
               prior_rain_recovery_reason="", prior_rain_quality_hold=False,
               prior_rain_original_missing_intervals=144-len(observed), prior_rain_nonmidnight_valid_intervals=len(known),
               prior_official_date=previous_start.date().isoformat(), prior_official_daily_mm=daily,
               prior_official_status=record["status"] if record else "not_covered",
               prior_official_queries=record["queries"] if record else "",
               prior_previous_start_cumulative_mm=prev8, prior_current_start_cumulative_mm=today8,
               prior_previous_2350_cumulative_mm=prev2350, prior_formula_candidate_mm=formula,
               prior_reconstructed_midnight_mm=last10, prior_recovery_joined_sum_mm=joined,
               prior_counter_decreases=decreases, prior_observed_formula_difference_mm=(formula-original) if formula is not None and original is not None else None,
               prior_recovery_policy=POLICY)
    if original is not None:
        # Preserve the observed value; hold AI1 rather than silently overwrite a disagreement.
        if formula is not None and abs(formula-original) > TOLERANCE_MM:
            row.update(prior_rain_recovery_status="observed_conflict", prior_rain_recovery_reason="observed_prior_official_disagreement",
                       prior_rain_quality_hold=True)
    else:
        reason = ("official_daily_not_covered" if record is None else
                  "official_daily_conflict" if record["status"] == "conflicting_values" else
                  "official_daily_missing" if daily is None else
                  "0800_cumulative_missing" if prev8 is None or today8 is None else
                  "observed_counter_decrease" if decreases else
                  "official_total_endpoint_inconsistent" if formula < 0 or prev8 > daily or (prev2350 is not None and prev2350 > daily) else
                  "nonmidnight_observations_incomplete" if len(known) != 143 else
                  "2350_cumulative_missing" if prev2350 is None else
                  "negative_reconstructed_interval" if last10 < 0 else
                  "reconstruction_sum_inconsistent" if joined is None or abs(joined-formula) > TOLERANCE_MM else "")
        if reason:
            row["prior_rain_recovery_reason"] = reason
        else:
            row.update(prior_24h_rain_mm=formula, prior_rain_source="reconstructed_official_daily",
                       prior_rain_recovery_status="restored")
    update_eligibility(row)
    return row


def recover_all(rows, weather, official):
    groups = defaultdict(list)
    for row in rows:
        groups[row["station_code"]].append(row)
    visited, station, points = set(), None, {}
    def flush(code, data):
        if code in groups:
            print(f"Recovering {code}", flush=True)
            for row in groups[code]:
                recover_row(row, data, official)
    with weather.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.reader(stream)
        header = next(reader)
        idx = [header.index(k) for k in ("station_code", "observed_at", "daily_cumulative_rain_mm", "rain_10min_mm")]
        for raw in reader:
            code, stamp, cumulative, increment = [raw[i] for i in idx]
            if code != station:
                if station is not None:
                    flush(station, points); visited.add(station)
                if code in visited:
                    raise ValueError("weather must be station-contiguous; source format changed")
                station, points = code, {}
            if code in groups:
                t = datetime.fromisoformat(stamp)
                if t.tzinfo or t.second or t.microsecond or t.minute % 10:
                    raise ValueError("invalid KST 10-minute observation end")
                value = (base.number(cumulative, minimum=0), base.number(increment, minimum=0))
                if points.setdefault(t, value) != value:
                    raise ValueError("conflicting duplicate weather point")
        if station is not None:
            flush(station, points); visited.add(station)
    if set(groups)-visited:
        raise ValueError("target stations absent from weather source")
    return rows


def fit_recovered(rows, model, config, *, seed=42, trees=300, min_samples=100):
    eligible = [r for r in rows if r[f"{model}_model_eligible"]]
    bundle = base.fit_bundle(eligible, model, config, seed=seed, trees=trees, min_samples=min_samples)
    metadata = bundle["metadata"]
    metadata.update(schema_version=VERSION, recovery_policy=POLICY,
                    excluded_stations=[EXCLUDED_AI1] if model == "AI1" else [])
    train = [r for r in eligible if base.TRAIN_START <= r["work_date"] <= base.TRAIN_END]
    bundle["training_membership"] = {(r["station_code"], r["work_date"]): tuple(r[k] for k in FEATURES[model])
                                     for r in train} if bundle["forest"] is not None else {}
    payload = {k: metadata.get(k) for k in ("schema_version", "model", "features", "config", "parameters", "training_input_sha256", "status")}
    metadata["model_id"] = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:24]
    return bundle


def score_recovered(rows, bundles, config):
    for model, features in FEATURES.items():
        bundle = bundles[model]
        meta = bundle["metadata"]
        if (meta["schema_version"] != VERSION or meta["model"] != model or meta["features"] != list(features)
                or meta["config"] != config or meta["recovery_policy"] != POLICY):
            raise ValueError("saved model input/configuration contract mismatch")
        indices = [i for i, r in enumerate(rows) if r[f"{model}_model_eligible"]]
        computed = {}
        if indices and bundle["forest"] is not None:
            matrix = np.asarray([[rows[i][k] for k in features] for i in indices], dtype=float)
            if not np.isfinite(matrix).all():
                raise ValueError("nonfinite inference matrix")
            raw = -bundle["forest"].score_samples(matrix)
            computed = dict(zip(indices, zip(raw, base.percentile(bundle["reference"], raw))))
        for i, row in enumerate(rows):
            key = row["station_code"], row["work_date"]
            row[f"{model}_model_version"] = VERSION
            row[f"{model}_model_id"] = meta["model_id"]
            row[f"{model}_used_features"] = "|".join(features) if i in computed else ""
            row[f"{model}_training_used"] = bundle["training_membership"].get(key) == tuple(row[k] for k in features)
            row[f"{model}_status"] = "computed" if i in computed else "unavailable"
            row[f"{model}_raw_anomaly"], row[f"S_{model}"] = tuple(map(float, computed[i])) if i in computed else (None, None)
            row[f"{model}_unavailable_reason"] = ("" if i in computed else row[f"{model}_exclusion_reason"] or meta["reason"])
    for row in rows:
        a, b = (row[f"{m}_status"] == "computed" for m in FEATURES)
        row["calculation_status"] = "both" if a and b else "AI1_only" if a else "AI2_only" if b else "unavailable"


def compare(rows, old):
    keys = {(r["station_code"], r["work_date"]) for r in rows}
    if keys != set(old) or len(keys) != len(rows):
        raise ValueError("comparison keys differ")
    groups = defaultdict(list)
    deltas = []
    for r in rows:
        previous = old[(r["station_code"], r["work_date"])]
        for k in ("S_R", "S_W", "S_H", "work_start", "work_end", "period"):
            if r[k] != previous[k]:
                raise ValueError("STEP03 reference or work calendar changed")
        for m in FEATURES:
            before = previous[f"{m}_status"] == "computed"
            after = r[f"{m}_status"] == "computed"
            old_value = float(previous[f"S_{m}"]) if before else None
            new_value = r[f"S_{m}"]
            cohort = "common" if before and after else "newly_computed" if after else "no_longer_computed" if before else "unavailable_both"
            delta = new_value-old_value if cohort == "common" else None
            if m == "AI2" and (before != after or delta is not None and abs(delta) > 1e-12):
                raise ValueError("AI2 numerical results changed despite unchanged inputs/settings")
            item = {"period": r["period"], "station_code": r["station_code"], "work_date": r["work_date"],
                    "model": m, "cohort": cohort, "old_score": old_value, "new_score": new_value,
                    "score_difference": delta, "prior_rain_recovery_status": r["prior_rain_recovery_status"]}
            deltas.append(item)
            groups[(r["period"], m)].append(item)
    summaries = []
    for (period, m), items in sorted(groups.items()):
        common = [r for r in items if r["cohort"] == "common"]
        ds = [r["score_difference"] for r in common]
        correlation = float(spearmanr([r["old_score"] for r in common], [r["new_score"] for r in common]).statistic) if len(common)>1 else None
        if correlation is not None and not math.isfinite(correlation):
            correlation = None
        top_old = {(r["station_code"], r["work_date"]) for r in sorted(common, key=lambda r: (-r["old_score"], r["work_date"], r["station_code"]))[:10]}
        top_new = {(r["station_code"], r["work_date"]) for r in sorted(common, key=lambda r: (-r["new_score"], r["work_date"], r["station_code"]))[:10]}
        summaries.append({"period": period, "model": m, "total_rows": len(items),
                          **{k+"_rows": sum(r["cohort"] == k for r in items) for k in ("common", "newly_computed", "no_longer_computed", "unavailable_both")},
                          "common_mean_difference": math.fsum(ds)/len(ds) if ds else None,
                          "common_mean_absolute_difference": math.fsum(map(abs, ds))/len(ds) if ds else None,
                          "common_max_absolute_difference": max(map(abs, ds), default=None),
                          "common_score_spearman": correlation, "common_top10_overlap": len(top_old & top_new)})
    return deltas, summaries


def summarize(rows, old):
    groups = defaultdict(list)
    for r in rows:
        groups[(r["period"], "ALL", "all24")].append(r)
        groups[(r["period"], r["station_code"], "station")].append(r)
        if r["station_code"] != EXCLUDED_AI1:
            groups[(r["period"], "ALL", "AI1_supported23")].append(r)
    model_rows, recovery = [], []
    for (period, station, scope), group in sorted(groups.items()):
        total = len(group)
        recovery.append({"period":period, "station_code":station, "scope":scope, "total_rows":total,
                         "original_prior_missing":sum(r["prior_rain_observed_mm"] is None for r in group),
                         "restored_rows":sum(r["prior_rain_recovery_status"] == "restored" for r in group),
                         "remaining_prior_missing":sum(r["prior_24h_rain_mm"] is None for r in group),
                         "observed_conflicts":sum(r["prior_rain_quality_hold"] for r in group)})
        for m in FEATURES:
            if scope == "AI1_supported23" and m != "AI1":
                continue
            before = sum(old[(r["station_code"], r["work_date"])][f"{m}_status"] == "computed" for r in group)
            after = sum(r[f"{m}_status"] == "computed" for r in group)
            reasons = Counter(r[f"{m}_unavailable_reason"] for r in group if r[f"{m}_status"] != "computed")
            item = {"period":period, "station_code":station, "scope":scope, "model":m, "total_rows":total,
                    "before_computed_rows":before, "after_computed_rows":after,
                    "before_excluded_rows":total-before, "after_excluded_rows":total-after,
                    "before_exclusion_pct":100*(total-before)/total, "after_exclusion_pct":100*(total-after)/total,
                    "input_incomplete_rows":sum(not r[f"{m}_input_complete"] for r in group),
                    "policy_excluded_rows":sum(r[f"{m}_policy_excluded"] for r in group),
                    "quality_hold_rows":sum(r[f"{m}_quality_hold"] for r in group),
                    "training_used_rows":sum(r[f"{m}_training_used"] for r in group),
                    "primary_unavailable_reasons":json.dumps(dict(reasons),sort_keys=True)}
            for hazard in ("rain", "wind", "heat"):
                events = [r for r in group if r[hazard+"_full_score"]]
                item[f"{hazard}_observed_extreme_rows"] = len(events)
                item[f"{hazard}_extreme_scored_before"] = sum(old[(r["station_code"],r["work_date"])][f"{m}_status"] == "computed" for r in events)
                item[f"{hazard}_extreme_scored_after"] = sum(r[f"{m}_status"] == "computed" for r in events)
            model_rows.append(item)
    return model_rows, recovery


def validate(rows, bundles):
    from bisect import bisect_right
    for r in rows:
        if r["prior_rain_recovery_status"] == "restored":
            if (r["prior_rain_observed_mm"] is not None or r["prior_rain_nonmidnight_valid_intervals"] != 143
                    or r["prior_rain_source"] != "reconstructed_official_daily"
                    or r["prior_counter_decreases"] or r["prior_reconstructed_midnight_mm"] < 0
                    or abs(r["prior_24h_rain_mm"] - r["prior_recovery_joined_sum_mm"]) > TOLERANCE_MM):
                raise ValueError("recovery acceptance audit failed")
        for m, features in FEATURES.items():
            expected = all(r[k] is not None and math.isfinite(r[k]) for k in features)
            eligible = expected and not r[f"{m}_policy_excluded"] and not r[f"{m}_quality_hold"]
            if expected != r[f"{m}_input_complete"] or eligible != r[f"{m}_model_eligible"]:
                raise ValueError("eligibility audit failed")
            computed = eligible and bundles[m]["forest"] is not None
            if computed != (r[f"{m}_status"] == "computed"):
                raise ValueError("score availability audit failed")
            if computed:
                raw, value = r[f"{m}_raw_anomaly"], r[f"S_{m}"]
                ref = bundles[m]["reference"]
                scalar = 100.*bisect_right(ref, raw)/len(ref)
                if not math.isfinite(raw) or not 0 <= value <= 100 or abs(value-scalar)>1e-12:
                    raise ValueError("score/ECDF audit failed")
    return {"checked_rows":len(rows), "recovery_acceptance":"passed", "eligibility_and_ecdf":"passed"}


def validate_baseline(folder, weather):
    report = json.loads((folder/"run_report.json").read_text(encoding="utf-8"))
    for name, digest in report["output_files"].items():
        if base.sha256(folder/name) != digest:
            raise ValueError(f"baseline artifact hash mismatch: {name}")
    for path, metadata in report["inputs"].items():
        if base.sha256(Path(path)) != metadata["sha256"]:
            raise ValueError(f"baseline source changed: {path}")
    if str(weather.resolve()) not in report["inputs"]:
        raise ValueError("weather differs from baseline source")
    return report


def run(args):
    folder, output = args.baseline_dir.resolve(), args.output_dir.resolve()
    if output == folder or (output.exists() and any(output.iterdir())):
        raise ValueError("use a separate empty output directory; baseline is immutable")
    for protected in (base.WORKER/"code"/"step03", base.WORKER/"docs"/"step03", DATA/"step03"):
        if output == protected.resolve() or protected.resolve() in output.parents:
            raise ValueError("STEP03 is read-only")
    print("Checking immutable baseline/source hashes", flush=True)
    old_report = validate_baseline(folder, args.weather)
    official_hash = base.sha256(args.official_daily)
    original_features = load_baseline(folder/"weather_ai_features.csv")
    untouched = [{k:r[k] for k in NUMERIC if k != "prior_24h_rain_mm"} for r in original_features]
    old = {(r["station_code"],r["work_date"]):r for r in read_csv(folder/"weather_ai_scores.csv")}
    official = load_official(args.official_daily)
    rows = recover_all(original_features, args.weather, official)
    for before, row in zip(untouched, rows):
        if any(row[k] != v for k,v in before.items()):
            raise ValueError("non-prior model features changed")
    config = {**old_report["config"], "recovery_policy":POLICY, "recovery_tolerance_mm":TOLERANCE_MM,
              "AI1_excluded_stations":[EXCLUDED_AI1], "observed_official_disagreement":"preserve_value_hold_AI1"}
    output.mkdir(parents=True,exist_ok=True)
    with TemporaryDirectory(prefix=".recovery_run_",dir=output) as temporary:
        stage = Path(temporary)
        bundles = {}
        for m in FEATURES:
            print(f"Refitting {m}: 2016-2023 eligible workdays only",flush=True)
            bundles[m] = fit_recovered(rows,m,config,seed=args.seed,trees=args.trees,min_samples=args.min_training_rows)
            with (stage/base.FILES[m]).open("wb") as f:
                pickle.dump(bundles[m],f,protocol=pickle.HIGHEST_PROTOCOL)
        score_recovered(rows,bundles,config)
        before_reload = [(r["AI1_raw_anomaly"],r["S_AI1"],r["AI2_raw_anomaly"],r["S_AI2"]) for r in rows]
        reloaded = {}
        for m in FEATURES:
            with (stage/base.FILES[m]).open("rb") as f:
                reloaded[m] = pickle.load(f)
        score_recovered(rows,reloaded,config)
        if before_reload != [(r["AI1_raw_anomaly"],r["S_AI1"],r["AI2_raw_anomaly"],r["S_AI2"]) for r in rows]:
            raise ValueError("saved/reloaded model scores differ")
        audit = validate(rows,reloaded)
        deltas, score_summary = compare(rows,old)
        model_summary, rain_summary = summarize(rows,old)
        base.write_csv(stage/"weather_ai_scores.csv",rows)
        feature_columns = [k for k in rows[0] if not (k.endswith("_raw_anomaly") or k.startswith("S_AI") or k.endswith("_training_used") or k.endswith("_model_id") or k.endswith("_model_version") or k.endswith("_used_features") or k.endswith("_unavailable_reason") or k in ("AI1_status","AI2_status","calculation_status"))]
        base.write_csv(stage/"weather_ai_features.csv",[{k:r[k] for k in feature_columns} for r in rows])
        recovery_columns = ["station_code","work_date","period","prior_24h_rain_mm","prior_rain_interval_count"]+[k for k in rows[0] if k.startswith("prior_") and k not in ("prior_24h_rain_mm","prior_rain_interval_count")]
        base.write_csv(stage/"rain_recovery_audit.csv",[{k:r[k] for k in recovery_columns} for r in rows])
        base.write_csv(stage/"comparison_exclusion.csv",model_summary)
        base.write_csv(stage/"recovery_summary.csv",rain_summary)
        base.write_csv(stage/"score_comparison.csv",deltas)
        base.write_csv(stage/"score_change_summary.csv",score_summary)
        for period,name in (("train","weather_ai_train.csv"),("validation","weather_ai_holdout.csv"),("after_validation","weather_ai_additional.csv")):
            base.write_csv(stage/name,[r for r in rows if r["period"]==period],columns=rows[0])
        for m in FEATURES:
            selected = sorted([r for r in rows if r["period"]=="validation" and r[f"{m}_status"]=="computed"],key=lambda r:(-r[f"S_{m}"],r["work_date"],r["station_code"]))[:10]
            base.write_csv(stage/f"top10_{m}_validation.csv",selected,columns=rows[0])
        validate_baseline(folder,args.weather)
        if base.sha256(args.official_daily) != official_hash:
            raise ValueError("official daily source changed during run")
        report = {"schema_version":VERSION,"config":config, "baseline_dir":str(folder),"baseline_manifest_sha256":base.sha256(folder/"run_report.json"),
                  "official_daily_file":str(args.official_daily.resolve()),"official_daily_sha256":official_hash,
                  "official_daily_coverage_note":"Shared reference covers train and holdout work calendars. 2026 missing prior has no official coverage and remains unavailable.",
                  "models":{m:b["metadata"] for m,b in bundles.items()},"audit":audit,"model_reload":"passed",
                  "AI2_score_invariance":"passed", "baseline_and_STEP03_source_preservation":"passed",
                  "period_rows":dict(Counter(r["period"] for r in rows)),
                  "restored_rows_by_period":dict(Counter(r["period"] for r in rows if r["prior_rain_recovery_status"]=="restored")),
                  "recovery_holds":dict(Counter(r["prior_rain_recovery_reason"] for r in rows if r["prior_rain_recovery_status"]=="held")),
                  "observed_conflicts_by_period":dict(Counter(r["period"] for r in rows if r["prior_rain_quality_hold"])),
                  "code_sha256":base.sha256(Path(__file__)),
                  "interpretation":"Coverage and score-change comparison, not cancellation accuracy or statistical significance.",
                  "output_files":{p.name:base.sha256(p) for p in sorted(stage.iterdir())}}
        (stage/"run_report.json").write_text(json.dumps(report,ensure_ascii=False,indent=2,allow_nan=False),encoding="utf-8")
        for p in sorted(stage.iterdir(),key=lambda p:p.name=="run_report.json"):
            p.replace(output/p.name)
    print(json.dumps({"output":str(output),"models":{m:b["metadata"]["training_rows"] for m,b in bundles.items()},
                      "restored":report["restored_rows_by_period"],"observed_conflicts":report["observed_conflicts_by_period"]},indent=2),flush=True)
    return report


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--baseline-dir",type=Path,default=DATA/"step04")
    p.add_argument("--weather",type=Path,default=DATA/"weather_clean.csv")
    p.add_argument("--official-daily",type=Path,default=DATA/"step04/share/official_daily_20261125/official_daily_rainfall.csv")
    p.add_argument("--output-dir",type=Path,default=DATA/"step04/recovered_daily_20261125")
    p.add_argument("--seed",type=int,default=42);p.add_argument("--trees",type=int,default=300)
    p.add_argument("--min-training-rows",type=int,default=100)
    args=p.parse_args()
    if args.trees<1 or args.min_training_rows<2:p.error("trees >=1, min-training-rows >=2 required")
    try:run(args)
    except (ValueError,OSError,KeyError,csv.Error) as exc:p.exit(1,f"Error: {exc}\n")


if __name__=="__main__":main()
