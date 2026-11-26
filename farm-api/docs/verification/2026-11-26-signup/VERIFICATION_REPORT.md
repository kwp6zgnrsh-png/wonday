# 회원가입·선택 급여 계좌·/me 국적·farm-demo 단위 검증 보고서

- 검증일: 2026-11-26
- 작업 공간: `C:\Users\HCB\Desktop\wonday`
- 브랜치: `signup`
- HEAD: `78d9f9d`
- staged 변경은 없고, 커밋되지 않은 변경이 있어요. 모두 보존했어요.
- 저장소 루트와 `farm-api`에는 `AGENTS.md`가 없어요.
- `.codex`의 member-identity worktree는 수정하지 않았어요.
- 이번 결과는 이번 실행만의 결과예요. 이전 단위 테스트 120개, V8 통합 테스트 7개, 사용자의 수동 확인과 합치지 않았어요.

## 1. 요약

| 구분 | 테스트 | 성공 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|---|
| 신규 Java 단위 테스트 (8개 클래스) | 156 | 155 | **1** | 0 | 0 |
| 직접 영향받은 기존 단위 테스트 (5개 클래스) | 12 | 12 | 0 | 0 | 0 |
| **Gradle 선택 실행 합계** | **168** | 167 | **1** | 0 | 0 |
| 화면 순수 함수 (node 내장 `node:test`, Gradle과 별도) | 7 | 7 | 0 | 0 | 0 |

- **남은 실패 1건은 운영 코드 결함 D-1의 재현 테스트예요(6절).**
  - JSON이 아닌 Content-Type 요청에 `SignupErrorHandler`가 선언한 400 대신 415가 나가요.
  - 기대값을 바꾸거나 테스트를 제외하지 않았어요.
- 중간 실행에서 나온 실패 4건은 제 테스트 코드 오류였어요. 고친 뒤 다시 실행했어요(4절).
- 이 밖의 계약 항목은 모두 기대대로 동작했어요.

## 2. 추가한 테스트 파일과 목적

경로는 모두 `farm-api/src/test/java/com/wonday/farm/` 기준이에요.

| 파일 | 수 | 목적 |
|---|---|---|
| `member/signup/testsupport/SignupTestData.java` | — | 가상 요청·번호·계좌·32바이트 키, 테스트 전용 계좌 복호화 헬퍼 |
| `member/signup/dto/SignupRequestJsonTest.java` | 17 | 실제 DTO의 JSON 문자열 강제, 필수값, 길이, 선택 계좌 구분, toString 가림 |
| `salary/validation/SalaryAccountValidatorTest.java` | 33 | 은행 코드, 계좌 숫자·하이픈, 1~30자리, 원문 100자, 예금주 정리 |
| `salary/crypto/SalaryAccountCryptoTest.java` | 17 | 계좌 암호화 형식, nonce, AAD(회원 ID·은행 코드) 결합, 키 오류, 엔티티 |
| `member/signup/service/SignupServiceTest.java` | 43 | 역할·국적별 저장, 정규화, 비밀번호 인코딩, 선택 계좌, 키, 중복, 저장 순서, 예외 전파, `@Transactional` 선언 |
| `member/signup/api/SignupApiTest.java` | 32 | 201 응답, 400·409·503·500 변환, 제약 이름 판정, 비노출, 세션 미변경 |
| `auth/MeNationalityTest.java` | 6 | DB 국적 → FarmPrincipal → /me, null 호환, 요청 파라미터 무시 |
| `demo/signup/DemoSignupAccountApiTest.java` | 5 | 본인 ID 조회, 등록·미등록, 접근 거절, 503·500, no-store, 선언 |
| `demo/SignupDemoPageTest.java` | 3 | `/demo/signup` 리소스, 로그인 링크, 정적 회귀 방지 |

- **화면 순수 함수 검증 스크립트:** `C:\Users\HCB\AppData\Local\Temp\claude\c592a1f415e1f9ad\4f6e9423-6b6c-4914-b480-f74ee6027617\scratchpad\signup-format.test.mjs`
  - 저장소에는 넣지 않았어요.
  - HTML에서 함수 원문을 추출해 실행했어요.
- 기존 테스트, 운영 코드, HTML, 마이그레이션, Gradle, CI는 수정하지 않았어요.
- `.env`와 실제 키는 읽지 않았어요. 새 패키지도 설치하지 않았어요.

## 3. 실행 명령·범위·시간

`build.gradle` 확인 결과는 다음과 같아요.

- `unitTest`는 `integration`과 `known-defect` 태그를 제외해요.
- `unitTest`에 자동으로 연결된 태스크는 없어요.
- `jacocoUnitTestReport`는 `unitTest`에 의존해요.

