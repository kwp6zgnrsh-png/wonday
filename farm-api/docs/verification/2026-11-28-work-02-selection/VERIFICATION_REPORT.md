# WORK-02·03·04 선정·약정·조건 재확인 단위 검증 보고서

- 검증일: 2026-11-28
- 검증자: Claude (독립 검증, 운영 코드 미수정)
- 범위: 선정 API, 신규 약정 생성, 근로자 조건 재확인 API, APP-04·WORK-01 확인 상태 표시, farm-demo 시연 데이터 준비·화면
- 이번 결과는 이 보고서의 자동 테스트 실행만 집계한다.
  - 사용자가 farm-demo에서 수동으로 확인한 결과(선정 201·재요청 200·조건 재확인·409)와 합산하지 않는다.
  - 이전 기능의 테스트 수(WORK-01 화면 27개 등)와도 합산하지 않는다.

## 1. 브랜치와 기준

| 항목 | 값 |
|---|---|
| 작업 폴더 | `C:\Users\HCB\Desktop\wonday` (원래 저장소, `.codex` worktree 아님) |
| 브랜치 | `work-selection` (전환하지 않음) |
| 기준 HEAD | `842ef8b fix(farm): 연락처 조회의 포커스 이탈 경합 수정` |
| AGENTS.md | 저장소 루트·farm-api 모두 없음 |
| 대상 구현 | 미커밋 상태 (수정 14개·신규 파일 다수, 아래 9절) |

- 다른 사람의 미커밋 변경은 열람만 하고 수정하지 않았다.
  - `FlywayMigrationTests.java` 수정분도 이에 해당하며, 실행하지 않았다.
- 작업 전후로 `src/main`·`src/test`·Gradle·`.github`·`docs/*.md`의 sha256과 `git diff` 해시를 비교했다.
  - 기존 파일의 해시는 바뀌지 않았다.
  - 차이는 새 테스트 파일 추가뿐이다.

## 2. 작성한 테스트 파일 (모두 신규, 기존 테스트 수정 없음)

| 파일 | 검증 방식 | 테스트 수(최종) |
|---|---|---:|
| `src/test/java/com/wonday/farm/work/testsupport/SelectionFixtures.java` | 가상 데이터 도우미 | — |
| `work/service/WorkSelectionServiceTest.java` | 실제 서비스 + 저장소 mock + 고정 Clock(순서 확인은 Clock mock) | 38 |
| `work/api/WorkSelectionApiTest.java` | standalone MockMvc + 실제 오류 처리기 | 25 |
| `work/entity/WorkAssignmentSelectionTest.java` | 순수 엔티티 | 6 |
| `work/service/SelectionTermsTest.java` | 순수 규칙 | 10 |
| `work/validation/SelectionInputTest.java` | 순수 입력 검사 | 19 |
| `application/service/ConditionConfirmationServiceTest.java` | 실제 서비스 + 저장소 mock + 고정 Clock + 실제 JsonMapper | 19 |
| `application/api/ConditionConfirmationApiTest.java` | standalone MockMvc | 12 |
| `application/service/ConditionConfirmationMapperTest.java` | 순수 매핑 + 실제 APP-04 매퍼 | 8 |
| `application/entity/ApplicationConditionAcknowledgementTest.java` | 엔티티 + 매핑 선언 | 3 |
| `work/service/WorkSelectionQueryResponsesTest.java` | WORK-01 실제 서비스 + 저장소 mock, SQL 선언 검사 | 5 |
| `demo/selection/DemoWorkSelectionFixtureServiceTest.java` | 실제 준비 서비스 + mock 저장소·PasswordEncoder·변경/완료 서비스 + 고정 Clock | 22 |
| `demo/selection/DemoWorkSelectionApiTest.java` | standalone MockMvc | 5 |
| `demo/selection/WorkSelectionDemoPageTest.java` | 정적 문자열(HTML·SecurityConfig 선언) | 4 |
| `docs/verification/2026-11-28-work-02-selection/work-selection-page.test.mjs` | Node 가짜 DOM·fetch로 화면 스크립트 원문 실행 | 21 |

Java 테스트 경로의 앞부분은 `farm-api/src/test/java/com/wonday/farm/`이다.

## 3. 실제 실행한 명령

Java (`JAVA_HOME=…/jdk-21.0.12.1+1`, farm-api 폴더). `unitTest`에 정확한 클래스 이름 13개만 지정했다:

```
./gradlew unitTest \
  --tests "com.wonday.farm.work.service.WorkSelectionServiceTest" \
  --tests "com.wonday.farm.work.api.WorkSelectionApiTest" \
  --tests "com.wonday.farm.work.entity.WorkAssignmentSelectionTest" \
  --tests "com.wonday.farm.work.service.SelectionTermsTest" \
  --tests "com.wonday.farm.work.validation.SelectionInputTest" \
  --tests "com.wonday.farm.application.service.ConditionConfirmationServiceTest" \
  --tests "com.wonday.farm.application.api.ConditionConfirmationApiTest" \
  --tests "com.wonday.farm.application.service.ConditionConfirmationMapperTest" \
  --tests "com.wonday.farm.application.entity.ApplicationConditionAcknowledgementTest" \
  --tests "com.wonday.farm.work.service.WorkSelectionQueryResponsesTest" \
  --tests "com.wonday.farm.demo.selection.DemoWorkSelectionFixtureServiceTest" \
  --tests "com.wonday.farm.demo.selection.DemoWorkSelectionApiTest" \
  --tests "com.wonday.farm.demo.selection.WorkSelectionDemoPageTest" --console=plain
```

Node (v26.13.1):

```
cd C:\Users\HCB\Desktop\wonday\farm-api
node --test ./docs/verification/2026-11-28-work-02-selection/work-selection-page.test.mjs
```

