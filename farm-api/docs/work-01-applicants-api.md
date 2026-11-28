# WORK-01 고용주 날짜별 지원자 조회

## 범위와 권한

- 농업 고용주 세션(FARMSESSION)과 현재 DB 역할을 확인한다.
- 공고 소유자와 일정·지원의 소속을 저장소 쿼리에도 적용한다.
- OPEN/PAUSED/CLOSED/REMOVED 및 지난 근무일 모두 조회할 수 있다.
- 현재 회차 목록·상세·전체 연락처만 제공한다. 고용주용 과거 회차 이력 API와 선정 쓰기는 후속 범위다.
- 응답은 Cache-Control: no-store. GET에는 CSRF가 필요 없으며 기존 쓰기 CSRF는 유지한다.
- 새 마이그레이션·테이블·설정 키는 없다. 적용된 마이그레이션은 변경하지 않는다.

## API

```http
GET /api/job-postings/{jobPostingId}/schedules/{workScheduleId}/applications?status=PENDING&limit=20
GET /api/job-postings/{jobPostingId}/schedules/{workScheduleId}/applications/{applicationId}
GET /api/job-postings/{jobPostingId}/schedules/{workScheduleId}/applications/{applicationId}/contact
```

### 목록

- status: 생략 PENDING. 허용 PENDING / ALL / SELECTED / NOT_SELECTED.
- 기본 20명, limit 1~100. 현재 회차(is_current=1)만 반환한다.
- 지원 시각 오름차순, 같은 시각이면 지원 ID 오름차순. 재지원은 새 현재 회차 시각을 사용한다.
- 정원·확정 인원·남은 자리는 필터와 관계없는 해당 일정 전체 집계다.
- 실제 work_assignment 생성 수에서 CANCELLED를 제외한다. EARLY_ENDED·완료 근무는 자리를 비운 것으로 세지 않는다.
- items=[]도 200이다. 더 없으면 nextCursor=null, hasMore=false.
- 각 페이지는 REPEATABLE_READ 읽기 트랜잭션에서 조회하며 여러 요청 사이까지 스냅샷을 고정하지 않는다.

```json
{
  "jobPostingId": 101,
  "workScheduleId": 201,
  "workDate": "2026-12-10",
  "recruitmentStatus": "OPEN",
  "headcount": 3,
  "confirmedCount": 1,
  "remainingSlots": 2,
  "items": [{
    "applicationId": 501,
    "workerId": 123,
    "name": "시연 근로자",
    "maskedPhone": "010-****-1001",
    "phoneState": "AVAILABLE",
    "applicationRound": 1,
    "status": "PENDING",
    "appliedAt": "2026-11-28T09:00:00+09:00",
    "legacyAppliedAt": null,
    "appliedTimeBasis": "UTC",
    "appliedTermsVersion": 1,
    "latestTermsVersion": 2,
    "conditionComparisonStatus": "CHANGED",
    "canViewContact": true,
    "assignmentId": null,
    "workStatus": "PENDING"
  }],
  "nextCursor": null,
  "hasMore": false
}
```

목록과 상세는 이름 전체와 마스킹 번호만 반환한다.
status는 지원 상태이며 workStatus는 확정/취소/조기 종료/완료를 설명하는 별도 값이다.
마감·취소·과거 날짜라는 이유로 지원 상태를 변경하지 않는다.

### 커서와 기존 시각

cursor는 서버 응답 값을 같은 공고·일정·필터로 전달한다. 조건 변경 시 처음부터 조회한다.
고용주·공고·일정·필터·앵커 ID를 묶는 버전 지정 Base64URL이며 인증 토큰이나 서명된 허가증이 아니다.
서버는 앵커를 본인 공고/일정 범위로 다시 조회한다. 앵커가 이후 과거 회차로 바뀌어도 원래 시각 경계는 유지한다.

