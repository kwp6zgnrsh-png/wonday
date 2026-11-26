# JOB-07 날짜 모집 취소 API — 단위 테스트 독립 검증 보고서

- **검증일:** 2026-11-25
- **검증자:** Claude (테스트 작성과 실행만 했다)
- **대상:** 브랜치 `job-cancel`, HEAD `05d2354`
  - JOB-07 운영 코드는 **커밋되지 않은 작업 트리 상태**다(9절).
  - `AGENTS.md`는 없다.
- **실행 범위:** JOB-07 신규 테스트와 직접 영향을 받은 기존 단위 테스트만 선택해 실행했다.
  - **이 결과는 전체 테스트 통과나 전체 커버리지가 아니다.**
- **검증 범위 밖:**
  - 실제 MySQL의 집계·잠금·커밋·롤백·행 보존
  - Security 필터·세션·CSRF·익명 401
  - farm-demo JavaScript
- **수동 확인:** 사용자가 보고한 farm-demo 409 수동 확인은 자동 테스트 결과에 포함하지 않았다.
- **운영 코드:** 수정하지 않았고, **임시 변조도 하지 않았다**(이번 요청 조건).

## 요약

| 항목 | 결과 |
|---|---|
| 실행 | 211개 (JOB-07 신규 116 + 기존 영향 95) |
| 결과 | **211 통과 · 0 실패 · 0 오류 · 0 건너뜀** |
| 결함 | **없음** |
| 커버리지 | `WorkScheduleCancelService` 라인 97.7% · 분기 96.2% (미실행 1곳은 현재 도달 불가 분기, 6절) |
| 나머지 JOB-07 클래스 | 라인 100% (6절) |

## 1. 작성·수정한 파일 (모두 신규 테스트)

| 파일 | 테스트 수 |
|---|---|
| `src/test/java/com/wonday/farm/job/service/WorkScheduleCancelServiceTest.java` | 54 |
| `src/test/java/com/wonday/farm/job/api/WorkScheduleCancelApiTest.java` | 34 |
| `src/test/java/com/wonday/farm/job/dto/CancelWorkScheduleRequestTest.java` | 21 |
| `src/test/java/com/wonday/farm/job/entity/WorkScheduleCancelRecruitmentTest.java` | 7 |

기존 테스트, 운영 코드, 마이그레이션, Gradle·CI 설정은 변경하지 않았다.

## 2. 검증 항목과 테스트 연결

서비스 테스트는 저장소만 mock으로 바꿨다. 회원·공고·일정은 실제 도메인 객체를 썼고, Clock은 `Clock.fixed`(일부는 mock Clock)를 썼다.

- **기본 데이터:** 예정 시작은 GMT+9 기준 `2026-12-02T09:00`, 즉 **UTC `2026-12-02T00:00:00Z`**이다.
- **기본 취소 시각:** `2026-11-25T08:00:00.123456789Z`

### [1] 정상 취소 — `WorkScheduleCancelServiceTest.SuccessfulCancel`
- **상태 전환:** OPEN, PAUSED, CLOSED 세 상태에서 각각 `REMOVED`로 바뀐다(집계 0, 시작 전).
- **취소 시각:** 서버 시각을 마이크로초로 절삭한 UTC 값(`…08:00:00.123456`)으로 기록한다.
- **응답:** 레코드 전체가 기대값과 같다.
  - 공고 101, 일정 201, 근무일 `2026-12-02`, `REMOVED`, 버전 1
  - 취소 시각 `2026-11-25T08:00:00.123456Z` (오프셋 UTC)
- **조건 버전:** 증가하지 않는다. 4는 4로 남고, `Integer.MAX_VALUE`도 그대로다.
- **트랜잭션:** `READ_COMMITTED`이다.

### [2] 역할·소유자·대상 — `RoleOwnershipAndTarget`
- **역할 확인:** 회원이 없거나, WORKER이거나, `ADMIN` 역할이면 `AccessDeniedException`이다.
  - 이때 공고와 일정 저장소를 호출하지 않는다.
  - 상태, 취소 시각, 조건은 그대로다.
- **로그인 후 역할 변경:** 같은 회원으로 다른 날짜를 한 번 취소한 뒤, DB 역할을 WORKER로 바꾸고 다시 요청하면 거부된다.
- **ID 검증:** 공고·일정 ID가 0이거나 음수이면 400 `INVALID_REQUEST`이고, 저장소를 호출하지 않는다.
- **조회 순서(`inOrder`로 확인):**
  1. `members.findById(10)`
  2. `findOwnedForUpdate(101, 10)`
  3. `findByIdAndJobPosting_Id(201, 101)`