다음은 실행하지 않았다.
- 실행 금지 대상: `test`·전체 `unitTest`·`integrationTest`·`fullTest`·`build`·`check`·리포트·커버리지 태스크
- 반복 실행하지 않은 기존 테스트: WORK-01 화면 27개와 이전 기능 테스트

## 4. 실행 결과 (Java와 Node 별도 집계)

### Java

| 회차 | 단계 | 실행 | 성공 | 실패 | 건너뜀 |
|---|---|---:|---:|---:|---:|
| 컴파일 1·2 | `compileTestJava` 실패(테스트 코드의 이름 충돌) | 0 | — | — | — |
| 실행 1 | 첫 실제 실행 | 175 | 173 | 2 | 0 |
| 실행 2 (최종) | 415 확인을 별도 테스트로 분리한 뒤 | **176** | **174** | **2** | **0** |

- 컴파일 실패 2회는 테스트를 실행하지 못한 회차다. 원인은 `SelectionTermsTest`의 지역 메서드 `schedule()`이 정적 import와 충돌한 것이다. 이름을 `open()`으로 바꿨다.
- 최종 XML 파일 24개(@Nested 포함)는 모두 선택한 13개 클래스의 것이다. 합계 176은 Gradle 집계와 같다.
- 실패 2건은 결함 D-1의 재현 테스트다(5절). 기대값을 낮추거나 제외하지 않았다.
- 실행 1에서는 `ConditionConfirmationApiTest`의 415 확인이 경로 ID·principal 확인과 한 테스트에 묶여 있었다. 그래서 415에서 멈추면 뒤 단언이 실행되지 않았다. 실행 2에서 별도 테스트로 나눴다(테스트 수 +1). 나머지 단언은 통과했다.

### Node 화면

| 회차 | 실행 | 성공 | 실패 | 건너뜀 |
|---|---:|---:|---:|---:|
| 1차 | 21 | 20 | 1 | 0 |
| 최종 | **21** | **21** | **0** | **0** |

- 1차 실패는 테스트 코드 문제였다.
  - 아직 스크립트가 건드리지 않은 `write-status` 가짜 요소를 직접 읽어 `undefined`가 됐다.
  - 요소를 생성하는 접근자 `p.el()`로 바꿨다. 기대(성공 결과 미표시)는 그대로다.
- 보조 확인으로 scratchpad 사본에 스크립트 변형 4개를 넣어 같은 21개를 실행했다. 모두 실패로 잡혔다. 정식 결과에는 넣지 않았다.

| 넣은 변형 | 실패 수 |
|---|---:|
| 이전 버전 버튼이 같은 버전 전송 | 2 |
| 재요청 본문 변경 | 1 |
| GET이 쓰기 결과를 덮음 | 3 |
| 응답 후 계정 재확인 제거 | 5 |

## 5. 발견한 결함

### D-1. JSON이 아닌 형식의 선정·재확인 요청 415 응답에 `no-store`와 오류 코드가 붙지 않는다 (낮음)

- 실패 테스트
  - `WorkSelectionApiTest.mediaType` — "application/json 이외 형식 → 415 INVALID_REQUEST (no-store), 서비스 호출 없음"
  - `ConditionConfirmationApiTest.mediaType` — "text/plain → 415 INVALID_REQUEST (no-store), 서비스 호출 없음"
- 재현 입력: `POST /api/job-postings/101/schedules/201/applications/501/selection`, `Content-Type: text/plain`, 본문 `{"termsVersion":3}`. 폼 형식도 같고, 재확인 경로도 `text/plain`으로 같다.
- 기대 결과
  - `WorkSelectionErrorHandler.mediaType()`이 의도한 415 `{"code":"INVALID_REQUEST",…}`
  - 요구사항 "성공·오류 Cache-Control: no-store"
- 실제 결과: 상태는 415다. `Cache-Control` 헤더가 null이다. 본문 code 단언은 헤더 단언에서 멈춰 실행되지 않았다.
- 원인
  - 두 API가 `@PostMapping(consumes = "application/json")`을 선언했다.
  - 형식이 맞지 않으면 핸들러를 고르기 전에 매핑 단계에서 `HttpMediaTypeNotSupportedException`이 발생한다. 이때 핸들러는 없다.
  - `WorkSelectionErrorHandler`는 `assignableTypes = {WorkSelectionApi, ConditionConfirmationApi}`로 범위가 정해져 있다. 그래서 핸들러가 없는 예외에는 적용되지 않는다(Spring `HandlerTypePredicate`는 null 핸들러 형식에 false).
  - 결국 `DefaultHandlerExceptionResolver`가 기본 415를 보낸다. `mediaType()` 처리기는 도달하지 않는 코드다.
  - 저장소에 범위 없는 전역 advice가 없다(grep 확인). 따라서 실제 앱에서도 이 처리기는 적용되지 않을 것으로 본다. 다만 Boot `/error` 응답 형태는 실제 서버 없이 확인하지 않았다.
- 영향: 서비스는 호출되지 않는다(쓰기 없음). 오류 응답 형식과 캐시 금지 헤더만 문서·의도와 다르다. API 문서의 오류 표에는 415가 없다.
- 수정 방향(참고, 운영 코드는 고치지 않음): `consumes`를 빼고 처리기에서 형식을 검사한다. 또는 전역 처리기에서 해당 경로를 처리한다.

### 확인 후 결함 아님으로 판정한 항목

