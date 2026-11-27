# 농업 API

## 보험 서버 연동

내·외국인 등록 정보 확인은 `POST /api/internal/insurance/v2/worker-verifications`로 추가했다. 기존 V1을 유지하고 같은 서버 토큰·no-store 정책을 사용한다. 국적·번호 종류·HMAC·이름·전화가 일치하는 WORKER만 반환하며 V1 또는 원문 조회로 fallback하지 않는다. 요청·응답·설정·검증 범위는 [보험 V2 문서](docs/worker-verification-v2-api.md)를 참고한다.

본인 확인, 개인별 근무 변경 목록, 확정 약정 상세 API의 사용 방법과 후속 연결 사항은 [보험 연동 API 문서](docs/insurance-integration-api.md)를 참고한다. 서버 전용 Bearer 토큰을 사용하며 농업 앱의 로그인 세션과 분리한다. 팀 합의에 따라 보험 서버가 동의를 관리하고 농업 서버는 서버 토큰과 근로자·근무 소유 관계를 확인한다. 새 V5는 아직 DB에 적용하지 않았다.

## 공고와 여러 날짜 일정 등록

고용주로 로그인한 뒤 `POST /api/job-postings`에 JSON을 보낸다. 요청에는 `FARMSESSION` 쿠키와 현재 세션의 `X-CSRF-TOKEN` 헤더를 포함한다. 소유자 ID는 로그인 세션에서 가져온다.

```json
{
  "title": "사과 수확 근로자 모집",
  "description": "사과 수확과 운반 작업입니다.",
  "cropCode": "FT01060100060100",
  "workTypeCode": "0058",
  "workLocation": "충청북도 충주시 예시농장",
  "latitude": 37.12345678,
  "longitude": 127.12345678,
  "timeZone": "GMT+9",
  "headcount": 2,
  "dailyWage": 120000,
  "schedules": [
    {
      "workDate": "2026-11-30",
      "plannedStartAt": "2026-11-30T08:00:00",
      "plannedEndAt": "2026-11-30T16:00:00",
      "breakStartAt": "2026-11-30T12:00:00",
      "breakEndAt": "2026-11-30T13:00:00"
    },
    {
      "workDate": "2026-12-02",
      "plannedStartAt": "2026-12-02T08:00:00",
      "plannedEndAt": "2026-12-02T12:00:00",
      "breakStartAt": null,
      "breakEndAt": null
    }
  ]
}
```

작목은 제공된 작목 목록, 작업 유형은 공통 4자리 코드를 사용하기로 했다. 위 예시는 사과·수확이다. 현재 등록 API의 검증은 필수 여부와 DB 길이이며, 기준 목록의 허용값 검증까지 추가한 것은 아니다. APP-01은 포함된 코드 사전으로 이름을 함께 응답하고 미등록 기존 값의 이름은 `null`로 반환한다. 출처·중복 처리·문자열 코드는 [코드 기준 문서](docs/agricultural-code-catalog.md)를 참고한다.

성공 응답은 `201`이며 `jobPostingId`, 세션에서 확인한 `employerId`, 날짜별 공통 `headcount`, `schedules` 배열의 `workScheduleId`·`workDate`·`status`를 반환한다. 새 일정의 상태는 `OPEN`, `terms_version`은 1이다. 날짜 전체를 검증한 뒤 공고와 모든 일정을 같은 트랜잭션으로 저장한다.

신규 공고의 `workLocation`·`latitude`·`longitude`는 필수다. 좌표는 JSON 숫자로 보내고, 원본 위도 -90~90·경도 -180~180 범위를 검사한 뒤 HALF_UP으로 소수점 6자리 반올림해 저장한다. 위 예시의 저장 좌표는 `37.123457`, `127.123457`이다. 숫자 문자열은 거절한다. 고용주 내 공고, 근로자 목록·상세에는 저장된 `latitude`·`longitude`를 제공한다.

V7은 공고와 확정 약정에 nullable 좌표 컬럼을 추가한다. 기존 누락값은 `null`로 유지하고 0이나 현재 공고 좌표로 보완하지 않는다. 보험 상세는 약정의 `agreedLatitude`·`agreedLongitude`만 반환한다. 이번 범위에는 지도 화면, 주소 검색, 작업지 수정과 선정·확정 API를 추가하지 않았다. 자세한 계약과 후속 연결은 [작업지 좌표 API 문서](docs/job-location-api.md)를 참고한다.