`farm-api`에서 `JAVA_HOME=jdk-21.0.12.1+1`로 실행했어요.

```
./gradlew.bat --no-daemon unitTest \
  --tests 'com.wonday.farm.member.signup.dto.SignupRequestJsonTest*' \
  --tests 'com.wonday.farm.member.signup.service.SignupServiceTest*' \
  --tests 'com.wonday.farm.member.signup.api.SignupApiTest*' \
  --tests 'com.wonday.farm.salary.validation.SalaryAccountValidatorTest*' \
  --tests 'com.wonday.farm.salary.crypto.SalaryAccountCryptoTest*' \
  --tests 'com.wonday.farm.auth.MeNationalityTest*' \
  --tests 'com.wonday.farm.demo.signup.DemoSignupAccountApiTest*' \
  --tests 'com.wonday.farm.demo.SignupDemoPageTest*' \
  --tests 'com.wonday.farm.auth.AuthApiTest' \
  --tests 'com.wonday.farm.auth.FarmUserDetailsServiceTest*' \
  --tests 'com.wonday.farm.auth.PasswordPolicyTest*' \
  --tests 'com.wonday.farm.member.MemberTest*' \
  --tests 'com.wonday.farm.demo.AuthDemoPageTest*'
# unitTest가 D-1로 실패하면 리포트 태스크가 건너뛰어지므로, 같은 실행 데이터로 리포트만 생성:
./gradlew.bat --no-daemon jacocoUnitTestReport -x unitTest
```

- **최종 실행:** `unitTest`는 약 40초 걸렸고, D-1 때문에 BUILD FAILED예요. 리포트 생성은 약 13초였어요.
- **영향 테스트 선정 이유** (총 12개)

  | 클래스 | 이유 |
  |---|---|
  | `AuthApiTest` (2) | /me 응답 필드 변경 |
  | `FarmUserDetailsServiceTest` (3) | 로그인 principal 생성 변경 |
  | `PasswordPolicyTest` (3) | 가입에서 같은 정책 사용 |
  | `MemberTest` (3) | 생성자 추가 |
  | `AuthDemoPageTest` (1) | 매핑 추가 |

  - `AuthApiTests`와 `AuthHttpTests`는 통합 테스트라서 제외했어요.
- **화면 함수 실행:** `SIGNUP_HTML=<farm-signup.html 경로> node --test signup-format.test.mjs` (Node v26.13.1 내장, 약 0.1초)
- **실행하지 않은 것:** 전체 단위 테스트, 통합 테스트(FlywayMigrationTests 포함), SpringBootTest, Testcontainers, 서버, DB, Flyway, 이관 도구, 브라우저

## 4. 중간 실패 (테스트 코드 오류, 수정 완료)

| 실패 | 원인 | 조치 |
|---|---|---|
| 컴파일 오류 | 애노테이션 값에 `"1".repeat(31)` 식을 넣었어요 | 31자리 리터럴로 교체 |
| `SignupApiTest` 3건, `DemoSignupAccountApiTest` 1건 | 이미 예외를 던지도록 설정한 mock을 `given(mock.call()).willThrow()`로 다시 설정해서, 설정 도중 예외가 발생했어요 | `willThrow(..).given(mock)` 형식으로 변경 |
| node 테스트 1건 | vm 영역의 배열과 비교해서 참조가 같지 않다고 나왔어요 | JSON 복사 후 비교 |

운영 코드 결함이 아니라서 기대값은 바꾸지 않았어요.

## 5. 항목별 결과

### A. 요청 JSON·입력 검증

- **정상 입력:** WORKER·EMPLOYER와 DOMESTIC·FOREIGN 4가지 조합이 모두 정상 처리돼요.
- **문자열 강제:**
  - 문자 필드 10개(회원 7개, 계좌 3개)에 숫자, 소수, boolean, 배열, 객체를 넣으면 모두 역직렬화 단계에서 거절해요.
  - 숫자로 보낸 은행 코드 `20`과 정수 식별번호도 거절해요. 앞자리 0이 사라지는 것을 막아요.
- **필수값과 역할·국적 값**
  - 회원 7개 필드는 누락, null, 빈 값, 공백만 있는 값이 모두 해당 필드 위반이에요(Bean Validation).
  - 서비스는 `worker`, `ADMIN`, `" WORKER"`, `domestic`, `KOREAN`, `Foreign`을 400 `INVALID_REQUEST`로 거절해요.
- **정규화:**
  - 전화번호는 공백·하이픈을 제거한 뒤 ASCII 숫자 10~11자리만 받아요. 전각 숫자, `+82`, `_`는 거절해요.
  - 식별번호는 공백·하이픈을 제거한 뒤 13자리이고, 앞자리 0을 보존해요(`000000-0000000`).
  - 생년월일이나 체크섬은 검사하지 않아요.
