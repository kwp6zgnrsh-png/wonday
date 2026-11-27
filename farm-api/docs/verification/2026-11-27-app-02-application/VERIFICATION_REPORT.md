# APP-02 지원 API·farm-demo 단위 검증 보고서 (2026-11-27)

- 저장소: `C:\Users\HCB\Desktop\wonday`
- 브랜치·기준 커밋: `app-apply`, 기준 커밋 `078c69b`. 작업 트리에는 아직 커밋되지 않은 기능 변경이 있다.
- AGENTS.md: 저장소 루트와 `farm-api` 모두에 없다.
- 대상
  - `POST /api/job-postings/{jobPostingId}/applications`
  - `application` 패키지
  - 기존 조회·집계 변경 (APP-01, JOB-05)
  - `demo/application` 패키지
  - demo 화면 3종
- 검증자는 기능 코드·HTML·마이그레이션·Gradle·CI를 수정하지 않았다. 커밋·푸시·브랜치 변경·stash·restore도 하지 않았다.

## 1. 결론 요약

| 구분 | 결과 |
|---|---|
| Java 단위 테스트 (최종 재실행) | **310 / 310 통과**. 실패·오류·건너뜀 0. 신규 166 + 기존 직접 영향 144 |
| Node 화면 스크립트 테스트 | **18 / 18 통과** (최초 실행부터 통과) |
| 발견 결함 (기능 코드) | **없음** |
| 관찰 사항 | 6건 (O-1~O-6). 결함이 아니며 정책 확인을 권장 |

- Java 최초 실행은 컴파일 오류였고, 2차 실행에서는 7건이 실패했다. 모두 **검증자가 작성한 테스트 코드의 오류**였다(4장). 기대값은 바꾸지 않았다.
- 이번 실행은 Repository mock, standalone MockMvc, 가짜 DOM·가짜 서버 수준이다. 다음은 검증하지 않았다(7장).
  - 실제 MySQL의 잠금·롤백
  - V12 제약
  - 전체 Security 체인

## 2. 추가·수정한 파일

### 신규 테스트 (검증자 작성)

| 파일 (`farm-api/` 기준) | 테스트 | 검증 수준 |
|---|---:|---|
| `src/test/java/com/wonday/farm/application/testsupport/ApplicationTestData.java` | — | 가상 데이터 생성기 |
| `src/test/java/com/wonday/farm/application/validation/ApplicationRequestValidatorTest.java` | 21 | 단위 |
| `src/test/java/com/wonday/farm/application/dto/ApplicationPayloadTest.java` | 17 | JSON 변환·Bean Validation·codec 단위 |
| `src/test/java/com/wonday/farm/application/service/JobApplicationServiceTest.java` | 48 | 서비스 단위. 실제 엔티티·실제 codec·고정 Clock, Repository는 mock |
| `src/test/java/com/wonday/farm/application/entity/ApplicationEntityTest.java` | 7 | 엔티티 단위 + 잠금·SQL 선언 확인 |
| `src/test/java/com/wonday/farm/application/api/JobApplicationApiTest.java` | 40 | standalone MockMvc (principal 직접 주입) |
| `src/test/java/com/wonday/farm/job/repository/ApplicationRoundQueryDeclarationTest.java` | 3 | SQL 문자열 선언 확인 |
| `src/test/java/com/wonday/farm/demo/application/DemoJobApplicationServiceTest.java` | 14 | 서비스 단위 (Repository mock, 고정 Clock) |
| `src/test/java/com/wonday/farm/demo/application/DemoJobApplicationApiTest.java` | 13 | standalone MockMvc + 프로필·SQL·SecurityConfig 선언 확인 |
| `src/test/java/com/wonday/farm/demo/application/JobApplyDemoPageTest.java` | 3 | HTML·스크립트 정적 문자열 검사 |
| `docs/verification/2026-11-27-app-02-application/job-apply-page.test.mjs` | 18 (Node) | 인라인 스크립트 원문을 `node:vm` + 가짜 DOM·fetch·crypto로 실행 |

### 수정한 기존 테스트
- **없음.** 기존 테스트 파일의 해시는 실행 전후로 같다.
- `FlywayMigrationTests`의 미커밋 변경은 작업 시작 전부터 있던 기능 작성자의 변경이다. 통합 태그라 이번에는 실행하지 않았다.

## 3. 실행 명령과 선택한 테스트 클래스

환경과 실행 범위
- `JAVA_HOME=…/jdk-21.0.12.1+1`, 작업 폴더는 `farm-api`다.
- 전체 `test`·`fullTest`·`integrationTest`와 커버리지 태스크는 실행하지 않았다.