- WORK-01 조회 SQL 별칭 누락 의심
  - `WorkApplicantRepository.Row`가 재확인 getter를 상속한다. 처음에는 WORK-01 SQL이 바뀌지 않은 것으로 보였다.
  - 실제로는 `SELECT = MyApplicationQueryRepository.SELECT_ROW + …`, `FROM = FROM_ROW + …`를 재사용하므로 별칭과 LEFT JOIN이 포함된다.
  - Spring Data `TupleBackedMap.get`은 없는 별칭에 조용히 null을 반환한다(소스 jar에서 확인).
  - 그래서 Row getter마다 SQL 별칭이 있는지 검사하는 테스트를 추가했다(`aliasesCoverRows`, 통과).

## 6. 관찰 사항 (결함 아님, 정책 확인 권장)

- O-1 (화면): 선정·재확인에 성공한 뒤 다음 동작을 하면 마지막 요청이 지워진다.
  - 해당 동작: 같은 사례를 다시 선택하기, 사례 새로 불러오기, 준비 버튼 누르기(`clearTarget`)
  - 결과: 「같은 요청 다시 보내기」가 비활성으로 돌아간다.
  - 일반 선정 버튼으로 다시 보내면 서버가 기존 결과를 200으로 반환한다(`[관찰]` 테스트로 기록).
- O-2 (시연 준비): `prepare`가 시계를 두 번 읽는다(표식 날짜용, 사례 조회 날짜용).
  - GMT+9 자정을 넘는 순간에 준비하면 전날 표식으로 만들고 다음 날로 조회한다.
  - 그러면 `created=true`인데 사례가 비어 있을 수 있다(`[관찰]` 테스트로 기록).
- O-3 (선정): 확정 근무가 있는 지원의 재요청에서 확정 근무가 `EARLY_ENDED`로 끝났는지 확인한다.
  - `WorkChange.Type`이 현재 CANCELLED·EARLY_ENDED 두 종류뿐이라 문제는 없다.
  - 종류가 늘면 `change.isPresent()`만으로 EARLY_ENDED로 판정하는 분기를 다시 봐야 한다.
- O-4 (재확인): 최초 지원 버전이 최신인 경우에도 같은 버전의 기존 재확인 기록 조회와 시각 조회를 한다. 저장은 하지 않는다(테스트로 확인). 동작상 문제는 없다.

## 7. 확인한 범위와 검증 방식 구분

표기는 [mock] 서비스 단위(저장소 mock), [standalone] MockMvc, [선언] 문자열·리플렉션 검사, [스크립트] Node 가짜 DOM 실행이다.

### 7.1 선정 서비스 [mock]

- **권한·소속**
  - 고용주 역할 확인(없음·근로자·알 수 없는 코드 → AccessDenied)
  - 세션 ID로 `findOwnedForUpdate(공고, 고용주)` 호출
  - 타인 공고·다른 공고의 일정·다른 날짜의 지원은 각각 404
  - ID·버전 입력 오류는 400이며 저장소 호출이 없다.
- **새 선정 대상**
  - 현재 회차 PENDING만 허용(과거 회차·SELECTED·NOT_SELECTED·CANCELLED → 409)
  - 대상 회원은 현재 WORKER여야 한다(없음·고용주 → 409 WORKER_UNAVAILABLE).
  - 다른 회차의 유효한 확정 근무 → 409 ALREADY_CONFIRMED
- **상태·시간**
  - OPEN·PAUSED 허용, CLOSED·REMOVED → 409
  - 시작 1µs 전 허용, 정확히 시작 시각과 그 이후 → 409
  - 서버 시각은 잠금·정원·중복 검사 뒤 한 번만 읽고, 그 값을 확정 시각으로 저장한다(Clock mock InOrder).
- **버전·재확인**
  - 고용주가 본 버전 ≠ 최신 → 409
  - 최초 지원 버전 = 최신이면 재확인을 조회하지 않는다.
  - 최초 ≠ 최신이면 같은 지원 ID·최신 버전의 기록만 인정한다. 다른 지원(502)·이전 버전(v2) 기록으로는 통과하지 않는다.
  - 최초 버전 null + 기록 없음 → 409
  - 좌표 없음 → WORK_LOCATION_INCOMPLETE(좌표를 보완하지 않음), 시간대 불명 → WORK_TERMS_UNAVAILABLE
- **정원**: 실제 집계 호출. 1 < 2면 선정, 2·3(같음·초과)이면 CAPACITY_FULL.
- **실패 시 불변**: 모든 실패에서 지원 상태가 PENDING으로 유지되고 assignment 저장이 없다.
- **성공**
  - 지원 SELECTED, 확정 근무 1건 저장
  - 확정 시각 = 서버 UTC 마이크로초(`confirmedAt`·`confirmedAtUtc` 동일)
  - 최초 지원 시각·버전·회차 유지, 응답 +09:00
- **재요청**
  - 정상 확정은 200 대상이다. 기존 ID·시각·약정 버전을 반환하고 정원·중복·시각·재확인 조회가 없다.
  - 현재 CLOSED·시작 경과·최신 v5·요청 v2여도 200이다.
  - 역할·소유권·소속 검사는 재요청에도 적용된다.
  - CANCELLED → 409(되살리지 않음), EARLY_ENDED·COMPLETED → 200 + 해당 상태·완료 시각
  - 완료 정보가 반쪽만 있으면 503
  - 과거 행의 약정 버전·UTC 시각이 null이면 null을 그대로 반환한다.
  - 지원 PENDING + 확정 근무 → 409 APPLICATION_STATE_CONFLICT
- **트랜잭션**
  - [선언] `@Transactional(READ_COMMITTED)`
  - [mock] 호출 순서: 공고 → 일정 → 지원 → 확정 근무 → 정원 → 중복 → 시각 → 저장
  - [mock] 저장 실패는 예외로 전파되고 결과를 반환하지 않는다.

### 7.2 약정 복사·입력·응답

