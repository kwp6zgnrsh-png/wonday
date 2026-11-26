# 보험 V2 근로자 확인 API · farm-demo 독립 단위 검증 (2026-11-26)

## 1. 요약

- 이번 자동 단위 테스트(Gradle 선택 실행)는 **160개를 실행해 160개 통과**했어요. 실패·오류·건너뜀은 0개예요.
- 화면 스크립트 node 테스트는 **13개를 실행해 13개 통과**했어요. Gradle 160개와는 **별개의 실행**이라 합산하지 않았어요.
- 운영 코드 결함은 발견하지 못했어요. 관찰 사항은 3건이에요(5절).
- 운영 코드·화면·설정·Gradle·CI는 수정하지 않았어요. 실행 전후 해시가 같아요.
- 사용자 수동 확인 결과(200·404·400)는 8절에 따로 적었고, 자동 테스트 수에 포함하지 않았어요.
- 기존 signup 검증 결과와도 합산하지 않았어요.

## 2. 작업 위치

| 항목 | 값 |
|---|---|
| 경로 | `C:\Users\HCB\Desktop\wonday` (`farm-api`에서 실행) |
| 브랜치 | `worker-verification-v2` |
| 기준 커밋 | `dac094e` (Merge PR #21). V2 구현은 커밋되지 않은 작업 트리 변경 |
| AGENTS.md | 저장소 루트와 `farm-api` 모두 없음 |
| `.codex` worktree | 작업하지 않음 (`C:\Users\HCB\.codex\worktrees\worker-verification-v2\wonday`에는 접근하지 않았어요) |

검증 대상 변경(작업 트리, 커밋 전)은 다음과 같아요.
- **수정:** `SecurityConfig`, `InsuranceSecurityConfig`, `WorkerVerificationRepository`(V2 조회·`V2Match` 추가), `farm-login.html`, README, `insurance-integration-api.md`
- **신규:** V2 API·DTO 2개·서비스, `demo/insurance/DemoWorkerVerificationV2Api`, `farm-worker-verification.html`, `worker-verification-v2-api.md`

## 3. 추가한 테스트 파일

테스트 파일은 모두 새로 만들었어요. 기존 테스트는 수정하지 않았어요.

| 파일 (`farm-api/src/test/java/com/wonday/farm/` 기준) | 수 | 목적 |
|---|---|---|
| `insurance/testsupport/WorkerVerificationV2TestData.java` | 도우미 | 가상 요청·번호·V2Match 생성, 비노출 확인 문자열 목록 |
| `insurance/dto/WorkerVerificationV2RequestTest` | 23 | JSON 문자열 강제, 필수·길이·국적, V1 필드, toString·응답 직렬화 |
| `insurance/service/WorkerVerificationV2ServiceTest` | 62 | 정규화, 입력 거절, HMAC·조회 인자, 0·1·2건, 503, 반환 행 해석, V1 미호출 |
| `insurance/api/InsuranceWorkerVerificationV2ApiTest` | 40 | 경로·200·no-store·JSON, 400·404·409·503·500, 비노출, 실제 서비스 연결 |
| `demo/insurance/DemoWorkerVerificationV2ApiTest` | 26 | 선언, 화면, 본인 200, 403 거절, 다른 근로자 404, 오류 응답, no-store |
| `demo/insurance/WorkerVerificationDemoPageTest` | 9 | 화면 구성, 스크립트 정적 회귀 방지, 보안 설정 **선언** 확인 |

화면 스크립트 실행 테스트(node 내장 `node:test`, 설치 없음)도 하나 추가했어요.
- 파일: `farm-api/docs/verification/2026-11-26-worker-verification-v2/worker-verification-page.test.mjs` (13개)
- 인라인 스크립트 **원문**을 `node:vm`에서 실행해요. `document`·`fetch`·`window`·저장소는 최소한의 가짜 객체예요.

## 4. 실행 명령·범위·시간

JDK 경로: `JAVA_HOME=C:\Users\HCB\Desktop\woori-workspace\00.sw\jdk-21.0.12.1+1`

```
# farm-api
./gradlew.bat --no-daemon unitTest \
  --tests 'com.wonday.farm.insurance.dto.WorkerVerificationV2RequestTest*' \
  --tests 'com.wonday.farm.insurance.service.WorkerVerificationV2ServiceTest*' \
  --tests 'com.wonday.farm.insurance.api.InsuranceWorkerVerificationV2ApiTest*' \
  --tests 'com.wonday.farm.demo.insurance.DemoWorkerVerificationV2ApiTest*' \
  --tests 'com.wonday.farm.demo.insurance.WorkerVerificationDemoPageTest*'

# 저장소 루트
node --test farm-api/docs/verification/2026-11-26-worker-verification-v2/worker-verification-page.test.mjs
```

| 실행 | 실행 | 통과 | 실패 | 오류 | 건너뜀 | 시간 |
|---|---|---|---|---|---|---|
| Gradle `unitTest` 선택 실행 | 160 | 160 | 0 | 0 | 0 | BUILD SUCCESSFUL 39초 |
| node 화면 스크립트 (별개) | 13 | 13 | 0 | 0 | 0 | 약 3.9초 |

- **선택 범위 확인:** 결과 XML은 위 5개 클래스의 `@Nested` 17개 파일뿐이에요. testcase의 classname도 이 5개 클래스뿐이라(26+9+40+23+62=160) 다른 기능 테스트가 섞이지 않았어요. `@Nested`는 모두 실행됐어요.
- **실행하지 않은 것:** 전체 `test`·전체 `unitTest`·`integrationTest`·`fullTest`·`build`·`check`, 커버리지 리포트 태스크. 기존 V1 테스트(`InsuranceIntegrationApiTest` 등)도 이번 요청 범위대로 다시 실행하지 않았어요.
- **같은 날 두 번 실패한 Gradle 시도:** 둘 다 테스트를 하나도 실행하지 않았어요.
  - 첫 시도는 **테스트 코드 컴파일 오류**로 실패했어요(5절 테스트 자체 오류).
  - 고친 뒤 `compileTestJava`만 따로 실행해 통과(25초)를 확인했어요.
  - 첫 시도는 13분이 걸렸어요. 같은 시각에 다른 Gradle 데몬이 떠 있었고, 사용자가 띄운 것으로 보이는 `FarmApiApplication`도 실행 중이었어요. 그 프로세스들은 건드리지 않았어요.

### 결과 위치 (docs로 복사하지 않음)

- XML: `farm-api/build/test-results/unitTest/TEST-com.wonday.farm.{insurance.dto.WorkerVerificationV2RequestTest,insurance.service.WorkerVerificationV2ServiceTest,insurance.api.InsuranceWorkerVerificationV2ApiTest,demo.insurance.DemoWorkerVerificationV2ApiTest,demo.insurance.WorkerVerificationDemoPageTest}$*.xml`
- HTML: `farm-api/build/reports/tests/unitTest/index.html`
- node 결과: 콘솔 출력만 있어요(파일 없음).

## 5. 실패·결함·관찰

### 최종 실행의 실패

없어요.

### 테스트 자체 오류 (운영 코드 결함 아님, 수정 완료)

1. **컴파일 오류.** API·demo 테스트의 응답 파싱 도우미 `body(MockHttpServletResponse)`가 정적 import한 요청 생성 `body()`와 이름이 겹쳤어요. 도우미를 `parse`로 바꿨어요.
2. **컴파일 오류.** 필드 타입 목록을 `List<Class<?>>`로 명시하지 않아 AssertJ 제네릭 추론이 실패했어요.
3. **작성 중 기대값 정정 (실행 전).**
   - DTO 테스트에서 U+3000 공백을 `@NotBlank` 거절로 기대하지 않도록 뺐어요. Validator 구현마다 다를 수 있기 때문이에요.
   - 대신 서비스와 실제 서비스 연결 컨트롤러 테스트에서 400을 확인해요.
   - 서비스 테스트의 "유니코드 공백만 있는 이름"에서 U+00A0을 U+2003으로 바꿨어요. Java `strip()`·`isBlank()`는 U+00A0을 공백으로 보지 않아요(관찰 O-3).
4. **문법 오류.** node 테스트의 매개변수 기본값 중괄호 오류를 고쳤어요.

### 운영 코드 결함

없어요.

### 관찰 (결함으로 분류하지 않음, 작성자 판단 사항)

- **O-1. JSON이 아닌 Content-Type은 415예요.**
  - 동작: V2 내부 API에 `application/x-www-form-urlencoded`로 요청하면 Spring 기본 415가 나가요. 서비스는 호출되지 않고 응답에 원문도 없어요.
  - 원인: `InsuranceErrorHandler`는 `HttpMediaTypeNotSupportedException`을 400으로 바꾸도록 선언돼 있어요. 하지만 consumes 불일치는 핸들러 선택 전에 생기므로 `basePackageClasses` 범위 advice가 적용되지 않아요. signup D-1과 같은 구조예요.
  - 판단: V2 문서는 Content-Type 오류의 상태를 정하지 않았어요. 그래서 현재 동작(415)을 테스트로 고정하고 관찰로만 남겨요. 문서에 415를 적거나 사용되지 않는 선언을 정리할지는 작성자가 정해야 해요.
  - 데모 API도 같은 이유로 415가 될 것으로 예상하지만 테스트하지는 않았어요. 화면은 항상 JSON으로 보내요.
- **O-2. 내부 API 오류 응답의 no-store는 필터가 붙여요.**
  - `InsuranceErrorHandler`의 오류 응답에는 `Cache-Control`이 없어요. no-store는 `ServiceTokenFilter`가 모든 응답에 미리 붙여요.
  - standalone 테스트에는 필터가 없으므로, 내부 API **오류 응답의 no-store는 이번에 검증하지 않았어요**. 성공 200의 no-store는 컨트롤러가 직접 붙이므로 확인했어요.
  - 데모 API는 컨트롤러 내부 처리기가 모든 오류에 no-store를 붙이는 것을 확인했어요.
- **O-3. NBSP(U+00A0)만 있는 이름은 400이 아니에요 (코드 판독, 테스트로 고정하지 않음).**
  - 서비스는 이 값을 빈 값으로 거절하지 않고 조회까지 보내요. 저장 이름과 다르면 0건이라 404가 될 것으로 보여요.
  - `String.strip()`·`isBlank()`가 U+00A0을 공백으로 보지 않기 때문이에요.
  - 문서의 "빈 값 거절"과 경계가 약간 다르지만 보안 영향은 없어 관찰로만 남겨요.

## 6. 항목별 결과

### A. 요청 DTO·JSON (DTO 23 + 컨트롤러 일부)

- **확인한 것:**
  - 프로젝트 의존성의 Jackson 3(`tools.jackson`) 기본 `JsonMapper`와 Hibernate Validator를 사용했어요.
  - 네 필드는 모두 필수예요. 누락·null·빈 값·공백은 각 필드 오류예요.
  - 100자는 허용하고 101자는 거절해요.
  - 네 필드 각각에 숫자·boolean·배열·객체·실수를 보내면 역직렬화가 실패해요.
  - 국적은 정확히 `DOMESTIC`·`FOREIGN`만 받아요. 소문자·앞뒤 공백·기타 값은 거절해요.
  - V1의 `residentRegistrationNumber`만 보내면 `identityNumber` 누락으로 거절해요.
  - 앞자리 0 문자열은 보존돼요.
- **컨트롤러에서 확인한 것:** 위 오류는 모두 400 `INVALID_REQUEST`예요. 서비스는 호출되지 않고 입력 원문은 응답에 없어요.
- **한계:** Spring Boot가 구성한 `JsonMapper` 설정(기능 플래그·모듈)은 적용하지 않았어요. standalone MockMvc의 기본 변환기를 사용했어요.

### B. V2 서비스 (62, Repository mock)

- **IdentityCrypto 준비:** 테스트 전용 가상 키(`IdentityTestKeys`)로 만든 실제 객체를 spy로 감쌌어요.
- **정상 확인:**
  - DOMESTIC은 `RESIDENT_REGISTRATION_NUMBER`, FOREIGN은 `FOREIGN_REGISTRATION_NUMBER`로 조회해요. 번호 종류는 서버가 정해요.
  - 같은 번호는 국적과 관계없이 HMAC이 같아요.
  - 응답은 요청값이 아니라 **조회된 행**의 이름·전화를 사용해요.
- **정규화:**
  - 이름은 앞뒤 공백(유니코드 포함)만 제거하고 대소문자·내부 공백은 보존해요.
  - 전화·번호는 공백·하이픈(유니코드 공백 포함)을 제거하고 앞자리 0을 보존해요.
  - 전화는 10·11자리를 허용해요.
  - 원문 100자 경계는 허용해요.
- **거절(34가지 입력):**
  - 모두 400이고, `blindIndex`를 호출하지 않으며 Repository와 상호작용하지 않아요.
  - 이름: null·빈 값·공백·유니코드 공백, 제어 문자 TAB·NUL·DEL·C1, 101자
  - 전화: 9·12자리, 문자, +82, 괄호, 전각·아라비아-인도 숫자
  - 국적: 소문자·공백·기타·17자
  - 번호: 12·14자리, 문자, 전각, en dash, 점
  - 요청 null도 400이에요.
- **조회 인자:**
  - 정규화한 이름·전화, 국적·종류 문자열, 64자리 hex HMAC만 전달해요.
  - 원문 번호(하이픈 포함·정규화 모두)는 인자에 없어요.
  - `verifyNoMoreInteractions`로 V2 조회 1회만 호출하는 것을 확인했어요.
- **V1 fallback 없음:** 성공·0건·키 오류 어느 경우에도 `findMatches`·`findDomesticByIdentityHmac`를 호출하지 않아요. 생성자·필드도 V2 Repository·`IdentityCrypto`뿐이라 조회 모드 설정이나 V1 서비스를 받지 않아요.
- **업무 오류 코드:**
  - 키 미설정·같은 키는 503이고 조회하지 않아요.
  - 0건은 404 `WORKER_IDENTITY_NOT_MATCHED`예요.
  - 2건은 409 `WORKER_IDENTITY_AMBIGUOUS`이고 workerId를 노출하지 않아요.
- **반환 행 처리:** 국적 null·해석 불가·소문자·요청과 다른 국적, workerId 0·음수는 503이에요. Repository 예외는 그대로 전파해요.
- **선언만 확인:** `@Transactional(readOnly = true)`
- **한계:** **Repository mock은 실제 SQL의 성공을 입증하지 않아요.** 다음은 이번 범위가 아니에요.
  - MySQL `regexp_replace`·`cast(... as binary)` 비교, `order by`·`limit 2`
  - 네이티브 쿼리 projection(`V2Match`) 변환
  - NULL 국적·암호문 없는 행의 제외

### C. 실제 V2 컨트롤러 (40, standalone MockMvc + InsuranceErrorHandler)

- **정상 200:**
  - `POST /api/internal/insurance/v2/worker-verifications`가 요청 원문을 서비스에 전달해요.
  - 응답은 200이고 `Cache-Control: no-store`, JSON 네 필드예요.
  - workerId는 정수 숫자이고 nationalityType은 문자열이에요.
  - V1 경로·하위 경로는 404, GET은 405이고 서비스는 호출되지 않아요.
- **실제 서비스 연결(Repository mock + 테스트 키):**
  - 정규화 값·종류·HMAC으로 V2 조회만 해요.
  - 0건·2건·키 미설정은 각각 404·409·503이에요.
  - 12자리 번호와 U+3000 이름은 400이고 조회하지 않아요.
- **오류 변환:**
  - 깨진 JSON·빈 본문·배열·null·문자열·숫자 본문, 필드 오류·누락, V1 형식 본문은 400이에요.
  - 서비스 404·409를 그대로 내보내요.
  - 키·`DataAccessResourceFailure`·`QueryTimeout`·`CannotAcquireLock`·`CannotCreateTransaction`·`TransactionTimedOut`은 503이에요.
  - `BadSqlGrammar`·`IllegalState`·NPE는 500이에요.
- **비노출:**
  - 모든 오류 응답은 `{code, message}`만 담아요.
  - 식별번호·HMAC·암호문·번호 종류·SQL·예외 메시지·jdbc·토큰 문자열이 응답에 없어요.
  - 예외 메시지에 번호·SQL을 일부러 넣고 확인했어요.
- **한계:** 이 테스트는 Security 필터를 통과하지 않아요. **Bearer 인증·401/403·체인 분리·세션 격리를 검증한 것이 아니에요.**

### D. demo 컨트롤러 (26, standalone MockMvc, principal 직접 주입)

- **선언:**
  - `@Profile("farm-demo")`, GET 화면·POST API 경로, consumes·produces, `@Transactional(readOnly = true)`
  - **선언 확인일 뿐**, 실제 프로필별 빈 등록·트랜잭션 동작은 검증하지 않았어요.
- **화면:** GET은 실제 HTML을 200·text/html·no-store로 반환해요.
- **의존성:** V2 서비스·`MemberRepository`뿐이에요. 대조 로직(Repository·IdentityCrypto)을 복제하지 않았어요.
- **본인 200:**
  - DB 역할을 다시 확인한 뒤 서비스를 호출하고, 결과가 본인 workerId일 때만 200·no-store예요.
  - 실제 V2 서비스 연결에서도 200이에요.
- **거절 403 `DEMO_ACCESS_DENIED`:**
  - principal이 없거나 세션 역할이 고용주면 거절하고, DB와 서비스를 호출하지 않아요.
  - DB 계정 삭제, DB 역할 EMPLOYER·ADMIN·소문자·null이면 거절하고 서비스를 호출하지 않아요.
- **다른 근로자 404:**
  - 서비스가 다른 근로자를 반환하면 404 `WORKER_IDENTITY_NOT_MATCHED`예요. 실제 서비스 연결에서 다른 근로자 행이 정확히 일치해도 같아요.
  - 상대 ID·이름·전화는 응답에 없어요.
  - 본문은 "0건 404"와 같아서 존재 여부를 구분할 수 없어요.
- **오류 응답(모두 no-store, 비노출):**
  - 입력 오류는 400이고 서비스를 호출하지 않아요. 화면의 12자리 예시는 실제 서비스에서 400이고 조회하지 않아요.
  - 서비스 400·404·409·503은 그대로, DB 일시 장애는 503, SQL 문법·기타 예외는 500이에요.
  - 회원 재확인 단계의 DB 장애는 503, 기타 DB 오류는 500이고 서비스를 호출하지 않아요.
  - 성공·오류 응답에 식별번호·Bearer·csrf 문자열이 없어요.
- **한계:** principal을 SecurityContext에 직접 넣었어요. **실제 로그인·세션·CSRF·`hasRole("WORKER")` 필터 차단을 검증한 것이 아니에요.**

### E. demo 화면

**Java 정적 검사 (9)** — 브라우저 동작을 검증한 것은 아니에요.
- 이름·전화(tel)·국적 select(DOMESTIC/FOREIGN 2개)·식별번호(password, autocomplete off) 입력과 세 버튼이 있어요. 처음에는 fieldset이 비활성이에요.
- 로그인 화면에 V2 화면 링크가 있어요.
- 스크립트는 innerHTML 계열·eval을 쓰지 않고 결과를 textContent로 넣어요.
- 저장소·cookie·console·history·location·URLSearchParams·Authorization·Bearer·`/api/internal/`을 쓰지 않아요.
- fetch는 3개(me·csrf·POST)이고, CSRF 조회가 POST보다 앞에 있어요. POST 옵션 구조와 표시 필드 목록도 확인했어요.

**node 가짜 DOM 실행 (13, 위 160과 별개)** — 실제 스크립트 원문을 실행했어요.
- **로그인 상태:**
  - 근로자면 입력을 활성화하고 이름을 채워요.
  - 고용주·401·네트워크 오류면 입력을 비활성화하고, 새로고침 버튼은 복구해요.
- **정상 전송:**
  - CSRF를 조회한 뒤 받은 헤더 이름·토큰으로 POST해요. same-origin, no-store, JSON이고 Authorization 헤더는 없어요.
  - 입력값을 그대로 보내요(하이픈 포함 전화·번호).
- **불일치 예시:** 전송 전화의 마지막 숫자만 +1이에요(9는 0). 이름·번호와 폼 값은 그대로예요. 전화 형식이 잘못되면 요청하지 않고 안내만 해요.
- **입력 오류 예시:** 번호 `000000000000`(12자리)을 보내고 폼의 원래 번호는 유지해요.
- **결과 표시:**
  - 성공은 4필드만 보여 줘요. 서버가 식별번호·HMAC 같은 여분 필드를 보내도 숨겨요.
  - 404·409·503은 code·message만, JSON이 아닌 응답은 `HTTP_500`과 고정 안내를 보여 줘요.
- **401·403 이후:** 근로자 상태를 해제하고 입력을 비활성화하며 재전송하지 않아요. 기타 오류 뒤에는 입력과 busy 상태를 복구해요.
- **CSRF 실패:** 조회 실패·형식 오류·네트워크 오류면 POST하지 않아요.
- **중복 전송:** 응답 전에 다시 눌러도 POST는 1회만 나가요. 요청 중에는 입력·새로고침이 비활성이고, 그사이 세션 재확인도 하지 않아요.
- **비노출:** 결과·상태·계정 표시에 식별번호·12자리 예시·CSRF 토큰과 헤더 이름·`identityNumber`가 없어요. `localStorage`·`sessionStorage`·`console`·`history`·`location` 접근은 0회이고 URL 쿼리도 없어요.
- **페이지 이탈·복귀:** pagehide에서 식별번호·결과를 지우고, pageshow에서 로그인 상태를 다시 확인하며 번호를 지워요.
- **이벤트 연결:** submit의 기본 동작을 막고, 두 예시 버튼과 새로고침 버튼이 각 모드에 연결돼 있어요.
- **한계:** 가짜 DOM이므로 다음은 검증하지 않았어요. 실제 브라우저 자동화·실제 fetch도 이번 범위가 아니에요.
  - 실제 브라우저 렌더링, `reportValidity`의 실제 HTML 검증(항상 true로 둠)
  - 실제 bfcache 동작, 접근성, 실제 서버 CSRF 처리

### F. 보안 설정 (선언만)

- `InsuranceSecurityConfig`: V2 POST 경로가 V1과 같은 `hasAuthority("INSURANCE_SERVICE")` 규칙에 있어요. `securityMatcher("/api/internal/insurance/**")`, `denyAll`, `ServiceTokenFilter` 연결을 소스에서 확인했어요.
- `SecurityConfig`: farm-demo 분기 안에 GET 화면 `permitAll`과 POST demo API `hasRole("WORKER")`가 있어요. 농업 체인에 CSRF 비활성화나 `ignoringRequestMatchers`는 없어요. 데모 컨트롤러도 CSRF·토큰을 다루지 않아요.
- **한계:** 선언·소스 문자열 검사만 했어요. **실제 필터 체인에서 인증 우회 방지·CSRF 차단이 실행된다는 것은 검증하지 않았어요.** 후속 통합 검증 대상이에요.

## 7. 운영 코드·설정 보존

- 실행 전후로 `src/main`·`build.gradle`·`settings.gradle`·`gradle/`·`.github` 파일 123개의 SHA-256이 같아요.
- git 추적 diff(`git diff HEAD`)와 staged diff의 해시도 같아요.
- 추가된 것은 테스트 파일 6개(도우미 1개 포함)와 이 보고서 폴더(보고서, node 테스트)뿐이에요.
- `.env`·실제 키·서비스 토큰은 읽거나 출력하지 않았어요. 테스트에는 가상 이름·번호와 테스트 전용 키(`IdentityTestKeys`)만 사용했어요.
- 서버·DB·마이그레이션·이관 도구는 실행하지 않았어요. 커밋·푸시·PR도 하지 않았어요.
- 이미 실행 중이던 `FarmApiApplication`과 다른 Gradle 데몬은 건드리지 않았어요.

## 8. 사용자 수동 확인 (별도 기록, 자동 테스트 수에 포함하지 않음)

사용자가 farm-demo에서 직접 확인한 결과예요. 이번 자동 테스트로 재현하거나 입증한 것은 아니에요.
- 근로자 확인 성공: 200
- 정보 불일치: 404
- 입력 오류: 400

## 9. 이번에 검증하지 않은 범위

- **실제 SQL:** MySQL에서 V2 쿼리 실행, regexp·binary 비교, 정렬·`limit 2`, projection, NULL 국적·암호문 없는 행 제외, V8 UNIQUE와의 관계
- **Security:** `ServiceTokenFilter`·`InsuranceSecurityConfig` 실제 체인(Bearer 인증, 401/403, 오류 응답 no-store, 체인 분리), 농업 체인의 세션·CSRF 차단·`hasRole("WORKER")`·익명 401
- **프로필 활성화:** farm-demo가 아닐 때 데모 빈이 등록되지 않는지, farm-demo에서 등록되는지
- **트랜잭션:** `readOnly` 트랜잭션의 실제 적용
- **Spring Boot 구성:** 실제 `JsonMapper` 설정, 실제 Bean Validation 메시지 처리
- **브라우저:** 실제 렌더링·이벤트·fetch·CSRF 쿠키, bfcache
- **보험 서버 연동:** 실제 보험 클라이언트 호출, `FARM_WORKER_VERIFICATION_V2_ENABLED` 전환
- **기존 V1 회귀:** 기존 V1 테스트는 이번에 다시 실행하지 않았어요. V1 서비스·컨트롤러 파일은 변경되지 않았고, Repository에는 메서드만 추가됐어요.

## 10. PR 본문용 짧은 검증 요약

```
보험 V2 근로자 확인 API·farm-demo 독립 단위 검증 (worker-verification-v2, 기준 dac094e + 작업 트리)
- Gradle unitTest 선택 실행 160개 전부 통과 (DTO 23 / 서비스 62 / V2 API 40 / demo API 26 / 화면·설정 정적 9)
- 화면 스크립트 가짜 DOM 실행 node 테스트 13개 통과 (별도 실행, 합산 안 함)
- 확인: 네 필드 문자열 강제·필수·길이·국적, 정규화·앞자리 0, 국적별 번호 종류 서버 결정,
  정규화 값+HMAC만 조회 인자(원문 번호 없음), 잘못된 입력·키 오류 시 HMAC·조회 없음,
  0/1/2건 → 404/200/409, 해석 불가 행 503, V1 조회·fallback 없음,
  200 no-store·4필드, 오류 비노출, demo 본인만 성공·다른 근로자 404·DB 역할 재확인·no-store
- 관찰: JSON 아닌 Content-Type은 Spring 기본 415(InsuranceErrorHandler의 400 선언 미적용),
  내부 API 오류 응답 no-store는 필터 담당(단위 미검증), NBSP만 있는 이름은 400이 아닌 404
- 운영 코드 결함 없음. Repository mock이므로 실제 SQL 미검증. Security 필터·CSRF·프로필 활성화·
  트랜잭션·브라우저·보험 서버 연동은 후속 통합 검증 대상
- 운영 코드·설정 변경 없음(해시 확인), 커밋 없음
```