```
.\gradlew.bat --no-daemon unitTest
  --tests "com.wonday.farm.application.validation.ApplicationRequestValidatorTest"
  --tests "com.wonday.farm.application.dto.ApplicationPayloadTest"
  --tests "com.wonday.farm.application.service.JobApplicationServiceTest"
  --tests "com.wonday.farm.application.entity.ApplicationEntityTest"
  --tests "com.wonday.farm.application.api.JobApplicationApiTest"
  --tests "com.wonday.farm.job.repository.ApplicationRoundQueryDeclarationTest"
  --tests "com.wonday.farm.demo.application.DemoJobApplicationServiceTest"
  --tests "com.wonday.farm.demo.application.DemoJobApplicationApiTest"
  --tests "com.wonday.farm.demo.application.JobApplyDemoPageTest"
  --tests "com.wonday.farm.job.service.JobPostingQueryServiceTest"
  --tests "com.wonday.farm.job.service.JobPostingQueryServiceMissingCountsTest"
  --tests "com.wonday.farm.job.service.WorkerJobPostingQueryServiceTest"
  --tests "com.wonday.farm.job.api.MyJobPostingApiTest"
  --tests "com.wonday.farm.job.api.WorkerJobPostingApiTest"
  --tests "com.wonday.farm.demo.SalaryAccountDemoPageTest"
  --tests "com.wonday.farm.demo.insurance.WorkerVerificationDemoPageTest"
  --tests "com.wonday.farm.demo.insurance.InsuranceSalaryAccountDemoPageTest"
  --tests "com.wonday.farm.demo.completion.WorkCompletionDemoPageTest"
  --tests "com.wonday.farm.demo.completion.DemoWorkCompletionApiTest"
```

```
node --test docs/verification/2026-11-27-app-02-application/job-apply-page.test.mjs
```

### 직접 영향받는 기존 테스트 선택 근거

| 기존 테스트 | 이유 |
|---|---|
| `JobPostingQueryServiceTest`, `JobPostingQueryServiceMissingCountsTest`, `MyJobPostingApiTest` | JOB-05 집계(`countOwnedSchedules`)와 `JobPostingQueryService` 변경, 집계 누락 시 0 처리 |
| `WorkerJobPostingQueryServiceTest`, `WorkerJobPostingApiTest` | APP-01 본인 지원 조회(`findWorkerApplications`)가 현재 회차만 읽도록 변경 |
| `SalaryAccountDemoPageTest`, `WorkerVerificationDemoPageTest`, `InsuranceSalaryAccountDemoPageTest`, `WorkCompletionDemoPageTest`, `DemoWorkCompletionApiTest` | 변경된 `SecurityConfig.java`·`farm-login.html` 문자열을 읽는 정적 검사 |

실행하지 않은 기존 테스트
- `JobPostingServiceTest`, `WorkScheduleCancelServiceTest`, `WorkScheduleUpdateServiceTest`
  - 저장소 인터페이스에 메서드가 추가됐을 뿐이고, 이 테스트들이 쓰는 동작은 바뀌지 않아 직접 영향 대상에서 뺐다.
- `JobPostingHttpTests`, `JobPostingRollbackTests`, `FlywayMigrationTests`
  - 통합 태그라 실행하지 않았다.

### 실행 범위 확인
- XML 51개는 모두 선택한 19개 클래스와 그 @Nested 클래스에 속한다.
- 소스의 @Nested 목록과 XML 목록을 클래스마다 대조했고, 19개 모두 일치했다.

## 4. 실행 결과 (최초 실행과 재실행 구분)

### Java 단위 테스트

| 회차 | 테스트 | 통과 | 실패 | 오류 | 건너뜀 | 시간 |
|---|---:|---:|---:|---:|---:|---|
| 1차 (최초) | — (컴파일 실패, 실행 0) | — | — | — | — | BUILD FAILED in 28s (측정 30초) |
| 2차 | 310 | 303 | 7 | 0 | 0 | BUILD FAILED in 41s (측정 42초) |
| 3차 (최종) | 310 | **310** | 0 | 0 | 0 | BUILD SUCCESSFUL in 48s (측정 50초) |

테스트 코드 수정 내역 (두 가지 모두 테스트 코드 오류이고 기대값은 바꾸지 않았다)

| 회차 | 원인 | 수정 |
|---|---|---|
| 1차 | `JobApplicationServiceTest`의 Mockito `argThat` 람다가 `saveAllAndFlush` 인자를 `List`로 취급했다. 실제 인자 타입이 `Iterable`이라 `size()`·`stream()`이 없어 컴파일에 실패했다. | `Iterable`을 목록으로 옮겨 검사한다. 검사 조건("2건, 모두 current=true")은 같다. |
| 2차 | `JobApplicationServiceTest`의 테스트 헬퍼 `Map<Long, WorkSchedule>`를 `byId.get(202)` 같은 int 리터럴로 조회했다. 키가 `Integer`로 박싱돼 null이 반환됐고, `ReflectionTestUtils.setField(null, …)`가 `IllegalArgumentException`을 던졌다. 7건 모두 이 원인이다. | `byId.get(202L)`처럼 long 리터럴로 바꿨다. |

