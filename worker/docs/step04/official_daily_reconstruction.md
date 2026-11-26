# STEP04 공식 일강수량 기반 선행강수 복원

STEP03 코드·기준표·산출물, 공유 `weather_clean.csv`, 기존 STEP04 산출물은 그대로 유지합니다. 이 기능은 STEP04 전용 특성에만 복원값을 추가합니다. 기존 실행 안내의 STEP01 재정제나 STEP03 재산출은 이 실행에 필요하지 않습니다.

## 입력과 저장 위치

| 용도 | 기본 경로 |
| --- | --- |
| 원래 10분 자료 | `worker/data/gyeonggi_processed/weather_clean.csv` |
| 작업일·작업시간·산지 설정 | `worker/data/gyeonggi_processed/step03/weather_component_scores.csv` |
| 공식 일강수 | `worker/data/gyeonggi_processed/official_daily_rainfall.csv` |
| 기존 STEP04 결과 | `worker/data/gyeonggi_processed/step04/` — 보존 |
| 새 복원 실행 결과 | `worker/data/gyeonggi_processed/step04_official_daily/` |

`features --official-daily 경로`를 지정하면 별도 출력 폴더가 기본값이 됩니다. 이후 `diagnose`, `validate-features`, `train`, `score`, `validate`에는 같은 새 폴더를 `--output-dir`로 명시합니다. 복원 실행은 기존 STEP03/STEP04 폴더에 쓰는 것을 거부합니다. 다른 새 폴더를 사용하려면 모든 단계에 동일한 경로를 지정하세요.

공식 일자료는 같은 관측소의 KST 달력일 총량이라는 입력 계약을 전제로 합니다. 관측소 코드는 문자열입니다. `data_status=valid`이고 유한한 0 이상 값만 사용합니다. `0mm`는 유효하며 결측·음수·비유한 값·출처 충돌은 대치하지 않습니다. 정리본의 관측소/날짜 키 중복은 오류로 거부합니다.

## 복원 규칙

08:00 시작 작업에 한정합니다. 다른 시작시간이면 재검증 없이 일반화하지 않고 오류로 종료합니다.

```text
P24(d) = 공식 일강수량(d-1)
         - 누적강수량(d-1 08:00)
         + 누적강수량(d 08:00)

자정 마지막 10분 강수 = 공식 일강수량(d-1) - 누적강수량(d-1 23:50)
검산 P24 = 자정 외 143개 강수 증분 합 + 자정 마지막 10분 강수
```

기존 P24가 결측이며, 자정 구간만 비어 있고, 자정 외 143개 증분과 양일 08:00/전날 23:50 누적값이 유효한 경우에만 복원합니다. 자정 행이 아예 없거나 24:00 누적계가 초기화된 경우도 같은 공식 자료 검증을 통과해야 합니다.

전날 08:00~23:50 및 당일 00:10~08:00 누적값의 비정상 감소와 일총량 모순을 확인합니다. 복원 P24와 마지막 증분은 음수가 될 수 없습니다. 검산과 P24 공식의 차이는 0.001mm 이하여야 합니다. 실제 음수를 허용 오차로 0 처리하지 않습니다.

자정 밖 결측, 기준 누적값 부족, 공식 자료 결측·충돌, 음수 및 계산 불일치는 보류합니다. 2026년 공식 자료가 없으면 해당 기간의 결측은 그대로 남습니다.

이미 완전하게 관측된 P24는 덮어쓰지 않습니다. 공식 자료와 비교 가능할 때 검산하며, 모순이 확인되면 원래 값은 보존하고 `observed_conflict`로 표시해 새 실행의 AI1 입력에서 보류합니다. 공식 자료가 없다는 이유만으로 완전한 기존 관측을 제외하지 않습니다.

## 기록과 모델별 적격성

| 열 | 의미 |
| --- | --- |
| `prior_24h_rain_mm` | 새 실행에서 사용할 최종 P24 |
| `prior_rain_original_mm` | 원래 관측으로 계산한 P24. 기존 결측은 빈칸 보존 |
| `prior_rain_candidate_mm` | 공식 일자료와 08:00 기준값으로 계산한 후보 |
| `prior_rain_source` | `observed_10min`, `reconstructed_official_daily`, `unavailable` |
| `prior_rain_status`, `prior_rain_hold_reason` | 복원/관측 비교/보류 상태와 구체적 이유 |
| `prior_rain_interval_count` | 실제 관측 유효 구간 수. 복원 후에도 143개를 144개로 바꾸지 않음 |
| `prior_rain_other_interval_count` | 자정 외 유효 강수 구간 수 |
| `prior_rain_official_*` | 공식 자료 날짜·값·상태·조회 식별자 |
| `prior_rain_cumulative_*` | 복원 기준 누적값 |
| `prior_rain_policy` | `official_daily_midnight_only_v1` |
| `ai1_policy_exclusion` | 입력 부족과 별도인 정책상 제외 사유 |

AI1은 검증된 복원 P24를 사용할 수 있지만 다른 네 특성은 여전히 완전 관측이어야 합니다. AI2는 기존 온열 입력으로 독립 판단합니다. 요구사항의 신북면 `487915A001`은 새 실행의 AI1에서만 명시적으로 제외하며 AI2와 대상 행은 유지합니다. 입력 부족과 정책 제외 건수는 겹칠 수 있으므로 합산하지 않습니다.

결과에 붙어 있는 `S_R/S_W/S_H`는 기존 STEP03의 참고 점수입니다. 새 P24로 STEP03 점수를 재계산한 값이 아닙니다.

## 순서대로 실행하기

저장소 루트에서 실행합니다.

