# APP-04 내 지원 내역 API·farm-demo 화면·상태별 시연 데이터 준비 단위 검증 보고서

- 검증일: 2026-11-27
- 검증 담당: Claude (독립 검증, 운영 코드 수정 없음)
- 기준 문서: `farm-api/docs/app-04-my-applications-api.md`
- 이번 결과는 이전 APP-02 등 다른 보고서의 테스트 수와 합산하지 않습니다. 사용자가 demo에서 직접 확인한 내용(목록, 필터, 조건 비교, 과거 회차)도 합산하지 않습니다.

## 1. 실행 당시 위치와 대상 변경

| 항목 | 값 |
|---|---|
| 브랜치 | `app-history` |
| HEAD | `340bf62` Merge pull request #30 (app-apply) |
| AGENTS.md | 저장소 루트·`farm-api` 모두 없음 |

검증 대상 변경(작성자 변경, 미커밋, 수정하지 않음):

- 수정(M):
  - `auth/SecurityConfig.java`
  - `demo/application/DemoJobApplicationErrorHandler.java`
  - `demo/farm-job-apply.html`, `demo/farm-login.html` (링크 부분만 확인)
- 신규(??):
  - `application/api/MyApplicationApi`
  - `application/dto/MyApplicationResponses`
  - `application/exception/MyApplicationQueryErrorHandler`, `MyApplicationQueryException`
  - `application/repository/MyApplicationQueryRepository`
  - `application/service/ApplicationConditionMapper`, `MyApplicationQueryService`
  - `application/validation/ApplicationHistoryCursor`
  - `demo/application/DemoMyApplicationPage`, `DemoMyApplicationFixtureApi`, `DemoMyApplicationFixtureService`, `DemoMyApplicationFixtureRepository`
  - `demo/farm-my-applications.html`
  - `docs/app-04-my-applications-api.md`

## 2. 작성한 테스트와 실행 명령

### Java (`farm-api/src/test/java/com/wonday/farm/` 아래)

| 파일 | 테스트 수 | 검증 수준 |
|---|---:|---|
| `application/testsupport/MyApplicationRows.java` | 도우미 | 조회 행 projection의 가짜 구현(Proxy) |
| `application/validation/ApplicationHistoryCursorTest` | 23 | 실제 코드 단위 |
| `application/service/MyApplicationQueryServiceTest` | 54 | 실제 서비스·매퍼, 저장소 mock |
| `application/service/ApplicationConditionMapperTest` | 44 | 실제 매퍼, 실제 코드 목록 JSON |
| `application/api/MyApplicationApiTest` | 42 | standalone MockMvc, 실제 컨트롤러·서비스·매퍼·오류 처리기, 저장소 mock |
| `application/repository/MyApplicationQueryRepositoryDeclarationTest` | 6 | SQL 문자열 선언만 |
| `demo/application/DemoMyApplicationFixtureServiceTest` | 28 | 실제 서비스·고정 Clock, 저장소·WorkChangeRecorder·WorkCompletionService mock |
| `demo/application/DemoMyApplicationFixtureApiTest` | 7 | standalone MockMvc, 서비스 mock |
| `demo/application/MyApplicationsDemoPageTest` | 4 | HTML·SecurityConfig 문자열 선언만 |
| **합계** | **208** | |

```
cd C:\Users\HCB\Desktop\wonday\farm-api
JAVA_HOME=.../jdk-21.0.12.1+1
./gradlew.bat unitTest \
  --tests "com.wonday.farm.application.api.MyApplicationApiTest" \
  --tests "com.wonday.farm.application.service.MyApplicationQueryServiceTest" \
  --tests "com.wonday.farm.application.service.ApplicationConditionMapperTest" \
  --tests "com.wonday.farm.application.validation.ApplicationHistoryCursorTest" \
  --tests "com.wonday.farm.application.repository.MyApplicationQueryRepositoryDeclarationTest" \
  --tests "com.wonday.farm.demo.application.DemoMyApplicationFixtureServiceTest" \
  --tests "com.wonday.farm.demo.application.DemoMyApplicationFixtureApiTest" \
  --tests "com.wonday.farm.demo.application.MyApplicationsDemoPageTest"
```

기존 테스트 클래스는 실행 대상에 넣지 않았습니다. 이번 변경과 직접 관련된 신규 클래스만 실행했습니다.

### Node (가짜 DOM·가짜 fetch)

