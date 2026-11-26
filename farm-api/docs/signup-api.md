# 회원가입 API와 본인 조회 국적 응답

## 범위와 기준

- 2026-11-26 최신 main `78d9f9d`에서 `signup` 브랜치를 만들었다.
- `POST /api/auth/signup`과 `GET /api/auth/me`의 국적 응답을 구현한다.
- 기존 로그인·로그아웃·세션·CSRF·BCrypt 검증을 유지한다. 가입은 자동 로그인이 아니다.
- 급여 계좌는 가입 시 선택 저장만 지원한다. 계좌 관리 API·보험 V2·보험 계좌 조회·근무 완료·user-web 제품 화면은 이번 범위가 아니다. 개인 확인용 farm-demo 화면과 본인 계좌 등록 여부 조회를 별도로 제공한다.

## 회원가입 요청

서버 경로: `POST /api/auth/signup`, `Content-Type: application/json`.
먼저 `GET /api/auth/csrf`에서 받은 토큰과 같은 농업 세션 쿠키를 보낸다. 익명 가입에도 CSRF가 필요하다.

```json
{
  "loginId": "worker01",
  "password": "ExamplePass123!",
  "name": "TEST WORKER",
  "phone": "010-1234-5678",
  "role": "WORKER",
  "nationalityType": "FOREIGN",
  "identityNumber": "900101-5000000",
  "salaryAccount": {
    "bankCode": "020",
    "accountNumber": "1002-0030-0400",
    "accountHolderName": "TEST WORKER"
  }
}
```

모든 정보는 가상 예시다. 문자 필드에는 JSON 문자열만 받으며 숫자·boolean을 문자열로 자동 변환하지 않는다.

- `loginId`: 필수, 최대 100자. 기존 로그인 필터와 맞춰 앞뒤 ASCII 공백을 제거하고 저장한다.
- `password`: 공백만 있는 값은 거절한다. 임의 최소 길이·문자 조합 규칙을 추가하지 않고 기존 BCrypt의 UTF-8 72바이트 상한을 적용한다. 비밀번호 자체는 trim하지 않는다.
- `name`: 필수, 최대 100자, 앞뒤 공백만 제거한다. 외국인 이름을 번역하거나 대소문자를 바꾸지 않는다.
- `phone`: 원문 최대 100자. 공백·하이픈 제거 후 ASCII 숫자 10~11자리.
- `role`: 정확히 `WORKER` 또는 `EMPLOYER`. `nationalityType`: 정확히 `DOMESTIC` 또는 `FOREIGN`.
- `identityNumber`: 원문 최대 100자. 공백·하이픈 제거 후 ASCII 숫자 13자리. 체크섬·정부기관 진위 확인은 하지 않는다.
- 식별번호 종류는 서버가 국적에 따라 정한다. 요청으로 회원 ID·해시·식별번호 종류를 받지 않는다.
- `salaryAccount`: 생략 또는 null 허용. 객체를 보내면 세 필드가 모두 필수다.
- `bankCode`: 앞자리 0을 포함하는 숫자 3자리 문자열. 우리은행 `020` 외에도 형식에 맞는 코드를 받으며 실제 은행 코드 목록·계좌 존재·예금주 검증은 하지 않는다.
- 계좌번호: 사용자 합의에 따라 숫자·ASCII 하이픈 입력을 허용하고 하이픈을 제거한 숫자 1~30자리를 암호화한다. 앞자리 0은 유지한다. 공백·다른 문자는 거절하고 원문 길이는 100자까지 받는다. 은행별 하이픈 위치를 추측하거나 강제하지 않는다.
- 예금주명: 필수, 최대 100자, 앞뒤 공백 제거.

## 성공 응답과 로그인 흐름

`201 Created`:

```json
{
  "id": 101,
  "loginId": "worker01",
  "name": "TEST WORKER",
  "role": "WORKER",
  "nationalityType": "FOREIGN"
}
```

번호·계좌·해시·비밀번호는 반환하지 않는다. 서버 발급 `id`가 소유자 식별용 ID다.

1. `GET /api/auth/csrf` → 농업 쿠키·토큰 확보
2. `POST /api/auth/signup` → JSON·동일 쿠키·CSRF 헤더 → 201
3. 기존 `POST /api/auth/login` → 폼 본문 `loginId`, `password`·동일 쿠키·CSRF 헤더 → 204
4. `GET /api/auth/me` → 로그인 세션으로 조회
5. 로그인 후 쓰기 요청 전에는 CSRF를 다시 조회한다.