개인 확인은 farm-demo 로그인 후 **작업지 좌표 등록 · 조회 확인**(`/demo/job-location`)에서 한다. 고용주는 좌표 입력·실제 등록·자동 재조회·원본/6자리 저장값 비교·범위/누락 오류를 확인한다. 근로자는 공고 ID로 조회하거나 본인 기존 약정을 선택해 실제 null 응답을 확인한다. 약정은 데모 전용 세션 경로에서 보험 상세와 같은 조회 서비스를 사용하며 서버 토큰을 화면에 노출하지 않는다. 보험 Bearer 인증의 HTTP 검증과는 별개다. 실제 지도·user-web은 변경하지 않는다.

| 오류 | 상태 코드 | 응답의 code |
| --- | --- | --- |
| 로그인 세션 없음 | 401 | 기존 인증 진입점 응답 |
| 유효한 CSRF 토큰을 가진 근로자의 등록 요청 | 403 | `EMPLOYER_ROLE_REQUIRED` |
| 필수 항목·길이·양수 조건 오류 | 400 | `INVALID_JOB_POSTING` |
| 중복 근무일 | 400 | `DUPLICATE_WORK_DATE` |
| 작업지 시간대가 `GMT+9`와 다름 | 400 | `INVALID_TIME_ZONE` |
| 근무일과 시작 일자의 불일치·종료 순서 오류 | 400 | `INVALID_WORK_TIME` |
| 휴게시간 한쪽 누락·순서·범위 오류 | 400 | `INVALID_BREAK_TIME` |
| DB 범위·정밀도 초과 또는 작업지에서 해석이 모호한 일시 | 400 | `INVALID_LOCAL_TIME` |
| JSON 역직렬화 오류 | 400 | `INVALID_REQUEST_BODY` |

팀 합의에 따라 `timeZone`은 정확히 `GMT+9`만 허용하며 저장 시 같은 표기를 유지한다. 기존에 저장된 시간대 값은 그대로 보존한다. 시간은 해당 시간대의 현지 일시로 받고 시작 날짜를 `workDate`로 사용한다. 종료는 시작 이후여야 하며, 날짜를 넘기는 근무도 입력할 수 있다. 일시 정밀도는 MySQL과 동일한 마이크로초까지 지원한다.

정원과 일급에는 따옴표 없는 JSON 정수를 입력한다. 소수와 숫자 문자열은 `400 INVALID_REQUEST_BODY`로 거절한다. 일시는 `2026-11-30T08:00:00`처럼 시간대 접미사 없이 입력하며, `Z`나 `+09:00`이 붙은 일시도 같은 오류로 거절한다. 휴게시간 오류의 `field`는 누락된 시작·종료 항목 또는 근무 범위를 벗어난 항목을 가리킨다.

날짜·일시는 JSON 문자열로 입력한다. `[2026,11,30]` 같은 배열은 거절하며, 날짜와 시간 사이에는 대문자 `T`를 사용한다. 소문자 `t`가 포함된 일시도 `400 INVALID_REQUEST_BODY`로 거절한다.

V4는 공고의 `time_zone`과 일정의 `terms_version`을 추가한다. 기존 공고의 시간대는 실제 작업지 확인 후 보완해야 하므로 DB에서 NULL을 허용하고, 신규 API 등록에는 시간대를 필수로 받는다. 공통 정원·일급은 기존 `default_headcount`, `default_daily_wage` 컬럼에 매핑한다. 기존 행의 시간대 보완 후 DB의 NOT NULL 제약을 적용하는 후속 마이그레이션이 필요하다.

## 날짜별 일정 수정과 조건 이력 (JOB-06, 첫 구현)

`PUT /api/job-postings/{jobPostingId}/schedules/{workScheduleId}`로 일정 하나의 날짜·근무·휴게 조건을 수정한다. 로그인한 고용주 본인 소유 공고에 속한 일정만 수정할 수 있다. `FARMSESSION` 쿠키와 로그인 후 발급받은 현재 `X-CSRF-TOKEN`이 필요하다. 고용주 ID, 공통 정원·일급, 모집 상태는 이 요청으로 변경하지 않는다.

`GET /api/job-postings/mine`에서 수정 대상의 `termsVersion`을 확인해 `expectedTermsVersion`으로 보낸다. 나머지 조건은 전체 입력하며 휴게를 없애려면 양쪽을 `null`로 보낸다. 등록과 동일한 엄격한 숫자·날짜·일시 형식 및 근무·휴게 검증을 사용한다.

```json
{
  "expectedTermsVersion": 1,
  "workDate": "2026-12-02",
  "plannedStartAt": "2026-12-02T09:00:00",
  "plannedEndAt": "2026-12-02T17:00:00",
  "breakStartAt": "2026-12-02T12:00:00",
  "breakEndAt": "2026-12-02T13:00:00"
}
```

