"""Descriptive exclusion diagnostics; no statistical pass/fail thresholds."""

from collections import Counter, defaultdict
from pathlib import Path

from common import (AI_FEATURES, ALL_FEATURES, EXTREME_LIMITS, boolean, check_rows,
                    present, read_rows, write_json, write_rows, is_reconstructed_run,
                    protect_existing_results)
from weather_ai_features import input_status


def diagnose(rows, folder):
    check_rows(rows)
    reconstruction = is_reconstructed_run(rows)
    if reconstruction:
        protect_existing_results(folder)
    groups = defaultdict(list)
    for row in rows:
        groups[(row["period"],"__ALL__")].append(row)
        groups[(row["period"],row["station_code"])].append(row)
    summaries, missing, combinations, extremes = [], [], [], []
    for (period,station), members in sorted(groups.items()):
        base = {"period":period,"station_code":station,"total_rows":len(members)}
        status = Counter(input_status(boolean(r["ai1_eligible"]),boolean(r["ai2_eligible"])) for r in members)
        for key in ("both","AI1_only","AI2_only","neither"):
            combinations.append(dict(base,status=key,rows=status[key],rate_pct=100*status[key]/len(members)))
        for feature in ALL_FEATURES:
            count=sum(not present(r[feature]) for r in members)
            missing.append(dict(base,feature=feature,missing_rows=count,missing_rate_pct=100*count/len(members)))
        for name in AI_FEATURES:
            prefix=name.lower()
            used=[r for r in members if boolean(r[prefix+"_eligible"])]
            excluded=len(members)-len(used)
            extra = {}
            if reconstruction:
                policy_rows = sum(bool(r.get("ai1_policy_exclusion")) for r in members) if name == "AI1" else 0
                supported = [r for r in members if boolean(r["ai1_station_supported"])] if name == "AI1" else members
                supported_used = sum(boolean(r[prefix+"_eligible"]) for r in supported)
                extra = {
                    "direct_observed_prior_rows":sum(r["prior_rain_source"]=="observed_10min" and boolean(r["prior_rain_usable"]) for r in members),
                    "reconstructed_prior_rows":sum(r["prior_rain_source"]=="reconstructed_official_daily" for r in members),
                    "input_incomplete_rows":sum(bool(r[prefix+"_missing_features"]) for r in members),
                    "policy_excluded_rows":policy_rows,
                    "supported_target_rows":len(supported),"supported_used_rows":supported_used,
                    "supported_excluded_rate_pct":100*(len(supported)-supported_used)/len(supported) if supported else None,
                }
            summaries.append(dict(base,model=name,used_rows=len(used),excluded_rows=excluded,
                                  excluded_rate_pct=100*excluded/len(members),
                                  actual_train_start=min((r["work_date"] for r in used),default="") if period=="train" else "",
                                  actual_train_end=max((r["work_date"] for r in used),default="") if period=="train" else "",
                                  missing_combinations=dict(Counter(r[prefix+"_missing_features"] for r in members if not boolean(r[prefix+"_eligible"]))),**extra))
            for eligible in (True,False):
                subset=[r for r in members if boolean(r[prefix+"_eligible"])==eligible]
                for variable, limit in EXTREME_LIMITS.items():
                    observed=[float(r[variable]) for r in subset if present(r[variable])]
                    # Non-mountain wind 21 / mountain 24, matching STEP03 full-score limits.
                    exceeded=sum(float(r[variable]) >= (24 if variable=="observed_work_max_wind_mps" and boolean(r["is_mountain"]) else limit)
                                 for r in subset if present(r[variable]))
                    extremes.append(dict(base,model=name,eligible=eligible,variable=variable,
                                         group_rows=len(subset),observed_rows=len(observed),
                                         observed_mean=sum(observed)/len(observed) if observed else None,
                                         observed_max=max(observed,default=None),threshold=limit,
                                         threshold_rule="24 mountain / 21 non-mountain" if variable=="observed_work_max_wind_mps" else str(limit),
                                         exceedance_rows=exceeded,
                                         exceedance_rate_observed_pct=100*exceeded/len(observed) if observed else None))
    folder=Path(folder)
    flat=[]
    for row in summaries:
        flat.append({k:v for k,v in row.items() if k!="missing_combinations"})
    write_rows(folder/"input_diagnostics.csv",flat)
    write_rows(folder/"feature_missingness.csv",missing)
    write_rows(folder/"eligibility_combinations.csv",combinations)
    write_rows(folder/"observed_extremes.csv",extremes)
    report={"target_rows":len(rows),"groups":summaries,
            "interpretation":"Observed-only descriptive comparison; partial maxima are lower bounds. Not a statistical significance test.",
            "rain_threshold_note":"72 mm over a complete within-work one-hour window; no imputation across missing intervals.",
            "missing_reason_counts_overlap":True}
    write_json(folder/"input_diagnostics.json",report)
    if reconstruction:
        reconstruction_diagnostics(rows,folder)
    for row in flat:
        if row["station_code"]=="__ALL__":
            print(f'{row["period"]} {row["model"]}: {row["used_rows"]:,}/{row["total_rows"]:,} usable; excluded {row["excluded_rate_pct"]:.2f}%',flush=True)
    return report


