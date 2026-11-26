"""Acceptance tests for official-daily recovery and refit contracts."""
import copy
import argparse
import csv
import pickle
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import isolation_forest_scores as base
import retrain_with_daily_rain as recovery
import test_isolation_forest_scores as fixtures
import validate_daily_recovery_results as saved_v2


def example(day="2023-07-01", station="001", complete=False):
    row = fixtures.row(day)
    row["station_code"] = station
    row["prior_24h_rain_mm"] = 4. if complete else None
    row["prior_rain_interval_count"] = 144 if complete else 143
    start = datetime.fromisoformat(day+"T08:00:00")
    previous = start-timedelta(days=1)
    points = {}
    for i in range(96):
        t = previous+i*base.STEP
        points[t] = (1. if i<6 else 2., 1. if i==6 else 0.)
    midnight = start.replace(hour=0)
    for i in range(1,49):
        points[midnight+i*base.STEP] = (1., 1. if i==1 else 0.)
    if complete:
        points[midnight] = (4.,2.)
    official = {(station,previous.date().isoformat()):{"value":4.,"status":"valid","queries":"test-query"}}
    return row,points,official


def training():
    rows = fixtures.training_rows()
    for r in rows:
        r["prior_rain_quality_hold"] = False
        recovery.update_eligibility(r)
    return rows


def models(rows, config=None):
    config = config or {}
    return {m:recovery.fit_recovered(rows,m,config,trees=10,min_samples=2) for m in base.FEATURES}


class RecoveryTests(unittest.TestCase):
    def test_midnight_absence_restored_and_observation_count_preserved(self):
        row,points,official = example()
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_24h_rain_mm"],4.)
        self.assertEqual(row["prior_rain_interval_count"],143)
        self.assertEqual(row["prior_rain_original_missing_intervals"],1)
        self.assertEqual(row["prior_reconstructed_midnight_mm"],2.)
        self.assertEqual(row["prior_rain_source"],"reconstructed_official_daily")
        self.assertTrue(row["AI1_model_eligible"])

    def test_midnight_reset_zero_is_not_assumed_zero_increment(self):
        row,points,official = example()
        points[datetime(2023,7,1)] = (0.,None)
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_reconstructed_midnight_mm"],2.)
        self.assertEqual(row["prior_24h_rain_mm"],4.)

    def test_valid_zero_daily_rain_is_recoverable(self):
        row,points,official = example()
        points={t:(0.,0.) for t in points}
        next(iter(official.values()))["value"]=0.
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_rain_recovery_status"],"restored")
        self.assertEqual(row["prior_24h_rain_mm"],0.)

    def test_official_missing_and_conflict_not_filled(self):
        for record,reason in ((None,"official_daily_not_covered"),
                              ({"value":None,"status":"missing_value","queries":"x"},"official_daily_missing"),
                              ({"value":None,"status":"conflicting_values","queries":"x"},"official_daily_conflict")):
            row,points,official=example()
            if record is None:official={}
            else:official[next(iter(official))]=record
            recovery.recover_row(row,points,official)
            self.assertIsNone(row["prior_24h_rain_mm"])
            self.assertEqual(row["prior_rain_recovery_reason"],reason)

    def test_missing_0800_is_held(self):
        for day in (datetime(2023,6,30,8),datetime(2023,7,1,8)):
            row,points,official=example()
            cumulative,increment=points[day]
            points[day]=(None,increment)
            recovery.recover_row(row,points,official)
            self.assertEqual(row["prior_rain_recovery_reason"],"0800_cumulative_missing")

    def test_other_gap_not_automatically_recovered(self):
        row,points,official=example()
        points[datetime(2023,6,30,12)] = (2.,None)
        row["prior_rain_interval_count"]=142
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_rain_recovery_reason"],"nonmidnight_observations_incomplete")

    def test_counter_decrease_is_held(self):
        row,points,official=example()
        points[datetime(2023,6,30,13)] = (1.,0.)
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_rain_recovery_reason"],"observed_counter_decrease")

    def test_official_total_below_counter_is_held(self):
        row,points,official=example()
        next(iter(official.values()))["value"]=1.
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_rain_recovery_reason"],"official_total_endpoint_inconsistent")

    def test_sum_disagreement_is_held(self):
        row,points,official=example()
        points[datetime(2023,6,30,12)]=(2.,.5)
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_rain_recovery_reason"],"reconstruction_sum_inconsistent")

    def test_complete_value_preserved_and_conflict_excluded_only_from_ai1(self):
        row,points,official=example(complete=True)
        next(iter(official.values()))["value"]=5.
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_24h_rain_mm"],4.)
        self.assertEqual(row["prior_rain_source"],"observed_10min")
        self.assertTrue(row["AI1_input_complete"])
        self.assertFalse(row["AI1_model_eligible"])
        self.assertTrue(row["AI2_model_eligible"])

    def test_complete_value_with_agreeing_daily_remains_observed(self):
        row,points,official=example(complete=True)
        recovery.recover_row(row,points,official)
        self.assertEqual(row["prior_rain_recovery_status"],"observed_unchanged")
        self.assertFalse(row["prior_rain_quality_hold"])

    def test_shinbuk_restoration_does_not_enable_ai1(self):
        row,points,official=example(station=recovery.EXCLUDED_AI1)
        recovery.recover_row(row,points,official)
        self.assertTrue(row["AI1_input_complete"])
        self.assertFalse(row["AI1_model_eligible"])
        self.assertEqual(row["AI1_exclusion_reason"],"station_excluded_wind_unavailable")
        self.assertTrue(row["AI2_model_eligible"])

    def test_year_and_leap_boundaries(self):
        for day,expected in (("2024-01-01","2023-12-31"),("2024-03-01","2024-02-29")):
            row,points,official=example(day)
            recovery.recover_row(row,points,official)
            self.assertEqual(row["prior_official_date"],expected)
            self.assertEqual(row["prior_24h_rain_mm"],4.)

    def test_source_points_and_other_features_are_unchanged(self):
        row,points,official=example()
        before=copy.deepcopy(points)
        old={k:row[k] for k in recovery.NUMERIC if k!="prior_24h_rain_mm"}
        recovery.recover_row(row,points,official)
        self.assertEqual(points,before)
        self.assertEqual(old,{k:row[k] for k in old})

    def test_baseline_count_disagreement_rejected(self):
        row,points,official=example();row["prior_rain_interval_count"]=144
        with self.assertRaisesRegex(ValueError,"count mismatch"):
            recovery.recover_row(row,points,official)

    def test_official_duplicate_and_invalid_status_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/"daily.csv"
            data=[{"station_code":"001","observation_date":"2023-06-30","daily_rain_mm":0.,"data_status":"valid","source_queries":"q"}]
            base.write_csv(path,data)
            self.assertEqual(recovery.load_official(path)[("001","2023-06-30")]["value"],0.)
            base.write_csv(path,data*2)
            with self.assertRaisesRegex(ValueError,"duplicate official"):
                recovery.load_official(path)
            data[0]["data_status"]="missing_value";base.write_csv(path,data)
            with self.assertRaisesRegex(ValueError,"must not contain"):
                recovery.load_official(path)


