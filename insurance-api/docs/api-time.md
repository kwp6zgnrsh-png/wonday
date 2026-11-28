# 보험 API 시각 기준

| 구분 | 기준 |
|---|---|
| DB 시각 컬럼·서버 내부 비교·재시도 기한 | UTC, 기존 `DATETIME(6)` 유지 |
| 보험 API 응답 일시 | GMT+9, ISO 8601 `+09:00` 명시 |
| 근무일·관측일·18시 배치·보장 개월 계산 | 기존 GMT+9 업무 규칙 유지 |
| 저장된 농업 약정·변경 JSON과 기상 원문 XML | 원문 유지 |

DB의 `2026-11-28 18:00:00.123456`(UTC)는 응답에서 `2026-11-29T03:00:00.123456+09:00`이다.
같은 순간이며 날짜가 다음 날로 넘어갈 수 있다. null은 null로, 마이크로초 정밀도는 그대로 유지한다.

## 적용 API

목록·상세·생성·재시도 등 동일 DTO를 사용하는 모든 경로에 적용한다.

| API | 일시 필드 |
|---|---|
| `/api/products`, `/{productId}` | `salesStartAt`, `salesEndAt` |
| `/api/contracts`, `/{contractId}` | `acceptedAt`, `coverageFrom`, `coverageTo` |
| `/api/platform/link` | `consentedAt` |
| `/api/rewards/salary-account/checks`, `/latest` | `checkedAt`, `recordedAt` |
| `/api/claims`, `/{claimId}` | `openedAt`, `updatedAt`, 취소 `occurredAt`, 아래 약정 일시 |
| `/api/admin/claims`, `/{claimId}`, `/{claimId}/history` | 접수·계약·이벤트·이력의 모든 일시와 아래 약정 일시 |
| `/api/weather/stations`, `/nearest` | `metadataSyncedAt` |
| `/api/admin/claims/{claimId}/weather-station` | `selectedAt` |
| `/api/admin/weather/collections`, `/{id}` | `collectedAt` |
| `/api/admin/weather/collection-jobs`, `/{id}`, `/{id}/attempts` | `leaseExpiresAt`, `createdAt`, `updatedAt`, `startedAt`, `finishedAt` |
| Spring 기본 JSON 오류 | `timestamp` |

회원·본인 확인·급여계좌 등 일시 필드가 없는 응답은 기존 형식을 유지한다. 웹 프록시 경로에도 동일하게 적용한다.

## 약정·원문 처리

- 근로자·관리자 접수 상세의 `work` 중 `confirmedAt`, `agreedStartAt`, `agreedEndAt`, `agreedBreakStartAt`, `agreedBreakEndAt`은 응답용 객체에서만 GMT+9로 변환한다.
- 저장 JSON의 `Z`·다른 오프셋은 수정하지 않는다. 날짜(`agreedWorkDate`)와 원래 합의한 업무 시간대(`agreedTimeZone`)는 약정 메타데이터이므로 보존한다.
- 기상 상세의 `pages`는 제공처의 XML 원문이다. 원문 안의 관측 시각·`24:00` 등을 JSON 응답 일시처럼 다시 해석하거나 변환하지 않는다.
- 전체 JSON 문자열을 재귀적으로 치환하지 않는다. 약관·상품 규칙·원문·기타 문자열을 일시로 추측하지 않는다.

## 코드·클라이언트

- `ApiTime.fromUtc`가 DB UTC 일시에 UTC 오프셋을 붙인 뒤 같은 순간의 `+09:00`으로 변환한다. 단순히 `atOffset(+09:00)`을 붙이지 않는다.
- 응답 DTO는 `OffsetDateTime`을 사용하고, 약정 문자열은 `ApiTime.fromOffsetString`으로 변환한다.
- 전역 ObjectMapper·JVM/DB 시간대·엔티티 매핑은 변경하지 않는다. 농업 수신 JSON 직렬화·중복 비교·DB 저장과 재시도/보장 판정은 그대로 유지한다.
- 프런트는 응답의 오프셋을 포함해 해석한다. 문자열에 `Z`를 붙이거나 숫자에 다시 9시간을 더하지 않는다.
- 이번 응답 형식 변경으로 과거의 오프셋 없는 UTC 일시와 UTC `Z` 응답은 `+09:00`으로 바뀐다. API 소비 측도 이 규격을 사용한다.
