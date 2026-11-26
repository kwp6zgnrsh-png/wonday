"""Verify row preservation, saved-model scores, ECDFs and training membership."""

import json
import math
from collections import Counter, defaultdict
from pathlib import Path

import numpy as np

from common import (AI_FEATURES, boolean, check_rows, read_rows, row_key, sha256, write_json,
                    feature_contract, is_reconstructed_run, protect_existing_results,
                    verify_feature_metadata)
from pooled_isolation_forest import load_models, matrix, score_rows


def validate_features(features_path, targets_path, report_path=None):
    """Pre-training validation, including original-source fingerprints and reconstruction algebra."""
    rows, targets = read_rows(features_path), read_rows(targets_path)
    check_rows(rows)
    if is_reconstructed_run(rows):
        verify_feature_metadata(features_path,rows)
        if report_path is not None:
            protect_existing_results(report_path)
    metadata=json.loads(Path(features_path).with_suffix(".metadata.json").read_text(encoding="utf-8-sig"))
    if is_reconstructed_run(rows):
        from datetime import date, timedelta
        from prior_rain_reconstruction import load_daily, value
        daily=load_daily(metadata["official_daily_path"])
        for row in rows:
            previous=(date.fromisoformat(row["work_date"])-timedelta(days=1)).isoformat()
            record=daily.get((row["station_code"],previous),{"value":None,"status":"not_returned","queries":""})
            if (row["prior_rain_official_date"]!=previous
                    or value(row["prior_rain_official_mm"])!=record["value"]
                    or row["prior_rain_official_status"]!=record["status"]
                    or row["prior_rain_official_queries"]!=record["queries"]):
                raise ValueError("stored official rainfall provenance differs from its source record")
    if metadata["target_sha256"] != sha256(targets_path):
        raise ValueError("STEP03 target file changed after feature aggregation")
    lookup={row_key(r):r for r in targets}
    if len(lookup)!=len(targets) or len(rows)!=len(targets) or {row_key(r) for r in rows}!=set(lookup):
        raise ValueError("features did not preserve STEP03 target keys exactly once")
    for row in rows:
        target=lookup[row_key(row)]
        if any(row[k]!=target[k] for k in ("work_start","work_end","period")) or boolean(row["is_mountain"])!=boolean(target["is_mountain"]):
            raise ValueError("STEP03 work settings were changed")
    report={"status":"passed","target_rows":len(rows),"reconstructed_prior_rows":sum(r.get("prior_rain_source")=="reconstructed_official_daily" for r in rows),
            "observed_prior_conflicts":sum(r.get("prior_rain_status")=="observed_conflict" for r in rows),
            "model_training_executed":False,"mismatch_count":0}
    if report_path is not None:
        write_json(report_path,report)
        print(json.dumps(report,ensure_ascii=False,indent=2))
    return report


