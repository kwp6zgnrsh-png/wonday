# 급여 계좌 관리 API · farm-demo 독립 단위 검증 (2026-11-26)

## 1. 요약

- **최종 Gradle 선택 실행:** 253개를 실행해 253개 통과했어요. 실패·오류·건너뜀은 0개예요.
  - 신규 테스트: 146개
  - 직접 영향을 받는 기존 테스트: 107개
- **첫 Gradle 실행:** 253개 중 252개 통과, 1개 실패했어요. 실패한 것은 기존 테스트 `SalaryAccountCryptoTest$Entity.noPlainNumber`예요.
  - 이 테스트는 signup 시점의 "암호문 getter 없음" 가정을 담고 있었어요.
  - 이번에 마스킹을 위해 문서화된 내부용 `ciphertextForDecryption()`이 추가됐어요.
  - **운영 코드 결함이 아니라 설계 변경에 따른 기존 테스트 갱신 대상**으로 판단했어요. 테스트를 좁게 수정한 뒤 다시 실행했어요(5절).
- **node 화면 테스트:** 화면 스크립트 원문을 가짜 DOM과 가짜 서버로 실행해 16개 통과했어요. Gradle과 **별개의 실행**이라 합산하지 않았어요.
- **결함:** 운영 코드 결함은 없어요. 관찰 사항은 2건이에요(5절).
- **보존:** 운영 코드·화면·설정·마이그레이션·Gradle·CI는 수정하지 않았어요. 실행 전후 해시가 같아요.
- **합산하지 않은 것:** 사용자 수동 확인(8절), signup·보험 V2의 기존 검증 결과

## 2. 작업 위치

| 항목 | 값 |
|---|---|
| 경로 | `C:\Users\HCB\Desktop\wonday` (`farm-api`에서 실행) |
| 브랜치 | `salary-account` (브랜치 변경·병합·리베이스 없음) |
| 기준 커밋 | `432477f` (signup 커밋). 급여 계좌 관리 구현은 커밋되지 않은 작업 트리 변경 |
| AGENTS.md | 저장소 루트·`farm-api` 모두 없음 |
| `.codex` worktree | 사용하지 않음 |

검증 대상 작업 트리 변경은 다음과 같아요.
- **수정:** `SecurityConfig`, `AuthDemoPage`, `Member`, `MemberRepository`, `SalaryAccountCrypto`, `MemberSalaryAccount`, `farm-login.html`, README
- **수정(작성자의 기존 테스트):** `FlywayMigrationTests` — 이번에 손대지도, 실행하지도 않았어요.
- **신규:** `salary/api`·`dto`·`exception`·`serialization`·`service`, `farm-salary-account.html`, `salary-account-api.md`, `V10__add_salary_account_version.sql`

`git status` 기준 `db/migration`에서 바뀐 것은 신규 V10뿐이에요. **V1~V9 파일은 수정되지 않았어요.**

## 3. 추가·변경한 테스트 파일

### 신규 (146)

| 파일 (`farm-api/src/test/java/com/wonday/farm/` 기준) | 수 | 목적 |
|---|---|---|
| `salary/testsupport/SalaryAccountTestData.java` | 도우미 | 테스트 전용 키(0x66·0x77 반복), 회원 생성, 운영 코드와 별개로 구현한 AES-GCM 암호화 |
| `salary/dto/SalaryAccountRequestJsonTest` | 17 | JSON 문자열·정수 강제, version 경계, 필수·길이, toString·미등록 응답 형태 |
| `salary/service/SalaryAccountServiceTest` | 46 | 본인·역할 확인, 조회, 등록·교체, 버전 409, 해제 정책, 생애주기, 키·저장 예외 |
| `salary/crypto/SalaryAccountMaskingTest` | 22 | 마스킹 경계, 가입 형식 호환, AAD·변조·키 오류, 엔티티 교체, 회원 버전, 선언·V10 |
| `salary/api/SalaryAccountApiTest` | 58 | GET·PUT 200, DELETE 204, principal ID, 400·403·409·503·500·415, 비노출 |
| `demo/SalaryAccountDemoPageTest` | 3 | 화면 경로·프로필 선언·리소스, 로그인 링크, SecurityConfig **선언** |