- 파일: `docs/verification/2026-11-27-app-04-my-applications/my-applications-page.test.mjs` (29개)
- 실행 명령(Node v26.13.1, 패키지 설치 없음):
  ```
  node --test docs/verification/2026-11-27-app-04-my-applications/my-applications-page.test.mjs
  ```
- 실제 `farm-my-applications.html`의 인라인 스크립트 원문을 `node:vm`에서 실행합니다.
- document·window·fetch는 가짜이고, 서버 응답은 테스트가 정한 값입니다.

## 3. 실행 결과 (Java와 Node 별도 집계)

### Java

| 회차 | 실행 | 통과 | 실패 | 건너뜀 | 시간 |
|---|---:|---:|---:|---:|---|
| 1차 | 208 | 206 | 2 | 0 | 40초 |
| **최종(2차)** | **208** | **208** | **0** | **0** | 26초 (`BUILD SUCCESSFUL in 25s`) |

- 결과 XML에는 지정한 8개 클래스와 그 @Nested 클래스만 있습니다.
  - 소스의 @Nested 수와 XML 수가 일치합니다.
  - 바깥 클래스에 직접 테스트가 있으면 바깥 클래스 XML도 1개 있습니다.
- 클래스별 합계(API 42, 조회 서비스 54, 매퍼 44, 커서 23, SQL 선언 6, 준비 서비스 28, 준비 API 7, 정적 4)는 XML 합계와 같은 208입니다.

### Node

| 회차 | 실행 | 통과 | 실패 | 건너뜀 | 취소 | 시간 |
|---|---:|---:|---:|---:|---:|---|
| 1회 | **29** | **29** | 0 | 0 | 0 | 약 317ms |

실행 전에 제 테스트 두 곳을 정리했습니다. 인증 오류 사례를 세 경우로 명시하고, 첫 테스트의 잘못 쓴 assert를 고쳤습니다. 이 수정 뒤 처음 실행한 결과입니다.

## 4. 실패 분석: 테스트 코드 문제와 기능 결함의 구분

1차 Java 실패 2건은 모두 **제 테스트 코드 문제**였습니다. 기대값은 바꾸지 않았습니다.

| 테스트 | 오류 | 원인 | 조치 |
|---|---|---|---|
| `DemoMyApplicationFixtureApiTest.errors` | 오류 매핑 확인 전에 AccessDeniedException 발생 | 이미 예외를 던지도록 stub한 `service.prepare(5L)`을 `given(...)`으로 다시 stub하면서 메서드가 호출됨 | `willThrow(...).given(service).prepare(5L)` 형식으로 변경 |
| `DemoMyApplicationFixtureServiceTest$Failures.assignmentFailure` | `WorkSchedule.getId()` null로 NPE | 같은 테스트에서 `prepare`를 두 번 호출했는데, 가짜 `saveAllAndFlush`가 첫 호출에만 ID를 부여함 | ID가 없는 새 일정이면 매번 ID를 부여하도록 가짜 저장을 수정 |

**기능 결함은 발견하지 않았습니다.** 그래서 결함 재현 테스트, 재현 조건, 수정 후보는 해당 사항이 없습니다.

## 5. 주요 확인 결과

### 본인 확인·입력 검증 (서비스 단위 + standalone 컨트롤러)

- 서비스에는 principal의 회원 ID만 전달됩니다.
  - `workerId`·`memberId` 쿼리, 요청 본문의 `workerId`·`jobPostingId`·`employerId`를 넣어도 소유자가 바뀌지 않습니다.
  - 다른 principal(6)이면 그 회원으로만 조회합니다.
- 현재 DB 역할을 다시 확인합니다.
  - 고용주, 모르는 역할 코드, 회원 없음이면 403 `WORKER_ROLE_REQUIRED`이고 저장소 조회가 없습니다.
  - 역할 검사가 입력 검사보다 먼저라서, 고용주에게는 400 대신 403이 나갑니다.
- 없는 지원과 타인의 지원은 같은 404 `APPLICATION_NOT_FOUND`이며, 응답 본문도 같습니다.
- 지원 ID 검사:
  - 0, 음수, 앞자리 0, 소수, 문자, 지수 표기, 부호, 공백, Long 범위 초과는 400입니다.
  - Long 최댓값은 형식상 허용됩니다.