최종 실행의 신규 테스트와 기존 테스트

| 구분 | 클래스 | 테스트 | 결과 |
|---|---:|---:|---|
| 신규 | 9 | 166 | 166 통과 |
| 기존 직접 영향 | 10 | 144 | 144 통과 (수정 없음) |

### Node 화면 스크립트 테스트 (Java와 별도 집계)

| 회차 | 테스트 | 통과 | 실패 | 시간 |
|---|---:|---:|---:|---|
| 1차 (최초·최종) | 18 | **18** | 0 | 약 139ms |

## 5. 항목별 검증 내용과 수준

### 5-1. 요청 검증·응답 보관 (`ApplicationRequestValidatorTest`, `ApplicationPayloadTest`)

**요청 키 (`Idempotency-Key`)**
- UUID v4는 통과하고, 대문자는 소문자로 정규화돼 같은 키가 된다.
- 다음은 모두 400 `INVALID_REQUEST`다.
  - null·빈 값
  - 다른 버전 (v1·v7)
  - 잘못된 variant (`c`)
  - 하이픈 없음, 중괄호, 앞뒤 공백·개행, 길이 부족, nil UUID
  - 쉼표로 합친 두 키

**일정 목록**
- 1개·100개는 통과하고, 결과는 일정 ID 오름차순이다.
- 0개·101개, null 목록·null 요청, null 항목, null·0 이하 ID와 버전은 400이다.
- 같은 일정 ID가 중복되면 버전이 달라도 400이다. 공고 ID가 0 이하여도 400이다.

**요청 지문**
- 선택 순서가 달라도 지문이 같다 (SHA-256 hex 64자).
- 공고·일정·버전·일정 수가 바뀌면 지문이 달라진다.
- 구분자 덕분에 `201:12` / `2011:2`, 공고 `10|1` / `1|01` 같은 자리 경계 충돌이 없다.

**JSON 변환** (기본 `JsonMapper`)
- 다음 값은 정수로 자동 변환하지 않고 JSON 오류를 낸다: 문자열 `"201"`, `201.0`, `1.5`, `2e2`, `true`, 배열.
- Long·Integer 범위를 넘는 정수도 JSON 오류다.
- 범위 끝 값인 `Long.MAX_VALUE`·`Integer.MAX_VALUE`는 그대로 읽는다.

**Bean Validation 선언**
- null 목록·빈 목록·101개·null 항목·0·음수는 위반이다. 1개·100개는 통과한다.
- 요청 DTO에는 `workerId`·`employerId` 성분이 없다.

**응답 보관 codec**
- encode → decode 후에도 ID·회차·상태·버전·접수 시각이 같다.
  - 보관 JSON 원문이 `"2026-12-01T10:02:03.456789+09:00"`이다. `Z`나 UTC 시각으로 바뀌지 않는다.
  - 마이크로초 0인 시각과 `-05:00` offset도 그대로 유지된다.
- 보관 JSON 필드는 `jobPostingId`·`applications`와 항목 6개뿐이다. worker·csrf·token·session 문자열은 없다.
- 응답 목록은 불변 복사본이다.
- [특성화] 기본 `JsonMapper`로 직렬화해도 `@JsonFormat` 덕분에 +09:00이 유지된다.

### 5-2. 지원 서비스·엔티티 (`JobApplicationServiceTest`, `ApplicationEntityTest`)
고정 Clock은 UTC `2026-12-01T01:02:03.456789123Z`이다. 저장값은 `…03.456789`, 응답은 `10:02:03.456789+09:00`이다. 일정 201은 UTC 12-10 00:00에 시작한다.

**정상 접수**
- 날짜별로 PENDING 지원을 저장한다.
  - 1회차, current=true, 조회한 일정 버전을 그대로 기록한다.
  - `appliedAt`·`appliedAtUtc` 모두 UTC 마이크로초 값이다.
  - 날짜마다 별도 ID가 생긴다.
- 응답은 공고 ID와, 일정 ID 오름차순으로 정렬한 항목이다.
- 성공 기록에 다음을 저장한다: 소문자 키, 공고, 지문, 최초 응답 원문, UTC 생성 시각. 만료 필드는 없다.
- 일정은 ID 오름차순으로 조회한다. 모든 조회·검증이 끝난 뒤 지원 저장 1회 → 성공 기록 저장 순서로 진행한다.
- 확정 2명·정원 3명이면 남은 자리가 있으므로 접수한다.

**시작 시각 경계**
- 시작 1µs 전, 그리고 나노초를 절삭해도 시작 전이면 허용한다.
- 정확히 시작 시각이거나 그 이후면 409 `SCHEDULE_STARTED`다.
- 둘째 날짜만 시작됐어도 전체를 거절하고 아무것도 저장하지 않는다.

