# STEP04 산출물 해석과 조회 명령

아래 명령은 Windows PowerShell 7 기준이다. 기본 경로는 `C:\Users\user\wonday`다. 조회·검증 명령은 기존 모델을 재학습하지 않는다. 마지막 학습 명령은 실제 재학습과 결과 저장을 하므로 현재 보류한다.

## 결과 해석

| 열 | 읽는 방법 |
| --- | --- |
| S_AI1 | 강수·강풍의 학습분포 백분위. 높을수록 이례적 |
| S_AI2 | 온열의 학습분포 백분위. 높을수록 이례적 |
| AI1_raw_anomaly·AI2_raw_anomaly | -score_samples. 높을수록 이례적이며 서로 다른 모델의 원시값을 직접 비교하지 않음 |
| AI1_input_complete·AI2_input_complete | 각 모델 입력이 완전한지. CSV에서는 True/False 문자열 |
| AI1_status·AI2_status | computed 또는 unavailable |
| AI1_missing_features·AI2_missing_features | 부족한 특성 목록. 구분자는 \| |
| AI1_unavailable_reason·AI2_unavailable_reason | 입력 부족 또는 모델 사용 불가 사유 |
| calculation_status | both, AI1_only, AI2_only, unavailable |
| AI1_training_used·AI2_training_used | 현재 기본 전체 학습 실행에서 학습에 사용된 행. 별도 재추론 시의 제한은 CODE_REVIEW 참고 |
| period | train=2016~2023, validation=2024~2025, after_validation=2026 추가기간 |

예를 들어 95점은 학습 행의 원시 이상점수 중 95%가 해당 점수 이하라는 뜻이다. 취소확률 95%나 손해율이 아니다. 빈칸은 계산 불가이며 0점으로 바꾸지 않는다. 계산되지 않은 다른 분기를 위험 없음으로 해석하지 않는다. S_R·S_W·S_H는 참고용 기존 지수이며 STEP04에서 합산하지 않는다.

상위 10개는 관측소·작업일 10행이다. 같은 날 여러 관측소가 포함될 수 있고 날짜 10개라는 뜻은 아니다. 점수 동점은 날짜·관측소 코드 순으로 정렬한다. 전체 높은 점수만 보고 제외된 여름 날짜가 안전했다고 해석하지 않는다.

## 평가기간 상위 10개씩 조회

Import-Csv는 숫자도 문자열로 읽으므로 반드시 숫자로 정렬한다. 다음 준비 명령과 함수를 같은 PowerShell 세션에서 실행한다.

```powershell
Set-Location -LiteralPath 'C:\Users\user\wonday'
$scorePath = 'worker/data/gyeonggi_processed/step04/weather_ai_scores.csv'
$scores = @(Import-Csv -LiteralPath $scorePath -Encoding utf8)
$holdout = @($scores | Where-Object { $_.period -eq 'validation' })

function Get-AiTop {
    param(
        [object[]]$Rows,
        [ValidateSet('AI1', 'AI2')][string]$Model,
        [int]$Count = 10
    )
    $scoreColumn = 'S_' + $Model
    $statusColumn = $Model + '_status'
    $Rows |
        Where-Object { $_.$statusColumn -eq 'computed' -and $_.$scoreColumn -ne '' } |
        Sort-Object -Property @{
            Expression = { [double]::Parse($_.$scoreColumn, [cultureinfo]::InvariantCulture) }
            Descending = $true
        }, work_date, station_code |
        Select-Object -First $Count
}

Get-AiTop -Rows $holdout -Model AI1 |
    Format-Table work_date, station_code, station_name, S_AI1, work_rain_mm, prior_24h_rain_mm, work_max_mean_wind_mps -AutoSize

Get-AiTop -Rows $holdout -Model AI2 |
    Format-Table work_date, station_code, station_name, S_AI2, work_max_temperature_c, work_mean_humidity_pct, work_heat_minutes_ge31 -AutoSize
```

상위 10개를 선택할 때 두 모델을 모두 요구하지 않는다. AI1 상위는 AI1 계산 행, AI2 상위는 AI2 계산 행에서 각각 고른다.

## 기간과 관측소를 바꿔 조회

```powershell
# 학습기간 AI1 상위 10개
Get-AiTop -Rows @($scores | Where-Object { $_.period -eq 'train' }) -Model AI1 |
    Format-Table work_date, station_code, S_AI1

# 2026년 추가기간 AI2 상위 10개
Get-AiTop -Rows @($scores | Where-Object { $_.period -eq 'after_validation' }) -Model AI2 |
    Format-Table work_date, station_code, S_AI2

# 가평군 가평읍 평가기간 AI1 상위 10개
Get-AiTop -Rows @($holdout | Where-Object { $_.station_code -eq '477802A001' }) -Model AI1 |
    Format-Table work_date, station_name, S_AI1, work_rain_mm

# 평가기간 각 관측소에서 AI1 상위 10개씩
$topByStation = @($holdout | Group-Object station_code | ForEach-Object {
    Get-AiTop -Rows @($_.Group) -Model AI1
})
$topByStation | Format-Table station_code, work_date, S_AI1
```