- 같은 내용을 반복하는 테스트나 선언 검사만으로 수를 늘리지 않으려고 했어요.
- 선언·정적 검사는 `SalaryAccountMaskingTest`의 1개와 `SalaryAccountDemoPageTest`의 3개로 묶었어요.
- 입력 형식 규칙(은행 3자리·숫자 1~30자리·하이픈 100자 경계·전각/en dash 거절)은 기존 `SalaryAccountValidatorTest`(33)가 이미 다뤄요. 그래서 신규 테스트에서는 서비스·컨트롤러 연결 수준에서만 대표 사례를 확인했어요.

### 기존 영향 테스트 (107, 이번 변경과 직접 관련된 클래스만)

| 클래스 | 수 | 선택 이유 | 변경 |
|---|---|---|---|
| `salary.crypto.SalaryAccountCryptoTest` | 17 | `SalaryAccountCrypto`·`MemberSalaryAccount` 수정 | **1개 테스트 수정**(5절) |
| `salary.validation.SalaryAccountValidatorTest` | 33 | PUT 정규화의 핵심(코드는 변경 없음) | 없음 |
| `member.signup.service.SignupServiceTest` | 43 | `Member`·`MemberSalaryAccount` 생성자·`MemberRepository` 변경 | 없음 |
| `demo.signup.DemoSignupAccountApiTest` | 5 | `MemberSalaryAccount` 사용 | 없음 |
| `demo.AuthDemoPageTest` | 1 | `AuthDemoPage` 수정 | 없음 |
| `demo.SignupDemoPageTest` | 3 | `AuthDemoPage`·`farm-login.html` 수정 | 없음 |
| `demo.JobLocationDemoPageTest` | 5 | `AuthDemoPage` 리소스 확인 | 없음 |

### node 화면 테스트 (별도)

- 파일: `farm-api/docs/verification/2026-11-26-salary-account/salary-account-page.test.mjs` (16개)
- 인라인 스크립트 **원문**을 `node:vm`에서 실행해요. 운영 스크립트 로직을 테스트에 복제하지 않았어요.
- `document`·`fetch`·`window`·저장소는 최소한의 가짜 객체예요.
- 서버는 문서의 버전 정책(409, 미등록 DELETE 204, 버전 증가)을 흉내 낸 가짜이고, 여러 창이 같은 서버를 공유할 수 있게 만들었어요.

## 4. 실행 명령·범위·시간

JDK 경로: `JAVA_HOME=C:\Users\HCB\Desktop\woori-workspace\00.sw\jdk-21.0.12.1+1`

```
# farm-api
./gradlew.bat --no-daemon unitTest \
  --tests 'com.wonday.farm.salary.dto.SalaryAccountRequestJsonTest*' \
  --tests 'com.wonday.farm.salary.service.SalaryAccountServiceTest*' \
  --tests 'com.wonday.farm.salary.crypto.SalaryAccountMaskingTest*' \
  --tests 'com.wonday.farm.salary.api.SalaryAccountApiTest*' \
  --tests 'com.wonday.farm.demo.SalaryAccountDemoPageTest*' \
  --tests 'com.wonday.farm.salary.crypto.SalaryAccountCryptoTest*' \
  --tests 'com.wonday.farm.salary.validation.SalaryAccountValidatorTest*' \
  --tests 'com.wonday.farm.member.signup.service.SignupServiceTest*' \
  --tests 'com.wonday.farm.demo.signup.DemoSignupAccountApiTest*' \
  --tests 'com.wonday.farm.demo.AuthDemoPageTest*' \
  --tests 'com.wonday.farm.demo.SignupDemoPageTest*' \
  --tests 'com.wonday.farm.demo.JobLocationDemoPageTest*'

# 저장소 루트
node --test farm-api/docs/verification/2026-11-26-salary-account/salary-account-page.test.mjs
```

