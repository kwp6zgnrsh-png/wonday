# APP-04 내 지원 목록·상세·과거 회차 조회

## 공통

- 농업 근로자 로그인 세션 `FARMSESSION`을 사용한다. workerId는 요청값이 아니라 principal에서 가져오고 현재 DB 역할도 확인한다.
- GET 조회이므로 CSRF 헤더는 필요 없다. 기존 로그인과 쓰기 API의 CSRF 정책은 변경하지 않는다.
- 성공 및 컨트롤러 오류 응답에 `Cache-Control: no-store`를 적용한다.
- 날짜는 YYYY-MM-DD, 기준을 아는 시각은 오프셋 ISO 8601(+09:00)을 사용한다.
- 공고/일정/지원/약정/변경/조건 이력을 한 REPEATABLE_READ 읽기 트랜잭션으로 조회한다. 다음 페이지 요청까지 한 스냅샷을 유지하는 것은 아니다.
- 신규 마이그레이션·테이블·환경변수는 없다. 기존 적용 마이그레이션과 저장 데이터는 수정하지 않는다.

## 1. 현재 회차 목록

```http
GET /api/me/applications?status=PENDING&limit=20
```

- status 기본 ALL. 허용: ALL, PENDING, CONFIRMED, NOT_SELECTED, CANCELLED, EARLY_ENDED, COMPLETED.
- limit 기본 20, 1~100. 날짜별 지원 한 건을 한 항목으로 센다.
- 지원 시각 내림차순, 같은 시각이면 지원 ID 내림차순. 현재 회차만 반환한다.
- 화면의 근무일·제목은 최신 모집값이다. 지원 당시 날짜는 상세의 appliedConditions에서 확인한다.
- 재지원하면 새 회차의 지원 시각으로 정렬한다. 상태 변경과 같은 키 재전송은 지원 시각을 바꾸지 않는다.

```json
{
  "items": [{
    "applicationId": 501,
    "applicationRound": 2,
    "current": true,
    "jobPostingId": 101,
    "title": "사과 수확 작업",
    "workScheduleId": 201,
    "workDate": "2026-12-10",
    "appliedAt": "2026-12-01T15:00:00+09:00",
    "legacyAppliedAt": null,
    "appliedTimeBasis": "UTC",
    "applicationStatus": "PENDING",
    "displayStatus": "PENDING",
    "cancellationType": null,
    "assignmentId": null,
    "completedAt": null,
    "appliedTermsVersion": 3,
    "latestTermsVersion": 4,
    "conditionComparisonStatus": "CHANGED"
  }],
  "nextCursor": "서버가 반환한 커서",
  "hasMore": true
}
```

다음 요청은 같은 status와 직전 nextCursor를 사용한다. 필터를 바꾸면 cursor를 생략한다.
더 없으면 nextCursor=null, hasMore=false이며, 결과가 없으면 items=[]로 200을 반환한다.

### 커서 검증과 변경 중 조회

커서는 계정·LIST/HISTORY·일정·필터·마지막 지원 ID를 담은 버전 지정 Base64URL 페이지 위치다.
클라이언트는 내용을 해석/생성하지 않고 그대로 전달한다. 서명된 인증 토큰이나 보험 변경 목록의 영속 커서는 아니다.
서버는 형식과 조회 범위를 확인하고, 앵커 행을 다시 본인 worker_id로 조회한다. 커서가 소유자 검사나 권한을 대신하지 않는다.

다른 계정·다른 필터·다른 일정 이력의 커서, 형식 오류나 없는/타인 앵커는 400 INVALID_CURSOR다.
목록 앵커가 이후 재지원으로 과거 회차가 되어도 경계 시각은 유지한다.
여러 요청 사이 상태가 바뀔 수 있으므로 고정된 목록 전체 스냅샷은 보장하지 않는다. 신규 지원은 목록 새로고침으로 확인한다.

### 기존 지원 시각

