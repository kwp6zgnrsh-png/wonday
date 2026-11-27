# 근무 완료 기능 단위 검증 보고서 (2026-11-27)

- 저장소: `C:\Users\HCB\Desktop\wonday` · 브랜치 `work-completion` · 기준 커밋 `3261c65` (작업 트리에 미커밋 기능 변경 포함)
- AGENTS.md: 저장소 루트·`farm-api` 모두 없음
- 검증 대상: `POST /api/work-assignments/{assignmentId}/completion`, 취소·조기 종료 저장 경계와의 연결, 보험·근로자 demo 상세의 `completedAt`, 고용주 완료 demo(`demo/completion`), demo 화면 3종
- 검증자는 기능 코드·HTML·설정·마이그레이션·Gradle·CI를 수정하지 않았다. 커밋·푸시·브랜치 변경 없음.

## 1. 결론 요약

| 구분 | 결과 |
|---|---|
| Java 단위 테스트 (수정 후 재실행) | **288 / 288 통과**, 실패 0, 오류 0, 건너뜀 0 (신규 134 + 기존 직접 영향 154) |
| Node 화면 스크립트 테스트 (수정 후 재실행) | **23 / 23 통과** |
| 발견 결함 (기능 코드) | **없음** |
| 관찰 사항 | 4건 (O-1 ~ O-4, 결함 아님 · 정책 확인 권장) |

최초 실행에서 Java 1건, Node 1건이 실패했다. 모두 **검증자가 작성한 테스트 코드의 오류**였고 기대값은 바꾸지 않았다(4장).
결과가 모두 통과했지만, 이번 실행은 Repository mock·standalone MockMvc·가짜 DOM 수준이다. 실제 MySQL 트랜잭션·행 잠금·V11 제약·전체 Security 체인은 검증하지 않았다(7장).

## 2. 추가·수정한 파일

### 신규 테스트 (검증자 작성)

| 파일 | 테스트 수 | 검증 수준 |
|---|---:|---|
| `src/test/java/com/wonday/farm/work/service/WorkCompletionServiceTest.java` | 41 | 서비스 단위 (Repository mock, 고정 Clock) |
| `src/test/java/com/wonday/farm/work/entity/WorkAssignmentCompletionTest.java` | 11 | 엔티티 단위 + 매핑·잠금 선언 확인 |
| `src/test/java/com/wonday/farm/work/api/WorkCompletionApiTest.java` | 27 | standalone MockMvc (principal 직접 주입) |
| `src/test/java/com/wonday/farm/change/service/WorkChangeRecorderCompletionTest.java` | 11 | 저장 경계 단위 (Repository mock) |
| `src/test/java/com/wonday/farm/insurance/service/WorkAssignmentCompletionResponseTest.java` | 9 | 실제 조회 서비스 + Repository mock, standalone MockMvc JSON |
| `src/test/java/com/wonday/farm/demo/completion/DemoWorkCompletionQueryServiceTest.java` | 15 | 서비스 단위 (Repository mock) |
| `src/test/java/com/wonday/farm/demo/completion/DemoWorkCompletionApiTest.java` | 17 | standalone MockMvc + 프로필·SQL·SecurityConfig 선언 확인 |
| `src/test/java/com/wonday/farm/demo/completion/WorkCompletionDemoPageTest.java` | 3 | HTML·스크립트 정적 문자열 검사 |
| `docs/verification/2026-11-27-work-completion/work-completion-page.test.mjs` | 23 (Node) | 인라인 스크립트 원문을 `node:vm` + 가짜 DOM·fetch로 실행 |

### 수정한 기존 테스트
- **없음.** 기존 테스트 파일의 해시는 실행 전후로 같다.
- 다만 작업 트리에는 기능 작성자가 이미 수정한 기존 테스트 4개가 있다. 검증자는 이 파일들을 바꾸지 않았다.
  - `WorkChangeRecorderTest`: `findOwned` stub을 `findByIdForUpdate` stub으로 교체하고 호출 순서를 갱신했다.
  - `DemoWorkAssignmentApiTest`, `InsuranceIntegrationApiTest`: `WorkAssignmentResponse` 생성자에 `completedAt` 인자 `null`을 추가했다.
  - `FlywayMigrationTests`: 통합 태그라서 이번 범위에서 실행하지 않았다.