- [엔티티] 날짜·시간대·주소·위도·경도·작목·작업 유형·시작/종료·휴게·일급·버전·확정 시각을 복사한다.
- [엔티티] 휴게 없음은 양쪽 null이다.
- [엔티티] 이후 일정 변경(v4)·일급·주소·좌표를 바꿔도 저장된 약정은 그대로다.
- [엔티티] 좌표 null은 null로 복사한다(자동 보완 없음).
- [엔티티] `select()`는 현재 PENDING만 바꾸고 최초 정보를 유지한다.
- [standalone] 신규 201·기존 200, 세션 principal ID 전달(본문의 employerId·workerId·시각 무시)
- [standalone] 다음 입력은 400 INVALID_REQUEST다.
  - 소수(3.0·3.5)·숫자 문자열·배열·불리언·2147483648·0·음수·null·누락·빈 본문·깨진 JSON·지수
  - 경로 ID 0·음수·앞자리 0·문자·범위 초과
- [standalone] 403·404·409(무결성 위반 포함)·503(DB·트랜잭션·쿼리 시간 초과)·500 매핑을 확인했다.
  - 오류 본문은 code·message 두 필드뿐이다.
  - SQL·접속 정보·비밀번호·전화번호·예외 이름이 노출되지 않는다.
- [standalone] 성공·오류 응답 no-store(415 제외, D-1)
- [standalone] JSON 표현은 `+09:00`·마이크로초·null·필드 목록을 확인했다. GET은 405다.

### 7.3 조건 재확인 서비스·API

- [mock] 역할 WORKER만 허용한다.
- [mock] 다음 경우는 404다: 본인 소속 조회 없음, 잠금 뒤 소유자 불일치, 공고·일정 소멸.
- [mock] 다음 경우는 409다: 과거 회차, PENDING 아님, PENDING + 확정 근무, CLOSED·REMOVED, 시작 시각 이상, 버전 불일치(예전 기록이 있어도). PAUSED는 허용한다.
- [mock] 저장: 지원 ID·최신 버전·서버 UTC 시각으로 1건을 저장한다.
  - 조건 원문은 `SelectionTerms.snapshot(잠근 일정)`과 같다.
  - 원문에는 주소·좌표·작업 내용·정원·일급·근무/휴게 시각이 들어 있다.
  - 최초 지원 버전·시각·회차·상태는 바뀌지 않는다.
  - 선정·정원 조회·assignment 저장이 없다(자리를 확보하지 않음).
- [mock] 같은 최신 버전 재요청은 최초 ID·시각·원문을 유지하고 새로 저장하지 않는다.
- [mock] 최초 지원 버전이 최신이면 저장 없이 INITIAL_APPLICATION·true를 반환하고, 재확인 ID·버전·시각은 null이다.
- [mock] 처리 순서: 역할 → 소속 → 공고 잠금 → 일정 → 지원 → 확정 근무 → 기존 기록 → 시각 → 저장
- [standalone] 200·no-store, +09:00·마이크로초, 최초 지원 확인의 null 필드
- [standalone] 잘못된 본문·경로는 400이다.
- [standalone] principal 없음·AccessDenied는 `/api/me` 경로라 WORKER_ROLE_REQUIRED다.

### 7.4 APP-04·WORK-01 확인 상태

- [순수·mock] 각 사례의 매핑 결과는 다음과 같다.

| 사례 | 결과 |
|---|---|
| 최초 = 최신 | INITIAL_APPLICATION·true |
| 최초 ≠ 최신, 최신 버전 재확인 있음 | RECONFIRMATION·true |
| 마지막 재확인이 이전 버전 | RECONFIRMATION·false, 과거 ID·버전·시각 유지 |
| 최초 ≠ 최신, 재확인 없음 | INITIAL_APPLICATION·false |
| 최초 버전·재확인 모두 없음 | NONE·null·false |
| 최초 버전 없음, 최신 버전 재확인 있음 | RECONFIRMATION·true |

- [mock] APP-04 매퍼의 목록·상세와 WORK-01 서비스의 목록·상세에 `conditionConfirmation`이 전달된다.
- [mock] CHANGED이면서 latestTermsConfirmed=true인 경우를 확인했다. UNCHANGED·UNKNOWN 비교 의미는 유지된다.
- [선언] APP-04 `FROM_ROW`는 같은 `app.id`의 최대 버전 재확인만 LEFT JOIN한다.
- [선언] WORK-01은 같은 SELECT/FROM을 재사용한다. Row를 반환하는 조회(각 2개 이상)는 모두 재확인 JOIN을 포함한다.
- [선언] Row getter마다 SQL 별칭이 있다.
- 다른 회차 기록이 연결되지 않는다는 것은 SQL 문자열(`application_id = app.id`)로만 확인했다.

### 7.5 시연 데이터 준비 [mock]

- **프로필·계정**
  - [선언] API·서비스·저장소·오류 처리기 모두 `@Profile("farm-demo")`
  - 회원 없음·알 수 없는 역할 → AccessDenied
  - 고용주로 준비하면 상대는 demo-worker, 근로자로 준비하면 상대는 demo-employer다. 같은 표식 `[WORK-02 시연·고용주 10·근로자 123·2026-11-28]`과 같은 근로자 잠금을 쓴다.
  - 상대 계정 부재·역할 불일치 → 409(잠금·저장 없음)
- **잠금·재사용**
  - 잠금 행 보장 → 근로자 잠금 → 기존 조회 순서. 잠금 실패 → 503.
  - 재사용은 created=false·기존 ID이며 어떤 저장·상태 변경·비밀번호 인코딩도 없다.