- 필터·limit·중복 파라미터:
  - 필터 생략은 ALL, limit 생략은 20(조회는 21건)입니다.
  - limit 1·100은 허용하고, 0·101·빈 값·부호·소수·문자는 400입니다.
  - 빈 필터·소문자·UNKNOWN·조합 필터는 400입니다.
  - status·limit·cursor가 중복되면 값이 같아도 400입니다(과거 회차 API 포함).
- 성공·오류 응답 형식:
  - 성공과 오류 응답 모두 `Cache-Control: no-store`입니다.
  - 오류 본문은 `code`·`message`만 담고, 내부 예외 문자열(`jdbc:`, 비밀번호 형태)은 노출하지 않습니다.
  - DataAccess·트랜잭션 생성 실패·트랜잭션 시간 초과·QueryTimeout은 503, 그 밖의 예외는 500입니다.
- principal이 없으면 standalone에서 컨트롤러가 403을 돌려줍니다. **실제 비로그인 401은 Security 체인 범위라 확인하지 않았습니다.**

### 페이지와 커서

- size+1건을 조회해 hasMore와 표시 개수를 계산합니다. 다음 커서는 마지막 표시 항목 ID로 만듭니다.
- 정확히 size건이거나 빈 목록이면 `nextCursor=null`, `hasMore=false`입니다.
- 목록은 `findCurrent`, 과거 회차는 `findPast`로 분리돼 있습니다.
- 커서 검사:
  - 다른 계정·다른 필터·LIST/HISTORY 혼용·다른 일정의 커서는 `INVALID_CURSOR`입니다.
  - 형식 오류도 `INVALID_CURSOR`입니다: 빈 값, 공백, `+ / =`, 256자 초과, Base64 길이 오류, 구분자 개수 오류, 버전 2, 앵커 0·음수·앞자리 0·Long 초과.
  - 비정규 표기(패딩 추가)도 거절합니다.
- 앵커 검사:
  - 없는 앵커·타인의 앵커는 본인 조건(`findOwned`)에서 빈 결과가 되어 `INVALID_CURSOR`입니다.
  - 과거 이력의 앵커가 다른 일정이거나 현재 회차여도 `INVALID_CURSOR`입니다.
  - 목록 앵커가 재지원으로 과거 회차가 돼도 거절하지 않고, 원래 정렬 시각과 ID를 경계로 전달합니다.
- 커서는 서명 토큰이 아닙니다. 같은 계정 값으로 만든 커서는 받아들이고, 소유자 조건은 저장소 호출마다 principal ID로 따로 적용합니다.
- 과거 회차는 기준 지원의 소유권을 먼저 확인합니다. 없거나 타인이면 404이고, 이때 limit·커서는 해석하지 않습니다.
- 과거 회차 응답에는 `currentApplicationId`가 들어가며, 현재 회차가 없으면 null입니다.

### 상태와 SQL 선언 (문자열 검사만)

- 모든 조회에 `app.worker_id = :workerId`가 있고, `@Modifying`·UPDATE·DELETE·INSERT는 없습니다.
- 현재 목록:
  - `is_current = 1`, 필터는 표시 상태식과 같은 식입니다.
  - 정렬 경계는 `coalesce(applied_at_utc, applied_at)` 시각과 ID입니다.
  - `order by ... desc, app.id desc`, `limit :fetchLimit`입니다.
- 과거 회차는 같은 일정·`is_current = 0`, 회차 내림차순·ID 내림차순입니다.
- `cast(app.is_current as signed)`로 Boolean을 숫자로 변환합니다.
- 조건 이력 JOIN은 `revision.terms_version = app.applied_terms_version` 조건입니다. V6 이력에 없는 좌표·작업 내용 컬럼은 조회하지 않습니다.
- 표시 상태식 순서: 개인 취소 → 조기 종료 → 완료(`completed_at_utc`) → 확정 → 모집 취소(REMOVED) → PENDING → NOT_SELECTED → UNKNOWN.
  - `planned_end_at`·`agreed_end_at`·현재 시각 함수를 쓰지 않으므로, 예정 종료 시각만으로 완료 처리하지 않습니다.
  - `'SELECTED'` 분기가 없으므로, 확정 근무 없는 SELECTED는 UNKNOWN입니다.
- 매퍼는 SQL의 표시 상태를 그대로 전달합니다(UNKNOWN 포함).
  - CANCELLED에 확정 근무가 없으면 `SCHEDULE_CANCELLED`, 있으면 `ASSIGNMENT_CANCELLED`입니다.

