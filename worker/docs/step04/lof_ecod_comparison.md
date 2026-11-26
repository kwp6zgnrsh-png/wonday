# STEP04: LOF·학습분포 고정형 ECOD 비교

기존 IF 결과를 읽기 전용 기준으로 두고, 동일한 특성·학습기간·모델별 적격 행으로 LOF와 고정형 ECOD를 학습한다. 각 알고리즘마다 AI1(강수·강풍), AI2(온열)를 별도로 학습하므로 새 모델 파일은 총 4개다. 실제 자료 학습은 아래 명령을 사용자가 실행한다.

## 입력과 보존 범위

- 기본 입력: `worker/data/gyeonggi_processed/step04_official_daily/weather_ai_features.csv`
- 기존 IF: `worker/data/gyeonggi_processed/step04_official_daily/`
- 기존 `step03/`, `step04/`, `step04_official_daily/`와 공유 원본·정제 파일에는 쓰지 않는다. IF 학습·집계 코드도 수정하지 않는다.
- 입력을 재집계하거나 결측을 대치하지 않는다. 공식 일강수량 복원 정책, 신북면 AI1 제외, `ai1_eligible`·`ai2_eligible`를 그대로 따른다. 기존 S_R·S_W·S_H를 모델 입력에 추가하지 않는다.
- 전체 90,886행을 점수 결과에도 보존하고, 해당 모델에서 입력 불완전·정책 제외인 행의 점수는 빈칸으로 둔다. 실제 0은 정상 입력이다.
- 학습·전처리·점수 기준분포는 2016~2023년 적격 행만 사용한다. 2024~2025년은 평가, 2026년은 별도 추가기간이다.

현재 입력에서 확인한 행 수:

| 기간 | 전체 대상 | AI1 사용 | AI2 사용 |
|---|---:|---:|---:|
| 학습 2016~2023 | 65,638 | 58,654 | 63,584 |
| 평가 2024~2025 | 17,544 | 16,206 | 17,206 |
| 추가 2026 | 7,704 | 770 | 7,311 |

2026년 AI1의 적은 사용 행 수는 현재 입력의 공식 일강수량 가용 범위에 따른 것이다. 새 모델이 결측 자료를 복원하지는 않는다.

## LOF 계산

- `LocalOutlierFactor(novelty=True, n_neighbors=20, algorithm="kd_tree", metric="euclidean", contamination="auto", n_jobs=1)`.
- 학습 행이 21개 미만인 테스트에서는 이웃 수를 `n_train - 1`로 줄인다. 실제 데이터에서는 20이다.
- 거리 계산의 단위 차이를 처리하기 위해 해당 AI 모델의 학습 행에만 `StandardScaler`를 맞춘다. 이후 표준화 기준을 고정한다. IF의 전처리는 바뀌지 않는다.
- 학습 행 원시점수: `-negative_outlier_factor_`. 평가·추가기간 원시점수: `-score_samples(X)`.
- 학습 행에는 novelty `score_samples`를 호출하지 않는다. 학습 당시 행별 원시점수와 입력을 저장하고, 같은 학습 행의 입력이 바뀌면 계산을 거절한다.
- 이웃 수 등 설정은 최종 평가자료를 본 뒤 조정하지 않는다. 변경 시 2023년 이전 자료에서 검토하고 새 학습을 수행한다.

**LOF 해석 제한:** 학습 행의 기본 LOF 점수와 새 행의 novelty 점수는 이웃 계산 방식이 다르다. 학습 기본점수의 백분위에 평가 novelty 점수를 대응시킨 값은 기술적인 상대 순위다. 동일한 확률 보정이나 정확히 5%의 미래 발동률을 보장하지 않는다. CSV에 `AI1_score_method`, `AI2_score_method`로 `train_native`/`novelty`를 구분한다.

## 고정형 ECOD 계산

`ECOD_training_frozen_v1`은 이 프로젝트의 **학습분포 고정형 변형**이다. 별도의 공식 모델명이나 PyOD ECOD를 그대로 실행한 결과가 아니다. NumPy·SciPy로 계산하므로 PyOD를 추가 설치할 필요가 없다.