V12 이전 행은 applied_at_utc가 없을 수 있다. 이를 최신 공고 시간대나 서버 시간대로 추측해서 바꾸지 않는다.
이 경우 appliedAt=null, legacyAppliedAt=원본 현지 시각, appliedTimeBasis=LEGACY_LOCAL_UNKNOWN_ZONE을 반환한다.
정렬 경계는 UTC 값이 있으면 applied_at_utc, 없으면 원본 applied_at을 사용한다.
따라서 시간대가 없는 과거 행과 UTC 행 사이의 절대 시각 순서까지 복원했다고 볼 수 없다. 원본 데이터 보완은 별도 작업이다.

## 2. 상태 필터

저장된 applicationStatus와 조회용 displayStatus를 분리한다. 상태를 갱신하는 API가 아니다.

| 표시 | 판정 |
| --- | --- |
| CANCELLED / ASSIGNMENT_CANCELLED | 해당 확정 근무에 CANCELLED 기록 |
| EARLY_ENDED | 해당 확정 근무에 EARLY_ENDED 기록 |
| COMPLETED | 변경 기록이 없고 completed_at_utc가 저장됨 |
| CONFIRMED | 개인별 확정 근무가 있으며 위 종료 기록 없음 |
| CANCELLED / SCHEDULE_CANCELLED | 확정 근무가 없고 모집 날짜가 REMOVED |
| PENDING | 위 경우가 아니고 지원 상태 PENDING |
| NOT_SELECTED | 위 경우가 아니고 지원 상태 NOT_SELECTED |

예정 종료 시각만 지났다고 완료로 바꾸지 않는다. 모집 취소는 지원을 미선정으로 덮어쓰지 않는다.
SELECTED이지만 확정 근무가 없는 등 분류할 수 없는 기존 데이터는 UNKNOWN으로 ALL에 표시한다.
이를 정상 확정으로 추측하거나 저장하지 않는다. UNKNOWN 전용 필터는 제공하지 않는다.
과거 미선정 회차는 기본 목록의 NOT_SELECTED에 포함되지 않고 과거 이력으로 확인한다.

## 3. 상세

```http
GET /api/me/applications/501
```

현재·과거 회차 모두 본인 지원 ID로 상세를 조회한다. 없는 ID와 타인 ID는 동일한 404 APPLICATION_NOT_FOUND다.

응답은 applicationId/applicationRound/current/jobPostingId/title/workScheduleId,
applicationStatus/displayStatus/cancellationType, 지원 시각 정보, 버전 정보 및 다음 필드를 포함한다.

- appliedConditions: 지원 당시 조건
- latestConditions: 최신 모집 조건
- changedFields: 실제 비교 가능한 항목 중 값이 다른 필드
- unavailableComparisonFields: 비교할 수 없는 필드
- comparisonComplete: 모든 대상 필드를 비교할 수 있는지
- assignment: 확정 근무가 없으면 null. 있으면 assignmentId, confirmedAt, completedAt, agreedConditions, change

### 조건 표시

Conditions의 availability는 AVAILABLE / PARTIAL / UNAVAILABLE이며 termsVersion을 함께 반환한다.
조건 필드는 workDate, timeZone, workLocation, latitude, longitude, cropCode/cropName,
workTypeCode/workTypeName, description, headcount, dailyWage,
plannedStartAt/plannedEndAt/breakStartAt/breakEndAt, unavailableFields다.

- 지원 당시 버전과 일치하는 work_schedule_revision이 있으면 그 저장값을 사용한다.
- 과거 이력이 없지만 지원 버전이 최신 버전과 같으면 현재 동일 버전 조건을 사용한다.
- 버전이 없거나 다른 버전의 이력을 찾을 수 없으면 UNAVAILABLE로 반환하고 최신 조건으로 채우지 않는다.
- V6 이력에 없는 좌표·작업 내용은 null이며 unavailableFields에 명시한다. 변경 당시 좌표나 내용을 최신 공고에서 복사하지 않는다.
- 양쪽 휴게 시각이 null이고 누락 표시가 없으면 '휴게 없음'이다. 누락/변환 불가와 구분한다.
- 기존 시간대 누락·DST 모호 시각은 오프셋을 추측하지 않고 해당 필드를 unavailableFields로 표시한다.
- 작목·작업 유형 이름은 공통 코드 목록으로 해석한다. 모르는 코드는 원본 코드를 보존하고 이름만 null이다.

