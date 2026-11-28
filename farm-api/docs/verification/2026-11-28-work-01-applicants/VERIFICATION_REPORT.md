# WORK-01 고용주 날짜별 지원자 조회 단위 검증 보고서

- 검증일: 2026-11-28
- 검증 담당: Claude (독립 검증, 운영 코드 수정 없음)
- 기준 문서: `farm-api/docs/work-01-applicants-api.md`
- 사용자가 farm-demo에서 직접 확인한 내용(목록·필터·다음 페이지, 조건 비교, 연락처 조회·권한 거부)은 사용자의 수동 확인 기록입니다. 이번 자동 테스트 결과와 합산하지 않습니다.
- 다른 기능(APP-02·APP-04 등)의 테스트 수와도 합산하지 않습니다.

## 1. 브랜치와 대상 변경

| 항목 | 값 |
|---|---|
| 브랜치 | `work-applicants` |
| 기준 커밋(HEAD) | `bdb7748` Merge pull request #33 |
| AGENTS.md | 저장소 루트·`farm-api` 모두 없음 |

검증 대상(작성자의 미커밋 변경, 수정하지 않음):

- 수정(M):
  - `auth/SecurityConfig.java`
  - `demo/farm-login.html`
  - `farm-api/README.md`
- 신규(??):
  - `work/api/WorkApplicantApi`
  - `work/service/WorkApplicantQueryService`
  - `work/repository/WorkApplicantRepository`
  - `work/dto/WorkApplicantResponses`
  - `work/exception/WorkApplicantException`, `WorkApplicantErrorHandler`
  - `work/validation/WorkApplicantCursor`, `ApplicantPhone`
  - `demo/applicant/DemoWorkApplicantApi`, `DemoWorkApplicantErrorHandler`, `DemoWorkApplicantFixtureService`, `DemoWorkApplicantFixtureRepository`
  - `demo/farm-work-applicants.html`
  - `docs/work-01-applicants-api.md`

## 2. 추가한 테스트 파일 (기존 테스트 파일 변경 없음)

| 파일 (`farm-api/src/test/java/com/wonday/farm/`) | 테스트 수 | 검증 수준 |
|---|---:|---|
| `work/testsupport/WorkApplicantRows.java` | 도우미 | 조회 행의 가짜 구현. APP-04의 `MyApplicationRows`를 재사용 |
| `work/validation/WorkApplicantCursorTest` | 21 | 실제 코드 단위 |
| `work/validation/ApplicantPhoneTest` | 23 | 실제 코드 단위(Java 쪽 정규화만) |
| `work/service/WorkApplicantQueryServiceTest` | 66 | 실제 서비스, 실제 APP-04 매퍼·코드 목록, 저장소 mock |
| `work/api/WorkApplicantApiTest` | 22 | standalone MockMvc(실제 컨트롤러·서비스·매퍼·오류 처리기), 저장소 mock |
| `work/repository/WorkApplicantRepositoryDeclarationTest` | 6 | SQL 문자열 선언만 |
| `demo/applicant/DemoWorkApplicantFixtureServiceTest` | 20 | 실제 서비스, 고정 Clock, 저장소·PasswordEncoder mock |
| `demo/applicant/DemoWorkApplicantApiTest` | 4 | standalone MockMvc, 서비스 mock |
| `demo/applicant/WorkApplicantsDemoPageTest` | 3 | HTML·SecurityConfig 문자열 선언만 |
| **Java 합계** | **165** | |
| `farm-api/docs/verification/2026-11-28-work-01-applicants/work-applicants-page.test.mjs` | 22 | Node, 실제 HTML 스크립트 원문 + 가짜 DOM·fetch |

## 3. 실행 명령

```
cd C:\Users\HCB\Desktop\wonday\farm-api
JAVA_HOME=.../jdk-21.0.12.1+1
./gradlew.bat unitTest \
  --tests "com.wonday.farm.work.validation.WorkApplicantCursorTest" \
  --tests "com.wonday.farm.work.validation.ApplicantPhoneTest" \
  --tests "com.wonday.farm.work.service.WorkApplicantQueryServiceTest" \
  --tests "com.wonday.farm.work.api.WorkApplicantApiTest" \
  --tests "com.wonday.farm.work.repository.WorkApplicantRepositoryDeclarationTest" \
  --tests "com.wonday.farm.demo.applicant.DemoWorkApplicantFixtureServiceTest" \
  --tests "com.wonday.farm.demo.applicant.DemoWorkApplicantApiTest" \
  --tests "com.wonday.farm.demo.applicant.WorkApplicantsDemoPageTest"

node --test docs/verification/2026-11-28-work-01-applicants/work-applicants-page.test.mjs   (Node v26.13.1, 설치 없음)
```

- 클래스 이름을 정확히 지정했고, 와일드카드는 쓰지 않았습니다.
- 다음 작업은 실행하지 않았습니다: test, 전체 unitTest, integrationTest, fullTest, build, check, 리포트, 커버리지.
- 커버리지는 측정하지 않았습니다.

## 4. 실행 결과 (Java와 Node 별도 집계)

### Java

| 회차 | 실행 | 성공 | 실패 | 건너뜀 | 시간 |
|---|---:|---:|---:|---:|---|
| 1차 | 165 | 153 | 12 | 0 | 1분 36초 |
| **최종(2차)** | **165** | **165** | **0** | **0** | 46초 (`BUILD SUCCESSFUL`) |

- 결과 XML에는 지정한 8개 클래스와 그 @Nested 클래스만 있습니다. 소스의 @Nested 수와도 일치합니다.
- 클래스별 합계는 21+23+66+22+6+20+4+3 = 165입니다.

### Node (가짜 DOM)