def diagnose_file(features_path,folder,baseline_path=None):
    rows=read_rows(features_path)
    report=diagnose(rows,folder)
    if baseline_path is not None:
        compare_eligibility(rows,read_rows(baseline_path),folder)
    return report


def reconstruction_diagnostics(rows, folder):
    grouped=defaultdict(list)
    for row in rows:
        grouped[row["period"],"__ALL__"].append(row)
        grouped[row["period"],row["station_code"]].append(row)
    summary=[]
    for (period,station),members in sorted(grouped.items()):
        summary.append({"period":period,"station_code":station,"target_rows":len(members),
            "original_prior_missing":sum(not present(r["prior_rain_original_mm"]) for r in members),
            "reconstructed_rows":sum(r["prior_rain_source"]=="reconstructed_official_daily" for r in members),
            "observed_conflict_rows":sum(r["prior_rain_status"]=="observed_conflict" for r in members),
            "held_missing_rows":sum(r["prior_rain_source"]=="unavailable" for r in members),
            "hold_reasons":dict(Counter(r["prior_rain_hold_reason"] for r in members if r["prior_rain_hold_reason"]))})
    write_json(Path(folder)/"prior_rain_reconstruction_summary.json",{"groups":summary,
        "scope":"Conservative midnight-only feature reconstruction. Actual observed counts and STEP03 unchanged."})
    columns=(["station_code","work_date","period","prior_rain_interval_count","prior_24h_rain_mm"]+
             [k for k in rows[0] if k.startswith("prior_rain_") and k!="prior_rain_interval_count"]+
             ["ai1_eligible","ai1_missing_features","ai1_policy_exclusion"])
    write_rows(Path(folder)/"prior_rain_reconstruction.csv",
               [{k:r[k] for k in columns} for r in rows],columns)


def compare_eligibility(rows, baseline, folder):
    from common import row_key
    check_rows(baseline)
    before={row_key(r):r for r in baseline}
    if len(rows)!=len(baseline) or {row_key(r) for r in rows}!=set(before):
        raise ValueError("baseline and reconstructed targets differ; cannot compare exclusion rates")
    groups=defaultdict(Counter)
    for row in rows:
        old=before[row_key(row)]
        if any(row[k]!=old[k] for k in ("period","work_start","work_end")) or boolean(row["is_mountain"])!=boolean(old["is_mountain"]):
            raise ValueError("baseline work settings differ")
        for station in ("__ALL__",row["station_code"]):
            for name in AI_FEATURES:
                key=(row["period"],station,name)
                a,b=boolean(old[name.lower()+"_eligible"]),boolean(row[name.lower()+"_eligible"])
                groups[key]["target_rows"]+=1
                groups[key]["before_used_rows"]+=a
                groups[key]["after_used_rows"]+=b
                groups[key]["newly_eligible_rows"]+=b and not a
                groups[key]["newly_excluded_rows"]+=a and not b
                groups[key]["eligible_in_both_rows"]+=a and b
    output=[dict(period=k[0],station_code=k[1],model=k[2],**v) for k,v in sorted(groups.items())]
    write_rows(Path(folder)/"reconstruction_eligibility_comparison.csv",output)
