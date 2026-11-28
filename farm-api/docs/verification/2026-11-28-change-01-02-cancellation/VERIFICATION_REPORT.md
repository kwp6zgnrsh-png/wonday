# CHANGE-01·02 시작 전 개인별 취소 — 단위 테스트 검증 보고서

- 검증일: 2026-11-28
- 검증 범위: 단위 테스트만. 이번 결과는 선택 실행 결과이며 전체 CI·운영 환경 검증 완료를 뜻하지 않는다.
- 결론: 선택 실행한 Java·화면 테스트에서 운영 코드 결함을 찾지 못했다. 그래서 FIX_REQUEST.md는 작성하지 않았다. 정책 확인이 필요한 관찰 사항은 7절에 있다.

## 1. 작업 위치

| 항목 | 값 |
|---|---|
| 저장소 | `C:\Users\HCB\Desktop\wonday` (원래 폴더. 별도 worktree는 사용하지 않음) |
| 브랜치 | `work-cancel` |
| HEAD | `943a1aa` (Merge pull request #39 from kwp6zgnrsh-png/work-selection) |
| AGENTS.md | 없음 |
| 구현 상태 | 미커밋(수정 4개, 신규 다수). 검증 전후로 sha256과 `git diff` 해시가 같음을 확인했다(운영 파일 16개, 변경 없음). |
| JDK / Node | JDK 21.0.12.1 (`woori-workspace/00.sw`) / Node v26.13.1 |

## 2. 검증한 운영 파일

- `change/api/WorkCancellationApi.java`
- `change/dto/WorkCancellationRequest.java`, `WorkCancellationResponse.java`
- `change/serialization/CancellationStringDeserializer.java`
- `change/validation/CancellationInput.java`
- `change/exception/WorkCancellationException.java`, `WorkCancellationErrorHandler.java`
- `change/service/WorkCancellationService.java`
- `change/service/WorkChangeRecorder.java`
  - 신규 `cancelBeforeStart`
  - 기존 `recordValidatedChange`·`reviseReason`은 소스 대조와 기존 테스트로 확인
- `demo/cancellation/DemoWorkCancellationApi.java`, `DemoWorkCancellationFixtureService.java`, `DemoWorkCancellationErrorHandler.java`
- `auth/SecurityConfig.java` (선언만 소스 대조)
- `resources/demo/farm-work-cancellation.html`, `resources/demo/farm-login.html`
- 참고 문서: `docs/change-01-02-work-cancellation-api.md`

## 3. 작성한 테스트 파일 (모두 신규)

| 파일 (`src/test/java/com/wonday/farm/` 기준) | 테스트 수 | 내용 |
|---|---:|---|
| `change/validation/CancellationInputTest` | 58 | ID 형식, 사유 조합, 설명 정규화, 500 코드 포인트 |
| `change/dto/WorkCancellationRequestJsonTest` | 14 | 문자열 전용 역직렬화(숫자·불리언·배열·객체 거절) |
| `change/service/WorkChangeRecorderCancellationTest` | 47 | `cancelBeforeStart` 실제 코드 + mock 저장소 + 제어 가능한 Clock |
| `change/service/WorkCancellationServiceTest` | 8 | 실제 서비스 + 실제 기록기, 응답 변환, 예외 전파 |
| `change/api/WorkCancellationApiTest` | 52 | standalone MockMvc: 실제 API·오류 처리기·서비스, 기록기만 mock |
| `demo/cancellation/DemoWorkCancellationFixtureServiceTest` | 11 | 시연 준비 서비스(WORK-02 준비·선정 서비스는 mock) |
| `demo/cancellation/DemoWorkCancellationApiTest` | 4 | standalone MockMvc: 화면 GET, 준비 201/200, 오류 |
| `demo/cancellation/WorkCancellationDemoPageTest` | 3 | 정적 검사: 화면 스크립트, 로그인 메뉴, SecurityConfig 선언 |
| `docs/verification/2026-11-28-change-01-02-cancellation/work-cancellation-page.test.mjs` | 17 | Node 가짜 DOM·fetch로 화면 스크립트 원문 실행 |

## 4. 실행 명령과 선택 범위

### 4.1 Java 1차 — 이번에 작성한 8개 클래스

```
cd C:\Users\HCB\Desktop\wonday\farm-api
.\gradlew.bat unitTest --tests "com.wonday.farm.change.validation.CancellationInputTest" --tests "com.wonday.farm.change.dto.WorkCancellationRequestJsonTest" --tests "com.wonday.farm.change.service.WorkChangeRecorderCancellationTest" --tests "com.wonday.farm.change.service.WorkCancellationServiceTest" --tests "com.wonday.farm.change.api.WorkCancellationApiTest" --tests "com.wonday.farm.demo.cancellation.DemoWorkCancellationFixtureServiceTest" --tests "com.wonday.farm.demo.cancellation.DemoWorkCancellationApiTest" --tests "com.wonday.farm.demo.cancellation.WorkCancellationDemoPageTest"
```

### 4.2 Java 2차 — 직접 관련된 기존 4개 클래스 (1차와 별도 실행)

```
.\gradlew.bat unitTest --tests "com.wonday.farm.change.service.WorkChangeRecorderTest" --tests "com.wonday.farm.change.service.WorkChangeRecorderCompletionTest" --tests "com.wonday.farm.demo.selection.WorkSelectionDemoPageTest" --tests "com.wonday.farm.demo.completion.WorkCompletionDemoPageTest"
```

선택 이유:

| 클래스 | 이유 |
|---|---|
| `WorkChangeRecorderTest`, `WorkChangeRecorderCompletionTest` | 이번 변경으로 수정된 `WorkChangeRecorder`의 기존 경로(`recordValidatedChange`·`reviseReason`) 회귀 확인 |
| `WorkSelectionDemoPageTest` | 수정된 `farm-login.html`과 `SecurityConfig.java`에서, 새 줄 바로 옆의 기존 선정 메뉴·권한 선언을 문자열로 확인 |
| `WorkCompletionDemoPageTest` | 새 403 분기가 기존 완료 분기 바로 앞에 삽입되어 함께 확인 |

### 4.3 화면 테스트 (새 기능 파일 1개)

```
node --test docs/verification/2026-11-28-change-01-02-cancellation/work-cancellation-page.test.mjs
```

### 4.4 실행하지 않은 것

- 전체 `unitTest`, `test`, `fullTest`, `integrationTest`, build/check, JaCoCo·커버리지 태스크
- Spring 컨텍스트, 서버, DB, H2, Docker, Testcontainers, 이관 도구, 브라우저

## 5. 실행 결과 (각 실행을 따로 기록하며 서로 합산하지 않는다)

| 실행 | 대상 | 실행 | 성공 | 실패 | 건너뜀 |
|---|---|---:|---:|---:|---:|
| Java 1차 | 신규 8개 클래스 | 197 | 197 | 0 | 0 |
| Java 2차 | 기존 4개 클래스 (41 + 11 + 4 + 3) | 59 | 59 | 0 | 0 |
| Node 1차 | 화면 파일 1개 | 17 | 16 | 1 | 0 |
| Node 2차 (재실행) | 같은 파일 | 17 | 17 | 0 | 0 |

- 두 Gradle 실행 모두 `BUILD SUCCESSFUL`이다. 개수는 실행 뒤 새로 생성된 `build/test-results/unitTest` XML에서 집계했다.
- **Node 1차 실패 1건(테스트 코드 결함, 운영 코드 결함 아님)**
  - 실패 테스트: 「근로자·비로그인」
  - 원인: 테스트 도우미가 화면 스크립트를 실행한 뒤에 가짜 서버의 계정을 바꿨다. 스크립트가 처음 `/api/auth/me`를 부를 때는 아직 고용주 계정이었다.
  - 조치: 스크립트 실행 전에 계정을 정하도록 도우미만 고쳤다. 기대값은 바꾸지 않았다.
  - 2차 재실행 결과는 1차와 별도로 기록했다.
- **커버리지**: 커버리지 태스크를 실행하지 않았다. 따라서 커버리지 기준 실패는 해당 없음이며, 테스트 실패와는 별개다. 위 표의 실패는 테스트 실패만 뜻한다.
- 사용자가 farm-demo에서 직접 확인한 결과(최초 200, 동일 재요청 200, 다른 사유 409)는 이 자동 테스트 개수에 포함하지 않았다.

## 6. 검증 항목별 결과

### 6.1 입력 검증과 정규화 — 통과

**경로 ID**
- 허용: `1`, `7`, `31`, `1000000`, `9223372036854775807`
- 거절(400 INVALID_REQUEST): `0`, `-1`, `+1`, `1.0`, `1.5`, 앞뒤 공백, `1e3`/`1E3`, `0x1F`, `01`, 빈 문자열, `9223372036854775808` 등 Long 범위 초과, 전각 `１`, null
- API 경로에서도 같고, 기록기는 호출되지 않는다.

**사유 코드**
- 필수다.
- WEATHER / FARM_CIRCUMSTANCES / OTHER만 허용한다.
- WEATHER는 RAIN / WIND / HEAT / OTHER 중 하나가 필수다.
- 기상이 아닌 사유에 기상 세부 사유를 보내면 400이다.
- 빈 코드, 소문자, 앞뒤 공백, 알 수 없는 코드는 거절한다.

**JSON 자동 변환 차단**
- 세 필드 모두 숫자·불리언·배열·객체 토큰을 `JacksonException`으로 거절한다. API에서는 400이다.
- null이나 생략은 null이다.

**설명 정규화**
- 생략, null, 공백만 있는 값(탭·줄바꿈·U+3000·U+00A0·U+2028/2029 등)은 null로 정규화한다.
- 앞뒤의 공백·탭·줄바꿈·유니코드 공백(U+2007·U+202F 포함)은 제거한다.
- 중간의 공백·줄바꿈·탭은 보존한다.

**길이(코드 포인트 기준)**
- 앞뒤 공백 제거 후 500 코드 포인트는 허용, 501은 거절한다.
- 한글, 영문, 이모지, 혼합 문자열 모두 확인했다.
- 이모지 500개(UTF-16 길이 1000)는 허용, 501개는 거절이다.
- 중간 공백도 길이에 포함된다.
- 초과분을 잘라 저장하지 않고 400으로 거절한다. 오류 메시지에 입력 원문은 없다.
- 기록기를 직접 호출하는 내부 경로에도 같은 검사가 적용된다.

### 6.2 최초 취소와 상태·권한 — 통과 (실제 `cancelBeforeStart` 코드, mock 저장소, 제어 가능한 Clock)

**권한·소유권**
- DB 회원 역할이 EMPLOYER가 아니면(회원 없음, WORKER, `ADMIN`·`INSURER`·소문자·빈 역할 코드) `AccessDeniedException`이다.
  - 소유권 조회, 스트림 잠금, 근무 잠금, 변경 저장, 버전 저장을 모두 호출하지 않는다.
- 다른 고용주의 근무와 없는 근무는 같은 404 `WORK_ASSIGNMENT_NOT_FOUND`이며 메시지도 같다. 잠금과 저장이 없다.

**시작 시각 판정**
- 기준은 약정 `agreedStartAt`·`agreedTimeZone`이다.
  - 약정 시간대를 `UTC`로 바꾸면 GMT+9 가정과 다른 경계에서 판정된다.
  - 약정 뒤 공고 일정(`plannedStartAt`)을 당기거나 늦춰도 약정 시각으로만 판정한다.
- 경계
  - 시작 1ns·1µs 전은 허용한다. 저장 시각은 `22:59:59.999999`로 마이크로초 절삭된다.
  - 정확히 시작 시각, +1ns, +1µs, +1분, +1일은 409 `WORK_ALREADY_STARTED`이며 저장·버전·순번 증가가 없다.
- 판정 시각
  - Clock은 스트림 잠금 → 근무 잠금 → 변경 행 잠금 뒤에 1회만 읽는다(inOrder 확인).
  - 스트림 잠금이 반환될 때 시계를 시작 이후로 옮기면 409가 된다. 즉 잠금 대기가 끝난 시각으로 판단한다.
- 시간대·시작 시각을 해석할 수 없으면 503 `SERVICE_UNAVAILABLE`이며 저장이 없다.
  - 시간대가 null, 빈 값, 공백, 잘못된 값(`Mars/Olympus`, `GMT+99`)인 경우
  - 시작 시각이 null인 경우
  - DST 공백(`America/New_York` 2027-03-14 02:30)과 중복(2027-11-07 01:30)인 경우

**상태 충돌**
- 완료 기록(`completedAtUtc` 또는 `completedBy` 중 하나만 있어도)은 409 `WORK_ALREADY_COMPLETED`이다.
- 조기 종료 기록은 409 `WORK_ALREADY_EARLY_ENDED`이며 기존 기록은 바뀌지 않는다.
- 모집 상태(`WorkSchedule.Status` 전 값)는 개인별 취소를 막지 않는다.

### 6.3 동일 재요청과 다른 내용 충돌 — 통과

**동일 재요청**
- 사유·기상 세부 사유·정규화한 설명이 같으면 기존 객체를 그대로 반환한다(같은 인스턴스).
  - 변경 ID, 사유, 버전, effectiveAt, recordedAt, updatedAt이 유지된다.
  - 변경 저장, 버전 저장, 순번 증가가 없다.
- 설명 비교는 정규화 기준이다.
  - 앞뒤 공백 차이, 생략·null·빈 설명, 저장 원본 쪽 앞뒤 공백까지 같은 내용으로 본다.
- 시작 시각이 3일 지난 뒤의 동일 재요청도 기존 결과를 반환한다. 이때 Clock 읽기 횟수가 0이므로 시작 시각을 다시 판정하지 않는다.

**다른 내용**
- 사유, 기상 세부 사유, 설명 중 하나만 달라도 409 `CANCELLATION_REASON_CONFLICT`이다. 시작 이후에도 같다.
- 충돌 시 기존 기록의 필드는 바뀌지 않는다.

**이전 회차**
- 이전 회차 근무(다른 assignmentId)의 취소 기록은 조회·수정하지 않는다.
- 새 근무에는 별도 변경이 생성된다.

### 6.4 저장과 보험 조회용 버전 — 통과 (mock 기준)

**변경 원본**
- CANCELLED 원본을 다음 값으로 저장한다.
  - 정규화한 설명
  - 세션 고용주 ID(actorId)
  - 서버 시각: UTC 기준, 마이크로초 절삭(`.123456789` → `.123456`)
- 최초 세 시각은 같고 sourceVersion은 1이다.

**보험 조회용 버전**
- `WorkChangeVersion`을 1회 저장한다.
- 다음 값이 연결된다: workerId, assignmentId, changeId, sourceVersion 1, 유형·사유·세부 사유·설명, 세 시각, 약정 시간대(`GMT+9`), 발행 순번(이전 4 → 5).

**순서와 실패 처리**
- 호출 순서: 회원 → 소유권 → 스트림 보장 → 스트림 잠금 → 근무 잠금 → 변경 행 잠금 → 변경 저장 → 버전 저장 (inOrder 확인)
- 동일 재요청에서는 순번을 발행하지 않는다.
- 버전 저장 실패(`DataIntegrityViolationException`, `DataAccessResourceFailureException`)는 기록기와 서비스 밖으로 예외로 전파된다. 성공 응답으로 바뀌지 않는다.
- 스트림 잠금 결과가 없으면 503, 잠금 중 근무가 사라지면 404이며 둘 다 저장이 없다.

**다른 데이터에 영향 없음**
- 약정 필드(applicationId, 시작·종료, 시간대, 근무일, 일당, 완료)는 바뀌지 않는다.
- 저장소 호출은 이 근무 ID의 조회·잠금과 변경 INSERT 1회뿐이다(`verifyNoMoreInteractions`). 삭제나 다른 근무에 대한 호출이 없다.
- 기록기에는 지원 저장소 의존성이 없다. 따라서 지원을 미선정으로 바꾸는 경로가 없다.

**기존 경로 소스 대조**
- `git diff`에서 `recordValidatedChange`·`reviseReason`의 바뀐 부분은 클래스·메서드 위 주석뿐이며 본문은 변경이 없다.
- 기존 테스트 52개(41 + 11)가 통과했다(Java 2차).

### 6.5 API와 오류 응답 — 통과 (standalone MockMvc)

**성공 응답**
- 세션 principal의 회원 ID(10)가 기록기로 전달된다.
- 본문의 `employerId`·`actorId`·`effectiveAt`·`recordedAt`·`changeId`·`sourceVersion`은 무시된다(관찰 O-2). 서버 값만 사용한다.
- 200, `Cache-Control: no-store`, `application/json`이다.
- 필드는 정확히 10개다.
  - `sourceVersion`은 JSON 문자열(`"1"`, `"12"`)이다.
  - 세 시각은 GMT+9 약정이면 `2026-11-28T15:00:00.123456+09:00`, UTC 약정이면 `...Z`로 오프셋이 포함된다.
  - 기상 세부 사유와 설명은 null로 응답한다.

**오류 응답**

| 상태 | 조건 |
|---|---|
| 400 | 잘못된 경로 ID 9종, 본문 누락·빈 본문·`null`·배열·문자열, JSON 형식 오류, 비문자열 토큰, 사유 조합 오류, 501자 설명 |
| 404 | 다른 소유자·없는 근무 |
| 409 | 4개 상태 충돌 코드와 각 메시지, DB 무결성 충돌(`WORK_STATE_CONFLICT`) |
| 403 | `AccessDeniedException`과 principal 없음 → `EMPLOYER_ROLE_REQUIRED` |
| 415 | `text/plain`, `application/x-www-form-urlencoded`, `application/xml`, `application/octet-stream` → `INVALID_REQUEST` JSON, 기록기 미호출 |
| 503 | `InsuranceApiException.unavailable`, `WorkCancellationException.unavailable`, `DataAccessResourceFailureException`, `QueryTimeoutException`, `CannotCreateTransactionException` |
| 500 | 예상하지 못한 예외 → `INTERNAL_SERVER_ERROR` |
| 405 | GET (기록기 미호출) |

- 모든 컨트롤러 오류 응답은 no-store이고 본문은 `code`·`message` 두 필드뿐이다.
- 응답 본문에 SQL 문자열, `jdbc:`, 비밀번호, 전화번호 형식, 예외 이름, 입력 원문(`<script>` 등)이 나오지 않는다.

**선언 확인**
- `@PostMapping`에 `consumes`가 없다. 그래서 415가 전용 처리기로 간다.
- 오류 처리기의 `assignableTypes`는 `WorkCancellationApi`뿐이다.

### 6.6 SecurityConfig 소스 대조 — 선언만 확인 (실제 필터 체인 미실행)

- farm-demo 블록 안에 두 줄이 각각 1회씩 있다.
  - `GET /demo/work-cancellation` permitAll
  - `POST /demo/api/work-cancellation/fixtures` hasRole EMPLOYER
- 공통 블록에는 `POST /api/work-assignments/*/cancellation` hasRole EMPLOYER가 `anyRequest().denyAll()` 앞에 있다.
- 두 경로 모두 permitAll이나 hasAnyRole로 선언되지 않았다. 두 경로를 대상으로 한 CSRF 예외(`ignoringRequestMatchers`) 문자열도 없다.
- 403 JSON 분기는 두 POST 경로에서 CSRF 예외가 아닐 때 `EMPLOYER_ROLE_REQUIRED`·no-store로 응답한다. 분기 정규식이 `/completion`이나 하위 경로와 섞이지 않는 것도 확인했다.
- 익명 401, CSRF 403, hasRole 적용, 세션 격리는 실제 필터 체인을 실행하지 않았으므로 **미검증**이다.

### 6.7 demo API와 시연 데이터 준비 — 통과 (mock)

**프로필과 권한**
- `DemoWorkCancellationApi`, `DemoWorkCancellationFixtureService`, `DemoWorkCancellationErrorHandler` 모두 `@Profile("farm-demo")`이다(선언만 확인. 실제 프로필 활성화는 미검증).
- 고용주 본인만 준비할 수 있다. 회원 없음, 근로자, 알 수 없는 역할은 WORK-02 준비를 호출하기 전에 거절된다.

**사례 준비**
- 준비 응답의 `preparedJobPostingId`와 사례만 사용한다.
  - 다른 공고의 사례는 선정·조회 호출 없이 제외된다.
  - 대상 외 사례(CHANGED·CLOSED·REMOVED·CAPACITY_FULL)도 제외된다.
  - WORK-02 준비는 1회만 호출하고 `list()`로 날짜를 다시 조회하지 않는다. 그래서 자정 뒤 재조회로 사례가 누락되지 않는다.
- 정상, 다른 정상, 일시중단 사례는 `selection.select(고용주, 공고, 일정, 지원, TermsVersionRequest(현재 버전))`로 확정한다.
- 시작 경과 사례는 선정 서비스를 거치지 않는다.
  - 대기 지원을 `select()`한다.
  - 약정은 다음 값으로 저장한다: 일정 시작 2026-11-27 09:00 GMT+9, 확정 시각은 시작 하루 전(UTC 2026-11-26 00:00).
  - 지원이 대기·현재 회차가 아니거나 지원·일정이 없으면 503이며 저장이 없다.
- 반환값 연결: `assignmentId`, `workStatus`, 약정 시작(`2026-12-10T09:00+09:00`), `suggestedRequest`, 라벨

**재사용**
- 기존 취소, 조기 종료, 완료, 확정 사례는 상태 그대로 반환한다. 선정, 근무 저장, 변경 저장·삭제가 없고, 기존 취소 기록과 완료 시각도 바뀌지 않는다.
- 이전에 취소한 정상 사례는 다시 준비해도 CANCELLED로 남는다.
- 기존 취소의 `suggestedRequest`는 저장된 세 필드와 같다(앞뒤 공백 포함 원문).

**실패 처리**
- 공고 ID 없음 → 503
- 선정 409, WORK-02 준비 DB 실패, 선정 뒤 근무 조회 실패(503)는 예외로 전파된다.
- API에서 준비 실패는 409, 503, 500 JSON이며 no-store이다.

**회원 데이터**
- 회원 저장소는 `findById` 외에 호출되지 않는다. 비밀번호 인코더 의존성도 없다.

**demo API**
- 화면 GET: HTML, no-store, 서비스 미호출
- 준비: 신규 201, 재사용 200, 세션 회원 ID 전달
- principal 없음과 역할 거절은 403이다.

### 6.8 화면 단위 테스트 (Node 가짜 DOM·fetch) — 통과 (2차 17/17)

**로그인과 진입**
- 진입할 때 `GET /api/auth/me`만 호출하고 POST는 없다. 고용주에게는 준비 버튼만 활성화된다.
- 근로자나 비로그인 상태면 준비 버튼이 비활성이다. 클릭해도 CSRF 발급과 POST가 없다.
- `farm-login.html` 스크립트도 실행했다. 취소 링크는 고용주일 때만 보이고 근로자·비로그인일 때는 숨겨진다.

**준비와 사례 선택**
- 준비 → 사례 표 6행 → 정상 사례 자동 선택 → 입력 칸에 suggestedRequest 반영 → 저장 상태 GET 순으로 연결된다.
- 다른 사례를 고르면 입력이 그 사례 값으로 바뀐다.

**CSRF와 노출 방지**
- POST 4회 모두 직전에 새로 발급받은 서로 다른 토큰을 `X-CSRF-TOKEN`으로 보낸다. `credentials: same-origin`이고 Authorization·Cookie 헤더는 없다.
- CSRF 발급 실패(500, 빈 토큰, 통신 오류, headerName 누락)이면 쓰기 POST가 없다.
- 토큰이 화면 텍스트, 요청 본문 표시, 요청 기록 표에 없다.
- `localStorage`·`sessionStorage`·`indexedDB`·`document.cookie`·`console`·`innerHTML` 접근이 0건이다.

**최초 취소와 재요청**
- 최초 200을 표시하고 첫 성공 응답을 비교 기준으로 보관한다. 변경 ID, 버전, 세 시각을 표시한다.
- 취소 응답과 DB 상태 GET 응답은 별도 칸에 표시된다.
- 동일 재요청은 사유·설명 입력을 수정한 뒤에도 직전 성공 요청 본문을 그대로 보낸다. 비교 결과 "기존 결과 유지 확인"을 표시한다.
  - 서버가 다른 버전·시각을 돌려주면 달라진 필드를 오류로 표시한다(비교 로직이 실제로 동작함을 확인).
- 다른 사유 버튼
  - WEATHER 성공 뒤에는 `OTHER`/null을, OTHER 성공 뒤에는 `FARM_CIRCUMSTANCES`/null을 보낸다. 둘 다 유효한 조합이다.
  - 409를 받아도 이전 성공 ID와 비교 기준이 유지된다. 이후 재요청은 원래 본문을 보낸다.

**오류 상황**
- 보조 GET이 500이나 통신 오류여도 취소 결과, 사례 선택, 재요청·다른 사유·취소 버튼이 유지된다. 이어서 재요청도 할 수 있다.
- 대기 중인 POST가 있을 때 추가로 클릭해도 동시 POST가 생기지 않는다(POST 1건).
- POST 500이나 통신 오류일 때 자동 재전송이 없다. 통신 오류 때는 "결과가 불확실 … 자동 재전송하지 않습니다"를 안내한다.

**계정 변경과 pagehide**
- 요청 전이나 요청 중에 계정이 바뀌면 사례, 응답, 비교 기준을 지운다. 요청 전에 바뀐 경우에는 POST도 없다.
- pagehide 뒤에 도착한 응답은 화면을 다시 채우지 않는다.

**설명 입력**
- 길이 표시는 앞뒤 공백 제거 후 코드 포인트 기준이다(이모지 500 → `500 / 500`, 501 → 경고).
- 501자도 잘리지 않고 전송되어 서버 400이 표시된다. 기상 세부 사유를 비운 요청도 전송되어 400이 표시된다.
- 사용자 문자열(`<img onerror>`, `<script>`)은 textContent로 문자 그대로 표시된다.

**재사용과 이미 취소된 사례**
- 재준비 200은 "기존 시연 공고와 상태를 재사용합니다"로 표시된다.
- 이미 취소된 사례는 "이미 취소된 사례입니다 … 기존 결과 200을 확인합니다"로 안내한다.
  - 저장된 사유로 요청하고 기존 변경 ID와 시각을 표시한다.
  - 안내 문구에 "최초 취소"라는 표현이 없다.
- 시작 경과, 조기 종료, 완료 사례는 각 409 코드를 그대로 표시하고, 성공 필드를 채우지 않는다.

## 7. 발견 결함과 관찰 사항

**결함: 없음.** 실패한 Node 1차 1건은 테스트 도우미 결함이다(5절).

**관찰 사항 (결함 아님, 정책 확인용)**

| ID | 내용 |
|---|---|
| O-1 | 경로 ID `01`처럼 앞에 0이 붙은 값은 400으로 거절된다(정규식 `[1-9][0-9]{0,18}`). 문서는 "양수의 10진 숫자 문자열"이라고만 적고 있어, 앞자리 0을 거절하는 것이 의도인지 확인이 필요하다. 테스트는 현재 동작을 기록했다. |
| O-2 | 본문의 정의되지 않은 필드(`employerId`, `effectiveAt` 등)는 거절하지 않고 무시한다. 서버는 이 값을 사용하지 않는다. 확인한 것은 standalone 기본 JsonMapper의 동작이며, Spring Boot가 구성한 JsonMapper 설정은 실행하지 않았다. |
| O-3 | U+200B(폭 없는 공백)는 Java 공백이 아니어서 제거하지 않는다. 화면 길이 계산도 같은 기준이다. |
| O-4 | 취소 시연 준비는 WORK-02 시연 공고의 지원을 실제로 선정한다. 그래서 선정 demo 화면에도 확정 상태가 보인다. 문서와 화면 안내에 명시되어 있다. |
| O-5 | 동일 재요청에서 기존 결과를 반환할 때 기록기는 약정 시간대를 검사하지 않는다. 그 대신 서비스가 응답 변환 중 검사하며, 시간대가 깨져 있으면 503이다(`replayWithUnusableStoredZoneIs503`). |

## 8. 미검증 범위

- 실제 MySQL의 다음 동작: `PESSIMISTIC_WRITE` 잠금과 잠금 대기, 커밋 순서, 롤백, `work_change.assignment_id` 유니크 제약, `ensureExists` 네이티브 SQL. mock 호출 순서만 확인했으며 실제 DB 롤백·동시성·커밋 순서를 증명하지 않는다.
- 동시 요청(취소와 완료, 취소와 취소)의 직렬화
- `@Transactional` 실제 적용: 프록시와 READ_COMMITTED 격리 수준은 선언만 확인했다.
- 실제 Spring Security 필터 체인: 익명 401, CSRF 403 응답 형식, hasRole, 세션 격리, 403 JSON 분기의 실제 동작
- Spring Boot `/error`, Boot가 구성한 JsonMapper와 메시지 변환기 설정(standalone 기본값만 사용)
- `@Profile("farm-demo")` 빈의 실제 활성화·비활성화
- WORK-02 실제 준비 SQL(시연 공고 표식 조회), 실제 시연 데이터
- 실제 브라우저 렌더링·포커스·접근성. 화면 검증은 가짜 DOM이며 가짜 서버의 판단은 실제 API 검증이 아니다.
- 보험 서버의 변경 조회 API에서 새 버전이 실제로 노출되는지

## 9. 변경 파일 목록

신규(모두 미커밋):
- `farm-api/src/test/java/com/wonday/farm/change/validation/CancellationInputTest.java`
- `farm-api/src/test/java/com/wonday/farm/change/dto/WorkCancellationRequestJsonTest.java`
- `farm-api/src/test/java/com/wonday/farm/change/service/WorkChangeRecorderCancellationTest.java`
- `farm-api/src/test/java/com/wonday/farm/change/service/WorkCancellationServiceTest.java`
- `farm-api/src/test/java/com/wonday/farm/change/api/WorkCancellationApiTest.java`
- `farm-api/src/test/java/com/wonday/farm/demo/cancellation/DemoWorkCancellationFixtureServiceTest.java`
- `farm-api/src/test/java/com/wonday/farm/demo/cancellation/DemoWorkCancellationApiTest.java`
- `farm-api/src/test/java/com/wonday/farm/demo/cancellation/WorkCancellationDemoPageTest.java`
- `farm-api/docs/verification/2026-11-28-change-01-02-cancellation/work-cancellation-page.test.mjs`
- `farm-api/docs/verification/2026-11-28-change-01-02-cancellation/VERIFICATION_REPORT.md` (이 문서)

수정하지 않은 것:
- 운영 코드, HTML, 마이그레이션, Gradle, CI, 기존 테스트·보고서
- 다른 사람의 미커밋 변경

커밋·푸시·브랜치 변경은 하지 않았다.

## 10. 재검증: 취소 시연 준비의 공고 잠금 순서 수정 (2026-11-28)

1~9절의 기록(Java 197개, 기존 59개, 화면 17개)은 그대로 두고, 이 절의 13개와 합산하지 않는다.

### 10.1 작업 위치

| 항목 | 값 |
|---|---|
| 저장소 | `C:\Users\HCB\.codex\worktrees\work-early-end\wonday` (폴더 이름은 work-early-end지만 브랜치는 work-cancel) |
| 브랜치 | `work-cancel` (확인만 했고 전환하지 않음) |
| HEAD | `6d655f5` (feat(farm): 시작 전 개인별 근무 취소 API 구현) |
| AGENTS.md | 없음 |
| 미커밋 변경 | `DemoWorkCancellationFixtureService.java`, `DemoWorkCancellationFixtureServiceTest.java`, `change-01-02-work-cancellation-api.md` (스테이징 없음) |
| 보존 확인 | 실행 전후로 `git diff`와 `git status --porcelain`의 해시가 같다. |

원래 폴더 `C:\Users\HCB\Desktop\wonday`는 수정하거나 테스트하지 않았다.

### 10.2 실행 명령

```
cd C:\Users\HCB\.codex\worktrees\work-early-end\wonday\farm-api
.\gradlew.bat unitTest --tests "com.wonday.farm.demo.cancellation.DemoWorkCancellationFixtureServiceTest" --console=plain
```

- JDK는 21.0.12.1이고 결과는 `BUILD SUCCESSFUL`이다.
- `compileJava`와 `compileTestJava`는 UP-TO-DATE였다. 개발자가 수정 후 이미 컴파일한 결과를 재사용했다.
- 개수는 실행 후 새로 생성된 결과 XML 1개에서 집계했다.

### 10.3 결과

| 실행 | 성공 | 실패 | 건너뜀 |
|---:|---:|---:|---:|
| 13 | 13 | 0 | 0 |

- 선택된 테스트는 요청한 13개와 같다.
  - 1~9절의 이 클래스는 11개였다.
  - 공고 잠금 관련 테스트 2개(`locksPostingBeforeExistingAssignmentAndLaterSelection`, `postingUnavailableStopsBeforeLowerLocks`)가 새로 추가되었다.
  - 나머지 11개는 이름이 같고 그대로 실행되었다.
- 테스트 실패는 없다.
- 커버리지 태스크는 실행하지 않았으므로 커버리지 기준 실패는 해당 없음이다.

### 10.4 잠금 순서 회귀 검증

**운영 코드 대조**
- `prepare()`의 처리 순서
  1. 회원 역할을 확인한다.
  2. `fixtures.prepare(employerId)`를 호출한다.
  3. 공고 ID가 null이면 503이다.
  4. 사례 루프 전에 `postings.findOwnedForUpdate(preparedJobPostingId, employerId)`를 호출하고, 빈 결과면 `WorkCancellationException.unavailable()`(503)이다.
  5. 사례 루프를 시작한다.
- 루프 안의 처리는 바뀌지 않았다. 기존 상태 재사용과 취소·조기 종료·완료 기록 보존 동작이 그대로 유지된다.

**검증 결과**

| 중점 항목 | 결과 | 근거 테스트 |
|---|---|---|
| 1. 기존 확정 NORMAL과 미선정 STALE_VERSION이 섞인 경우 | 통과 | `locksPostingBeforeExistingAssignmentAndLaterSelection` |
| 2. 공고 잠금 실패 | 통과 | `postingUnavailableStopsBeforeLowerLocks` |
| 3. 시작 경과 사례 | 통과 | `startedScenario` |
| 4. 기존 회귀 | 통과 | 나머지 테스트 |

1. 기존 확정·미선정 혼합 사례
   - inOrder 확인 순서: 준비 서비스 호출 → 소유자 공고 잠금(`findOwnedForUpdate(4201, 10)`) → NORMAL 약정 조회 → STALE_VERSION 약정 조회 → `selection.select(10, 4201, 302, 1002, v2)` → 확정 근무 조회
   - 공고 잠금은 1회다.
   - 두 사례의 assignmentId 7000과 7002가 반환된다.
   - 약정 `saveAndFlush`는 호출되지 않는다.
2. 공고 잠금 실패
   - 빈 결과면 503 `SERVICE_UNAVAILABLE`이다.
   - 선정, 지원, 일정, 약정, 변경 저장소에 상호작용이 없다(`verifyNoInteractions`).
3. 시작 경과 사례
   - 순서: 공고 잠금 → 약정 조회 → 지원 잠금(`findScopedForUpdate(1006, 306)`) → 일정 조회
   - 기존 과거 약정 생성 조건이 유지된다: 약정 시작 2026-11-27 09:00, 확정 시각은 시작 하루 전(UTC 2026-11-26 00:00), 시간대 GMT+9, 선정 서비스 미호출
4. 기존 회귀
   - 권한 거절: 공고 잠금 전에 거절되며, `postings`에도 상호작용이 없다.
   - 신규 준비, 재사용, 사유 안내, 공고 ID 없음 503, 실패 전파, 다른 공고 제외, 프로필 선언, 회원 쓰기 없음 테스트가 모두 그대로 통과했다.
   - 생성자 변경에 맞춘 mock 설정은 적절하다.
     - `JobPostingRepository` mock을 생성자 두 번째 인자로 전달한다.
     - `@BeforeEach`에서 `findOwnedForUpdate(4201, 10)`이 공고를 반환하도록 했다.
     - 공고 ID가 null인 경우는 잠금 전에 503으로 끝나므로 이 설정의 영향을 받지 않는다.

### 10.5 관찰 사항 (결함 아님)

| ID | 내용 |
|---|---|
| O-6 | 혼합 사례 테스트가 직접 확인하지 않는 두 가지가 있다. 하나는 NORMAL에 `selection.select`가 호출되지 않는 것이고, 다른 하나는 변경 저장소의 저장이 없는 것이다. 운영 코드상 기존 약정이 있으면 선정 분기로 가지 않는다. 기존 `reuseKeepsStates`가 `verifyNoInteractions(selection)`과 변경 저장 없음을 확인한다. 기대값을 바꿀 필요는 없다. |
| O-7 | 실제 `WorkSelectionService.select`는 같은 트랜잭션 안에서 같은 공고를 다시 `findOwnedForUpdate`로 잠근다. 같은 트랜잭션의 재잠금은 대기가 없을 것으로 보이지만, select가 mock이라 실제로는 확인하지 않았다. |
| O-8 | WORK-02 준비(`fixtures.prepare`)는 근로자 요청 잠금을 먼저 잡고, 그 뒤 이번 공고 잠금이 이어진다. 다른 기능(지원 등)과의 전체 잠금 순서 그래프는 이번 범위에서 검토하지 않았다. |

### 10.6 미검증 범위

- 이번 검증은 mock 기반의 호출 순서와 동작 검증이다.
- 다음은 검증하지 않았다: 실제 MySQL에서 두 트랜잭션이 경합할 때의 동작, 교착 해소, 잠금 시간 초과, 롤백, `findOwnedForUpdate`의 실제 SQL 잠금
- 이 클래스 하나의 통과는 전체 CI 통과나 운영 환경 검증을 뜻하지 않는다.

### 10.7 변경한 파일

- `farm-api/docs/verification/2026-11-28-change-01-02-cancellation/VERIFICATION_REPORT.md`: 이 10절만 추가했다. 이 파일은 HEAD에 커밋된 상태여서, 이번 추가로 미커밋 수정이 된다.
- 운영 코드, 테스트 코드, 기대값, 문서는 수정하지 않았다.
- 커밋·푸시·브랜치 변경은 하지 않았다.