| 회차 | 실행 | 성공 | 실패 | 건너뜀 |
|---|---:|---:|---:|---:|
| 1차 | 21 | 18 | 3 | 0 |
| **최종(2차)** | **22** | **22** | **0** | **0** |

2차에서 1건이 늘어난 것은 1차 실패 원인을 기록하는 관찰 테스트([관찰] P-2)를 추가했기 때문입니다.

## 5. 실패 분석: 테스트 코드 문제와 기능 결함의 구분

**기능 결함은 발견하지 않았습니다.** 1차 실패는 모두 제 테스트 코드 문제였습니다.

| 실패 | 원인 | 조치 |
|---|---|---|
| Java 12건 (`DemoWorkApplicantFixtureServiceTest`) | 가짜 저장 함수의 `ReflectionTestUtils.setField(call.getArgument(0), …)`가 제네릭 추론 때문에 `setField(Class, …)` 오버로드로 해석돼 ClassCastException 발생 | 인자를 `JobPosting` 변수·`(Object)`로 받도록 수정. 기대값은 변경하지 않음 |
| Node 1건 (상세 테스트) | 지원 대기 목록에서 선정(503) 행의 버튼을 찾음 | 전체(ALL) 목록에서 찾도록 수정 |
| Node 2건 (사례 버튼 뒤 명단 유지) | 시연 사례 버튼은 요청 전에 `setTarget()` → `clearList()`로 명단을 비우는 구현임. 실패 응답 때문에 지운 것이 아님 | 실패 시 전체 초기화가 없는지만 확인하도록 고침. 명단을 비우는 동작은 [관찰] 테스트로 따로 기록(P-2) |

Node 2건은 기대값을 낮춘 것이 아닙니다. 요구사항은 "상세·연락처 **실패 때문에** 정상 목록을 불필요하게 지우지 않음"입니다.

- 목록 행 버튼 경로에서 상세·연락처가 실패(500·통신 오류·형식 오류)해도 명단이 유지되는지는 별도 테스트에서 통과를 확인했습니다.
- 사례 버튼이 명단을 비우는 것은 응답이 성공이든 실패든 똑같이 일어나므로, 관찰 사항으로 분리했습니다.

## 6. 단위 테스트로 확인한 범위

### 목록·소유권·역할 (서비스 + standalone 컨트롤러)

- 역할과 고용주 ID:
  - 현재 DB 역할이 EMPLOYER가 아니면(근로자·모르는 역할 코드·회원 없음) 목록·상세·연락처 모두 403 `EMPLOYER_ROLE_REQUIRED`이며, 저장소 조회가 없습니다.
  - 세션 principal의 회원 ID가 고용주 ID로 쓰입니다. `employerId` 쿼리·본문 값은 무시됩니다.
- 소속 검사:
  - 타인 공고는 404 `JOB_POSTING_NOT_FOUND`이고, 이때 일정·지원 조회는 없습니다.
  - 공고에 속하지 않는 일정은 404 `WORK_SCHEDULE_NOT_FOUND`이고, 지원 조회는 없습니다.
  - 저장소 호출마다 고용주·공고·일정·지원 ID가 정확히 전달됩니다.
- 목록은 현재 회차 쿼리(`findCurrent`)만 씁니다. 과거 회차 API는 없습니다.
- OPEN·PAUSED·CLOSED·REMOVED와 지난 근무일 모두 조회를 허용하고, 모집 상태를 그대로 응답합니다.
- 필터:
  - 생략하면 PENDING이고, 허용값은 4종입니다.
  - 빈 값·소문자·CONFIRMED·CANCELLED·UNKNOWN·공백·조합은 400 `INVALID_REQUEST`입니다.
- 개수:
  - 생략하면 20(조회는 21건)이고, 1·100은 허용합니다.
  - 0·음수·101·999·1000·소수·문자·빈 값·공백·부호·지수 표기는 400입니다.
- 페이지:
  - size+1건으로 hasMore를 판단하고, 반환 개수는 요청 크기를 넘지 않습니다.
  - 빈 결과는 `items=[]`, `nextCursor=null`, `hasMore=false`입니다. 마지막 페이지의 nextCursor는 null입니다.
  - 여러 페이지에서 1쪽 커서의 앵커를 소유권 범위(`findOwnedAnchor`)로 다시 읽고, 그 정렬 시각과 ID를 2쪽 조회 경계로 전달합니다.
- 집계:
  - 정원·확정·남은 자리는 필터와 무관하게 `countOccupyingAssignments(일정)` 결과를 씁니다.
  - 남은 자리는 max(정원−확정, 0)이며, 확정 5/정원 3이어도 0이고 음수가 되지 않습니다.
- **정렬:** 지원 시각 오름차순·ID 오름차순은 SQL 선언(`order by coalesce(...), app.id`, `>` 경계, `desc` 없음)으로만 확인했습니다. mock 순서는 실제 정렬 검증이 아닙니다.
- **집계 SQL:** `work_assignment` 집계의 실제 실행 결과(CANCELLED 제외 등)는 단위 테스트로 증명하지 못합니다.

### 커서

- 정상 인코딩·디코딩이 되고, 생략하면 첫 페이지입니다.
- 다른 고용주·공고·일정·필터의 커서는 거부합니다.
- 형식 오류도 거부합니다: 빈 값, 공백, `+ / =`, 한글, `%`, 256자 초과, 디코딩 불가, 구분자 개수 오류, 버전 2.
- 앵커가 0·음수·앞자리 0·소수·문자·빈 값·Long 범위 초과면 거부합니다.
- 본인 범위에 없는 앵커는 `INVALID_CURSOR`이고, 목록 조회는 없습니다.
- 앵커가 과거 회차로 바뀌어도 저장된 원래 시각을 경계로 씁니다.
- 커서는 서명된 토큰이 아닙니다. 같은 범위 값으로 직접 만든 커서도 형식 검사는 통과하며, 앵커는 소유권 범위 쿼리로 다시 조회합니다.