**권한**
- 키·일정 검증 실패는 400이다. 이때 회원·잠금·성공 기록을 조회하지 않는다.
- 회원이 없거나 고용주·`ADMIN` 역할이면 AccessDenied다. 이때 잠금·성공 기록·공고를 조회하지 않는다.
- 근로자 잠금 행이 없으면 503이다.

**공고·일정 조건** (모두 저장 없이 전체 거절)
- 없는 공고는 404 `JOB_POSTING_NOT_FOUND`다.
- 없는 일정이나 다른 공고의 일정은 404 `WORK_SCHEDULE_NOT_FOUND`다. 앞 날짜도 저장하지 않는다.
- 409 오류

| 코드 | 조건 |
|---|---|
| `RECRUITMENT_PAUSED` | PAUSED |
| `RECRUITMENT_CLOSED` | CLOSED |
| `RECRUITMENT_CANCELLED` | REMOVED |
| `SCHEDULE_STATUS_UNAVAILABLE` | 상태 null |
| `CAPACITY_FULL` | 확정 인원이 정원과 같거나 많음 |
| `SCHEDULE_TERMS_CHANGED` | 한 날짜라도 버전 불일치 |
| `TIME_ZONE_UNAVAILABLE` | 시간대 null·공백·`Seoul`·`GMT+99` |
| `SCHEDULE_START_TIME_UNAVAILABLE` | 시작 시각 누락, 또는 서머타임 공백·중복 현지 시각 |

**기존 지원과 재지원**
- 새 키에 PENDING 기존 지원이 섞이면, 새 날짜가 있어도 전체 409 `ALREADY_APPLIED`이고 저장하지 않는다.
- 미선정 1회차에 재지원하면
  - 과거 행은 상태·시각·회차·버전·ID를 유지하고 current만 false가 된다.
  - 새 ID·2회차·current=true인 행이 생긴다.
- 저장 순서는 과거 current 해제 flush → 새 지원 저장 → 성공 기록 저장이다.
- 취소된 확정 근무에 연결된 SELECTED 회차는 재지원할 수 있다. 과거 행의 상태는 그대로다.
- 409 `ALREADY_CONFIRMED`
  - 차단 근무 조회 결과가 있을 때 (mock 값). SQL 의미는 5-4의 선언 확인 참고.
  - SELECTED 표시만 있고 확정 근무가 없을 때.
- 알 수 없는 상태는 409 `APPLICATION_STATUS_UNAVAILABLE`다.
- 앞 날짜가 재지원 대상이고 뒤 날짜가 버전 불일치나 시작 시각 도달로 실패하면, 앞 회차의 current를 바꾸지 않는다.
- 회차 상한·집계 이상
  - 최대 회차가 `Integer.MAX_VALUE`면 409 `APPLICATION_ROUND_EXHAUSTED`다.
  - 음수 집계면 503이다.
  - 다음 회차는 전체 최대 회차 + 1이다 (4 → 5).

**재요청**
- 같은 키·같은 내용이면 `replayed=true`와 최초 결과를 돌려준다.
  - 키 대소문자와 일정 순서가 달라도 같은 내용으로 본다.
  - 공고·일정·현재 지원을 조회하지 않고, 지원·성공 기록도 새로 저장하지 않는다.
- 그사이 마감·버전 변경·시작 시각 경과·공고 삭제가 있어도 최초 결과를 그대로 유지한다.
- 같은 키에 다른 날짜·버전·공고를 보내면 409 `IDEMPOTENCY_KEY_CONFLICT`다.
- 재요청에서도 DB 역할을 먼저 검사한다. 근로자가 아니면 성공 기록을 읽지 않는다.
- 성공 기록은 근로자 ID와 키로 찾는다. 그래서 다른 근로자의 같은 키는 새 요청으로 처리된다.
- [특성화] 보관 응답이 손상돼 공고 ID가 다르면 `IllegalStateException`이 나고 500으로 처리된다.

**잠금 순서·예외 전파** (Mockito `inOrder`)
- 순서는 다음과 같다.
  1. 역할 확인
  2. 잠금 행 준비(`ensureExists`)
  3. 근로자 잠금
  4. 성공 기록 확인
  5. 공고 잠금
  6. 일정 ID 오름차순으로 일정 조회와 현재 지원 잠금
- 성공 기록 확인이 모집 조건 검사보다 먼저다.
- 지원 저장이나 성공 기록 저장에서 난 `DataIntegrityViolationException`은 삼키지 않고 전파한다. 지원 저장이 실패하면 성공 기록을 저장하지 않는다.
- 선언 확인: `@Transactional(READ_COMMITTED)`, Clock 한정자 `jobClock`.

