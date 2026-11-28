# CHANGE-03 개인별 조기 종료 — 단위 테스트 검증 보고서

- 검증일: 2026-11-28
- 범위: 단위 테스트만 실행했다. 이번 결과는 선택 실행 결과이며 전체 CI·운영 환경 검증 완료를 뜻하지 않는다.
- 결론: 선택 실행한 Java·화면 테스트에서 운영 코드 결함을 찾지 못했다. 그래서 FIX_REQUEST.md는 작성하지 않았다. 정책 확인이 필요한 관찰 사항은 7절에 있다.
- 취소 기능의 이전 결과(197개·59개·화면 17개·재검증 13개)는 이 보고서의 수치에 포함하지 않는다.

## 1. 작업 위치

| 항목 | 값 |
|---|---|
| 저장소 | `C:\Users\HCB\Desktop\wonday` (원래 폴더) |
| 브랜치 | `work-early-end` (확인만 했고 전환하지 않음) |
| HEAD | `943a1aa` (Merge pull request #39 from kwp6zgnrsh-png/work-selection) |
| AGENTS.md | 없음 |
| 구현 상태 | 미커밋, 스테이징 없음 |
| 보존 확인 | 검증 전후로 운영 파일 18개의 sha256과 `git diff` 해시가 같다. |
| 사용하지 않은 폴더 | `C:\Users\HCB\.codex\worktrees\work-early-end\wonday` (work-cancel 브랜치). 사용하거나 수정하지 않았다. |
| JDK / Node | JDK 21.0.12.1 / Node v26.13.1 |

## 2. 검증한 운영 파일

`farm-api/src/main/java/com/wonday/farm/` 기준:

- `change/api/WorkEarlyEndApi.java`
- `change/dto/WorkEarlyEndRequest.java`, `WorkEarlyEndResponse.java`
- `change/serialization/EarlyEndTimeDeserializer.java`, `EarlyEndStringDeserializer.java`
- `change/validation/EarlyEndInput.java`
- `change/exception/WorkEarlyEndException.java`, `WorkEarlyEndErrorHandler.java`
- `change/service/WorkEarlyEndService.java`
- `change/service/WorkChangeRecorder.java`
  - 신규 `endWork`
  - 기존 `recordValidatedChange`·`reviseReason`은 diff 대조와 기존 테스트로 확인
- `demo/earlyend/DemoWorkEarlyEndApi.java`, `DemoWorkEarlyEndFixtureService.java`, `DemoWorkEarlyEndErrorHandler.java`, `DemoWorkEarlyEndRepository.java`
- `auth/SecurityConfig.java` (선언만 대조)

리소스(`farm-api/src/main/resources/` 기준):
- `demo/farm-work-early-end.html`, `demo/farm-login.html`

참고 문서: `farm-api/docs/change-03-work-early-end-api.md`

## 3. 작성한 테스트 (모두 신규)

| 파일 (`src/test/java/com/wonday/farm/` 기준) | 테스트 수 | 내용 |
|---|---:|---|
| `change/validation/EarlyEndInputTest` | 46 | ID, 실제 종료 시각 필수·마이크로초, 사유 조합, 설명 정규화·500 코드 포인트 |
| `change/dto/WorkEarlyEndRequestJsonTest` | 60 | 시각 형식(허용 13·거절 31·비문자열 8), 문자열 전용 필드 |
| `change/service/WorkChangeRecorderEarlyEndTest` | 42 | `endWork` 실제 코드 + mock 저장소 + 제어 가능한 Clock |
| `change/service/WorkEarlyEndServiceTest` | 7 | 실제 서비스 + 실제 기록기, 응답 변환, 예외 전파 |
| `change/api/WorkEarlyEndApiTest` | 47 | standalone MockMvc: 실제 API·오류 처리기·서비스, 기록기만 mock |
| `demo/earlyend/DemoWorkEarlyEndFixtureServiceTest` | 12 | 시연 준비 서비스(저장소·기록기·완료 서비스는 mock) |
| `demo/earlyend/DemoWorkEarlyEndApiTest` | 3 | standalone MockMvc: 화면 GET, 준비 201/200, 오류 |
| `demo/earlyend/WorkEarlyEndDemoPageTest` | 3 | 정적 검사: 화면 스크립트, 로그인 메뉴, SecurityConfig 선언 |
| `docs/verification/2026-11-28-change-03-early-end/work-early-end-page.test.mjs` | 17 | Node 가짜 DOM·fetch로 화면 스크립트 원문 실행 |

## 4. 실행 명령과 선택 범위

`build.gradle` 62행의 기존 `unitTest` 태스크를 사용했다.

### 4.1 Java 1차 — 이번에 작성한 8개 클래스

```
cd C:\Users\HCB\Desktop\wonday\farm-api
.\gradlew.bat unitTest --tests "com.wonday.farm.change.validation.EarlyEndInputTest" --tests "com.wonday.farm.change.dto.WorkEarlyEndRequestJsonTest" --tests "com.wonday.farm.change.service.WorkChangeRecorderEarlyEndTest" --tests "com.wonday.farm.change.service.WorkEarlyEndServiceTest" --tests "com.wonday.farm.change.api.WorkEarlyEndApiTest" --tests "com.wonday.farm.demo.earlyend.DemoWorkEarlyEndFixtureServiceTest" --tests "com.wonday.farm.demo.earlyend.DemoWorkEarlyEndApiTest" --tests "com.wonday.farm.demo.earlyend.WorkEarlyEndDemoPageTest" --console=plain
```

### 4.2 Java 2차 — 직접 영향을 받는 기존 4개 클래스 (별도 실행)

```
.\gradlew.bat unitTest --tests "com.wonday.farm.change.service.WorkChangeRecorderTest" --tests "com.wonday.farm.change.service.WorkChangeRecorderCompletionTest" --tests "com.wonday.farm.demo.selection.WorkSelectionDemoPageTest" --tests "com.wonday.farm.demo.completion.WorkCompletionDemoPageTest" --console=plain
```

선택 이유:

| 클래스 | 이유 |
|---|---|
| `WorkChangeRecorderTest`, `WorkChangeRecorderCompletionTest` | 이번에 수정된 `WorkChangeRecorder`의 기존 경로(`recordValidatedChange`·`reviseReason`) 회귀 확인 |
| `WorkSelectionDemoPageTest` | 수정된 `farm-login.html`·`SecurityConfig.java`에서 새 줄 바로 옆의 기존 선정 메뉴·권한 선언 확인 |
| `WorkCompletionDemoPageTest` | 새 403 분기가 기존 완료 분기 바로 뒤에 삽입되어 함께 확인 |

### 4.3 화면 — 이번 기능 파일 1개

```
node --test docs/verification/2026-11-28-change-03-early-end/work-early-end-page.test.mjs
```

### 4.4 실행하지 않은 것

- 전체 `unitTest`, `test`, `fullTest`, `integrationTest`, build/check, 커버리지 태스크
- Spring 컨텍스트, 서버, DB, H2, Docker, 이관 도구, 브라우저
- `.env`와 암호화 키는 읽거나 사용하지 않았다.

## 5. 실행 결과 (실행별로 따로 기록하며 서로 합산하지 않는다)

| 실행 | 대상 | 실행 | 성공 | 실패 | 건너뜀 |
|---|---|---:|---:|---:|---:|
| Java 1차 | 신규 8개 클래스 | 220 | 220 | 0 | 0 |
| Java 2차 | 기존 4개 클래스 (41 + 11 + 4 + 3) | 59 | 59 | 0 | 0 |
| Node 1차 | 화면 파일 1개 | 17 | 17 | 0 | 0 |

- 세 실행 모두 첫 실행이고 재실행은 없다.
- 두 Gradle 실행 모두 `BUILD SUCCESSFUL`이다. 개수는 실행 뒤 새로 생성된 결과 XML에서 집계했다. 선택된 테스트가 0개가 아님을 확인했다.
- Java 2차의 59개는 취소 검증 때의 59개와 같은 기존 클래스지만, 이번 브랜치에서 새로 실행한 결과다. 취소 결과와 합산하지 않는다.
- 커버리지 태스크는 실행하지 않았으므로 커버리지 기준 실패는 해당 없음이다. 위 실패 0은 테스트 실패만 뜻한다.
- 사용자의 farm-demo 수동 확인(최초 200, 동일 200, 다른 내용 409, 기한 초과 409)은 자동 테스트 개수에 포함하지 않았다.
- Node 화면 테스트의 가짜 계정은 스크립트 실행 전에 설정했다(초기 `/me` 순서 오류 방지).

## 6. 검증 항목별 결과

### 6.1 요청 형식과 입력 정규화 — 통과

**경로 ID**
- 양수만 허용한다.
- 다음은 400 INVALID_REQUEST이다: `0`, 음수, `+1`, 공백, 소수, `1e3`, `0x10`, `01`, 빈 값, Long 범위 초과, 전각 숫자, null

**actualEndAt — 필수, null이나 생략이면 400**
- 허용 형식
  - `+09:00`, `-08:00`, `+05:30`, `+00:00`, `Z`
  - 초 생략(0초로 처리)
  - 소수초 없음 또는 1~6자리
  - 윤년 2월 29일
- 허용된 값은 모두 같은 Instant로 해석한다. `Z`는 UTC 오프셋 그대로 유지한다.
- 거절 형식 (기본 JsonMapper `JacksonException`, API에서는 400)
  - 소수초 7자리 이상: `.1234560`처럼 뒤가 0이어도 거절하며 자르지 않는다. 9자리 0도 거절한다.
  - 오프셋 없음
  - 소문자 `t`·`z`, 공백 구분자
  - `+0900`, `+09`, `+09:00:00`, `[Asia/Seoul]`
  - 앞뒤 공백
  - 잘못된 날짜·시각: `02-30`, 2025년 `02-29`, 13월, `24:00`, `14:60`, `:60`초
  - 범위 밖 오프셋 `+19:00`
  - `.` 뒤 숫자 없음, `,` 소수점, `+` 연도, 한 자리 일자, 날짜만, 시각만, `now`, 에포크 문자열
  - 숫자, 소수, 불리언, 배열, 객체 토큰
- 내부 호출(`EarlyEndInput.values`)도 1ns 단위 값은 400으로 거절한다. 마이크로초 정밀도를 지킨다.

**사유와 설명**
- reasonCode는 필수이고 WEATHER / FARM_CIRCUMSTANCES / OTHER만 허용한다.
- WEATHER는 RAIN / WIND / HEAT / OTHER 중 하나가 필수다. 기상이 아닌 사유에 세부 사유가 있으면 거절한다.
- 세 문자열 필드는 숫자·불리언·배열·객체를 자동 변환하지 않는다.
- 설명 정규화
  - 앞뒤 공백·탭·줄바꿈·유니코드 공백은 제거하고, 빈 값·생략·null은 null이 된다.
  - 중간 공백과 줄바꿈은 보존한다.
- 설명 길이는 코드 포인트로 센다.
  - 한글, 영문, 이모지, 혼합 문자열 모두 500개 허용, 501개 거절이다.
  - 이모지 500개는 UTF-16 길이로 1000이다.

### 6.2 권한·상태·시간·입력 기한 — 통과 (실제 `endWork` 코드, mock 저장소, 제어 가능한 Clock)

**권한**
- 회원 없음, WORKER, `ADMIN`·빈 역할 코드는 `AccessDeniedException`이다. 소유권 조회, 잠금, 저장, 버전 발행이 없다.
- 다른 고용주 소유와 없는 근무는 같은 404이며 메시지도 같다. 잠금이 없다.
- 입력 오류는 모든 저장소 조회보다 먼저 거절된다.

**상태**
- 취소 기록이 있으면 409 `WORK_ALREADY_CANCELLED`이며 기존 기록은 바뀌지 않는다.
- 완료 기록(`completedAtUtc`나 `completedBy` 중 하나만 있어도)이 있으면 409 `WORK_ALREADY_COMPLETED`이다. 이 경우 변경 행 조회 전에 거절된다.
- 모집 상태(`WorkSchedule.Status` 전 값)는 개인별 조기 종료를 막지 않는다.

**시간 판정**
- 기준은 약정 시각과 시간대다.
  - 약정 뒤 공고 일정을 바꿔도 약정 시각으로 판정한다.
  - 약정 시간대를 `UTC`로 바꾸면 그 시간대로 판정한다.
- 서버 시각이 시작 1ns 전이면 409 `WORK_NOT_STARTED`이다. 정확히 시작 시각부터 허용한다.
- 실제 종료 시각
  - 시작과 같음, 종료 1µs 전은 허용한다.
  - 시작 1µs 전, 종료와 같음, 종료 이후는 400이다.
  - 서버 현재 시각과 같은 값은 허용한다. 서버 시각보다 1µs 미래면 400이다(서버 시각 `.123456789`, 입력 `.123456`/`.123457`).
  - 약정 종료 뒤의 늦은 입력에서도 그보다 이른 실제 종료 시각을 기록할 수 있다.
- 다음 경우는 503이며 저장이 없다.
  - 시간대가 null, 빈 값, 공백, 잘못된 값
  - 시작 또는 종료 시각 없음
  - 종료 ≤ 시작
  - DST 공백(`America/New_York` 2027-03-14 02:30)이나 중복(2027-11-07 01:30)

**입력 기한 (약정 종료 + 24시간)**
- 정확히 기한 시각은 허용한다.
- 기한 +1ns는 409 `EARLY_END_DEADLINE_PASSED`이다.
  - 서버 시각을 비교 전에 마이크로초로 잘랐다면 허용되었을 값이다. 즉 기한을 느슨하게 만들지 않는다.
  - 메시지에 "담당자에게 문의" 안내가 있다.
- 기한이 지나도 완료로 바꾸지 않는다(`completedAtUtc`·`completedBy` null 유지). 다른 저장이나 다른 근무 호출도 없다.
- 판정 시각은 잠금 대기가 끝난 서버 시각이다.
  - 스트림 잠금이 반환될 때 시계를 기한 이후로 옮기면 409가 된다.
  - Clock은 모든 잠금 뒤에 1회만 읽는다(inOrder 확인).
- 검사 순서는 시작 여부 → 입력 기한 → 실제 종료 범위이며 문서와 같다(관찰 O-2).

### 6.3 동일 재요청과 내용 충돌 — 통과

**동일 재요청**
- 실제 종료 시점, 사유, 세부 사유, 정규화한 설명이 모두 같으면 기존 객체를 그대로 반환한다.
  - 변경 ID, 버전(2), effectiveAt, recordedAt, updatedAt이 유지된다.
  - 저장, 버전 저장, 순번 증가가 없다.
- 시각 표기가 달라도 같은 Instant면 동일 요청이다(`Z`, `+09:00`, `-08:00` 표기).
- 기한 10일 뒤의 동일 재요청도 성공한다. 이때 Clock 읽기 횟수가 0이다.
- 시작 전이거나 약정 종료가 바뀐 상태여도 동일 재요청에는 새 시간 제한을 적용하지 않는다.
- 설명은 정규화 기준으로 비교한다(저장 원본의 앞뒤 공백 포함).

**내용 충돌**
- 다음 중 하나만 달라도 409 `EARLY_END_CONTENT_CONFLICT`이고 기존 값은 바뀌지 않는다: 종료 시각(+1µs, +1초), 사유, 세부 사유, 설명(변경 또는 null)
- 기존 기록 확인이 신규 시간 검사보다 먼저다. 시작 전이나 기한 후에도 시간 오류가 아니라 내용 충돌로 응답한다.
- 기존 기록의 effectiveAt이 null이면 503이다.

### 6.4 저장과 보험 변경 버전 — 통과 (mock 기준)

**변경 원본**
- EARLY_ENDED 유형으로 저장하고, 처리자는 세션 고용주 ID다. 정규화한 설명이 저장된다.
- effectiveAt은 실제 종료 시각의 UTC 값이며 입력 마이크로초 `.123456`을 보존한다.
- recordedAt·updatedAt은 서버 시각의 UTC 값이며 마이크로초로 절삭한다(`.123456789` → `.123456`).
- effectiveAt과 recordedAt은 서로 다른 값이다. sourceVersion은 1이다.

**보험 변경 버전**
- `WorkChangeVersion`을 1회 저장한다.
- 연결 값: workerId, assignmentId, changeId, 유형, 사유, 세부 사유, 설명, 세 시각, `GMT+9`, 버전 1, 발행 순번 4 → 5

**순서와 실패 처리**
- 호출 순서: 회원 → 소유권 → 스트림 보장 → 스트림 잠금 → 근무 잠금 → 변경 행 잠금 → 저장 → 버전 저장 (inOrder 확인)
- 원본 저장 실패와 버전 저장 실패는 예외로 전파된다(서비스 포함).
- 스트림 잠금 결과가 없으면 503, 근무가 없으면 404이며 둘 다 저장이 없다.

**다른 데이터에 영향 없음**
- 약정 필드는 바뀌지 않는다.
- 저장소 호출은 이 근무 ID의 조회·잠금과 INSERT 1회뿐이다(`verifyNoMoreInteractions`).
- 기록기에는 지원·일정·공고 저장소 의존성이 없다. 따라서 자리 복원이나 모집 재개 경로가 없다.

**기존 경로 대조**
- `git diff`에서 `WorkChangeRecorder`의 변경은 import, 클래스 주석, `endWork`·`RecordedEarlyEnd` 추가뿐이다.
- 기존 `recordValidatedChange`·`reviseReason` 본문은 바뀌지 않았다. 기존 테스트 52개(41 + 11)가 통과했다.

### 6.5 API와 오류 응답 — 통과 (standalone MockMvc)

**성공 응답**
- principal 회원 ID 10이 기록기로 전달된다.
- 본문의 `employerId`·`actorId`·`recordedAt`·`updatedAt`·`changeId`는 무시된다(관찰 O-3).
- 200, no-store이고 필드는 정확히 10개다.
  - enum은 문자열이다.
  - `sourceVersion`은 JSON 문자열(`"1"`, `"4"`)이다.
  - null 사유·설명은 null로 응답한다.
  - effectiveAt `2026-11-30T14:00:00.123456+09:00`, recordedAt `2026-11-30T18:00:00.654321+09:00`으로 서로 구분된다(GMT+9 약정).
- `Z` 입력도 같은 Instant로 기록기에 전달된다.

**오류 응답**

| 상태 | 조건 |
|---|---|
| 400 | 경로 ID 8종, 본문 누락·형식·시각 형식(오프셋 없음, 7자리 소수초, 잘못된 날짜, 비문자열)·사유 조합·자동 변환, 501자 설명, 실제 종료 범위 오류 |
| 403 | 역할 거절, principal 없음 |
| 404 | 다른 소유자·없는 근무 |
| 409 | `WORK_NOT_STARTED`·`WORK_ALREADY_CANCELLED`·`WORK_ALREADY_COMPLETED`·`EARLY_END_CONTENT_CONFLICT`(각 메시지), `EARLY_END_DEADLINE_PASSED`(문의 안내), `WORK_STATE_CONFLICT` |
| 415 | `text/plain`·form·xml·octet-stream → `INVALID_REQUEST` JSON, 기록기 미호출 |
| 503 | 5종 |
| 500 | 예상하지 못한 예외 |
| 405 | GET |

- 모든 오류 응답은 no-store이고 본문은 `code`·`message`뿐이다.
- SQL, `jdbc:`, 비밀번호, 전화번호 형식, 예외 이름, 입력 원문(`<script>`, `1234567`)이 응답에 나오지 않는다.

**선언 확인**
- `@PostMapping`에 consumes가 없다.
- 오류 처리기의 `assignableTypes`는 `WorkEarlyEndApi`뿐이다.

### 6.6 SecurityConfig — 선언 대조만 (실제 필터 체인 미실행)

- farm-demo 블록에 다음 두 줄이 각각 1회 있다.
  - `GET /demo/work-early-end` permitAll
  - `POST /demo/api/work-early-end/fixtures` hasRole EMPLOYER
- 공통 블록에는 `POST /api/work-assignments/*/early-end` hasRole EMPLOYER가 `denyAll()` 앞에 있다.
- 두 경로는 permitAll이나 hasAnyRole로 선언되지 않았다. CSRF 예외(`ignoringRequestMatchers`) 대상 문자열도 없다.
- 403 JSON 분기
  - 두 POST 경로에서 CSRF 예외가 아니면 `EMPLOYER_ROLE_REQUIRED`·no-store로 응답한다.
  - 분기 정규식은 `/completion`이나 하위 경로와 섞이지 않는다.
- 익명 401, CSRF 403, hasRole 적용, 세션 격리는 **미검증**이다.

### 6.7 demo API와 시연 데이터 준비 — 통과 (mock)

**프로필과 권한**
- API, 서비스, 오류 처리기, 저장소 모두 `@Profile("farm-demo")`이다(선언만 확인).
- 고용주 본인만 준비할 수 있다. 회원 없음, 근로자, 알 수 없는 역할은 잠금·조회 전에 거절된다.
- demo-worker가 없거나 역할이 다르면(EMPLOYER·ADMIN) 409 `DEMO_ACCOUNT_UNAVAILABLE`이며 잠금이 없다.

**잠금과 기준 날짜**
- 처리 순서: 회원 → demo-worker → 근로자 요청 잠금(보장 → 잠금) → Clock 1회 → 표식 조회 → 생성 (inOrder 확인)
- 잠금 결과가 없으면 503이며 조회·생성이 없다.
- 기준 날짜는 한 번만 정한다.
  - UTC 2026-11-28 15:30은 GMT+9로 2026-11-29 00:30이다.
  - 표식 `…·2026-11-29]`, 일정 날짜, 응답 분류에 이 날짜를 똑같이 쓴다.

**신규 생성**
- 공고는 표식 제목과 `GMT+9`로 만든다.
- 일정 7개
  - 정상·늦은 입력: 00:00~23:59:59.999999
  - 나머지: 09:00~17:00
  - 날짜 매핑은 문서와 같다.
- 지원 7개는 모두 demo-worker의 SELECTED 지원이다.
- 약정 7개의 확정 시각은 시작 하루 전이다.
- 기존 서비스 호출
  - 취소(7005)·조기 종료(7006)만 `recordValidatedChange`를 호출한다. 조기 종료 시각은 14:00+09:00이다.
  - 완료(7007)만 `completion.complete`를 호출한다.

**재사용**
- 생성, 기록, 완료 호출이 없다.
- 조회는 `findCases(공고, 고용주, 근로자, 표식)`로 한다. SQL 선언에 네 조건이 모두 있다.

**7개 사례 연결**
- 조회 순서를 섞어도 근무일 매핑으로 분류하고, 응답은 정해진 사례 순서로 정렬된다.
- 연결 값: assignmentId, 일정 ID, 약정 시작·종료(+09:00), 입력 마감(종료 + 24시간), 상태
  - 상태: CONFIRMED / CANCELLED / EARLY_ENDED / COMPLETED
- suggestedRequest 기본 종료 시각
  - 정상: 기준 시각 1분 전(`00:29:00.123456+09:00`, 마이크로초 정밀도)
  - 늦은 입력: 종료 1시간 전
  - 기한 초과·완료: 약정 범위 안이고 기준 시각 이하
  - 시작 전·취소: 약정 시작
  - 자정 30초 뒤 준비하면 정상 기본값은 약정 시작으로 보정된다.
- 기존 조기 종료의 suggestedRequest는 저장된 시점(`2026-11-26T14:00+09:00`)·사유·설명과 같다.

**반복 준비**
- 사용자가 기록한 조기 종료와 완료를 초기화하지 않는다. 저장된 값으로 안내한다.

**실패 처리**
- 근무일이 매핑되지 않는 행이 있으면 503이다(성공으로 표시하지 않음).
- 기록 경계, 완료 서비스, 공고 저장 실패는 예외로 전파된다.
- API에서 준비 실패는 403, 409, 503(WorkCompletionException·DB 포함), 500이며 no-store이다.

**회원 데이터와 생성자**
- 회원 저장소는 `findById`·`findByLoginId`만 호출한다. 비밀번호를 수정하는 경로가 없다.
- 생성자 의존성은 11개이며 순서가 확인되었다. Clock은 `@Qualifier("insuranceChangeClock")`이다.

**demo API**
- 화면 GET: HTML, no-store, 서비스 미호출
- 준비: 신규 201, 재사용 200. 응답 JSON의 소수초 6자리가 보존된다.

### 6.8 화면 단위 테스트 — 통과 (Node 가짜 DOM·fetch, 17/17)

**로그인과 진입**
- 진입할 때 `GET /api/auth/me`만 호출하고 POST는 없다.
- 고용주만 준비·기록할 수 있다. 근로자·비로그인 상태에서는 클릭해도 CSRF 발급과 POST가 없다.
- `farm-login.html` 스크립트를 실행해 보면 조기 종료 링크는 고용주에게만 보인다.

**준비와 사례 선택**
- 준비 → 사례 7행 → 정상 사례 자동 선택 → 입력 연결 순으로 진행된다. 약정 시작·종료·직접 입력 마감이 표시된다.
- 다른 사례를 수동으로 고르면 입력이 그 사례 값으로 바뀐다.

**달력과 전송 시각**
- 서버 기본값 `…00:29:00.123456+09:00`은 달력에 `…00:29:00.123456`, 전송 시각에 원문으로 들어간다. 소수초 6자리가 유지된다.
- `Z` 값(`13:59:59.999999Z`)은 달력에 GMT+9 `22:59:59.999999`로 표시되고, 전송은 Z 원문 그대로 한다. Date 변환 과정에서 마이크로초를 잃지 않는다.
- 달력을 편집하면 전송 시각이 바뀐다: `00:10` → `00:10:00+09:00`, `00:10:05` → `…+09:00`.
- 전송 시각이 비어 있으면 POST가 없다.

**CSRF와 노출 방지**
- POST 5회 모두 직전에 새로 발급받은 서로 다른 토큰을 보낸다. `same-origin`이고 Authorization·Cookie·보험 키 헤더는 없다.
- CSRF 발급 실패 4종(500, 빈 토큰, headerName 누락, 통신 오류)과 준비 시 403이면 쓰기 POST가 없다.
- 토큰이 화면, 요청 기록, 웹 저장소에 없다. 금지 API 접근은 0건이다.

**최초 기록과 재요청**
- 최초 기록 200과 비교 기준을 보관한다. 실제 종료 시각과 저장 시각이 따로 표시된다.
- 기록 POST 응답과 상태 GET 응답은 별도 칸에 표시된다.
- 동일 재요청
  - 사유, 설명, 달력을 바꿔도 직전 성공 본문을 그대로 보낸다.
  - 결과가 같으면 "기존 결과 유지 확인"을 표시한다.
  - 서버가 다른 버전·시각을 돌려주면 달라진 필드를 오류로 표시한다(비교 로직이 실제로 동작함).
- 다른 사유 버튼은 WEATHER 성공 뒤에는 OTHER/null을, OTHER 성공 뒤에는 FARM_CIRCUMSTANCES/null을 보낸다.
- 종료 시각 변경 버튼
  - 정확히 +1초(마이크로초 차이 1,000,000)를 더하고 소수초를 유지한다. 시각 외 필드는 그대로다.
  - Z 값이 분 경계를 넘는 경우(`22:59:59.999999` → `23:00:00.999999+09:00`)도 맞다.
- 다른 내용 409를 받아도 마지막 성공 요청과 비교 기준이 유지된다.

**거절 사례 표시**
- 기한 초과는 409와 문의 안내를 표시한다.
  - 표와 DB 재조회는 "정상 확정", "완료 일시: 없음"이다. 완료로 표시하지 않는다.
  - 성공 필드를 채우지 않는다.
- 시작 전, 취소, 완료 사례는 각 409 코드를 표시한다.
- 이미 기록된 사례는 "기존 결과 200" 안내와 저장된 ID·시각을 표시한다. "최초 기록"이라는 표현이 없다.

**오류 상황**
- 보조 GET이 500이나 통신 오류여도 기록 응답, 선택, 재요청 버튼이 유지된다. 이어서 재요청도 할 수 있다.
- 중복 클릭 중에 동시 POST가 생기지 않는다.
- POST 500이나 통신 오류일 때 자동 재전송이 없고, 결과가 불확실하다고 안내한다.

**계정 변경과 pagehide**
- 요청 전이나 요청 중에 계정이 바뀌면 이전 사례·응답·전송 시각을 지운다.
- pagehide 뒤에 도착한 응답은 무효화된다.

**설명 입력**
- 길이는 코드 포인트 기준으로 표시한다. 501자 설명도 그대로 전송되어 서버 400이 표시된다. 기상 세부 사유를 비운 요청은 400이다.
- 사용자 문자열은 textContent로만 표시된다.

## 7. 결함과 관찰 사항

**결함: 없음.**

**관찰 사항 (결함 아님, 정책 확인용)**

| ID | 내용 |
|---|---|
| O-1 | 경로 ID `01`처럼 앞에 0이 붙은 값은 400으로 거절된다. 취소 API와 같은 정책인지 확인이 필요하다. |
| O-2 | 신규 요청은 시작 여부 → 입력 기한 → 실제 종료 범위 순으로 검사한다. 그래서 기한이 지난 뒤 범위 밖 시각을 보내면 400이 아니라 409(기한)로 응답한다. 문서에 적힌 순서와 같다. |
| O-3 | 본문의 정의되지 않은 필드는 거절하지 않고 무시한다. 확인한 것은 standalone 기본 JsonMapper의 동작이며, Boot 구성 JsonMapper는 실행하지 않았다. |
| O-4 | 화면의 달력은 `datetime-local step="0.000001"`이다. 실제 브라우저가 6자리 소수초를 표시·편집하는지는 가짜 DOM으로 검증할 수 없다. 표시가 안 되면 초 단위로 대체하고 전송 시각은 원문을 유지하는 코드가 있다. 이 대체 경로는 가짜 DOM에서 재현되지 않아 실행되지 않았다. |
| O-5 | 동일 재요청은 기록기에서 시간대 검사를 하지 않는다. 그 대신 서비스가 응답 변환 중에 검사하며, 시간대가 깨져 있으면 503이다. |
| O-6 | 시연 준비의 기존 취소·조기 종료 사례는 내부 `recordValidatedChange`로 만든다. 이 경로에는 신규 API의 시간 검사가 없지만, 시연 전용이며 문서에 명시되어 있다. |

## 8. 미검증 범위

- 실제 MySQL의 다음 동작: 행 잠금과 잠금 대기, 교착, 잠금 시간 초과, 커밋 순서, 롤백, `work_change` 유니크 제약, native SQL(`findCases`, `findFixturePosting`, `ensureExists`). mock 호출 순서만 확인했으며 실제 DB 롤백·동시성·커밋 순서를 증명하지 않는다.
- 동시 요청의 직렬화: 조기 종료와 완료, 조기 종료와 취소, 병렬 시연 준비
- `@Transactional` 실제 적용: 선언만 확인
- 실제 Spring Security 필터 체인: 익명 401, CSRF 403, hasRole, 세션 격리, 403 JSON 분기의 실제 동작
- Spring Boot `/error`, Boot가 구성한 JsonMapper와 메시지 변환기
- `@Profile("farm-demo")` 빈의 실제 활성화
- 실제 브라우저의 datetime-local, 렌더링, 접근성. 화면 가짜 서버의 판단은 실제 API 검증이 아니다.
- 보험 서버의 주기 조회에서 EARLY_ENDED 버전이 실제로 노출되는지

## 9. 변경 파일 목록

신규(모두 미커밋):
- `farm-api/src/test/java/com/wonday/farm/change/validation/EarlyEndInputTest.java`
- `farm-api/src/test/java/com/wonday/farm/change/dto/WorkEarlyEndRequestJsonTest.java`
- `farm-api/src/test/java/com/wonday/farm/change/service/WorkChangeRecorderEarlyEndTest.java`
- `farm-api/src/test/java/com/wonday/farm/change/service/WorkEarlyEndServiceTest.java`
- `farm-api/src/test/java/com/wonday/farm/change/api/WorkEarlyEndApiTest.java`
- `farm-api/src/test/java/com/wonday/farm/demo/earlyend/DemoWorkEarlyEndFixtureServiceTest.java`
- `farm-api/src/test/java/com/wonday/farm/demo/earlyend/DemoWorkEarlyEndApiTest.java`
- `farm-api/src/test/java/com/wonday/farm/demo/earlyend/WorkEarlyEndDemoPageTest.java`
- `farm-api/docs/verification/2026-11-28-change-03-early-end/work-early-end-page.test.mjs`
- `farm-api/docs/verification/2026-11-28-change-03-early-end/VERIFICATION_REPORT.md` (이 문서)

수정하지 않은 것:
- 운영 코드, HTML, 마이그레이션, Gradle, CI, 기존 테스트·보고서
- 보조 worktree
- 다른 사람의 미커밋 변경

커밋·푸시·브랜치 변경은 하지 않았다.
