"""Separate STEP04 comparison runs. Never writes into either IF result tree."""

import argparse
import json
from pathlib import Path
import pickle
import sys
import uuid

import numpy as np
from scipy.stats import spearmanr

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "step04"))
from common import (AI_FEATURES, DATA_DIR, boolean, check_rows, feature_contract,
                    read_rows, row_key, sha256, verify_feature_metadata, write_json, write_rows)
from pooled_isolation_forest import matrix, percentile, save_binary, versions
from models import ComparisonModel

DEFAULT_FEATURES = DATA_DIR / "step04_official_daily" / "weather_ai_features.csv"
DEFAULT_IF = DATA_DIR / "step04_official_daily"
ALGORITHM_LABELS = {"lof": "LOF_novelty", "ecod": "ECOD_training_frozen_v1"}


def output_folder(algorithm):
    return DATA_DIR / ("step04_" + algorithm)


def protect_output(folder, features_path=None):
    folder = Path(folder).resolve()
    protected = [DATA_DIR / name for name in ("step03", "step04", "step04_official_daily")]
    if folder == DATA_DIR.resolve() or any(
            folder == p.resolve() or folder in p.resolve().parents or p.resolve() in folder.parents
            for p in protected):
        raise ValueError("existing IF/STEP03 folders and their parents are protected")
    if features_path is not None and Path(features_path).resolve().is_relative_to(folder):
        raise ValueError("comparison output must be separate from the source features")
    if folder.exists() and any(folder.rglob("isolation_forest_*.pkl")):
        raise ValueError("an IF model exists in the requested output folder")


def read_features(path):
    rows = read_rows(path)
    check_rows(rows)
    verify_feature_metadata(path, rows)
    return rows


def eligibility_summary(rows):
    summary = []
    for period in sorted({r["period"] for r in rows}):
        part = [r for r in rows if r["period"] == period]
        for name in AI_FEATURES:
            used = sum(boolean(r[name.lower() + "_eligible"]) for r in part)
            summary.append(dict(period=period, model=name, total=len(part), used=used,
                                excluded=len(part) - used,
                                excluded_percent=100 * (len(part) - used) / len(part)))
    return summary


def train(features_path, folder, algorithm, neighbors=20):
    protect_output(folder, features_path)
    rows = read_features(features_path)
    folder = Path(folder)
    metadata_path = folder / "model_metadata.json"
    if metadata_path.exists() and json.loads(metadata_path.read_text(encoding="utf-8-sig")).get("algorithm") != algorithm:
        raise ValueError("output folder belongs to a different algorithm")
    manifest = dict(schema_version=1, algorithm=algorithm, algorithm_label=ALGORITHM_LABELS[algorithm],
                    model_id=str(uuid.uuid4()), versions=versions(),
                    features_sha256=sha256(features_path), features_path=str(Path(features_path).resolve()),
                    feature_contract=feature_contract(rows), models={},
                    eligibility=eligibility_summary(rows),
                    interpretation="Anomaly ranks, not damage or cancellation probabilities",
                    ecdf="100 * count(training_anomaly <= query) / n; ties use right rank")
    fitted = {}
    for name, columns in AI_FEATURES.items():
        selected = sorted((r for r in rows if r["period"] == "train" and
                           boolean(r[name.lower() + "_eligible"])), key=row_key)
        print(f"{algorithm.upper()} {name}: fitting {len(selected):,} training rows", flush=True)
        model = ComparisonModel(algorithm, neighbors).fit(matrix(selected, columns), list(map(row_key, selected)))
        dates_by_station = {}
        for station in sorted({r["station_code"] for r in rows}):
            dates = [r["work_date"] for r in selected if r["station_code"] == station]
            dates_by_station[station] = dict(used_rows=len(dates), actual_train_start=min(dates) if dates else None,
                                            actual_train_end=max(dates) if dates else None)
        info = dict(feature_order=list(columns), train_rows=len(selected),
                    actual_train_start=min(r["work_date"] for r in selected),
                    actual_train_end=max(r["work_date"] for r in selected), stations=dates_by_station,
                    training_matrix_sha256=model.training_matrix_sha256_,
                    training_score_unique_count=len(np.unique(model.reference_)))
        if algorithm == "lof":
            info.update(preprocessing="StandardScaler fitted only on eligible 2016-2023 rows; no imputation/clipping",
                        params=model.estimator_.get_params(),
                        training_raw="-negative_outlier_factor_ (native LOF)",
                        heldout_raw="-score_samples (novelty LOF)",
                        calibration_note="Native training and novelty scores have different neighbor semantics; ranks are descriptive")
        else:
            info.update(preprocessing="original units; no imputation/clipping",
                        params=dict(tail_floor=1 / len(selected), skew_sign=model.estimator_.skew_sign_.tolist(),
                                    cdf_ties="left P(train<=x), right P(train>=x)", constant_skew=0),
                        training_raw="frozen ECOD tail sum", heldout_raw="same frozen ECOD tail sum",
                        calibration_note="Inductive variant, not unmodified transductive PyOD ECOD")
        fitted[name] = model
        manifest["models"][name] = info
    # Both models must finish before any artifacts are published; manifest last.
    for name, model in fitted.items():
        filename = f"{algorithm}_{name.lower()}.pkl"
        path = folder / filename
        save_binary(path, lambda stream, m=model: pickle.dump(m, stream, protocol=pickle.HIGHEST_PROTOCOL))
        ref_path = folder / f"{name.lower()}_train_reference.npy"
        save_binary(ref_path, lambda stream, m=model: np.save(stream, m.reference_, allow_pickle=False))
        manifest["models"][name].update(model_file=filename, model_sha256=sha256(path),
                                      reference_file=ref_path.name, reference_sha256=sha256(ref_path))
    write_json(metadata_path, manifest)
    print(f"Saved {algorithm.upper()} AI1/AI2: {folder}", flush=True)
    return manifest