가입만 하고 로그인하지 않은 익명 세션의 `/me`는 기존대로 401이다. 이미 로그인했던 세션으로 가입하더라도 새 계정으로 인증을 바꾸지 않는다.

`GET /api/auth/me`의 기존 `id`, `name`, `role`을 유지하고 다음 필드를 추가한다.

```json
{
  "id": 101,
  "name": "TEST WORKER",
  "role": "WORKER",
  "nationalityType": "FOREIGN"
}
```

기존 미이관 계정의 국적은 `null`이다. 국적은 로그인할 때 DB에서 읽어 세션에 보관하므로, 별도 이관 후 이미 로그인한 세션에는 재로그인이 필요하다. 식별번호는 반환하지 않는다.

## 저장·중복·원자성

`SignupApi` → `SignupService` → `MemberRepository` / `IdentityCrypto` → 선택 시 `SalaryAccountCrypto` / `MemberSalaryAccountRepository` 순서다.

- 유효성 검사·필수 키 확인 후 로그인 ID 중복, 새 HMAC 중복, 기존 원문 컬럼의 정규화 번호 중복을 조회한다.
- 신규 회원 ID를 발급한 뒤 `Member.assignIdentity`로 국적·종류·암호문·HMAC을 연결한다. 기존 원문 컬럼에는 신규 번호를 쓰지 않는다.
- 계좌는 회원 ID·은행 코드를 AAD로 결합한 AES-GCM 암호문으로 저장한다. 이름·은행 코드와 생성/수정 시각을 함께 기록한다.
- 하나의 `@Transactional`에서 회원 발급·식별정보 연결·선택 계좌 저장·flush를 수행한다. 중간 실패 시 이번 가입 전체를 롤백하도록 구성했다. 실패한 ID 번호의 공백은 생길 수 있으며 기존 회원이나 계좌를 삭제하지 않는다.
- 사전 조회 뒤 동시 가입은 기존 DB UNIQUE로 보호하고, 제약 이름에 따라 로그인 ID·식별번호 중복 409로 변환한다. 트랜잭션 안에서 실패한 저장을 계속 진행하지 않는다.
- 기존 LEGACY 조회 모드는 변경하지 않는다. 신규 가입 번호는 암호문/HMAC에만 저장하므로 기존 보험 V1이 신규 내국인까지 확인하려면 검토된 이관·조회 전환 후 `ENCRYPTED` 모드를 사용해야 한다. 외국인 V2 확인은 후속 API다.

## 키와 마이그레이션

필수 설정:

- 모든 신규 가입: 기존 `FARM_IDENTITY_ENCRYPTION_KEY`, `FARM_IDENTITY_HMAC_KEY` (서로 다른 32바이트 Base64 키)
- 계좌를 함께 저장할 때: `FARM_SALARY_ACCOUNT_ENCRYPTION_KEY` (별도로 생성한 32바이트 Base64 계좌 키)

키는 실제 서버의 비밀 환경 설정으로 제공하고 저장소·PR·화면·로그에 넣지 않는다. 키를 재시작마다 새로 생성하지 않는다. 이 작업에서는 실제 키나 `.env`를 만들거나 수정하지 않았다.

키를 설정하지 않아도 기존 로그인·공고 기능은 유지한다. 가입에 필요한 키가 없거나 잘못되면 503으로 거절한다. 계좌를 생략하면 계좌 키는 필요 없다.

신규 `V9__add_member_salary_account.sql`:

- `member_id` PK·FK로 회원당 최대 한 계좌
- `bank_code CHAR(3)`, `account_number_ciphertext VARCHAR(256)` (ascii_bin), `account_holder_name VARCHAR(100)`
- `created_at`, `updated_at`: DATETIME(6), 애플리케이션이 UTC로 저장
- 기존 회원 계좌를 자동으로 채우지 않음
- V1~V8과 기존 데이터는 수정하지 않음

## 실패 응답

신규 업무 오류는 `{"code":"...","message":"..."}` 형식이며 입력값·SQL·키·식별번호·계좌번호를 포함하지 않는다.

| HTTP | 코드 | 내용 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 필수값·문자열 형식·역할·국적·전화·식별번호·계좌·비밀번호 상한 오류 |
| 409 | LOGIN_ID_ALREADY_EXISTS | 로그인 ID 중복 |
| 409 | IDENTITY_ALREADY_EXISTS | 새 HMAC 또는 기존 번호 중복 |
| 503 | SERVICE_UNAVAILABLE | 필수 암호화 설정 오류·일시적 DB 장애 |
| 500 | INTERNAL_SERVER_ERROR | 기타 내부 오류. 원문 예외 비노출 |

