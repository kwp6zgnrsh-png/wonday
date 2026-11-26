# 보험 V2 근로자 등록정보 확인 API

## 범위

- 최신 main dac094e(보험 V2 호출 준비 PR #21 포함)에서 별도 worker-verification-v2 브랜치로 구현했다.
- signup 커밋 432477f는 signup 브랜치에 보존했다. 테스트 종료 후 V2 변경 9개를 해시 검증해 원래 C:\Users\HCB\Desktop\wonday의 worker-verification-v2 브랜치로 옮겼다.
- 기존 V1 경로·요청·응답·서비스·LEGACY/ENCRYPTED 전환 설정을 유지한다.
- 동의는 보험 서버가 관리한다. 정부기관 인증이 아니라 농업 플랫폼 등록 정보 대조다.
- 새 마이그레이션·데이터 이관·회원가입·계좌·user-web 제품 화면은 추가하지 않는다. farm-demo 전용 본인 확인 화면은 아래와 같이 별도로 제공한다.

## 요청

`POST /api/internal/insurance/v2/worker-verifications`

기존 `Authorization: Bearer {token}`과 `Content-Type: application/json`을 사용한다.

```json
{
  "name": "TEST WORKER",
  "phone": "010-1234-5678",
  "nationalityType": "FOREIGN",
  "identityNumber": "900101-5000000"
}
```

모두 가상 예시다. 내국인은 nationalityType을 DOMESTIC으로 보낸다.

- 네 필드는 모두 필수 JSON 문자열이다. 숫자·boolean·배열·객체를 문자열로 자동 변환하지 않는다.
- 이름은 원문 최대 100자, 앞뒤 공백 제거, 빈 값·제어 문자 거절. 번역·대소문자 변환은 하지 않는다.
- 전화는 원문 최대 100자, 공백·하이픈 제거 후 ASCII 숫자 10~11자리다.
- 국적은 정확히 DOMESTIC 또는 FOREIGN이다. 번호에서 국적을 추측하지 않는다.
- 식별번호는 원문 최대 100자, 공백·하이픈 제거 후 ASCII 숫자 13자리다. 체크섬·진위는 검사하지 않는다.
- 번호 종류는 국적에 따라 서버가 결정한다. V1의 residentRegistrationNumber만 보내면 V2의 identityNumber가 없으므로 400이다.

## 처리 흐름

1. 기존 ServiceTokenFilter·InsuranceSecurityConfig가 서버 토큰만 인증한다. 브라우저 세션·CSRF를 사용하지 않는다.
2. InsuranceWorkerVerificationV2Api가 WorkerVerificationV2Request를 검증한다.
3. WorkerVerificationV2Service가 정규화·형식을 재확인하고 IdentityCrypto로 HMAC을 계산한다.
4. WorkerVerificationRepository가 WORKER·국적·번호 종류·HMAC·이름·전화를 모두 대조한다.
5. 저장된 암호문이 존재하고 국적이 명시된 계정만 최대 2건 조회한다.
6. 실제 조회된 회원 ID·정규화 이름/전화·국적을 반환한다.

V2는 V1의 FARM_IDENTITY_LOOKUP_MODE와 독립적으로 항상 HMAC 저장 구조를 사용한다. 원문 컬럼을 검색하거나 V1으로 fallback하지 않는다. 정보가 없는 기존 계정은 성공으로 처리하지 않으며, 확인된 이관 또는 후속 가입 데이터가 필요하다. 이번 작업에서는 실제 이관·데이터 입력을 하지 않았다.

## 응답

`200 OK`, `Cache-Control: no-store`:

```json
{
  "workerId": 101,
  "name": "TEST WORKER",
  "phone": "01012345678",
  "nationalityType": "FOREIGN"
}
```

workerId는 농업 회원 ID인 JSON 숫자다. 식별번호·번호 종류·암호문·HMAC·항목별 일치 여부는 반환하지 않는다. 요청과 응답의 toString도 개인정보를 가린다.

기존 InsuranceErrorHandler가 같은 컨트롤러 패키지의 V2에도 적용된다. 오류는 code/message 형태이며 토큰 필터의 no-store 정책도 유지한다.

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 필수값·문자열·국적·번호 등 입력 오류 |
| 401 | INVALID_SERVICE_TOKEN | 서버 토큰 누락·오류·미설정 |
| 403 | SERVICE_ACCESS_DENIED | API 호출 권한 없음 |
| 404 | WORKER_IDENTITY_NOT_MATCHED | 일치하는 근로자 없음. 고용주·미이관 정보 포함 |
| 409 | WORKER_IDENTITY_AMBIGUOUS | 복수 일치 |
| 503 | SERVICE_UNAVAILABLE | 키 설정 불가·일시적 DB 장애·조회 결과 해석 불가 |
| 500 | INTERNAL_SERVER_ERROR | 기타 내부 오류 |

V8 UNIQUE가 정상 적용된 데이터에서는 복수 HMAC 일치가 제한되지만 계약의 409 방어 분기는 유지한다. 개인정보나 원문 예외를 오류에 붙이지 않는다.

## 설정과 보험 팀 전달 사항

농업 서버는 기존 FARM_API_SERVICE_TOKEN과 기존 FARM_IDENTITY_ENCRYPTION_KEY/FARM_IDENTITY_HMAC_KEY를 사용한다. 기존 IdentityCrypto 설정 정책에 따라 두 키는 서로 다른 32바이트 Base64 값이어야 하며 저장 데이터를 만든 값과 같아야 한다. 실제 키는 코드·PR·보고서·브라우저에 넣지 않는다.

보험 서버는 기존 FARM_API_BASE_URL과 같은 FARM_API_SERVICE_TOKEN을 사용한다. 농업 V2 배포·상호 호출 검증 후 보험 서버의 FARM_WORKER_VERIFICATION_V2_ENABLED=true로 전환한다. 최신 main의 보험 클라이언트는 이 경로·네 요청/응답 필드와 맞으며 V2 오류 시 V1으로 자동 재전송하지 않는다. 보험 코드나 설정은 이번에 변경하지 않았다.

후속 공유 자료는 접속 주소·설정 방법·정보가 갖춰진 가상 내국인/외국인 workerId·성공/불일치 사례다. 실제 키는 별도 보안 경로로 전달한다. 아직 서버·DB를 실행하지 않았으므로 workerId나 연동 성공 사례를 임의로 만들지 않는다.

## 검증 상태

- JDK 21·Gradle 9.9.1의 compileJava compileTestJava --offline --no-daemon 통과: BUILD SUCCESSFUL, 36초.
- 신규·기존 테스트 실행, 서버, DB, 이관, 보험 서버 상호 호출, 커밋·푸시는 하지 않았다.
- 후속 단위 검증: 양쪽 국적·정규화·문자열 강제·잘못된 값·키 오류·조회 인자·0/1/2건·응답/예외 비노출·V1 fallback 없음·매핑·no-store.
- 실제 토큰 체인·세션 격리·Spring 바인딩·MySQL regexp/cast·projection·보험 클라이언트 상호 호출은 후속 통합 검증 대상이다.
- 기존 로그인·공고·좌표·회원 식별정보 테스트 결과를 이 API 성공 근거로 사용하지 않는다.

## farm-demo 간이 확인 (2026-11-26 추가)

- 원래 wonday의 farm-api를 기존 .env와 farm-demo 프로필로 실행한다.
- GET /demo/worker-verification 또는 /demo/login의 보험 V2 링크를 연다.
- 국적·암호화 식별정보가 저장된 근로자로 로그인한다. signup에서 가입한 가상 계정을 사용할 수 있다.
- 가입 당시 이름·전화·국적·가상 번호를 입력하고 정상 확인을 누르면 200과 본인 workerId를 확인할 수 있다.
- 불일치 예시 버튼은 전송할 전화번호의 마지막 숫자만 바꿔 404를 확인한다.
- 입력 오류 예시 버튼은 식별번호를 12자리로 보내 400을 확인한다. 폼의 원래 입력값은 유지한다.
- 화면에 HTTP 상태와 허용된 응답 필드를 표시한다. 원문 번호·요청 본문·CSRF·서버 토큰은 출력하거나 브라우저 저장소에 넣지 않는다.

화면의 POST /demo/api/insurance/v2/worker-verifications는 farm-demo에서만 등록된다. 근로자 세션·CSRF를 요구하고 DB의 현재 역할 및 성공 결과의 본인 소유를 검사한다. 다른 근로자가 일치해도 404로 응답한다. 내부 API와 같은 WorkerVerificationV2Service를 호출하지만 내부 Bearer 토큰 필터 자체를 통과하는 검증은 아니다. 토큰 인증·보험 서버 상호 호출은 Apidog 또는 후속 연동 검증으로 별도 확인한다. 실제 내부 API의 보안 체인은 변경하지 않는다.

signup 화면·API는 아직 별도 signup 브랜치의 기능이다. V2 브랜치에서 새로 가입하는 기능을 복제하지 않고, 같은 로컬 DB에 이미 가입한 가상 근로자를 사용한다. 기존 demo-worker의 누락된 식별정보를 자동 보완하지 않는다. 식별정보 키는 가입 때 사용한 기존 값을 유지한다.

이번 화면 추가 후 테스트·서버·DB는 실행하지 않는다. 별도 단위 검증과 사용자 수동 확인이 필요하다.

이동·화면 추가 후 정적 확인: 원래 wonday/farm-api에서 compileJava compileTestJava --offline --no-daemon 통과(BUILD SUCCESSFUL, 30초). 새 화면의 인라인 JavaScript는 node --check로 문법 확인 통과. 테스트·브라우저·서버·DB·실제 HTTP·커밋·푸시는 실행하지 않았다. 이전 worktree의 이전 변경 9개는 백업과 해시 대조 후 정리했으며, Codex 기본 작업 공간 보호 때문에 폴더 자체는 남아 있다.
