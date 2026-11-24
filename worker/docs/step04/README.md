# STEP04 — Isolation Forest 기상 이상지수

STEP04는 기상자료만으로 강수·강풍 AI1, 온열 AI2의 주 모델 두 개를 학습합니다. 보조 모델은 없습니다. 작목 코드, S_R·S_W·S_H와의 결합, R1·R2, 지급 기준은 STEP05입니다. 파일명 `r1`, `r2`는 위험군 식별이며 결합지수가 아닙니다. 현재 정책은 이 문서와 STEP03의 현재 README를 따릅니다. archive의 과거 학습기간 제약은 적용하지 않습니다.

## 현재 구현 상태

**초기 구현·검증 단계입니다. 공식 일강수량 기반 복원 로직은 생산 코드·기존 모델에 아직 반영하지 않았으며, 모델 유용성 추가 검증이 필요합니다.** 완료 범위와 다음 작업은 [구현 상태와 남은 작업](STATUS.md)을 먼저 확인하세요.

## 실행

Python 3.11 기준으로 검증한 고정 의존성은 `worker/code/step04/requirements.txt`입니다. 저장소 루트에서 실행합니다.

```powershell
python -m venv worker/.venv-step04
worker/.venv-step04/Scripts/python.exe -m pip install -r worker/code/step04/requirements.txt
worker/.venv-step04/Scripts/python.exe -B -m unittest discover -s worker/code/step04 -p test_isolation_forest_scores.py -v
worker/.venv-step04/Scripts/python.exe -B worker/code/step04/isolation_forest_scores.py
worker/.venv-step04/Scripts/python.exe -B worker/code/step04/validate_saved_results.py
```

매 실행마다 **2016~2023년만으로 재학습**합니다. `--weather`, `--targets`, `--output-dir`로 경로를 바꿀 수 있습니다. 기본 입력은 `worker/data/gyeonggi_processed/weather_clean.csv`와 `step03/weather_component_scores.csv`입니다. 전체 STEP03 결과를 작업일 목록으로 사용하므로 별도 train/holdout CSV를 중복 결합하지 않습니다. 날짜로 기간을 검증하며 기존 period와 다르면 중단합니다. 관측소·작업일 중복, 작업시간 혼합, 동일 시각의 충돌 관측은 오류입니다. 완전히 동일한 관측 중복은 한 번만 사용합니다.

관측소별 임시 분할 후 한 관측소씩 읽으므로 정제자료 크기 정도의 임시 디스크 공간이 필요합니다. STEP03의 파서·분할 함수·체감온도 공식만 읽기 전용으로 재사용합니다. 기존 점수 열은 참고용으로 복사하며 학습 또는 적격성 판단에는 사용하지 않습니다. STEP03을 수정하거나 재계산하지 않습니다.

## 특성 계약

관측소·작업일별로 집계합니다. 관측시각은 KST 10분 구간 종료시각입니다. 기본 작업 08:00~17:00에는 08:10~17:00의 54개 구간, 선행 24시간은 전날 08:10~당일 08:00의 144개 구간을 사용합니다. STEP03 작업시간과 산지 설정을 읽어 그대로 따르며 날짜를 넘기는 작업은 지원하지 않습니다. 종료 24:00은 허용합니다.

| 모델 | 열 | 정의 |
| --- | --- | --- |
| AI1 | work_rain_mm | 작업 구간 강수 합, mm |
| AI1 | prior_24h_rain_mm | 직전 24시간 강수 합, mm |
| AI1 | work_max_mean_wind_mps | 작업 구간 10분 평균풍속 최댓값, m/s |
| AI1 | work_rain_minutes | 강수 >0mm 구간 누적시간, 분 |
| AI1 | work_strong_wind_minutes | 비산지 평균풍속 ≥14m/s, 산지 ≥17m/s 누적시간, 분 |
| AI2 | work_max_temperature_c | 작업 구간 실제 기온 최댓값, ℃ |
| AI2 | work_mean_humidity_pct | 작업 구간 상대습도 산술평균, % |
| AI2 | work_heat_minutes_ge31 | STEP03 여름철 체감온도 ≥31℃ 누적시간, 분 |

순간풍속은 입력하지 않습니다. 지속시간은 최장 연속시간이 아니라 누적시간입니다. 최고기온은 실제 기온이며 고온시간에는 체감온도를 씁니다. 체감온도는 STEP03의 Stull 습구온도 추정과 기상청 공식(+3.0 보정)을 그대로 사용합니다.

## 완전성과 결측

