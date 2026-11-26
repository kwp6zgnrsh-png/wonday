# STEP04: Isolation Forest를 단계별로 실행하기

## 실행 경로 구분

원격 main의 단일 실행형 IF와 현재 사용 중인 확장형 IF·LOF·ECOD 워크플로를 함께 보존합니다.

| 경로 | 실행 진입점 | 의존성 | 안내 |
| --- | --- | --- | --- |
| 확장형 IF · 현재 비교 기준 | `worker/code/step04/step04.py` | `worker/code/step04/requirements-pooled.txt` · Python 3.14 | 이 문서와 [공식 일강수량 복원](official_daily_reconstruction.md) |
| LOF · 고정형 ECOD | `worker/code/step04_comparison/compare_models.py` | `worker/code/step04_comparison/requirements.txt` | [학습·비교 실행](lof_ecod_comparison.md) |
| 원격의 단일 실행형 IF | `worker/code/step04/isolation_forest_scores.py` | `worker/code/step04/requirements.txt` · 기존 Python 3.11 기준 | [단일 실행형 안내](standalone_workflow.md) |

확장형 내부 IF 구현은 `pooled_isolation_forest.py`에 있습니다. 기존 `step04.py` 및 LOF·ECOD 실행 명령은 유지합니다. 입력 열·모델·점수 저장 형식은 두 IF 경로 간에 다르므로 각 경로의 모델과 검증기를 함께 사용합니다. 현재 LOF·ECOD 비교의 기본 기준은 `step04_official_daily`이며 기존 결과 파일은 변경하지 않습니다.

아래 내용은 확장형 `step04.py` 실행 안내입니다.


공식 일강수량으로 선행강수 결측을 복원하는 새 실행은 [공식 일자료 복원 안내](official_daily_reconstruction.md)를 따르세요. 해당 모드는 STEP03과 기존 결과를 보존하며 별도 `step04_official_daily/` 폴더를 사용합니다. 아래는 기존 관측 기반 실행 안내입니다.

이 문서는 저장소 루트에서 1~8번을 순서대로 실행하는 안내입니다. 전체 자료를 자동으로 학습하지 않으며, 각 명령은 해당 작업만 수행합니다. STEP03 원본과 산출물은 변경하지 않습니다.

## 1. Python 3.14와 라이브러리 준비

```bash
cd /Users/yun/Desktop/wonday
python3.14 --version
python3.14 -m pip install --upgrade --target "$HOME/Library/Python/3.14/lib/python/site-packages" -r worker/code/step04/requirements-pooled.txt
python3.14 -c "import numpy, sklearn; print(numpy.__version__, sklearn.__version__)"
```

위 설치 경로는 macOS의 Python 3.14 개인 패키지 폴더입니다. 가상환경을 만들지 않고 Homebrew의 기본 패키지 폴더도 수정하지 않습니다. 기존 Python 3.9의 `pip3`와 구분하기 위해 반드시 `python3.14 -m pip`를 사용합니다. `--break-system-packages`는 사용하지 않습니다.

이 저장소에서 검증한 버전은 `requirements.txt`에 고정했습니다. 모델을 다시 읽을 때 Python·NumPy·SciPy·scikit-learn 버전을 학습 환경과 비교합니다. 다른 환경을 이미 사용 중이면 그 환경의 Python에 requirements를 설치하고 아래 명령의 실행 파일을 일관되게 바꾸세요.

## 2. STEP03 입력과 실행 환경 확인

```bash
python3.14 worker/code/step04/step04.py check
```

`status: ready`, 관측소 수, 대상 행 수, 작업시간·산지 설정, 라이브러리 버전을 확인합니다. 기본 입력은 다음 두 파일입니다.

- `worker/data/gyeonggi_processed/weather_clean.csv`
- `worker/data/gyeonggi_processed/step03/weather_component_scores.csv`

기본 출력은 `worker/data/gyeonggi_processed/step04/`입니다. STEP03 대상 행의 날짜·작업시간·산지 여부를 그대로 사용하고 기간은 2016~2023년 학습, 2024~2025년 평가, 2026년 이후 추가기간으로 검사합니다. 날짜 중복 및 관측값 충돌은 집계 단계에서 거부합니다.

## 3. 원래 10분 자료에서 특성 집계

```bash
python3.14 worker/code/step04/step04.py features
```

