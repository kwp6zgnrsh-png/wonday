# WORK-02·03·04 개인별 선정·약정·조건 재확인

## 작업 기준

- WORK-01 추가 수정 커밋 `842ef8b`를 포함하는 `work-selection` 브랜치다.
- WORK-01 검증·추가 푸시 이후 선정 작업을 원래 `C:\Users\HCB\Desktop\wonday`로 이동했다. 선정 변경은 미커밋 상태다.
- 선정은 한 명씩 한다. 모집 마감·일시중단/재개 쓰기·확정 후 조건 제안·자동 취소는 추가하지 않는다.
- 가입·보험·급여 계좌와 기존 로그인·FARMSESSION·CSRF 구조는 유지한다.

## 선정

```http
POST /api/job-postings/{jobPostingId}/schedules/{workScheduleId}/applications/{applicationId}/selection
Content-Type: application/json
X-CSRF-TOKEN: 발급받은토큰
```

```json
{"termsVersion": 3}
```

- 고용주 본인 공고·일정에 속한 지원을 검사한다. 타인 공고는 404다.
- 새 선정은 현재 회차 PENDING, OPEN/PAUSED, 예정 시작 전이어야 한다.
- 고용주가 본 termsVersion과 최신 버전이 다르면 409 TERMS_VERSION_CONFLICT.
- 최초 지원 버전이 최신이거나, 해당 지원에 최신 버전 재확인 기록이 있어야 한다.
- 미확인은 409 CONDITION_RECONFIRMATION_REQUIRED. 지원은 PENDING으로 유지한다.
- 좌표 없는 공고는 409 WORK_LOCATION_INCOMPLETE. 기존 좌표를 0이나 현재의 다른 값으로 보완하지 않는다.
- 정원은 해당 일정의 실제 assignment 집계(CANCELLED 제외)로 확인한다.
- 다른 회차에 유효한 확정 근무가 있으면 새 확정을 막는다.
- 공고 → 일정 → 지원 순서로 잠그고, READ_COMMITTED에서 정원 검사·SELECTED 변경·약정 INSERT를 한 트랜잭션으로 처리한다.
- 기존 지원 INSERT 및 일정 수정과 공고 → 일정 잠금 순서를 맞춘다.
- 근로자 역할도 현재 회원정보로 확인하며 클라이언트에서 workerId·고용주 ID·시각을 받지 않는다.

신규 201, 정상 재요청 200:

```json
{
  "applicationId": 501,
  "workScheduleId": 201,
  "workerId": 123,
  "status": "SELECTED",
  "assignmentId": 7001,
  "agreedTermsVersion": 3,
  "confirmedAt": "2026-11-28T15:00:00+09:00",
  "workStatus": "CONFIRMED",
  "completedAt": null
}
```

- 정상 재요청은 역할·소유권·소속 검사 후 기존 assignment를 반환한다. 현재 모집 상태·시작 경계·버전을 새 선정처럼 다시 적용하지 않는다.
- 원래 약정·확정 시각을 갱신하거나 정원을 추가 사용하지 않는다.
- CANCELLED는 409 WORK_ASSIGNMENT_CANCELLED.
- EARLY_ENDED / COMPLETED도 기존 assignment와 해당 workStatus를 200으로 반환한다.
- 과거에 이미 저장된 assignment의 버전·UTC 시각이 없으면 null이며 현재 버전/현재 시각으로 채우지 않는다.
- 실제 약정은 WORK-01 지원자 상세의 assignment와 기존 보험용 상세로 확인한다.

## 근로자 조건 재확인

```http
POST /api/me/applications/{applicationId}/condition-confirmation
Content-Type: application/json
X-CSRF-TOKEN: 발급받은토큰
```

```json
{"termsVersion": 3}
```

- 근로자 본인의 현재 회차 PENDING만 허용한다.
- OPEN/PAUSED이고 예정 시작 전이어야 한다. 종료/선정된 지원에 재확인을 추가하지 않는다.
- 오래된 버전은 이전 확인 기록이 있어도 409다.
- 같은 최신 버전 재요청은 최초 ID·확인 시각·조건 원문을 유지한다.
- 최초 지원 버전이 최신이면 새 재확인 행 없이 200이다.
- 단순 조회는 확인으로 처리하지 않으며, 재확인은 선정이나 자리 확보가 아니다.
- 지원 당시 버전·접수 시각·회차는 변경하지 않는다.

항상 정상 처리 200:

```json
{
  "applicationId": 501,
  "applicationRound": 1,
  "status": "PENDING",
  "conditionConfirmation": {
    "source": "RECONFIRMATION",
    "confirmedTermsVersion": 3,
    "latestTermsConfirmed": true,
    "acknowledgementId": 9001,
    "acknowledgedTermsVersion": 3,
    "acknowledgedAt": "2026-11-28T14:00:00+09:00"
  }
}
```

최초 지원으로 확인된 경우 source=INITIAL_APPLICATION, confirmedTermsVersion=최초 지원 버전,
latestTermsConfirmed=true, acknowledgementId/acknowledgedTermsVersion/acknowledgedAt=null이다.
원래 접수 시각은 기존 appliedAt에서 확인하며 가상의 재확인 시각을 만들지 않는다.

## APP-04·WORK-01 표시