- **이름과 예금주명:**
  - 앞뒤 공백만 제거해요. 전각 공백도 포함돼요.
  - 내부 공백, 대소문자, 외국인 이름(`Nguyen  Van A`)은 그대로예요.
  - 공백만 있는 값은 거절해요.
  - `loginId`는 앞뒤 ASCII 공백을 제거해요. 제어문자만 있는 ID도 거절돼요.
- **비밀번호:**
  - ASCII 72자와 한글 24자(72바이트)는 허용해요.
  - ASCII 73자, 한글 24자+1, 한글 25자, 공백만 있는 값은 거절해요.
  - trim 없이 원문 그대로 인코더에 전달해요.
- **선택 계좌:**
  - 생략과 null은 허용해요.
  - `{}`와 일부 필드 누락은 해당 계좌 필드의 위반이에요.
  - `salaryAccount` 자리에 문자열, 배열, boolean, 숫자를 넣으면 형식 오류예요.
- **은행 코드:** ASCII 숫자 정확히 3자리 문자열이에요. `000`, `999` 같은 목록 밖 코드도 허용해요. 실제 은행 여부는 확인하지 않아요.
- **계좌번호:**
  - 숫자 1자리와 30자리는 허용하고, 하이픈 위치는 상관없어요(`--1--` → `1`).
  - 하이픈 포함 원문 100자는 허용하고 101자는 거절해요.
  - 거절: 31자리, 하이픈만 있는 값, 공백, `_`, `.`, en dash, 전각 숫자, 문자, `+`, 탭
  - 은행별 대표 길이와 달라도(우리 5자리·20자리) 거절하지 않아요.
- **원문 길이 상한:**
  - loginId, name, phone, identityNumber, accountNumber, holder는 100자
  - password는 72자, role은 32자, nationalityType은 16자, bankCode는 3자
  - 위 기준으로 상한에서는 허용되고 상한+1에서는 위반이에요.
- **서버가 정하는 필드:** 요청 DTO에 회원 ID, 해시, 식별번호 종류 필드가 없어요. 이 값들은 서버가 정해요.

### B. 회원가입 서비스·중복·저장 (mock 단위)

- **검증 실패 시:** 입력 거절(24가지와 null 요청)에서는 Repository, 암호화, 인코더를 전혀 호출하지 않아요.
- **키 미설정 시:**
  - 식별정보 키가 없으면 `IdentityCryptoUnavailableException`이고, 조회와 저장이 없어요. 계좌 생략·포함 모두 같아요.
  - 계좌 포함 가입만 계좌 키가 필요해요. 키가 없으면 `SalaryAccountCryptoUnavailableException`이고, 조회와 저장이 없어요.
  - 계좌를 생략하면 계좌 키가 잘못돼도 가입에 성공해요.
- **중복:**
  - 로그인 ID 중복은 409 `LOGIN_ID_ALREADY_EXISTS`예요.
  - HMAC 중복과 기존 원문 번호(정규화 번호로 조회) 중복은 409 `IDENTITY_ALREADY_EXISTS`예요.
  - HMAC 중복은 역할·국적 조합 4가지 모두에서 우회되지 않아요.
  - 중복이면 회원 저장, 계좌 저장, 인코더 호출이 없어요.
- **저장 순서:** 아래 순서를 확인했어요.
  1. 로그인 ID 확인
  2. HMAC 확인
  3. 기존 번호 확인
  4. 회원 `saveAndFlush`
  5. 계좌 `saveAndFlush`
  6. 최종 `flush`
- **식별정보:**
  - 발급된 회원 ID(101)로 식별정보를 연결해요.
  - 저장된 암호문을 같은 ID와 종류로 복호화하면 정규화 번호가 나와요.
  - HMAC은 정규화 번호의 HMAC이에요.
  - 국적과 종류는 DOMESTIC→RESIDENT, FOREIGN→FOREIGN으로 저장돼요.
- **저장값:**
  - 정규화한 이름·전화가 저장되고, 비밀번호는 인코더 결과(`{bcrypt}encoded`)만 저장돼요.
  - `Member`에는 기존 원문 번호 필드가 매핑되어 있지 않아서, 신규 번호를 그 컬럼에 쓸 수 없어요(정적 확인).
- **계좌:**
  - 같은 회원 ID로 은행 `020`, 예금주(앞뒤 공백 제거), 암호문을 저장해요.
  - 암호문에 평문 번호가 없고, 테스트 복호화 결과는 `100200300400`이에요.
  - 생성·수정 시각은 서울 시간대 Clock을 넣어도 UTC `2026-11-26T01:02:03.123456`(마이크로초 절사)예요.
