"""Known-answer boundaries, independent eligibility, leakage, persistence and diagnostics."""

import csv
import json
import tempfile
import unittest
from dataclasses import replace
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np

from common import AI_FEATURES, FEATURE_COLUMNS, check_rows, read_rows, write_rows
from diagnostics import diagnose
from pooled_isolation_forest import (fit_model, load_models, matrix, percentile,
                                     score_file, score_rows, train_models)
from validate_isolation_forest import validate
from weather_ai_features import Observation, aggregate, build_features, load_points

DAY="2024-07-08"
START=datetime.fromisoformat(DAY+"T08:00")
STEP=timedelta(minutes=10)
PARAMS={"n_estimators":16}


def target(day=DAY,station="001",mountain=False):
    from common import period_for
    return dict(station_code=station,station_name="test",work_date=day,work_start="08:00",
                work_end="17:00",is_mountain=mountain,period=period_for(day),S_R="",S_W="",S_H="")


def points(day=DAY,rain=0,wind=0,temperature=25,humidity=60):
    start=datetime.fromisoformat(day+"T08:00")
    return {start+i*STEP:Observation(rain,wind,temperature,humidity) for i in range(-143,55)}


def row(day=DAY,station="001",**values):
    return aggregate(points(day,**values),target(day,station))


def samples():
    return [row("2016-01-01",rain=0,wind=0,temperature=20),
            row("2019-07-01",rain=.5,wind=14,temperature=33),
            row("2023-12-31",rain=2,wind=20,temperature=35),
            row("2024-07-08",rain=5,wind=25,temperature=38),
            row("2026-01-01",rain=10,wind=30,temperature=40)]


