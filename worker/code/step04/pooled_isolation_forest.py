"""Fit pooled models for step04.py and the LOF/ECOD comparison workflow.

Kept separate from the standalone isolation_forest_scores.py upstream workflow.
"""

import hashlib
import json
import pickle
import platform
import uuid
from collections import defaultdict
from pathlib import Path
from tempfile import NamedTemporaryFile

import numpy as np
import scipy
import sklearn
from sklearn.ensemble import IsolationForest

from common import (AI_FEATURES, boolean, check_rows, read_rows, row_key,
                    sha256, write_json, write_rows, feature_contract, is_reconstructed_run,
                    protect_existing_results, verify_feature_metadata)
from weather_ai_features import input_status

DEFAULT_PARAMS = dict(n_estimators=300, max_samples="auto", max_features=1.0,
                      contamination="auto", bootstrap=False, random_state=42, n_jobs=1)
MODEL_FILES = {"AI1":"isolation_forest_r1.pkl", "AI2":"isolation_forest_r2.pkl"}


def versions():
    return {"python":platform.python_version(), "numpy":np.__version__,
            "scipy":scipy.__version__, "scikit-learn":sklearn.__version__}


def matrix(rows, columns):
    return np.asarray([[float(row[k]) for k in columns] for row in rows], dtype=np.float64)


def percentile(reference, anomaly):
    reference = np.asarray(reference, dtype=np.float64)
    anomaly = np.asarray(anomaly, dtype=np.float64)
    if (reference.ndim != 1 or not len(reference) or not np.isfinite(reference).all()
            or np.any(reference[1:] < reference[:-1]) or not np.isfinite(anomaly).all()):
        raise ValueError("ECDF requires nonempty finite sorted training scores and finite query scores")
    return 100.0 * np.searchsorted(reference, anomaly, side="right") / len(reference)


def fit_model(rows, model_name, params=None):
    columns = AI_FEATURES[model_name]
    selected = sorted((r for r in rows if r["period"] == "train"
                       and boolean(r[model_name.lower()+"_eligible"])), key=row_key)
    if not selected:
        raise ValueError(f"{model_name}: no complete training rows; never fit held-out rows instead")
    settings = dict(DEFAULT_PARAMS, **(params or {}))
    estimator = IsolationForest(**settings).fit(matrix(selected, columns))
    reference = np.sort(-estimator.score_samples(matrix(selected, columns)))
    by_station = defaultdict(list)
    for row in selected:
        by_station[row["station_code"]].append(row["work_date"])
    stations = {}
    for station in sorted({r["station_code"] for r in rows}):
        dates = by_station[station]
        stations[station] = {"used_rows":len(dates), "actual_train_start":min(dates) if dates else None,
                             "actual_train_end":max(dates) if dates else None}
    digest = hashlib.sha256(json.dumps(
        [(r["station_code"],r["work_date"],*[float(r[k]) for k in columns]) for r in selected],
        separators=(",", ":"), allow_nan=False).encode()).hexdigest()
    info = {
        "model":model_name, "feature_order":list(columns), "params":settings,
        "train_start":"2016-01-01", "train_end":"2023-12-31", "train_rows":len(selected),
        "actual_train_start":min(r["work_date"] for r in selected),
        "actual_train_end":max(r["work_date"] for r in selected), "stations":stations,
        "training_matrix_sha256":digest, "preprocessing":"original units; no imputation or clipping",
        "score_direction":"negative score_samples", "ecdf":"count(train_anomaly <= query) / n * 100",
        "training_score_unique_count":int(len(np.unique(reference))),
        "feature_contract":feature_contract(rows),
    }
    if is_reconstructed_run(rows):
        info["preprocessing"] = "original units; verified official-daily P24 reconstruction; no statistical imputation or clipping"
    return estimator, reference, info