### 상세·조건 비교

- 현재 회차 쿼리만 씁니다. 없거나 다른 날짜·과거 회차인 지원(빈 결과)은 404 `APPLICATION_NOT_FOUND`입니다.
- 이름은 전체, 번호는 마스킹 값만 반환합니다. 목록·상세 JSON과 응답 레코드에 `phone` 필드는 없습니다.
- 조건 비교:
  - v1 이력 대 최신 v2: CHANGED, `changedFields=[plannedStartAt, plannedEndAt]`. 좌표·작업 내용은 null로 확인 불가이며 최신 값으로 채우지 않고, `comparisonComplete=false`입니다.
  - 같은 버전: UNCHANGED·비교 완료.
  - 지원 버전 null: UNKNOWN·지원 당시 UNAVAILABLE.
- 약정: 확정 근무가 있으면 저장된 약정(최신 공고와 다른 작업지·일급)을 반환하고, 없으면 `assignment=null`입니다.
- 시각: UTC는 +09:00·마이크로초로 표시합니다. UTC가 없는 기존 행은 `appliedAt=null`이고, `legacyAppliedAt` 원본과 `LEGACY_LOCAL_UNKNOWN_ZONE`을 돌려줍니다.
- 상세 조회는 연락처 쿼리를 부르지 않습니다. 저장소 인터페이스에는 쓰기(@Modifying) 메서드가 없습니다.

### 연락처

- 허용·거부:
  - 현재 회차 PENDING·SELECTED만 허용합니다.
  - NOT_SELECTED, 과거 회차(상태 무관), 모르는 상태는 403 `CONTACT_ACCESS_DENIED`입니다.
  - 소속이 없으면 404이고, 다른 소유자·공고·일정이면 각 404 규칙을 적용합니다.
- 연락처 API는 목록의 `canViewContact`를 입력으로 받지 않고, 매 요청마다 `findContact`를 다시 호출합니다.
  - 목록 뒤 상태가 PENDING에서 NOT_SELECTED로 바뀌면, 같은 지원도 200 대신 403으로 판정합니다.
  - 번호가 바뀌면 새 번호를 씁니다.
- Java 정규화(`ApplicantPhone`):
  - 하이픈·공백(탭·줄바꿈·NBSP 포함)을 지운 뒤 숫자 10~11자리일 때만 정상입니다.
  - 미등록·빈 값·하이픈만 있는 값은 `phone=null`, NOT_REGISTERED입니다.
  - 국가번호·점·괄호·12자리 이상·9자리 이하·문자·전각 숫자·다른 대시는 `phone=null`, INVALID_FORMAT이며, 보정이나 가상 번호가 없습니다.
- 목록 마스킹은 SQL에서 계산합니다. 이번에는 **식 선언 문자열만** 확인했습니다.
  - 10~11자리일 때 `left 3`-`'*' × (길이−7)`-`right 4`, 아니면 null입니다.
  - 같은 식을 Java로 옮겨 계산하면 11자리 `010-****-1001`, 10자리 `021-***-5678`입니다. MySQL 실행 결과는 아닙니다.
- 연락처 SQL은 현재 회차·PENDING/SELECTED일 때만 `worker.phone`을 돌려주고, 그 외에는 null입니다. `worker.phone`은 이 쿼리에서 한 번만 참조합니다.
- 응답 레코드와 SQL에는 주민번호·식별정보(identity_*)·HMAC·계좌·은행·비밀번호 컬럼이나 필드가 없습니다.
- 오류 본문에도 내부 예외 문자열(주민번호 형태, `jdbc:`, 비밀번호)이 노출되지 않습니다.
- 30일 제한이나 자동 파기 같은 새 기준은 없습니다(코드에 해당 로직 없음). 테스트에도 추가하지 않았습니다.

### API·오류 (standalone MockMvc)

- 목록·상세·연락처가 정상이면 200·no-store입니다.
- 오류 응답 매핑(모두 no-store, 본문은 `code`·`message`만):
  - ID 형식 오류·범위 초과(0, -1, 01, 1.5, abc, Long+1, +1): 400, 저장소 조회 없음
  - status·limit·cursor 중복: 400
  - 잘못된 필터·limit: `INVALID_REQUEST`, 잘못된 커서·빈 커서·다른 범위 커서: `INVALID_CURSOR`
  - `EMPLOYER_ROLE_REQUIRED`, `CONTACT_ACCESS_DENIED`, `JOB_POSTING_NOT_FOUND`, `WORK_SCHEDULE_NOT_FOUND`, `APPLICATION_NOT_FOUND`
  - DataAccess·QueryTimeout·트랜잭션 생성 실패·트랜잭션 시간 초과: 503 `SERVICE_UNAVAILABLE`
  - 그 밖의 예외: 500 `INTERNAL_SERVER_ERROR`
- JSON 형식: 날짜 `2026-12-10`, 일시 `+09:00`·마이크로초, null, 빈 배열, 상태, 버전을 확인했습니다. standalone 기본 JsonMapper 기준입니다.
- **standalone MockMvc는 실제 Security 필터·세션 쿠키·CSRF 체인을 거치지 않습니다.** principal이 없을 때의 403은 컨트롤러 동작이며, 익명 401이나 실제 hasRole 차단은 검증하지 않았습니다.

### 시연 데이터 준비 (mock·고정 Clock, 실제 데이터 생성 없음)