class AggregationTests(unittest.TestCase):
    def test_interval_boundaries_and_true_zero(self):
        p=points()
        for stamp in (START-144*STEP,START+55*STEP):
            p[stamp]=Observation(999,999,99,99)
        p[START]=Observation(1,999,99,99)
        r=aggregate(p,target())
        self.assertEqual(r["work_rain_mm"],0)
        self.assertEqual(r["prior_24h_rain_mm"],1)
        self.assertEqual(r["work_rain_minutes"],0)
        self.assertEqual(r["work_max_wind_mps"],0)
        self.assertEqual(r["work_max_temp_c"],25)
        self.assertEqual(r["scheduled_interval_count"],54)
        self.assertEqual(r["prior_rain_interval_count"],144)
        self.assertTrue(r["ai1_eligible"])
        self.assertTrue(r["ai2_eligible"])
        check_rows([r])

    def test_precipitation_and_wind_durations_are_accumulated_not_consecutive(self):
        p=points()
        p[START+STEP]=Observation(1,14,25,60)
        p[START+3*STEP]=Observation(2,17,25,60)
        normal=aggregate(p,target())
        mountain=aggregate(p,target(mountain=True))
        self.assertEqual(normal["work_rain_mm"],3)
        self.assertEqual(normal["work_rain_minutes"],20)
        self.assertEqual(normal["work_strong_wind_minutes"],20)
        self.assertEqual(mountain["work_strong_wind_minutes"],10)

    def test_model_selection_all_four_cases_and_scores_not_used(self):
        both=points()
        ai1=points(); ai1[START+STEP]=replace(ai1[START+STEP],temperature=None)
        ai2=points(); ai2[START]=replace(ai2[START],rain=None)
        neither=dict(ai1); neither[START]=replace(neither[START],rain=None)
        for p,status in ((both,"both"),(ai1,"AI1_only"),(ai2,"AI2_only"),(neither,"neither")):
            with self.subTest(status=status):
                self.assertEqual(aggregate(p,target())["ai_input_status"],status)

    def test_partial_maxima_are_diagnostic_only(self):
        p=points(wind=25)
        p[START+STEP]=replace(p[START+STEP],wind=None)
        r=aggregate(p,target())
        self.assertEqual(r["observed_work_max_wind_mps"],25)
        self.assertIsNone(r["work_max_wind_mps"])
        self.assertIsNone(r["work_strong_wind_minutes"])
        self.assertFalse(r["ai1_eligible"])

    def test_temperature_humidity_pair_completeness(self):
        p=points(temperature=35,humidity=70)
        r=aggregate(p,target())
        self.assertEqual(r["work_heat_minutes_ge31"],540)
        p[START+STEP]=replace(p[START+STEP],humidity=None)
        r=aggregate(p,target())
        self.assertEqual(r["work_max_temp_c"],35)
        self.assertIsNone(r["work_mean_humidity_pct"])
        self.assertIsNone(r["work_heat_minutes_ge31"])
        self.assertEqual(r["work_heat_interval_count"],53)

    def test_different_step03_window_and_no_observations(self):
        t=dict(target(),work_end="16:00")
        r=aggregate(points(),t)
        self.assertEqual(r["scheduled_interval_count"],48)
        empty=aggregate({},t)
        self.assertEqual(empty["ai_input_status"],"neither")
        self.assertEqual(empty["work_observation_count"],0)
        check_rows([empty])

    def test_count_mismatch_and_invalid_period_rejected(self):
        r=row(); r["work_wind_interval_count"]=53
        with self.assertRaisesRegex(ValueError,"completeness/count"):
            check_rows([r])
        with self.assertRaisesRegex(ValueError,"fixed"):
            aggregate(points(),dict(target(),period="train"))

    def test_raw_duplicates_and_out_of_range_are_handled(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"raw.csv"
            columns=["station_code","observed_at","rain_10min_mm","wind_mps","temperature_c","humidity_pct"]
            record=["001",START.isoformat(),-1,-1,25,101]
            with p.open("w",newline="") as f:
                w=csv.writer(f);w.writerow(columns);w.writerows([record,record])
            observations=load_points(p)
            self.assertEqual(len(observations),1)
            self.assertEqual(observations[START],Observation(None,None,25,None))
            with p.open("a",newline="") as f:
                csv.writer(f).writerow(["001",START.isoformat(),0,0,30,60])
            with self.assertRaisesRegex(ValueError,"conflicting"):
                load_points(p)


class ModelTests(unittest.TestCase):
    def test_ecdf_direction_ties_and_outside_range(self):
        np.testing.assert_array_equal(percentile([.2,.2,.4,.8],[.1,.2,.3,.4,.8,1]),[0,50,50,75,100,100])
        for reference in ([],[2,1],[float("nan")]):
            with self.assertRaises(ValueError):
                percentile(reference,[1])

    def test_future_rows_do_not_change_either_model_or_reference(self):
        baseline=samples()
        changed=baseline[:3]+[row("2024-07-08",rain=10000,wind=999,temperature=50),
                               row("2026-01-01",rain=20000,wind=888,temperature=-50)]
        for name,columns in AI_FEATURES.items():
            a,ref_a,info_a=fit_model(baseline,name,PARAMS)
            b,ref_b,info_b=fit_model(changed,name,PARAMS)
            np.testing.assert_array_equal(ref_a,ref_b)
            self.assertEqual(info_a,info_b)
            np.testing.assert_array_equal(a.score_samples(matrix(baseline,columns)),b.score_samples(matrix(baseline,columns)))

    def test_partial_training_history_and_row_order_do_not_change_fit(self):
        rows=[row("2019-10-01"),row("2023-12-31",rain=1),row("2024-07-08")]
        _,ref,info=fit_model(rows,"AI1",PARAMS)
        _,again,other=fit_model(list(reversed(rows)),"AI1",PARAMS)
        np.testing.assert_array_equal(ref,again)
        self.assertEqual(info,other)
        self.assertEqual(info["actual_train_start"],"2019-10-01")
        self.assertEqual(info["train_rows"],2)

    def test_no_training_fallback_to_future(self):
        with self.assertRaisesRegex(ValueError,"no complete training"):
            fit_model([row()],"AI1",PARAMS)

    def test_model_specific_scoring_preserves_excluded_rows(self):
        models={name:fit_model(samples(),name,PARAMS)[:2] for name in AI_FEATURES}
        p=points();p[START]=replace(p[START],rain=None)
        rows=[aggregate(p,target()),aggregate({},target("2024-07-09"))]
        result=score_rows(rows,models)
        self.assertEqual(len(result),2)
        self.assertIsNone(result[0]["S_AI1"])
        self.assertIsNotNone(result[0]["S_AI2"])
        self.assertEqual(result[0]["ai_score_status"],"AI2_only")
        self.assertIsNone(result[1]["S_AI1"])
        self.assertIsNone(result[1]["S_AI2"])


class PipelineTests(unittest.TestCase):
    def test_diagnostics_denominators_and_overlapping_reasons(self):
        p=points("2024-07-09");stamp=START+timedelta(days=1)+STEP
        p[stamp]=replace(p[stamp],rain=None)
        rows=[row(),aggregate(p,target("2024-07-09"))]
        with tempfile.TemporaryDirectory() as folder:
            report=diagnose(rows,folder)
            ai1=next(r for r in report["groups"] if r["model"]=="AI1" and r["station_code"]=="__ALL__")
            self.assertEqual(ai1["excluded_rows"],1)
            self.assertEqual(ai1["excluded_rate_pct"],50)
            self.assertEqual(ai1["missing_combinations"],{"work_rain_mm|work_rain_minutes":1})
            status=read_rows(Path(folder)/"eligibility_combinations.csv")
            global_rows=[r for r in status if r["station_code"]=="__ALL__"]
            self.assertEqual(sum(int(r["rows"]) for r in global_rows),2)

    def test_full_pipeline_saved_reloaded_and_missing_targets_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            raw=root/"clean.csv";targets=root/"targets.csv";features=root/"features.csv";scores=root/"scores.csv"
            target_rows=[target(r["work_date"]) for r in samples()]+[target("2024-07-09","missing")]
            write_rows(targets,target_rows)
            with raw.open("w",newline="") as f:
                writer=csv.writer(f)
                writer.writerow(["station_code","observed_at","rain_10min_mm","wind_mps","temperature_c","humidity_pct"])
                for r in samples():
                    p=points(r["work_date"],rain=r["work_rain_mm"]/54,wind=r["work_max_wind_mps"],temperature=r["work_max_temp_c"])
                    for stamp,obs in p.items():
                        writer.writerow(["001",stamp.isoformat(),obs.rain,obs.wind,obs.temperature,obs.humidity])
            built=build_features(raw,targets,features)
            self.assertEqual(len(built),6)
            diagnose(built,root)
            manifest=train_models(features,root,PARAMS)
            self.assertEqual(manifest["models"]["AI1"]["train_rows"],3)
            before=load_models(root)
            first=score_file(features,root,scores)
            second=score_file(features,root,root/"scores2.csv")
            self.assertEqual(first,second)
            self.assertEqual(manifest,before[1])
            report=validate(features,scores,root,targets,root/"validation.json")
            self.assertEqual(report["status"],"passed")
            actual=read_rows(scores)
            self.assertEqual(actual[-1]["S_AI1"],"")
            self.assertEqual(actual[-1]["S_AI2"],"")
            self.assertEqual(actual[-1]["ai_score_status"],"neither")
            diagnostics_path=root/"input_diagnostics.json"
            saved=diagnostics_path.read_text(encoding="utf-8-sig")
            tampered=json.loads(saved)
            tampered["groups"][0]["excluded_rate_pct"]=999
            diagnostics_path.write_text(json.dumps(tampered),encoding="utf-8")
            with self.assertRaisesRegex(ValueError,"denominator"):
                validate(features,scores,root,targets,root/"validation.json")
            diagnostics_path.write_text(saved,encoding="utf-8")
            model_path=root/"isolation_forest_r1.pkl"
            model_path.write_bytes(model_path.read_bytes()+b"modified")
            with self.assertRaisesRegex(ValueError,"checksum"):
                load_models(root)

    def test_duplicate_targets_are_rejected_before_loading_observations(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);p=root/"targets.csv"
            write_rows(p,[target(),target()])
            with self.assertRaisesRegex(ValueError,"duplicate STEP03"):
                build_features(root/"does_not_exist.csv",p,root/"features.csv")


if __name__=="__main__":
    unittest.main()