- **신규 구성**
  - created=true, 공고 1개(표식 제목·정원 1·GMT+9·좌표)·일정 11개
  - 날짜: 미래 +7+i, 시작 경과 −1·조기 종료 −2·완료 −3일
  - 대상 근로자 지원 11건(1회차·5일 전) + 정원 충족용 1건
  - CHANGED는 v1 지원 → v2, STALE_VERSION은 v2 → 지원. BASELINE·UPDATED 기록 4건(변경자=고용주).
  - 취소·조기 종료·완료는 SELECTED + 확정 근무이며, 고용주 ID로 변경 기록·완료 서비스를 호출한다.
  - PAUSED·CLOSED·REMOVED는 상태 변경 3건이다(고용주·공고·일정·표식·서버 시각).
  - 정원 충족용 새 가상 근로자: `demo-selection-` 로그인, 무작위 UUID 비밀번호를 인코딩해 저장, SELECTED + 확정 근무
  - 기존 회원의 이름·전화·비밀번호 해시는 바뀌지 않는다.
  - 응답 JSON에 password·hash·인코딩 값·전화번호가 없다.
- **실패**: 상태 준비 결과 ≠ 1이면 503, 저장 예외는 전파된다. 두 경우 모두 사례 조회 응답이 없다.
- **조회**
  - 행위자 ID·GMT+9 오늘로만 조회하며 저장이 없다.
  - 공고별로 일정 순서대로 11개 사례 종류를 연결한다.
  - 고용주·근로자 계정에서 같은 ID의 사례 종류는 같다.
  - 선정으로 상태가 바뀌어도 사례 종류는 유지된다.
  - 한 공고에 12행 이상이면 503이다.
  - [선언] SQL은 행위자가 고용주이거나 지원자인 현재 회차만 조회한다. 표식은 실제 고용주·근로자 ID와 오늘로 비교한다. 정렬은 공고 desc·일정 asc.

### 7.6 demo 화면 [스크립트]

화면 스크립트 원문을 가짜 DOM·fetch로 실행해 다음을 확인했다.

- **진입**
  - `/me` → 사례 GET만 호출하고 준비 POST는 없다. same-origin·no-store.
  - 역할별 패널이 표시되고, 근로자는 `/api/me/applications/{id}`로 조회한다.
  - 401·지원하지 않는 역할이면 안내를 표시하고 중단한다.
- **준비 흐름**: 준비 버튼에서만 CSRF → `/me` → POST(CSRF 헤더만, 본문 없음) → `/me` → 첫 사례 상세. 201 다음은 200 재사용.
- **조건 조회·선정**
  - 조회 응답의 버전을 자동으로 사용한다.
  - 조건 표에서 변경 행을 강조한다. 확인 근거·재확인 ID·최초 재확인 일시를 표시한다.
  - 선정 201 → 같은 요청 200: 경로·본문이 같고, CSRF·JSON 헤더를 보내며, 준비 POST로 초기화하지 않는다.
  - 조회 뒤 서버 버전이 바뀌어도 조회한 버전 그대로 보내 409를 받는다. 쓰기 직전 추가 조회는 없다.
  - 이전 버전 버튼은 버전−1을 보낸다(버전 1이면 비활성).
- **409 표시**: CONDITION_RECONFIRMATION_REQUIRED·CAPACITY_FULL·SELECTION_NOT_ALLOWED를 표시하고, 재요청 버튼은 비활성이다.
- **재확인**
  - 재확인 200 → 같은 본문 재요청 200이며 최초 ID·시각을 유지한다.
  - 이전 버전 재확인은 409다.
  - 재확인 뒤 고용주 선정은 201이다.
- **쓰기 결과 유지**: 쓰기 결과는 후속 GET에 덮이지 않고, 진단 영역만 갱신된다.
- **불확실한 요청·중복 방지**
  - 통신 실패·JSON 없는 500 POST는 자동 재전송하지 않고 성공으로 표시하지 않는다.
  - 대기 중 연속 클릭으로 중복 전송되지 않는다.
- **계정·화면 전환**
  - POST 대기 중 계정이 바뀌면 결과를 표시하지 않고 초기화한다.
  - 같은 회원의 역할이 바뀐 경우도 초기화한다.
  - 화면 이탈 뒤 도착한 응답은 표시하지 않는다.
  - 권한 403이면 전체를 초기화한다.
- **보안·표시**
  - CSRF 토큰·password가 화면·진단·요청 기록에 없다.
  - 저장소·쿠키·console·innerHTML 접근이 없다.
  - CSRF 발급 실패 시 POST가 없다.
  - 외부 문자열은 textContent로 표시한다.
  - 한글 버튼·안내가 유지된다.
- [선언, Java 정적] 다음은 문자열 검사로만 확인했다.
  - 로그인 화면 선정 링크(기본 숨김, 고용주·근로자일 때 표시)
  - 내 지원·지원자 화면의 선정 화면 링크
  - 두 화면의 확인 상태 표시 문구
  - SecurityConfig 선언

## 8. 미검증 범위와 후속 통합 검증 필요 항목

이번 실행은 단위 테스트·standalone MockMvc·선언 검사·가짜 DOM 스크립트 실행이다. 다음은 검증하지 않았다.

- **실제 SQL (MySQL)**
  - `findOwnedForUpdate`·`findScopedForUpdate`·`findByApplicationForUpdate`·`findForUpdate`의 실제 행 잠금
  - `countOccupyingAssignments`·`findBlockingAssignment` 집계 결과
  - APP-04·WORK-01의 재확인 LEFT JOIN·`max(terms_version)` 서브쿼리 결과, 행 중복 여부, 정렬, 타입 변환
  - 시연 사례 SQL의 표식 비교(`cast(:today as char)`), 정렬