모델별 학습자료에서 각 특성의 정렬값과 왜도 부호를 저장한다. 상수 특성의 왜도 부호는 0으로 둔다. 새 입력 x에 대해 다음을 계산한다.

1. 왼쪽 확률 `pL = count(train <= x) / n`.
2. 오른쪽 확률 `pR = count(train >= x) / n`.
3. 학습 범위 밖에서도 무한대가 나오지 않도록 양쪽 확률을 각각 최소 `1/n`으로 제한한다. 이 꼬리 확률 하한은 명시적인 유한 표본 처리이며, 하한보다 극단적인 값은 같은 꼬리 점수가 될 수 있다.
4. `UL = -log(pL)`, `UR = -log(pR)`.
5. 학습 왜도가 양수이면 `Uskew = UR`, 음수이면 `UL`, 0이면 `UL + UR`.
6. 각 특성의 `max(UL, UR, Uskew)`를 합산한다.

입력은 원단위를 유지한다. 평가 입력을 학습자료에 합치거나 평가자료로 CDF·왜도·꼬리 방향을 다시 맞추지 않는다. 한 행씩 점수화하거나 묶어서 점수화해도 결과가 같다. `score_method`는 `frozen_ecod`이다.

## 0~100점 변환과 공정한 비교

두 알고리즘 모두 원시점수가 높을수록 이상하도록 방향을 맞춘 후, **각 모델 자신의 학습 이상점수**를 정렬해 저장한다.

`S_AI = 100 * count(training_anomaly <= current_anomaly) / n`

동점에는 같은 우측 백분위가 적용된다. 모델마다 원시점수 단위가 다르므로 원시점수 크기를 직접 비교하지 않는다. AI1과 AI2도 따로 비교한다. 저온 등 드문 패턴이 높은 점수를 받을 수 있으며, 높은 이상점수가 피해·취소 확률 또는 위험의 물리적 강도를 뜻하지 않는다.

비교 시 세 알고리즘의 특성 파일 SHA256, 모든 원본 열·행·순서·적격 여부와 학습행렬 SHA256까지 확인한다. 입력이나 기존 IF 결과가 다른 실행의 것이면 중단한다. IF를 자동 재학습하거나 덮어쓰지 않는다.

## 실행 순서

아래 명령은 프로젝트 루트 `/Users/yun/Desktop/wonday`에서 실행한다. Python 3.14의 기존 IF 환경을 그대로 사용한다. 의존성이 없는 환경에서만 `python3.14 -m pip install -r worker/code/step04_comparison/requirements.txt`를 실행한다.

### 1. 입력 확인

```bash
python3.14 worker/code/step04_comparison/compare_models.py check
```

파일과 결측 정책을 검사하고 위 기간별 사용 행 수를 출력한다. 학습하거나 결과를 저장하지 않는다. 기본 입력을 바꾸려면 **모든 단계에 동일하게** `--features /절대경로/weather_ai_features.csv`를 지정한다. 비교 대상 IF도 정확히 그 파일로 계산된 결과여야 한다.

### 2. LOF 학습

```bash
python3.14 worker/code/step04_comparison/compare_models.py train --model lof
```

이 한 번의 명령으로 LOF AI1·AI2를 모두 학습한다.

### 3. LOF 점수 생성

```bash
python3.14 worker/code/step04_comparison/compare_models.py score --model lof
```

### 4. LOF 결과 검증

```bash
python3.14 worker/code/step04_comparison/compare_models.py validate --model lof
```

### 5. 고정형 ECOD 학습

```bash
python3.14 worker/code/step04_comparison/compare_models.py train --model ecod
```

이 한 번의 명령으로 ECOD AI1·AI2를 모두 학습한다.

### 6. ECOD 점수 생성

```bash
python3.14 worker/code/step04_comparison/compare_models.py score --model ecod
```

### 7. ECOD 결과 검증

```bash
python3.14 worker/code/step04_comparison/compare_models.py validate --model ecod
```

### 8. 기존 IF와 비교

```bash
python3.14 worker/code/step04_comparison/compare_models.py compare
```