- 취소·조기 종료 연결 검증은 기존 `WorkChangeRecorderTest`를 고치지 않고 별도 클래스 `WorkChangeRecorderCompletionTest`로 추가했다.

## 3. 실행 명령과 선택한 테스트 클래스

환경: `JAVA_HOME=…/jdk-21.0.12.1+1`, 작업 폴더 `farm-api`. 전체 `test`·`fullTest`·`integrationTest`·커버리지 태스크는 실행하지 않았다.

```
.\gradlew.bat --no-daemon unitTest
  --tests "com.wonday.farm.work.service.WorkCompletionServiceTest"
  --tests "com.wonday.farm.work.entity.WorkAssignmentCompletionTest"
  --tests "com.wonday.farm.work.api.WorkCompletionApiTest"
  --tests "com.wonday.farm.change.service.WorkChangeRecorderCompletionTest"
  --tests "com.wonday.farm.insurance.service.WorkAssignmentCompletionResponseTest"
  --tests "com.wonday.farm.demo.completion.DemoWorkCompletionQueryServiceTest"
  --tests "com.wonday.farm.demo.completion.DemoWorkCompletionApiTest"
  --tests "com.wonday.farm.demo.completion.WorkCompletionDemoPageTest"
  --tests "com.wonday.farm.change.service.WorkChangeRecorderTest"
  --tests "com.wonday.farm.insurance.service.WorkAssignmentQueryServiceTest"
  --tests "com.wonday.farm.insurance.service.WorkAssignmentCoordinatesTest"
  --tests "com.wonday.farm.insurance.api.InsuranceIntegrationApiTest"
  --tests "com.wonday.farm.demo.assignment.DemoWorkAssignmentApiTest"
  --tests "com.wonday.farm.demo.JobLocationDemoPageTest"
  --tests "com.wonday.farm.demo.SalaryAccountDemoPageTest"
  --tests "com.wonday.farm.demo.insurance.WorkerVerificationDemoPageTest"
  --tests "com.wonday.farm.demo.insurance.InsuranceSalaryAccountDemoPageTest"
```

```
node --test docs/verification/2026-11-27-work-completion/work-completion-page.test.mjs
```

### 직접 영향받는 기존 테스트 선택 근거

| 기존 테스트 | 선택 이유 |
|---|---|
| `WorkChangeRecorderTest` | `WorkChangeRecorder`의 잠금 순서·조회 메서드 변경 |
| `WorkAssignmentQueryServiceTest`, `WorkAssignmentCoordinatesTest` | `WorkAssignmentQueryService`·`WorkAssignmentResponse`에 `completedAt` 추가 |
| `InsuranceIntegrationApiTest`, `DemoWorkAssignmentApiTest` | `WorkAssignmentResponse` 생성자 변경 |
| `JobLocationDemoPageTest` | `farm-job-location.html` 변경 |
| `SalaryAccountDemoPageTest`, `WorkerVerificationDemoPageTest`, `InsuranceSalaryAccountDemoPageTest` | `SecurityConfig.java`·`farm-login.html` 문자열을 읽는 정적 검사 |

### 실행 범위 확인 (XML)
- `build/test-results/unitTest/`의 XML 48개는 모두 위 17개 클래스와 그 @Nested 클래스에 속한다.
- 소스의 @Nested 클래스 목록과 XML 목록을 클래스별로 대조했다. 누락은 없다.

## 4. 실행 결과 (최초 실행과 재실행 구분)

### Java 단위 테스트

| 회차 | 테스트 | 통과 | 실패 | 오류 | 건너뜀 | 시간 |
|---|---:|---:|---:|---:|---:|---|
| 최초 실행 | 288 | 287 | 1 | 0 | 0 | BUILD FAILED in 1m 1s (측정 62초) |
| 수정 후 재실행 | 288 | **288** | 0 | 0 | 0 | BUILD SUCCESSFUL in 27s (측정 28초) |

최초 실행 실패는 `DemoWorkCompletionApiTest$Errors.dataErrors`의 테스트 코드 오류였다.
- 원인: 이미 `willThrow`로 stub한 `repository.findForEmployer(anyLong())`를 `given(...)` 안에서 다시 호출했다. 그래서 stub을 등록하는 시점에 예외가 났다.
- 수정: 기대값(503·503·500)은 그대로 두고, 순차 stub `willThrow(a, b, c)` 한 번으로 바꿨다.

