# JOB-06 날짜별 일정 수정 API — 단위 테스트 독립 검증 보고서

- **검증일:** 2026-11-25
- **검증자:** Claude (테스트 작성·실행만 했고 운영 코드는 수정하지 않았다)
- **브랜치:** `job-update`. JOB-06 운영 코드는 아직 커밋되지 않은 작업 트리 상태다.
- **AGENTS.md:** 없음.
- **범위:** JOB-06 단위 테스트, 그리고 이번 변경이 건드린 기존 코드의 단위 테스트만 실행했다.
  - 사용자가 2026-11-25 "JOB-06 + 영향 받은 테스트"를 선택했다. 그래서 전체 단위 스위트는 실행하지 않았다.
  - 전체 프로젝트 커버리지 수치도 보고하지 않는다. 일부만 실행한 결과라 의미가 없다.
- **이번 검증 범위 밖:**
  - 실제 MySQL의 집계·잠금·롤백·마이그레이션
  - HTTP Security 필터·세션·CSRF
  - farm-demo 브라우저 화면의 입력 동작(JavaScript)
- **사용자의 farm-demo 수동 확인:** 작성자가 보고한 내용이다. 이 문서의 자동 테스트 결과에는 포함하지 않았다.

## 요약

| 항목 | 결과 |
|---|---|
| 실행한 테스트 | 229개 = JOB-06 신규 153개 + 영향 받은 기존 76개 |
| 실행 결과 | 229개 모두 통과 · 실패 0 · 제외 0 |
| 결함 | **없음** |
| JOB-06 핵심 클래스 커버리지 | 서비스·검증기·엔티티·오류 처리기·API 모두 라인 100%, 분기 100% |
| 변조 확인 | 운영 코드를 4곳 임시로 변조했고, 테스트가 4곳 모두 잡아냈다. 모두 원본으로 복원했다 |

## 1. 추가·수정한 파일

모두 새로 만든 테스트 파일이다. 기존 테스트, 운영 코드, Gradle·CI 설정은 바꾸지 않았다.

| 파일 | 테스트 수 |
|---|---|
| `src/test/java/com/wonday/farm/job/service/WorkScheduleUpdateServiceTest.java` | 70 |
| `src/test/java/com/wonday/farm/job/dto/UpdateWorkScheduleRequestTest.java` | 47 |
| `src/test/java/com/wonday/farm/job/api/WorkScheduleUpdateApiTest.java` | 25 |
| `src/test/java/com/wonday/farm/job/validation/WorkScheduleValidatorTest.java` | 11 |

영향 받은 기존 테스트(수정 없이 실행만 함):

| 테스트 | 실행한 이유 |
|---|---|
| `JobPostingServiceTest` (49) | 등록 검증 코드가 공용 `WorkScheduleValidator`로 옮겨졌다 |
| `JobPostingQueryServiceTest` (24), `JobPostingQueryServiceMissingCountsTest` (1) | `WorkSchedule`에 `PAUSED` 상태와 새 메서드가 추가됐다 |
| `JobPostingApiTest` (1) | `WorkSchedule.Status`를 사용한다 |
| `AuthDemoPageTest` (1) | `AuthDemoPage`에 `/demo/jobs` 페이지가 추가됐다 |

## 2. 검증 항목과 테스트 연결

**테스트 방식:**
- 서비스 테스트는 저장소만 mock으로 바꿨다. 공고·일정·이력은 실제 도메인 객체를 사용했다.
- Clock은 고정값이다: `2026-11-25T03:04:05.123456789Z`를 마이크로초로 절삭하면 `…05.123456`이 된다.

### [1] 정상 수정과 변경 범위 (`WorkScheduleUpdateServiceTest.SuccessfulUpdate`, `WorkScheduleUpdateApiTest.Delegation`)
- **값 전달:** 세션 principal의 회원 ID(42), 경로의 공고·일정 ID(1, 11), 본문 DTO가 그대로 서비스에 전달된다. 서비스 결과는 JSON으로 그대로 반환된다.
- **필드별 변경:** 날짜, 예정 시작, 예정 종료, 휴게 시작, 휴게 종료를 하나씩 따로 바꿀 수 있다. 다섯 번 수정하면 버전은 6이 된다.
- **휴게 변경:** 휴게를 제거해 null/null로 만들 수 있고, 다시 추가할 수도 있다.
- **다음 날 종료:** 22:00 시작, 다음 날 06:00 종료인 근무를 정상 처리한다. 휴게가 다음 날에 있어도 된다.
- **버전 증가:** 버전은 정확히 1 증가한다(7 → 8).
- **응답:** 응답 레코드 전체가 수정 결과와 같다(ID, 일시, 상태, 버전).
- **유지되는 값:** 일정 ID, 공고 연결(같은 객체), 정원·일급·장소·작목·작업 유형·시간대는 바뀌지 않는다.
- **트랜잭션:** `READ_COMMITTED`이다.