- 권한·잠금·재사용:
  - 고용주가 아니면 AccessDenied이고 잠금·저장이 없습니다. 고용주 행 잠금이 실패해도 AccessDenied이고 저장이 없습니다.
  - 순서는 역할 확인 → 고용주 행 잠금 → 오늘 표식 조회입니다.
  - 고용주 ID는 세션에서 가져옵니다(API 테스트에서 파라미터·본문 무시 확인).
  - 재사용 시 created=false이고, 회원·공고·일정·이력·지원·약정·상태 변경을 호출하지 않습니다.
  - GMT+9 날짜 경계: UTC 14:59:59.999999는 그날, 15:00은 다음 날 표식입니다.
- 신규 준비 내용:
  - 가상 근로자 5명: 모두 WORKER, 고유 `demo-applicant-` 로그인 ID, 번호 4명(`01000001001`~`004`)과 미등록 1명.
  - 저장 시점에 모두 ID가 없는 새 회원이며, 기존 회원은 저장하지 않습니다.
  - 비밀번호는 UUID 원문을 encoder에 넘기고 해시만 저장합니다. 응답 레코드에 비밀번호·해시·키·전화번호·로그인 ID 필드는 없습니다.
  - 공고 1개: 잠근 본인 고용주 소유, 표식 제목, 정원 3, GMT+9.
  - 일정 4개: 오늘+7, +8, −1, +9.
- 지원 9건(현재 8, 과거 1): 근로자·일정·상태·회차·버전·현재 여부·지원 시각을 모두 확인했습니다.
  - 첫 일정: 현재 대기 3, 선정 1, 미선정 1, 과거 1. 같은 접수 시각 사례가 있습니다.
  - 재지원자: 1회차(과거, NOT_SELECTED)와 2회차(현재, PENDING).
- 조건 변경:
  - 첫 지원(v1)을 저장한 뒤 BASELINE(v1, 09~17, 작성자 없음)을 저장하고, 일정을 v2(10~18)로 바꾼 뒤 UPDATED(v2, 고용주)를 저장합니다.
  - 첫 일정의 나머지 현재 지원은 v2입니다.
- 확정 약정 1건: 선정 지원의 실제 ID·그 근로자·공고·고용주·표식으로만 만듭니다.
- 다른 일정 상태: 둘째 PAUSED, 셋째(지난 날짜) CLOSED, 넷째 REMOVED를 새 일정 ID·고용주·공고·표식으로만 바꿉니다. 첫 일정은 변경하지 않습니다.
- 실패 처리:
  - 지원 INSERT 0건, ID 재조회 실패, 약정 INSERT 0건, 상태 변경 0건이면 503이며 성공 응답(사례 조회)을 만들지 않습니다.
  - 실패 뒤 후속 작업(이력·약정·상태 변경·남은 지원)을 하지 않습니다.
  - 회원 저장 예외는 그대로 전파되고, 공고·지원 저장이 없습니다.
- 응답 사례 이름 매핑: 과거, 연락처 미등록, 선정, 미선정, 조건 변경, 대기, 버전 null.
- 선언:
  - READ_COMMITTED 트랜잭션.
  - `lockEmployer`의 PESSIMISTIC_WRITE와 `m.role = 'EMPLOYER'` 조건.
  - 서비스·저장소·API·오류 처리기의 farm-demo 프로필.
  - 쓰기 SQL은 공고·고용주·표식 범위로 제한되고 DELETE와 member 갱신이 없습니다. 상태 변경은 `status = 'OPEN'`인 새 일정만 대상입니다.
- 준비 API(standalone):
  - 신규 201, 재사용 200, no-store.
  - 오류 매핑: AccessDenied 403, 저장 실패 503, DB·트랜잭션 503, 그 밖 500.
  - 화면 GET은 HTML·no-store이고 준비 서비스를 호출하지 않습니다. 준비 GET은 405입니다.

**mock 예외와 트랜잭션·잠금 선언 확인으로 실제 DB 롤백이나 동시 준비 직렬화가 증명된 것은 아닙니다.**

### demo 화면 (Node 가짜 DOM 22개, 실제 브라우저 아님)

- 로그인·계정:
  - 고용주 로그인을 확인합니다. 근로자나 401이면 데이터를 비우고 요청을 중단합니다.
  - 계정이 바뀌면 이전 데이터를 지웁니다.
- 진입·시연 준비:
  - 진입 시 `/me` → 내 공고 → `/me`만 호출하고, 준비 POST는 버튼을 눌렀을 때만 보냅니다.
  - 준비 순서: CSRF 자동 조회 → 계정 확인 → POST(CSRF 헤더만, 본문 없음) → 계정 확인 → 내 공고 → 첫 날짜 PENDING 20명 조회.
  - 신규 201과 재사용 200을 구분해 안내합니다.
- 실패 안내:
  - 준비 실패(POST 500)와 후속 목록 실패(500·통신 실패)를 구분해 안내하고, 후속 실패 시 사례는 유지합니다.
  - 불확실한 POST(통신 실패·형식 오류)는 자동 재전송하지 않고 성공으로 표시하지 않습니다.
  - CSRF 실패(500·빈 토큰·통신 실패)면 POST가 없습니다.
- 목록 조회:
  - 기본 PENDING·20명이고, 정원·확정·남은 자리를 표시합니다.
  - 전체·선정·미선정 필터는 처음부터 조회합니다.
  - 다음 페이지에 서버 cursor를 전달하고, 마지막 페이지면 다음 버튼이 비활성입니다.
  - 공고·날짜·필터·개수·ID 입력을 바꾸면 이전 커서와 명단을 지웁니다.