성공은 `200`이며 공고·일정 ID, 변경된 날짜·시간·휴게, 유지된 `status`, 새 `termsVersion`을 반환한다. 실제 조건이 바뀌면 버전을 1 증가시키고, 최신 버전과 같은 조건을 다시 저장하면 버전과 이력을 늘리지 않는다. 오래된 버전의 요청은 자동 덮어쓰기 대신 `409 SCHEDULE_VERSION_CONFLICT`로 거부한다.

| 상황 | 처리 |
| --- | --- |
| 대상 날짜에 확정 근무 없음 | 수정 가능 |
| 다른 날짜에만 확정자 있음 | 대상 날짜 수정 가능 |
| 대상 날짜에 취소되지 않은 확정 근무가 남음 | `409 SCHEDULE_ALREADY_CONFIRMED` |
| 모든 확정 근무에 시작 전 취소 `CANCELLED` 기록 있음 | 이력을 보존하면서 수정 가능 |
| 조기 종료 `EARLY_ENDED` | 시작 전 취소로 취급하지 않으므로 수정 차단 |
| 모집 취소 `REMOVED` | `409 SCHEDULE_NOT_EDITABLE` |
| 모집 마감 `CLOSED` 또는 일시중단 `PAUSED` | 위 확정 여부 조건을 만족하면 수정. 상태는 그대로 유지 |
| 시간대가 비어 있거나 해석 불가인 기존 공고 | `409 TIME_ZONE_UNAVAILABLE`. GMT+9로 자동 보완하지 않음 |
| 다른 공고·고용주의 일정, 없는 공고·일정 | `404` |
| 중복 날짜·잘못된 시간·휴게·입력 형식 | `400`과 오류 필드 |

이번에는 `PAUSED` 상태값만 준비하며, 중단·재개·마감·취소 API는 추가하지 않는다. 수정만으로 모집을 재개하거나 지원·확정 상태를 바꾸지 않는다. 실제 작업 시작 확인 기준은 미정이므로 예정 시각만으로 시작 여부를 임의 판정하지 않는다. 수정 가능 여부는 검증을 거쳐 기록된 시작 전 취소 유형을 기준으로 한다.

V6의 `work_schedule_revision`은 버전별 조건을 추가 저장한다. 첫 실제 수정 때 기존 버전의 현재 조건을 `BASELINE`으로, 수정 후 조건을 `UPDATED`로 보관한다. `BASELINE`의 시각은 과거 변경 시각이 아니라 현재 상태를 관측한 UTC 시각이며 원래 변경자를 추측하지 않는다. 날짜·근무·휴게와 당시 시간대·작업지·작목·작업 유형·정원·일급을 복사한다. 일정 변경과 두 스냅샷 저장은 한 트랜잭션이므로 실패 시 모두 롤백한다. 확정 근무의 약정과 보험 변경 버전은 수정하지 않는다. 이력 조회 API와 공통 조건 수정은 후속 범위다.

수정은 `READ_COMMITTED`에서 공고 → 일정 순서로 잠그고 확정 여부·중복·조건 버전을 검사한다. 향후 선정·공통 조건 수정·일정 추가도 같은 잠금 순서를 사용해야 한다. 이번 구현만으로 아직 없는 선정 API의 동시성까지 검증한 것은 아니다.

기존 V1~V5는 수정하지 않았고 V6를 실제 DB에 적용하지 않았다. 서버를 시작하면 Flyway가 새 마이그레이션을 실행하므로 공유 DB의 버전·적용 순서를 먼저 확인한다. 지원 당시 버전 비교·변경 표시, 새 취소 정책을 반영한 JOB-05 집계, 재모집·재지원은 후속 기능에서 연결한다.

## 날짜 모집 취소 (JOB-07)

`POST /api/job-postings/{jobPostingId}/schedules/{workScheduleId}/cancel`에 `{"expectedTermsVersion":1}`을 보낸다. 고용주 세션과 현재 CSRF 토큰이 필요하고, 조건 버전은 내 공고 조회에서 읽는다. 성공은 `200`이며 일정의 `status`를 `REMOVED`로 바꾸고 UTC 취소 시각을 반환한다.

- `OPEN`·`PAUSED`·`CLOSED`에서 현재 남은 확정자가 없고 서버 시각이 예정 시작 시각 이전이면 허용한다. 시작 시각과 같으면 이미 시작한 것으로 본다.
- 확정 근무 전부가 시작 전 취소 `CANCELLED`된 날짜도 허용한다. 조기 종료 `EARLY_ENDED`는 시작 전 취소로 취급하지 않는다.
- 일정·지원·약정·기존 개인 취소·보험 변경 이력을 삭제하거나 덮어쓰지 않는다. 새 개인 취소 기록도 생성하지 않는다.
- 조건 버전은 변경하지 않는다. 이미 `REMOVED`인 날짜의 재요청은 현재 버전·시작 시각 검사 없이 기존 결과를 반환하고 최초 취소 시각을 보존한다. 역할·소유자 검사는 항상 수행한다.
- 기존 `work_schedule.cancelled_at` 컬럼에 이번 API의 UTC 시각을 보관한다. DB 마이그레이션은 추가·수정하지 않았다.