- **대상 없음:**
  - 소유 공고가 없으면 404 `JOB_POSTING_NOT_FOUND`이고, 일정을 조회하지 않는다.
  - 해당 공고에 일정이 없으면 404 `WORK_SCHEDULE_NOT_FOUND`이다.
  - 둘 다 상태가 바뀌지 않는다.
- **한계:** mock 인자 검증일 뿐이다. 실제 SQL의 소유자 필터와 공고 연결 필터는 검증하지 않았다.

### [3] 예정 시작 시각 — `PlannedStart`
- **시작 경계:**
  - 시작 1나노초 전(`…23:59:59.999999999Z`)은 허용된다. 취소 시각은 `…23:59:59.999999`로 절삭된다.
  - 시작과 정확히 같은 시각, 1나노초 후, 1시간 후는 409 `SCHEDULE_ALREADY_STARTED`이고 상태가 바뀌지 않는다.
- **GMT+9 09:00 = UTC 00:00:**
  - UTC 08:59는 이미 시작된 것으로 거부된다.
  - UTC 전날 23:59는 허용된다.
  - 현지 시각을 UTC로 착각하는 구현이었다면 이 결과가 반대로 나온다.
- **저장된 공고 시간대 사용:** 공고 시간대가 `UTC`이면 같은 UTC 08:59가 시작 전으로 판정되어 허용된다.
- **시간대 보존:** `Asia/Seoul`·`UTC`가 저장된 공고는 취소 후에도 시간대가 그대로다(GMT+9로 덮어쓰지 않음).
- **나노초 시작 경계:**
  - 예정 시작이 `09:00:00.000000500`일 때 `…00.000000499Z`는 허용된다. 취소 시각은 `00:00:00`으로 절삭된다.
  - `…00.000000500Z`는 시작된 것으로 거부된다.
- **시각 확인 순서:** mock Clock과 `inOrder`로 확인했다.
  - 공고 잠금 → 일정 잠금 → 확정 집계 → `clock.instant()` 순서다.
  - `clock.instant()`는 1회만 호출된다.
- **시간대 오류:** null, 빈 값, 공백, `Seoul`, `GMT+99`, `Mars/Olympus`이면 409 `TIME_ZONE_UNAVAILABLE`이다.
  - 공고 시간대 값은 바뀌지 않는다. GMT+9나 서버 기본 시간대로 보완하지 않는다.
  - 일정도 취소되지 않는다.
- **예정 시작 해석 불가는 409 `SCHEDULE_START_TIME_UNAVAILABLE`:**
  - America/New_York의 DST 공백 구간(`2026-03-08T02:30`)
  - DST 중복 구간(`2026-11-01T01:30`)
  - 예정 시작 누락(null)
- **실제 출근 확인:** 그런 기능은 추가하지 않았다. 정책대로 예정 시작 시각만 기준으로 판단한다.

### [4] 남은 확정자 — `ConfirmedWorkers`
- **집계 0:** 허용한다.
- **집계 양수:** 1, 3, `Long.MAX_VALUE`이면 409 `SCHEDULE_ALREADY_CONFIRMED`이다. 상태와 취소 시각은 바뀌지 않는다.
- **집계 대상:** 대상 일정 ID로만 1회 조회한다. 다른 ID에 7을 줘도 결과에 영향이 없다.
- **한계:** CANCELLED는 제외하고 EARLY_ENDED와 변경 없음은 포함하는 실제 집계는 `countOccupyingAssignments`의 네이티브 SQL이 한다. 이번 테스트는 mock 집계값만 줬으므로 **그 SQL은 검증하지 않았다**(8절).

### [5] 버전 충돌 — `TermsVersion`, `SuccessfulCancel`
- **현재 버전:** 현재 버전(2)으로 요청하면 정상 취소된다.
- **과거·미래 버전:** 현재 버전이 2일 때 1이나 3을 보내면 409 `SCHEDULE_VERSION_CONFLICT`, field `expectedTermsVersion`이다.
  - 상태, 취소 시각, 버전이 바뀌지 않는다.
  - 확정 집계도 조회하지 않는다.