- 상세·연락처:
  - 상세: 변경 행 강조, 과거 좌표 확인 불가, 부분 비교 안내, 약정 표시, "조회로 선정·동의 상태를 변경하지 않음" 안내.
  - 연락처: 전용 GET으로 요청하고 전체 번호는 연락처 영역에만 표시합니다. 진단 JSON에는 `[연락처 영역에서만 표시]`로 나오고, 요청 기록·다른 영역·브라우저 저장소에는 번호가 없습니다.
  - 미선정 행은 연락처 버튼이 비활성입니다. 사례의 미선정·과거 연락처는 403을 표시하고 전체 초기화하지 않습니다.
  - 과거 상세는 404를 표시하고, 미등록 연락처는 "연락처 미등록"으로 표시합니다.
  - 목록 행 경로에서 상세·연락처가 실패(500·통신·형식 오류)해도 해당 영역만 오류를 표시하고 명단은 유지합니다.
- 전체 번호 제거:
  - 다른 동작, 창 포커스 이탈, 계정 변경, 화면 이탈 시 번호 표시를 지웁니다.
  - 연락처 대기 중 계정이 바뀌거나 화면을 떠난 뒤 도착한 응답의 번호는 표시하지 않습니다.
- 중복·권한·입력:
  - POST 대기 중에는 버튼을 다시 눌러도 추가 요청이 없습니다.
  - 401과 403 `EMPLOYER_ROLE_REQUIRED`는 전체 초기화하고, 403 `CONTACT_ACCESS_DENIED`는 초기화하지 않습니다.
  - 외부 문자열은 textContent로만 표시하고, innerHTML 계열·저장소·쿠키·console에 접근하지 않습니다.
  - 한글 버튼·안내를 유지하고, 잘못된 ID·개수는 요청 없이 한글로 안내합니다.
- 정적 검사(Java):
  - 로그인 화면의 지원자 링크는 기본 숨김이고 고용주일 때만 표시합니다(문자열 확인).
  - SecurityConfig: 화면 permitAll과 준비 POST hasRole(EMPLOYER)은 farm-demo 블록 안에 있습니다. 정식 GET 3종은 hasRole(EMPLOYER)이고 근로자용 `/api/job-postings/*` 규칙보다 먼저 선언돼 있으며, 403 JSON 처리 경로 정규식이 있습니다.

## 7. 관찰 사항 (결함 아님, 정책 확인 권장)

- **P-1:** 연락처 응답을 기다리는 중에 창 포커스가 이탈하면, 이탈 뒤 도착한 응답의 전체 번호가 표시됩니다. blur는 이미 표시된 번호만 지우고 대기 중 요청은 취소하지 않기 때문입니다. [관찰] 테스트로 현재 동작을 기록했습니다.
- **P-2:** 시연 사례의 상세·연락처 버튼은 같은 일정이어도 `setTarget()`으로 명단을 먼저 비웁니다. 응답이 성공이든 실패든 같습니다. 목록 행 버튼은 명단을 유지합니다. [관찰] 테스트로 기록했습니다.
- **P-3:** 목록 행의 "연락처 보기" 버튼은 `phoneState === 'AVAILABLE'`일 때만 활성입니다. 번호가 미등록인 현재 대기자는 사례 버튼이나 직접 입력으로만 연락처 API를 호출할 수 있습니다. 서버의 `canViewContact`는 번호 상태와 무관하게 true이며, 이는 문서와 일치합니다.
- **P-4:** 확정 인원 집계는 기존 `countOccupyingAssignments`를 재사용합니다. CANCELLED만 제외하고 EARLY_ENDED·완료는 셉니다(문서와 일치). 선언만 확인했습니다.
- **P-5:** limit 검사가 `[0-9]{1,3}`라서 앞자리 0(`007`)을 허용합니다. 소스를 읽어 확인했고, 테스트는 작성하지 않았습니다.
- **P-6:** 목록 마스킹(MySQL ICU `[-[:space:]]`)과 연락처 정규화(Java `(?U)[\s-]`)는 정규식 엔진이 다릅니다. 특수 공백 등에서 두 결과가 같은지는 확인하지 않았습니다.

## 8. 미검증 범위와 후속 통합 검증 필요 항목

- 실제 MySQL에서의 SQL 실행:
  - 정렬(지원 시각·ID 오름차순)과 커서 경계
  - 현재 회차 필터
  - `regexp_replace` 마스킹과 연락처 상태 계산
  - `countOccupyingAssignments` 집계
  - 연락처 CASE 식
  - JOIN 중복 여부
- REPEATABLE_READ / READ_COMMITTED의 실제 동작, 실제 롤백
- 고용주 행 PESSIMISTIC_WRITE 잠금에 의한 동시 준비 직렬화와 중복 방지
- 시연 데모 INSERT/UPDATE의 실제 실행과 DB 제약(V1·V6·V12 등)
- 실제 WorkScheduleRevision 저장·버전 증가가 DB에 반영된 결과
- Spring Security 전체 체인:
  - 익명 401, hasRole(EMPLOYER) 실제 차단
  - CSRF 거절과 세션 쿠키
  - 403 JSON 처리기의 실제 응답
  - 근로자용 `/api/job-postings/*` 규칙과의 경로 매칭
- farm-demo 프로필의 실제 빈 등록과 기본 프로필 차단
- Spring Boot JsonMapper 설정
- 실제 브라우저 렌더링·포커스·조작
- 커버리지(측정하지 않음)

## 9. 운영 코드 변경 여부와 실행 후 git 변경

- 운영 코드, 기존 테스트, Gradle, CI, 문서, README는 **변경하지 않았습니다.**
  - `git diff` 해시가 실행 전후 동일합니다.
  - sha256 비교에서 기존 파일은 변경 없음이며, 차이는 추가 파일뿐입니다(`src/main`, `src/test`, Gradle, `gradle/`, `.github`, `docs/*.md`, `docs/verification` 전체).
- 서버·Docker·Testcontainers·DB·`.env`·마이그레이션을 사용하지 않았고, 실제 시연 데이터도 만들지 않았습니다.
- 커밋·푸시·PR·병합·stash·reset을 하지 않았습니다.
- 실행 전후 브랜치 `work-applicants`, HEAD `bdb7748`은 같습니다.