재실행 결과 중 신규 테스트와 기존 직접 영향 테스트는 다음과 같다.

| 구분 | 클래스 | 테스트 | 결과 |
|---|---:|---:|---|
| 신규 | 8 | 134 | 134 통과 |
| 기존 직접 영향 | 9 | 154 | 154 통과 (기존 테스트 수정 없음) |

### Node 화면 스크립트 테스트 (Java와 별도 집계)

| 회차 | 테스트 | 통과 | 실패 | 원인 |
|---|---:|---:|---:|---|
| 1차 | 23 | 22 | 1 | 테스트 코드 오류 1: `confirm` 기록 항목에는 `options`가 없는데 `c.options.method`를 읽음 |
| 2차 | 23 | 22 | 1 | 테스트 코드 오류 2: vm 컨텍스트에서 만든 객체를 `deepStrictEqual`로 비교해 prototype이 달라 실패. 값은 같았음 |
| 3차 (최종) | 23 | **23** | 0 | — (duration 약 131ms) |

- 1차 실행 시간은 약 249ms다. 2차 실행 시간은 기록하지 않았다.
- 오류 1은 `c.options?.method`로 고쳤다. 오류 2는 JSON 왕복 비교로 고쳤다.
- 두 수정 모두 기대값(헤더 `{ 'X-CSRF-TOKEN': 토큰 }`)은 바꾸지 않았다.
- 가짜 DOM의 `remove()` 구현도 단순화했다. 기능 스크립트와는 무관하다.

## 5. 항목별 검증 내용과 수준

### 5-1. 완료 서비스·엔티티 (`WorkCompletionServiceTest`, `WorkAssignmentCompletionTest`)
고정 Clock을 쓰고, Repository는 mock이다. 픽스처 약정은 GMT+9 2026-11-30 17:00(UTC 08:00)에 끝난다.

**정상 완료**
- 서버 시각(UTC)과 세션 고용주 ID를 저장한다.
- 응답은 `assignmentId`와, 약정 시간대(+09:00)로 표시한 `completedAt`이다.
- 나노초는 마이크로초로 절삭해 저장한다. 예: `…30.123456789Z` → `…30.123456`.
- 다른 시간대 약정도 확인했다. `GMT-5`는 -05:00, `Asia/Seoul`은 +09:00으로 응답한다.

**종료 시각 경계**
- 종료 1마이크로초 전, 그리고 절삭해도 종료 전인 `…59.999999999Z`는 409 `WORK_NOT_FINISHED`이고 저장하지 않는다.
- 정확히 종료 시각이거나 그 이후면 완료할 수 있다.
- 판단 기준은 저장된 `agreedEndAt`이다. 테스트에서 이 값을 늦추면 같은 시각의 요청도 거절된다.

**권한·소유권**
- ID 0·음수·`Long.MIN_VALUE`는 400 `INVALID_REQUEST`이고, 저장소를 호출하지 않는다.
- DB 역할을 다시 확인해 거절한다. 없는 회원, 근로자, 역할 코드 `ADMIN`, 세션 이후 DB 역할이 바뀐 경우 모두 `AccessDeniedException`이다.
- 없는 근무, 그리고 다른 고용주의 근무는 같은 404 `WORK_ASSIGNMENT_NOT_FOUND`다. 이때 잠금 조회는 하지 않는다.
- 소유권 확인 뒤 잠금 조회에서 근무가 사라지면 404다.

**취소·조기 종료**
- 취소 기록이 있으면 409 `WORK_ALREADY_CANCELLED`, 조기 종료 기록이 있으면 409 `WORK_ALREADY_EARLY_ENDED`다.
- 변경 기록이 있으면 종료 전이라도 이 409가 `WORK_NOT_FINISHED`보다 먼저 나온다.

**저장된 약정 이상 (503)**
- 시간대가 null·공백·`Seoul`·`GMT+99`·`Asia/Nowhere`이면 503이다.
- 종료 시각이 누락됐거나, 서머타임 공백·중복 구간의 현지 시각이면 503이다.
- 완료 시각만 있거나 처리자만 있는 반쪽 상태도 503이고, 어느 값도 덮어쓰지 않는다.