요청·응답, 오류 코드와 검증 인계 항목은 [JOB-07 API 문서](docs/job-07-date-cancellation.md)에 정리했다. `farm-demo`의 `/demo/jobs`에서 공고와 날짜를 선택하고 **날짜 모집 취소**를 눌러 확인할 수 있다. 조회한 조건 버전과 현재 CSRF 토큰은 자동 전송한다. 성공 후 목록을 다시 조회해 ‘모집 취소’를 표시하고, **취소 결과 다시 확인**으로 최초 취소 시각이 유지되는지도 확인할 수 있다. 오류는 실제 HTTP 상태·코드·메시지를 표시한다. 근로자의 내 지원 내역에 ‘모집 취소’를 표시하는 연결은 APP-04 구현에서 진행한다.

## 내 공고와 날짜별 모집 현황 조회 (JOB-05)

고용주로 로그인한 후 `GET /api/job-postings/mine`을 호출한다. 소유자 ID는 세션에서 가져오며 요청 파라미터로 지정하지 않는다. `FARMSESSION` 쿠키를 유지하고 호출하면 된다. 성공은 `200`이며 본인 공고가 없으면 `{"jobPostings":[]}`를 반환한다. 미인증 요청은 `401`, 근로자는 `403 EMPLOYER_ROLE_REQUIRED`를 받는다.

응답의 `jobPostings`에는 공고 ID·제목·설명·작목·작업 유형·작업지·시간대·일일 정원·일급과 `schedules`가 들어간다. 공고는 ID 내림차순, 각 공고의 일정은 근무일·일정 ID 오름차순으로 제공한다. 기존 공고의 시간대가 비어 있으면 `timeZone`은 null이다.

각 일정에는 ID·근무일·예정 근무 및 휴게 일시·모집 상태·일정 버전과 아래 집계값을 제공한다.

| 응답 필드 | 계산 기준 |
| --- | --- |
| `applicantCount` | 해당 일정의 총 지원 행 수. PENDING·SELECTED·NOT_SELECTED 모두 포함 |
| `confirmedCount` | 해당 일정의 지원에 연결된 확정 근무 생성 건수 |
| `remainingSlots` | `max(공고 headcount - confirmedCount, 0)` |

남은 자리는 확정 근무 생성 수 기준의 통계값이다. 모집 상태가 `CLOSED` 또는 `REMOVED`이면 자리가 남아 있어도 신규 지원을 허용하는 의미가 없다. 확정 후 취소된 자리의 재모집 가능 여부는 팀 합의 대기 중이며, 이번 API는 그 여부를 판정하거나 모집 상태를 바꾸지 않는다. 현재 스키마에는 개인별 `work_change`가 없으므로 취소 건의 별도 집계는 해당 기능과 정책 확정 후 연결한다.

고용주 조건은 모든 공고·일정·집계 쿼리에 적용한다. JPA 저장소의 네이티브 집계로 지원과 확정 근무를 한 번에 읽으며, 공고마다 개별 집계 쿼리를 실행하지 않는다. MySQL의 REPEATABLE_READ 읽기 트랜잭션에서 공고·일정·집계에 같은 스냅샷을 사용한다.

## 근로자 공고 목록·상세 조회 (APP-01)

근로자 세션으로 `GET /api/job-postings?page=0&size=20`과 `GET /api/job-postings/{jobPostingId}`를 호출한다. GET 조회에는 CSRF 토큰이 필요하지 않다. `/api/job-postings/mine`의 고용주 전용 규칙은 유지한다.

- 목록은 시작 전 `OPEN` 날짜가 하나 이상인 공고를 공고 ID 내림차순으로 반환한다. 정원 충족·본인 지원 여부로 공고를 숨기지 않는다.
- `recruitingWorkDates`와 `recruitingSchedules`는 시작 전 `OPEN` 날짜만 포함한다. 후자는 날짜별 확정 인원·남은 자리·본인 기준 지원 가능 여부를 함께 제공한다.
- 상세는 모든 상태와 지난 날짜를 포함하며, 목록에서 제외된 공고의 기존 링크도 조회할 수 있다.
- `confirmedCount`는 시작 전 취소를 제외한 확정 근무 수이며 조기 종료는 포함한다. 남은 자리는 `max(headcount - confirmedCount, 0)`이다. 기존 JOB-05의 생성 건수 통계와 구분한다.
- 신규 지원 가능 여부는 상태·예정 시작·정원·본인의 지원 이력을 함께 확인한다. 미선정·시작 전 취소의 재지원 정책도 판정에 반영하며, 실제 지원 저장은 APP-02~03에서 구현한다.
- 코드와 이름을 함께 반환하고, 미등록 기존 코드의 원문을 보존하며 이름만 `null`로 반환한다.
- 별도 모집 마감 시각·조건 변경 응답 기한·자동 상태 변경을 추가하지 않는다. 기존 데이터와 보험 연동 응답도 수정하지 않는다.