`git status`에서 새로 생긴 파일(모두 미추적 ??):

```
farm-api/src/test/java/com/wonday/farm/work/testsupport/WorkApplicantRows.java
farm-api/src/test/java/com/wonday/farm/work/validation/WorkApplicantCursorTest.java
farm-api/src/test/java/com/wonday/farm/work/validation/ApplicantPhoneTest.java
farm-api/src/test/java/com/wonday/farm/work/service/WorkApplicantQueryServiceTest.java
farm-api/src/test/java/com/wonday/farm/work/api/WorkApplicantApiTest.java
farm-api/src/test/java/com/wonday/farm/work/repository/WorkApplicantRepositoryDeclarationTest.java
farm-api/src/test/java/com/wonday/farm/demo/applicant/DemoWorkApplicantFixtureServiceTest.java
farm-api/src/test/java/com/wonday/farm/demo/applicant/DemoWorkApplicantApiTest.java
farm-api/src/test/java/com/wonday/farm/demo/applicant/WorkApplicantsDemoPageTest.java
farm-api/docs/verification/2026-11-28-work-01-applicants/work-applicants-page.test.mjs
farm-api/docs/verification/2026-11-28-work-01-applicants/VERIFICATION_REPORT.md
```


## 10. PR 리뷰 p3 보완 — 재검증 전

위 통과 수치는 보완 전 실행 기록이다. 이후 WORK-01 PR 리뷰에 따라 아래를 수정했다.
- 연락처 요청에 별도 유효성 번호를 부여하고 blur·탭 숨김·화면 초기화 시 증가시킨다.
- 요청 시작 시 비활성 화면을 차단하고, 응답 뒤 계정 재확인까지 끝난 표시 직전에 번호·포커스·탭 상태를 검사한다.
- 포커스 복귀만으로 이전 응답의 표시 권한을 되살리지 않는다.
- P-1 관찰 테스트를 제거하고 지연 응답·계정 재확인 중 blur·비활성 요청·탭 숨김의 회귀 테스트로 교체했다.
- 이번 보완 후 테스트는 실행하지 않았다. 이전 165/22 통과 기록을 보완 후 결과로 해석하지 않는다.

## 11. PR 리뷰 p3 보완 — 재검증 결과 (2026-11-28, 검증자)

> 이 절은 보완 후 별도 실행 기록이다. 1~9절의 보완 전 Java 165/165·화면 22/22 통과와 합산하지 않는다.
> Java·Gradle(`unitTest` 포함)·통합 테스트는 이번에 실행하지 않았다. 실제 브라우저 검증이 아니다.

### 11.1 확인한 상태
- 브랜치 `work-applicants`, HEAD `009b613`, 저장소·farm-api 모두 AGENTS.md 없음.
- 보완 diff는 세 파일(`farm-work-applicants.html`, `work-applicants-page.test.mjs`, 이 보고서)에만 있음. `work-selection` worktree는 건드리지 않음.
- 보완 내용(diff 확인): `contactSequence` 증가(`clearContact` — blur·`visibilitychange` hidden·화면 초기화·`run` 시작 시), `loadContact` 시작 시 `document.hasFocus()`·`visibilityState` 검사, `call`(연락처 GET + `/me` 재확인) 완료 뒤 번호·포커스·탭 상태 재검사.
- 진단창·요청 기록은 `showReply`가 연락처 `phone`을 `[연락처 영역에서만 표시]`로 바꿔 기록함(보완 전과 동일).

### 11.2 실행 명령 (Node만)
```
cd C:\Users\HCB\Desktop\wonday\farm-api
node --test ./docs/verification/2026-11-28-work-01-applicants/work-applicants-page.test.mjs
```
Node v26.13.1.

| 실행 | 대상 | tests | pass | fail | skipped |
|---|---|---:|---:|---:|---:|
| 1차 | 작성자 보완본 그대로 | 25 | 25 | 0 | 0 |
| 최종 | 검증자 보강 2건 추가 후 | 27 | 26 | **1** | 0 |

- 25 = 기존 22 − P-1 관찰 1 + 작성자 회귀 4.
- 보조 확인: 보완 **전** HTML(HEAD 원본)을 scratchpad 사본에 두고 같은 27개를 돌리면 작성자 회귀 4건과 검증자 보강 2건 모두 실패(21/27). 작성자 회귀 테스트가 원래 문제를 실제로 잡는다는 뜻이며, 정식 결과에는 넣지 않는다.

### 11.3 요청 항목별 결과 (가짜 DOM·가짜 fetch)
| # | 항목 | 결과 | 테스트 |
|---|---|---|---|
| 1 | 연락처 응답 대기 → blur → 응답 도착 시 번호 미표시 | 통과 | 작성자 `연락처 응답 대기 중 blur 후…` |
| 2 | blur 후 focus 복귀해도 이전 응답 번호 미표시 | 응답 대기 중 이탈은 통과 / **클릭 직후 `/me` 대기 중 이탈은 실패(11.4)** | 같은 테스트(refocus) · 검증자 보강 2 |
| 3 | 연락처 응답 뒤 `/me` 대기 중 blur → 미표시 | 통과 | 작성자 `연락처 응답 뒤 계정 재확인…` |
| 4 | 비활성 화면에서 연락처 GET 미시작 | 통과 | 작성자 `비활성 화면에서는…` |
| 5 | 포커스 복귀 후 새 클릭은 정상 표시 | 통과(비활성 클릭 후·지연 응답 무효화 후 두 경우) | 작성자 4번 테스트 · 검증자 보강 1 |
| 6 | 탭 숨김 → 표시 → 지연 응답, 번호 미부활 | 통과 | 작성자 `탭이 숨겨진 뒤…` |
| 7 | 정상 조회·권한 거부·계정 전환·화면 이탈 등 기존 회귀 | 통과(기존 21건 모두) | 기존 테스트 |
| 8 | 진단창·요청 기록·브라우저 저장소에 번호 없음 | 통과 | 새 시나리오는 모든 요소 텍스트 검사, 검증자 보강 1은 `textExcept`·`forbidden=[]` 추가 |