**이것은 SQL 문자열과 가짜 조회 행으로 확인한 결과이며, 실제 MySQL 실행 결과가 아닙니다.**

### 조건 비교와 응답 변환 (매퍼 단위)

- 비교 상태: 같은 버전 UNCHANGED, 다른 버전 CHANGED, 지원 버전 null UNKNOWN. 127을 넘는 Integer 버전도 값으로 비교합니다.
- 지원 당시 조건을 고르는 규칙:
  - 대응 이력이 있으면 이력 값을 씁니다. 좌표·작업 내용은 null이고 `unavailableFields`에 들어가며, 최신 값으로 보완하지 않습니다.
  - 이력이 없고 같은 버전이면 현재 조건을 씁니다.
  - 이력이 없고 다른 버전이면 UNAVAILABLE이며, 모든 값이 null이고 14개 필드가 모두 확인 불가입니다.
  - 지원 버전이 null이면 이력 행이 있어도 UNAVAILABLE입니다.
- 변경 항목:
  - 근무일·작업지·작목·작업 유형·정원·일급·휴게가 각각 다르면 정확히 그 항목만 `changedFields`에 들어갑니다.
  - 시간대 문자열만 다르고 오프셋 시각이 같으면 timeZone만 변경입니다.
  - 한쪽이 확인 불가인 항목은 `changedFields`에서 빠집니다.
  - `unavailableComparisonFields`는 양쪽 확인 불가 항목의 합집합입니다. `comparisonComplete`는 이 집합이 비었을 때만 true입니다.
- 휴게:
  - 양쪽 null은 '휴게 없음'(AVAILABLE)입니다.
  - 한쪽만 있으면 누락 쪽이 확인 불가입니다.
  - 휴게 없음에서 휴게 있음으로 바뀌면 변경으로 잡습니다.
- 시간대·DST:
  - 시간대가 null·공백·해석 불가(`Mars/Base`, `GMT+99`)면 timeZone과 모든 시각을 확인 불가로 둡니다.
  - America/New_York의 DST 겹침(01:30)·공백(02:30) 시각은 오프셋을 고르지 않고 확인 불가로 둡니다.
- 코드: 알려진 코드는 이름(콩(일반), 경운 등)을 돌려줍니다. 모르는 코드는 원본 코드를 유지하고 이름만 null이며, 확인 불가로 표시하지 않습니다.
- 약정:
  - 확정 근무에 저장된 값만 반환합니다(최신 공고와 다른 작업지·좌표·일급·시각을 유지).
  - 누락된 항목은 PARTIAL과 `unavailableFields`로 표시합니다.
  - 취소·조기 종료 뒤에도 약정을 반환합니다.
- 시각:
  - `completedAt`은 저장된 값만 +09:00으로 변환하고, 없으면 null입니다.
  - UTC 시각은 +09:00으로 표시하고 마이크로초를 유지합니다.
- JSON(standalone 기본 JsonMapper 기준):
  - 예: `2026-12-01T15:00:00.123456+09:00`. null 필드는 null로 유지되고 날짜는 `YYYY-MM-DD`입니다.
  - UTC가 없는 기존 행은 `appliedAt=null`이고, `legacyAppliedAt` 원본과 `LEGACY_LOCAL_UNKNOWN_ZONE`을 돌려줍니다.

### 시연 데이터 준비 서비스 (mock·고정 Clock)

- 권한·고용주·잠금:
  - WORKER만 허용합니다. 고용주, 역할 변경, 회원 없음이면 AccessDenied이고 잠금·저장이 없습니다.
  - demo-employer가 없거나 고용주가 아니면 409 `DEMO_EMPLOYER_UNAVAILABLE`이며, 업무 데이터를 저장하기 전에 멈춥니다.
  - 잠금 행을 얻지 못하면 503입니다.
- 호출 순서: 역할 확인 → `ensureExists` → `lockWorker` → 고용주 확인 → 오늘 표식 조회.
- 날짜와 재사용:
  - GMT+9 경계: UTC 14:59:59.999999는 그날, 15:00은 다음 날 표식을 씁니다.
  - 표식에 근로자 ID가 들어가 근로자별로 따로 준비합니다.
  - 재사용 시 새 공고·일정·지원·약정·변경·완료·이력을 만들지 않습니다(created=false).