각 특성에 필요한 예정 구간 전체가 유효해야 해당 특성이 존재합니다. 강수 두 작업 특성은 작업 강수 전체, 선행강수는 144개 전체, 풍속 두 특성은 작업 풍속 전체가 필요합니다. 최고기온은 기온 전체, 평균습도는 습도 전체, 고온시간은 모든 시각의 기온·습도 쌍이 필요합니다. 유효 구간 수를 함께 기록합니다.

빈 값·X·-·NA·비유한 값, 음수 강수/풍속, 0~100% 밖의 습도는 결측입니다. 수치가 아닌 알 수 없는 문자열은 오류로 중단합니다. 결측 대치, 일부 구간의 비례 확대, 극단값 절삭은 하지 않습니다. **관측된 0은 유효**하므로 비 없는 강풍과 바람 없는 폭우 모두 AI1 대상입니다.

AI1의 5개 특성, AI2의 3개 특성 완전성을 독립 판단합니다. 각 모델은 자기 입력이 완전한 모든 학습 행으로 학습하고 완전한 행만 추론합니다. `component_scores_complete` 또는 S_R·S_W·S_H 유무로 일괄 선택하지 않습니다. STEP03의 부분 관측 만점이 AI 특성 완전성을 보장하지 않습니다. 한 모델이 불가여도 다른 모델은 계산합니다. 부분 특성 모델이나 보조 모델은 없습니다.

## 학습과 점수

2016-01-01~2023-12-31은 학습, 2024~2025년은 평가, 이후는 `after_validation` 추가기간입니다. 2016년 이전은 `before_train`으로 보존하되 학습하지 않습니다. 24개 관측소를 통합하며 늦게 시작한 관측소도 확보한 유효 자료를 포함합니다. 동일한 관측소 가중치를 적용하는 구조는 아니므로 긴 관측소의 행이 더 많을 수 있습니다.

원단위 고정 입력을 사용합니다. 기본 IF는 `n_estimators=300`, `max_samples='auto'`, `max_features=1.0`, `contamination='auto'`, `bootstrap=False`, `random_state=42`, `n_jobs=1`입니다. 취소 여부 이진 판정이나 contamination 기반 라벨은 사용하지 않습니다. `--trees`, `--seed`, `--min-training-rows`를 지원합니다. 평가 결과에 맞춰 설정을 선택하지 않습니다.

운영상 최소 학습 행은 기본 100개이고 서로 다른 입력 벡터가 최소 2개 필요합니다. 이는 통계적 유의성 기준이 아닙니다. 부족하면 해당 모델 번들을 `unavailable`로 저장하고 사유를 기록하며 다른 모델은 계속 계산합니다.

모델 m의 원시 이상도는 `a_m(x) = -model.score_samples(x)`입니다. 최종 점수는 다음과 같습니다.

`S_m(x) = 100 * count(training_anomaly <= a_m(x)) / n_training`

각 모델을 실제 학습한 모든 행을 다시 점수화해 정렬된 기준분포를 저장합니다. 동점은 오른쪽 누적순위로 동일 점수를 받으며 학습 최솟값에서도 0점일 필요는 없습니다. 기준보다 낮으면 0점, 최댓값 이상이면 100점입니다. 이후 기간에도 분포를 재학습하지 않습니다. 학습 점수분포는 in-sample 기준이며 실제 성능 추정치가 아닙니다.

[scikit-learn 공식 IF 문서](https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.IsolationForest.html)에 따라 score_samples의 방향을 반전합니다. 높은 최종 점수는 과거 대비 이례성을 뜻하며 취소·피해 확률이 아닙니다. 저온·저습 등 드문 패턴에도 높은 점수가 가능하고 위험 강도와 단조 증가를 보장하지 않습니다. 두 모델의 같은 백분위가 동일한 위험 크기라는 뜻도 아닙니다.

## 산출물

모든 결과는 `worker/data/gyeonggi_processed/step04/`에 저장합니다.