`weather_ai_features.csv`와 `weather_ai_features.metadata.json`이 생깁니다. 원본을 한 번 읽어 임시 관측소 파일로 분리하고 한 관측소씩 계산하므로 이 단계가 가장 오래 걸립니다. 정제 CSV 크기 정도의 임시 디스크 공간이 필요합니다.

STEP03의 모든 대상 행을 유지합니다. 해당 관측소의 원래 관측이 전혀 없어도 대상 행은 남습니다. 최고기온·평균습도를 복원할 수 없는 STEP03 집계 객체 대신 실제 10분 기온·습도를 읽고, 체감온도 공식은 STEP03 함수를 공유합니다.

| 모델 | 고정된 입력 열 순서 |
| --- | --- |
| AI1 | `work_rain_mm`, `prior_24h_rain_mm`, `work_max_wind_mps`, `work_rain_minutes`, `work_strong_wind_minutes` |
| AI2 | `work_max_temp_c`, `work_mean_humidity_pct`, `work_heat_minutes_ge31` |

기본 작업 구간은 08:10~17:00 종료시각 54개, 선행강수는 전날 08:10~당일 08:00 종료시각 144개입니다. 강풍시간은 비산지 14m/s·산지 17m/s 이상, 고온시간은 같은 시각 기온·습도로 계산한 체감온도 31℃ 이상 구간의 누적분입니다.

변수별 필요한 관측이 모두 유효할 때만 특성을 저장합니다. 관측된 0은 유지하고 관측 부족은 빈칸입니다. 부분 관측 최댓값은 `observed_*` 진단 열에만 남기며 모델 입력으로 사용하지 않습니다. `S_R/S_W/S_H`는 연결 정보로 보관하지만 모델에 넣거나 일괄 필터링하는 데 사용하지 않습니다.

## 4. 입력 적격 여부와 제외율 확인

```bash
python3.14 worker/code/step04/step04.py diagnose
```

다음 보고서가 생깁니다.

| 파일 | 내용 |
| --- | --- |
| `input_diagnostics.csv` | 기간·관측소·모델별 전체·사용·제외 행 수 및 제외율, 학습 사용 날짜 범위 |
| `input_diagnostics.json` | 위 집계와 결측 특성 조합별 건수 |
| `feature_missingness.csv` | 특성별 결측 행 수와 비율 |
| `eligibility_combinations.csv` | 둘 다 가능·AI1만·AI2만·둘 다 불가 비율 |
| `observed_extremes.csv` | 입력 완전/불완전 그룹에서 관측 가능한 극단값 비교 |

`station_code=__ALL__`은 전체 관측소 통합 집계입니다. 모델별 제외율 분모는 해당 기간·관측소의 전체 대상 행 수입니다. 개별 결측 사유는 중복될 수 있습니다.

극단값 비교에는 작업 중 완전한 1시간 이동강수량 72mm, 평균풍속 비산지 21m/s·산지 24m/s, 기온 33℃, 체감온도 38℃ 기준을 설명용으로 사용합니다. 이는 모델의 학습 필터나 새 지급 기준이 아닙니다. 부분 관측 최댓값은 그날 실제 최댓값의 하한이며, 비교 가능한 관측 수와 분모를 함께 기록합니다. 미관측 구간의 극단값은 판단할 수 없습니다. 통계적 유의성·합격 판정을 하지 않습니다.

제외율이 높다고 결측을 0으로 채우거나 보조 모델을 만들지 않습니다. 두 모델은 각자의 입력 완전성을 독립적으로 판단합니다. 이 보고서는 자료 한계 확인용이며 실행을 막는 통계 합격 관문이 아닙니다.

## 5. 가상자료 자동 테스트

```bash
python3.14 -B -m unittest discover -s worker/code/step04 -p test_isolation_forest.py -v
```

마지막에 `OK`가 나와야 합니다. 작업시간·선행강수 경계, 누적 지속시간, 산지 기준, 실제 0과 결측, 모델별 독립 선택, 체감온도 입력쌍, 미래자료 누출, 동점 백분위, 학습자료 부재, 재로딩, 대상 행 보존 및 제외율을 확인합니다. 테스트는 임시 폴더를 사용하며 전체 실제 자료를 학습하지 않습니다.

## 6. 학습기간 자료로 모델 두 개 학습

```bash
python3.14 worker/code/step04/step04.py train
```

2016~2023년의 AI1 적격 행과 AI2 적격 행을 각각 선택합니다. 24개 관측소를 통합한 모델 두 개이며 늦게 시작한 관측소도 학습기간 안의 완전한 행을 포함합니다. 관측소·날짜·기존 점수는 학습 입력에서 제외합니다.