**재요청**
- 나중 서버 시각으로 다시 요청해도 최초 시각·처리자를 그대로 반환하고, 저장값을 덮어쓰지 않는다.
- 완료된 근무라도 다른 고용주의 재요청은 404다.

**다른 데이터 보존**
- 완료 두 필드 외의 엔티티 필드는 모두 그대로다. 리플렉션으로 전후 스냅샷을 비교했다.
- 변경 저장소에는 `findForUpdate`만 호출하고 저장·삭제는 하지 않는다.
- 서비스 필드는 회원·근무·변경 저장소와 Clock뿐이다. 즉 보험 변경 피드(스트림·버전) 의존성이 없다.

**엔티티**
- 중복 완료는 `IllegalStateException`이고, 최초 값을 유지한다. 반쪽 상태도 덮어쓰지 않는다.
- 시각 null이나 처리자 0·음수는 `IllegalArgumentException`이고, 아무 값도 기록하지 않는다.
- 공개 변경 메서드는 `complete` 하나뿐이다. 즉 완료 취소·정정 메서드는 없다.

**선언 확인 (실행 아님)**
- 메서드에 `@Transactional(isolation = READ_COMMITTED)`가 선언돼 있다.
- Clock 한정자는 `workCompletionClock`이고, 설정 Clock은 UTC다.
- `findByIdForUpdate`에는 `@Lock(PESSIMISTIC_WRITE)`와 id 단건 JPQL이 선언돼 있다.
- 컬럼 매핑은 `completed_at_utc`, `completed_by`다.

### 5-2. 취소·조기 종료 저장과의 연결 (`WorkChangeRecorderCompletionTest` + 기존 `WorkChangeRecorderTest`)
- 완료된 근무에 취소·조기 종료를 처음 기록하면 409 `WORK_ALREADY_COMPLETED`다. 사유 정정도 같은 409다.
- 반쪽 완료 상태도 같은 409다.
- 거절될 때는 다음이 모두 일어나지 않는다.
  - `saveAndFlush` 호출
  - `versions.save` 호출
  - 발행 순번 증가 (`lastSequence`가 4 그대로)
  - 변경 행의 버전·사유·`updatedAt` 변경
- 완료 여부는 기존 변경 중복(`WORK_CHANGE_ALREADY_EXISTS`) 검사보다 먼저 판단한다.
- 미완료 근무의 취소 기록과 사유 정정은 기존대로 동작한다. 기존 `WorkChangeRecorderTest` 41개도 모두 통과했다.
- 호출 순서(Mockito `inOrder`)
  - 완료: 역할 확인 → 소유권 → `findByIdForUpdate` → `changes.findForUpdate`
  - 변경 기록·정정: `ensureExists` → `lockWorker` → `findByIdForUpdate` → `changes.findForUpdate` → 저장
  - 변경 기록 경로는 더 이상 `findOwned`를 호출하지 않는다.
- **검증 수준 주의**
  - Mockito 호출 순서는 코드가 잠금 메서드를 그 순서로 부른다는 것만 보여준다.
  - 다음은 증명하지 않는다.
    - 실제 MySQL에서 두 트랜잭션이 같은 행 잠금을 기다리는지
    - 완료와 취소가 동시에 들어올 때 하나만 성공하는지
    - 데드락이 없는지
    - 롤백되는지

### 5-3. 완료 API·오류 응답 (`WorkCompletionApiTest`, standalone MockMvc)

**정상 응답**
- 본문 없는 POST는 200이다. 서비스는 principal의 고용주 ID(10)와 경로 ID(31)로 호출된다.
- 본문이나 쿼리로 `completedBy`·`employerId`·`completedAt`을 보내도 무시된다.
- 응답 JSON은 정확히 `assignmentId`, `completedAt` 두 필드다.
  - `completedBy`나 처리자 ID는 없다.
  - `completedAt`은 `"2026-11-30T17:00:00.123456+09:00"` 형식이다.
  - 헤더는 `Cache-Control: no-store`, 콘텐츠 타입은 `application/json`이다.