### [2] 역할·소유권·대상 (`RoleOwnershipAndTarget`)
- **역할 확인:** 회원이 없음, WORKER, 로그인 후 DB 역할이 WORKER로 바뀜, `ADMIN` 역할이면 모두 `AccessDeniedException`이다.
  - 이때 공고·일정·이력 저장소를 호출하지 않는다.
  - 일정 조건도 그대로다.
- **ID 검증:** 공고·일정 ID가 0이거나 음수이면 `INVALID_REQUEST`이고 아무것도 조회하지 않는다.
- **조회 순서:** 다음 순서를 `inOrder`로 확인했다.
  1. 회원 조회
  2. `findOwnedForUpdate(공고ID, 호출자ID)`
  3. `findByIdAndJobPosting_Id(일정ID, 공고ID)`
- **대상 없음:**
  - 소유 공고가 없으면 404 `JOB_POSTING_NOT_FOUND`이고, 일정 조회를 하지 않는다.
  - 해당 공고의 일정이 없으면 404 `WORK_SCHEDULE_NOT_FOUND`이다.
- **한계:** mock 저장소 검증은 실제 SQL의 소유자 필터와 공고 연결 필터를 입증하지 않는다.

### [3] 수정 가능한 상태와 확정자 (`EditableStatusAndConfirmedWorkers`)
- **수정 가능 상태:** OPEN, PAUSED, CLOSED는 수정할 수 있고, 수정 후에도 상태가 그대로다. 수정만으로 모집을 재개하지 않는다.
- **REMOVED:** 409 `SCHEDULE_NOT_EDITABLE`이다. 조건과 이력은 바뀌지 않는다.
- **확정자 있음:** 확정 집계가 1, 2, `Long.MAX_VALUE`이면 409 `SCHEDULE_ALREADY_CONFIRMED`이다. 조건과 이력은 바뀌지 않는다.
- **확정자 없음:** 집계가 0이면 수정할 수 있다.
- **집계 대상:** 대상 일정 ID로만 집계를 1회 조회한다. 다른 ID에 5를 줘도 결과에 영향이 없다.
- **한계:** CANCELLED와 EARLY_ENDED를 구분하는 실제 집계는 `countOccupyingAssignments`의 네이티브 SQL에 있다. 이번 테스트는 mock 집계값만 줬으므로 그 SQL은 검증하지 않았다.

### [4] 날짜·근무·휴게 (`DateWorkAndBreakValidation`, `WorkScheduleValidatorTest`)

| 경우 | 결과 |
|---|---|
| 같은 공고의 다른 일정과 날짜 중복 | 400 `DUPLICATE_WORK_DATE`, field `workDate` |
| 근무일 ≠ 예정 시작의 날짜 | `INVALID_WORK_TIME`, field `workDate` |
| 종료 = 시작, 또는 종료가 1µs 빠름 | `INVALID_WORK_TIME`, field `plannedEndAt` |
| 휴게 시작만 누락 | `INVALID_BREAK_TIME`, field `breakStartAt` |
| 휴게 종료만 누락 | `INVALID_BREAK_TIME`, field `breakEndAt` |
| 휴게 종료가 시작과 같거나 빠름 | field `breakEndAt` |
| 휴게 시작이 근무 시작 이전 | field `breakStartAt` |
| 휴게 종료가 근무 종료 이후 | field `breakEndAt` |

- **중복 조회 호출:** `existsByJobPosting_IdAndWorkDateAndIdNot(공고, 날짜, 자기 일정 ID)`로 자기 일정을 제외한다. 날짜를 그대로 둔 수정은 중복이 아니다.
- **허용 경계:** 휴게 시작 = 근무 시작, 휴게 종료 = 근무 종료는 허용된다.
- **정밀도:** 마이크로초(`.000001`, `.999999`)는 그대로 보존한다. 100나노초나 1나노초 정밀도는 `INVALID_LOCAL_TIME`이다.
- **연도 범위:**
  - 허용: 1000-01-01, 9999-12-31T23:59:59.999999
  - 거부(`INVALID_LOCAL_TIME`): 0999년, +10000년