conditionComparisonStatus는 같은 버전 UNCHANGED, 다른 버전 CHANGED, 지원 버전 미확인 UNKNOWN이다.
버전이 달라도 일부 값은 같을 수 있다. changedFields=[]만 보고 '조건 동일'이라고 표시하면 안 된다.
comparisonComplete=true일 때만 모든 비교 항목이 동일한지 판정할 수 있다.

### 확정 약정

assignment.agreedConditions는 work_assignment에 저장된 날짜·시간대·작업지·좌표·작목·작업 유형·시간·휴게·일급만 반환한다.
현재 공고의 조건이나 좌표로 보완하지 않는다. 기존 약정 누락은 PARTIAL과 unavailableFields로 표시한다.
완료 시각이 없으면 completedAt=null이다. 취소·조기 종료 후에도 약정 상세를 제공한다.
change가 있으면 유형·사유·기상 사유·설명·실제 적용/최초 기록/수정 시각을 구분한다.

조회는 조건 동의나 재확인 완료가 아니다. 자동 취소·조건 변경 수락·재확인 기한 처리는 추가하지 않는다.

## 4. 과거 회차

```http
GET /api/me/applications/501/history?limit=20
```

기준 지원 501의 소유권을 먼저 확인하고, 같은 근로자·같은 일정의 is_current=0 행만 반환한다.
현재 지원 ID를 기준으로 호출해도 되고 본인의 과거 지원 ID로 호출해도 된다.
최근 회차(applicationRound 내림차순, 같은 회차면 ID 내림차순)이며 기본 20·최대 100의 별도 커서를 사용한다.
필터 파라미터는 제공하지 않는다.

```json
{
  "currentApplicationId": 501,
  "items": [],
  "nextCursor": null,
  "hasMore": false
}
```

items 항목은 목록과 같은 구조이며 current=false다. 과거 항목의 applicationId로 상세를 조회한다.

## 5. 오류

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 양수가 아닌/형식이 틀린 ID, 잘못된 필터·limit, 알려진 파라미터 중복 |
| 400 | INVALID_CURSOR | 커서 형식·계정·조회 종류·일정·필터·앵커 오류 |
| 401 | 기존 세션 인증 처리 | 비로그인 |
| 403 | WORKER_ROLE_REQUIRED | 고용주 또는 로그인 후 DB 역할 변경 |
| 404 | APPLICATION_NOT_FOUND | 지원 없음 또는 타인의 지원 |
| 503 | SERVICE_UNAVAILABLE | DB/트랜잭션 조회 불가 |
| 500 | INTERNAL_SERVER_ERROR | 그 밖의 서버 오류 |

## 구현 및 확인 범위

이번 작업은 조회 API와 권한·문서 및 farm-demo 간이 화면을 추가한다. user-web은 변경하지 않는다.
기존 APP-02 demo 오류 보완은 병합된 main의 코드를 그대로 사용한다.
테스트 실행·서버 실행·실제 DB 조회·마이그레이션 적용·커밋·푸시는 하지 않는다.
compileJava·compileTestJava --offline --no-daemon으로 소스 컴파일은 통과했다. 테스트를 실행한 결과는 아니며, 실제 SQL/projection/커서/조건 비교/보안 체인은 독립 검증 대상이다.

## farm-demo 내 지원 화면