| 실행 | 실행 | 통과 | 실패 | 오류 | 건너뜀 | 시간 |
|---|---|---|---|---|---|---|
| Gradle 1차 (기존 테스트 수정 전) | 253 | 252 | 1 | 0 | 0 | BUILD FAILED 1분 14초 |
| **Gradle 최종 (같은 선택 범위)** | **253** | **253** | 0 | 0 | 0 | BUILD SUCCESSFUL 1분 14초 |
| node 화면 스크립트 (별개) | 16 | 16 | 0 | 0 | 0 | 약 0.3초 |

최종 253개 중 신규는 146개, 기존 영향은 107개예요. 두 Gradle 실행은 같은 범위를 다시 실행한 것이라 합산하지 않았어요.

- **선택 범위 확인:** 최종 결과 XML은 24개(위 12개 클래스와 그 `@Nested`)예요.
  - testcase의 classname은 12개 클래스뿐이에요. `FlywayMigrationTests`나 다른 기능 테스트는 섞이지 않았어요.
  - 클래스별 수: 3+58+17+22+17+46 / 1+5+3+5+43+33 = 253
  - `@Nested`는 모두 실행됐어요(예: `SalaryAccountApiTest` Ok·BadRequest·Errors, `SalaryAccountServiceTest` Find·Forbidden·Replace·Remove).
- **실행하지 않은 것:** 전체 `test`·전체 `unitTest`·`integrationTest`·`fullTest`·`build`·`check`, 커버리지 리포트, `FlywayMigrationTests`

### 결과 위치 (docs로 복사하지 않음)

- XML: `farm-api/build/test-results/unitTest/TEST-com.wonday.farm.*.xml` (최종 실행 24개)
- HTML: `farm-api/build/reports/tests/unitTest/index.html`
- node 결과는 콘솔 출력만 있어요.

## 5. 실패·결함·관찰

### 1차 실행의 실패 (기존 테스트, 설계 변경 반영 필요)

- **테스트:** `SalaryAccountCryptoTest$Entity.noPlainNumber` ("…암호문 getter도 없다")
- **기대값:** `MemberSalaryAccount`에 이름에 `cipher`·`number`가 들어간 메서드가 없음
- **실제값:** `ciphertextForDecryption` 메서드가 있음
- **원인:**
  - 이번 기능은 조회 응답의 마스킹을 위해 서비스가 암호문을 읽어야 해요.
  - 엔티티 주석과 `salary-account-api.md`에 내부 마스킹 전용으로 명시돼 있어요.
  - 이 테스트는 signup 검증 때 제가 작성했고, 당시 "복호화 API 없음" 정책을 반영하고 있었어요.
- **처리:**
  - 기대값을 "암호문·번호 관련 메서드는 **정확히** `ciphertextForDecryption` 하나"로 바꿨어요.
  - 원문 번호 필드 금지와 다른 접근 메서드 금지는 그대로 유지했어요.
  - 변경 이유는 테스트 주석에 남겼어요.
  - 결함을 숨기려고 기대값을 완화한 것이 아니라, 문서화된 설계 변경을 반영한 거예요.
  - 검토 후 이 판단에 동의하지 않으면, 수정을 되돌리고 운영 코드의 접근 방식을 바꾸는 쪽으로 다시 논의해야 해요.

### 테스트 자체 오류

- 최종 범위에서는 없어요.
- node 테스트는 작성 후 첫 실행에서 통과했어요.

### 운영 코드 결함

없어요.

### 관찰 (결함으로 분류하지 않음)

- **O-1. 저장 실패 시 회원 버전이 메모리에서 먼저 증가해요.**
  - 동작: `replace`는 `member.advanceSalaryAccountVersion()`을 `saveAndFlush`보다 먼저 호출해요. 그래서 저장 예외가 나면 메모리의 회원 버전은 이미 올라가 있어요.
  - 영향: 실제로는 트랜잭션 롤백으로 DB 값이 원복될 것으로 설계돼 있어요.
  - 이번 단위 테스트에서 확인한 것은 예외가 그대로 전파되고(성공 응답이 아님) `members.flush()`가 호출되지 않는다는 데까지예요. **실제 롤백은 검증하지 않았어요.**