def load(folder):
    folder = Path(folder)
    manifest = json.loads((folder / "model_metadata.json").read_text(encoding="utf-8-sig"))
    algorithm = manifest.get("algorithm")
    if manifest.get("schema_version") != 1 or algorithm not in ALGORITHM_LABELS or manifest.get("versions") != versions():
        raise ValueError("comparison model schema/algorithm/runtime mismatch")
    models = {}
    for name, columns in AI_FEATURES.items():
        info = manifest["models"][name]
        model_path, ref_path = folder / info["model_file"], folder / info["reference_file"]
        if sha256(model_path) != info["model_sha256"] or sha256(ref_path) != info["reference_sha256"]:
            raise ValueError("comparison model/reference checksum mismatch")
        # Load only locally generated, trusted model files.
        with model_path.open("rb") as stream:
            model = pickle.load(stream)
        reference = np.load(ref_path, allow_pickle=False)
        percentile(reference, [])
        if (not isinstance(model, ComparisonModel) or model.algorithm != algorithm or
                model.n_features_in_ != len(columns) or info["feature_order"] != list(columns) or
                len(reference) != info["train_rows"] or not np.array_equal(reference, model.reference_)):
            raise ValueError("comparison model/reference contract mismatch")
        models[name] = model
    return models, manifest


def require_same_features(path, rows, manifest):
    if sha256(path) != manifest["features_sha256"] or feature_contract(rows) != manifest["feature_contract"]:
        raise ValueError("features differ from this comparison training run; train on the same input first")


def score_rows(rows, models, manifest):
    result = [dict(r, algorithm=manifest["algorithm_label"], comparison_model_id=manifest["model_id"],
                   AI1_anomaly=None, S_AI1=None, AI1_score_method="unavailable",
                   AI2_anomaly=None, S_AI2=None, AI2_score_method="unavailable") for r in rows]
    for name, columns in AI_FEATURES.items():
        indices = [i for i, r in enumerate(rows) if boolean(r[name.lower() + "_eligible"])]
        if not indices:
            continue
        selected = [rows[i] for i in indices]
        model = models[name]
        training = [r["period"] == "train" for r in selected]
        raw = model.anomaly(matrix(selected, columns), list(map(row_key, selected)), training)
        scores = percentile(model.reference_, raw)
        for i, anomaly, value, is_train in zip(indices, raw, scores, training):
            result[i][name + "_anomaly"] = float(anomaly)
            result[i]["S_" + name] = float(value)
            result[i][name + "_score_method"] = (
                "train_native" if is_train else "novelty"
            ) if model.algorithm == "lof" else "frozen_ecod"
    return result