`Content-Type`이 `application/json`이 아닌 요청(예: `application/x-www-form-urlencoded`)은 `415 Unsupported Media Type`이다. 이 응답은 컨트롤러 선택 전에 Spring 기본 처리로 만들어지므로 위 `{"code","message"}` 형식이 아니며, 요청은 가입 서비스에 도달하지 않는다. (2026-11-26 독립 검증 D-1 결정: 415 유지)

로그인·권한·CSRF 차단 응답은 기존 Security 처리를 유지한다.

## 검증 상태와 후속 작업

- farm-demo 추가 후 JDK 21·Gradle 9.9.1에서 `compileJava compileTestJava --offline --no-daemon`으로 최종 소스 컴파일이 통과했다(24초). 두 HTML의 스크립트는 `node --check`로 문법만 확인했다. 신규 기능 테스트·서버·브라우저 동작·DB 접속·V9 적용·이관·커밋·푸시는 실행하지 않았다.
- 기존 `AuthApiTest`의 응답 필드 기대값을 4개로 갱신했다.
- Flyway 기존 단언을 제거하지 않고 V9까지의 버전과 실행 개수(빈 DB 9개, V3 이후 6개)를 갱신했다. 새 계좌 컬럼과 기존 계좌 자동 등록 없음 검증을 추가했지만 실행하지 않았다.
- 가입 단위 검증은 정상 역할·국적·계좌 선택, 문자열 형식·정규화·UTF-8 상한·중복·키 실패, 응답 비노출과 트랜잭션 설정을 확인해야 한다.
- 실제 트랜잭션 롤백·동시 중복·V9 제약·Hibernate 매핑·Security/CSRF·가입 후 로그인 연결은 별도 통합 검증 대상이다. 컴파일 통과를 실행 검증으로 해석하지 않는다.
- 급여 계좌 조회·교체·해제·마스킹 응답과 보험 V2·계좌 조회, 근무 완료, 프론트 연결은 다음 기능이다.

## farm-demo 회원가입 확인 화면

`signup` 코드가 있는 작업 공간에서 서버를 `farm-demo` 프로필로 다시 실행하고 `/demo/signup`으로 접속한다. 예를 들어 포트가 18081이면 `http://localhost:18081/demo/signup`이다. 기존 `/demo/login`에서도 회원가입 화면으로 이동할 수 있고, 로그인 결과에 국적을 표시한다.

로그인·회원가입용 식별정보 키 2개와 선택 계좌용 키 1개는 앞의 서버 환경 설정으로 제공해야 한다. 기존 로그인만 확인하는 경우에는 새 암호화 키를 요구하지 않는다. 서버 실행 시 기존 Flyway 설정에 따라 V9가 적용되므로 올바른 개발 DB 설정인지 확인한다. 이번 구현 과정에서는 서버나 마이그레이션을 실행하지 않았다.

### 화면에서 확인하는 순서

1. 이미 로그인되어 있으면 화면의 로그아웃 버튼을 누른다. 현재 `/me`가 401이면 가입·로그인 폼을 사용할 수 있다.
2. 중복되지 않는 로그인 ID·가상 식별번호와 이름·전화·역할·국적을 입력한다. 계좌 없이 확인하려면 체크를 끈다.
3. 계좌도 함께 확인하려면 체크 후 숫자 3자리 은행 코드·숫자/하이픈 계좌번호·예금주명을 입력한다. 체크가 꺼진 계좌 필드는 비활성화하고 요청에 넣지 않는다.
4. 회원가입 버튼을 누른다. 페이지가 농업 CSRF를 자동으로 받은 뒤 실제 JSON 가입 요청을 보내고 201 응답을 표시한다.
5. 이후 `/me`의 401로 가입이 자동 로그인이 아님을 확인한다. 성공한 ID만 로그인 폼으로 옮기고 비밀번호는 다시 입력한다.
6. 로그인 버튼을 누르면 새 CSRF로 기존 폼 로그인 API를 호출한다. 204 뒤 실제 `/me` 200의 ID·이름·역할·nationalityType을 표시한다.
7. 데모 전용 본인 계좌 조회 결과로 DB 등록 여부·은행 코드·예금주명을 확인한다. 계좌를 생략한 가입은 registered=false가 정상이다.
8. 로그아웃 후 다른 역할·국적·계좌 포함 여부로 반복할 수 있다. 실제 DB 데이터는 재시작이나 화면 새로고침으로 초기화하지 않는다.

