# 보험 서버용 급여 계좌 등록 상태 조회 API · farm-demo 독립 단위 검증 (2026-11-27)

## 1. 요약

- **최종 Gradle 선택 실행:** 138개를 실행해 138개 통과했어요. 실패·오류·건너뜀은 0개예요.
  - 신규 테스트: 84개
  - 직접 영향을 받는 기존 테스트: 54개
- **첫 Gradle 실행:** 138개 중 137개 통과, 1개 실패했어요.
  - 실패한 테스트: 기존 `WorkerVerificationDemoPageTest$SecurityDeclarations.farmChain`
  - 원인: 데모 GET `permitAll` 목록에서 `/demo/worker-verification`이 **마지막**이라고 가정한 정적 검사였어요. 이번에 그 뒤에 `/demo/insurance-salary-account`가 추가됐어요.
  - 판단: **운영 결함이 아니라 테스트 자체의 취약한 기대값**이에요. 팀이 `SalaryAccountDemoPageTest`에서 고친 것(6ede564)과 같은 방식으로 수정한 뒤 다시 실행했어요.
- **node 화면 테스트:** 운영 인라인 스크립트 원문을 가짜 DOM·가짜 fetch로 실행해 11개 통과했어요. Gradle과 **별개의 실행**이라 합산하지 않았어요. 실제 서버에는 연결하지 않았어요.
- **결함:** 운영 코드 결함은 없어요. 관찰 사항은 2건이에요(5절).
- **보존:** 운영 코드·화면·설정·Gradle·CI는 수정하지 않았어요. 실행 전후 해시가 같아요.
- **합산하지 않은 것:** 사용자 수동 확인(8절), signup·보험 V2·급여 계좌 관리의 기존 검증 결과

## 2. 작업 위치