- **응답:** `id, loginId, name, role, nationalityType` 5필드만 있어요.
- **예외 전파:** 회원 저장, 계좌 저장, 최종 flush 예외가 성공 응답으로 바뀌지 않고 그대로 전파돼요. Java 객체 값이 복원된다고 기대하지 않았어요.
- **트랜잭션 선언:** `signup`에 `@Transactional`(REQUIRED, readOnly=false, noRollbackFor 없음)이 선언된 것만 확인했어요.
  - 서비스를 직접 생성했기 때문에 Spring 트랜잭션 프록시를 거치지 않았어요.
  - **실제 DB 전체 롤백은 입증하지 않았어요.**

### C. 계좌 암호화·엔티티

- **형식:** `v1:` + URL-safe Base64예요. 평문 계좌번호가 보이지 않아요.
- **왕복:** 테스트 전용 JDK 복호화로 `0`, `0012345`, 30자리 번호가 모두 원문으로 돌아와요.
  - 운영 코드에 복호화 API는 추가하지 않았어요.
- **nonce:** 같은 번호를 30회 암호화하면 암호문 30개, nonce 30개가 모두 달라요.
- **결합:** 다른 회원 ID, 다른 은행 코드, 다른 키로 복호화하면 모두 `AEADBadTagException`이에요.
- **잘못된 입력:** 정규화되지 않은 번호, 31자리, 문자, null, 회원 ID 0, 잘못된 은행 코드는 `IllegalArgumentException`이에요.
- **키 설정:**
  - 누락, 공백, Base64 오류, 3·16·31·33·63바이트 키를 확인했어요.
  - 생성은 예외 없이 되고, `requireConfigured`나 `encrypt`를 호출할 때 원인 없는 고정 메시지 예외가 나요.
  - toString에 키가 나오지 않아요.
- **엔티티:**
  - `memberId`가 `@Id`(`member_id`)예요.
  - 필드는 6개(회원 ID, 은행 코드, 암호문, 예금주, 생성·수정 시각)이고, 원문 번호 필드나 암호문·번호 getter는 없어요.
  - 잘못된 저장값 12가지는 거절되고, 256자 암호문과 100자 예금주는 허용돼요.
  - toString은 가려져 있어요.
- 몇 개의 테스트로 암호학적 안전성을 입증한 것은 아니에요.

### D. API 응답·오류 변환 (standalone MockMvc)

- **정상:** 201이고 JSON 필드는 5개 순서 그대로예요. 번호, 계좌, 비밀번호는 없어요. 계좌를 생략하거나 null로 보내도 201이에요.
- **400 `INVALID_REQUEST`** (서비스 호출 없음):
  - 깨진 JSON, 빈 본문, 배열, null, 문자열 본문
  - 문자 필드의 숫자, 필수값 누락, 공백, 불완전한 계좌, 73자 비밀번호
  - 서비스가 던진 invalid 예외도 400이에요.
- **409:** 서비스의 중복 오류 2종이 각각의 코드로 변환돼요.
- **동시 가입 모사** (Hibernate `ConstraintViolationException`을 원인으로 가진 `DataIntegrityViolationException`):
  - 409로 변환되는 제약 이름:
    - `uq_member_login`, `member.uq_member_login`, `` `member`.`UQ_MEMBER_LOGIN` `` → 로그인 ID 중복
    - `uq_member_identity_hmac`, `member.uq_member_identity_hmac` → 식별정보 중복
  - 500으로 처리되는 제약 이름(중복으로 오판하지 않음): `PRIMARY`, `member_salary_account.PRIMARY`, `fk_salary_account_member`, `ck_member_identity_storage`, `uq_member_login_backup`, `old_uq_member_identity_hmac`, null
  - Hibernate 원인이 없는 무결성 오류나 `DuplicateKeyException`은 메시지에 제약 이름이 있어도 500이에요.
- **503:** 식별정보 키, 계좌 키, DB 연결, 쿼리 시간 초과, 트랜잭션 생성 실패, 트랜잭션 시간 초과
- **500:** 그 밖의 내부 예외
- **비노출:** 모든 오류 본문은 `code, message`뿐이에요. 비밀번호, 번호, 계좌, `Duplicate entry`, SQL, `jdbc`, `Exception`, 로그인 ID 원문이 없어요.
- **세션:** 기존 SecurityContext 인증이 그대로 남고, 세션 속성과 쿠키가 추가되지 않아요.
  - 실제 Security 필터, CSRF, 세션 고정 방지, 쿠키는 standalone MockMvc로 검증하지 않았어요.
- **Content-Type 오류:** `application/x-www-form-urlencoded` 요청은 **415**예요. 결함 D-1이에요.

### E. 로그인 연계와 /me 국적