**엔티티**
- 새 지원은 PENDING·current=true이고, 두 시각 모두 UTC 값이다.
- 0 이하 인자나 null 시각은 `IllegalArgumentException`이다.
- 해제는 current만 바꾼다. 공개 변경 메서드는 `retireForReapplication` 하나뿐이다.
- 성공 기록은 모든 컬럼이 `updatable=false`이고, 공개 변경 메서드가 없다. 만료·삭제·상태 필드도 없다.
- 선언 확인
  - 근로자 요청 잠금·공고·현재 지원에 `PESSIMISTIC_WRITE`가 있다.
  - 잠금 행 준비 SQL은 `insert … on duplicate key update`다.

**검증 수준 주의**
- Mockito 호출 순서와 어노테이션 확인은 다음을 입증하지 않는다.
  - 실제 행 잠금 대기, 같은 키의 동시 요청 직렬화
  - UNIQUE(현재 회차·회차 번호·요청 키) 위반
  - 롤백
- 직접 생성한 서비스에는 트랜잭션이 없다. 그래서 예외 뒤 엔티티 값이 원복되지 않아도 DB 롤백 결함의 근거로 보지 않았다.
- 근로자 잠금 행 준비(`ensureExists`)는 정책 안내대로 업무 검증보다 먼저 호출된다.

### 5-3. API·오류 응답 (`JobApplicationApiTest`, standalone MockMvc)

**정상 응답**
- 신규는 201, 재요청은 200이다. 모두 no-store다.
- 서비스에는 principal ID(5), 경로 공고 ID, 헤더 키, 일정 목록이 전달된다.
- 응답 필드는 정해진 목록과 같다. `appliedAt`은 `"…10:02:03.456789+09:00"`이다.
- 응답에 workerId·csrf·token이 없다.
- 쿼리 `workerId`/`employerId`나 헤더 `X-Worker-Id`를 보내도 principal ID만 쓴다.
- 본문에 `workerId:99`를 넣어도 99로 호출되지 않는다. 다만 상태가 201인지 400인지는 단정하지 않았다 (O-6).

**요청 형식 오류** (모두 400 `INVALID_REQUEST`, 서비스 호출 없음)
- 키 누락, 키 두 개 (헤더 중복)
- 깨진 JSON, 빈 본문
- 문자열·소수·범위 초과 정수
- null 목록·빈 목록·null 항목·0 값, 101개
- 숫자가 아니거나 범위를 넘는 공고 ID

**미디어 타입**
- `text/plain` 요청은 415이고 서비스를 호출하지 않는다.
- 응답 본문과 no-store 여부는 O-1 참고.

**오류 매핑** (16가지)

| 상태 | 원인 |
|---|---|
| 400 | `INVALID_REQUEST` |
| 403 | `AccessDeniedException` → `WORKER_ROLE_REQUIRED` |
| 404 | 공고 없음, 일정 없음 |
| 409 | 버전 변경, 키 충돌, 이미 지원, `DataIntegrityViolationException` → `APPLICATION_CONFLICT` |
| 503 | 서비스 503, DB 연결 실패, 잠금 획득 실패, 조회 시간 초과, 트랜잭션 생성 실패, 트랜잭션 시간 초과 |
| 500 | `IllegalStateException`, 일반 예외 |

- 모든 오류 본문은 `code`·`message`뿐이고 no-store다.
- 예외 메시지에 넣은 JDBC URL·비밀번호·이메일·전화번호는 응답에 나오지 않는다.
- principal이 없으면 403이다. 이것은 standalone에서의 컨트롤러 방어 동작이다. 실제 체인에서 익명 요청이 401이 되는지는 검증하지 않았다.

### 5-4. 기존 조회·집계 변경
- **선언 확인** (`ApplicationRoundQueryDeclarationTest`, SQL 문자열만 확인)
  - APP-01 `findWorkerApplications`는 `app.is_current = 1`과 본인 `worker_id`로 제한한다.
  - JOB-05 지원자 수는 `count(distinct application.worker_id)`다.
  - 확정 인원은 `change_type <> 'CANCELLED'`일 때만 센다. 미취소와 EARLY_ENDED는 포함한다.
  - 정원 판단(`countOccupyingAssignments`)은 확정 근무만 센다. PENDING 지원 행이나 CANCELLED 근무는 세지 않는다.
- **기존 테스트 실행** (Repository mock 기준)
  - 집계가 누락되면 0으로 처리하고, 기존 응답은 유지된다.
  - `JobPostingQueryServiceTest` 24, `JobPostingQueryServiceMissingCountsTest` 1, `MyJobPostingApiTest` 3, `WorkerJobPostingQueryServiceTest` 61, `WorkerJobPostingApiTest` 20 — 모두 통과했다.
- **증명하지 않은 것**: 실제 MySQL의 JOIN·DISTINCT·is_current 필터·projection·정렬 결과는 실행 검증하지 않았다.

### 5-5. demo API·시연 준비 (`DemoJobApplicationServiceTest`, `DemoJobApplicationApiTest`)