기본 IF 폴더가 다른 경우에만 `--if-dir /절대경로/IF결과폴더`를 지정한다. 해당 폴더에서 모델이나 점수를 생성하지 않고 읽기만 한다.

`train`은 각 알고리즘의 새 폴더에만 모델을 저장한다. 이를 다시 실행하면 해당 알고리즘의 모델이 교체되므로 `score → validate → compare`도 다시 실행한다. `score`는 학습하지 않는다. 모델 파일이 없다는 오류는 앞선 `train`을 실행하지 않았다는 의미이고, 점수 파일이 없다는 오류는 `score`를 실행하지 않았다는 의미다.

## 산출물 확인

| 위치 | 파일 | 확인할 내용 |
|---|---|---|
| `worker/data/gyeonggi_processed/step04_lof/` | `lof_ai1.pkl`, `lof_ai2.pkl` | LOF와 학습 표준화·점수 기준 |
| `worker/data/gyeonggi_processed/step04_ecod/` | `ecod_ai1.pkl`, `ecod_ai2.pkl` | 고정형 ECOD와 학습 분포·왜도 |
| 두 알고리즘 폴더 각각 | `ai1_train_reference.npy`, `ai2_train_reference.npy` | 정렬된 학습 원시 이상점수 |
| 두 알고리즘 폴더 각각 | `model_metadata.json` | 실제 학습행·관측소별 기간·입력 순서·설정·버전·해시 |
| 두 알고리즘 폴더 각각 | `weather_ai_scores.csv` | 전체 행과 `S_AI1`, `S_AI2`, 원시점수, 계산 방식 |
| 두 알고리즘 폴더 각각 | `validation_report.json` | `status: passed`, 원본 행 보존·결측·재로딩 점수 일치 |
| `worker/data/gyeonggi_processed/step04_comparison/` | `model_comparison.csv` | 같은 관측소·날짜의 IF/LOF/ECOD AI1·AI2 점수 나란히 비교 |
| 비교 폴더 | `score_summary.csv` | 기간·AI별 점수 평균·중앙값·95분위·95점 이상 행 수 |
| 비교 폴더 | `pairwise_comparison.csv` | 기간·AI별 세 모델 쌍의 Spearman 상관도·95점 이상 중복/불일치 |
| 비교 폴더 | `comparison_report.json` | 입력·실행 ID·비교 조건·해석 제한 |

95점 기준은 이상점수 비교용 예시이며 지급 기준이 아니다. 동점을 모두 포함하므로 95점 이상인 비율이 정확히 5%일 필요는 없다. 상관도가 계산되지 않는 상수 점수·행 부족에서는 빈칸으로 기록한다. 최종 평가는 2024~2025년을 중심으로 보고, 학습기간과 2026년을 분리해서 해석한다. 실제 취소 라벨이나 독립적인 위험 기준 없이 Precision·Recall·F1 또는 베이시스 리스크 감소를 확정하지 않는다.

## 구현 검증

```bash
python3.14 -m unittest discover -s worker/code/step04_comparison -p 'test_*.py' -v
```

작은 합성자료로만 학습한다. 꼬리확률·동점·상수 특성·범위 밖 값, 배치 독립성, 미래자료 누출 방지, IF와 동일한 학습행렬, 모델별 독립 선택, 빈칸·원본 행 보존, 저장·재로딩 일치, 파일 변조 거절, 기존 IF 출력 보호, 세 알고리즘 비교를 검증한다.

## 근거 문서

- [scikit-learn LOF API](https://scikit-learn.org/stable/modules/generated/sklearn.neighbors.LocalOutlierFactor.html)
- [scikit-learn novelty LOF 주의사항](https://scikit-learn.org/stable/auto_examples/neighbors/plot_lof_novelty_detection.html)
- [ECOD 논문](https://arxiv.org/abs/2201.00382)
- [PyOD ECOD 구현](https://pyod.readthedocs.io/en/latest/_modules/pyod/models/ecod.html): 비교를 위한 참고 구현이며 이 프로젝트는 평가자료를 합치는 동작을 사용하지 않는다.