- 접속: `/demo/my-applications`. HTML은 farm-demo 프로필에서만 제공한다.
- 로그인 화면의 근로자 메뉴 또는 기존 `/demo/job-apply`의 링크에서 이동한다.
- HTML 자체는 로그인 전 열 수 있으나, 실제 데이터는 `/api/auth/me`와 근로자 전용 APP-04 API로 조회한다.
- 새 demo 데이터 조회 API는 만들지 않는다. 본인 목록·상세·과거 회차는 정식 APP-04 GET을 호출한다.
- 상태 7종 필터, 조회 개수 1~100, 다음 페이지 커서를 제공한다. 필터나 개수를 바꾸면 이전 커서는 버린다.
- 목록은 현재 회차만 표시한다. 상세와 과거 이력은 각각 조회하며 한 영역의 조회 장애가 나머지 정상 영역을 지우지 않는다.
- 상세 비교는 변경 항목을 강조한다. 일부 과거 필드가 없으면 '확인 불가'와 비교 불가 항목을 표시한다.
- 조건 버전이 달라도 실제 비교 항목이 같을 수 있다. 전체 비교가 가능할 때만 동일하다고 안내한다.
- 확정 약정과 완료 일시, 개인별 취소/조기 종료 기록은 별도 표시한다. 취소된 근무도 저장된 약정을 보여준다.
- 조회는 GET만 사용한다. 별도의 시연 데이터 준비 버튼만 근로자 세션·CSRF를 사용하는 POST를 호출하며 자동 실행하지 않는다.
- 세션·계정은 요청 전후 확인하고 계정 변경·로그아웃·권한 오류에는 이전 조회 결과와 커서를 지운다.
- pagehide에 요청을 중단하고 데이터는 초기화한다. localStorage 등에 지원 내역을 보관하지 않는다.
- 공고·사유·오류 메시지는 textContent로 표시하며 원문 HTML을 삽입하지 않는다.

### 직접 확인 순서

1. farm-demo 서버를 재시작하고 근로자로 로그인한다. 로그인 메뉴에서 '내 지원 내역 · 조건 비교 · 과거 회차'로 이동한다.
2. 현재 지원 회차 목록과 실제 HTTP 200을 확인한다. 지원 이력이 없으면 정상 빈 목록이다.
3. 이력이 필요하면 화면의 'APP-04 상태별 시연 데이터 준비'를 누른다. 앞선 지원·선정 과정을 직접 거치지 않아도 사례별 상세·과거 회차를 바로 열 수 있다.
4. 지원 대기·미선정·취소·조기 종료·완료 필터를 바꾸고 '처음부터 조회'한다. 현재 회차에 해당하는 데이터만 표시되며, 없는 상태는 빈 목록이다.
5. 현재 지원이 여러 개라면 조회 개수를 1로 바꾸고 다음 페이지를 확인한다. 필터를 바꾸면 다음 페이지 버튼이 초기화된다.
6. 목록의 상세를 눌러 지원 당시/최신 조건과 접수·최신 버전을 확인한다.
7. 조건 변경은 별도 세션의 고용주가 기존 일정 수정 API/화면으로 실제 수정한 뒤 이 화면을 다시 조회한다. 변경된 시간 등을 강조하고 과거 이력에 없는 작업 내용·좌표는 확인 불가로 표시해야 한다.
8. APP-02 시연 공고의 셋째 날짜에 재지원했다면 목록에는 새 회차만 나온다. 그 행의 과거 회차를 누르면 기존 미선정 회차를 확인하고 상세를 열 수 있다.
9. 연결된 확정 근무가 있으면 저장된 약정과 완료 일시를 확인한다. 기록이 없으면 현재 공고로 약정을 만들거나 완료된 것으로 표시하지 않는다.
10. 본인 지원 ID 직접 입력으로 상세/이력을 조회할 수 있다. 없는 ID·다른 근로자의 ID는 같은 404이며, 오류는 화면에 실제 상태 코드와 메시지로 표시한다.

화면의 실제 브라우저 조작·세션/보안 체인·SQL 실행은 이번에 수행하지 않았다.