**본인 이력**
- 세션 근로자 ID와 공고 ID로 조회한다. 쿼리로 보낸 `workerId=99`는 무시된다.
- 그대로 매핑하는 값
  - 과거·현재 회차 (`currentFlag` 1/0을 boolean으로)
  - 접수 버전 (null 포함)
  - 확정·취소 연결
- 이력이 없으면 빈 목록이다.
- 오류
  - 없는 공고는 404다.
  - 0 이하 ID는 회원을 조회하기 전에 400이다.
  - 고용주이거나 없는 회원이면 AccessDenied이고, 공고·이력을 조회하지 않는다.

**시연 준비**
- 고정 Clock은 UTC 11-30 15:30 = GMT+9 12-01 00:30이다. 날짜가 GMT+9 기준으로 넘어가는 경계를 일부러 골랐다.
- 새로 준비하면 다음을 만든다.
  - 가상 공고: demo-employer 소유, GMT+9, 정원 3, 표식 `[APP-02 시연·근로자 5·2026-12-01]`
  - 일정 3개: 12-08·09·10, 09~17시, OPEN, 버전 1
- 미선정 1회차 INSERT는 새로 만든 셋째 일정 ID에만 실행된다. 시각은 서버 시각 1시간 전 UTC 마이크로초다.
- 같은 근로자가 같은 날 이미 준비했다면 기존 공고를 반환한다(200, created=false). 저장이나 INSERT는 없다.
- 근로자마다 별도 표식을 쓴다.
- 순서는 근로자 확인 → 요청 잠금 → demo-employer 확인 → 표식 조회다.
- 오류
  - demo-employer가 없거나 고용주가 아니면 409 `DEMO_EMPLOYER_UNAVAILABLE`이고 아무것도 만들지 않는다.
  - 근로자가 아니면 잠금·생성 전에 거절한다.
  - 잠금 실패나 INSERT 행 수가 1이 아니면 503이다.

**API 응답 형태**
- 화면은 200 `text/html`이다. 이력·준비 응답은 모두 no-store다.
- 준비 결과가 기존 공고면 200이다.
- 오류

| 상태 | 원인 |
|---|---|
| 403 | 고용주 |
| 400 | 잘못된 ID |
| 404 | 없는 공고 |
| 503 | DB 오류 |
| 409 | 준비 충돌 (`DEMO_FIXTURE_CONFLICT`) |
| 500 | 그 밖의 예외 |

- 예외 메시지는 노출되지 않는다.

**선언 확인 (실행 아님)**
- 컨트롤러·서비스·저장소·오류 처리기 모두 `@Profile("farm-demo")`다.
- 이력 SQL은 본인과 공고로 제한하고, `id desc`·`limit 500`이다.
- 저장소의 변경 쿼리는 `insertNotSelectedFixture` INSERT 하나뿐이다. UPDATE·DELETE는 없다.
- SecurityConfig
  - farm-demo 분기: 화면은 permitAll, 이력 GET과 준비 POST는 `hasRole("WORKER")`
  - 분기 밖: 지원 POST는 `hasRole("WORKER")`
  - CSRF 예외 선언은 없다.
- 기본 프로필에서의 차단, CSRF, JPA INSERT의 실제 실행은 검증하지 않았다. fixture를 실제로 생성하거나 개발 DB를 변경하지도 않았다.

### 5-6. demo 화면 (Node 18 + Java 정적 3)

**정적 검사** (Java `JobApplyDemoPageTest`)
- 지원 화면 스크립트는 다음을 쓰지 않는다: `innerHTML`, 저장소, `console`, `eval`, `Authorization`, `workerId`/`employerId` 필드.
- 키는 `crypto.randomUUID()`로 만들고, 버전은 조회한 일정 값을 그대로 쓴다. `no-referrer` 메타가 있다.
- 로그인 화면의 지원 링크는 기본 숨김이고, 근로자일 때만 표시한다.
- 공고 목록·상세 화면에 지원 링크가 있고, 상세 링크는 `jobPostingId`를 인코딩한다.

**스크립트 동작** (Node, 원문 실행. 가짜 서버가 접수 정책을 단순화해 흉내 냄)

- 초기 표시
  - /me → 목록 → /me만 호출한다. 시연 준비 POST나 상세는 자동으로 요청하지 않는다.
  - `?jobPostingId`가 있으면 상세와 이력을 조회한다. 지원할 수 있는 날짜만 체크박스가 활성화된다.
- 일반 지원
  - 순서는 CSRF 조회 → /me → POST → /me → 상세·이력 재조회다.
  - POST 헤더는 `Content-Type: application/json`, UUID v4 키, CSRF 헤더다.
  - 본문은 일정 ID 오름차순이고, 조회한 버전을 그대로 쓴다.
  - 201 안내와 함께 이력을 재조회한 결과를 표시한다.
  - CSRF 토큰은 화면·요청 기록 어디에도 없다. 금지 API 접근 기록도 0건이다.