요청·응답 예시와 오류·검증 범위는 [APP-01 문서](docs/app-01-worker-job-query.md)에 정리했다. `farm-demo`로 실행한 뒤 근로자로 로그인하고 **공고 목록 · 상세 보기**를 누르면 `/demo/worker-jobs`에서 실제 목록·상세·날짜별 지원 가능 여부를 확인할 수 있다. 공고 ID 직접 입력과 `?jobPostingId=101` 링크로 목록에서 빠진 공고도 조회한다. 실제 상태코드·오류·응답 JSON을 표시하며, 지원·선정 쓰기 기능은 추가하지 않았다.

## 브라우저에서 공고·일정 확인하기 (farm-demo)

`farm-demo` 프로필로 실행한 뒤 `/demo/login`에서 고용주 계정으로 로그인하고 **내 공고 조회 · 일정 수정하기**를 누른다. `/demo/jobs`로 직접 들어가도 된다. 18081 포트로 실행했다면 `http://127.0.0.1:18081/demo/jobs`를 사용한다. 이 페이지는 `farm-demo`에서만 제공되며 `user-web`과 별개다.

1. 내 공고 목록을 조회하고 공고·날짜를 선택한다.
2. 채워진 근무일·시작/종료·휴게를 수정하고 저장한다. 휴게가 없으면 양쪽을 비운다.
3. 실제 HTTP 상태 코드·오류 코드·메시지·필드와 응답 원문을 확인한다.
4. 성공하면 목록을 다시 조회하고 저장 전후 조건을 비교한다.

브라우저가 세션 쿠키를 전송하고 화면이 CSRF 토큰을 발급받아 넣는다. 선택한 일정의 조건 버전을 보관해 `expectedTermsVersion`으로 전송한다. 저장 직전에 버전을 자동으로 바꾸지 않으므로, 다른 요청이 먼저 수정하면 409를 그대로 보여준다. 충돌 시 입력을 유지하며 **최신 조건 다시 불러오기**를 누를 때 현재 입력을 초기화한다.

근무일과 각 일시의 날짜는 달력으로, 시간은 시·분·초 선택 칸으로 입력한다. 날짜를 넘기는 근무를 위해 종료 날짜는 별도로 선택한다. 근무일을 변경하면 시작·종료·휴게의 날짜도 확인해야 한다. **휴게 없음으로 비우기**는 휴게 시작·종료의 날짜와 시간을 모두 비워 `null`로 전송한다. API에는 기존 현지 일시 문자열 형식으로 조합해 보내며 JavaScript Date 변환을 사용하지 않는다. 시간을 바꾸지 않으면 기존 소수점 이하 초를 보존하고, 시간을 변경하면 선택한 초 단위 값을 보낸다.

근로자 계정은 실제 조회 API의 403을 확인할 수 있다. 계정이 다른 탭에서 바뀌거나 세션이 만료되면 기존 목록·입력을 정리한다. 토큰과 비밀번호를 화면의 요청 기록이나 로컬 저장소에 보관하지 않는다.

이 화면은 실제 로컬 API와 DB를 사용하며 공고·확정 근무를 자동 생성하지 않는다. 공고가 없으면 기존 등록 API로 먼저 준비한다. 저장 전후 비교는 조회값과 수정 응답의 비교이며, 저장된 DB 이력 조회 결과는 아니다. 공통 조건 수정·중단·재개·변경 이력 조회 화면은 후속 범위다. 서버 실행 시 새 V6가 적용될 수 있으므로 공유 DB의 적용 순서를 먼저 확인한다.

## 농업 API 로그인

루트 README의 DB 설정을 마친 후, 데모 계정을 만들려면 로컬 개발 DB에 연결된 PowerShell에서 다음과 같이 실행한다. 두 비밀번호는 예시이며, 각자 정한 값으로 바꾼다.

```powershell
cd farm-api
$env:SPRING_PROFILES_ACTIVE = 'farm-demo'
$env:FARM_DEMO_EMPLOYER_PASSWORD = 'local-employer-demo'
$env:FARM_DEMO_WORKER_PASSWORD = 'local-worker-demo'
$env:SERVER_ADDRESS = '127.0.0.1'
.\gradlew.bat bootRun
```