초기 고정 설정은 `n_estimators=300`, `max_samples="auto"`, `max_features=1.0`, `contamination="auto"`, `bootstrap=False`, `random_state=42`, `n_jobs=1`입니다. 최적화된 설정이라고 주장하지 않습니다. 원단위 그대로 사용하며 결측 대치·스케일링·극단값 절삭은 없습니다. 어느 모델이든 학습 가능한 행이 없으면 미래자료로 대체하지 않고 오류로 종료합니다.

다음 파일이 생성됩니다.

- `isolation_forest_r1.pkl`, `isolation_forest_r2.pkl`
- `ai1_train_reference.npy`, `ai2_train_reference.npy`
- `model_metadata.json`

각 기준분포는 `-score_samples(X_train)`을 정렬한 배열입니다. 메타데이터에는 열 순서·설정·라이브러리 버전·사용 행 수·관측소별 실제 학습 날짜 범위·학습 입력 지문·파일 체크섬을 기록합니다. 기준분포는 전체 통합 모델의 학습 행 분포이므로 관측소별 사용 행 수에 따른 비중이 생깁니다. 두 모델의 학습을 모두 마친 뒤 저장하고 manifest를 마지막에 저장합니다. 여러 산출물 전체는 단일 트랜잭션이 아니지만 추론 시 manifest 체크섬 불일치를 거부합니다.

## 7. 저장된 모델로 전체 기간 점수 계산

```bash
python3.14 worker/code/step04/step04.py score
```

저장된 두 모델과 기준분포를 읽어 모든 대상 행을 보존한 `weather_ai_scores.csv`를 만듭니다. 다시 학습하지 않습니다. `AI1_anomaly`, `AI2_anomaly`는 부호를 뒤집은 원시 이상점수이고 `S_AI1`, `S_AI2`가 0~100점입니다. 불완전한 모델의 점수는 빈칸으로 남깁니다.

각 점수는 다음 식을 그대로 구현합니다.

```python
100 * np.searchsorted(sorted_train_anomaly, anomaly, side="right") / len(sorted_train_anomaly)
```

동점은 동일 백분위이며 최솟값이 반드시 0점이 되는 것은 아닙니다. 동일한 학습 점수가 많으면 한 점수에 큰 백분위가 배정될 수 있습니다. 새로운 평가기간·추가기간 자료로 기준분포를 다시 만들지 않습니다.

높은 점수는 이례성을 뜻합니다. 취소·피해 확률이 아니며, 저온처럼 온열 위험과 방향이 다른 관측에도 높은 점수가 나올 수 있습니다. 위험 방향 보정이나 기존 S와 결합은 이 단계에서 수행하지 않습니다.

## 8. 저장 결과 검증

```bash
python3.14 worker/code/step04/step04.py validate
```

`validation_report.json`의 `status: passed`, `mismatch_count: 0`을 확인합니다. 다음을 검사합니다.

- STEP03의 모든 대상 행과 작업시간·산지 설정 보존
- 특성·유효 관측 수·모델별 적격 여부 일치
- 제외 행의 점수 빈칸과 0~100점 범위
- 저장·재로딩한 모델의 원시 점수 및 백분위 재계산 일치
- 학습기간의 적격 행에서만 생성한 기준분포와 학습 입력 지문 일치
- 특성·점수·모델 메타데이터 및 체크섬 연결
- 기간·관측소·모델별 제외율 분모와 중복 결측 사유 건수

이는 구현 검증이며 실제 농작업 취소 탐지 성능·Precision/Recall·베이시스 리스크 개선 검증이 아닙니다. 작목코드, S와의 결합, R1/R2, 지급 기준은 STEP05 범위로 남겨 둡니다.

## 재실행과 경로 변경

모든 명령에 `--output-dir`, `--clean`, `--step03`, `--features`, `--scores`, `--model-dir`를 지정할 수 있습니다. 예를 들어 별도 실행 결과를 유지하려면 모든 명령에 같은 `--output-dir`를 지정하세요.

원래 관측 또는 STEP03 대상 자료를 변경하면 3번 집계부터 다시 진행합니다. 학습기간 입력을 바꾸면 6번에서 모델과 기준분포를 다시 생성해야 합니다. 평가기간 점수를 본 뒤 모델 설정을 고르지 않습니다.