class ModelContractTests(unittest.TestCase):
    def test_validation_and_2026_rows_never_affect_fit_or_ecdf(self):
        rows=training()
        future=[]
        for day in ("2024-07-01","2026-07-01"):
            row=fixtures.row(day,rain=999.,wind=99.)
            row["prior_rain_quality_hold"]=False;recovery.update_eligibility(row)
            row["period"]="train"  # Fit must trust the date boundary, not this label.
            future.append(row)
        for model in base.FEATURES:
            a=recovery.fit_recovered(rows,model,{},trees=10,min_samples=2)
            b=recovery.fit_recovered(rows+future,model,{},trees=10,min_samples=2)
            self.assertEqual(pickle.dumps(a),pickle.dumps(b))

    def test_shinbuk_exclusion_applies_to_ai1_not_ai2_fit(self):
        rows=training();rows[0]["station_code"]=recovery.EXCLUDED_AI1
        recovery.update_eligibility(rows[0]);bundle=models(rows)
        self.assertEqual(bundle["AI1"]["metadata"]["training_rows"],19)
        self.assertEqual(bundle["AI2"]["metadata"]["training_rows"],20)
        recovery.score_recovered(rows,bundle,{})
        self.assertEqual(rows[0]["calculation_status"],"AI2_only")

    def test_contract_mismatch_rejected(self):
        rows=training();bundle=models(rows)
        for mutation in ("features","config"):
            bad=copy.deepcopy(bundle)
            bad["AI1"]["metadata"][mutation] = [] if mutation=="features" else {"different":True}
            with self.assertRaisesRegex(ValueError,"contract mismatch"):
                recovery.score_recovered(rows,bad,{})

    def test_training_membership_is_not_assumed_for_new_past_rows(self):
        rows=training();bundle=models(rows)
        novel=copy.deepcopy(rows[0]);novel["station_code"]="NEW"
        changed=copy.deepcopy(rows[0]);changed["work_rain_mm"]+=1.
        recovery.score_recovered([novel,changed],bundle,{})
        self.assertFalse(novel["AI1_training_used"])
        self.assertFalse(changed["AI1_training_used"])
        self.assertTrue(changed["AI2_training_used"])

    def test_ai2_fit_reference_and_scores_unchanged(self):
        rows=training()
        old=base.fit_bundle(rows,"AI2",{},trees=10,min_samples=2)
        new=models(rows)
        x=np.array([[r[k] for k in base.FEATURES["AI2"]] for r in rows])
        np.testing.assert_array_equal(old["reference"],new["AI2"]["reference"])
        recovery.score_recovered(rows,new,{})
        np.testing.assert_array_equal([r["AI2_raw_anomaly"] for r in rows],-old["forest"].score_samples(x))
        restored=pickle.loads(pickle.dumps(new))
        first=[r["S_AI2"] for r in rows]
        recovery.score_recovered(rows,restored,{})
        self.assertEqual(first,[r["S_AI2"] for r in rows])

    def test_model_id_changes_when_training_values_change(self):
        rows=training();before=models(rows)
        changed=copy.deepcopy(rows);changed[0]["work_rain_mm"]+=1.
        after=models(changed)
        self.assertNotEqual(before["AI1"]["metadata"]["model_id"],after["AI1"]["metadata"]["model_id"])
        self.assertEqual(before["AI2"]["metadata"]["model_id"],after["AI2"]["metadata"]["model_id"])