def validate(features_path, scores_path, model_dir, targets_path, report_path):
    validate_features(features_path, targets_path)
    rows, scores, targets = read_rows(features_path), read_rows(scores_path), read_rows(targets_path)
    if is_reconstructed_run(rows):
        protect_existing_results(report_path)
    check_rows(rows)
    check_rows(scores)
    features_meta = json.loads(Path(features_path).with_suffix(".metadata.json").read_text(encoding="utf-8-sig"))
    scores_meta = json.loads(Path(scores_path).with_suffix(".metadata.json").read_text(encoding="utf-8-sig"))
    if features_meta["target_sha256"] != sha256(targets_path):
        raise ValueError("STEP03 target file changed after feature aggregation")
    if scores_meta["features_sha256"] != sha256(features_path):
        raise ValueError("feature file changed after scoring")
    if scores_meta["models_metadata_sha256"] != sha256(Path(model_dir)/"model_metadata.json"):
        raise ValueError("model manifest changed after scoring")
    feature_keys = [row_key(r) for r in rows]
    if feature_keys != [row_key(r) for r in scores]:
        raise ValueError("scoring dropped/reordered target rows")
    if len(targets) != len(rows) or set(feature_keys) != {row_key(r) for r in targets}:
        raise ValueError("features did not preserve all STEP03 targets exactly once")
    lookup = {row_key(r):r for r in targets}
    for row in rows:
        target = lookup[row_key(row)]
        for key in ("work_start","work_end","period"):
            if row[key] != target[key]:
                raise ValueError(f"target settings changed: {row_key(row)}/{key}")
        if boolean(row["is_mountain"]) != boolean(target["is_mountain"]):
            raise ValueError("target mountain setting changed")
    models, manifest = load_models(model_dir)
    if manifest["schema_version"] == 2:
        if manifest["feature_contract"] != feature_contract(rows):
            raise ValueError("model/input contract mismatch")
    elif is_reconstructed_run(rows):
        raise ValueError("reconstructed features cannot use legacy observed-only models")
    expected = score_rows(rows,models)
    comparisons = 0
    for original, actual, wanted in zip(rows,scores,expected):
        for key,value in original.items():
            if actual[key] != value:
                raise ValueError(f"feature value changed in score output: {row_key(actual)}/{key}")
        if actual["ai_score_status"] != wanted["ai_score_status"]:
            raise ValueError("independent model selection status mismatch")
        if manifest["schema_version"] == 2:
            if actual["model_id"] != manifest["model_id"]:
                raise ValueError("score model identity mismatch")
            for name in AI_FEATURES:
                participated=manifest["models"][name]["stations"].get(actual["station_code"],{}).get("used_rows",0)>0
                if boolean(actual[name.lower()+"_training_station_participated"])!=participated:
                    raise ValueError("training participation flag mismatch")
        for model in AI_FEATURES:
            for key in (model+"_anomaly", "S_"+model):
                value = wanted[key]
                if value is None:
                    if actual[key] != "":
                        raise ValueError(f"excluded row received a score: {row_key(actual)}/{key}")
                else:
                    if not math.isclose(float(actual[key]),value,rel_tol=1e-12,abs_tol=1e-12):
                        raise ValueError(f"saved/reloaded score mismatch: {row_key(actual)}/{key}")
                    if key.startswith("S_") and not 0 <= float(actual[key]) <= 100:
                        raise ValueError("AI score outside 0..100")
                comparisons += 1
    for name,columns in AI_FEATURES.items():
        train = sorted((r for r in rows if r["period"]=="train" and boolean(r[name.lower()+"_eligible"])),key=row_key)
        model,reference = models[name]
        wanted_ref = np.sort(-model.score_samples(matrix(train,columns)))
        if not np.array_equal(reference,wanted_ref):
            raise ValueError(f"{name}: reference differs from training-only scores")
        # Calculate membership fingerprint without fitting another estimator.
        import hashlib
        digest=hashlib.sha256(json.dumps(
            [(r["station_code"],r["work_date"],*[float(r[k]) for k in columns]) for r in train],
            separators=(",",":"),allow_nan=False).encode()).hexdigest()
        if digest != manifest["models"][name]["training_matrix_sha256"]:
            raise ValueError(f"{name}: current training membership differs from fitted input")
    diagnostics_path=Path(report_path).parent/"input_diagnostics.json"
    diagnostics=json.loads(diagnostics_path.read_text(encoding="utf-8-sig"))
    if diagnostics["target_rows"] != len(rows):
        raise ValueError("diagnostic target row count mismatch")
    grouped=defaultdict(list)
    for row in rows:
        grouped[(row["period"],"__ALL__")].append(row)
        grouped[(row["period"],row["station_code"])].append(row)
    seen=set()
    for group in diagnostics["groups"]:
        key=(group["period"],group["station_code"])
        name=group["model"]
        identity=(*key,name)
        if identity in seen or key not in grouped or name not in AI_FEATURES:
            raise ValueError("duplicate or unexpected diagnostic group")
        seen.add(identity)
        members=grouped[key]
        excluded=[r for r in members if not boolean(r[name.lower()+"_eligible"])]
        if (group["total_rows"] != len(members) or group["excluded_rows"] != len(excluded)
                or group["used_rows"] != len(members)-len(excluded)
                or not math.isclose(group["excluded_rate_pct"],100*len(excluded)/len(members),abs_tol=1e-12)
                or group["missing_combinations"] != dict(Counter(r[name.lower()+"_missing_features"] for r in excluded))):
            raise ValueError(f"diagnostic denominator/reasons mismatch: {identity}")
    if len(seen) != len(grouped)*len(AI_FEATURES):
        raise ValueError("diagnostic groups are missing")
    report = {"status":"passed","target_rows":len(rows),"score_rows":len(scores),
              "comparison_count":comparisons,"mismatch_count":0,
              "checks":["target preservation","work settings","model-independent eligibility",
                        "ineligible scores blank","score range","saved/reloaded score equality",
                        "training-only reference","training input fingerprint","artifact checksums",
                        "exclusion denominators and overlapping reasons"],
              "scope":"Implementation verification, not cancellation/damage prediction performance."}
    write_json(report_path,report)
    print(json.dumps(report,ensure_ascii=False,indent=2))
    return report