- **O-2. 서비스는 입력 검증을 권한 확인보다 먼저 해요.**
  - 동작: 잘못된 PUT은 DB 역할 확인 전에 400이 돼요. 예를 들어 DB 역할이 바뀐 회원이 잘못된 입력을 보내면 403이 아니라 400이에요.
  - 범위: 컨트롤러에서 principal 역할은 먼저 확인하므로, 이 순서는 세션 역할은 근로자인데 DB 역할이 바뀐 경우에만 해당해요.
  - 문서에 순서 규정은 없어요. 판단은 작성자 몫이에요.

## 6. 항목별 결과

### A. 요청·입력 검증 (DTO 17 + 컨트롤러 BadRequest 38 + 서비스 invalid 15)

**DTO (Jackson 3 기본 `JsonMapper` + Hibernate Validator)**
- 문자열 세 필드에 숫자·boolean·배열·객체·실수를 보내면 역직렬화가 실패해요.
- `version` 허용: 0과 `Long.MAX_VALUE`
- `version` 역직렬화 실패: `1.0`·`1.5`·`1e2`·`"1"`·`true`·배열·객체, Long 범위 초과(양·음)
- `version` 검증 오류: 음수·null·누락
- 세 필드의 누락·null·빈 값·공백은 오류예요. 은행 4자·번호 101자·예금주 101자도 오류이고, 100자는 허용해요.

**서비스 (`invalid` 15가지)**
- 대상: 요청·version null, 음수, 은행 2자리·문자·null, **마스킹 문자열 `********0400`**, 31자리, 하이픈만, 공백 포함, 전각 숫자, 빈 값, 예금주 공백만·101자·null
- 결과: 모두 400이고, 회원 잠금 조회·암호화·저장을 하지 않아요(`verifyNoInteractions`).
- 경계 허용: 1자리, 30자리, 하이픈 포함 100자, 예금주 100자
- 정규화: 하이픈을 제거하고 앞자리 0을 보존해요(`0000-0000-1` → `000000001` → `*****0001`). 예금주는 앞뒤 공백만 제거해요.

**DELETE version**
- 400: 누락, 빈 값, `-1`·`1.0`·`1e2`·`abc`·`+1`·앞뒤 공백·`0x1`·전각 숫자, 20자리, 19자리 범위 초과, 중복 파라미터(다른 값·같은 값)
- 허용: `0`, Long 최대값, `007`(→7)

### B. 서비스와 버전 (46, Repository mock, 순차 호출)

**본인·역할**
- 세 메서드 모두 principal로 받은 회원 ID로만 회원·계좌를 조회해요.
- PUT·DELETE는 `findByIdForSalaryAccountUpdate`(잠금 선언 메서드)를, GET은 `findById`를 사용해요.
- 다음은 조회·등록·해제 모두 403이고, 계좌 저장소에 접근하지 않아요.
  - DB 역할 EMPLOYER·ADMIN·소문자·null
  - DB 회원 없음
  - 회원 ID 0 이하

**조회**
- 미등록: `registered=false`, null 필드, 회원의 현재 version(7)을 돌려줘요. 복호화하지 않고 계좌 키 없이도 동작해요.
- 등록: 은행·`********0400`·예금주·version을 돌려줘요.
- 다른 회원의 암호문이나 키 미설정이면 정상 응답이 아니라 암호화 예외(503용)예요.

**등록·교체**
- 신규 등록:
  - 회원·은행 코드에 결합된 암호문을 만들어요. signup의 독립 복호화 도우미로 `100200300400`이 복원돼요.
  - `createdAt`·`updatedAt`은 UTC이고 마이크로초 단위로 잘려요(`…03.123456789Z` → `…03.123456`). 시계 시간대는 Asia/Seoul로 둬서 UTC 변환을 확인했어요.
  - version이 0에서 1로 올라요.
  - 호출 순서는 잠금 조회 → `saveAndFlush` → `members.flush`예요.