- `FarmUserDetailsService`가 DB의 DOMESTIC·FOREIGN을 `FarmPrincipal`에 담아요.
- /me는 `id, name, role, nationalityType` 4필드로 응답해요. 해시, 번호, 로그인 ID, 계좌는 없어요.
- 국적이 없는 계정은 /me에서 `nationalityType: null`(명시적 null)이에요.
- DB의 알 수 없는 값(`domestic`)은 null로 읽고, 로그인 자체는 막지 않아요.
- 기존 5인자 생성자도 호환되며, 이때 국적은 null이에요.
- `memberId`, `id`, `role`, `nationalityType` 요청 파라미터로 /me 값을 바꿀 수 없어요.
- 기존 `AuthApiTest`와 `FarmUserDetailsServiceTest`도 그대로 통과했어요.

### F. 데모 전용 계좌 조회

- **본인 범위:** principal ID(101)로만 회원과 계좌를 조회해요. `memberId=999` 파라미터는 무시돼요.
- **등록된 경우:** 응답은 `memberId, registered=true, bankCode, accountHolderName`이에요. 암호문, `v1:`, 번호, 키는 없어요.
- **미등록:** `registered=false`이고 은행 코드와 예금주는 명시적 null이에요. EMPLOYER 계정도 허용돼요.
- **접근 거절:** 삭제된 계정, `ADMIN`, 소문자 `worker`는 403 `DEMO_ACCESS_DENIED`이고, 계좌를 조회하지 않아요.
- **오류:** DB 연결 오류, 쿼리 시간 초과, 트랜잭션 생성 실패는 503이에요. 그 밖의 예외는 500이고, 원문이 노출되지 않아요.
- **캐시 금지:** 모든 응답에 `Cache-Control: no-store`가 있어요.
- **선언:** `@Profile("farm-demo")`와 `@Transactional(readOnly = true)` 선언만 확인했어요. 실제 프로필 기동과 SecurityConfig의 `hasAnyRole` 제한은 검증하지 않았어요.

## 6. 발견한 결함

### D-1. JSON이 아닌 Content-Type의 가입 요청이 400 INVALID_REQUEST가 아니라 415로 응답함 (낮음)

- **재현:** `SignupApiTest$BadRequest.mediaType`
  - `POST /api/auth/signup`에 `Content-Type: application/x-www-form-urlencoded`, 본문 `loginId=worker-01&password=Pass`를 보내요.
- **기대값:** 400과 `{"code":"INVALID_REQUEST",...}`
  - 근거: `SignupErrorHandler.invalidRequest()`가 `HttpMediaTypeNotSupportedException`을 400 `INVALID_REQUEST`로 명시적으로 선언했어요.
  - 근거: 문서는 신규 업무 오류가 `{"code","message"}` 형식이라고 해요.
- **실제값:** 415이고, 우리 오류 형식이 아니에요(Spring 기본 처리).
- **원인:**
  1. `consumes = application/json`이 맞지 않으면, 핸들러를 고르는 단계에서 `HttpMediaTypeNotSupportedException`이 발생해요.
  2. 이 시점에는 선택된 핸들러가 없어요.
  3. 그래서 `@RestControllerAdvice(assignableTypes = SignupApi.class)`의 적용 대상 판정이 false가 되고, advice가 적용되지 않아요.
  4. 결국 `DefaultHandlerExceptionResolver`가 415를 반환해요.
  - 이 판단은 standalone MockMvc 결과와 Spring의 advice 적용 규칙을 근거로 했어요. 실제 서버의 `/error` 응답 본문은 확인하지 않았어요.
- **영향:**
  - 클라이언트가 형식 오류를 문서와 다른 상태코드·본문으로 받아요.
  - 핸들러의 해당 선언은 실행되지 않는 코드예요.
  - 민감정보 노출은 확인되지 않았어요.
- **최소 수정안 (운영 코드는 수정하지 않았어요, 택1):**
  1. 문서와 핸들러를 415 그대로로 정리해요. 핸들러에서 `HttpMediaTypeNotSupportedException`을 제거하고 문서에 415를 적어요.
  2. 400을 유지하려면 `consumes` 제한을 없애고, 컨트롤러나 필터에서 Content-Type을 검사한 뒤 `SignupException.invalid()`를 던져요.
  3. 또는 이 예외만 처리하는 경로 기반 advice(`assignableTypes` 없음, `/api/auth/signup` 요청만 변환)를 별도로 두어요.
- 실패하는 재현 테스트는 스위트에 그대로 두었어요. 수정 방향이 정해지면 기대값을 그에 맞게 확정해야 해요.

#### D-1 처리 결과 (2026-11-26, 사용자 결정: 415 유지·문서 수정)