| 파일 | 내용 |
| --- | --- |
| isolation_forest_r1.pkl | AI1 모델·특성 순서·설정·학습분포·버전·실제 학습 범위 |
| isolation_forest_r2.pkl | AI2의 동일 정보 |
| weather_ai_features.csv | 전체 대상 행의 특성·관측 수·입력 적격성·기존 참고 지수 |
| weather_ai_scores.csv | 전체 대상 행과 AI 원시점수·S_AI1·S_AI2·계산 상태 |
| weather_ai_train.csv | 학습기간 전체 행, 제외 행도 보존 |
| weather_ai_holdout.csv | 평가기간 전체 행, 제외 행도 보존 |
| weather_ai_additional.csv | 추가기간 전체 행, 해당 기간이 없으면 헤더만 저장 |
| model_diagnostics.csv | 기간×관측소×모델 제외율·실제 학습기간·점수분포·극단 관측 비교 |
| feature_missingness.csv | 같은 구분의 특성별 결측률 |
| missing_patterns.csv | 같은 구분의 결측 조합과 행 수 |
| calculation_coverage.csv | 두 지수/AI1만/AI2만/모두 불가 비율 |
| run_report.json | 입력·출력 해시, 모델 메타데이터, 행 수, 검증 결과 |

`AI1_input_complete`/`AI2_input_complete`는 특성 완전성입니다. `*_missing_features`는 부족한 입력, `*_exclusion_reason`은 입력 제외 사유입니다. `*_status`는 computed/unavailable, `*_unavailable_reason`은 입력 부족 또는 모델 부족 사유입니다. `*_used_features`는 실제 사용한 고정 입력, `*_training_used`는 모델 학습에 사용된 행, `*_model_version`은 정책 버전입니다. 각 점수와 원시점수는 계산 불가 시 빈칸이며 CSV에 불필요한 반올림을 하지 않습니다. `calculation_status`는 both/AI1_only/AI2_only/unavailable입니다. 부분 상태는 한 지수만 계산됐다는 뜻입니다.

PKL은 신뢰하는 로컬 파일만 로드하고 저장된 라이브러리 버전과 같은 환경을 사용합니다. 재추론은 번들의 `forest`, `reference`, `metadata.features`를 사용해 `score_rows`를 호출할 수 있습니다. CLI는 재추론 전용이 아니라 전체 재학습 실행입니다.

## 제외율과 편향 진단

입력 부족 제외율은 `불완전 입력 행 수 / 해당 기간·관측소 전체 대상 행 수 * 100`입니다. 관측소 `ALL`은 기간 전체 합산입니다. 입력 완전성, 실제 계산 행 수, 모델 부족으로 계산하지 못한 완전 행 수를 구분합니다. 특성별 사유는 중복되며 합계가 제외 행 수와 다를 수 있습니다.

극단 관측 진단은 STEP03의 `rain_full_score`, `wind_full_score`, `heat_full_score`를 참고하여 포함·제외 집단별 관측된 기준 충족 행 수와 비율을 비교합니다. 관측되지 않은 구간의 극단값은 알 수 없으므로 False를 안전한 날로 해석하지 않습니다. 학습기간의 실제 사용 날짜 범위를 모델별·관측소별로 기록합니다. 이 진단은 편향과 자료 사용 범위를 설명하며 합격 관문이나 통계적 유의성 검정이 아닙니다.

## 검증과 저장 제한

자동 테스트는 시간 경계·24:00·결측·정상 0·독립 분기·산지 기준·미래 자료 추가에 대한 학습 불변·동점 ECDF·저장 재로딩·제외율 분모·충돌 중복·전체 파일 실행을 확인합니다. 실제 실행에서는 모든 행의 적격성, 점수 범위, 독립적인 scalar ECDF 조회, 전체 모델 재로딩 후 점수 일치를 검사합니다. 10분 원본부터의 별도 독립 재집계 검증은 아니며 STEP03의 불일치 0건을 STEP04 집계 검증으로 전용하지 않습니다.

`validate_saved_results.py`는 출력 해시, 대상 행·기존 점수 보존, 기간별 파일 분리, 제외율 분모를 별도로 검사합니다. 강수합·선행강수합과 완전한 풍속/온열 특성은 저장된 STEP03 집계와 대조합니다. STEP04에서 새로 집계한 최고기온·평균습도·강수시간은 가상자료 테스트로 확인합니다. 검증기는 원래 10분 자료를 독립 재집계하는 도구는 아니며, 오류가 있으면 종료 코드 1을 반환합니다.

검증 성공 후 임시 결과를 파일별 교체하고 run_report.json을 마지막에 게시합니다. 파일 전체가 하나의 원자적 트랜잭션은 아니므로 중단 후에는 보고서의 파일 해시로 일관성을 확인하거나 재실행합니다. 같은 출력 경로는 재실행 시 교체되므로 다른 설정은 별도 출력 폴더를 사용합니다. STEP03 경로를 출력으로 지정하지 않습니다. 분석 스크립트는 Git 커밋·푸시를 자동 실행하지 않습니다.