- **동시성**
  - 동시 선정 때 정원 초과 방지
  - 같은 지원의 동시 선정·재확인
  - UNIQUE(지원 ID·버전)·assignment UNIQUE 충돌 → 409 WORK_STATE_CONFLICT 경로
  - 근로자 잠금에 의한 시연 준비 직렬화
- **롤백·원자성**
  - 저장 실패 시 SELECTED 변경과 assignment INSERT의 실제 롤백(mock에서는 메모리 상태가 SELECTED로 남는다)
  - 시연 준비 중간 실패 시 전체 롤백
- **V13 마이그레이션**
  - `V13__add_selection_and_condition_acknowledgement.sql` 적용
  - 새 컬럼(`confirmed_at`·`agreed_terms_version`)·테이블·제약
  - 기존 행 NULL 유지
  - Flyway 통합 테스트(수정분 포함)
- **Security 체인**
  - 비로그인 401
  - `hasRole("EMPLOYER")`·`hasRole("WORKER")`·`hasAnyRole` 실제 판정
  - CSRF 누락 403
  - 세션 쿠키
  - accessDeniedHandler의 JSON 본문(문자열 선언만 확인)
  - farm-demo 프로필에서의 빈 등록
- **실제 연동 서비스**: `WorkChangeRecorder`·`WorkCompletionService`의 실제 검증(과거 날짜 조기 종료·완료 허용 여부)
- **실제 브라우저**: 이벤트 순서, 렌더링, 두 창을 오가며 진행하는 실제 흐름
- **D-1의 실제 서버 응답**: Boot `/error` 처리 결과 형태

후속 통합 검증 권장 항목:
1. MySQL 8.4 격리 환경에서 V13을 적용한 뒤 선정·재확인 저장과 UNIQUE 충돌을 확인한다.
2. 동시 선정 2건에서 정원 1이 지켜지는지 확인한다.
3. 실제 Security 체인에서 401·403·CSRF를 확인한다.
4. APP-04·WORK-01 조회 SQL의 재확인 JOIN 결과를 확인한다.
5. 시연 준비 전체 흐름(실제 변경·완료 서비스 포함)과 중간 실패 롤백을 확인한다.

## 9. 운영 코드 변경 여부와 최종 git 변경 목록

- 운영 코드·HTML·마이그레이션·Gradle·CI·기존 테스트·기존 보고서·문서는 수정하지 않았다.
  - 작업 전후 sha256 비교에서 기존 파일 변경 0건
  - `git diff` 해시 동일 (`e3364f73…`)
- 커밋·푸시·PR·병합·stash·reset·브랜치 전환을 하지 않았다.

이번 검증으로 추가된 파일 (모두 untracked):

```
farm-api/src/test/java/com/wonday/farm/work/testsupport/SelectionFixtures.java
farm-api/src/test/java/com/wonday/farm/work/service/WorkSelectionServiceTest.java
farm-api/src/test/java/com/wonday/farm/work/api/WorkSelectionApiTest.java
farm-api/src/test/java/com/wonday/farm/work/entity/WorkAssignmentSelectionTest.java
farm-api/src/test/java/com/wonday/farm/work/service/SelectionTermsTest.java
farm-api/src/test/java/com/wonday/farm/work/validation/SelectionInputTest.java
farm-api/src/test/java/com/wonday/farm/work/service/WorkSelectionQueryResponsesTest.java
farm-api/src/test/java/com/wonday/farm/application/service/ConditionConfirmationServiceTest.java
farm-api/src/test/java/com/wonday/farm/application/api/ConditionConfirmationApiTest.java
farm-api/src/test/java/com/wonday/farm/application/service/ConditionConfirmationMapperTest.java
farm-api/src/test/java/com/wonday/farm/application/entity/ApplicationConditionAcknowledgementTest.java
farm-api/src/test/java/com/wonday/farm/demo/selection/DemoWorkSelectionFixtureServiceTest.java
farm-api/src/test/java/com/wonday/farm/demo/selection/DemoWorkSelectionApiTest.java
farm-api/src/test/java/com/wonday/farm/demo/selection/WorkSelectionDemoPageTest.java
farm-api/docs/verification/2026-11-28-work-02-selection/work-selection-page.test.mjs
farm-api/docs/verification/2026-11-28-work-02-selection/VERIFICATION_REPORT.md
```

작성자의 기존 미커밋 변경(수정 14개·신규 운영 파일·문서·V13)은 그대로 두었다.

## 10. D-1 수정 후 재검증 (2026-11-28, 검증자)

> 이 절은 D-1 수정 뒤 두 API 테스트 클래스만 따로 실행한 기록이다.
> 4절의 Java 176개(174 통과·2 실패)와 Node 21개 기록은 수정 전 결과로 그대로 두고, 이번 37개와 합산하지 않는다.

### 10.1 상태

- 브랜치 `work-selection`, HEAD `842ef8b`(변경 없음), AGENTS.md 없음.
- 원래 wonday 폴더에서 작업했다. 기존 미커밋 변경은 그대로 두었다.
- 9절 실행 후 sha256과 비교한 결과, 바뀐 파일은 다음 두 운영 파일뿐이다. 테스트 파일·보고서·다른 운영 코드는 그대로다.
  - `src/main/java/com/wonday/farm/work/api/WorkSelectionApi.java`
  - `src/main/java/com/wonday/farm/application/api/ConditionConfirmationApi.java`
- 수정 내용(소스 확인): 두 메서드의 `@PostMapping(consumes = "application/json")`을 `@PostMapping`으로 바꿨다. `produces`·`@Valid @RequestBody`·no-store 성공 응답은 그대로다.

### 10.2 실행 명령