- **저장된 공고 시간대:**
  - null, 빈 값, 공백, `Seoul`, `GMT+99`이면 409 `TIME_ZONE_UNAVAILABLE`이다. 공고의 시간대 값은 바뀌지 않는다(GMT+9로 임의 보완하지 않음).
  - 이미 저장된 유효한 시간대(`Asia/Seoul`, `GMT+09:00`, `UTC`)는 거부하지 않고 수정에 성공한다.
- **검증 실패 시:** 이력을 조회하거나 저장하지 않는다.
- **공용 검증기의 필드 경로:**
  - 8가지 규칙 위반 각각에 대해 등록용 접두어는 `schedules[0].breakEndAt`, `schedules[3].…`처럼 배열 경로를 반환하고, 수정용 접두어 `""`는 `breakEndAt`처럼 단일 필드명을 반환한다.
  - 서머타임 시간대(America/New_York)에 존재하지 않는 시각은 `INVALID_LOCAL_TIME`이다.
  - 영향 받은 기존 `JobPostingServiceTest` 49개도 모두 통과했다. 등록 쪽 필드 경로와 규칙은 바뀌지 않았다.

### [5] 조건 버전과 충돌 (`TermsVersion`)
- **현재 버전:** 요청 버전이 현재 버전과 같으면 정상 수정된다.
- **과거·미래 버전:** 현재 버전이 2일 때 1이나 3을 보내면 409 `SCHEDULE_VERSION_CONFLICT`, field `expectedTermsVersion`이다. 조건과 버전이 바뀌지 않고, 이력 저장소를 호출하지 않는다.
- **동일 조건 + 현재 버전:** 성공하지만 버전이 1 그대로이고 이력 저장소를 호출하지 않는다.
- **동일 조건 + 오래된 버전:** 충돌이다.
- **`Integer.MAX_VALUE` 버전:**
  - 실제로 조건을 바꾸면 409 `SCHEDULE_VERSION_EXHAUSTED`이고 아무것도 바뀌지 않는다.
  - 동일 조건이면 버전 증가 없이 정상 반환한다.
- **두 편집자 충돌(순차 재현):**
  1. A와 B가 버전 1을 읽는다.
  2. B가 수정해 버전이 2가 된다.
  3. A가 버전 1로 요청하면 거부된다. B의 결과는 그대로 남는다.
  4. A가 버전 2로 다시 요청하면 성공하고 버전이 3이 된다.
- **한계:** 이 테스트는 오래된 버전을 거부하는 로직만 검증한다. 실제 동시 트랜잭션이나 잠금 경합을 검증한 것이 아니다.

### [6] 변경 이력 (`History`)
- **첫 실제 수정:** BASELINE과 UPDATED가 순서대로 저장된다.
  - BASELINE: 기존 버전 1, 기존 조건, actorId = null
  - UPDATED: 버전 2, 변경된 조건, actorId = 고용주 ID(10)
- **현재 버전 이력 확인:** `existsByWorkSchedule_IdAndTermsVersion(일정, 현재 버전)`으로 확인한다.
  - 버전 4일 때 4로 조회한다.
  - 이력이 이미 있으면 UPDATED만 추가한다.
- **연속 수정:** BASELINE(1), UPDATED(2), UPDATED(3) 순으로 저장된다. 과거 이력에 대한 수정·삭제 API가 저장소에 없고, 호출도 없다.
- **스냅샷 복사:** 다음 값이 정확히 복사된다.
  - 일정 연결(같은 객체), 조건 버전, 이력 유형, 변경자, 기록 시각
  - 근무일, 예정 시작·종료, 휴게 시작·종료
  - 공고 시간대, 장소, 작목, 작업 유형, 정원, 일급
- **기록 시각:**
  - BASELINE과 UPDATED의 기록 시각은 고정 Clock의 UTC 값을 마이크로초로 절삭한 값으로 같다.
  - Clock을 바꾸면 기록 시각도 따라 바뀐다. 즉 BASELINE 시각은 과거 생성 시각을 추측한 값이 아니라 현재 관측 시각이다.
- **스냅샷 불변:** 일정을 다시 수정해도 이전 스냅샷의 조건은 바뀌지 않는다.
- **이력 저장 없음:** 동일 조건, 버전 충돌, 검증 실패, 상태·확정 거부에서는 이력을 저장하지 않는다(각 Nested 참고).
- **저장 실패:**
  - BASELINE 저장이 실패하면 예외가 전파되고, 일정은 변경되지 않으며, UPDATED 저장도 시도하지 않는다(save 1회).
  - UPDATED 저장이 실패하면 예외가 그대로 전파되어 성공으로 처리되지 않는다.