가입의 201과 후속 조회 실패를 분리하고, 조회 실패를 계좌 미등록으로 표시하지 않는다. 다른 탭에서 세션이 바뀌어 /me와 계좌 응답의 회원 ID가 달라지면 표시를 중단하고 재조회를 안내한다.

### 데모 전용 계좌 조회

`GET /demo/api/signup/salary-account`는 farm-demo에서만 등록되는 읽기 경로다. 고용주·근로자 본인 세션으로만 허용하며 요청의 memberId를 사용하지 않는다. DB의 계정 유효성도 확인한다. `Cache-Control: no-store`로 응답하고 계좌번호·암호문·키는 복호화하거나 반환하지 않는다.

```json
{
  "memberId": 101,
  "registered": true,
  "bankCode": "020",
  "accountHolderName": "TEST WORKER"
}
```

미등록은 registered=false, bankCode/accountHolderName=null이다. 이 경로는 본인 저장 여부를 보기 위한 데모이며 후속 `/api/me/salary-account` 관리 API나 보험용 계좌 API를 구현한 것은 아니다.

페이지는 세션 쿠키를 같은 출처로 전송하고 POST마다 CSRF를 자동으로 발급받는다. 브라우저 저장소에 회원·비밀번호를 저장하거나 클라이언트에서 인증하지 않는다. 기록에는 응답 상태코드·안전한 응답만 표시하고 입력 본문·CSRF 토큰을 기록하지 않는다. DOM 출력에는 textContent를 사용한다.

## 파일을 따라가는 요청 흐름

1. `farm-signup.html`이 JSON 요청과 CSRF 헤더를 만든다. `SecurityConfig`가 기존 CSRF 검사를 적용한다.
2. `SignupApi`가 요청을 받고 `SignupRequest`/`SignupSalaryAccountRequest`가 문자열 형식·필수값을 검사한다.
3. `SignupService`가 정규화·키·중복을 확인하고 같은 트랜잭션에서 처리한다.
4. `MemberRepository`가 회원 ID를 발급한다. `IdentityCrypto`와 `Member.assignIdentity`가 식별정보를 암호화/HMAC으로 연결한다.
5. 계좌가 있으면 `SalaryAccountValidator` → `SalaryAccountCrypto` → `MemberSalaryAccountRepository`가 정규화·암호화·저장을 처리한다.
6. flush와 트랜잭션 완료 후 `SignupResponse`로 201을 반환한다. 입력·중복·설정 오류는 `SignupErrorHandler`가 원문을 숨긴 응답으로 바꾼다.
7. 별도 로그인 요청은 기존 `SecurityConfig`의 로그인 필터 → `FarmUserDetailsService` → `MemberRepository` → BCrypt 검증을 거친다. 성공하면 국적을 포함한 `FarmPrincipal`을 세션에 보관한다.
8. `AuthApi`의 /me는 이 세션에서 국적을 반환한다. `DemoSignupAccountApi`는 같은 본인 ID로 계좌 저장 행을 읽고 허용한 필드만 화면에 반환한다.
## 2026-11-26 데모 입력 화면 개선

이번 변경은 원래 `C:\Users\HCB\Desktop\wonday`의 signup 브랜치에서 데모 입력 화면만 수정한다. API 요청·응답, V9, 암호화 저장, 서버의 계좌 숫자 1~30자리 정책은 그대로다.