def score(features_path, folder):
    protect_output(folder, features_path)
    rows = read_features(features_path)
    models, manifest = load(folder)
    require_same_features(features_path, rows, manifest)
    result = score_rows(rows, models, manifest)
    path = Path(folder) / "weather_ai_scores.csv"
    write_rows(path, result)
    write_json(path.with_suffix(".metadata.json"), dict(
        features_sha256=sha256(features_path), scores_sha256=sha256(path), row_count=len(rows),
        models_metadata_sha256=sha256(Path(folder) / "model_metadata.json")))
    print(f"Saved {len(rows):,} rows: {path}", flush=True)
    return result


def verified_scores(features_path, rows, folder, is_if=False):
    folder = Path(folder)
    path = folder / "weather_ai_scores.csv"
    meta = json.loads(path.with_suffix(".metadata.json").read_text(encoding="utf-8-sig"))
    if meta["features_sha256"] != sha256(features_path) or meta["models_metadata_sha256"] != sha256(folder / "model_metadata.json"):
        raise ValueError(f"{folder}: scores came from different inputs/models; regenerate that run")
    manifest = json.loads((folder / "model_metadata.json").read_text(encoding="utf-8-sig"))
    if manifest.get("feature_contract") != feature_contract(rows):
        raise ValueError(f"{folder}: incompatible feature contract")
    if is_if:
        if manifest.get("schema_version") != 2 or "algorithm" in manifest:
            raise ValueError("IF comparison requires the versioned IF run for the same reconstructed features")
    else:
        if meta.get("scores_sha256") != sha256(path):
            raise ValueError("scores checksum mismatch")
    scores = read_rows(path)
    if len(scores) != len(rows) or len(scores) != meta["row_count"]:
        raise ValueError("score/source row count mismatch")
    for original, scored in zip(rows, scores):
        if any(scored.get(k) != v for k, v in original.items()):
            raise ValueError("score rows must preserve all source features, keys, order and eligibility")
        for name in AI_FEATURES:
            value, raw = scored["S_" + name], scored[name + "_anomaly"]
            eligible = boolean(original[name.lower() + "_eligible"])
            if (value != "") != eligible or (raw != "") != eligible:
                raise ValueError("score missingness differs from model-specific eligibility")
            if eligible and (not np.isfinite(float(raw)) or not np.isfinite(float(value)) or not 0 <= float(value) <= 100):
                raise ValueError("invalid anomaly/percentile")
    return scores, manifest


def validate(features_path, folder):
    protect_output(folder, features_path)
    rows = read_features(features_path)
    models, manifest = load(folder)
    require_same_features(features_path, rows, manifest)
    actual, _ = verified_scores(features_path, rows, folder)
    expected = score_rows(rows, models, manifest)
    for left, right in zip(actual, expected):
        for name in AI_FEATURES:
            for field in (name + "_anomaly", "S_" + name):
                if right[field] is not None and not np.isclose(float(left[field]), right[field], rtol=1e-12, atol=1e-12):
                    raise ValueError(f"reloaded score mismatch: {row_key(left)}/{field}")
            if left[name + "_score_method"] != right[name + "_score_method"]:
                raise ValueError("score method mismatch")
        if left["comparison_model_id"] != manifest["model_id"] or left["algorithm"] != manifest["algorithm_label"]:
            raise ValueError("score model identity mismatch")
    report = dict(status="passed", algorithm=manifest["algorithm_label"], row_count=len(rows),
                  reloaded_scores_match=True, eligibility=eligibility_summary(rows))
    write_json(Path(folder) / "validation_report.json", report)
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    return report