```bash
cd /Users/yun/Desktop/wonday
```

### 1. 자동 테스트

```bash
python3.14 -B -m unittest discover -s worker/code/step04 -p 'test_*.py' -v
```

기존 계산, 복원 대수식, 정상 0, 결측·음수·충돌, 원래 관측 수와 기존값 보존, 연말/윤일 연결, 신북면 정책, 미래 자료 누출 방지, 저장 모델 입력 계약을 확인합니다. 모델 학습 테스트는 임시 가상자료에 한정합니다.

### 2. 입력 확인

```bash
python3.14 worker/code/step04/step04.py check \
  --official-daily worker/data/gyeonggi_processed/official_daily_rainfall.csv
```

### 3. 복원 특성 생성

```bash
python3.14 worker/code/step04/step04.py features \
  --official-daily worker/data/gyeonggi_processed/official_daily_rainfall.csv \
  --output-dir worker/data/gyeonggi_processed/step04_official_daily
```

기존 정제자료를 읽기만 합니다. 이 단계가 가장 오래 걸리며 정제자료 크기 정도의 임시 디스크 공간을 사용합니다. 새 특성과 공식 자료·정제자료·STEP03 대상 파일의 지문 및 입력 계약을 저장합니다.

### 4. 복원·제외율과 기존 실행 비교

```bash
python3.14 worker/code/step04/step04.py diagnose \
  --output-dir worker/data/gyeonggi_processed/step04_official_daily \
  --baseline-features worker/data/gyeonggi_processed/step04/weather_ai_features.csv
```

기존 진단에 다음 보고서가 추가됩니다.

- `prior_rain_reconstruction.csv`: 모든 대상 행의 원래 값·복원 후보·최종 값·출처·보류 사유.
- `prior_rain_reconstruction_summary.json`: 기간·관측소별 직접 관측/복원/보류/기존값 충돌 현황.
- `reconstruction_eligibility_comparison.csv`: 같은 키에서 모델별 기존/신규 사용 가능·새로 포함·새로 제외된 행 수.
- `input_diagnostics.csv`: 전체 24곳의 제외율과 AI1 지원 관측소의 별도 분모. 입력 부족과 정책 제외를 별도 열로 기록.

### 5. 학습 전 특성 검증

```bash
python3.14 worker/code/step04/step04.py validate-features \
  --output-dir worker/data/gyeonggi_processed/step04_official_daily
```

모델 없이 실행할 수 있습니다. 출처 파일 지문, 공식 전날 연결, 복원 증거와 대수식, 실제 관측 수, STEP03 대상 키와 작업 설정을 검사합니다. `feature_validation_report.json`에서 `status: passed`를 확인합니다. 이 값은 자료 대표성·센서 품질 합격을 뜻하지 않습니다. `observed_prior_conflicts`와 보류 사유는 따로 확인하세요.

### 6. 사용자가 새 모델 학습

```bash
python3.14 worker/code/step04/step04.py train \
  --output-dir worker/data/gyeonggi_processed/step04_official_daily
```

2016~2023년의 모델별 적격 행으로 두 모델과 각각의 ECDF를 다시 생성합니다. 기존 모델을 재사용하지 않습니다. 새 모델 식별자·복원 정책·열 순서·작업 설정·버전을 함께 저장합니다. 기존 모델 및 다른 작업시간/정책으로 계산된 특성의 혼용은 거부합니다.

### 7. 새 모델로 점수 산출

```bash
python3.14 worker/code/step04/step04.py score \
  --output-dir worker/data/gyeonggi_processed/step04_official_daily
```

전체 행을 보존합니다. `model_id`와 모델별 `*_training_station_participated`를 기록하여 실제 학습 참여 여부를 현재 추론 적격 여부와 구분합니다.

### 8. 최종 검증

```bash
python3.14 worker/code/step04/step04.py validate \
  --output-dir worker/data/gyeonggi_processed/step04_official_daily
```

새 폴더의 `validation_report.json`에서 `status: passed`, `mismatch_count: 0`을 확인합니다. 기존 결과와 새 결과의 점수·순위 변화 및 모델의 추가 가치는 별도 연구 평가 대상입니다. 입력 행 증가와 같은 행의 모델 점수 변화를 구분해 해석해야 합니다.

## 구현 검증 기록 — 2026-11-25

자동 테스트 33개를 통과했습니다. 실제 자료는 임시 폴더에서 특성 집계·복원·진단·학습 전 검증까지만 실행했으며, 실제 자료의 모델 재학습은 실행하지 않았습니다.

| 항목 | 확인 결과 |
| --- | ---: |
| 보존한 대상 행 | 90,886 |
| 복원 P24 | 학습 56,903 / 평가 16,024 |
| 복원 후 AI1 입력 가능 행 | 학습 58,654 / 평가 16,206 |
| 기존 관측 P24 값 보존 | 7,765건 모두 동일 |
| P24·AI1 적격성과 관련 없는 기존 특성 | 90,886행 모두 동일 |
| 기존 관측 P24와 공식 자료의 충돌 | 학습 6건, 원래 값 보존 및 새 AI1 입력에서 보류 |

충돌 6건은 신북면 `487915A001`의 2021년 자료입니다. 신북면은 새 실행의 AI1에서 정책상 제외되며 AI2는 유지됩니다. 2026년은 공식 일자료가 없어 새로 복원하지 않았습니다. STEP03 코드·공유 자료·기존 산출물 등 보호대상 53개 파일의 작업 전후 SHA-256은 모두 일치했습니다. 이 기록은 구현 검증이며 위험 탐지 성능 검증이 아닙니다.