`demo-employer`는 `EMPLOYER`, `demo-worker`는 `WORKER`다. `farm-demo` 프로필로 시작하면 최초 실행 시 비밀번호를 BCrypt로 해시하여 저장한다. 같은 로그인 ID가 이미 있으면 기존 계정과 비밀번호를 그대로 사용한다. 프로필을 끄더라도 이미 만들어진 계정은 DB에 남으므로 전용 로컬 개발 DB에서 실행한다.

브라우저에서 `http://127.0.0.1:8081/demo/login`을 열고 데모 아이디와 비밀번호를 입력한다. 로그인하면 서버가 반환한 계정 역할에 따라 ‘고용주님, 반갑습니다.’ 또는 ‘근로자님, 반갑습니다.’가 표시된다. 로그아웃한 뒤 다른 계정으로 로그인할 수 있다. `SERVER_PORT=18081`로 실행했다면 주소에도 `18081`을 사용한다. 데모 화면은 `farm-demo` 프로필로 실행할 때 제공된다.

| 요청 | 처리 |
| --- | --- |
| `GET /api/auth/csrf` | `headerName`, `token`을 받는다. |
| `POST /api/auth/login` | `application/x-www-form-urlencoded` 본문에 `loginId`, `password`를 보내고 CSRF 헤더를 설정한다. URL에 쿼리 문자열이 있으면 `400`, 본문 형식이 다르면 `415`다. 성공은 `204`, 자격 증명 오류나 UTF-8 기준 72바이트를 넘는 비밀번호는 `401`이다. |
| `GET /api/auth/me` | 세션의 계정 ID, 이름, 역할을 받는다. 로그인하지 않았으면 `401`이다. |
| `POST /api/auth/logout` | 로그인 후 새로 발급받은 CSRF 토큰을 헤더에 보내면 현재 세션이 종료된다. 성공은 `204`다. |

요청 사이에 `FARMSESSION` 쿠키를 전달한다. 로그인과 로그아웃 이후에는 다음 `POST` 전에 CSRF 토큰을 다시 받는다. 다른 역할을 확인하려면 로그아웃 후 새 토큰을 받아 다시 로그인한다.

## 테스트와 커버리지

기능 구현 단계에는 `.\gradlew.bat test`가 **단위 테스트와 단위 JaCoCo 리포트**를 실행한다. 팀 합의에 따라 전체 커버리지 90% 미달에 따른 자동 실패는 일시 해제한다. 단위 테스트 실패는 계속 빌드를 실패시킨다. 측정 대상은 줄이지 않으며, 리포트에서 미검증 코드를 확인한다. CI의 farm-api 단계는 `unitTest jacocoUnitTestReport bootJar`로 단위 테스트·리포트 생성과 JAR 빌드를 실행한다.

90% 기준을 직접 확인하려면 `.\gradlew.bat jacocoUnitTestCoverageVerification`을 실행한다. 이 명시적 검사의 기준은 유지한다. 통합 테스트는 기능이 갖춰진 뒤 Docker를 켜고 `.\gradlew.bat fullTest`로 실행한다. `fullTest`는 기존처럼 단위 90%·통합 80% 검사를 모두 포함한다. 통합 테스트는 Testcontainers로 실행마다 새 MySQL 8.4 컨테이너를 띄우므로 로컬 개발 DB(`.env`의 `farm_db`)를 사용하지 않는다.

| 명령 | 실행 범위 | 라인 기준 | 리포트 |
| --- | --- | --- | --- |
| `.\gradlew.bat test` | 단위 테스트와 단위 리포트. 기존 제외 태그 유지 | 측정만, 수치로 차단하지 않음 | `build/reports/jacoco/jacocoUnitTestReport/html/index.html` |
| `.\gradlew.bat jacocoUnitTestCoverageVerification` | 단위 테스트와 명시적 커버리지 기준 검사 | 90% 이상 | 콘솔의 검사 결과 |
| `.\gradlew.bat unitTest` | 단위 테스트만 | 검사 없음 | `build/reports/tests/unitTest/index.html` |
| `.\gradlew.bat integrationTest` | 통합: `@Tag("integration")` + `@SpringBootTest` (Docker 필요) | 80% 이상 (`fullTest`에서 검사) | `build/reports/jacoco/jacocoIntegrationTestReport/html/index.html` |
| `.\gradlew.bat fullTest` | 단위 + 통합, 커버리지 기준 둘 다 (Docker 필요) | 90% / 80% | 위 두 리포트와 합산 `build/reports/jacoco/test/html/index.html` |