def compare(features_path, if_dir, lof_dir, ecod_dir, destination):
    protect_output(destination, features_path)
    rows = read_features(features_path)
    loaded = {"IF": verified_scores(features_path, rows, if_dir, is_if=True),
              "LOF": verified_scores(features_path, rows, lof_dir),
              "ECOD": verified_scores(features_path, rows, ecod_dir)}
    for label, algorithm in (("LOF", "lof"), ("ECOD", "ecod")):
        if loaded[label][1].get("algorithm") != algorithm:
            raise ValueError("comparison folders contain the wrong algorithm")
    # Require identical eligible training matrices, not merely matching row counts.
    for name in AI_FEATURES:
        fingerprints = {manifest["models"][name]["training_matrix_sha256"] for _, manifest in loaded.values()}
        if len(fingerprints) != 1:
            raise ValueError(f"{name}: training matrix differs across algorithms")
    joined = []
    for i, row in enumerate(rows):
        record = {k: row[k] for k in ("station_code", "station_name", "work_date", "period", "ai1_eligible", "ai2_eligible")}
        for label, (scores, _) in loaded.items():
            for name in AI_FEATURES:
                record[f"{label}_S_{name}"] = scores[i]["S_" + name]
        joined.append(record)
    summary = []
    pairwise = []
    for period in sorted({r["period"] for r in rows}):
        for name in AI_FEATURES:
            indices = [i for i, r in enumerate(rows) if r["period"] == period and boolean(r[name.lower() + "_eligible"])]
            values = {label: np.asarray([float(scores[i]["S_" + name]) for i in indices])
                      for label, (scores, _) in loaded.items()}
            for label, scores in values.items():
                summary.append(dict(period=period, model=name, algorithm=label, eligible_rows=len(indices),
                                    mean=float(np.mean(scores)) if len(scores) else None,
                                    median=float(np.median(scores)) if len(scores) else None,
                                    p95=float(np.quantile(scores, .95)) if len(scores) else None,
                                    score_ge95_rows=int(np.count_nonzero(scores >= 95))))
            for a, b in (("IF", "LOF"), ("IF", "ECOD"), ("LOF", "ECOD")):
                x, y = values[a], values[b]
                correlation = float(spearmanr(x, y).statistic) if len(x) > 1 and np.ptp(x) > 0 and np.ptp(y) > 0 else None
                both = int(np.count_nonzero((x >= 95) & (y >= 95)))
                union = int(np.count_nonzero((x >= 95) | (y >= 95)))
                pairwise.append(dict(period=period, model=name, algorithm_a=a, algorithm_b=b,
                                     compared_rows=len(x), spearman=correlation,
                                     score_ge95_both=both, score_ge95_union=union,
                                     score_ge95_jaccard=both / union if union else None,
                                     score_ge95_disagreement_rows=int(np.count_nonzero((x >= 95) != (y >= 95)))))
    destination = Path(destination)
    write_rows(destination / "model_comparison.csv", joined)
    write_rows(destination / "score_summary.csv", summary)
    write_rows(destination / "pairwise_comparison.csv", pairwise)
    write_json(destination / "comparison_report.json", dict(
        row_count=len(rows), features_sha256=sha256(features_path),
        source_manifests={k: str(Path(p).resolve() / "model_metadata.json") for k, p in
                          (("IF", if_dir), ("LOF", lof_dir), ("ECOD", ecod_dir))},
        model_ids={k: v[1]["model_id"] for k, v in loaded.items()},
        eligibility=eligibility_summary(rows),
        notes=["95 is an illustrative anomaly threshold, not a payout rule or a guaranteed top 5%; ties are included.",
               "LOF uses native training reference and novelty held-out scores; their neighbor semantics differ.",
               "ECOD is a training-frozen inductive variant with a 1/n tail probability floor.",
               "These are descriptive comparisons, not Precision/Recall/F1 or proof of basis-risk reduction."] ))
    print(f"Saved IF/LOF/frozen ECOD comparisons: {destination}", flush=True)
    return joined, pairwise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("check", "train", "score", "validate", "compare"))
    parser.add_argument("--model", choices=("lof", "ecod"))
    parser.add_argument("--features", type=Path, default=DEFAULT_FEATURES)
    parser.add_argument("--neighbors", type=int, default=20, help="LOF training neighbors; fixed before evaluation")
    parser.add_argument("--if-dir", type=Path, default=DEFAULT_IF, help="read-only IF reference folder")
    args = parser.parse_args()
    if args.command in ("train", "score", "validate") and args.model is None:
        parser.error("train/score/validate require --model lof or --model ecod")
    if args.command == "check":
        rows = read_features(args.features)
        print(json.dumps(dict(features=str(args.features), total_rows=len(rows), eligibility=eligibility_summary(rows)),
                         ensure_ascii=False, indent=2))
    elif args.command == "train":
        train(args.features, output_folder(args.model), args.model, args.neighbors)
    elif args.command == "score":
        score(args.features, output_folder(args.model))
    elif args.command == "validate":
        validate(args.features, output_folder(args.model))
    else:
        compare(args.features, args.if_dir, output_folder("lof"), output_folder("ecod"), DATA_DIR / "step04_comparison")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