- 교체: 같은 엔티티를 갱신해요. `createdAt`은 보존하고 `updatedAt`은 새 시각이에요. 새 은행 코드로 다시 결합되고 version이 올라요.
- 같은 값을 다시 보내도 매번 version이 올라요(0→1→2→3).
- 버전 불일치(오래된 4·0, 미래 6)는 409예요. 이때 암호화·저장·flush가 없고, 회원 버전과 기존 계좌 값은 그대로예요.
- 키 미설정은 503용 예외이고 저장·버전 변경이 없어요. version이 `Long.MAX_VALUE`이면 503이고 저장하지 않아요. 저장 예외는 그대로 전파돼요.

**해제**
- 등록 + 버전 일치: 삭제하고 version을 올리고 flush해요. **`SalaryAccountCrypto`와 상호작용이 전혀 없고**, 계좌 키 없이도 해제돼요.
- 등록 + 불일치(2·0·4): 409이고, 삭제·버전 변경이 없어요.
- 현재 미등록: 요청 버전이 0·2·3·99 중 무엇이든 성공이고, 반복 해제해도 version이 3으로 유지돼요. 삭제·flush는 없어요.
- 음수는 400이고 회원을 조회하지 않아요. 삭제 예외는 그대로 전파돼요.
- 생애주기: 등록(1) → 해제(2) → 미등록 조회 v2 → 오래된 PUT(0) 409 → 재등록(3) → 오래된 해제(1) 409 → 조회 `*2345`. **버전이 0으로 돌아가지 않아요.**
- **한계:** 순차 호출 확인이에요. **실제 DB 동시성, PESSIMISTIC_WRITE 잠금, REPEATABLE_READ 스냅샷, 롤백 검증이 아니에요.**

### C. 암호화·마스킹·엔티티 (22, 테스트 전용 가상 키)

- **마스킹 경계:**
  - `1`→`*`, `0`→`*`, `1234`→`****`, `0000`→`****`
  - `12345`→`*2345`, `01234`→`*1234`
  - 12자리→`********0400`, `000000000001`→`********0001`
  - 30자리→별표 26개 + `7890`
  - 길이는 숫자 개수와 같고, 앞부분 숫자는 노출되지 않아요.
- **가입 형식 호환:** 운영 코드와 **별개로 구현한** AES-GCM 암호문(`v1:`, nonce 12, 같은 AAD)을 운영 `maskedAccountNumber`가 정상 마스킹해요.
- **거절(모두 같은 예외):**
  - 다른 회원 ID·다른 은행 코드·다른 키
  - 1바이트 변조, 잘린 암호문, `v2:` 접두사, Base64 오류, `v1:`만, 257자
  - null, 회원 ID 0, 은행 코드 2자리
  - 같은 키·AAD로 만든 비정상 평문(하이픈 포함, 31자리, 0자리, 전각 숫자)
  - 키 미설정·공백·Base64 오류·3바이트 키
- **예외 메시지:** 원인 체인이 없고, 메시지에 번호·뒤 4자리·암호문·키가 없어요.
- **엔티티 교체:**
  - 은행·암호문·예금주·`updatedAt`이 바뀌고 `memberId`·`createdAt`은 보존돼요. toString은 가려져요.
  - 잘못된 교체 값 5종은 거절하고 기존 상태를 그대로 유지해요.
- **회원 버전:** 0에서 시작해 1씩 올라요. `Long.MAX_VALUE`에서는 `ArithmeticException`이 나고 값은 바뀌지 않아요.
- **선언·정적 확인 (실제 동작 검증 아님):**
  - `salary_account_version` 컬럼 not null
  - `findByIdForSalaryAccountUpdate`의 `@Lock(PESSIMISTIC_WRITE)`
  - `findMine`의 readOnly + `REPEATABLE_READ`, `replace`·`remove`의 쓰기 트랜잭션
  - V10 SQL에 `ADD COLUMN salary_account_version BIGINT NOT NULL DEFAULT 0`과 `CHECK (>= 0)`이 있고 DROP·UPDATE·DELETE는 없음

### D. 컨트롤러·오류 응답 (58, standalone MockMvc + SalaryAccountErrorHandler)