- **한계:** UPDATED 저장 실패 시 메모리의 일정 객체는 이미 바뀐 상태다. DB 롤백으로 되돌아가는지, 트랜잭션이 원자적인지는 이 테스트로 증명하지 않는다.

### [7] 요청 DTO와 엄격한 JSON (`UpdateWorkScheduleRequestTest`)
- **테스트 환경:** 운영 역직렬화기를 실제로 사용하는 Jackson `JsonMapper`와 Hibernate Validator를 썼다. Spring 컨텍스트는 없다.
- **정상 입력:** 문서의 예시 본문을 읽을 수 있다. 휴게는 선택값이다. 초 생략과 마이크로초를 허용한다. `Integer.MAX_VALUE`를 읽을 수 있다.
- **`expectedTermsVersion` 형식 거부(11종):** 소수, `1.0`, 지수, 숫자 문자열, 빈 문자열, 배열, 객체, true/false, int 범위 초과(양수·음수)
- **`expectedTermsVersion` Bean Validation:** 0, -1, null은 해당 필드의 Bean Validation 위반이다.
- **필수값:** `workDate`, `plannedStartAt`, `plannedEndAt`는 필드 누락이든 명시적 null이든 위반이다.
- **`workDate` 거부(11종):** 배열, 숫자, boolean, 객체, 존재하지 않는 날짜, 13월, 슬래시 구분, 한 자리 일, 앞 공백, 일시 문자열, 빈 문자열
- **일시 네 필드에 같은 거부 계약(16종 × 4필드):**
  - 형식: 배열, 숫자, boolean, 객체
  - 구분자: 소문자 `t`, 공백 구분
  - 시간대 표기: `Z`, `+09:00`, `+0900`, `[Asia/Seoul]`
  - 잘못된 값: `24:00`, `09:60`, 2월 30일, 날짜만, `T`로 끝남, 빈 문자열
- **한계:** 서비스를 `new`로 생성한 테스트에는 `@Valid`가 적용되지 않는다. 그래서 DTO 필수값의 근거는 이 테스트뿐이다.

### [8] 오류 응답 변환 (`WorkScheduleUpdateApiTest`, standalone MockMvc)
- **응답 형식:** 모든 오류 본문의 필드는 정확히 `code`, `message`, `field` 세 개다.

| 오류 | 응답 |
|---|---|
| Bean Validation 위반 | 400 `INVALID_WORK_SCHEDULE`, field = 위반 필드 |
| 읽을 수 없는 본문(빈 값, 잘린 JSON, 배열, 숫자 문자열, 날짜 배열, `Z` 일시) | 400 `INVALID_REQUEST_BODY`, field null |
| 숫자가 아닌 경로 ID | 400 `INVALID_REQUEST_BODY` |
| Content-Type이 `text/plain`이거나 없음 | 400 `INVALID_REQUEST_BODY` (현재 응답 계약) |
| 입력 검증(`JobPostingException`) | 400, 코드·메시지·필드 그대로 |
| 대상 없음 | 404 |
| 버전·상태·확정·시간대 충돌 | 409, 코드·필드 그대로 |
| 역할 오류 | 403 `EMPLOYER_ROLE_REQUIRED` |
| `DataIntegrityViolationException`, `PessimisticLockingFailureException`, `CannotAcquireLockException` | 409 `SCHEDULE_UPDATE_CONFLICT` |

- 400 응답에서는 서비스를 호출하지 않는다.
- 409 `SCHEDULE_UPDATE_CONFLICT` 응답에는 SQL, 키 이름, Deadlock·Lock wait 문구가 노출되지 않는다.
- **한계:**
  - SecurityContext에 principal을 직접 넣었으므로 실제 Security 필터·세션·CSRF·`SecurityConfig`의 PUT 경로 권한은 검증하지 않았다.
  - 기본 `JsonMapper` 기준이다. Spring Boot가 구성한 매퍼와는 다를 수 있다.

## 3. 실행 명령과 결과

JDK는 `JAVA_HOME=C:\Users\HCB\Desktop\woori-workspace\00.sw\jdk-21.0.12.1+1`를 사용했다.