- 새로 준비하는 내용:
  - 공고: 표식 제목, demo-employer, GMT+9, 정원 3.
  - 일정 9개: 오늘+7~+12, 오늘-2, 오늘-1, 오늘+13.
  - 지원 10건 = 현재 9건 + 과거 1건. 지원 시각은 지금-3일부터 1분 간격입니다.
  - 재지원: 과거 NOT_SELECTED 1회차(현재 아님)가 먼저, 현재 PENDING 2회차가 나중입니다.
  - 모집 취소: 넷째 일정만 REMOVED이고, 그 지원은 PENDING으로 유지되며 약정이 없습니다.
  - 조건 변경: 둘째 일정만 v1 BASELINE(09~17, 작성자 없음)을 저장한 뒤 v2 UPDATED(10~18, 고용주)를 저장합니다. 나머지 일정은 v1입니다.
  - 약정: SELECTED 4건의 새 지원 ID에만 연결하며, 확정 시각은 첫 지원+1시간입니다.
- 기존 서비스에 위임하는 내용:
  - 취소: WorkChangeRecorder에 `CANCELLED`, 적용 시각 null, `FARM_CIRCUMSTANCES`로 넘깁니다.
  - 조기 종료: `EARLY_ENDED`, 오늘-2 14:00+09:00(09~17 안, 현재보다 과거, 마이크로초 정밀도), `WEATHER/RAIN`으로 넘깁니다.
  - 완료: 오늘-1 일정의 새 약정만 WorkCompletionService에 넘깁니다.
  - 같은 근무를 취소·조기 종료·완료에 중복으로 넘기지 않고, 확정 사례는 어디에도 넘기지 않습니다.
- 중간 실패:
  - 지원 INSERT 처리 건수가 0·2건이거나 ID를 다시 찾지 못하면 503입니다.
  - 약정 INSERT 0건이나 약정 ID 누락도 503이며, 이후 변경·완료가 없습니다.
  - 변경·완료 위임 중 예외는 그대로 전파되고, 이후 단계를 호출하지 않습니다.
- 응답: 사례 이름 10종, ID, 회차, 현재 여부, 확정 근무 ID, 변경 여부가 조회 결과를 그대로 반영합니다. 분류할 수 없는 상태는 '상태 확인 필요'입니다.
- 선언: `prepare`는 `READ_COMMITTED` 트랜잭션(읽기 전용 아님)이고, 서비스·저장소·API·화면·오류 처리기는 `farm-demo` 프로필입니다.
- 준비 API(standalone): 새 준비 201, 재사용 200, no-store입니다.
  - 오류 매핑: AccessDenied 403, 고용주 계정 없음 409, 잠금 실패 503, 제약 충돌 409 `DEMO_FIXTURE_CONFLICT`, DB 장애 503, 그 밖 500.
  - GET 요청은 405입니다.
  - 화면 GET은 HTML·no-store이고, 화면 조회만으로 준비 서비스를 부르지 않습니다.

**mock 예외 처리와 @Transactional 선언 확인만으로 실제 롤백·동시 실행 직렬화·중복 방지가 입증된 것은 아닙니다.**

### demo 화면 (Node 가짜 DOM 29개)

- 페이지 진입 시 `/me` → 정식 목록 GET → `/me`만 호출하고, 준비 POST는 자동으로 호출하지 않습니다. `?applicationId`는 유효할 때만 상세를 조회합니다.
- 목록·커서:
  - 필터·개수·다음 페이지는 같은 status·limit와 서버 커서로 이어 조회합니다. 마지막 페이지면 다음 버튼이 비활성입니다.
  - 필터·개수를 바꾸면 이전 커서와 목록을 버리고 다음 버튼이 비활성이 됩니다.
  - 개수 0·101·소수·빈 값은 요청 없이 안내합니다.
- 영역 분리: 상세, 과거 회차, 목록은 각각 실패해도(500·통신 실패·503·404) 다른 정상 영역을 지우지 않습니다.
- 조건 비교 표시:
  - 변경 행만 강조하고, 과거 좌표·작업 내용은 '확인 불가'로 표시하며 비교 불가 항목 이름을 안내합니다.
  - 부분 비교면 '모두 같습니다'라고 쓰지 않고, 완전 비교일 때만 그렇게 안내합니다.
  - 휴게 없음과 확인 불가를 구분하고, 모르는 코드는 '이름 미확인'으로 표시합니다.
