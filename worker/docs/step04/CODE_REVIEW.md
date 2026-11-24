# STEP04 코드 구성과 리뷰

2026-11-24 기준. STEP03·STEP04 생산 코드와 모델은 수정하지 않고 읽기 및 가상자료 재현으로 검토했다. 자동 테스트 21개와 기존 저장 결과 검증은 통과했다. 아래 결함 중 재추론 관련 항목은 현재 기본 전체 학습 실행에서 오류가 발생했다는 뜻은 아니다.

## 먼저 확인할 문제

| 우선순위 | 위치 | 문제와 영향 | 수정 방향 |
| --- | --- | --- | --- |
| P1 | gyeonggi_weather.ipynb 123~171행의 regular_rain_grid·clean_station | 일자료를 결합한 임시 10분 격자에는 자정값을 넣을 수 있지만 최종 frame은 원본 시각만 유지한다. 원래 자정 행이 없으면 정제 CSV에도 계속 없어 STEP03·04의 엄격한 구간 검사에 걸린다. | 공식 일자료 반영 설계 시 파생 구간을 명시적으로 출력하거나 별도 일합계 복원 특성을 구현하고 관측·복원 출처를 구분한다. |
| P1 | isolation_forest_scores.py 198~206행 | score_rows가 번들 metadata.features·config·schema_version을 확인하지 않고 현재 FEATURES와 행 적격성을 사용한다. 같은 차원의 다른 특성 정의나 작업시간의 모델을 넘겨도 조용히 점수를 만들 수 있다. 고정 143개 대안을 도입할 때 기존 모델 재사용을 막을 수 없다. | 추론 전에 모델·열 순서·특성 정책·작업시간·버전 계약을 검증하고 불일치 시 중단한다. |
| P2 | isolation_forest_scores.py 211~212행 | training_used는 실제 학습 행 소속이 아니라 period=train과 현재 입력 완전성으로 다시 계산된다. 저장 모델에 학습하지 않은 과거 날짜·새 관측소를 추론하면 학습 사용으로 잘못 표시된다. | 이번 fit에서 사용한 키를 따로 보관하거나 training_used를 학습 실행의 기록으로 유지하고 재추론 적격성과 분리한다. |
| P2 | isolation_forest_scores.py 209행 | 모든 행의 모델 버전이 고정 문자열 step04-v1이다. seed·trees·학습자료가 다른 모델도 동일한 행별 버전이어서 여러 결과를 합칠 때 구별하기 어렵다. | 정책 버전과 별도로 학습 입력·설정·모델 해시에 근거한 model_id 또는 run_id를 저장한다. |
| P2 | isolation_forest_scores.py 370~373행 및 별도 saved_results_validation.json | 전체 재실행은 새 run_report를 쓰지만 별도 저장 검증 보고서를 갱신하지 않는다. 예전 saved_results_validation.json이나 RUN_RESULTS.md가 남아 새 결과의 성공 보고서처럼 보일 수 있다. | 후속 보고서에 참조 run_id·manifest 해시를 넣고 검증 시 일치 여부를 확인하거나 실행별 폴더로 분리한다. |

score_rows 결함은 가상자료로 재현했다. 학습하지 않은 NEW_STATION의 2023년 행이 training_used=True가 되었으며, 번들 특성 순서와 작업시간 메타데이터를 바꿔도 오류 없이 추론됐다. 현재 저장 결과는 기본 실행에서 바로 학습한 같은 행을 재추론하므로 이 재현만으로 기존 점수 오류를 주장하지 않는다.

추가 유지보수 사항: audit_prior_rain.py는 원본 열 3·9·10을 위치로 읽고 헤더 이름을 충분히 검증하지 않는다. 현재 원본 스키마에서는 맞지만 내보내기 열 순서가 바뀌면 잘못된 진단이 가능하다. 파일명 날짜 패턴·관측소별 정렬도 전제하므로 원본 형식 변경 시 함께 검증해야 한다.

## 파일 구성