- 실제 서비스(Repository mock)와 연결해도 확인했다. UTC 09:00:00.5에 처리하면 응답은 `18:00:00.5+09:00`이다. 경로 ID 0은 400이다.

**잘못된 경로 ID**
- `abc`·`1.5`·`1e3`, 그리고 long 범위를 넘는 값은 400 `INVALID_REQUEST`이고 서비스를 호출하지 않는다.
- 16진수 `0x1F`는 31로 받아들여진다. 이 동작은 관찰 O-3으로 기록했다.

**예외 → 상태코드 매핑 (15가지)**

| 상태 | 원인 |
|---|---|
| 400 | `INVALID_REQUEST` |
| 403 | `AccessDeniedException` → `EMPLOYER_ROLE_REQUIRED` |
| 404 | `WORK_ASSIGNMENT_NOT_FOUND` |
| 409 | 세 코드 (`WORK_ALREADY_CANCELLED`, `WORK_ALREADY_EARLY_ENDED`, `WORK_NOT_FINISHED`) |
| 503 | 서비스 503, DB 연결 실패, 잠금 획득 실패, 조회 시간 초과, 트랜잭션 생성 실패, 트랜잭션 시간 초과 |
| 500 | 무결성 위반, `IllegalStateException`, 일반 예외 |

- 모든 오류 본문은 `code`·`message`뿐이고 `no-store`다.
- 예외 메시지에 넣은 JDBC URL·비밀번호·이메일·전화번호와 `Exception`·`trace` 문자열은 응답에 없다.

**principal 없음·GET**
- standalone에서 principal이 없으면 403이고 서비스를 호출하지 않는다.
  - 이것은 컨트롤러의 방어 동작이다. 실제 체인에서 익명 요청이 401이 되는지는 별개이고 이번에 검증하지 않았다.
- GET은 405다. 핸들러가 선택되기 전의 예외라 이 advice가 적용되지 않는 기본 응답이다.

**보안 설정**
- 다음은 `DemoWorkCompletionApiTest$Declarations`에서 소스 문자열만 읽은 선언 확인이다. 실제 필터 체인에서 실행해 검증하지 않았다.
  - 완료 POST 경로가 farm-demo 분기 밖에 `hasRole("EMPLOYER")`로 선언돼 있다.
  - CSRF 예외(`ignoringRequestMatchers`·`.csrf(`)가 추가되지 않았다.
  - 403 JSON 문구가 있다.

### 5-4. 보험·근로자 demo 상세 (`WorkAssignmentCompletionResponseTest` + 기존 테스트)
- 저장된 UTC 완료 시각을 약정 시간대로 변환한다. 예: `08:30:00.000001` → `17:30:00.000001+09:00`.
- 미완료이거나 V11 이전의 기존 행이면 `completedAt`은 null이다.
  - JSON에는 키가 있고 값이 `null`이다. 보험 내부 API와 근로자 demo API 양쪽 JSON에서 확인했다.
- `completedBy`는 레코드 성분에도 없고 JSON에도 없다.
- 좌표·휴게·일급·근로자 ID 같은 기존 약정 값은 그대로다.
- 완료된 근무라도 다음은 기존처럼 503이다: 필수 약정 누락, 휴게 반쪽, 좌표 반쪽, 해석할 수 없는 시간대.
- 조회는 `findOwned`만 호출한다. 현재 공고나 다른 저장소의 값으로 보완하지 않는다(mock 호출 기준).
- 기존 `WorkAssignmentQueryServiceTest` 25개, `WorkAssignmentCoordinatesTest` 14개, `InsuranceIntegrationApiTest` 33개, `DemoWorkAssignmentApiTest` 21개가 모두 통과했다.
- 반쪽 완료 상태를 조회에서 따로 검사하지 않는 동작은 관찰 O-2로 기록했다.

### 5-5. 고용주 완료 demo 조회 API (`DemoWorkCompletionQueryServiceTest`, `DemoWorkCompletionApiTest`)