def save_binary(path, write):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with NamedTemporaryFile("wb", dir=path.parent, delete=False) as stream:
            temporary = Path(stream.name)
            write(stream)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def train_models(features_path, output_dir, params=None):
    rows = read_rows(features_path)
    check_rows(rows)
    reconstruction = is_reconstructed_run(rows)
    if reconstruction:
        protect_existing_results(output_dir)
        verify_feature_metadata(features_path, rows)
    # Finish both fits before publishing either artifact. The manifest is published last.
    fitted = {name:fit_model(rows, name, params) for name in AI_FEATURES}
    folder = Path(output_dir)
    manifest = {"schema_version":1, "versions":versions(), "models":{},
                "interpretation":"Weather anomaly percentiles, not cancellation/damage probabilities."}
    if reconstruction:
        manifest.update(schema_version=2, model_id=str(uuid.uuid4()), feature_contract=feature_contract(rows))
    for name, (model, reference, info) in fitted.items():
        model_path = folder / MODEL_FILES[name]
        ref_path = folder / (name.lower()+"_train_reference.npy")
        save_binary(model_path, lambda stream, m=model:pickle.dump(m, stream, protocol=pickle.HIGHEST_PROTOCOL))
        save_binary(ref_path, lambda stream, ref=reference:np.save(stream, ref, allow_pickle=False))
        info.update(model_file=model_path.name, reference_file=ref_path.name,
                    model_sha256=sha256(model_path), reference_sha256=sha256(ref_path))
        manifest["models"][name] = info
        print(f'{name}: trained {info["train_rows"]:,} rows; saved {model_path}', flush=True)
    write_json(folder / "model_metadata.json", manifest)
    return manifest


def load_models(folder):
    folder = Path(folder)
    manifest = json.loads((folder/"model_metadata.json").read_text(encoding="utf-8-sig"))
    if manifest.get("schema_version") not in (1,2) or manifest.get("versions") != versions():
        raise ValueError("model schema/runtime versions differ from training; use the recorded environment")
    models = {}
    for name, columns in AI_FEATURES.items():
        info = manifest["models"][name]
        if info["feature_order"] != list(columns):
            raise ValueError(f"{name}: saved feature order mismatch")
        model_path, ref_path = folder/info["model_file"], folder/info["reference_file"]
        if sha256(model_path) != info["model_sha256"] or sha256(ref_path) != info["reference_sha256"]:
            raise ValueError(f"{name}: model/reference checksum mismatch")
        with model_path.open("rb") as stream:
            model = pickle.load(stream)
        reference = np.load(ref_path, allow_pickle=False)
        percentile(reference, np.asarray([], dtype=float))
        if len(reference) != info["train_rows"] or model.n_features_in_ != len(columns):
            raise ValueError(f"{name}: saved model/reference dimensions mismatch")
        models[name] = model, reference
    return models, manifest


def score_rows(rows, models):
    result = [dict(row, ai_score_status=input_status(boolean(row["ai1_eligible"]),boolean(row["ai2_eligible"])),
                   AI1_anomaly=None, S_AI1=None, AI2_anomaly=None, S_AI2=None) for row in rows]
    for name, columns in AI_FEATURES.items():
        indices = [i for i,r in enumerate(rows) if boolean(r[name.lower()+"_eligible"])]
        if not indices:
            continue
        model, reference = models[name]
        anomaly = -model.score_samples(matrix([rows[i] for i in indices],columns))
        scores = percentile(reference, anomaly)
        for index, raw, score in zip(indices, anomaly, scores):
            result[index][name+"_anomaly"] = float(raw)
            result[index]["S_"+name] = float(score)
    return result


def score_file(features_path, model_dir, output_path):
    if Path(features_path).resolve() == Path(output_path).resolve():
        raise ValueError("scores must not overwrite features")
    rows = read_rows(features_path)
    check_rows(rows)
    if is_reconstructed_run(rows):
        protect_existing_results(output_path)
        verify_feature_metadata(features_path, rows)
    models, manifest = load_models(model_dir)
    if manifest["schema_version"] == 2:
        if manifest["feature_contract"] != feature_contract(rows):
            raise ValueError("model/input reconstruction policy or work settings differ; retrain for this contract")
    elif is_reconstructed_run(rows):
        raise ValueError("legacy observed-only model cannot score reconstructed inputs; train new models in a separate folder")
    result = score_rows(rows, models)
    if manifest["schema_version"] == 2:
        for row in result:
            row["model_id"] = manifest["model_id"]
            for name in AI_FEATURES:
                row[name.lower()+"_training_station_participated"] = manifest["models"][name]["stations"].get(row["station_code"],{}).get("used_rows",0)>0
    write_rows(output_path,result)
    write_json(Path(output_path).with_suffix(".metadata.json"), {
        "features_sha256":sha256(features_path),
        "models_metadata_sha256":sha256(Path(model_dir)/"model_metadata.json"),
        "row_count":len(rows), "model_count":2,
    })
    print(f"Saved {len(result):,} target rows with independent AI1/AI2 scores: {output_path}",flush=True)
    return result