- **버전 증가 없음:** 취소 자체는 조건 버전을 증가시키지 않는다(`Integer.MAX_VALUE` 포함).

### [6] 재요청 — `RepeatedRequest`, `WorkScheduleCancelRecruitmentTest`
- **같은 요청 반복:** 1분 뒤 다시 보내도 첫 응답과 같고, 최초 취소 시각이 유지된다.
- **시작 이후 재요청:** 예정 시작 하루 뒤에 다시 보내도 기존 결과를 반환한다.
- **오래된 버전:** 이미 REMOVED(버전 3)인 일정에 버전 1로 다시 보내면 기존 결과(버전 3, 기존 시각)를 반환한다.
- **기존 시각 없음:** 기존 REMOVED 행의 취소 시각이 null이면 응답도 null이다. 엔티티 값도 null로 남는다(추측해서 채우지 않음).
- **역할·소유자 검사:** 재요청에도 수행한다. WORKER는 거부되고, 소유 공고가 없으면 404다.
- **불필요한 재검사 없음:** 이미 REMOVED인 일정에 다음 조건을 줘도 200 기존 결과를 반환한다. 확정 집계와 Clock은 호출하지 않는다.
  - 시간대 null
  - 확정 집계 5
  - mock Clock
- **도메인 메서드:** `cancelRecruitment`를 두 번 호출해도 최초 시각을 덮어쓰지 않는다. 시각이 없는 REMOVED 행도 그대로 null이다.
- **DTO 검증과의 구분:** 위 재요청은 모두 양수 버전으로 한 것이다. HTTP 요청의 null·0·음수 버전은 DTO 검증에서 400 `INVALID_WORK_SCHEDULE`이고 서비스를 호출하지 않는다([8]). 이미 REMOVED인 일정이라도 잘못된 본문은 허용되지 않는다.

### [7] 이력과 다른 데이터 보존 — `Preservation`, `WorkScheduleCancelRecruitmentTest`

**실제 객체로 확인한 것:**
- 일정 ID와 공고 연결(같은 객체)이 유지된다.
- 근무일, 시작·종료, 휴게 시작·종료, 조건 버전이 유지된다.
- 공고의 정원·일급·장소·작목·작업 유형·시간대가 유지된다.
- 같은 공고의 다른 일정(ID 202)은 상태가 OPEN이고 취소 시각이 null이며, 서비스가 조회하지도 않는다.

**쓰기 범위의 코드 검토와 mock 확인:**
- 저장소 호출은 조회 2개와 집계 1개뿐이다. `verifyNoMoreInteractions`로 save나 delete 호출이 없음을 확인했다.
- 서비스는 지원·약정·조건 이력·`work_change`·보험 변경 버전 저장소를 **의존성으로 갖지 않는다**(생성자 인자가 회원·공고·일정 저장소와 Clock뿐). 코드 구조상 이런 데이터를 만들거나 지울 수 없다.

**한계:** 실제 DB 행이 보존되는지, JPA 변경 감지 저장이 동작하는지, 커밋·롤백은 mock 단위 테스트로 증명하지 않았다. 존재하지 않는 `schedules.save` 호출은 mock으로 만들지 않았다.

### [8] DTO·컨트롤러·오류 응답 — `CancelWorkScheduleRequestTest`, `WorkScheduleCancelApiTest`

**DTO** (Jackson `JsonMapper`와 Hibernate Validator로 실제 실행):
- `1`과 `2147483647`은 정상이다.
- 다음 13종은 역직렬화 단계에서 거부된다: 소수, `1.0`, 지수 2종, 숫자 문자열, 빈 문자열, 배열, 객체 2종, true/false, int 범위 초과(양수·음수)
- 0, -1, `Integer.MIN_VALUE`, null, 필드 누락은 `expectedTermsVersion`에 대한 위반이다.
- 레코드의 구성 요소는 `expectedTermsVersion` 하나뿐이다. 소유자·상태·취소 시각을 요청으로 지정할 수 없다.

**컨트롤러** (standalone MockMvc):
- principal 회원 ID(42), 경로 ID(101, 201), DTO가 그대로 서비스에 전달된다.
- 응답 JSON의 필드는 정확히 6개다. `status`는 `"REMOVED"`, `termsVersion`은 1, `cancelledAt`은 `"2026-11-25T08:00:00.123456Z"`이다.
- 기존 시각이 없으면 `cancelledAt` 키가 있고 값이 JSON null이다.

