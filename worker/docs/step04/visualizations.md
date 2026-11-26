# IF·LOF·고정형 ECOD 시각화

기존 점수 파일만 읽는다. 재학습하거나 모델·특성·점수 파일을 수정하지 않는다. 기본 그림은 2024~2025년 평가기간이다. AI1은 16,206건, AI2는 17,206건의 동일 관측소·작업일을 세 알고리즘에서 비교한다.

## 그림과 해석

출력 폴더는 `worker/data/gyeonggi_processed/step04_comparison/visualizations/`이다.

| 파일 | 내용 |
|---|---|
| `01-model-overview.png` | AI1·AI2별 5점 구간 점수 분포, 95점 이상 건수·비율, Spearman 상관행렬 |
| `02-pairwise-scores.png` | AI1·AI2별 IF/LOF, IF/ECOD, LOF/ECOD 점수의 대응 관계 |
| `03-heat-weather-relationship.png` | 최고기온·평균습도·고온 지속시간과 각 모델 AI2 점수의 관계 |
| `04-heat-duration-response.png` | 고온 지속시간 6개 구간의 AI2 중앙값과 95점 이상 비율 |
| `weather_model_comparison.pdf` | 위 그림을 순서대로 수록한 4페이지 PDF |
| `heat_duration_plot_values.csv` | 고온 지속시간 그림에 사용한 실제 집계값 |
| `visualization_metadata.json` | 기간·행 수·모델 ID·입력 해시·보존 확인 대상·출력 경로 |

육각형 그래프는 모든 적격 행을 구간별로 집계하며 무작위 표본 추출을 하지 않는다. 색은 관측 건수를 로그 눈금으로 나타낸다. 빈 구간은 흰색이며 결측을 0점으로 채우지 않는다. 기상변수 그림은 관측된 연관성을 보여주며 다른 변수를 고정한 인과효과를 뜻하지 않는다.

점수는 모델별 학습 원시 이상점수의 우측 백분위다. 동점 때문에 특정 점수에 많은 관측이 모일 수 있다. 95점 기준은 예시이며 상위 5%를 정확하게 선택한다는 의미나 지급 기준이 아니다. 특히 LOF의 학습 기본점수와 평가 novelty 점수는 이웃 계산 방식이 다르므로 확률로 해석하지 않는다.

## 재생성

Python 3.14에서 NumPy·SciPy와 Matplotlib 3.11.2가 필요하다. 이번 생성에서는 Matplotlib을 `/private/tmp/wonday-weather-viz-deps`에만 설치했으며 사용자 전역 패키지는 변경하지 않았다. 해당 임시 폴더가 있는 동안 아래 명령으로 재생성할 수 있다.

```bash
PYTHONPATH=/private/tmp/wonday-weather-viz-deps python3.14 /Users/yun/Desktop/wonday/worker/code/step04_comparison/visualize_comparison.py
```

별도 환경에서 준비할 경우 `requirements-visualization.txt`를 사용한다. 한국어 글꼴은 macOS AppleGothic 또는 Linux NotoSansCJK를 탐색한다.

학습기간과 추가기간도 별도 하위 폴더로 만들 수 있다.

```bash
PYTHONPATH=/private/tmp/wonday-weather-viz-deps python3.14 /Users/yun/Desktop/wonday/worker/code/step04_comparison/visualize_comparison.py --period train
PYTHONPATH=/private/tmp/wonday-weather-viz-deps python3.14 /Users/yun/Desktop/wonday/worker/code/step04_comparison/visualize_comparison.py --period after_validation
```

기본 평가기간을 포함해 각 기간의 폴더에서 같은 파일명을 사용한다. 서로 다른 기간을 하나의 그림에 섞지 않는다.