### 11.4 발견한 결함 (재현 테스트 실패 유지, 기대값 낮추지 않음)
**D-1. 클릭 직후 계정 확인(`/me`) 대기 중 blur·탭 숨김이 일어나고 응답 전에 복귀하면, 이탈 전 클릭의 전체 번호가 표시된다.**

재현 테스트: `[재검증 보강] 클릭 직후 run의 계정 확인(/me) 대기 중 blur·탭 숨김 → …` (blur·hidden 두 경우 모두 실패)
1. 명단 조회 후 `/api/auth/me` 응답을 보류한 채 501 `연락처 보기` 클릭 → 이 시점 연락처 GET 0회.
2. blur → focus (또는 hidden → visible).
3. `/me` 응답 도착.
4. 결과: 연락처 GET 1회가 나가고 `contact-note`에 `시연 근로자 501 · 지원 #501\n01000001001` 표시.

원인: `run`이 `clearContact()` → `requireEmployer`(/me) → `action` 순서로 동작하고, `loadContact`가 시작할 때 다시 `clearContact()`(번호 증가) 후 `requestSequence`를 잡는다. 그래서 `/me` 대기 중 이탈로 올라간 번호가 이 시점에 새 기준으로 덮이고, 그때 포커스·탭이 이미 복귀해 있으면 `contactIsActive()`도 통과한다. 즉 "포커스 복귀만으로 이전 요청의 표시 권한을 복구하지 않는다"가 연락처 GET 시작 전 구간에서는 지켜지지 않는다.
- 영향: 표시는 복귀(포커스 있음) 상태에서만 일어나므로 비활성 화면 노출은 아니다. 다만 이탈 전 클릭이 복귀 후 새 클릭 없이 번호를 띄운다.
- 수정 방향(참고, 운영 코드는 고치지 않음): 클릭(`run` 진입) 시점의 번호를 `loadContact`에 전달해 GET 전·후에 비교하거나, `loadContact` 안에서 번호를 다시 올리지 않는 방식.

### 11.5 그 밖의 관찰
- O-1: 무효화된 응답은 아무 안내 없이 버려지고 `contact-note`는 `전체 연락처 조회 전`으로 남는다. 다시 누르라는 안내는 비활성 상태에서 시작한 경우에만 나온다(UX 확인 권장, 결함 아님).
- O-2: 무효화돼도 연락처 GET·`/me`는 이미 서버에 나갔고, 진단창·요청 기록에는 마스킹된 응답이 남는다(번호 없음 확인).

### 11.6 한계
- `document.hasFocus()`·`visibilityState`·blur·`visibilitychange`는 테스트의 가짜 객체가 흉내 낸 것이다. 실제 브라우저의 이벤트 순서(blur와 visibilitychange의 선후, 개발자 도구 포커스, 모바일 백그라운드 전환, bfcache)는 확인하지 않았다.
- 서버·세션·CSRF·Security 체인·DB는 없다. 연락처 응답은 가짜 서버 값이다.

### 11.7 이번 재검증 변경 파일
- `docs/verification/2026-11-28-work-01-applicants/work-applicants-page.test.mjs`: 검증자 보강 테스트 2건 추가(기존·작성자 테스트 수정 없음).
- `docs/verification/2026-11-28-work-01-applicants/VERIFICATION_REPORT.md`: 이 11절 추가(10절 포함 기존 내용 수정 없음).
- 운영 코드(`farm-work-applicants.html`)·Java·Gradle은 수정하지 않음. 커밋·푸시 없음.


## 클릭 직후 로그인 확인 대기 구간 D-1 보완 — 재검증 전

- 원래 work-applicants 재검증의 27개 중 1개 실패 기록을 바탕으로 초기 /me 대기 구간을 보완했다.
- 연락처 표시용 contactSequence와 별개로 포커스 이탈 전용 contactFocusSequence를 추가했다.
- run 진입에서 첫 await 이전에 포커스 기준과 활성 상태를 고정한다.
- blur·탭 숨김은 포커스 번호를 증가시키며, 복귀나 loadContact 내부 초기화로 이 기준을 재설정하지 않는다.
- 연락처 GET 전과 응답 뒤 계정 확인 완료 후에 클릭 당시 기준을 모두 검사한다.
- 사례 버튼이 명단을 초기화하는 정상 동작은 포커스 이탈로 취급하지 않는다.
- 기존 실패 테스트의 기대값은 변경하지 않았다. 검증자 보강 두 테스트를 work-selection에도 보존했다.
- 이번 보완은 스크립트 문법만 확인하고 테스트는 실행하지 않는다. 기존 실행 결과를 이번 보완의 통과 결과로 사용하지 않는다.

## 12. D-1(클릭 직후 포커스 이탈) 보완 — 2차 재검증 결과 (2026-11-28, 검증자)

> 이 절은 D-1 보완 후 따로 실행한 기록이다. 1~9절의 보완 전 Java 165/165·화면 22/22, 11절의 25/25·26/27 결과와 합산하지 않는다.
> Java·Gradle(`unitTest` 포함)·통합 테스트는 이번에 실행하지 않았다. 실제 브라우저·Security 체인·DB 검증이 아니다.