- 회차: 과거 회차 목록, 현재 회차 상세 이동, 과거 항목 상세 조회를 확인했습니다.
- 약정과 상태 표시:
  - 저장된 약정, 확정 후 취소·조기 종료 기록, 완료 일시를 표시합니다. 확정 근무가 없으면 약정 표를 만들지 않습니다.
  - 목록에서 모집 취소와 확정 후 취소를 구분하고, 기존 시각은 '시간대 미확인'으로 표시합니다.
- 시연 준비:
  - 순서: CSRF 조회 → 계정 확인 → POST(CSRF 헤더만, 본문 없음) → 계정 확인 → 목록 재조회.
  - 201은 새 준비, 200은 재사용 안내입니다. 사례별 ID가 표시되고, 상세·과거 회차 버튼이 실제 ID로 조회합니다.
  - 준비 뒤 목록 재조회만 실패(500·통신 실패)해도 준비 결과와 사례 ID가 남습니다.
  - CSRF 발급 실패(500·빈 토큰·통신 실패)면 POST가 없습니다.
- 토큰 노출: CSRF 토큰은 화면과 요청 기록에 없고, `/api/auth/*` 요청은 기록 표에 없으며, 조회 GET에는 헤더가 없습니다. Authorization·Cookie 헤더도 쓰지 않습니다.
- 실패·중복 처리:
  - POST 통신 실패 시 자동 재전송이나 가짜 성공 표시가 없습니다. 응답 형식 오류와 409도 성공으로 표시하지 않습니다.
  - POST 대기 중에는 버튼을 다시 눌러도 추가 요청이 없습니다.
- 인증·계정·이탈:
  - 목록 401, 상세 403, 이력 403이면 목록·사례·상세·커서·입력 ID를 모두 지웁니다.
  - 준비 POST의 403 `WORKER_ROLE_REQUIRED`는 전체 초기화, 본문 없는 403은 오류 안내만 합니다.
  - 상세 응답이나 준비 POST 응답 뒤 계정이 바뀌면 이전 계정 데이터를 표시하지 않습니다.
  - pagehide 이후 도착한 응답은 표시하지 않고 화면을 비우며, 자동 재전송이 없습니다.
- 안전한 표시: 잘못된 지원 ID는 요청 없이 안내하고, 서버 문자열(`<img>`, `<script>`, `<b>`)은 textContent로만 표시합니다. `innerHTML` 계열·`console`·`localStorage`·`sessionStorage`·`indexedDB`·`document.cookie`에 접근하지 않습니다.
- 목록 응답 형식 오류(current 불일치, 커서 누락, items null)는 표시하지 않고 오류로 안내합니다.

정적 검사(Java `MyApplicationsDemoPageTest`):

- 위험 API를 쓰지 않습니다. 조회는 정식 APP-04 GET을 쓰고, POST는 준비 한 곳뿐입니다.
- `no-referrer` 메타가 있습니다.
- 로그인 화면의 근로자 전용 링크와 지원 화면 상호 링크가 있습니다.
- SecurityConfig 선언 문자열:
  - 화면은 GET permitAll, 준비 POST와 정식 GET 3종은 hasRole(WORKER)입니다.
  - demo 규칙은 `matchesProfiles("farm-demo")` 블록 안에 있고, 403 JSON 처리 경로 정규식은 CSRF 예외를 제외합니다.
  - 준비 경로의 permitAll이나 CSRF 제외 선언은 없습니다.

**설정 문자열 검사와 가짜 DOM 테스트는 실제 Security 체인이나 브라우저 검증이 아닙니다.**

## 6. 관찰 사항 (결함 아님, 정책 확인 권장)

- **O-1:** `equalValue`의 BigDecimal 자릿수 비교는 현재 경로에서 실행되지 않습니다.
  - 좌표만 BigDecimal인데 V6 이력에 좌표가 없고, 같은 버전이면 같은 객체를 비교하기 때문입니다.
  - 그래서 private 보조 함수를 리플렉션으로 직접 확인했습니다(37.0 = 37.000000).
- **O-2:** 같은 버전이라도 대응 이력이 있으면 이력 값을 우선합니다.
  - 이력에 좌표·작업 내용이 없으므로 `comparisonComplete`는 항상 false이고, 화면도 '모두 같습니다'를 표시하지 않습니다.
  - 문서 규칙("대응 이력이 있으면 그 저장값")과 일치합니다.