class EndToEndTests(unittest.TestCase):
    def test_pipeline_restores_holdout_without_learning_it_and_saved_audit_passes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);weather=root/"weather.csv";targets=root/"targets.csv"
            days=("2023-07-01","2023-07-03","2024-07-01","2026-07-01")
            base.write_csv(targets,[fixtures.target(day) for day in days])
            observations={};daily=[]
            for i,day in enumerate(days):
                data=fixtures.points(day,rain=float(i),wind=float(i),temperature=25.+i)
                previous_start=datetime.fromisoformat(day+"T08:00:00")-timedelta(days=1)
                data[previous_start]=(float(i),float(i),25.+i,60.)
                for stamp,values in data.items():
                    cumulative=float(i)*(144 if stamp.hour==0 and stamp.minute==0 else (stamp.hour*60+stamp.minute)//10)
                    increment=values[0]
                    if day=="2024-07-01" and stamp==datetime(2024,7,1):increment=None
                    observations[stamp]=(cumulative,increment,*values[1:])
                if i<3:daily.append({"station_code":"001","observation_date":previous_start.date().isoformat(),
                                     "daily_rain_mm":144.*i,"data_status":"valid","source_queries":"fixture"})
            with weather.open("w",newline="") as stream:
                writer=csv.writer(stream)
                writer.writerow(["station_code","station_name","observed_at","daily_cumulative_rain_mm","rain_10min_mm","wind_mps","temperature_c","humidity_pct"])
                for stamp,values in sorted(observations.items()):writer.writerow(["001","test",stamp.isoformat(),*values])
            official=root/"daily.csv";base.write_csv(official,daily)
            baseline=root/"baseline"
            args=argparse.Namespace(weather=weather,targets=targets,output_dir=baseline,seed=42,trees=10,min_training_rows=2)
            base.run(args)
            old_hashes={p.name:base.sha256(p) for p in baseline.iterdir() if p.is_file()}
            args=argparse.Namespace(weather=weather,baseline_dir=baseline,official_daily=official,output_dir=root/"recovered",seed=42,trees=10,min_training_rows=2)
            report=recovery.run(args)
            self.assertEqual(report["models"]["AI1"]["training_rows"],2)
            self.assertEqual(report["restored_rows_by_period"],{"validation":1})
            self.assertEqual(saved_v2.validate(args.output_dir)["status"],"passed")
            self.assertEqual(old_hashes,{p.name:base.sha256(p) for p in baseline.iterdir() if p.is_file()})
            with (args.output_dir/"weather_ai_scores.csv").open("a") as stream:stream.write("tamper")
            with self.assertRaisesRegex(ValueError,"hash mismatch"):saved_v2.validate(args.output_dir)


if __name__=="__main__":unittest.main()
