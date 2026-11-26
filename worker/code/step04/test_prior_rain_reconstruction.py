"""Known-answer tests for official daily reconstruction, provenance and frozen contracts."""

import csv
import json
import tempfile
import unittest
from dataclasses import replace
from datetime import date, datetime, time, timedelta
from pathlib import Path

import numpy as np

from common import (FEATURE_COLUMNS, OUTPUT_DIR, check_rows, read_rows, sha256, write_rows)
from diagnostics import diagnose, diagnose_file
from pooled_isolation_forest import fit_model, load_models, score_file, train_models
from prior_rain_reconstruction import COLUMNS, POLICY, load_daily
from validate_isolation_forest import validate, validate_features
from weather_ai_features import Observation, aggregate, build_features

STEP=timedelta(minutes=10)


def fixture(day="2024-01-01", station="001", total=20, a=5, b=3, last=18, observed=False):
    start=datetime.combine(date.fromisoformat(day),time(8))
    previous=start-timedelta(days=1)
    midnight=start.replace(hour=0)
    points={}
    for i in range(96):
        c=a+(last-a)*i/95
        points[previous+i*STEP]=Observation(0 if i==0 else (last-a)/95,2,25,60,c)
    points[midnight]=Observation(total-last if observed else None,2,25,60,total if observed else 0)
    for i in range(1,49):
        points[midnight+i*STEP]=Observation(b/48,2,25,60,b*i/48)
    for i in range(1,55):
        points[start+i*STEP]=Observation(0,2,25,60,b)
    target=dict(station_code=station,station_name="test",work_date=day,work_start="08:00",work_end="17:00",
                is_mountain=False,period="train" if day<="2023-12-31" else "validation" if day<="2025-12-31" else "after_validation",
                S_R="",S_W="",S_H="")
    record={"value":float(total),"status":"valid","queries":"same-station-daily"}
    daily={(station,previous.date().isoformat()):record}
    return points,target,daily


