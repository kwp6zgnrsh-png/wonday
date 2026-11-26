"""Run STEP04 one stage at a time: check, features, diagnose, train, score, validate."""

import argparse
import csv
import json
from pathlib import Path

from common import DATA_DIR, OUTPUT_DIR, RECONSTRUCTED_OUTPUT_DIR, read_rows


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    sub=parser.add_subparsers(dest="command",required=True)
    for name in ("check","features","diagnose","validate-features","train","score","validate"):
        p=sub.add_parser(name)
        p.add_argument("--clean",type=Path,default=DATA_DIR/"weather_clean.csv")
        p.add_argument("--step03",type=Path,default=DATA_DIR/"step03"/"weather_component_scores.csv")
        p.add_argument("--output-dir",type=Path)
        if name in ("check", "features"):
            p.add_argument("--official-daily",type=Path,
                           help="enable conservative STEP04-only prior-rain reconstruction; defaults to a separate output folder")
        p.add_argument("--features",type=Path)
        p.add_argument("--scores",type=Path)
        p.add_argument("--model-dir",type=Path)
        if name == "diagnose":
            p.add_argument("--baseline-features",type=Path,
                           help="optional preserved baseline features for matched-key before/after eligibility comparison")
    args=parser.parse_args()
    official = getattr(args, "official_daily", None)
    args.output_dir = args.output_dir or (RECONSTRUCTED_OUTPUT_DIR if official else OUTPUT_DIR)
    features=args.features or args.output_dir/"weather_ai_features.csv"
    scores=args.scores or args.output_dir/"weather_ai_scores.csv"
    models=args.model_dir or args.output_dir
    try:
        if args.command=="check":
            from pooled_isolation_forest import versions
            for path in (args.clean,args.step03):
                if not path.is_file():
                    raise ValueError(f"missing input: {path}")
            if official is not None:
                from prior_rain_reconstruction import load_daily
                from common import protect_existing_results
                protect_existing_results(args.output_dir)
                records=load_daily(official)
                print(f"Official daily records: {len(records):,}")
            rows=read_rows(args.step03)
            if not rows:
                raise ValueError("STEP03 target file has no rows")
            print(json.dumps({"status":"ready","versions":versions(),
                              "target_rows":len(rows),"stations":len({r['station_code'] for r in rows}),
                              "work_settings":sorted({(r['work_start'],r['work_end'],r['is_mountain']) for r in rows}),
                              "clean":str(args.clean),"step03":str(args.step03),
                              "output_dir":str(args.output_dir)},ensure_ascii=False,indent=2))
        elif args.command=="features":
            from weather_ai_features import build_features
            build_features(args.clean,args.step03,features,official_path=official)
        elif args.command=="diagnose":
            from diagnostics import diagnose_file
            diagnose_file(features,args.output_dir,baseline_path=args.baseline_features)
        elif args.command=="validate-features":
            from validate_isolation_forest import validate_features
            validate_features(features,args.step03,args.output_dir/"feature_validation_report.json")
        elif args.command=="train":
            from pooled_isolation_forest import train_models
            train_models(features,models)
        elif args.command=="score":
            from pooled_isolation_forest import score_file
            score_file(features,models,scores)
        elif args.command=="validate":
            from validate_isolation_forest import validate
            validate(features,scores,models,args.step03,args.output_dir/"validation_report.json")
    except (OSError,ValueError,KeyError,TypeError,ImportError,csv.Error) as exc:
        parser.exit(1,f"Error: {exc}\n")


if __name__=="__main__":
    main()