```
cd C:\Users\HCB\Desktop\wonday\farm-api
./gradlew unitTest --tests "com.wonday.farm.work.api.WorkSelectionApiTest" --tests "com.wonday.farm.application.api.ConditionConfirmationApiTest" --console=plain
```

JDK 21(`jdk-21.0.12.1+1`) 환경에서 실행했다. 다른 클래스·Node 화면 테스트·통합·커버리지는 실행하지 않았다.

### 10.3 결과

| 클래스 | 실행 | 성공 | 실패 | 건너뜀 |
|---|---:|---:|---:|---:|
| WorkSelectionApiTest | 25 | 25 | 0 | 0 |
| ConditionConfirmationApiTest | 12 | 12 | 0 | 0 |
| 합계 | **37** | **37** | **0** | **0** |

- 결과 XML은 두 클래스 것만 있다. 예상한 37개와 실행 수가 같다. `BUILD SUCCESSFUL`.
- 기존 실패 2건(`WorkSelectionApiTest.mediaType`, `ConditionConfirmationApiTest.mediaType`)은 통과해 **D-1은 해결**됐다.
  - 기대값은 바꾸지 않았다.
  - 확인한 내용: 선정 API는 `text/plain`·`application/x-www-form-urlencoded`, 재확인 API는 `text/plain`에서 415, `code=INVALID_REQUEST`, 본문 필드 `code`·`message`만, `Cache-Control: no-store`, 서비스 호출 없음.
- 응답 `message`가 "application/json 형식으로 요청해 주세요."인지는 테스트가 단언하지 않는다.
  - `WorkSelectionErrorHandler.mediaType()`의 문자열을 소스로만 확인했다.
  - 이번 요청 범위에 맞춰 테스트는 수정하지 않았다.
- 기존 검증은 모두 통과로 유지됐다.
  - 정상 선정 201·재요청 200·재확인 200
  - 잘못된 본문 400(JSON·소수·숫자 문자열·범위 초과·누락·빈 본문 등)
  - 잘못된 경로 ID 400
  - principal 없음 403
  - 정의된 오류 JSON·no-store
  - GET 405
  - 내부 예외·SQL·개인정보 비노출
- 추가 결함은 없다.

### 10.4 한계

- standalone MockMvc 검증이다.
- 실제 Security 체인(401·hasRole), CSRF, 세션 쿠키는 통과하지 않았다.
- 실제 서버의 오류 응답 경로(Boot `/error`·필터 단계 오류)와 DB는 확인하지 않았다.
- `consumes` 제거로 Content-Type이 없는 요청이 어떻게 처리되는지는 이번 테스트에 없다.

### 10.5 이번 재검증 변경 파일

- 이 보고서에 10절만 추가했다. 기존 1~9절은 수정하지 않았다.
- 테스트 파일·운영 코드는 수정하지 않았다. 커밋·푸시 없음.

## 11. 시연 준비 자정 경계(O-2) 수정 후 재검증 (2026-11-28, 검증자)

> 이 절은 `DemoWorkSelectionFixtureServiceTest` 한 클래스만 따로 실행한 기록이다.
> 4절(176개)·10절(37개) 결과와 합산하지 않는다. mock 기반 단위 테스트이며, 실제 MySQL 조회·트랜잭션은 검증하지 않았다.

### 11.1 상태

- 브랜치 `work-selection`, HEAD `e3c094e feat(farm): 지원자 선정·확정 및 조건 재확인 API 구현`. 10절 이후 사용자가 커밋했다.
- AGENTS.md 없음. 원래 wonday 폴더에서 작업했다.
- 10절 실행 후 sha256과 비교한 결과, 바뀐 파일은 다음 두 개뿐이다.
  - `src/main/java/com/wonday/farm/demo/selection/DemoWorkSelectionFixtureService.java`
  - `src/test/java/com/wonday/farm/demo/selection/DemoWorkSelectionFixtureServiceTest.java`

### 11.2 소스 대조

- `prepare()`는 시계를 한 번 읽어 `today`(GMT+9)를 정한다. 표식 생성·기존 공고 조회·응답 사례 조회(`response(…, today)`)에 같은 날짜를 쓴다. `response()`는 더 이상 시계를 읽지 않는다.
- `list()`는 요청마다 시계를 한 번 읽어 그 날짜로 조회한다.
- 테스트 변경
  - 6절 O-2를 기록하던 `[관찰]` 테스트 1건이 회귀 테스트 2건으로 바뀌었다. 이전 관찰 테스트는 다음 날로 조회하는 옛 동작을 단언하던 것이었다.
  - 나머지 테스트의 검증과 기대값은 바뀌지 않았다(diff 확인).

### 11.3 실행

```
cd C:\Users\HCB\Desktop\wonday\farm-api
./gradlew unitTest --tests "com.wonday.farm.demo.selection.DemoWorkSelectionFixtureServiceTest*" --console=plain
```

JDK 21. `unitTest` 태스크(`build.gradle` 62행, integration·knownDefect 태그 제외)를 사용했다.

| 묶음 | 실행 | 성공 | 실패 | 건너뜀 |
|---|---:|---:|---:|---:|
| 최상위(프로필 선언) | 1 | 1 | 0 | 0 |
| 역할·상대 계정·잠금·재사용 | 5 | 5 | 0 | 0 |
| 신규 준비 구성 | 8 | 8 | 0 | 0 |
| 실패 시 성공 응답 없음 | 2 | 2 | 0 | 0 |
| 사례 조회·매핑 (자정 경계 2건 포함) | 7 | 7 | 0 | 0 |
| 합계 | **23** | **23** | **0** | **0** |