정렬은 applied_at_utc가 있으면 그 값, 없으면 원본 applied_at을 쓴다.
UTC 기준이 없는 기존 행은 appliedAt=null, legacyAppliedAt=원본, appliedTimeBasis=LEGACY_LOCAL_UNKNOWN_ZONE이다.
서로 다른 기준의 기존 시각 사이 절대 순서가 복원됐다고 보지 않으며 현재 공고 시간대로 과거 시각을 추측하지 않는다.

### 상세와 조건 변경

현재 회차만 조회한다. 과거 회차 상세는 404 APPLICATION_NOT_FOUND이며 과거 이력 API는 추가하지 않는다.
APP-04 ApplicationConditionMapper를 재사용해 지원 당시 조건·최신 조건·확정 약정을 같은 기준으로 비교한다.

- conditionComparisonStatus: UNCHANGED / CHANGED / UNKNOWN
- appliedConditions, latestConditions, changedFields, unavailableComparisonFields, comparisonComplete
- assignment: 저장된 확정 약정과 변경·완료 시각. 없으면 null
- V6 이력에 없는 좌표·작업 내용은 null과 확인 불가 표시. 최신 값으로 채우지 않는다.
- 일부 필드가 확인 불가면 changedFields=[]만으로 동일 조건이라고 판단하지 않는다.
- 조회나 연락처 열람은 근로자의 변경 동의가 아니다. 선정·재확인·수락 처리도 하지 않는다.

### 전체 연락처

연락처 API는 요청 시 현재 회차·PENDING/SELECTED·소유자를 다시 확인한다.
READ_COMMITTED 조회로 목록 때의 허용 여부를 신뢰하지 않고 최신 회원정보를 읽는다.
미선정과 과거 회차는 403 CONTACT_ACCESS_DENIED다.

```json
{
  "applicationId": 501,
  "workerId": 123,
  "name": "시연 근로자",
  "phone": "01000001001",
  "phoneState": "AVAILABLE"
}
```

- 이름/번호는 최신 회원정보 기준. 지원 당시 번호 복사본은 새로 저장하지 않는다.
- 하이픈·공백 제거 후 숫자 10~11자리만 정상 번호로 제공한다.
- phoneState: AVAILABLE / NOT_REGISTERED / INVALID_FORMAT.
- 미등록·잘못된 기존 번호는 phone=null. 가상 번호로 보완하지 않는다.
- 목록은 앞 3자리·끝 4자리만 남기고 나머지를 가린다(11자리: 010-****-1001, 10자리: 앞3-***-끝4).
- canViewContact는 현재 회차와 지원 상태에 따른 권한이다. 번호 미등록 여부는 phoneState로 구분한다.
- 주민등록번호·식별번호·HMAC·계좌 정보는 응답에 포함하지 않는다.

종료된 근무의 연락처 제공 종료 시점은 정산·분쟁 기능과 함께 결정하기로 보류했다.
이번 조회 구현에는 임의의 30일 자동 마스킹, 자동 파기, 법정보존 기간 자동 판단을 넣지 않는다.
이는 무기한 연락처 제공의 적법성을 확정한 것이 아니며, 실제 서비스의 보존/제공 정책과 안내·동의는 별도 정리 대상이다.
현재 선정 상태인 종료 근무도 위 현재 회차/지원 상태 규칙을 적용하고, 종료 시각으로 새 제한을 추측하지 않는다.

## 오류

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | ID·필터·limit·알려진 파라미터 중복 |
| 400 | INVALID_CURSOR | 커서 형식·범위·앵커 오류 |
| 401 | 기존 세션 인증 처리 | 비로그인 |
| 403 | EMPLOYER_ROLE_REQUIRED | 고용주가 아님·DB 역할 변경 |
| 403 | CONTACT_ACCESS_DENIED | 과거 회차·미선정 등 전체 연락처 대상 아님 |
| 404 | JOB_POSTING_NOT_FOUND | 공고 없음 또는 타인 공고 |
| 404 | WORK_SCHEDULE_NOT_FOUND | 해당 공고의 일정 아님 |
| 404 | APPLICATION_NOT_FOUND | 해당 날짜 지원 없음·상세의 과거 회차 |
| 503 | SERVICE_UNAVAILABLE | DB·트랜잭션 조회 불가 |
| 500 | INTERNAL_SERVER_ERROR | 그 밖의 오류 |