```powershell
cd C:\Users\HCB\Desktop\wonday\farm-api
.\gradlew.bat --rerun-tasks unitTest `
  --tests com.wonday.farm.job.service.WorkScheduleUpdateServiceTest `
  --tests com.wonday.farm.job.validation.WorkScheduleValidatorTest `
  --tests com.wonday.farm.job.dto.UpdateWorkScheduleRequestTest `
  --tests com.wonday.farm.job.api.WorkScheduleUpdateApiTest `
  --tests com.wonday.farm.job.service.JobPostingServiceTest `
  --tests "com.wonday.farm.job.service.JobPostingQueryService*" `
  --tests com.wonday.farm.job.api.JobPostingApiTest `
  --tests com.wonday.farm.demo.AuthDemoPageTest `
  jacocoUnitTestReport
```

- **결과:** BUILD SUCCESSFUL. **229개 실행 · 229개 통과 · 0개 실패 · 0개 제외.**
- **`--tests` 위치:** 프롬프트의 예시 명령은 `--tests` 필터 없이 전체를 실행한다. `--tests`는 반드시 `unitTest` 바로 뒤에 둬야 한다. `jacocoUnitTestReport` 뒤에 두면 Gradle이 "Unknown command-line option" 오류를 낸다.
- **실행하지 않은 것:** `integrationTest`, `fullTest`, 통합·합산 커버리지 태스크, `jacocoUnitTestCoverageVerification`(90% 검사)

## 4. 커버리지 (위 실행 기준)

전체 프로젝트 수치는 보고하지 않는다. 일부 테스트만 실행했으므로 다른 기능의 코드가 미실행으로 잡혀 의미가 없다.

| JOB-06 대상 클래스 | 라인 | 분기 |
|---|---|---|
| WorkScheduleUpdateService | 100% (43/43) | 100% (24/24) |
| WorkScheduleValidator | 100% (31/31) | 100% (28/28) |
| WorkSchedule | 100% (23/23) | 100% (10/10) |
| WorkScheduleRevision | 100% (21/21) | - |
| WorkScheduleUpdateErrorHandler (+ ErrorResponse) | 100% (11/11) | - |
| WorkScheduleUpdateException | 100% (8/8) | - |
| WorkScheduleUpdateApi | 100% (2/2) | - |
| UpdateWorkScheduleRequest / UpdatedWorkScheduleResponse | 100% | - |
| JobPostingService (등록, 영향 받음) | 100% (28/28) | 100% (10/10) |
| StrictLocalDateTimeDeserializer | 66.7% (6/9) | 100% (6/6) |
| StrictLocalDateDeserializer | 71.4% (5/7) | 100% (2/2) |
| StrictIntegerDeserializer | 75.0% (3/4) | 100% (2/2) |
| JobConfig | 0% (0/2) | - |

**100%가 아닌 클래스의 이유:**
- **Strict 역직렬화기:** 미실행으로 표시된 줄은 `return (…) context.handleUnexpectedToken/handleWeirdStringValue(…)` 줄이다. 이 줄은 실제로 실행되어 예외를 던진다(DTO 거부 테스트 86건 이상). 호출 도중 예외가 나면 JaCoCo가 줄을 미실행으로 표시하는 측정상 한계다. 분기는 100%다.
- **JobConfig:** `Clock.systemUTC()` 빈 선언이라 컨텍스트가 기동될 때만 실행된다.

## 5. 리포트 경로

- **빌드 산출물**(다음 빌드 때 덮어씀):
  - `farm-api/build/reports/jacoco/jacocoUnitTestReport/html/index.html`
  - `farm-api/build/reports/tests/unitTest/index.html`
- **보존본(이 폴더):**
  - `jacoco/html/index.html`
  - `jacoco/unit.xml`
  - `test-results-html/index.html`

## 6. 결함

**발견한 결함 없음.**

테스트가 실제로 결함을 잡는지 운영 코드를 임시로 변조해 확인했다. 변조 후 `WorkScheduleUpdateServiceTest`만 실행했고, 매번 원본으로 복원해 `cmp`로 바이트 단위 동일함을 확인했다.

| 변조 | 실패한 테스트 |
|---|---|
| 동일 조건 바로 반환 제거 | `sameConditionsWithCurrentVersion…`, `sameConditionsAtMaxVersion…` |
| BASELINE 저장 생략 | `firstRealUpdateSavesBaselineThenUpdated` 외 3개 |
| 중복 날짜 조회에서 자기 일정 제외를 0으로 바꿈 | `duplicateLookupExcludesTheScheduleItself` 외 2개 |
| REMOVED를 수정 가능 상태에 추가 | `removedScheduleIsNotEditable` |

**관찰 사항(결함 아님, 참고용):**
- **검사 순서:** 버전 검사가 상태·확정·입력 검증보다 먼저 실행된다. 그래서 오래된 버전으로 REMOVED 일정을 수정하면 `SCHEDULE_NOT_EDITABLE`이 아니라 `SCHEDULE_VERSION_CONFLICT`가 나온다.
- **처리되지 않는 예외:** 오류 처리기에 모든 예외를 받는 처리기가 없다. 예상하지 못한 예외(`NullPointerException` 등)는 Spring Boot의 기본 `/error`로 넘어간다. 그때 내부 상세가 노출되는지는 서버 설정에 달려 있어 통합 검증 대상이다.
- **Bean Validation 위반이 여러 개일 때:** `field`는 `getFieldErrors().getFirst()`의 값이다. 어느 필드가 먼저 나올지는 보장되지 않는다. 등록 API에서 이미 기록한 것과 같은 관찰이다.
- **영향 범위:** 이번 변경은 등록 API의 검증 코드를 공용 검증기로 옮겼다. 영향 받은 등록 테스트는 모두 통과했다.

## 7. 단위 테스트로 확인하지 못한 통합 검증 항목

- **실제 MySQL:**
  - `findOwnedForUpdate`의 소유자 필터와 `PESSIMISTIC_WRITE` 잠금
  - `findByIdAndJobPosting_Id`의 공고 연결 필터와 잠금
  - 공고 → 일정 잠금 순서
- **확정 집계 SQL:** `countOccupyingAssignments`가 CANCELLED는 제외하고 EARLY_ENDED와 변경 없음은 집계하는지, 확정자가 여러 명일 때
- **중복 날짜:** `existsByJobPosting_IdAndWorkDateAndIdNot`, 그리고 `uq_posting_work_date` 유니크 제약이 동시 수정에서 동작하는지
- **이력 저장:**
  - `uq_schedule_revision_version` 유니크 제약과 V6 CHECK 제약(BASELINE ↔ actor null 등)
  - UPDATED 저장이 실패했을 때 일정 변경과 BASELINE이 함께 롤백되는지(트랜잭션 원자성)
- **V6 마이그레이션:** 적용 여부, `ddl-auto: validate` 통과 여부. `FlywayMigrationTests`는 V6에 맞게 이미 갱신되어 있지만 실행하지 않았다.
- **PAUSED 상태 저장:** 실제 DB에 `PAUSED`가 저장·조회되는지(V1의 status 컬럼에는 CHECK 제약이 없음)
- **HTTP Security:**
  - `SecurityConfig`의 `PUT /api/job-postings/*/schedules/*` 권한(EMPLOYER)
  - 비로그인 401, WORKER 403 JSON 본문, CSRF 토큰 없음 거부
  - 세션 principal 연결
- **Spring 환경:** Spring Boot가 구성한 JsonMapper의 응답 형식, 처리되지 않는 예외의 `/error` 응답
- **브라우저:** farm-demo 화면의 달력·시간 선택, CSRF·조건 버전 자동 처리(JavaScript)

## 8. 확인 사항

- 운영 코드, DB 마이그레이션, Gradle·CI 설정은 수정하지 않았다. 변조 확인은 임시로만 했고 모두 원본으로 복원했다.
- 테스트 비활성화, 제외 태그, 기준 완화는 하지 않았다.
- 통합 테스트는 작성하거나 실행하지 않았다. `@SpringBootTest`, `@WebMvcTest`, `@DataJpaTest`도 사용하지 않았다.
- 커밋, 푸시, PR, merge는 하지 않았다.

## PR 본문용 요약

> **JOB-06 일정 수정 API 단위 검증 (Claude, 2026-11-25)**
> - JOB-06 단위 테스트 153개를 추가했다: 서비스 70, DTO·엄격 JSON 47, 컨트롤러·오류 응답 25, 공용 검증기 11.
> - 영향 받은 기존 단위 테스트 76개와 함께 실행했고, 229개 모두 통과했다.
> - 수정 서비스, 공용 검증기, 엔티티, 오류 처리기는 라인·분기 커버리지 100%다.
> - 발견한 결함은 없다. 운영 코드 4곳을 임시로 변조해 테스트가 모두 잡아내는 것을 확인했다.
> - 실제 MySQL 집계·잠금·롤백·V6 마이그레이션, Security·세션·CSRF, 브라우저 동작은 범위 밖이다(보고서 7절).