| 파일 | 역할 |
| --- | --- |
| worker/code/gyeonggi_weather.ipynb | STEP01·02 정제, 누적강수의 10분 증분 변환, 기존 일별 집계 |
| worker/code/preprocess_weather.py | 이전 작업일 집계 스크립트. 현재 STEP04는 이 일별 출력으로 학습하지 않음 |
| worker/code/step03/weather_component_scores.py | 기존 S_R·S_W·S_H와 작업일 목록·설정 |
| worker/code/step04/isolation_forest_scores.py | 특성 집계, 두 IF 학습, 점수 변환, 진단, 결과 저장 |
| worker/code/step04/test_isolation_forest_scores.py | 21개 자동 테스트 |
| worker/code/step04/validate_saved_results.py | 저장 산출물 해시·행 보존·기간 분리·제외율·STEP03 공통 집계 대조 |
| worker/code/step04/audit_prior_rain.py | 평가 선행강수 결측의 원본 계보 추적 |
| worker/code/step04/requirements.txt | 전용 환경의 의존성 버전 |

## 기본 실행 흐름

```text
STEP03 전체 작업일 CSV에서 날짜·관측소·작업시간·산지 설정 읽기
    ↓ 날짜-기간 일치, 중복 키, 작업시간 설정 검증
weather_clean.csv를 관측소별 임시 파일로 분리
    ↓ 한 관측소씩 원래 10분 값 읽기
AI1 5개·AI2 3개 특성 집계
    ↓ 각 특성의 전체 예정 구간 완전성 검사
AI1·AI2 각각 2016~2023년 완전 입력 행만 선택
    ↓ 원단위 입력, 결측 대치 없음
Isolation Forest 두 개 학습
    ↓ 각 모델의 실제 학습 행 전체를 점수화
-score_samples → 학습 ECDF → S_AI1·S_AI2 0~100점
    ↓ 같은 모델·학습분포로 평가·추가기간 추론
PKL 저장·재로딩 후 전체 점수 일치 검사
    ↓ 적격성·범위·백분위 조회 검사
전체 행·기간별 결과·제외 진단을 저장하고 run_report를 마지막 게시
```

## 핵심 함수

- load_targets: STEP03 점수는 참고 열로 복사하고 작업일 달력으로 사용한다. 기존 점수 유무로 AI 적격성을 판단하지 않는다.
- load_points: 강수 증분·평균풍속·실제 기온·습도를 읽는다. 동일 중복은 합치고 값이 충돌하는 중복은 오류다.
- aggregate: 작업시간은 08:10~17:00 54개, 선행은 전날 08:10~당일 08:00 144개를 확인한다. 예정 구간 전체가 유효해야 해당 특성이 존재한다.
- fit_bundle: 2023년 말까지의 모델별 완전 행만 사용한다. 최소 100행·서로 다른 입력 벡터 2개 미만이면 해당 모델을 unavailable로 기록한다. 이 최소 기준은 통계적 유의성 기준이 아니다.
- score_rows: 각 모델을 독립 적용한다. 한쪽 불가가 다른 쪽 계산을 막지 않으며 원래 행을 삭제하지 않는다.
- diagnostics: 기간·관측소·모델별 계산률, 결측 조합, 점수분포와 STEP03에서 관측된 극단기상 표시를 요약한다.
- audit 및 validate_saved_results: 내부 점수 일관성과 저장 파일을 검사한다. 원래 10분 자료부터 완전히 독립 재집계한 검증을 대신하지는 않는다.

## 모델 설정과 해석상의 한계

기본 IF는 300개 트리, max_samples=auto, max_features=1.0, contamination=auto, bootstrap=False, seed=42다. auto 샘플링에서는 트리당 학습 표본 수가 전체 학습 행 수와 같지 않다. 학습 ECDF는 학습 행 전체의 점수로 만든다. 설정의 의미는 [공식 IF 문서](https://scikit-learn.org/stable/modules/generated/sklearn.ensemble.IsolationForest.html)를 따른다.

24개 관측소를 대상으로 하지만 AI1 실제 학습자료는 신북면을 제외한 23개 관측소에서 왔다. 동일 관측소 가중치가 아니라 행 단위 통합이다. 계절·관측소별 정상 분포를 별도 보정하지 않으므로 높은 점수는 드문 패턴이며 반드시 위험 강도나 취소확률은 아니다.

현재 평가기간에는 자정 공통 누락과 극단 관측일 제외가 있다. 테스트 통과·저장 집계 일치와 평가자료 대표성을 구분해야 한다. 코드는 train/holdout 분리를 지키지만 결측 때문에 평가 대상이 달라지는 문제까지 자동 해결하지 않는다.

이번 검토에서는 생산 코드 수정·실제 모델 재학습·STEP03 변경을 수행하지 않았다.