**목록·상세 조회**
- 목록과 상세 모두 DB 역할을 다시 확인한다. 근로자, 없는 회원, `ADMIN`은 거절하고 저장소를 조회하지 않는다.
- 세션 고용주 ID가 저장소 조회 인자다. 쿼리로 보낸 `employerId=99`는 무시된다.
- 목록의 다섯 필드를 매핑한다. 근무일은 null이어도 된다. 빈 목록이면 `assignments: []`다.
- 상세 매핑
  - 약정 종료 시각은 `…17:00:00+09:00` 형식이다.
  - `changeType`은 `CANCELLED`·`EARLY_ENDED`·null을 그대로 전달한다.
  - 미완료의 `completedAt`은 JSON null이다. 완료된 근무는 +09:00으로 변환하고 마이크로초를 유지한다.
  - `completedBy`는 없다.

**오류 응답**

| 상태 | 원인 |
|---|---|
| 400 | ID 0·음수·문자·범위 초과 (`INVALID_REQUEST`) |
| 403 | 근로자 (`EMPLOYER_ROLE_REQUIRED`) |
| 404 | 없는 근무·다른 고용주 근무 (`WORK_ASSIGNMENT_NOT_FOUND`) |
| 503 | 시간대 해석 불가·종료 시각 누락·서머타임 공백, DB 연결·잠금 오류 |
| 500 | 그 밖의 예외 |

- 모든 오류는 `no-store`이고, 예외 메시지를 노출하지 않는다.
- 화면 GET은 200 `text/html`, `no-store`다.

**선언 확인 (실행 아님)**
- API·서비스·저장소·오류 처리기 모두 `@Profile("farm-demo")`다. 서비스는 `readOnly` 트랜잭션이다.
- SQL 문자열
  - 목록: `posting.employer_id` 필터, `order by assignment.id desc`, `limit 100`
  - 상세: 고용주와 근무 ID를 함께 필터하고, `work_change`를 left join한다.
  - 두 쿼리 모두 `completed_by`·전화번호·비밀번호를 선택하지 않는다.
- SecurityConfig의 farm-demo 분기에 다음이 선언돼 있다.
  - `/demo/work-completion`은 permitAll
  - 목록·상세 API는 `hasRole("EMPLOYER")`
- **증명하지 않은 것**: Repository가 mock이라 실제 MySQL의 JOIN·소유자 필터·정렬·100건 제한·projection 변환은 실행 검증하지 않았다.

### 5-6. demo 화면 (Node 23개 + Java 정적 3개)

**정적 검사 (Java `WorkCompletionDemoPageTest`)**
- 완료 화면
  - 입력 폼이 없다. 일반 문구는 "완료 일시"다.
  - `no-referrer` 메타가 있다.
  - `innerHTML`·저장소·`console`·`eval`·`Authorization`·내부 보험 경로를 쓰지 않는다.
- 로그인 화면: 완료 링크는 기본 숨김이고, `role === 'EMPLOYER'`일 때만 표시하는 구조다.
- 좌표 화면: `completedAt` 키 존재 확인과 "미완료" 표시 구문이 있다.

**스크립트 동작 (Node, 원문 인라인 스크립트 실행)**
상태를 가진 가짜 서버를 썼다. 근무 41은 종료 후, 40은 종료 전, 39는 취소된 근무다.

- 초기 조회와 목록
  - 순서는 /me → 목록 → /me다. 모두 `same-origin`·`no-store`이고, 헤더나 쿼리를 보내지 않는다.
  - 목록 옵션 문구, 근무일 누락 시 "날짜 미등록", 빈 목록 안내를 표시한다.
- 상세 표시
  - 미완료는 "미완료"로 표시하고, JSON 원문은 `"completedAt": null`을 유지한다.
  - 취소 종류는 `CANCELLED`, 근무일이 없으면 "미등록"이다.
- 완료 요청 흐름
  - 순서는 /me → CSRF → /me → POST → /me → 상세 GET → /me다.
  - POST 헤더는 `{ 'X-CSRF-TOKEN': 토큰 }`뿐이고 본문이 없다.
  - 성공하면 "POST 응답과 DB에서 다시 조회한 완료 일시가 같습니다"를 표시하고, "완료 일시"에 서버 값을 보여준다.
  - CSRF 토큰과 헤더 이름은 화면·최근 요청 표 어디에도 없다.
  - 저장소·로그·HTML 삽입 접근 기록은 0건이다.