- 선택된 테스트는 0개가 아니다. XML 5개는 모두 이 클래스와 중첩 클래스의 것이고, 이번 실행에서 새로 생성됐다. `BUILD SUCCESSFUL`.
- 23개 = 이전 22개 − 관찰 1 + 회귀 2.
- 테스트 실패는 없다. 커버리지(JaCoCo) 태스크는 실행되지 않았으므로, 커버리지 기준 실패도 해당하지 않는다.

### 11.4 자정 경계 시나리오

- **신규 생성 중 자정 통과: 통과**
  - 상황: 23:59:59.999999(GMT+9)에 시작하고, 정원 충족용 비밀번호 인코딩 중 시각을 다음 날 00:00으로 바꿨다.
  - 결과: created=true, 공고 4201, 사례 11건, 사례 종류 11종이 순서대로 나왔다.
  - 지원 ID는 이번 준비에서 저장한 본인 지원 11건과 일치한다.
  - 공고 제목은 전날 표식이고, 사례는 전날(11-28)로 조회한다. 다음 날(11-29) 조회는 없고, 시계는 한 번만 읽는다.
- **재사용 중 자정 통과: 통과**
  - 상황: 기존 공고를 조회하는 중에 시각을 다음 날로 바꿨다.
  - 결과: created=false, 공고 4101, 사례 11건(지원 501~511)이 나왔다.
  - 전날 날짜로 조회하고, 다음 날 조회는 없으며, 시계는 한 번만 읽는다.
  - 공고·일정·지원·확정 근무·상태·변경·완료·비밀번호·회원 저장 호출이 없다.
- 두 테스트에서 다음 날 조회의 기본 mock 반환값은 빈 목록이다. 그래서 옛 동작(응답 직전에 시계를 다시 읽음)이면 사례 11건·`times(1)` 단언이 실패한다.
  - 옛 코드가 다음 날로 조회한다는 것은 수정 전 `[관찰]` 테스트로 확인한 적이 있다.
  - 수정 전 코드로 이번 테스트를 다시 돌려 보지는 않았다. 운영 파일을 바꿔야 하기 때문이다.
- `list()`의 자정 경계는 별도 테스트가 없다. 시계를 한 번만 읽는다는 것은 소스로만 확인했다.

### 11.5 결과

- 6절 O-2는 해소됐다(단위 테스트 기준).
- 추가 결함·수정 요청은 없다.
- 이번 재검증에서는 이 보고서에 11절만 추가했다. 운영 코드·테스트 파일은 수정하지 않았다. 커밋·푸시 없음.
- 한계
  - 실제 MySQL에서의 표식 비교(`cast(:today as char)`)·트랜잭션·근로자 잠금은 검증하지 않았다.
  - 실제 PasswordEncoder 처리 시간도 검증하지 않았다.

## 12. 기존 ApplicationEntityTest 기대값 갱신 재검증 (2026-11-28, 검증자)

> 이 절은 `ApplicationEntityTest` 한 클래스만 따로 실행한 기록이다. 4·10·11절과 합산하지 않는다. 이 클래스의 통과는 전체 CI 통과를 뜻하지 않는다.

### 12.1 상태와 변경

- 브랜치 `work-selection`, HEAD `e3c094e`, AGENTS.md 없음.
- 미커밋 변경: 이 보고서, 11절의 서비스·테스트, 그리고 `ApplicationEntityTest.java`. 모두 보존했다.
- 변경 내용
  - 이전: `onlyRetireMutator` → `containsExactly("retireForReapplication")`
  - 이후: `allowedMutators` → `containsExactlyInAnyOrder("retireForReapplication", "select")`
  - 표시명도 바뀌었다. 다른 테스트는 바뀌지 않았다(diff 확인).
- 배경: WORK-02에서 `JobApplication.select()`가 추가됐다. 그래서 기존 기대값은 현재 기능과 맞지 않았다.
  - 4절 실행 범위에는 이 기존 클래스가 없었다(새 기능 클래스만 선택).
  - 이 불일치는 그때 실행으로 확인되지 않았다.

### 12.2 검증 결과

- 현재 `JobApplication`(Lombok `@Getter`)의 public 비정적 선언 메서드 중 `get`·`is`로 시작하지 않는 것은 `select`와 `retireForReapplication` 두 개다. 생성자는 `getDeclaredMethods`에 포함되지 않는다. 기대값과 일치한다.
- 검증은 약화되지 않았다. `containsExactlyInAnyOrder`는 순서만 허용하고, 다음 경우에는 실패한다.
  - 다른 메서드가 추가된 경우
  - 둘 중 하나가 빠진 경우
  - 같은 이름이 중복된 경우(오버로드)
- 기존 한계는 그대로다: 도우미 `publicNonGetters`는 `get`·`is`로 시작하는 public 메서드를 변경 메서드로 보지 않는다. 이번 수정과 무관하다.

### 12.3 실행

```
cd C:\Users\HCB\Desktop\wonday\farm-api
./gradlew unitTest --tests "com.wonday.farm.application.entity.ApplicationEntityTest" --console=plain
```

| 클래스 | 실행 | 성공 | 실패 | 건너뜀 |
|---|---:|---:|---:|---:|
| ApplicationEntityTest | **7** | **7** | **0** | **0** |

- JDK 21. `unitTest` 태스크(`build.gradle` 62행, integration·knownDefect 태그 제외)를 사용했다.
- 이번 실행에서 새로 생성된 XML 1개(이 클래스)의 결과다. 선택된 테스트는 0개가 아니다. `BUILD SUCCESSFUL`.
- 테스트 실패는 없다. 커버리지(JaCoCo) 태스크는 실행되지 않았으므로, 커버리지 기준 실패도 해당하지 않는다.
- 추가 결함·수정 요청은 없다.
- 이번에는 이 보고서에 12절만 추가했다. 운영 코드·테스트는 수정하지 않았다. 커밋·푸시 없음.