**오류 응답** (모든 본문의 필드는 정확히 `code`·`message`·`field`):

| 경우 | 응답 |
|---|---|
| 버전 누락·null·0·음수 | 400 `INVALID_WORK_SCHEDULE`, field `expectedTermsVersion` |
| 빈 본문, `{`, `[]`, `null`, 소수, 지수, 숫자 문자열, 배열, 객체, boolean, int 초과 | 400 `INVALID_REQUEST_BODY`, field null |
| Content-Type이 `text/plain`이거나 없음 | 400 `INVALID_REQUEST_BODY` |
| 경로 ID가 숫자가 아님, 소수, long 초과 | 400 `INVALID_REQUEST_BODY` |
| 서비스의 `INVALID_REQUEST` | 400 |
| `JOB_POSTING_NOT_FOUND` / `WORK_SCHEDULE_NOT_FOUND` | 404 |
| 409 도메인 오류 6종 | 409. code·message·field를 서비스 값 그대로 반환 (버전 충돌은 field `expectedTermsVersion`) |
| `AccessDeniedException` | 403 `EMPLOYER_ROLE_REQUIRED` (내부 메시지 비노출) |
| `DataIntegrityViolation`·`PessimisticLockingFailure`·`CannotAcquireLock` | 409 `SCHEDULE_CANCEL_CONFLICT` |

- 409 도메인 오류 6종: `SCHEDULE_VERSION_CONFLICT`, `SCHEDULE_NOT_CANCELLABLE`, `SCHEDULE_ALREADY_CONFIRMED`, `SCHEDULE_ALREADY_STARTED`, `TIME_ZONE_UNAVAILABLE`, `SCHEDULE_START_TIME_UNAVAILABLE`
- `SCHEDULE_CANCEL_CONFLICT` 응답에는 SQL, 테이블명, Deadlock·Lock wait 문구가 노출되지 않는다.
- 형식 오류와 DTO 검증 실패에서는 서비스를 호출하지 않는다.

**한계:**
- standalone MockMvc이므로 실제 Security 필터·세션·CSRF·익명 401·`SecurityConfig`의 역할 차단은 **검증하지 않았다**.
- 기본 `JsonMapper` 기준이다.

### [9] 영향 받은 기존 테스트

`WorkSchedule`에 `cancelledAt` 필드와 `cancelRecruitment`가 추가되어 다음 기존 테스트를 함께 실행했다.

| 테스트 | 개수 | 실행한 이유 |
|---|---|---|
| `WorkScheduleUpdateServiceTest` | 70 | REMOVED 일정 수정 거부를 포함해, 같은 엔티티를 다룬다 |
| `JobPostingQueryServiceTest` | 24 | 내 공고 조회가 일정 엔티티를 매핑한다 |
| `JobPostingQueryServiceMissingCountsTest` | 1 | 위와 같다(`*JobPostingQueryService*` 패턴에 포함) |

이 밖의 기존 테스트는 실행하지 않았다.

## 3. 실행 명령

JDK는 `JAVA_HOME=C:\Users\HCB\Desktop\woori-workspace\00.sw\jdk-21.0.12.1+1`를 사용했다.

```powershell
cd C:\Users\HCB\Desktop\wonday\farm-api
.\gradlew.bat unitTest `
  --tests "*WorkScheduleCancel*" `
  --tests "*CancelWorkScheduleRequestTest" `
  --tests "*WorkScheduleUpdateServiceTest" `
  --tests "*JobPostingQueryService*" `
  jacocoUnitTestReport