- **결정:** 사용자가 "415 유지, 문서 수정"을 선택했어요. 그래서 계약은 415예요.
- **문서:** `farm-api/docs/signup-api.md`의 "실패 응답" 절에 문단을 추가했어요.
  - JSON이 아닌 Content-Type은 `415 Unsupported Media Type`이에요.
  - 응답이 `{"code","message"}` 형식이 아니에요.
  - 요청이 가입 서비스에 도달하지 않아요.
  - 운영 코드는 수정하지 않았어요.
- **테스트:** `SignupApiTest$BadRequest.mediaType`의 기대값을 합의된 계약에 맞게 바꿨어요. 결함을 숨기려고 완화한 것이 아니에요.
  - 상태가 415예요.
  - 서비스를 호출하지 않아요.
  - 응답에 비밀번호·로그인 ID 같은 원문이 없어요.
- **재실행:** `unitTest --tests 'com.wonday.farm.member.signup.api.SignupApiTest*'` (약 37초)
  - 결과: 32개, 통과 32, 실패 0, 오류 0, 건너뜀 0
  - 나머지 12개 클래스는 다시 실행하지 않았어요. 그 클래스들의 결과는 위 168개 실행 기록(D-1 1건 외 전부 통과)을 그대로 따라요.
- **남은 정리 (운영 코드, 작성자 몫):** `SignupErrorHandler.invalidRequest()`의 `HttpMediaTypeNotSupportedException` 항목은 실행되지 않는 선언이에요. 오해를 막으려면 제거하는 것이 좋아요. 이 작업은 검증 범위 밖이라 하지 않았어요.

#### D-1 후속 정리 재검증 (2026-11-26, 작성자 수정 후 선택 실행)

이 절은 위 168개 실행 및 첫 32개 재실행과 **별개의 실행**이에요. 합산하지 않았고, 이번 결과만으로 전체 테스트가 재통과했다고 보지 않아요.

- **위치:** `C:\Users\HCB\Desktop\wonday`, 브랜치 `signup` (AGENTS.md 없음)
- **작성자 수정 확인 (정적):**
  - `SignupErrorHandler.java`: `HttpMediaTypeNotSupportedException`의 선언과 import가 제거됐어요. `src/main`의 `member/signup` 아래에는 이 예외를 참조하는 곳이 없어요. 나머지 처리(400·409·503·500)는 그대로예요.
  - `SignupApiTest.java`: `@Nested` 표시명이 "400·415 — 서비스 호출 없음"이에요. `mediaType`의 검증 조건은 그대로예요(415, `assertNoSecrets`, `verifyNoInteractions(service)`).
- **명령:** `farm-api`에서 `./gradlew.bat --no-daemon unitTest --tests "com.wonday.farm.member.signup.api.SignupApiTest*"` (BUILD SUCCESSFUL, 약 43초)
  - 커버리지 리포트 태스크는 실행하지 않았어요. `unitTest`에는 리포트 finalizer가 없어요.
- **결과:** 32개, 통과 32, 실패 0, 오류 0, 건너뜀 0

  | @Nested | 실행 | 통과 | 실행 시간(XML) |
  |---|---|---|---|
  | `SignupApiTest$Created` (정상) | 3 | 3 | 10.717s |
  | `SignupApiTest$BadRequest` (400·415) | 8 | 8 | 0.698s |
  | `SignupApiTest$Errors` (409·503·500) | 21 | 21 | 1.196s |

  - 와일드카드 범위: 결과 XML은 위 3개뿐이고 모든 testcase의 classname이 `SignupApiTest$…`예요. 다른 클래스는 포함되지 않았어요.
  - 415 테스트("JSON이 아닌 Content-Type은 문서대로 415…")가 통과했어요. 서비스 미호출과 원문 비노출도 같은 테스트에서 확인됐어요.
- **결과 위치:** `farm-api/build/test-results/unitTest/TEST-com.wonday.farm.member.signup.api.SignupApiTest$*.xml`, `farm-api/build/reports/tests/unitTest/index.html` (docs로 복사하지 않았어요)
- **보존:** 실행 전후 `src`·`build.gradle`·`settings.gradle`·`gradle/`의 SHA-256(204개 파일)이 같았고, git 추적 diff 통계와 staged diff 해시도 같았어요.
- **참고 (범위 밖, 판단은 작성자 몫):** 같은 `HttpMediaTypeNotSupportedException` 선언이 `InsuranceErrorHandler`, `WorkScheduleCancelErrorHandler`, `WorkScheduleUpdateErrorHandler`에도 있어요. 각 advice의 적용 범위에 따라 같은 이유로 실행되지 않을 수 있어요. 이번에는 확인하지 않았어요.

## 7. 관련 클래스별 커버리지 (이번 168개 선택 실행 기준)

`build/reports/jacoco/jacocoUnitTestReport/jacocoUnitTestReport.xml`에서 클래스별로 계산했어요. 전체 프로젝트 커버리지가 아니에요. 측정 제외나 기준 변경은 하지 않았어요.