## 별도 CSV로 저장

Format-Table 결과를 Export-Csv에 보내지 않는다. 원래 행 객체를 내보내야 한다. 아래 경로는 기존 결과와 별도다.

```powershell
$exportDir = 'worker/data/gyeonggi_processed/step04/review/manual_exports'
New-Item -ItemType Directory -Force -Path $exportDir | Out-Null

Get-AiTop -Rows $holdout -Model AI1 |
    Select-Object work_date, station_code, station_name, S_AI1, work_rain_mm, prior_24h_rain_mm, work_max_mean_wind_mps, work_rain_minutes, work_strong_wind_minutes |
    Export-Csv -LiteralPath "$exportDir/top10_AI1_validation.csv" -NoTypeInformation -Encoding utf8BOM

Get-AiTop -Rows $holdout -Model AI2 |
    Select-Object work_date, station_code, station_name, S_AI2, work_max_temperature_c, work_mean_humidity_pct, work_heat_minutes_ge31 |
    Export-Csv -LiteralPath "$exportDir/top10_AI2_validation.csv" -NoTypeInformation -Encoding utf8BOM
```

관측소 코드는 문자열로 보존한다. Excel로 열 때 코드 열을 텍스트로 가져와야 앞의 0을 잃지 않는다.

## 제외 사유와 계산률 조회

```powershell
# AI1 계산 불가 중 처음 20행
$holdout | Where-Object { $_.AI1_status -eq 'unavailable' } |
    Select-Object -First 20 work_date, station_code, AI1_missing_features, AI1_unavailable_reason |
    Format-Table -Wrap

# 모델별 관측소 제외율. ALL 집계는 제외하고 숫자로 정렬
Import-Csv -LiteralPath 'worker/data/gyeonggi_processed/step04/model_diagnostics.csv' |
    Where-Object { $_.period -eq 'validation' -and $_.model -eq 'AI1' -and $_.station_code -ne 'ALL' } |
    Sort-Object { [double]::Parse($_.input_exclusion_pct, [cultureinfo]::InvariantCulture) } -Descending |
    Format-Table station_code, total_rows, input_complete_rows, input_excluded_rows, input_exclusion_pct

# 두 지수/한 지수/모두 불가 건수
$holdout | Group-Object calculation_status | Select-Object Name, Count

# 모델별·기간별 전체 집계
Import-Csv -LiteralPath 'worker/data/gyeonggi_processed/step04/model_diagnostics.csv' |
    Where-Object { $_.station_code -eq 'ALL' } |
    Format-Table period, model, total_rows, input_complete_rows, input_exclusion_pct
```

## 검증과 원인 진단 실행

```powershell
# 자동 테스트: 실제 저장 모델은 변경하지 않음
worker/.venv-step04/Scripts/python.exe -B -m unittest discover -s worker/code/step04 -p test_isolation_forest_scores.py -v

# 현재 저장 결과의 해시·행 보존·제외율·STEP03 공통 집계 대조
worker/.venv-step04/Scripts/python.exe -B worker/code/step04/validate_saved_results.py

# 원본 결측 추적. 별도 진단 폴더만 저장하며 실제 모델은 변경하지 않음
python -B worker/code/step04/audit_prior_rain.py --repo . --output-dir worker/data/gyeonggi_processed/step04/troubleshooting/prior_rain_2024_2025
```

## 재학습 명령은 현재 보류

```powershell
# 아래는 실제 학습을 수행한다. 검토 중에는 실행하지 않는다.
# 새 설정·입력을 검증할 때 기존 결과와 별도 폴더를 사용한다.
worker/.venv-step04/Scripts/python.exe -B worker/code/step04/isolation_forest_scores.py --output-dir worker/data/gyeonggi_processed/step04_candidate
```

기본 CLI는 기존 PKL을 불러오기만 하는 조회 명령이 아니라 전체 재학습 명령이다. 데이터·특성 정의 변경이 확정되기 전에는 실행하지 않는다.

## 현재 상위 결과 예시

평가기간 AI1 1위는 2025-08-13 남양주 진건읍 99.9776점이며 작업 강수 63.5mm, 선행강수 58.0mm, 최대 평균풍속 4.6m/s다. AI2 1위는 2025-07-27 고양 덕양구 99.9748점이며 최고기온 37.8℃, 평균습도 50.84%, 체감온도 31℃ 이상 시간 540분이다.

각 모델 상위 10개는 `worker/data/gyeonggi_processed/step04/review/top10_AI1_validation.csv`, `top10_AI2_validation.csv`에 별도 저장했다. 이는 기존 계산 가능 행의 순위이며 계산 불가 행까지 포함한 위험 순위는 아니다.
