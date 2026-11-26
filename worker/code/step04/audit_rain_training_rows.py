"""Read-only raw/clean reconciliation and a zero-to-zero midnight counterfactual.

Writes a diagnostic JSON only. Does not change clean data, features, models or scores.
"""

import argparse
import csv
import json
import math
from collections import Counter, defaultdict
from datetime import date, timedelta
from pathlib import Path

from common import DATA_DIR, OUTPUT_DIR, write_json


def numeric(value):
    try:
        n=float(value)
        return n if math.isfinite(n) and n >= 0 else None
    except (TypeError,ValueError):
        return None


def audit(raw_dir, clean_path, features_path, output_path):
    targets={}
    with features_path.open(encoding="utf-8-sig",newline="") as f:
        for r in csv.DictReader(f):
            if r["work_start"]!="08:00" or r["work_end"]!="17:00":
                raise ValueError("this diagnostic supports the current 08:00-17:00 window only")
            targets[r["station_code"],r["work_date"]]={
                "period":r["period"],"prior_count":int(r["prior_rain_interval_count"]),
                "work_complete":int(r["work_rain_interval_count"])==54 and int(r["work_wind_interval_count"])==54,
                "ai1_eligible":r["ai1_eligible"]=="True",
            }
    stations={k[0] for k in targets}
    tomorrow={}
    def next_day(d):
        if d not in tomorrow:
            tomorrow[d]=(date.fromisoformat(d)+timedelta(days=1)).isoformat()
        return tomorrow[d]
    clean_boundaries=defaultdict(dict)
    prior=defaultdict(lambda:[0,0.0])
    clean_rows=0
    clean_stats=Counter()
    print("Reading clean observations and independently counting prior intervals",flush=True)
    with clean_path.open(encoding="utf-8-sig",newline="") as f:
        reader=csv.reader(f);header=next(reader)
        si,ti,ci,ri=[header.index(k) for k in ("station_code","observed_at","daily_cumulative_rain_mm","rain_10min_mm")]
        for r in reader:
            if not r:
                continue
            clean_rows+=1
            station,stamp=r[si],r[ti]
            d,clock=stamp[:10],stamp[11:16]
            if station not in stations:
                continue
            increment=numeric(r[ri])
            key=(station,d if clock<="08:00" else next_day(d))
            if key in targets and increment is not None:
                prior[key][0]+=1
                prior[key][1]+=increment
            if clock in ("00:00","00:10","23:50"):
                boundary=(station,next_day(d) if clock=="23:50" else d)
                if boundary in targets:
                    clean_boundaries[boundary][clock]={"timestamp":stamp,"cumulative":numeric(r[ci]),"increment":increment}
                    if clock=="00:00":
                        clean_stats["midnight_rows"]+=1
                        if numeric(r[ci])==0 and increment is None:
                            clean_stats["midnight_zero_with_missing_increment"]+=1
    print(f"Clean rows scanned: {clean_rows:,}. Reading raw midnight boundaries",flush=True)
    raw_boundaries=defaultdict(dict)
    conflicts=[];raw_rows=0
    raw_files=sorted(raw_dir.glob("*.csv"))
    for index,path in enumerate(raw_files,1):
        with path.open(encoding="utf-8-sig",newline="") as f:
            reader=csv.reader(f);header=next(reader)
            si,ti,ci=[header.index(k) for k in ("station_code","날짜","강수량( mm )")]
            for r in reader:
                if not r:
                    continue
                raw_rows+=1
                station=r[si].strip()
                if station not in stations:
                    continue
                stamp=r[ti].strip()
                clock=stamp[-5:]
                if clock not in ("23:50","24:00","00:00","00:10"):
                    continue
                d=stamp[:10]
                key=(station,next_day(d) if clock in ("23:50","24:00") else d)
                if key not in targets:
                    continue
                normalized_clock="00:00" if clock=="24:00" else clock
                record={"timestamp":stamp,"raw_cumulative":r[ci],"cumulative":numeric(r[ci]),"file":path.name}
                previous=raw_boundaries[key].get(normalized_clock)
                if previous is not None and previous["cumulative"]!=record["cumulative"]:
                    conflicts.append({"station":station,"date":key[1],"clock":clock,"first":previous,"second":record})
                else:
                    raw_boundaries[key][normalized_clock]=record
        if index%100==0 or index==len(raw_files):
            print(f"Raw files scanned: {index}/{len(raw_files)}",flush=True)
    counts=defaultdict(Counter);station_counts=defaultdict(Counter)
    examples=defaultdict(list);mismatches=[];source_mismatches=[]
    for key,t in sorted(targets.items()):
        station,d=key;period=t["period"]
        c=counts[period];c["targets"]+=1
        c["current_ai1_eligible"]+=t["ai1_eligible"]
        c["work_rain_wind_complete"]+=t["work_complete"]
        pc,psum=prior.get(key,(0,0.0))
        if pc!=t["prior_count"]:
            mismatches.append({"station":station,"date":d,"computed":pc,"stored":t["prior_count"]})
        cb=clean_boundaries.get(key,{})
        rb=raw_boundaries.get(key,{})
        for clock in ("23:50","00:00","00:10"):
            clean,raw=cb.get(clock),rb.get(clock)
            if clean is not None and (raw is None or raw["cumulative"]!=clean["cumulative"]):
                source_mismatches.append({"station":station,"date":d,"clock":clock,"raw":raw,"clean":clean})
        clean_mid,clean_prev=cb.get("00:00"),cb.get("23:50")
        raw_mid,raw_prev=rb.get("00:00"),rb.get("23:50")
        zero_to_zero=(clean_mid is not None and clean_prev is not None
                      and clean_mid["cumulative"]==0 and clean_prev["cumulative"]==0
                      and clean_mid["increment"] is None
                      and raw_mid is not None and raw_prev is not None
                      and raw_mid["cumulative"]==0 and raw_prev["cumulative"]==0)
        recoverable=t["work_complete"] and pc==143 and zero_to_zero
        c["raw_verified_zero_to_zero_midnights"]+=zero_to_zero
        c["additional_ai1_if_zero_to_zero_valid"]+=recoverable
        c["counterfactual_ai1_eligible"]+=t["ai1_eligible"] or recoverable
        station_counts[(period,station)]["current_ai1_eligible"]+=t["ai1_eligible"]
        station_counts[(period,station)]["additional_ai1_if_zero_to_zero_valid"]+=recoverable
        if recoverable:
            tag="raw_zero_to_zero_only_missing"
        elif pc<144 and raw_mid is None:
            tag="raw_midnight_absent"
        elif pc<144 and raw_mid["cumulative"]==0 and raw_prev is not None and (raw_prev["cumulative"] or 0)>0:
            tag="positive_to_zero_reset"
        else:
            tag="other"
        if tag!="other" and len(examples[(period,tag)])<3:
            examples[(period,tag)].append({"station_code":station,"work_date":d,"prior_valid_intervals":pc,
                "partial_valid_prior_sum_mm":psum,"raw":rb,"clean":cb,
                "work_rain_wind_complete":t["work_complete"]})
    result={
        "scope":"Read-only source reconciliation; no production data or policies changed.",
        "clean_path":str(clean_path.resolve()),"features_path":str(features_path.resolve()),
        "raw_files":len(raw_files),"raw_rows_scanned":raw_rows,"clean_rows_scanned":clean_rows,
        "clean_midnight_stats":dict(clean_stats),
        "periods":{k:dict(v) for k,v in counts.items()},
        "prior_interval_count_mismatches":len(mismatches),"prior_mismatch_examples":mismatches[:10],
        "boundary_raw_clean_mismatches":len(source_mismatches),"boundary_mismatch_examples":source_mismatches[:10],
        "raw_boundary_conflicts":len(conflicts),"raw_conflict_examples":conflicts[:10],
        "station_counts":[{"period":k[0],"station_code":k[1],**v} for k,v in sorted(station_counts.items())],
        "examples":[{"period":k[0],"cause":k[1],"rows":v} for k,v in sorted(examples.items())],
        "counterfactual_policy":"ONLY existing raw/clean midnight and preceding 23:50 both have cumulative 0; treat that missing midnight increment as 0. No absent rows or positive-to-zero resets filled.",
        "interpretation":"A counterfactual identifies numerical processing differences. It does not establish the true missing ten-minute rainfall or authorize changing the policy.",
    }
    write_json(output_path,result)
    print(json.dumps({k:result[k] for k in ("raw_files","raw_rows_scanned","clean_rows_scanned","periods",
                     "prior_interval_count_mismatches","boundary_raw_clean_mismatches","raw_boundary_conflicts")},ensure_ascii=False,indent=2),flush=True)
    print(f"Diagnostic saved: {output_path}",flush=True)
    return result


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--raw-dir",type=Path,default=DATA_DIR.parent/"gyeonggi-2016-2026-csv")
    parser.add_argument("--clean",type=Path,default=DATA_DIR/"weather_clean.csv")
    parser.add_argument("--features",type=Path,default=OUTPUT_DIR/"weather_ai_features.csv")
    parser.add_argument("--output",type=Path,default=OUTPUT_DIR/"troubleshooting"/"rain_training_rows"/"audit_summary.json")
    args=parser.parse_args()
    audit(args.raw_dir,args.clean,args.features,args.output)