| 클래스 | 라인 | 분기 | 미실행 경로 |
|---|---|---|---|
| `SignupService` | 41/41 | 27/28 | `name.isBlank()` 참 분기: 앞의 `isBlank` 검사와 같은 공백 정의를 써서 도달할 수 없어요 |
| `SignupErrorHandler` | 17/17 | 14/14 | — |
| `SignupApi`, `SignupException`, `SignupRequest`, `SignupSalaryAccountRequest` | 100% | — | — |
| `SignupStringDeserializer` | 3/4 | 2/2 | return 안에서 예외가 나는 줄을 JaCoCo가 미실행으로 표시하는 현상 |
| `SalaryAccountValidator` | 9/9 | 16/16 | — |
| `SalaryAccountCrypto` | 32/34 | 24/24 | 51–52행 `GeneralSecurityException` catch: JDK 표준 알고리즘에서는 발생시킬 수 없어요 |
| `MemberSalaryAccount` | 18/19 | 22/22 | JPA용 protected 생성자 |
| `AuthApi` | 3/3 | — | — |
| `FarmPrincipal` | 12/12 | — | — |
| `FarmUserDetailsService` | 8/8 | — | — |
| `DemoSignupAccountApi` | 14/14 | — | — |
| `Member` | 27/29 | 7/14 | `assignIdentity` 덮어쓰기 거절 분기. 이번 실행 범위 밖인 `MemberIdentityStorageTest`가 담당해요 |
| `AuthDemoPage` | 3/6 | — | 다른 demo 페이지 메서드(이번 범위 밖) |

## 8. UI 정적 확인과 실제 동작 검증의 구분

**자동 실행한 것**

- `node --check`: `farm-signup.html`과 `farm-login.html`의 스크립트 문법 검사를 통과했어요.
- `SignupDemoPageTest`(Java):
  - 리소스와 링크가 있어요.
  - 식별번호 마지막 칸은 `type="password"`이고, 앞 6자리·첫 1자리 칸은 text·maxlength 6·1이에요.
  - 계좌 입력은 text예요.
  - 브라우저 저장소, `innerHTML`, `document.cookie`를 쓰지 않아요.
  - 이 검사들은 문자열이 있는지 없는지만 본 것이에요.
- `node:test` 7개: HTML 원문에서 추출한 순수 함수를 실행했어요.
  - 대표 형식 표는 우리 4-3-6, KB 6-2-6·3-2-4-3, 신한 3-3-6·3-2-6만 있어요.
  - 완성 길이와 부분 입력에서 하이픈 위치가 맞아요. 끝에 하이픈을 붙이지 않아요.
  - 앞자리 0과 숫자 순서를 유지하고, 30자리 입력도 자르지 않아요.
  - 형식을 바꿔 다시 표시해도 숫자가 보존돼요.
  - 커서 위치 계산이 맞아요.
  - 식별번호 붙여넣기 정규식이 화면 코드와 같아요.

**정적 검토만 한 것** (코드를 읽고 판단했고, 실행하지 않았어요)

- **은행 선택과 계좌 입력:**
  - 은행을 선택하기 전이나 기타 코드가 3자리가 아니면 계좌 입력과 자동 표시 체크가 비활성이에요(`updateControls`).
  - 은행 select와 기타 코드 입력은 `required`예요.
- **자동 하이픈:**
  - 입력 중에는 대표 형식을, 입력칸을 벗어나면 길이가 맞는 구계좌 형식을 적용해요.
  - IME 조합 중에는 적용하지 않아요. 하이픈 옆 Backspace·Delete는 선택 범위를 넓혀서 처리해요.
- **잘못된 입력:** 30자리 초과나 잘못된 문자는 `setCustomValidity`로 안내하고 제출을 막아요. 값을 자르지 않아요.
- **식별번호:**
  - 13자리를 붙여넣으면 세 칸으로 나눠 넣어요.
  - 칸 길이보다 긴 잘못된 붙여넣기는 막고 안내해요.
  - 실제 13자리를 합쳐 `identityNumber`로 보내요.
- **계좌 체크 해제:** 계좌 입력을 비우고 `salaryAccount: null`을 보내요.
- **CSRF:** POST마다 `/api/auth/csrf`를 새로 받아요. 로그인 뒤 다음 POST도 새 토큰을 받아요.
- **기록:** CSRF 응답은 숨김 메시지로 기록하고, 요청 본문은 기록하지 않아요.
- **가입과 로그인 분리:** 버튼과 폼이 따로예요. 가입 201 뒤의 세션 조회 실패는 "가입은 성공, 후속 조회만 실패"로 구분해 안내해요.
- **/me와 계좌 표시:** 로그인 뒤 /me와 계좌 조회 결과를 각각 표시해요. 계좌 조회 실패를 미등록으로 표시하지 않아요. 회원 ID가 서로 다르면 표시를 중단해요.
- **DOM 출력:** 모두 `textContent`를 사용해요.