## farm-demo 및 즉시 시연 데이터

- 화면: /demo/work-applicants. 로그인 메뉴의 고용주 전용 링크에서 이동.
- 준비: POST /demo/api/work-applicants/fixtures. farm-demo 전용, 고용주 세션·CSRF 사용.
- 페이지 조회에서는 저장하지 않는다. 준비 버튼만 POST를 실행하고 토큰·쿠키·전체 번호를 진단창에 남기지 않는다.
- 같은 고용주·GMT+9 기준 같은 날은 기존 데이터를 재사용(200), 신규 준비는 201.
- 기존 회원 이름·전화번호·비밀번호·업무 이력을 수정/삭제하지 않는다.
- 새 가상 근로자 5명(4명 가상 번호, 1명 미등록), 본인 소유 가상 공고, 일정 4개, 지원 9건(현재 8·과거 1), 확정 약정 1건을 준비한다.
- 새 가상 회원은 무작위 비밀번호의 BCrypt 해시만 저장하며 로그인 비밀번호·키는 반환하지 않는다.
- 첫 일정: 현재 대기 3명·선정 1명·미선정 1명, 과거 재지원 회차 1건. 정원3·확정1·남은2.
- 대기 중 한 명은 v1 지원 후 v2 변경, 다른 지원은 v2. 동일 접수 시각의 지원도 포함한다.
- 나머지 일정은 PAUSED, 지난 날짜 CLOSED, REMOVED이며 각각 지원 이력을 준비한다.
- 과거 회차는 준비 결과의 사례 ID로만 확인하며 실제 상세 404·연락처 403을 확인할 수 있다.
- 시연 준비는 실제 선정/중단/재개 쓰기 API를 구현한 것이 아니다. 상태 변경은 준비 트랜잭션의 새 일정에만 제한한다.
- 고용주 회원 행을 잠가 같은 고용주의 중복 준비를 직렬화한다. 전체 생성은 한 트랜잭션이며 실패 시 해당 생성 작업을 롤백하도록 구성한다.

### 직접 확인

1. farm-demo 재시작 → 고용주 로그인 → 날짜별 지원자 화면.
2. WORK-01 지원자 시연 데이터 준비를 누른다. 첫 날짜의 지원 대기 3명을 확인한다.
3. ALL/SELECTED/NOT_SELECTED 필터와 limit=1 다음 페이지를 확인한다.
4. 조건 변경 사례의 상세에서 지원 당시·최신 시간과 확인 불가 항목을 확인한다.
5. 대기·선정의 연락처와 미선정/과거의 403, 미등록 번호의 null 응답을 확인한다.
6. 날짜 선택에서 PAUSED/CLOSED/REMOVED를 선택해 조회한다.
7. 타인 공고·다른 날짜의 지원 ID를 직접 입력해 404를 확인한다.
8. 같은 날 준비 버튼 재요청은 이전 변경을 초기화하지 않고 재사용한다.

## 검증 범위

Java 21 환경에서 `compileJava compileTestJava --offline --no-daemon` 컴파일을 통과했다.
신규 지원자 화면과 변경된 로그인 화면의 스크립트는 `node --check` 문법 확인을 통과했다.
테스트·서버·실제 DB 조회/생성·마이그레이션 적용·커밋·푸시는 실행하지 않았다.
실제 SQL·보안 체인·동시성·롤백 및 브라우저 동작은 독립 검증 대상이다.