**정상 응답**
- GET 등록: 200·no-store·다섯 필드·마스킹이에요. 쿼리의 `memberId=999`는 무시하고 principal ID(101)를 써요.
- GET 미등록: 세 필드가 JSON null이고 version은 4예요.
- PUT 200·no-store: 요청 DTO 원문과 principal ID를 서비스에 전달하고, 새 version을 응답해요.
- DELETE 204: 빈 본문·no-store이고, 쿼리 version과 principal ID를 전달해요(`memberId` 쿼리는 무시).
- 실제 서비스 연결(Repository mock + 테스트 키): 미등록 GET → PUT 200 → GET v1 → 오래된 PUT 409 → 마스킹 번호 PUT 400 → DELETE 204 → 미등록 상태의 오래된 DELETE 204 → GET v2

**오류 응답**
- 400: 깨진 JSON·빈 본문·배열·null·문자열·숫자 본문, 필드 형식·필수·길이·version 오류 17종, 네 필드 누락, `1.0`·`1e0`, DELETE 오류 14종. 모두 서비스를 호출하지 않아요.
- 403 `WORKER_ROLE_REQUIRED`:
  - principal 없음·고용주·회원 ID 0이면 GET·PUT·DELETE 모두 403이고 서비스를 호출하지 않아요.
  - DELETE는 권한을 먼저 확인하므로, 고용주가 잘못된 version을 보내도 403이에요.
  - 서비스 403(DB 역할 변경)도 그대로 변환해요.
- 409 `SALARY_ACCOUNT_VERSION_CONFLICT`: PUT·DELETE
- 503 `SERVICE_UNAVAILABLE`: 계좌 암호화 예외, 업무 503, `DataAccessResourceFailure`·`QueryTimeout`·`CannotAcquireLock`·`CannotCreateTransaction`·`TransactionTimedOut` (GET·PUT·DELETE)
- 500 `INTERNAL_SERVER_ERROR`: `BadSqlGrammar`·`IllegalState`·`ArithmeticException`
- 415: JSON이 아닌 Content-Type의 PUT은 Spring 기본 415예요. 문서대로 code/message 형식을 보장하지 않고, 서비스를 호출하지 않으며 원문도 없어요.
- 모든 오류는 no-store이고 `{code, message}`만 담아요. 예외 메시지에 넣어 둔 계좌번호·`v1:`·키·SQL·jdbc·Exception 문자열은 응답에 없어요.

**한계:** principal을 직접 주입했어요. **실제 로그인·세션·CSRF 차단·`hasRole("WORKER")`·필터의 403 응답을 검증한 것이 아니에요.**

### E. farm-demo

**Java 정적 검사 (3)**
- `/demo/salary-account`의 `@Profile("farm-demo")` 선언과 실제 HTML을 200·no-store로 반환하는 것을 확인했어요. 실제 프로필 등록은 검증하지 않았어요.
- 화면이 실제 `/api/me/salary-account`를 쓰고 데모 전용 API·저장소·innerHTML·console·Authorization을 쓰지 않는 것을 확인했어요.
- 로그인 화면의 링크는 기본 숨김이고 근로자일 때만 표시하는 구조예요.
- SecurityConfig: GET·PUT·DELETE `hasRole("WORKER")`, farm-demo 분기 안의 화면 GET `permitAll`이 선언돼 있고, CSRF 비활성·예외 경로는 없어요. **선언만 확인했어요.**

**node 가짜 DOM 실행 (16, 위 Gradle 253과 별개)** — 실제 스크립트 원문을 실행했어요.
- **조회:** 로그인 확인 → GET → 계정 재확인 순서예요.
  - 마스킹 번호·버전·등록 상태를 표시하고, 은행·예금주만 채워요. **번호 칸에는 마스킹 번호를 넣지 않아요.**
  - 실제 `/api/me/salary-account`만 호출해요.