**검증하지 않은 것:** 실제 DOM 이벤트, 포커스·커서 이동, 마스킹 표시, 브라우저 유효성 차단, 실제 HTTP·CSRF·쿠키 흐름. 브라우저 테스트로 확대하지 않았어요.

## 9. 미검증 영역

- 실제 DB 관련
  - V9 적용과 제약(PK·FK·CHECK), Hibernate 매핑과 `validate`
  - `existsByIdentityHmac`·`countLegacyIdentityNumber`의 실제 SQL(regexp 정규화)
  - 실제 트랜잭션 롤백(회원 INSERT 후 계좌 실패 시 전체 롤백)
  - 동시 가입 시 실제 UNIQUE 위반과 Hibernate가 추출하는 실제 제약 이름 형식
- Security 관련
  - `/api/auth/signup` permitAll과 CSRF 적용
  - 가입이 자동 로그인이 아닌지(실제 세션)
  - 로그인 필터·세션 교체·`/me` 401 흐름
  - `/demo/api/signup/salary-account`의 `hasAnyRole`
- farm-demo 프로필 기동과 경로 등록, 실제 화면 동작(8절)
- Spring Boot 실제 ObjectMapper 설정(알 수 없는 필드 처리 등)과 `/error` 응답

## 10. 사용자 수동 확인 (별도 기록, 자동 테스트 수에 포함하지 않음)

사용자는 farm-demo에서 다음을 직접 확인했다고 보고했어요.

- 회원가입, 로그인
- 국적 표시, 계좌 등록 여부
- 은행 선택 전 계좌 입력 차단, 자동 하이픈

## 11. 보존 확인

- 추적 중인 파일의 unstaged diff SHA-256이 작업 전후로 같아요. staged 변경은 0건이에요.
- 새 테스트 파일(미추적)과 이 보고서 외에는 아무것도 바꾸지 않았어요.
- 다음 git 작업은 하지 않았어요: 브랜치 변경, 병합, rebase, stash, reset, add, 커밋, 푸시
- `.codex`의 member-identity worktree는 이번 작업에서 수정하지 않았어요.

## 12. PR 본문용 짧은 검증 요약

```
회원가입·선택 급여 계좌·/me 국적·farm-demo 독립 단위 검증 (signup)
- Gradle 선택 실행 168개: 167 통과 / 1 실패(결함 D-1 재현) / 오류·건너뜀 0
  (신규 156: 요청 JSON 17 / 계좌 검증 33 / 계좌 암호화·엔티티 17 / 가입 서비스 43 / 가입 API 32 / /me 국적 6 /
   데모 계좌 조회 5 / 데모 페이지 3, 기존 영향 12)
- 화면 순수 함수(node 내장 테스트) 7개 통과. DOM·브라우저 동작은 정적 검토만
- 확인 범위:
  - JSON 문자열 강제(숫자·boolean 자동 변환 거절), 필수값·길이, 정규화(전화·번호 앞자리 0, 이름 앞뒤 공백)
  - 비밀번호 72바이트 경계와 trim 없음, 선택 계좌 생략/null/불완전 구분, 계좌 1~30자리·은행 코드 3자리
  - 키 미설정 시 저장 전 거절(계좌 키는 계좌 포함 시만), 로그인 ID·HMAC·기존 번호 중복 409, 저장 순서
  - 계좌 암호문 AAD(회원 ID·은행 코드) 결합과 nonce, 제약 이름 기반 409 변환과 알 수 없는 제약 500
  - 오류 응답 비노출, /me nationalityType(DOMESTIC/FOREIGN/null), 데모 계좌 조회 본인 범위·no-store
- 결함 D-1: form 등 JSON이 아닌 Content-Type이 핸들러 선언(400 INVALID_REQUEST)과 달리 415로 응답
  (assignableTypes advice가 핸들러 선택 전 예외에 적용되지 않음)
  → 결정: 415 유지, signup-api.md에 반영. 테스트를 415 계약으로 확정한 뒤 SignupApiTest 32개 재실행, 전부 통과
  → 작성자가 핸들러의 미사용 HttpMediaTypeNotSupportedException 선언을 제거. SignupApiTest만 별도 재실행 32/32 통과
  (별도 선택 실행이며 168개 전체 재통과를 뜻하지 않음)
- mock 단위이므로 실제 트랜잭션 롤백·V9·동시 가입·Security/CSRF·프로필은 미검증
- 운영 코드 변경 없음, 커밋 없음
```