- 재요청
  - 서버 시각이 바뀌어도 최초 시각 유지 안내를 표시한다.
  - POST와 GET의 시각이 다르거나, 재요청 시 서버가 시각을 덮어쓰면 일치나 유지로 표시하지 않는다. 오류로 표시하고 상세를 지운다.
- 오류 표시
  - 409 `WORK_NOT_FINISHED`·`WORK_ALREADY_CANCELLED`는 상태코드·코드·서버 메시지를 표시한다. 자동 상세 재조회나 재전송이 없고, 최근 요청 표에 409와 코드가 남는다.
  - 403과 본문 없는 응답은 고정 안내 문구로 표시한다.
  - 상세 404, 목록 403도 코드와 메시지를 표시한다.
- 통신 실패
  - POST나 재조회 GET이 네트워크 오류면 성공으로 표시하지 않는다. "자동으로 재전송하지 않습니다" 안내를 띄우고, POST는 1회뿐이다.
  - CSRF 조회가 실패하거나 형식이 맞지 않으면 POST를 보내지 않는다.
  - 확인 창에서 취소하면 요청이 없다.
- 중복 요청 방지
  - POST 대기 중에는 버튼이 비활성화된다. 다시 클릭하거나 목록을 다시 조회해도 추가 요청이 없다.
  - 선택을 바꾸면 이전 상세를 바로 지운다. 상세와 선택이 다르면 완료할 수 없다.
- 계정 변경·화면 이탈
  - POST 전에 계정이 바뀌면 POST를 보내지 않고 목록·상세를 지운다.
  - POST 뒤 로그아웃(401)이면 일치로 표시하지 않는다.
  - 근로자, 비로그인, 문자열 ID 응답이면 목록을 조회하지 않는다.
  - pagehide 뒤에 도착한 응답은 표시하지 않는다.
- 안전한 표시: 근로자명·공고명의 `<img onerror>`·`<script>` 문자열은 `textContent`로 문자 그대로 표시된다.
- 좌표 화면
  - 미완료는 "완료 일시: 미완료", 완료는 서버 값 그대로 표시한다. JSON 원문에는 `"completedAt":null` 키가 있다.
  - `completedAt` 키가 없는 응답(V11 이전 서버)은 미완료로 추측하지 않고 오류를 안내한다.

**검증 수준 주의**
- 실제 브라우저의 렌더링·`confirm` 창·네트워크·쿠키·CSRF 검증은 실행하지 않았다.
- 가짜 서버는 실제 Spring 서버가 아니다.

## 6. 발견 결함과 관찰 사항

### 결함
기능 코드 결함은 발견하지 않았다. 재현 테스트로 남길 실패 사례가 없다.

### 관찰 (결함 아님, 정책 확인 권장)

| ID | 내용 | 근거 테스트 | 관련 파일 |
|---|---|---|---|
| O-1 | 변경 기록·정정 경로에서 완료 여부 확인보다 `streams.ensureExists`·`lockWorker`가 먼저 실행된다. 거절 예외가 `RuntimeException`이라 트랜잭션 롤백 대상으로 보인다. 실제 롤백은 확인하지 않았다. | `WorkChangeRecorderCompletionTest$FirstRecord.streamEnsuredBeforeRejection` (특성화) | `change/service/WorkChangeRecorder.java` |
| O-2 | 보험·근로자 상세 조회 서비스는 반쪽 완료 상태를 따로 검사하지 않는다. 처리자만 있으면 null, 시각만 있으면 그 시각을 반환한다. 완료 서비스는 같은 상태를 503으로 거절한다. V11 CHECK 제약이 실제로 막는다면 도달하지 않는 상태다. | `WorkAssignmentCompletionResponseTest$Mapping.halfStateIsNotCheckedOnRead` (특성화) | `insurance/service/WorkAssignmentQueryService.java`, `V11__add_work_assignment_completion.sql` |
| O-3 | 경로 ID `0x1F`를 Spring의 long 변환이 31로 받아 처리한다. 기존 다른 API와 같은 변환 규칙이다. | `WorkCompletionApiTest$InvalidId.lenientConversion` (특성화) | `work/api/WorkCompletionApi.java` |
| O-4 | 이미 완료된 근무를 다시 요청하면 약정 종료 판단보다 먼저 최초 값을 200으로 반환한다. 서버 시각이 종료 전이어도 마찬가지다. 재요청이 최초 값을 그대로 돌려준다는 정책과는 맞는다. | `WorkCompletionServiceTest$Repeat.repeatDoesNotRecheckEnd` (특성화) | `work/service/WorkCompletionService.java` |