class ReconstructionTests(unittest.TestCase):
    def test_known_answer_reset_and_missing_midnight_preserve_counts(self):
        for missing_row in (False,True):
            p,t,d=fixture()
            if missing_row:
                del p[datetime(2024,1,1)]
            original=aggregate(p,t)
            r=aggregate(p,t,d)
            self.assertAlmostEqual(r["prior_24h_rain_mm"],18)
            self.assertEqual(r["prior_rain_interval_count"],143)
            self.assertEqual(r["prior_rain_missing_interval_count"],1)
            self.assertEqual(r["prior_rain_source"],"reconstructed_official_daily")
            self.assertIsNone(r["prior_rain_original_mm"])
            self.assertEqual(r["prior_rain_last_10min_candidate_mm"],2)
            self.assertTrue(r["ai1_eligible"])
            for key in ("work_rain_mm","work_rain_minutes","work_max_wind_mps","work_heat_minutes_ge31","work_max_temp_c"):
                self.assertEqual(r[key],original[key])
            check_rows([r])

    def test_official_zero_is_a_valid_reconstruction(self):
        p,t,d=fixture(total=0,a=0,b=0,last=0)
        r=aggregate(p,t,d)
        self.assertEqual(r["prior_24h_rain_mm"],0)
        self.assertTrue(r["ai1_eligible"])
        check_rows([r])

    def test_leap_day_and_year_boundary_use_previous_calendar_date(self):
        for day,previous in (("2016-01-01","2015-12-31"),("2020-03-01","2020-02-29"),("2024-03-01","2024-02-29")):
            p,t,d=fixture(day)
            r=aggregate(p,t,d)
            self.assertEqual(r["prior_rain_official_date"],previous)
            self.assertAlmostEqual(r["prior_24h_rain_mm"],18)
            check_rows([r])

    def test_missing_conflicting_negative_and_nonfinite_official_values_are_held(self):
        for status,total in (("missing_value",None),("conflict",20),("valid",-1),("valid",float("nan"))):
            p,t,d=fixture()
            d={k:{**v,"status":status,"value":total if total is None or (np.isfinite(total) and total>=0) else None} for k,v in d.items()}
            r=aggregate(p,t,d)
            self.assertIsNone(r["prior_24h_rain_mm"])
            self.assertFalse(r["ai1_eligible"])
            self.assertTrue(r["ai2_eligible"])
            check_rows([r])

    def test_official_loader_rejects_duplicate_keys_and_preserves_invalid_zero(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"daily.csv"
            rows=[dict(station_code="001",observation_date="2023-12-31",daily_rain_mm="0",data_status="valid"),
                  dict(station_code="002",observation_date="2023-12-31",daily_rain_mm="-1",data_status="valid")]
            write_rows(p,rows)
            d=load_daily(p)
            self.assertEqual(d["001","2023-12-31"]["value"],0)
            self.assertIsNone(d["002","2023-12-31"]["value"])
            write_rows(p,rows+[rows[0]])
            with self.assertRaisesRegex(ValueError,"duplicate"):
                load_daily(p)

    def test_missing_other_interval_or_start_value_is_not_filled(self):
        for stamp,reason in ((datetime(2024,1,1,1),"other_prior_intervals_missing"),
                             (datetime(2023,12,31,8),"missing_cumulative_endpoint")):
            p,t,d=fixture()
            del p[stamp]
            r=aggregate(p,t,d)
            self.assertIsNone(r["prior_24h_rain_mm"])
            self.assertEqual(r["prior_rain_hold_reason"],reason)

    def test_negative_last_increment_and_cumulative_decrease_are_held(self):
        p,t,d=fixture()
        for record in d.values():record["value"]=17
        r=aggregate(p,t,d)
        self.assertEqual(r["prior_rain_hold_reason"],"negative_reconstruction")
        self.assertIsNone(r["prior_24h_rain_mm"])
        p,t,d=fixture()
        stamp=datetime(2024,1,1,2)
        p[stamp]=replace(p[stamp],cumulative=99)
        r=aggregate(p,t,d)
        self.assertEqual(r["prior_rain_hold_reason"],"cumulative_decrease")
        self.assertIsNone(r["prior_24h_rain_mm"])

    def test_formula_and_interval_sum_must_agree(self):
        p,t,d=fixture()
        stamp=datetime(2024,1,1,1)
        p[stamp]=replace(p[stamp],rain=99)
        r=aggregate(p,t,d)
        self.assertEqual(r["prior_rain_hold_reason"],"formula_interval_mismatch")
        self.assertIsNone(r["prior_24h_rain_mm"])

    def test_observed_value_is_preserved_and_conflict_is_excluded(self):
        p,t,d=fixture(observed=True)
        original=aggregate(p,t)
        r=aggregate(p,t,d)
        self.assertEqual(r["prior_rain_source"],"observed_10min")
        self.assertEqual(r["prior_24h_rain_mm"],original["prior_24h_rain_mm"])
        self.assertEqual(r["prior_rain_status"],"observed_agrees")
        for record in d.values():record["value"]=21
        r=aggregate(p,t,d)
        self.assertEqual(r["prior_rain_status"],"observed_conflict")
        self.assertEqual(r["prior_24h_rain_mm"],original["prior_24h_rain_mm"])
        self.assertFalse(r["ai1_eligible"])
        self.assertTrue(r["ai2_eligible"])
        check_rows([r])

    def test_missing_official_record_keeps_complete_observations(self):
        p,t,_=fixture(observed=True)
        r=aggregate(p,t,{})
        self.assertTrue(r["ai1_eligible"])
        self.assertEqual(r["prior_rain_status"],"observed_not_compared")
        check_rows([r])

    def test_sinbuk_station_excludes_ai1_only(self):
        p,t,d=fixture(station="487915A001")
        r=aggregate(p,t,d)
        self.assertIsNotNone(r["prior_24h_rain_mm"])
        self.assertFalse(r["ai1_eligible"])
        self.assertTrue(r["ai2_eligible"])
        self.assertEqual(r["ai1_policy_exclusion"],"station_excluded_wind_unavailable")
        check_rows([r])

    def test_stored_provenance_cannot_forge_observation_count_or_value(self):
        p,t,d=fixture()
        r=aggregate(p,t,d)
        for field,replacement in (("prior_rain_interval_count",144),("prior_24h_rain_mm",999),
                                  ("prior_rain_official_date","2024-01-01")):
            bad=dict(r,**{field:replacement})
            with self.assertRaises(ValueError):
                check_rows([bad])

    def test_restoration_refuses_baseline_output_before_reading_weather(self):
        with self.assertRaisesRegex(ValueError,"separate output"):
            build_features(Path("absent"),Path("absent"),OUTPUT_DIR/"features.csv",Path("absent"))

    def test_other_work_start_requires_separate_validation(self):
        p,t,d=fixture()
        with self.assertRaisesRegex(ValueError,"08:00"):
            aggregate(p,dict(t,work_start="09:00"),d)

    def test_diagnosis_and_training_refuse_protected_baseline_folder(self):
        p,t,d=fixture()
        rows=[aggregate(p,t,d)]
        with self.assertRaisesRegex(ValueError,"separate output"):
            diagnose(rows,OUTPUT_DIR)
        with tempfile.TemporaryDirectory() as folder:
            features=Path(folder)/"features.csv"
            write_rows(features,rows,(*FEATURE_COLUMNS,*COLUMNS))
            with self.assertRaisesRegex(ValueError,"separate output"):
                train_models(features,OUTPUT_DIR)


class ReconstructionPipelineTests(unittest.TestCase):
    def build_fixture_files(self,root):
        all_points={};targets=[];daily_rows=[]
        for day,total in (("2016-01-01",20),("2023-12-31",25),("2024-01-03",30),("2026-01-01",35)):
            p,t,d=fixture(day,total=total)
            all_points.update(p);targets.append(t)
            for (station,previous),record in d.items():
                daily_rows.append(dict(station_code=station,observation_date=previous,daily_rain_mm=record["value"],data_status="valid",source_queries=record["queries"]))
        clean,targets_path,daily_path=root/"clean.csv",root/"targets.csv",root/"daily.csv"
        with clean.open("w",newline="") as f:
            w=csv.writer(f);w.writerow(["station_code","observed_at","rain_10min_mm","wind_mps","temperature_c","humidity_pct","daily_cumulative_rain_mm"])
            for stamp,p in sorted(all_points.items()):
                w.writerow(["001",stamp.isoformat(),p.rain,p.wind,p.temperature,p.humidity,p.cumulative])
        write_rows(targets_path,targets);write_rows(daily_path,daily_rows)
        return clean,targets_path,daily_path

    def test_full_restored_pipeline_contract_and_provenance(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);clean,targets,daily=self.build_fixture_files(root)
            baseline=root/"baseline.csv";features=root/"restored.csv";scores=root/"scores.csv"
            build_features(clean,targets,baseline)
            built=build_features(clean,targets,features,daily)
            self.assertEqual(sum(r["prior_rain_source"]=="reconstructed_official_daily" for r in built),4)
            self.assertEqual(validate_features(features,targets)["status"],"passed")
            diagnose_file(features,root,baseline_path=baseline)
            self.assertTrue((root/"reconstruction_eligibility_comparison.csv").exists())
            manifest=train_models(features,root,{"n_estimators":12})
            self.assertEqual(manifest["schema_version"],2)
            self.assertEqual(manifest["models"]["AI1"]["train_rows"],2)
            self.assertEqual(manifest["feature_contract"]["prior_rain_policy"],POLICY)
            score_file(features,root,scores)
            validate(features,scores,root,targets,root/"validation.json")
            with self.assertRaisesRegex(ValueError,"contract"):
                score_file(baseline,root,root/"wrong_contract.csv")
            # Stored baseline estimators must not silently accept the new reconstruction contract.
            legacy_rows=[]
            for day in ("2016-01-01","2023-12-31"):
                p,t,_=fixture(day,observed=True)
                legacy_rows.append(aggregate(p,t))
            legacy_features=root/"legacy_features.csv"
            write_rows(legacy_features,legacy_rows,FEATURE_COLUMNS)
            train_models(legacy_features,root/"legacy_models",{"n_estimators":12})
            with self.assertRaisesRegex(ValueError,"legacy observed-only"):
                score_file(features,root/"legacy_models",root/"legacy_rejected.csv")
            manifest_path=root/"model_metadata.json"
            original_manifest=manifest_path.read_text(encoding="utf-8-sig")
            altered=json.loads(original_manifest)
            altered["feature_contract"]["heat_threshold_c"]=33
            manifest_path.write_text(json.dumps(altered),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"work settings differ"):
                score_file(features,root,root/"settings_rejected.csv")
            manifest_path.write_text(original_manifest,encoding="utf-8")
            before=sha256(features)
            daily.write_text(daily.read_text()+"\n",encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"changed"):
                validate_features(features,targets)
            self.assertEqual(sha256(features),before)

    def test_future_daily_extremes_do_not_change_training_models_or_ecdf(self):
        rows=[]
        for day in ("2016-01-01","2023-12-31","2024-01-01","2026-01-01"):
            p,t,d=fixture(day)
            rows.append(aggregate(p,t,d))
        changed=list(rows[:2])
        for day in ("2024-01-01","2026-01-01"):
            p,t,d=fixture(day,total=100000)
            changed.append(aggregate(p,t,d))
        for name in ("AI1","AI2"):
            _,reference,info=fit_model(rows,name,{"n_estimators":12})
            _,other,other_info=fit_model(changed,name,{"n_estimators":12})
            np.testing.assert_array_equal(reference,other)
            self.assertEqual(info,other_info)


if __name__=="__main__":
    unittest.main()