```

- `*WorkScheduleCancel*` 패턴은 `WorkScheduleCancelServiceTest`, `WorkScheduleCancelApiTest`, `WorkScheduleCancelRecruitmentTest`를 포함한다.
- `--rerun-tasks`는 쓰지 않았다.
- 다음은 실행하지 않았다: 전체 `test`·`unitTest`, `integrationTest`, `fullTest`, 통합·합산 리포트, 90% 검증 태스크
- **첫 실행 실패와 수정:**
  - 첫 실행에서 내가 작성한 테스트 1개가 실패했다(`startWithSubMicrosecondPartAtExactInstantIsStarted`).
  - 원인은 테스트 코드에 있었다. 공통 검증 도우미가 "예정 시작 09:00 유지"도 확인하는데, 이 테스트는 예정 시작을 일부러 바꾼다.
  - 테스트를 고쳐 다시 실행했다. 운영 코드의 문제가 아니다.

## 4·5. 테스트 개수와 결과 (최종 실행)

| 구분 | 테스트 | 개수 | 성공 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|---|---|
| JOB-07 신규 | WorkScheduleCancelServiceTest | 54 | 54 | 0 | 0 | 0 |
| JOB-07 신규 | WorkScheduleCancelApiTest | 34 | 34 | 0 | 0 | 0 |
| JOB-07 신규 | CancelWorkScheduleRequestTest | 21 | 21 | 0 | 0 | 0 |
| JOB-07 신규 | WorkScheduleCancelRecruitmentTest | 7 | 7 | 0 | 0 | 0 |
| 기존 영향 | WorkScheduleUpdateServiceTest | 70 | 70 | 0 | 0 | 0 |
| 기존 영향 | JobPostingQueryServiceTest | 24 | 24 | 0 | 0 | 0 |
| 기존 영향 | JobPostingQueryServiceMissingCountsTest | 1 | 1 | 0 | 0 | 0 |
| **합계** | | **211** | **211** | **0** | **0** | **0** |

## 6. JOB-07 대상 클래스 커버리지 (위 선택 실행 기준)

| 클래스 | 라인 | 분기 |
|---|---|---|
| WorkScheduleCancelService | 97.7% (42/43) | 96.2% (25/26) |
| WorkScheduleCancelApi | 100% (2/2) | - |
| CancelWorkScheduleRequest | 100% (1/1) | - |
| CancelledWorkScheduleResponse | 100% (1/1) | - |
| WorkScheduleCancelErrorHandler (+ ErrorResponse) | 100% (10/10) | - |
| WorkScheduleCancelException | 100% (8/8) | - |
| WorkSchedule (`cancelRecruitment` 포함) | 100% (27/27) | 100% (12/12) |
| StrictIntegerDeserializer (DTO가 사용) | 75.0% (3/4) | 100% (2/2) |

**미실행 부분:**
- **`WorkScheduleCancelService` 67~68행:** `SCHEDULE_NOT_CANCELLABLE` 분기다.
  - REMOVED는 그 앞에서 기존 결과를 반환한다.
  - 나머지 상태인 OPEN·PAUSED·CLOSED는 모두 허용 목록에 있다.
  - 따라서 현재 상태 enum(4종)에서는 **도달할 수 없는 방어 코드**다. 의미 없는 테스트로 억지로 실행하지 않았다.
  - 상태가 추가되면 그때 동작하게 된다.
- **`StrictIntegerDeserializer`의 1줄:** `return … handleUnexpectedToken(…)` 줄이다. 실제로 실행되어 예외를 던지지만, JaCoCo는 예외가 난 줄을 미실행으로 표시한다(측정상 한계).

## 7. 결함

**발견한 결함 없음.**

**관찰 사항(결함 아님, 참고):**
- **도달 불가 분기:** 위 `SCHEDULE_NOT_CANCELLABLE` 분기는 현재 도달할 수 없다. API 문서의 오류표에는 있지만, 현재 상태 값으로는 이 응답이 나오지 않는다.
- **검사 순서:** 확정 집계 검사가 시간대·예정 시작 검사보다 먼저 실행된다. 그래서 시간대가 잘못됐고 확정자도 남은 일정은 `TIME_ZONE_UNAVAILABLE`이 아니라 `SCHEDULE_ALREADY_CONFIRMED`로 응답한다. 문서의 처리 순서(6 → 7)와 같다.
- **기존 취소 시각의 기준:** `cancelled_at`은 V1부터 있던 컬럼이다. 저장소에서 이 컬럼에 쓰는 기존 Java 코드는 찾지 못했다. 다만 수동 데이터나 시드 데이터로 이미 값이 들어간 행이 있다면, 그 값이 원래 UTC였는지와 관계없이 응답에 `Z`(UTC)가 붙는다. 문서는 "기존 데이터의 시각 값을 일괄 변환하지 않는다"고 되어 있으므로, 이런 기존 행이 실제로 있는지 확인할 필요가 있다.
- **처리하지 않는 예외:** 오류 처리기에 모든 예외를 받는 처리기가 없다. 예상하지 못한 예외는 Spring Boot의 기본 `/error`로 넘어간다(통합 검증 대상).

## 8. 단위 테스트로 확인하지 못한 범위

- **잠금 조회:**
  - `findOwnedForUpdate`의 소유자 필터와 `PESSIMISTIC_WRITE` 잠금
  - `findByIdAndJobPosting_Id`의 공고 연결 필터와 잠금
  - 공고 → 일정 잠금 순서의 실제 동작
- **확정 집계 SQL:** `countOccupyingAssignments`가 변경 기록이 없는 근무와 EARLY_ENDED는 포함하고 CANCELLED는 제외하는지. 전원이 CANCELLED일 때 0이 되는지.
- **변경 감지 저장:** JPA 변경 감지로 `status`와 `cancelled_at`이 실제로 저장되는지, `DATETIME(6)` 정밀도
- **트랜잭션:** 커밋·롤백, 동시 취소·수정 경합, DB 무결성·잠금 예외의 실제 발생 경로
- **행 보존:** 지원·약정·조건 이력·`work_change`·보험 변경 버전 행이 실제로 보존되는지. 새 행이 생기지 않는지.
- **Security:**
  - `SecurityConfig`의 `POST /api/job-postings/*/schedules/*/cancel` 역할 제한
  - 익명 401, WORKER 403 JSON 본문, CSRF 누락·오류 거부
- **Spring 환경:** Spring Boot가 구성한 JsonMapper의 `cancelledAt` 형식, 처리하지 않는 예외의 `/error` 응답
- **브라우저:** farm-demo 화면(확인창, CSRF 발급, 버전 전송, 재요청 버튼)

## 9. 테스트 대상 상태

- **브랜치·커밋:** `job-cancel`, HEAD `05d2354` (Merge PR #14)
- **커밋되지 않은 변경(작성자 작업, 보존함):**
  - 수정: `README.md`, `SecurityConfig.java`, `WorkSchedule.java`, `demo/farm-jobs.html`, `demo/farm-login.html`
  - 신규: `WorkScheduleCancelApi`, `CancelWorkScheduleRequest`, `CancelledWorkScheduleResponse`, `WorkScheduleCancelErrorHandler`, `WorkScheduleCancelException`, `WorkScheduleCancelService`, `docs/job-07-date-cancellation.md`
- **내가 추가한 것:** 위 테스트 파일 4개와 이 보고서(모두 커밋되지 않음)

## 10. 확인 사항

- 운영 코드·마이그레이션·Gradle·CI: 수정하지 않았다. **임시 변조도 하지 않았다.**
- 통합 테스트: 작성하거나 실행하지 않았다. `@SpringBootTest`·`@WebMvcTest`·`@DataJpaTest`를 사용하지 않았다.
- 테스트 비활성화, 제외 태그, 기준 변경: 하지 않았다.
- 커밋·푸시·PR·merge: 하지 않았다.

## 리포트 경로

HTML 리포트는 docs에 복사하지 않았다. 아래 경로는 다음 빌드 때 덮어써진다.

- JaCoCo HTML: `farm-api/build/reports/jacoco/jacocoUnitTestReport/html/index.html`
- JaCoCo XML: `farm-api/build/reports/jacoco/jacocoUnitTestReport/jacocoUnitTestReport.xml`
- 테스트 결과 HTML: `farm-api/build/reports/tests/unitTest/index.html`
- 테스트 결과 XML: `farm-api/build/test-results/unitTest/`

## PR 본문용 요약

> **JOB-07 날짜 모집 취소 API 단위 검증 (Claude, 2026-11-25)**
> - JOB-07 신규 단위 테스트 116개(서비스 54, 컨트롤러·오류 응답 34, DTO 21, 엔티티 7)를 추가했다. 직접 영향 받은 기존 테스트 95개와 함께 선택 실행했고, 211개 모두 통과했다.
> - 선택 실행이므로 전체 테스트 결과가 아니다.
> - 다음을 검증했다:
>   - 예정 시작 경계(1나노초 전 허용, 정각·이후 거부, GMT+9 09:00 = UTC 00:00)
>   - 저장 시간대 사용, DST 해석 불가
>   - 재요청 시 기존 결과 반환(시각·버전 유지, 집계·시계 재검사 없음)
>   - 버전 충돌, 데이터 보존
> - 서비스 커버리지는 라인 97.7%다. 미실행 1곳은 현재 도달 불가인 `SCHEDULE_NOT_CANCELLABLE` 분기다.
> - 발견한 결함은 없다.
> - 실제 MySQL 집계·잠금·롤백, Security·CSRF, 브라우저 동작은 범위 밖이다.