## 7. 실행하지 않은 검증 범위

| 범위 | 상태 |
|---|---|
| V11 마이그레이션 실제 적용, CHECK(두 컬럼 동시 NULL/NOT NULL, `completed_by > 0`), FK(`completed_by → member`), 기존 행 NULL 보존 | 미실행. `FlywayMigrationTests`는 통합 태그라 실행하지 않음. 마이그레이션 적용·DB 접근 없음 |
| 실제 MySQL 트랜잭션: `PESSIMISTIC_WRITE`의 `FOR UPDATE`, 완료와 취소의 동시 요청 경합, 데드락 부재, 롤백, READ_COMMITTED 동작 | 미실행. 호출 순서와 어노테이션 선언만 확인 |
| `DATETIME(6)` 저장·조회 시 마이크로초 유지, JDBC 시간대 변환 | 미실행 |
| 네이티브 SQL의 JOIN·소유자 필터·정렬·LIMIT 100·projection 변환 | 미실행. 문자열 선언만 확인 |
| 전체 Security 체인: 익명 401, 근로자 403 JSON 핸들러, CSRF 누락·불일치 403, 세션 격리, farm-demo 비활성 시 경로 차단 | 미실행. SecurityConfig는 읽기 검토와 문자열 선언 확인만 함 |
| Spring Boot가 구성한 JsonMapper의 `OffsetDateTime` 직렬화 | 미실행. standalone 기본 변환기 기준 |
| 실제 브라우저·서버 기동·Docker·개발 DB | 실행하지 않음. 실행 전후 Docker 컨테이너 목록(`wonday-mysql-1`, `wonday-rabbitmq-1`)은 조회만 했고 건드리지 않음 |

## 8. 기존 결과와의 구분

- **사용자 수동 확인 (자동 테스트와 합산하지 않음)**: 사용자가 farm-demo에서 다음 세 가지를 확인했다고 전달받았다. 이번 실행으로 재현하거나 검증한 것이 아니다.
  - 완료 POST 200
  - 상세 재조회에 완료 일시 반영
  - 재요청 시 최초 완료 일시 유지
- 개발 DB의 시연용 근무는 fixture로 쓰거나 변경하지 않았다. 모든 데이터는 테스트 안의 가상 값이다.
- 이 보고서의 Java 288개와 Node 23개는 이번 실행 결과다. 다른 날짜·기능 보고서와 합산하지 않는다.
  - 예: `2026-11-27-insurance-salary-account`의 단위 138개, Node 11개, 통합 15개
- 실제 `.env`·키·서비스 토큰은 읽거나 출력하지 않았다. 테스트의 CSRF 토큰과 비밀 문자열은 가상 값이다.

## 9. 보존 확인

| 대상 | 결과 |
|---|---|
| `src/main`·Gradle 파일·`gradle/`·`.github` (167개 파일) | sha256이 실행 전후 동일 |
| `src/test` 기존 파일 | 해시 동일. 추가된 것은 신규 테스트 8개뿐 |
| 저장소 `git diff` | 해시 동일. 추적 파일 변경 없음 |
| 브랜치 | `work-completion` 유지. 커밋·푸시·stash·restore 없음 |

## PR 요약 (붙여넣기용)

> **근무 완료 단위 검증**
> - Java 단위 테스트 288/288 통과 (신규 134 + 기존 직접 영향 154). Node 화면 스크립트 테스트 23/23 통과.
> - 최초 실행에서 Java 1건·Node 1건이 실패했으나 모두 테스트 코드 오류였고, 기대값은 바꾸지 않았다.
> - 기능 결함은 없다. 관찰 4건: 거절 전 스트림 보장, 조회의 반쪽 상태 미검사, 16진수 경로 ID, 재요청 시 종료 판단 생략.
> - 범위 밖: 실제 MySQL 잠금·경합·롤백, V11 제약 적용, SQL 실행, 전체 Security 체인(401·CSRF·세션).
> - 사용자 수동 확인 3건은 별도이며 합산하지 않았다.