목록·상세(근로자 과거 회차 조회 포함)에 conditionConfirmation을 추가한다.

- source: INITIAL_APPLICATION / RECONFIRMATION / NONE
- confirmedTermsVersion: 확인 근거가 된 버전. 과거 값일 수 있다.
- latestTermsConfirmed: 그 근거가 현재 최신 버전을 충족하는지 여부
- acknowledgementId / acknowledgedTermsVersion / acknowledgedAt: 마지막 명시적 재확인 기록
- 최초 지원과 최신의 비교(CHANGED/UNCHANGED/UNKNOWN)는 기존 의미를 유지한다.
- CHANGED이면서 latestTermsConfirmed=true일 수 있다. 최초 지원 후 변경됐지만 최신 조건은 재확인했다는 뜻이다.
- 다른 회차의 확인 기록은 가져오지 않는다.
- 기존 farm-demo 내 지원·고용주 지원자 목록/상세에도 확인 상태·재확인 시각을 표시한다.
- 최초 지원 이력이 없는 과거 지원은 NONE이며 최신 버전을 추측하지 않는다.

## V13 저장 구조

- application_condition_acknowledgement 신규: 지원 ID·버전 UNIQUE, 서버 UTC 최초 시각, 확인한 전체 조건 JSON.
- 조건 JSON은 잠근 일정·공고에서 서버가 생성한다. 요청 조건 원문을 신뢰해 저장하지 않는다.
- 당시 주소·좌표·작업 내용·정원·일급·시간을 포함한 불변 원문을 보관한다.
- V6의 과거 기록에 없던 항목을 최신 값으로 덮어쓰지 않는다. 기존 조건 이력도 수정하지 않는다.
- work_assignment.agreed_terms_version은 신규 확정에서만 기록하며 기존 행은 NULL이다.
- 기존 V1~V12 파일은 변경하지 않았다. V13 적용/실제 이관은 이 작업에서 실행하지 않는다.
- Flyway 통합 테스트의 버전/개수 단언은 V13에 맞추고 새 컬럼 확인을 추가했지만 실행하지 않았다.

## 오류

| HTTP | code | 의미 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 잘못된 ID/JSON/버전. 소수·숫자 문자열 자동 변환은 차단 |
| 401 | 기존 인증 처리 | 비로그인 |
| 403 | EMPLOYER_ROLE_REQUIRED / WORKER_ROLE_REQUIRED | 역할 불일치 |
| 404 | JOB_POSTING_NOT_FOUND / WORK_SCHEDULE_NOT_FOUND / APPLICATION_NOT_FOUND | 없음 또는 접근할 수 없는 소속 |
| 409 | TERMS_VERSION_CONFLICT | 화면에서 본 버전이 최신과 다름 |
| 409 | CONDITION_RECONFIRMATION_REQUIRED | 근로자의 최신 조건 확인 필요 |
| 409 | APPLICATION_NOT_PENDING | 현재 회차 지원 대기 대상 아님 |
| 409 | SELECTION_NOT_ALLOWED | 마감/모집 취소 등 허용 상태 아님 |
| 409 | WORK_ALREADY_STARTED | 서버 시각이 예정 시작 이상 |
| 409 | CAPACITY_FULL / ALREADY_CONFIRMED | 정원 충족 / 기존 유효 근무 있음 |
| 409 | WORK_ASSIGNMENT_CANCELLED | 취소된 근무의 재선정 |
| 409 | WORK_LOCATION_INCOMPLETE / WORK_TERMS_UNAVAILABLE | 기존 모집 조건 보완 필요 |
| 409 | WORK_STATE_CONFLICT / APPLICATION_STATE_CONFLICT | 동시 저장 또는 기존 상태 불일치 |
| 503 | SERVICE_UNAVAILABLE | DB·트랜잭션 처리 불가 |
| 500 | INTERNAL_SERVER_ERROR | 그 밖의 내부 오류 |

JSON·UTF-8·no-store를 사용한다. 기존 CSRF 거부 처리는 유지한다.
`termsVersion`은 JSON 양의 정수만 받으며 필수다.

## 시연 데이터와 확인 범위

farm-demo의 /demo/work-selection에서 양쪽 실제 세션으로 선정·재요청·조건 재확인·409를 확인한다.
전용 준비 버튼으로 같은 고용주·근로자의 상태별 사례 11개를 새 공고에 구성한다.
같은 날 재요청은 기존 상태를 유지하며, 계정 전환 후에는 읽기 전용 사례 조회를 사용한다.
기존 내 지원·지원자 화면에도 확인 상태가 표시된다.
단계별 버튼과 예상 결과는 [선정·재확인 demo 안내](work-selection-demo.md)를 참고한다.

## 검증

- Java 21에서 `compileJava compileTestJava --offline --no-daemon` 컴파일 통과.
- 변경된 APP-04·WORK-01 demo 스크립트의 `node --check` 문법 확인 통과.
- `git diff --check` 통과.
- 단위·통합 테스트, 서버, 실제 DB·V13 적용·시연 생성·커밋·푸시는 실행하지 않는다.
- 실제 SQL/projection, 동시 선정의 정원 보호, 수정/선정/재확인 경합 및 롤백은 후속 독립 검증 대상이다.
