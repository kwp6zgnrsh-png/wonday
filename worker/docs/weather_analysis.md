# 기상 분석 코드 안내

저장소 루트 `C:\Users\user\wonday`에서 아래 명령을 실행합니다.

| 단계 | 코드 | 역할 |
| --- | --- | --- |
| STEP01·02 | `worker/code/gyeonggi_weather.ipynb` | 기존 10분 자료 정제·작업일 집계 노트북 |
| 기존 집계 스크립트 | `worker/code/preprocess_weather.py` | 지정 관측소의 작업시간 기상 집계 |
| STEP03 | [step03/README.md](step03/README.md) | 전체 관측소 개별 위험지수 수치화 |

## STEP03

```powershell
# 전체 관측소 지수 산출
python worker/code/step03/weather_component_scores.py

# 자동 테스트
python -B -m unittest discover -s worker/code/step03 -p test_weather_component_scores.py -v
```

STEP03의 현재 코드는 `worker/code/step03/`, 사용법은 `worker/docs/step03/README.md`, 최신 결과는 `worker/data/gyeonggi_processed/step03/`에 있습니다. 과거 검토 문서는 `worker/docs/step03/archive/`, 이전 결과는 결과 폴더의 `archive/`에 분리했습니다. STEP01·02와 공유하는 `weather_clean.csv`, `weather_workday_features.csv`는 기존 위치를 유지합니다.

강수 분위수는 각 관측소의 2023년까지 가용자료로 정합니다. `weather_component_train.csv`는 STEP04 학습 입력이고, `weather_component_holdout.csv`는 2024~2025년 평가를 위해 보관한 입력입니다. 이 단계에서는 Isolation Forest를 학습하지 않습니다.