새 Spring 통합 테스트 클래스에는 `@Tag("integration")`과 `@Import(MySqlTestDatabase.class)`를 붙인다. 태그가 없으면 단위 테스트로 실행된다. 데모 프로필 테스트만 전용 인메모리 H2에서 계정을 등록한다.

운영 코드 결함을 재현하는 테스트에는 `@Tag("known-defect")`를 붙인다. 이 테스트는 결함이 고쳐지기 전까지 실패하므로 `test`에서 빠지며, `.\gradlew.bat knownDefectTest`로 따로 실행한다. 결함을 고친 뒤 태그를 지우면 일반 테스트로 실행된다. 두 측정 모두 `FarmApiApplication`을 제외하고, 단위 측정은 필터 체인 조립만 하는 `SecurityConfig`도 제외한다. 각 리포트 폴더 옆에 같은 이름의 XML 파일도 만들어진다.

## 회원 식별정보 저장 구조와 기존 확인 API

회원 식별정보의 암호문·HMAC 저장과 V1 근로자 확인 API의 단계적 조회 전환은 [식별정보 문서](docs/member-identity-storage.md)를 참고한다. 기존 요청·응답·주민번호 컬럼은 유지한다. 기본 LEGACY 모드로 기존 조회를 보존하고, 확인된 내국인만 명시적으로 이관·검증한 후 ENCRYPTED로 전환한다. 실제 이관은 자동 실행하지 않는다.

신규 V8은 선행 좌표 V7 다음에 적용해야 한다. [V8 통합 검증](docs/verification/2026-11-26-member-identity-v8-migration/VERIFICATION_REPORT.md)은 당시 Testcontainers 격리 MySQL 8.4에서 해당 클래스만 실행해 7개 모두 통과한 기록이다. 실제 개발 DB와 이관 도구는 사용하지 않았다. 이 결과는 V8까지의 검증 기록이며, V9 마이그레이션과 실제 DB에서의 회원가입 처리 검증을 대신하지 않는다.

## 회원가입과 선택 급여 계좌 저장

`POST /api/auth/signup`은 JSON과 기존 농업 CSRF를 사용한다. 회원 식별정보와 선택 급여 계좌를 한 트랜잭션으로 저장하고 201을 반환하지만 자동 로그인하지 않는다. 이후 기존 로그인 API를 호출하면 `/api/auth/me`에 `nationalityType`이 추가된다. 기존 미이관 계정은 null을 반환한다.

신규 V9는 회원당 한 개의 급여 계좌 저장 테이블만 추가한다. 계좌번호는 하이픈을 제거한 숫자 1~30자리를 별도 키로 암호화하며 응답으로 반환하지 않는다. 계좌 입력 시 `FARM_SALARY_ACCOUNT_ENCRYPTION_KEY`가 필요하고, 모든 가입에는 기존 식별정보 암호화/HMAC 키가 필요하다. 계좌를 생략하면 계좌 키는 필요 없다. 실제 키는 저장소에 넣지 않는다.

요청·응답·설정과 검증 범위는 [회원가입 API 문서](docs/signup-api.md)를 참고한다. farm-demo의 `/demo/signup`에서 선택 계좌를 포함하거나 생략한 가입, 별도 로그인, `/me`의 국적과 실제 계좌 등록 여부를 확인할 수 있다. CSRF는 자동 처리하고 요청 상태·오류를 표시한다. 데모 전용 계좌 조회는 본인 세션으로만 허용하고 번호·암호문은 반환하지 않는다.

## 근로자 본인 급여 계좌 관리

`GET`·`PUT`·`DELETE /api/me/salary-account`로 근로자 본인의 급여 계좌를 조회·등록·교체·해제한다. 기존 로그인 세션·CSRF를 사용하고, 조회 시 계좌번호를 마스킹하며 오래된 버전의 변경은 409로 거절한다.

신규 V10에서 회원의 계좌 버전을 추가해 계좌 해제·재등록 후에도 과거 화면의 요청을 구분한다. 요청·응답과 검증 범위는 [계좌 관리 API 문서](docs/salary-account-api.md)를 참고한다.

farm-demo의 `/demo/salary-account`에서 실제 급여 계좌 조회·등록·교체·해제와 마스킹·409를 확인할 수 있다. CSRF와 조회 당시 버전을 자동 전송하고, 충돌 후에는 최신 조회를 요구한다. 두 창 확인 절차는 계좌 관리 API 문서의 farm-demo 절을 참고한다.

## 보험 V2 본인 확인 데모

farm-demo의 `/demo/worker-verification`에서 근로자 로그인 후 성공·불일치·입력 오류를 확인할 수 있다. CSRF는 자동 처리하며, 실제 내부 API의 서버 전용 Bearer 토큰 인증과는 별도로 확인하는 화면이다.

