"""LOF novelty detection and an inductive, training-frozen ECOD variant.

ECOD's tail/skew combination follows Li et al., ECOD (2022), section III.
This is not the transductive PyOD implementation: query rows never update CDFs.
"""

import hashlib
import json

import numpy as np
from scipy.stats import skew
from sklearn.neighbors import LocalOutlierFactor
from sklearn.preprocessing import StandardScaler


def fingerprint(keys, values):
    return hashlib.sha256(json.dumps(
        [(*key, *map(float, row)) for key, row in zip(keys, values)],
        separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def finite_matrix(values):
    values = np.asarray(values, dtype=np.float64)
    if values.ndim != 2 or not len(values) or not np.isfinite(values).all():
        raise ValueError("a nonempty finite feature matrix is required")
    return values


class FrozenECOD:
    """Training ECDFs, skew signs and 1/n tail floor are frozen at fit time."""

    def fit(self, values):
        values = finite_matrix(values)
        self.n_train_, self.n_features_in_ = values.shape
        if self.n_train_ < 2:
            raise ValueError("ECOD needs at least two training rows")
        self.sorted_features_ = np.sort(values, axis=0)
        self.skew_sign_ = np.zeros(self.n_features_in_, dtype=int)
        for j in range(self.n_features_in_):
            column = values[:, j]
            # scipy's skew is undefined for constant columns; use symmetric tails.
            if np.ptp(column) > 0:
                value = skew(column, bias=True)
                if not np.isfinite(value):
                    raise ValueError("nonfinite training skewness")
                self.skew_sign_[j] = int(np.sign(value))
        return self

    def anomaly(self, values):
        values = finite_matrix(values)
        if values.shape[1] != self.n_features_in_:
            raise ValueError("ECOD feature count mismatch")
        result = np.zeros(len(values))
        for j in range(self.n_features_in_):
            reference = self.sorted_features_[:, j]
            left = np.searchsorted(reference, values[:, j], side="right") / self.n_train_
            right = (self.n_train_ - np.searchsorted(reference, values[:, j], side="left")) / self.n_train_
            # Explicit finite-sample adaptation for unseen values outside train support.
            ul = -np.log(np.maximum(left, 1.0 / self.n_train_))
            ur = -np.log(np.maximum(right, 1.0 / self.n_train_))
            sign = self.skew_sign_[j]
            skew_tail = ul * (sign <= 0) + ur * (sign >= 0)
            result += np.maximum(np.maximum(ul, ur), skew_tail)
        return result


class ComparisonModel:
    """Persist preprocessing, native training scores and the frozen score ECDF."""

    def __init__(self, algorithm, neighbors=20):
        if algorithm not in ("lof", "ecod"):
            raise ValueError("algorithm must be lof or ecod")
        self.algorithm = algorithm
        self.neighbors = neighbors

    def fit(self, values, keys):
        values = finite_matrix(values)
        keys = [tuple(k) for k in keys]
        if len(keys) != len(values) or len(set(keys)) != len(keys):
            raise ValueError("training keys must be unique and match input rows")
        if len(values) < 2:
            raise ValueError("at least two eligible training rows are required")
        self.n_features_in_ = values.shape[1]
        self.training_matrix_sha256_ = fingerprint(keys, values)
        self.training_values_ = {k: row.copy() for k, row in zip(keys, values)}
        if self.algorithm == "lof":
            if self.neighbors < 1:
                raise ValueError("neighbors must be positive")
            self.scaler_ = StandardScaler().fit(values)
            self.estimator_ = LocalOutlierFactor(
                n_neighbors=min(self.neighbors, len(values) - 1), novelty=True,
                algorithm="kd_tree", metric="euclidean", contamination="auto", n_jobs=1,
            ).fit(self.scaler_.transform(values))
            # sklearn explicitly disallows novelty score_samples on fitted rows.
            training_anomaly = -self.estimator_.negative_outlier_factor_
        else:
            self.estimator_ = FrozenECOD().fit(values)
            training_anomaly = self.estimator_.anomaly(values)
        if not np.isfinite(training_anomaly).all():
            raise ValueError("nonfinite training anomalies")
        self.training_anomaly_ = dict(zip(keys, map(float, training_anomaly)))
        self.reference_ = np.sort(training_anomaly)
        return self

    def novelty_anomaly(self, values):
        values = finite_matrix(values)
        if values.shape[1] != self.n_features_in_:
            raise ValueError("feature count mismatch")
        if self.algorithm == "ecod":
            return self.estimator_.anomaly(values)
        # Bound temporary neighbor-query memory without changing scores.
        return np.concatenate([
            -self.estimator_.score_samples(self.scaler_.transform(values[i:i + 2048]))
            for i in range(0, len(values), 2048)
        ])

    def anomaly(self, values, keys, training):
        values = finite_matrix(values)
        if len(keys) != len(values) or len(training) != len(values):
            raise ValueError("score keys/periods must match feature rows")
        result = np.empty(len(values))
        novel = []
        for i, (key, is_training) in enumerate(zip(keys, training)):
            key = tuple(key)
            if is_training:
                if key not in self.training_values_ or not np.array_equal(values[i], self.training_values_[key]):
                    raise ValueError("training membership or features changed; retrain in the comparison folder")
                result[i] = self.training_anomaly_[key]
            else:
                if key in self.training_values_:
                    raise ValueError("fitted training key cannot be scored as a novelty sample")
                novel.append(i)
        if novel:
            result[novel] = self.novelty_anomaly(values[novel])
        if not np.isfinite(result).all():
            raise ValueError("nonfinite query anomalies")
        return result