- 주민번호·외국인등록번호를 앞 6자리, 고정 하이픈, 뒤 첫 1자리, 나머지 6자리의 세 입력으로 나눈다. 마지막 6자리만 password 입력으로 가린다.
- 완성된 표시 예는 `900101-5••••••`다. 각 칸을 합친 실제 13자리를 기존 identityNumber에 보내며 별표·불릿을 서버에 보내지 않는다.
- 전체 13자리를 붙여넣으면 분리하고, 길이가 맞지 않는 긴 값을 조용히 잘라 넣지 않는다. 국적은 기존 명시적 선택을 유지하며 번호로 추측하지 않는다.
- 계좌 입력은 text로 바꿔 입력 중 숫자 전체를 보여준다. 서버 응답·요청 기록에는 번호를 추가하지 않는다.
- 은행은 이름으로 선택하고 기존 숫자 3자리 코드를 전송한다. 목록 밖 금융기관도 기타 직접 입력으로 허용하며 서버 허용 목록을 새로 만들지 않는다.
- 사용자 합의: 대표 형식을 안내하고 확인된 형식만 자동 표시하되 서버는 기존 1~30자리 유지.
- 대표 표시 예시: 우리은행 4-3-6, 신한은행 3-3-6 및 구계좌 3-2-6, KB국민은행 6-2-6 및 구계좌 3-2-4-3. 입력 중에는 대표 묶음 경계에 하이픈을 자동 표시하고, 입력칸 이탈 시 길이가 맞는 구계좌 예시도 선택한다. 자동 표시 체크를 끄면 적용하지 않는다. 대표 예시보다 긴 숫자도 버리지 않는다.
- 자동 표시는 숫자 순서와 앞자리 0을 바꾸지 않는다. 형식이 미확인인 은행·다른 상품·다른 길이는 기존 입력 그대로 허용한다. 문자 오류나 너무 긴 숫자를 삭제·절단해서 정상 입력처럼 만들지 않는다.
- 은행을 선택하기 전에는 계좌번호와 자동 표시 체크를 비활성화한다. 기타 금융기관은 숫자 3자리 코드를 입력해야 활성화한다.
- 입력 중 중간 수정 시 커서 앞 숫자 개수를 유지한다. 하이픈 인접 Backspace/Delete도 처리한다.
- 30자리 초과나 잘못된 문자는 임의로 자르지 않고 안내 및 브라우저 유효성 오류로 가입 제출을 막는다. 은행 대표 예시와 다른 길이라는 이유만으로는 거절하지 않는다.
- 저장된 계좌 조회 결과의 은행 코드도 해당 은행 이름과 함께 표시한다.

대표 표시는 전체 계좌의 유효성 검증이나 실제 계좌 확인이 아니다. 아래 공개 자료의 코드·표시 예시만 사용했고 상품별 모든 규격을 추정하지 않았다.

- [금융결제원 CMS 자료의 금융기관 코드](https://www.cmsedi.or.kr/resources-web/doc/CMS_Service_Information.pdf)
- [KICC 개발자 센터 금융기관 코드](https://docs.kicc.co.kr/en/docs/support/common-codes/) — iM뱅크 등 표시 이름 대조
- [우리은행 공식 공개 표기](https://spot.wooribank.com/pot/jcc?__ID=c042750&withyou=CQCCS0088) — 마스킹된 계좌의 4-3-6 표시만 참고. 개인별 정보는 코드·문서에 옮기지 않음
- [신한은행 BizBANK 변경 안내](https://img.shinhan.com/cib/ko/data/BizBANK_03.pdf) — 구계좌 3-2-6, 신계좌 3-3-6 예시. 과거 자료이므로 전체 현행 규격으로 강제하지 않음

확인 방법: signup 서버를 다시 실행한 뒤 `/demo/signup`에서 강력 새로고침한다. 가상 식별번호 앞 6자리와 뒤 첫 자리만 보이는지, 계좌가 전체 표시되는지 확인한다. 우리은행에서 가상 숫자 13자리를 입력 후 다른 칸으로 이동하면 4-3-6으로 보이고, 신한은행에서는 12자리/11자리에 각 대표 형식이 보인다. 다른 길이를 임의로 자르지 않는지, 기타 코드 입력과 계좌 생략도 유지되는지 확인한다.

정적 확인: 스크립트의 `node --check` 통과. 실제 브라우저·단위·통합 테스트와 서버·DB 실행, 커밋·푸시는 수행하지 않았다. 기존 수동 회원가입 성공은 이번 UI 수정 전 확인 결과다.

### KB 표시 예시 추가 근거와 후속 확인

- [KB국민은행 급여이체 사용자 가이드](https://img2.kbstar.com/obj/obiz/bulk_payment_transfer_guide_201909.pdf)의 구계좌 표기 예시에서 3-2-4-3 묶음을 확인했다.
- [국세청 공개 자료](https://taxlaw.nts.go.kr/pd/USEPDA002P.do?ntstDcmId=200000000000021375)의 KB국민은행 마스킹 표기에서 6-2-6 묶음을 확인했다. 표기 형태만 사용했고 개인 사건·거래 내용은 코드나 안내에 옮기지 않았다.
- 둘 다 표시 예시의 근거이며 전체 현행 계좌 규격 목록이나 계좌 검증 근거로 사용하지 않는다. 사용자 합의대로 서버 숫자 1~30자리 정책은 유지한다.
- 후속 수동 확인: 은행 미선택 시 계좌 입력 잠김, 기타 코드 3자리 입력 시 열림, KB국민 선택 후 숫자 입력 중 하이픈 표시, 중간 수정·삭제·붙여넣기, 30자리 초과 안내를 확인한다. 이번 작업에서는 JavaScript 문법만 확인하고 실행 테스트·브라우저 조작·커밋·푸시는 하지 않았다.