| 항목 | 값 |
|---|---|
| 경로 | `C:\Users\HCB\Desktop\wonday` (`farm-api`에서 실행) |
| 브랜치 | `insurance-salary-account` (브랜치 변경·병합·리베이스 없음) |
| 기준 커밋 | `bc85bb9` (Merge PR #27). 이번 기능은 커밋되지 않은 작업 트리 변경 |
| AGENTS.md | 저장소 루트·`farm-api` 모두 없음 |
| `.codex` worktree | 사용하지 않음 |

검증 대상 작업 트리 변경은 다음과 같아요.
- **수정:** `SecurityConfig`, `InsuranceSecurityConfig`, `farm-login.html`, README, `insurance-integration-api.md`
- **신규:** `InsuranceSalaryAccountApi`, `WorkerSalaryAccountResponse`, `WorkerSalaryAccountQueryService`, `WorkerSalaryAccountRepository`, `DemoInsuranceSalaryAccountApi`, `farm-insurance-salary-account.html`, `insurance-salary-account-api.md`
- `ServiceTokenFilter`·`InsuranceErrorHandler`는 이번에 바뀌지 않았어요.

## 3. 추가·변경한 테스트 파일

### 신규 (84)

| 파일 (`farm-api/src/test/java/com/wonday/farm/` 기준) | 수 | 목적 |
|---|---|---|
| `insurance/service/WorkerSalaryAccountQueryServiceTest` | 36 | 등록·미등록·앞자리 0, 400·404, 비정상 결과 503, 예외 전파, 구조·쿼리·DTO 정적 확인 |
| `insurance/api/InsuranceSalaryAccountApiTest` | 30 | 경로·200·no-store·세 필드, ID 400, 404·503·500, 비노출, 실제 서비스 연결, 관찰 O-1 |
| `demo/insurance/DemoInsuranceSalaryAccountApiTest` | 15 | 선언·화면, principal ID만 사용, 거절 403, 다른 workerId 503, DB 역할 변경 404, 예외 변환 |
| `demo/insurance/InsuranceSalaryAccountDemoPageTest` | 3 | 화면 정적 구조, 로그인 링크, 두 보안 체인의 **선언** |

### 기존 영향 테스트 (54, 바뀐 설정·화면을 직접 확인하는 클래스만)

| 클래스 | 수 | 선택 이유 | 변경 |
|---|---|---|---|
| `demo.insurance.WorkerVerificationDemoPageTest` | 9 | `SecurityConfig`·`InsuranceSecurityConfig`·`farm-login.html` 정적 검사 | **1줄 수정**(5절) |
| `demo.SalaryAccountDemoPageTest` | 3 | `SecurityConfig`·`farm-login.html` 정적 검사 | 없음 |
| `insurance.security.ServiceTokenFilterTest` | 33 | `InsuranceSecurityConfig` 변경 (필터는 같은 체인) | 없음 |
| `demo.AuthDemoPageTest` / `SignupDemoPageTest` / `JobLocationDemoPageTest` | 1 / 3 / 5 | `farm-login.html` 변경 | 없음 |

`InsuranceIntegrationApiTest`·`InsuranceWorkerVerificationV2ApiTest`는 변경되지 않은 컨트롤러·처리기만 다루므로 이번 범위에서 제외했어요.

### node 화면 테스트 (별도)

- 파일: `farm-api/docs/verification/2026-11-27-insurance-salary-account/insurance-salary-account-page.test.mjs` (11개)
- 운영 스크립트 원문을 `node:vm`에서 실행해요. 로직을 테스트에 복제하지 않았어요.
- `fetch`는 전부 가짜이고 실제 HTTP 요청은 없어요.

## 4. 실행 명령·범위·시간

JDK 경로: `JAVA_HOME=C:\Users\HCB\Desktop\woori-workspace\00.sw\jdk-21.0.12.1+1`

```
# farm-api
./gradlew.bat --no-daemon unitTest \
  --tests 'com.wonday.farm.insurance.service.WorkerSalaryAccountQueryServiceTest*' \
  --tests 'com.wonday.farm.insurance.api.InsuranceSalaryAccountApiTest*' \
  --tests 'com.wonday.farm.demo.insurance.DemoInsuranceSalaryAccountApiTest*' \
  --tests 'com.wonday.farm.demo.insurance.InsuranceSalaryAccountDemoPageTest*' \
  --tests 'com.wonday.farm.demo.insurance.WorkerVerificationDemoPageTest*' \
  --tests 'com.wonday.farm.demo.SalaryAccountDemoPageTest*' \
  --tests 'com.wonday.farm.insurance.security.ServiceTokenFilterTest*' \
  --tests 'com.wonday.farm.demo.AuthDemoPageTest*' \
  --tests 'com.wonday.farm.demo.SignupDemoPageTest*' \
  --tests 'com.wonday.farm.demo.JobLocationDemoPageTest*'

# 저장소 루트
node --test farm-api/docs/verification/2026-11-27-insurance-salary-account/insurance-salary-account-page.test.mjs
```

| 실행 | 실행 | 통과 | 실패 | 오류 | 건너뜀 | 시간 |
|---|---|---|---|---|---|---|
| Gradle 1차 (기존 테스트 수정 전) | 138 | 137 | 1 | 0 | 0 | BUILD FAILED 32초 |
| **Gradle 최종 (같은 선택 범위)** | **138** | **138** | 0 | 0 | 0 | BUILD SUCCESSFUL 21초 |
| node 화면 스크립트 (별개) | 11 | 11 | 0 | 0 | 0 | 약 0.14초 |

최종 138개 중 신규는 84개, 기존 영향은 54개예요. 두 Gradle 실행은 같은 범위를 다시 실행한 것이라 합산하지 않았어요.

- **선택 범위 확인:** 최종 결과 XML은 22개(위 10개 클래스와 그 `@Nested`)예요.
  - testcase의 classname은 10개 클래스뿐이에요. 다른 기능 테스트는 섞이지 않았어요.
  - 클래스별 수: 36+30+15+3 / 9+3+33+1+3+5 = 138
  - `@Nested`(Success·Rejected·Inconsistent·Structure, Ok·BadRequest·Errors, Declarations 등)는 모두 실행됐어요.
- **실행하지 않은 것:** 전체 `test`·전체 `unitTest`·`integrationTest`·`fullTest`·`build`·`check`, 커버리지 리포트

### 결과 위치 (docs로 복사하지 않음)

- XML: `farm-api/build/test-results/unitTest/TEST-com.wonday.farm.*.xml` (최종 실행 22개)
- HTML: `farm-api/build/reports/tests/unitTest/index.html`
- node 결과는 콘솔 출력만 있어요.

## 5. 실패·결함·관찰

### 1차 실행의 실패 (테스트 자체 오류)

- **테스트:** `WorkerVerificationDemoPageTest$SecurityDeclarations.farmChain` (보험 V2 검증 때 제가 작성)
- **기대값:** 패턴 `requestMatchers(HttpMethod.GET, …"/demo/worker-verification")\.permitAll()` — 이 경로가 목록의 마지막이어야 일치해요.
- **실제값:** 목록이 `…"/demo/worker-verification", "/demo/insurance-salary-account").permitAll()`로 바뀌어 일치하지 않았어요.
- **원인:** 정적 검사가 목록 순서에 의존했어요. 운영 설정은 의도대로 경로를 추가한 것이고, `/demo/worker-verification`의 `permitAll`은 유지돼 있어요.
- **처리:**
  - 경로 뒤에 `[^)]*`를 허용하도록 고쳤어요. 같은 GET `permitAll` 호출 안에 있는지는 계속 확인해요.
  - 경로 문자열·`permitAll`·farm-demo 분기 조건과 나머지 검사는 그대로예요.
  - 팀의 `SalaryAccountDemoPageTest` 수정(6ede564)과 같은 방식이고, 이유는 주석에 남겼어요.

### 운영 코드 결함

없어요.

### 관찰 (결함으로 분류하지 않음, 판단은 작성자 몫)

- **O-1. workerId 경로 변환이 관대해요.**
  - 동작: Spring 기본 String→long 변환(`NumberUtils.parseNumber`) 때문에 다음 값들이 400이 아니라 **101로 변환돼 정상 조회돼요.**
    - 16진수 `0x65`·`#65`
    - `+101`
    - 앞자리 0 `0101`
    - 전각 숫자 `１０１`
  - 기록 방식: 현재 동작을 그대로 기록하는 테스트(`InsuranceSalaryAccountApiTest$BadRequest.lenientConversion`)로 남겼어요.
  - 거절되는 값: `abc`·`1.5`·`1e2`·`1L`·Long 범위 초과(양·음)·`0x`·`--1`은 400이에요.
  - 영향: 토큰이 필요한 내부 API이고, 같은 숫자 ID로만 바뀌므로 다른 회원 정보가 노출되지는 않아요.
  - 선택지: 문서의 "숫자가 아닌 ID는 400"을 엄격한 10진수로 해석한다면, 경로를 `{workerId:[0-9]+}`로 제한하거나 String으로 받아 검사해야 해요. 그대로 둔다면 문서에 현재 동작을 적어야 해요.
- **O-2. 내부 API 오류 응답의 no-store는 필터가 붙여요.**
  - `InsuranceErrorHandler`는 오류 응답에 `Cache-Control`을 붙이지 않아요. standalone 테스트에서 null임을 확인했어요.
  - 문서대로 `ServiceTokenFilter`가 모든 응답에 no-store를 먼저 붙이는 구조예요.
  - 필터 없는 이번 테스트에서는 **오류 응답의 no-store를 통과로 확인하지 않았어요.** 성공 200의 no-store는 컨트롤러가 붙이므로 확인했어요.
  - 데모 API는 컨트롤러 내부 처리기가 모든 오류에 no-store를 붙이는 것을 확인했어요.

## 6. 항목별 결과

### A. 조회 서비스 (36, Repository mock)

- **정상:**
  - 등록이면 `(101, true, "020")`이고, 요청 ID를 Repository에 그대로 한 번만 전달해요(`verifyNoMoreInteractions`).
  - 미등록이면 `(101, false, null)`이에요.
  - 은행 코드 `002`·`004`·`000`·`999`는 앞자리 0을 포함해 문자열 그대로예요.
  - Long 최대 ID도 조회해요.
  - 등록 → 다른 은행(088) → 해제 순서로 **mock 응답을 바꾸면** 결과가 따라 바뀌어요. 실제 DB 반영이나 동시성 검증은 아니에요.
- **400:** ID 0·-1·Long 최소값은 400이고 Repository를 호출하지 않아요.
- **404 `WORKER_NOT_FOUND`:**
  - 회원 없음
  - 고용주(계좌 있음·없음)
  - 소문자 `worker`, 앞 공백 `" WORKER"`, `ADMIN`, 역할 null
  - 역할이 근로자가 아니면 행 불일치보다 404가 먼저예요.
  - 404 메시지는 없음·고용주 구분 없이 같아요.
- **503 `SERVICE_UNAVAILABLE` (비정상 결과 11가지):** 성공이나 미등록으로 처리하지 않아요.
  - 조회 행 workerId 불일치(미등록·등록), 계좌 회원 ID 불일치
  - 계좌 ID 없음 + 은행 코드 있음, 계좌 있음 + 은행 null
  - 은행 2자리·4자리·문자·공백 포함·전각·빈 값
- **예외 전파:** Repository 예외는 같은 객체 그대로 전파돼요.
- **구조 (정적 확인):**
  - 서비스 필드는 `WorkerSalaryAccountRepository` 하나예요. `SalaryAccountService`·`SalaryAccountCrypto`·쓰기 Repository에 의존하지 않아요.
  - `find`는 readOnly 트랜잭션으로 선언돼 있어요.
  - Repository는 `Repository`만 상속하고 `CrudRepository`가 아니에요. 메서드는 `findStatus` 하나라 저장·삭제가 없어요.

### B. Repository·응답 DTO (정적 확인 + 직렬화)

- **projection:** `AccountStatus`의 getter는 `getWorkerId`·`getMemberRole`·`getAccountMemberId`·`getBankCode` 네 개뿐이에요.
- **쿼리 문자열:**
  - native 쿼리이고 `from member m left join member_salary_account account on account.member_id = m.id`, `where m.id = :workerId` 구조예요.
  - SELECT 목록은 정확히 네 별칭이에요.
  - 암호문·예금주·식별정보·주민번호·전화·비밀번호·버전 컬럼, `*`, inner join이 없어요.
  - **정적 문자열 확인이에요. 실제 MySQL 실행·타입 변환·LEFT JOIN 결과를 입증한 것이 아니에요.**
- **응답 JSON:**
  - 세 필드뿐이고, `workerId`는 정수, `registered`는 boolean, `bankCode`는 문자열이에요.
  - 미등록에도 `bankCode: null`이 들어가요.
  - toString은 가려져요(`[redacted]`).

### C. 실제 보험 API 컨트롤러 (30, standalone MockMvc + InsuranceErrorHandler)

- **정상:**
  - `GET /api/internal/insurance/workers/{id}/salary-account`가 경로의 ID(101)를 서비스에 전달해요.
  - 등록·미등록 모두 200·no-store·JSON 세 필드예요. 미등록은 `bankCode` null이에요.
  - POST는 405, 하위 경로는 404이고 서비스는 호출되지 않아요.
- **실제 서비스 연결(Repository mock):** 등록(004), 미등록, 고용주 404, 계좌 회원 불일치 503, ID 0은 400이에요.
- **400:**
  - `abc`·`1.5`·`1e2`·`1L`·`9223372036854775808`·`-9223372036854775809`·`0x`·`--1`은 경로 변환 단계에서 400이고 서비스를 호출하지 않아요.
  - 0·-1은 실제 서비스가 400을 내고 Repository를 호출하지 않아요.
  - 관찰 O-1(관대한 변환)은 별도 테스트로 기록했어요.
- **404·503·500:**
  - 서비스 404를 그대로 내보내요.
  - 업무 503·`DataAccessResourceFailure`·`QueryTimeout`·`CannotAcquireLock`·`CannotCreateTransaction`·`TransactionTimedOut`은 503이에요.
  - `BadSqlGrammar`·`IllegalState`·NPE는 500이에요.
  - 오류 본문은 `{code, message}`뿐이고, 예외에 넣어 둔 SQL·jdbc·Exception·계좌번호·`v1:`·암호문 문자열이 없어요.
- **오류 응답 no-store:** 처리기가 붙이지 않음을 확인했어요(관찰 O-2). 필터가 붙이는 부분은 미검증이에요.
- **한계:** Security 필터가 없어요. **Bearer 인증·401/403·세션 격리를 검증하지 않았어요.**

### D. demo 컨트롤러 (15, standalone MockMvc, principal 직접 주입)

- **선언:**
  - `@Profile("farm-demo")`, 화면·조회 경로, produces를 확인했어요.
  - 필드·생성자 의존성은 `WorkerSalaryAccountQueryService` 하나예요. 서버 토큰 설정·암호화 키·계좌 관리 서비스가 없어요.
  - **선언 확인일 뿐**, 실제 프로필별 빈 등록은 검증하지 않았어요.
- **화면:** 실제 HTML을 200·text/html·no-store로 반환해요.
- **본인 조회:**
  - principal ID(101)로만 서비스를 호출하고 200·no-store·세 필드를 돌려줘요.
  - 쿼리에 `workerId=202`·`memberId=202`를 넣어도 101로 조회해요.
  - 실제 서비스 연결에서 등록(020) → 은행 변경(088) → 해제(false/null) 순서로 응답이 바뀌어요. mock 응답을 바꾼 것이고 실제 DB 반영 검증은 아니에요.
- **거절:**
  - principal 없음·고용주·ID 0·-1은 컨트롤러 자체 방어로 403 `DEMO_ACCESS_DENIED`이고 서비스를 호출하지 않아요.
  - 이것은 **실제 익명 요청의 Security 401과는 별개**예요. 실제 익명 요청은 필터의 `HttpStatusEntryPoint(401)`가 먼저 처리하도록 설정돼 있지만, 이번에는 검증하지 않았어요.
- **다른 workerId:** 서비스가 202를 반환하면 결과를 노출하지 않고 503이에요(응답에 202·088 없음).
- **DB 역할 변경:** 로그인 뒤 DB 역할이 고용주로 바뀌거나 계정이 삭제되면, 실제 공유 서비스가 404 `WORKER_NOT_FOUND`를 내고 그대로 변환돼요. 은행 코드는 노출되지 않아요.
- **예외 변환:** 404·업무 503·일시적 DB 503·트랜잭션 503, `BadSqlGrammar`·`IllegalState`는 500이에요. 모두 no-store이고 SQL·계좌·토큰 문자열이 없어요.

### E. demo 화면

**Java 정적 검사 (3)**
- 화면에는 input·textarea·select·form이 없어요(토큰·회원 ID 입력 칸 없음).
- 관리 링크 `/demo/salary-account`는 새 창(`target="_blank"`, `rel="noopener noreferrer"`)으로 열리고 처음에는 숨겨져 있어요.
- 스크립트에는 내부 URL·Authorization·Bearer·저장소·cookie·console·innerHTML 계열·location·history가 없어요.
- 로그인 화면의 링크는 기본 숨김이고 근로자일 때만 표시하는 구조예요.

**node 가짜 DOM·가짜 fetch 실행 (11, 위 Gradle 138과 별개)**
- **요청 순서:** `/api/auth/me` → `/demo/api/insurance/salary-account` → `/api/auth/me`. 모두 same-origin·no-store이고 헤더(Authorization 포함)·쿼리·내부 URL이 없어요.
- **표시:** 등록은 `등록됨 (true)`·`020`, 미등록은 `미등록 (false)`·`null · 미등록`이에요. 결과 JSON은 세 필드이고, 조회에 성공하면 관리 링크가 보여요.
- **비교 안내:** 첫 조회 → 미등록→등록 → 은행 변경(020→088) → 같은 정보("같은 은행의 계좌번호 변경은 구분하지 않습니다") → 등록→미등록
- **로그인 상태:** 비로그인(401)·고용주·ID 0·문자열 ID면 계좌를 조회하지 않고 결과를 지워요. 버튼은 복구돼요.
- **조회 도중 계정 변경:** 두 번째 `/me`가 다른 회원·401·고용주이면 결과와 이전 상태를 지워요. 다음 조회는 "첫 조회"로 시작해요.
- **응답 검증:** workerId 불일치·문자열 ID·문자열 boolean·은행 null/2자리·미등록인데 은행 있음·필드 누락·null 본문은 표시하지 않고 미등록으로도 바꾸지 않아요.
- **여분 필드:** 서버가 계좌번호·마스킹 번호·예금주·버전·암호문을 추가로 보내도 세 필드만 표시해요.
- **조회 오류:** 404·503·500(HTML)·403·네트워크 오류는 `registered:false` 성공으로 바꾸지 않아요. 서버 메시지 원문 대신 고정 문구를 쓰고 버튼을 복구해요.
- **중복 조회:** 응답 대기 중 다시 눌러도 조회는 1회이고, 버튼은 비활성이었다가 완료 후 복구돼요.
- **페이지 이탈:** 표시와 이전 상태를 지워요. 이탈 뒤에 도착한 응답(088)은 표시하지 않고, 복귀 후에는 "첫 조회"로 시작해요.
- **기타:** window 처리기는 pagehide·pageshow뿐이라 포커스만 바뀌었다고 갱신하지 않아요. console·localStorage·sessionStorage·history·location 접근은 0회예요.
- **한계:** 가짜 DOM과 가짜 fetch예요. **실제 브라우저 렌더링·실제 세션·실제 HTTP·새 창 동작은 검증하지 않았어요.**

### F. 보안 설정 (선언·소스 검사만)

- `InsuranceSecurityConfig`:
  - `/api/internal/insurance/workers/*/salary-account`가 기존 GET `hasAuthority("INSURANCE_SERVICE")` 규칙에 들어 있어요.
  - `securityMatcher("/api/internal/insurance/**")`, `ServiceTokenFilter` 연결, STATELESS, `NullSecurityContextRepository`, `denyAll`이 그대로 선언돼 있어요. 세션 대신 토큰 체인을 쓰는 구조가 유지돼요.
- `SecurityConfig`:
  - farm-demo 분기 안에서 화면 GET은 `permitAll`, `/demo/api/insurance/salary-account` GET은 `hasRole("WORKER")`예요(한 번만 선언).
  - CSRF 비활성·`ignoringRequestMatchers`가 없고, 농업 체인에 내부 보험 경로 규칙을 추가하지 않았어요.
- `ServiceTokenFilterTest`(기존 33)는 필터 단위 동작으로 계속 통과해요.
- **한계:** **실제 토큰 없음·오류 401, 세션 격리, 권한 우회 방지를 필터 체인에서 실행 검증하지 않았어요.**

## 7. 운영 코드·설정 보존

- 실행 전후로 `src/main`·`build.gradle`·`settings.gradle`·`gradle/`·`.github` 파일 155개의 SHA-256이 같아요. staged diff 해시도 같아요.
- 바뀐 것은 테스트와 보고서뿐이에요.
  - 신규 테스트 파일 4개
  - 기존 `WorkerVerificationDemoPageTest` 1줄(패턴) 수정
  - 이 보고서 폴더(보고서, node 테스트)
- `.env`·실제 키·서버 토큰은 읽거나 출력하지 않았어요. 가상 데이터만 사용했어요.
- 서버·DB·마이그레이션·이관은 실행하지 않았어요. 커밋·푸시·PR도 하지 않았어요.

## 8. 사용자 수동 확인 (별도 기록, 자동 테스트 수에 포함하지 않음)

사용자가 farm-demo에서 등록·미등록·계좌 변경·해제 후 반영을 직접 확인했어요. 이번 자동 테스트로 재현하거나 입증한 것은 아니에요.

## 9. 이번에 검증하지 않은 범위

- **실제 DB:** MySQL에서의 native 쿼리 실행, LEFT JOIN 결과, projection 타입 변환(`BIGINT`→`long`/`Long`, `char(3)`), 계좌 변경·해제 후 다음 조회의 커밋 상태 반영, 동시 변경
- **Security:** `ServiceTokenFilter`·`InsuranceSecurityConfig` 체인을 통한 실제 요청(토큰 누락·오류·미설정 401, 403, 오류 응답 no-store), 쿠키만으로 내부 API에 접근할 수 없는지, 농업 체인의 익명 401·`hasRole("WORKER")` 차단
- **프로필:** farm-demo 활성·비활성에 따른 데모 컨트롤러 등록 여부
- **Spring Boot 구성:** 실제 `JsonMapper`, 실제 경로 변환기 설정(관찰 O-1 포함)
- **브라우저:** 실제 렌더링·새 창 링크·pageshow/pagehide·실제 fetch
- **보험 서버 연동:** 실제 보험 클라이언트 호출과 연동 동의·할인 처리(보험 서버 담당)

## 10. PR 본문용 짧은 검증 요약

```
보험 서버용 급여 계좌 등록 상태 조회·farm-demo 독립 단위 검증 (insurance-salary-account, 기준 bc85bb9 + 작업 트리)
- Gradle unitTest 선택 실행 최종 138개 전부 통과 = 신규 84 + 직접 영향 기존 54
  (신규: 조회 서비스 36 / 내부 API 30 / demo API 15 / 화면·설정 정적 3)
- 1차 실행에서 기존 WorkerVerificationDemoPageTest의 순서 의존 정적 패턴 1건 실패 → 6ede564와 같은 방식으로 갱신 후 통과 (운영 결함 아님)
- 화면 스크립트 원문 가짜 DOM·가짜 fetch node 테스트 11개 통과 (별도 실행, 합산 안 함)
- 확인: 등록 true+은행·미등록 false+null·앞자리 0, ID 0/음수 400(Repository 미호출), 없음·고용주·비정상 역할 404,
  행/계좌 ID 불일치·은행 형식 오류 503, 예외 전파, 조회 전용 구조·암호화/계좌 관리 의존 없음, 쿼리 네 컬럼·LEFT JOIN(정적),
  응답 세 필드·no-store·오류 비노출, demo principal ID만·다른 workerId 503·DB 역할 변경 404, 화면 비교 안내·오류를 미등록으로 바꾸지 않음
- 관찰: workerId 경로의 16진수·+부호·전각 숫자가 숫자 ID로 변환됨, 내부 API 오류 no-store는 필터 담당(단위 미검증)
- Repository mock이므로 실제 MySQL LEFT JOIN·projection 미검증. 토큰 체인·세션 격리·프로필 활성화·브라우저·보험 서버 연동은 후속 통합 검증 대상
- 운영 코드·설정 변경 없음(해시 확인), 커밋 없음
```