- **O-3:** 소스를 읽어 보면 limit 검사가 `[0-9]{1,3}`라서 앞자리 0(`007` → 7, `020` → 20)을 허용합니다. 이 입력은 테스트하지 않았습니다.
- **O-4:** 준비 중 WorkChangeRecorder나 WorkCompletionService가 자체 예외(InsuranceApiException·WorkCompletionException)를 던지면, `DemoJobApplicationErrorHandler`의 Exception 처리로 500이 됩니다. 이 판단은 소스를 읽은 것이고, 테스트는 일반 예외 500만 확인했습니다.
- **O-5:** 목록 커서의 앵커는 지금 필터에 맞는지 다시 검사하지 않고, 정렬 경계로만 씁니다. 앵커 상태가 바뀌어도 다음 페이지를 이어 읽으며, 문서의 "경계 유지" 설명과 일치합니다.

## 7. 미검증 범위

- 실제 MySQL에서의 SQL 실행:
  - JOIN 결과와 중복(`work_change`·`work_schedule_revision` UNIQUE에 기대는 부분)
  - 표시 상태식과 필터의 실제 결과, 정렬, 경계 비교
  - `CAST` 결과의 projection 변환, 인덱스와 성능
- REPEATABLE_READ / READ_COMMITTED 트랜잭션의 실제 동작, 실제 롤백
- APP-02 요청 잠금에 의한 실제 직렬화, 동시 준비와 중복 방지
- DB 제약(V1·V5·V6·V7·V11·V12)과 데모 INSERT의 실제 실행
- 실제 WorkChangeRecorder·WorkCompletionService와의 연동(모두 mock)
- Spring Security 전체 체인: 비로그인 401, hasRole, CSRF 거절, 세션, 403 JSON 처리기 실제 응답
- farm-demo 프로필에 따른 실제 빈 등록과 기본 프로필 차단
- Spring Boot JsonMapper 설정(standalone은 기본 JsonMapper 사용)
- 실제 브라우저 렌더링과 조작, 실제 서버의 응답 내용
- 통합 테스트, 전체 테스트, 커버리지(지시대로 실행하지 않음)

## 8. 실행 전후 기존 코드 보존

- 브랜치 `app-history`, HEAD `340bf62`로 실행 전후가 같습니다.
- `git diff` 해시가 실행 전후 동일합니다. 추적 중인 파일은 변경되지 않았습니다.
- `git status`의 차이는 아래 신규 파일 11개뿐입니다.
- sha256 비교 결과는 다음과 같습니다.
  - `src/main`, `src/test`의 기존 파일, Gradle 파일, `gradle/`, `.github`, `docs/*.md`: 기존 파일 변경 없음. 추가된 것은 신규 테스트 파일뿐입니다.
  - `docs/verification` 전체: 기존 보고서와 파일 변경 없음. 추가된 것은 신규 Node 테스트(와 이 보고서)뿐입니다.
- 서버·Docker·MySQL·개발 DB·`.env`·이관 도구는 사용하지 않았습니다. 실제 시연 데이터도 만들지 않았습니다.
- 커밋·푸시·브랜치 변경·stash·reset을 하지 않았습니다.

## 9. 커밋 대상에 추가할 파일

```
farm-api/src/test/java/com/wonday/farm/application/testsupport/MyApplicationRows.java
farm-api/src/test/java/com/wonday/farm/application/validation/ApplicationHistoryCursorTest.java
farm-api/src/test/java/com/wonday/farm/application/service/MyApplicationQueryServiceTest.java
farm-api/src/test/java/com/wonday/farm/application/service/ApplicationConditionMapperTest.java
farm-api/src/test/java/com/wonday/farm/application/api/MyApplicationApiTest.java
farm-api/src/test/java/com/wonday/farm/application/repository/MyApplicationQueryRepositoryDeclarationTest.java
farm-api/src/test/java/com/wonday/farm/demo/application/DemoMyApplicationFixtureServiceTest.java
farm-api/src/test/java/com/wonday/farm/demo/application/DemoMyApplicationFixtureApiTest.java
farm-api/src/test/java/com/wonday/farm/demo/application/MyApplicationsDemoPageTest.java
farm-api/docs/verification/2026-11-27-app-04-my-applications/my-applications-page.test.mjs
farm-api/docs/verification/2026-11-27-app-04-my-applications/VERIFICATION_REPORT.md
```

작성자 변경 파일(APP-04 운영 코드·HTML·문서·SecurityConfig 등)의 커밋 여부는 작성자가 판단할 사항입니다.