- **입력 활성화:** 비로그인·고용주면 입력·해제가 비활성이고 계좌를 조회하지 않아요. 최신 조회 버튼은 사용할 수 있어요.
- **은행 선택:** 은행을 고르기 전에는 번호 입력이 잠겨 있어요. 은행을 고르면 열리고, 기타 코드는 3자리를 입력한 뒤 열려요.
- **등록:**
  - 순서는 CSRF → 계정 → PUT → 계정이에요. 저장 직전에 계좌를 다시 조회해 버전을 바꾸지 않아요.
  - CSRF 헤더, JSON, 조회 당시 version(4)을 보내요.
  - 성공하면 새 version(5)과 마스킹 번호를 보여 주고 번호 칸을 비워요.
- **409 (두 창이 가짜 서버 공유):**
  - B의 PUT은 409예요. 자동 재시도 없이 저장·해제를 막고 오래된 정보 안내를 표시하며, 버전은 그대로 둬요.
  - 막힌 동안 다시 눌러도 전송하지 않아요.
  - 최신 조회 후에는 version 2로 다시 열리고 입력값은 유지돼요. 그 뒤 저장하면 200이에요.
- **포커스만 이동:** window에는 pagehide·pageshow 처리기만 있고 document에는 처리기가 없어요. 다른 창이 저장해도 버전이 자동으로 바뀌지 않아요.
- **해제:**
  - 순서는 CSRF → 계정 → `DELETE ?version=6`(본문 없음) → 계정 → 후속 GET이에요.
  - 결과는 미등록·v7이고 "후속 GET 확인"을 표시해요.
  - 후속 GET이 HTTP 500이거나 네트워크 오류면 "204 성공 · 후속 조회 실패/확인 못함"으로 **구분해 표시**하고 쓰기를 막아요.
  - 등록 상태의 오래된 DELETE는 409로 막혀요. 다른 창이 이미 해제했으면 오래된 version이어도 204이고 다시 조회해요.
- **중복 전송:** 대기 중의 저장·해제·조회는 무시해요(PUT 1회). 네트워크 오류 뒤에는 "자동 재시도하지 않습니다"를 표시하고 최신 조회를 요구해요. busy 상태는 해제돼요.
- **400 뒤:** 최신 조회 없이 다시 저장할 수 있고 같은 version을 보내요. 서버 메시지 원문 대신 고정 문구를 표시해요.
- **계정 변경:** CSRF 뒤 다른 회원이면 PUT을 보내지 않고 번호·예금주·조회 결과·버전을 지워요.
- **페이지 이탈:** 번호·결과·버전을 지워요. 이탈 뒤에 도착한 응답은 반영하지 않아요.
- **형식:**
  - 신한 3-3-6과 우리 4-3-6만 하이픈을 자동으로 넣어요.
  - 하나(대표 형식 미확인)와 자동 표시를 끈 경우에는 그대로 둬요.
  - 잘못된 문자·공백·전각·31자리는 **지우거나 자르지 않고** 유효성 메시지를 표시하며 저장하지 않아요.
- **마스킹 응답만 표시:** 서버가 마스킹되지 않은 번호를 보내면 표시·보관하지 않고 다시 조회하라고 안내해요.
- **비노출:** 전체 흐름에서 화면 표시에 계좌 원문·하이픈 형식·CSRF 토큰과 헤더 이름·`accountNumber` 키가 없어요. console·localStorage·sessionStorage·history·location 접근은 0회이고, URL 쿼리는 `version=숫자`뿐이에요.
- **한계:** 가짜 DOM과 가짜 서버이므로 다음은 검증하지 않았어요. **실제 브라우저 동작 검증이 아니에요.**
  - 실제 렌더링과 실제 HTML 폼 검증(`reportValidity`는 단순 모사)
  - 커서 위치·IME 조합·`beforeinput`, 실제 abort·bfcache
  - 실제 HTTP·CSRF 쿠키·세션

## 7. 운영 코드·설정 보존

- 실행 전후로 `src/main`(마이그레이션·화면 포함)·`build.gradle`·`settings.gradle`·`gradle/`·`.github` 파일 143개의 SHA-256이 같아요. staged diff 해시도 같아요.
- 바뀐 것은 테스트와 보고서뿐이에요.
  - 신규 테스트 파일 6개(도우미 포함)
  - 기존 `SalaryAccountCryptoTest` 1개 테스트 수정
  - 이 보고서 폴더(보고서, node 테스트)