- 재전송
  - 같은 키·같은 본문 문자열로 보내 200을 받고, "모두 유지됐습니다"를 표시한다. 그사이 서버 시각과 버전이 바뀌어도 마찬가지다.
  - 재요청 결과의 ID나 시각(Z로 바뀐 경우 포함)이 최초 결과와 다르면 유지 성공으로 표시하지 않는다.
- 요청만 준비
  - POST하지 않고 키와 이전 버전을 보관한다.
  - 서버 버전이 바뀐 뒤 지원하면 보관한 버전으로 보내 409 `SCHEDULE_TERMS_CHANGED`를 받는다.
  - 그 뒤에는 지원·준비 버튼이 비활성화되고 준비 내용이 초기화된다.
  - 최신 조건을 다시 조회한 뒤 지원하면 새 키·새 버전으로 201을 받는다.
  - 선택을 바꾸면 준비 내용이 초기화된다.
- 시연 준비
  - 버튼을 눌러야만 CSRF와 함께 POST한다.
  - 재지원 뒤 이력은 서버 결과대로 표시한다. 예: 203 2회차 "현재", 1회차 "미선정·과거".
  - 다시 누르면 기존 공고 안내를 표시한다.
- 선택 개수: 최대 100개다. 101번째 선택은 해제하고 안내하며, 전송 본문도 100개다.
- 중복 클릭: POST 대기 중에는 지원·재전송·준비·시연 준비 버튼을 다시 눌러도 추가 요청이 없다.
- 통신 실패
  - 성공으로 표시하지 않고, "자동 재전송하지 않습니다"를 안내한다.
  - 사용자가 재전송을 누를 때만 같은 키로 보낸다.
  - CSRF 조회가 실패하거나 형식이 맞지 않으면 POST를 보내지 않는다.
- 응답 이상
  - 409 `ALREADY_APPLIED`는 상태코드·코드·메시지를 표시하고, 기록 표에 남긴다. 성공으로 표시하지 않는다.
  - 201이어도 응답 항목이 요청과 다르면(SELECTED, 버전 불일치, 항목 누락, 공고 불일치) 성공으로 표시하지 않는다.
- 계정 변경·화면 이탈
  - POST 응답 뒤 계정이 바뀌면 결과를 표시하지 않는다. 이전 키·재전송·목록·날짜도 지운다.
  - 비로그인이나 고용주는 목록을 조회하지 않는다.
  - pagehide 뒤 도착한 응답은 표시하지 않는다.
- 안전한 표시: 공고명·작업지의 HTML 문자열은 `textContent`로 문자 그대로 표시된다.

**검증 수준 주의**: 실제 브라우저의 `crypto.randomUUID`·렌더링·쿠키·CSRF 검증·실제 서버 응답은 실행하지 않았다.

## 6. 발견 결함과 관찰 사항

### 결함
기능 코드 결함은 발견하지 않았다.

### 관찰 (결함 아님, 정책 확인 권장)

| ID | 내용 | 근거 | 관련 코드 |
|---|---|---|---|
| O-1 | `application/json`이 아닌 요청은 415다. `ApplicationErrorHandler.invalidContentType()`이 있지만 standalone에서는 적용되지 않았다. 응답 본문이 비어 있고 `Cache-Control`도 없다. 이유는 핸들러 선택 전에 예외가 나서 `assignableTypes` advice 범위 밖이기 때문으로 보인다. 실제 서버에서는 Spring Security 기본 캐시 헤더나 Boot 오류 응답이 붙을 수 있지만 확인하지 않았다. | `JobApplicationApiTest$Invalid.unsupportedMediaTypeBody` (특성화) | `application/exception/ApplicationErrorHandler.java:32-35` |
| O-2 | 공고 시간대나 일정 시작 시각을 해석할 수 없으면 503이 아니라 409(`TIME_ZONE_UNAVAILABLE`·`SCHEDULE_START_TIME_UNAVAILABLE`)다. 근무 완료 API는 같은 상황에 503을 쓴다. | `JobApplicationServiceTest$Conditions.invalidZone`·`unresolvableStart` | `application/service/JobApplicationService.java:162-175` |
| O-3 | 보관 응답이 손상되면(공고 ID 불일치) `IllegalStateException` → 500이다. | `JobApplicationServiceTest$Replay.corruptedReceipt` (특성화) | `JobApplicationService.java:77-78` |
| O-4 | 새 지원 행은 기존 컬럼 `applied_at`에도 UTC 값을 저장한다. 코드 주석에 명시된 의도다. 다만 과거 행의 `applied_at`을 다른 시간대로 해석하는 소비자가 있다면 섞일 수 있다. | `JobApplicationServiceTest$Success.storesPendingPerDate` | `application/entity/JobApplication.java:27-31,48-49` |
| O-5 | 화면: 409 `SCHEDULE_TERMS_CHANGED` 뒤에도 "같은 키 재전송" 버튼은 활성 상태다. 누르면 이전 버전 요청을 그대로 다시 보낸다. 서버는 실패를 성공 기록으로 남기지 않으므로 다시 409가 되고, 가짜 성공은 생기지 않는다. | 스크립트 읽기 검토 (`controls()`의 `retry` 조건) | `farm-job-apply.html` `controls()`·`send()` |
| O-6 | 본문에 알 수 없는 필드(`workerId`)가 있을 때의 상태(무시해서 201 / 400)를 단정하지 않았다. 어느 쪽이든 소유자는 바뀌지 않는다. Spring Boot `JsonMapper` 설정에 따라 결과가 다를 수 있다. | `JobApplicationApiTest$Success.bodyOwnerIgnored` | `application/api/JobApplicationApi.java` |