요청·응답·설정과 검증 범위는 [보험 V2 문서](docs/worker-verification-v2-api.md)를 참고한다.

보험 서버용 급여 계좌 조회·근무 완료·user-web 연결은 별도 작업 범위다.


## 보험 서버용 급여 계좌 등록 상태 조회

GET /api/internal/insurance/workers/{workerId}/salary-account는 기존 서버 전용 Bearer 토큰으로 근로자의 계좌 등록 여부·은행 코드만 제공한다. 미등록은 200, 없는 회원·고용주는 404다. 번호·예금주·암호문은 조회하지 않으며 계좌 복호화 키가 필요 없다. 자세한 규격과 수동 확인 절차는 [보험용 계좌 조회](docs/insurance-salary-account-api.md)를 참고한다.


farm-demo의 /demo/insurance-salary-account에서 근로자 본인의 보험 제공용 등록 여부·은행 코드를 확인한다. 기존 계좌 관리 창에서 등록·교체·해제 후 다시 조회해 변화를 볼 수 있다. 서버 토큰을 브라우저에 전달하지 않고 같은 조회 서비스를 사용하며, 실제 Bearer 인증은 별도 검증 대상이다.

## 고용주의 개인별 근무 완료

`POST /api/work-assignments/{assignmentId}/completion`은 고용주 본인 공고의 확정 근무를 약정 종료 시각 이후에 완료한다. 최초 처리 시각을 보존하며 재요청은 같은 결과를 반환하고, 완료 취소·정정은 제공하지 않는다. 보험 상세와 farm-demo 본인 약정 상세에 `completedAt`을 추가했다. V11은 기존 행을 NULL로 보존한다. 요청·응답과 미검증 범위는 [근무 완료 API 문서](docs/work-completion-api.md)를 참고한다.
## APP-02 여러 날짜 지원

POST /api/job-postings/{jobPostingId}/applications는 근로자 세션·CSRF와 UUID v4 Idempotency-Key를 사용해 1~100개 날짜를 한 트랜잭션으로 접수한다. 신규는 201, 같은 성공 요청 재전송은 최초 결과와 200을 반환한다. 조건 버전 불일치·유효 중복 지원은 전체 409다. V12는 재지원 회차·현재 회차와 성공 요청 보관 구조를 추가하고 기존 이력을 보존한다. 요청·응답·집계 변경·미검증 범위는 [APP-02 API](docs/app-02-job-application-api.md)를 참고한다.
farm-demo의 /demo/job-apply에서 날짜 선택·지원·같은 요청 재전송·조건 버전 충돌·재지원 회차를 확인할 수 있다. CSRF·UUID v4 키·조건 버전은 자동 처리하고 실제 지원 API와 본인 이력 GET을 호출한다. 버튼으로 새 시연 공고와 미선정 1회차를 준비할 수 있으며 기존 데이터를 초기화하지 않는다. 자세한 순서는 APP-02 문서의 farm-demo 절을 참고한다.
## APP-04 근로자 본인 지원 내역

`GET /api/me/applications`, `GET /api/me/applications/{applicationId}`,
`GET /api/me/applications/{applicationId}/history`를 추가했다.
현재 회차 목록·상태 필터·커서와 과거 회차 조회, 지원 당시/최신/확정 약정 조건의 구분을 제공한다.
모르는 과거 조건은 최신 값으로 채우지 않으며 조회로 동의·취소 상태를 바꾸지 않는다.
요청·응답과 기존 데이터의 시각·조건 이력 한계는 [APP-04 문서](docs/app-04-my-applications-api.md)를 참고한다.
새 DB 마이그레이션과 환경변수는 없고, 테스트 실행과 실제 DB 검증은 아직 하지 않았다.

farm-demo의 `/demo/my-applications`에서 근로자 본인 목록·필터·조건 비교·과거 회차를 확인할 수 있다.
로그인 메뉴와 지원 화면에서 이동하며 조회는 정식 APP-04 GET을 사용한다. 화면의 상태별 시연 데이터 준비 POST는 CSRF를 자동 처리한다.

### farm-demo 시연 데이터 준비 원칙

기능을 추가할 때 앞선 기능을 순서대로 수행하지 않아도 확인할 수 있는 가상 데이터 준비 기능을 함께 제공한다.
생성은 farm-demo의 명시적인 준비 버튼으로만 수행하고, 준비된 사례·ID·재사용 여부를 화면에 표시한다.
기존 업무 이력을 초기화하지 않고, 실제 API 조회 결과와 시연 데이터 생성 자체를 검증 결과로 혼동하지 않는다.
APP-04는 상태별 준비 버튼 한 번으로 현재 회차 9건·과거 회차 1건과 조건 비교·약정 사례를 확인할 수 있다.