### 12.1 상태
- 브랜치 `work-applicants`, HEAD `009b613`(변경 없음), AGENTS.md 없음(저장소·farm-api 모두).
- 미커밋 변경은 세 파일(`farm-work-applicants.html`, `work-applicants-page.test.mjs`, 이 보고서)뿐이다. `work-selection` worktree는 건드리지 않았다.
- 운영 HTML 해시: 11절 실행 때 `35b057d3…` → 이번 실행 때 `c07109bb…`(작성자 보완 반영).
- 테스트 파일은 11절 이후 바뀌지 않았다. 선언 27개, 검증자 보강 2건도 그대로이고, 기대값 변경·제외·건너뜀은 없다.

### 12.2 소스 대조 (`farm-work-applicants.html`)
- `contactSequence`(화면 정리)와 `contactFocusSequence`(포커스 이탈)를 분리했다. `invalidateContactFocus`는 blur와 `visibilitychange`(hidden)에서만 불린다.
- `run`은 첫 `await`(`requireEmployer`) 전에 같은 동기 구간에서 `context.contactFocusSequence`와 `contactStartedActive=contactIsActive()`를 고정한다.
- `contactActionIsActive(context)`는 세 조건을 모두 요구한다. 그래서 포커스가 돌아와도 클릭 당시 기준이 다시 살아나지 않는다.
  - 클릭 당시 화면이 활성이었을 것
  - 포커스 번호가 그 뒤로 바뀌지 않았을 것
  - 지금 화면이 활성일 것
- `loadContact`는 연락처 GET 전에 한 번, `call`(GET + 후속 `/me`) 완료 뒤에 한 번 이 조건을 검사한다. 응답 뒤 검사는 화면 정리 번호(`requestSequence`)도 함께 비교한다.
- 사례 버튼의 `setTarget → clearList → clearDetail → clearContact`와 `loadContact` 안의 `clearContact`는 `contactSequence`만 올린다. 포커스 기준은 바뀌지 않으므로 새 클릭이 무효화되지 않는다.

### 12.3 실행
```
cd C:\Users\HCB\Desktop\wonday\farm-api
node --test ./docs/verification/2026-11-28-work-01-applicants/work-applicants-page.test.mjs
```
Node v26.13.1.

| 실행 | 실행 | 성공 | 실패 | 건너뜀 | 취소 |
|---|---:|---:|---:|---:|---:|
| 이번(D-1 보완 후) | 27 | **27** | 0 | 0 | 0 |

선언 수 27개와 실행 수가 같다.

### 12.4 중점 항목 대조 (가짜 DOM·가짜 fetch)
| 항목 | 결과 | 근거 테스트 |
|---|---|---|
| 초기 `/me` 대기 중 blur→focus: 이전 클릭의 GET·번호 없음 | 통과(**D-1 해결**) | `[재검증 보강] 클릭 직후 run의 계정 확인(/me) 대기 중 …`(blur) |
| 초기 `/me` 대기 중 hidden→visible: 표시 권한 복구 없음 | 통과(**D-1 해결**) | 같은 테스트(hidden) |
| 연락처 GET 대기 중 이탈: 늦은 번호 미표시 | 통과 | `연락처 응답 대기 중 blur 후…`, `탭이 숨겨진 뒤…` |
| 응답 뒤 후속 `/me` 대기 중 이탈: 미표시 | 통과 | `연락처 응답 뒤 계정 재확인 대기 중 blur…` |
| 비활성 화면에서 시작한 클릭: 복귀만으로 유효해지지 않음 | 통과 | `비활성 화면에서는 연락처 GET을 시작하지 않으며…` |
| 복귀 후 새 클릭: 전체 번호 표시 | 통과 | 위 테스트 후반, `[재검증 보강] 지연 응답 무효화 후 포커스 복귀…` |
| 사례 버튼의 화면 초기화가 새 클릭을 무효화하지 않음 | 통과(아래 한계 참고) | 사례 `연락처 403 확인`(403 표시), 사례 505 `연락처`(`연락처 미등록` 표시) |
| 정상 조회·403·404·계정 전환·화면 이탈 유지 | 통과 | 기존 회귀 테스트 |
| 진단 JSON·요청 기록·브라우저 저장소에 전체 번호 없음 | 통과 | `연락처: 전용 GET…`, `[재검증 보강] 지연 응답…`(`textExcept`·`forbidden=[]`), `외부 문자열은 textContent로만…` |

- 이전 실패 1건(D-1)은 이번 실행에서 통과해 해결됐다.
- 추가로 발견한 결함은 없다.

### 12.5 검증 공백 (테스트 범위는 늘리지 않았다)
- **G-1:** 사례 버튼 경로는 403·미등록(phone=null) 표시로만 확인했다.
  - 두 경우 모두 응답 뒤 검사를 통과해야 화면에 나오므로, 사례 초기화가 새 클릭을 잘못 무효화하지 않는다는 점은 확인된다.
  - 사례 버튼으로 **전체 번호(AVAILABLE)**를 표시하는 경우와, 사례 경로 중 포커스 이탈 경우는 직접 테스트하지 않았다.
- **G-2:** 무효화된 응답은 아무 안내 없이 버려진다(11.5 O-1과 같음, 결함 아님).

### 12.6 한계
- `document.hasFocus()`·`visibilityState`·blur·`visibilitychange`는 가짜 객체가 흉내 낸 것이다.
- 실제 브라우저의 이벤트 순서, 개발자 도구 포커스, 모바일 백그라운드 전환, bfcache는 확인하지 않았다.
- 서버·세션·CSRF·Security 체인·DB는 없다. 연락처 응답은 가짜 서버 값이다.

### 12.7 이번 재검증 변경 파일
- `docs/verification/2026-11-28-work-01-applicants/VERIFICATION_REPORT.md`: 이 12절 추가(기존 절 수정 없음).
- 테스트 파일·운영 코드·Java·Gradle은 이번에 수정하지 않았다. 커밋·푸시 없음.