## 7. 실행하지 않은 검증 범위

| 범위 | 상태 |
|---|---|
| V12 마이그레이션 실제 적용, 기존 행 보존(1회차·current·버전 NULL), `uq_application_current`(생성 컬럼)·`uq_application_round`·`uq_application_receipt_worker_key`, CHECK·FK | 미실행. `FlywayMigrationTests`는 통합 태그. 마이그레이션 적용·DB 접근 없음 |
| 실제 MySQL 트랜잭션: 근로자·공고·현재 지원 `FOR UPDATE` 대기, 같은 키 동시 요청 직렬화, 다른 근로자·고용주 수정과의 경합, 데드락 부재, 롤백, READ_COMMITTED | 미실행. 호출 순서와 선언만 확인 |
| 네이티브 SQL 결과: 차단·취소 확정 근무, 정원, 지원자 DISTINCT, is_current 필터, 이력 정렬·LIMIT, projection 변환, `on duplicate key update` | 미실행. 문자열 선언만 확인 |
| `DATETIME(6)` 저장과 JDBC 시간대 변환 | 미실행 |
| 전체 Security 체인: 익명 401, 고용주 403 JSON 핸들러, CSRF 누락·불일치 403, 세션 격리, farm-demo 비활성 시 경로 차단 | 미실행. SecurityConfig는 읽기 검토와 문자열 선언 확인만 함 |
| Spring Boot `JsonMapper` 설정의 요청·응답 변환 (알 수 없는 필드 처리 포함) | 미실행. 기본 빌더 기준 |
| 실제 브라우저·서버 기동·Docker·개발 DB·fixture 실제 생성 | 실행하지 않음 |

## 8. 기존 결과와의 구분
- **사용자 수동 확인 (별도 근거, 합산하지 않음)**: 사용자가 farm-demo에서 다음을 확인했다고 전달받았다. 이번 자동 실행으로 재현하거나 검증한 것이 아니다.
  - 신규 지원 201
  - 같은 요청 재전송 200
  - 조건 버전 충돌 409
  - 재지원 2회차
- 이 보고서의 숫자(Java 310개, Node 18개)는 이번 실행 결과다. 다른 보고서의 숫자와 합산하지 않는다.
  - 예: `2026-11-27-work-completion`의 Java 288개·Node 23개, `2026-11-27-insurance-salary-account`의 결과
  - 기존 테스트 10개 클래스를 이번에도 실행했지만, 과거 실행과 별개인 이번 결과다.
- 실제 `.env`·키·서비스 토큰은 읽거나 출력하지 않았다. CSRF 토큰·UUID·회원·공고는 모두 테스트의 가상 값이다.

## 9. 보존 확인

| 대상 | 결과 |
|---|---|
| `src/main`·Gradle 파일·`gradle/`·`.github` (187개 파일) | sha256이 실행 전후 동일 |
| `src/test` 기존 파일 | 해시 동일. 추가된 것은 신규 테스트 10개 파일뿐 |
| 저장소 `git diff` | 해시 동일 |
| 브랜치 | `app-apply` 유지. 커밋·푸시·stash·restore 없음 |

## PR 요약 (붙여넣기용)

> **APP-02 지원 API·farm-demo 단위 검증**
> - Java 310/310 통과 (신규 166 + 기존 직접 영향 144). Node 화면 스크립트 18/18 통과.
> - Java 1차 컴파일 오류와 2차 실패 7건은 모두 테스트 코드 오류였다(Iterable 매처, Long 맵 키). 기대값은 바꾸지 않았다.
> - 기능 결함은 없다. 관찰 6건: 415 응답 형식, 시간대 오류 409, 손상된 보관 응답 500, `applied_at` UTC, 버전 충돌 후 재전송 버튼, 알 수 없는 본문 필드.
> - 범위 밖: 실제 MySQL 잠금·동시성·롤백, V12 제약, SQL 실행, 전체 Security 체인(401·CSRF·세션).
> - 사용자 수동 확인 4건은 별도이며 합산하지 않았다.