- 작성자가 수정해 둔 `FlywayMigrationTests`는 건드리지도 실행하지도 않았어요.
- `.env`·실제 키·서비스 토큰은 읽거나 출력하지 않았어요. 가상 번호와 테스트 전용 키만 사용했어요.
- 서버·DB·마이그레이션·이관은 실행하지 않았어요. 커밋·푸시·PR도 하지 않았어요.

## 8. 사용자 수동 확인 (별도 기록, 자동 테스트 수에 포함하지 않음)

사용자가 farm-demo에서 조회·등록·교체·해제·마스킹·409를 직접 확인했어요. 이번 자동 테스트로 재현하거나 입증한 것은 아니에요.

## 9. 이번에 검증하지 않은 범위

- **실제 DB:**
  - V10 적용과 기존 V9 데이터의 version 0 시작, CHECK 제약
  - `PESSIMISTIC_WRITE` 회원 행 잠금, `REPEATABLE_READ` 스냅샷
  - 최초 등록·교체·해제의 실제 동시성, 해제 후 재등록 경합
  - 저장·삭제 실패 시 계좌와 회원 버전의 함께 롤백(관찰 O-1)
  - JPA flush 순서, `FlywayMigrationTests`
- **Security:** 실제 세션 로그인, 익명 401, CSRF 누락 403(로그인 검사보다 먼저 일어나는 순서 포함), `hasRole("WORKER")` 필터 차단과 필터의 `WORKER_ROLE_REQUIRED` 403·no-store 응답
- **프로필:** farm-demo 활성·비활성에 따른 `/demo/salary-account` 등록 여부
- **Spring Boot 구성:** 실제 `JsonMapper` 설정, `jobClock` 빈 주입
- **브라우저:** 실제 렌더링·폼 검증·커서·IME·bfcache·실제 fetch, 두 실제 창 사이의 상호작용

## 10. PR 본문용 짧은 검증 요약

```
급여 계좌 관리 API·farm-demo 독립 단위 검증 (salary-account, 기준 432477f + 작업 트리)
- Gradle unitTest 선택 실행 최종 253개 전부 통과 = 신규 146 + 직접 영향 기존 107
  (신규: DTO 17 / 서비스 46 / 마스킹·엔티티 22 / API 58 / 화면·설정 정적 3)
- 1차 실행에서 기존 SalaryAccountCryptoTest "암호문 getter 없음" 1건 실패 → 문서화된 마스킹용
  ciphertextForDecryption() 추가에 맞춰 "그 하나만 허용"으로 좁게 갱신 후 재실행 통과 (운영 결함 아님)
- 화면 스크립트 원문 가짜 DOM·가짜 서버 node 테스트 16개 통과 (별도 실행, 합산 안 함)
- 확인: 문자열·정수 강제, version·DELETE 쿼리 경계, 마스킹 문자열 거절, principal ID만 사용, DB 역할 재확인,
  버전 일치 시 증가·불일치 409(저장·삭제 없음), 미등록 해제 204·버전 유지, 해제 후 재등록 버전 연속,
  해제에 복호화 불필요, 마스킹 1/4/5/30 경계·가입 형식 호환·AAD/변조/키 오류 503, 오류 비노출·no-store, 415,
  화면의 CSRF·조회 버전 전송, 409 후 자동 재시도 없음, 204 후 재조회·실패 구분, 형식·비노출
- 관찰: 저장 실패 시 회원 버전이 메모리에서 먼저 증가(실제 롤백 미검증), 잘못된 입력은 DB 역할 확인보다 먼저 400
- Repository mock·순차 호출이므로 실제 MySQL 잠금·스냅샷·롤백·V10 미검증. Security·CSRF·프로필 활성화·
  브라우저는 후속 통합 검증 대상
- 운영 코드·설정 변경 없음(해시 확인), 커밋 없음
```