## 상태별 시연 데이터 준비

POST `/demo/api/my-applications/fixtures`는 farm-demo 전용이다.
요청 본문 없이 로그인한 근로자 본인 ID를 사용하며 기존 CSRF 검사를 유지한다.
화면은 토큰을 자동 조회하여 POST 헤더로만 사용하고, 토큰 원문을 응답 진단창/로그에 표시하지 않는다.

### 준비되는 사례

새 가상 공고 하나에 아래 현재 회차 9건과 재지원 과거 회차 1건을 저장한다.

| 사례 | 준비 내용 |
| --- | --- |
| 지원 대기 | PENDING 1회차 |
| 지원 후 조건 변경 | 지원 당시 v1과 변경 이력 보존 후 일정 v2 (09~17 → 10~18) |
| 미선정 | NOT_SELECTED 현재 회차 |
| 모집 취소 | 지원 이력을 남긴 REMOVED 일정 |
| 근무 확정 | SELECTED와 개인별 약정 연결 |
| 확정 후 취소 | 새 확정 근무의 CANCELLED 및 사유 |
| 조기 종료 | 과거 날짜의 새 확정 근무와 EARLY_ENDED 및 기상 사유 |
| 근무 완료 | 과거 날짜의 새 확정 근무를 기존 완료 서비스로 완료 |
| 재지원 현재 회차 | PENDING 2회차, 과거 NOT_SELECTED 1회차 보존 |

작업지·좌표·작목·작업 유형·시간·휴게·일급은 가상 값이며 제목과 사유에 APP-04 시연임을 표시한다.
이 준비 경로는 조회 시연을 위한 저장 도구다. 사용자 선정·재지원 API의 전체 요청 흐름을 검증한 결과는 아니다.

### 저장과 재사용

- 근로자별 APP-02 요청 잠금을 함께 사용해 연타·동시 준비를 직렬화한다.
- 같은 근로자·GMT+9 기준 같은 날짜의 준비는 기존 가상 공고를 반환한다. 새 준비 201, 재사용 200이다.
- 재사용할 때 수정·취소한 상태를 초기화하지 않는다. 다음 날짜 또는 다른 근로자 계정은 별도의 가상 공고를 준비한다.
- 새 공고·일정·지원·약정·이력 생성은 한 트랜잭션이다. 도중 실패하면 해당 준비 작업을 롤백하도록 구성한다.
- JPA Repository의 demo 전용 INSERT는 서버가 만든 공고 ID·고용주 ID·시연 제목으로 범위를 제한한다.
- 새로운 가상 근무의 취소·조기 종료는 WorkChangeRecorder를 사용해 현재 기록과 보험용 불변 버전을 함께 만든다.
- 새로운 가상 근무의 완료는 WorkCompletionService를 사용하며 요청을 처리한 서버 시각을 보관한다.
- 기존 업무 이력의 삭제·덮어쓰기나 적용된 마이그레이션 변경은 하지 않는다.
- 서버 시작/페이지 조회에서는 생성하지 않는다. 실제 DB 저장은 사용자가 준비 버튼을 누를 때만 실행한다.

응답 scenarios는 실제 저장된 지원 ID·일정 ID·확정 근무 ID·회차·현재 여부·사례 이름을 반환한다.
화면의 사례 표에서 상세/과거 회차를 직접 조회할 수 있으므로 전체 목록의 다른 데이터와 섞여도 찾을 수 있다.
상태별 필터는 정식 APP-04 API를 사용한다. 새 fixture가 아닌 기존 내역은 그대로 유지된다.

데이터 준비 코드 및 실제 버튼은 이번 작업 중 실행하지 않았다.

시연 준비 기능 추가 후 compileJava·compileTestJava와 화면 스크립트 문법 확인은 통과했다. 테스트 실행·실제 준비 POST·개발 DB 접근·커밋·푸시는 하지 않았다.
