"""Independently validate saved v2 recovery lineage, fits and score comparisons."""
import argparse
import csv
import hashlib
import json
import math
import pickle
from collections import Counter
from datetime import date, timedelta
from pathlib import Path

import numpy as np


FEATURES = {"AI1": ["work_rain_mm", "prior_24h_rain_mm", "work_max_mean_wind_mps", "work_rain_minutes", "work_strong_wind_minutes"],
            "AI2": ["work_max_temperature_c", "work_mean_humidity_pct", "work_heat_minutes_ge31"]}
FILES = {"AI1":"isolation_forest_r1.pkl", "AI2":"isolation_forest_r2.pkl"}


def read(path):
    with path.open(encoding="utf-8-sig",newline="") as f:return list(csv.DictReader(f))


def sha(path):
    h=hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda:f.read(1048576),b""):h.update(block)
    return h.hexdigest()


def validate(output):
    report=json.loads((output/"run_report.json").read_text(encoding="utf-8"))
    for name,digest in report["output_files"].items():
        if sha(output/name)!=digest:raise ValueError(f"new output hash mismatch: {name}")
    baseline=Path(report["baseline_dir"])
    if sha(baseline/"run_report.json")!=report["baseline_manifest_sha256"]:
        raise ValueError("baseline manifest changed")
    old_report=json.loads((baseline/"run_report.json").read_text(encoding="utf-8"))
    for name,digest in old_report["output_files"].items():
        if sha(baseline/name)!=digest:raise ValueError(f"baseline changed: {name}")
    for path,metadata in old_report["inputs"].items():
        if sha(Path(path))!=metadata["sha256"]:raise ValueError("STEP03/weather source changed")
    official_path=Path(report["official_daily_file"])
    if sha(official_path)!=report["official_daily_sha256"]:raise ValueError("official source changed")
    official={(r["station_code"],r["observation_date"]):r for r in read(official_path)}
    old={(r["station_code"],r["work_date"]):r for r in read(baseline/"weather_ai_scores.csv")}
    rows=read(output/"weather_ai_scores.csv")
    keys={(r["station_code"],r["work_date"]) for r in rows}
    if len(keys)!=len(rows) or keys!=set(old):raise ValueError("target rows lost or duplicated")
    restored=Counter();holds=Counter()
    for row in rows:
        previous=old[(row["station_code"],row["work_date"])]
        for column in ["S_R","S_W","S_H","work_start","work_end","period","prior_rain_interval_count"]:
            if row[column]!=previous[column]:raise ValueError(f"preserved field changed: {column}")
        for column in set(FEATURES["AI1"]+FEATURES["AI2"])-{"prior_24h_rain_mm"}:
            a,b=row[column],previous[column]
            if (a=="")!=(b=="") or a!="" and float(a)!=float(b):
                raise ValueError("non-prior input changed")
        if row["prior_rain_observed_mm"]!=previous["prior_24h_rain_mm"]:
            raise ValueError("original observed prior not preserved")
        source=row["prior_rain_source"]
        status=row["prior_rain_recovery_status"]
        if status=="restored":
            day=(date.fromisoformat(row["work_date"])-timedelta(days=1)).isoformat()
            record=official[(row["station_code"],day)]
            daily=float(record["daily_rain_mm"])
            formula=math.fsum([daily,-float(row["prior_previous_start_cumulative_mm"]),float(row["prior_current_start_cumulative_mm"])])
            last=daily-float(row["prior_previous_2350_cumulative_mm"])
            if (previous["prior_24h_rain_mm"]!="" or record["data_status"]!="valid" or source!="reconstructed_official_daily"
                    or int(row["prior_rain_nonmidnight_valid_intervals"])!=143 or int(row["prior_counter_decreases"])!=0
                    or last<0 or formula<0 or abs(formula-float(row["prior_24h_rain_mm"]))>1e-9
                    or abs(formula-float(row["prior_recovery_joined_sum_mm"]))>.001):
                raise ValueError("saved recovery lineage inconsistent")
            restored[row["period"]]+=1
        elif previous["prior_24h_rain_mm"]!=row["prior_24h_rain_mm"]:
            raise ValueError("prior modified outside accepted recovery")
        if row["prior_rain_quality_hold"]=="True":holds[row["period"]]+=1
        if row["station_code"]=="487915A001":
            if row["AI1_status"]!="unavailable" or row["AI1_unavailable_reason"]!="station_excluded_wind_unavailable":
                raise ValueError("Shinbuk AI1 policy violation")
        if row["AI2_status"]!=previous["AI2_status"] or row["S_AI2"]!=previous["S_AI2"]:
            raise ValueError("AI2 changed")
    train_checks={}
    for model,features in FEATURES.items():
        with (output/FILES[model]).open("rb") as f:bundle=pickle.load(f)
        train=[r for r in rows if "2016-01-01"<=r["work_date"]<="2023-12-31" and r[f"{model}_model_eligible"]=="True"]
        if len(train)!=bundle["metadata"]["training_rows"]:raise ValueError("training row count mismatch")
        if bundle["metadata"]["features"]!=features or bundle["metadata"]["config"]!=report["config"]:
            raise ValueError("model input contract mismatch")
        x=np.array([[float(r[k]) for k in features] for r in train])
        reference=np.sort(-bundle["forest"].score_samples(x))
        if not np.array_equal(reference,bundle["reference"]):raise ValueError("training ECDF mismatch")
        members=bundle["training_membership"]
        if set(members)!={(r["station_code"],r["work_date"]) for r in train}:raise ValueError("fit membership dates differ")
        scored=[r for r in rows if r[f"{model}_status"]=="computed"]
        matrix=np.array([[float(r[k]) for k in features] for r in scored])
        raw=-bundle["forest"].score_samples(matrix)
        expected=100.*np.searchsorted(reference,raw,side="right")/len(reference)
        if not np.array_equal(raw,np.array([float(r[f"{model}_raw_anomaly"]) for r in scored])):
            raise ValueError("saved raw anomaly differs from model")
        if not np.allclose(expected,[float(r[f"S_{model}"]) for r in scored],rtol=0,atol=1e-12):
            raise ValueError("saved percentile differs from fitted ECDF")
        for row in rows:
            key=row["station_code"],row["work_date"]
            vector=tuple(float(row[k]) if row[k]!="" else None for k in features)
            actual=members.get(key)==vector
            if actual!=(row[f"{model}_training_used"]=="True"):raise ValueError("training-used flag mismatch")
        train_checks[model]={"training_rows":len(train),"last_training_date":max(r["work_date"] for r in train),"scored_rows":len(scored)}
    for period,filename in (("train","weather_ai_train.csv"),("validation","weather_ai_holdout.csv"),("after_validation","weather_ai_additional.csv")):
        if read(output/filename)!=[r for r in rows if r["period"]==period]:raise ValueError("period split differs")
    for summary in read(output/"comparison_exclusion.csv"):
        group=[r for r in rows if r["period"]==summary["period"] and (summary["station_code"]=="ALL" or r["station_code"]==summary["station_code"])
               and (summary["scope"]!="AI1_supported23" or r["station_code"]!="487915A001")]
        m=summary["model"]
        before=sum(old[(r["station_code"],r["work_date"])][f"{m}_status"]=="computed" for r in group)
        after=sum(r[f"{m}_status"]=="computed" for r in group)
        if (len(group)!=int(summary["total_rows"]) or before!=int(summary["before_computed_rows"]) or after!=int(summary["after_computed_rows"])
                or abs(float(summary["after_exclusion_pct"])-100*(len(group)-after)/len(group))>1e-10):
            raise ValueError("comparison denominator/count mismatch")
    return {"rows_checked":len(rows),"status":"passed","validated_run_report_sha256":sha(output/"run_report.json"),"models":train_checks,
            "restored_by_period":dict(restored),"observed_conflicts_by_period":dict(holds),
            "checks":["artifact_hashes","baseline_and_source_preservation","row_and_input_preservation","official_recovery_lineage",
                      "AI1_station_policy","AI2_score_invariance","train_only_membership_and_ECDF","saved_model_raw_and_percentile_scores","period_splits","exclusion_denominators"],
            "scope":"Independent saved artifact/model audit. Raw 143-interval sums are covered by recovery code/tests; not a second full raw-weather reaggregation."}


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    data=Path(__file__).resolve().parents[2]/"data/gyeonggi_processed/step04/recovered_daily_20261125"
    p.add_argument("--output-dir",type=Path,default=data)
    args=p.parse_args()
    try:result=validate(args.output_dir)
    except (ValueError,OSError,KeyError) as exc:p.exit(1,f"Error: {exc}\n")
    print(json.dumps(result,ensure_ascii=False,indent=2))
