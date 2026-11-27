# 보험 서버용 근로자 급여 계좌 등록 상태 조회

## 범위

- 원래 C:\Users\HCB\Desktop\wonday의 최신 origin/main bc85bb9에서 insurance-salary-account 브랜치를 생성했다.
- 기존 서버 토큰 인증·V1/V2 확인·변경 목록·약정 상세·근로자 본인 계좌 관리 기능을 유지한다.
- 보험 서버에는 workerId·등록 여부·은행 코드만 제공한다.
- 등록 여부는 저장된 계좌 행의 존재 여부다. 실계좌·예금주·은행 실재 여부를 확인하지 않는다.
- 보험의 할인·리워드·보험금 계산은 보험 서버가 담당한다. 연동 동의도 보험 서버가 관리한다.
- 신규 DB 마이그레이션·이관·제품 화면·계좌 복호화·키 생성·보험 서버 코드 수정은 없다. 개인 확인용 farm-demo 화면은 별도로 제공한다.

## 요청

GET /api/internal/insurance/workers/{workerId}/salary-account

헤더:
Authorization: Bearer {FARM_API_SERVICE_TOKEN}
Accept: application/json

workerId는 농업 플랫폼 회원 ID인 양의 정수다.
농업 앱 로그인 세션·CSRF는 사용하지 않는다. 쿠키만으로 내부 API에 접근할 수 없다.
기존 farm.insurance.service-token 설정과 INSURANCE_SERVICE 권한을 그대로 사용한다.
실제 토큰은 코드·문서·PR·로그에 넣지 않는다.

## 성공 응답

계좌가 등록된 근로자 — 200 OK:

```json
{
  "workerId": 101,
  "registered": true,
  "bankCode": "020"
}
```

계좌가 미등록인 근로자 — 200 OK:

```json
{
  "workerId": 101,
  "registered": false,
  "bankCode": null
}
```

Cache-Control: no-store를 사용한다.
계좌번호·마스킹 번호·암호문·예금주명·계좌 버전·잔액·거래 내역은 반환하지 않는다.
계좌 변경·해제 후 다음 조회는 해당 조회 SQL 시점에 커밋된 상태를 읽는다. 미등록은 404가 아니다.

## 오류 응답

```json
{
  "code": "WORKER_NOT_FOUND",
  "message": "해당 근로자를 찾을 수 없습니다."
}
```

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 숫자가 아닌 ID·Long 범위 초과·0·음수 |
| 401 | INVALID_SERVICE_TOKEN | 기존 서버 토큰 누락·오류·미설정 |
| 403 | SERVICE_ACCESS_DENIED | 기존 내부 보안 체인의 권한 거절 |
| 404 | WORKER_NOT_FOUND | 회원 없음 또는 WORKER 역할이 아님 |
| 503 | SERVICE_UNAVAILABLE | 일시적 DB 장애·일관되지 않은 조회 결과·잘못된 저장 은행 코드 |
| 500 | INTERNAL_SERVER_ERROR | 기타 내부 오류 |

고용주는 계좌가 있어도 404다. DB 역할의 소문자·알 수 없는 값·null도 근로자로 보지 않는다.
기존 ServiceTokenFilter가 오류 응답에도 no-store를 붙이고 InsuranceErrorHandler가 업무·DB 예외를 변환한다.
조회 예외 원문·SQL·개인정보를 응답하지 않는다.

## 처리 흐름

ServiceTokenFilter / InsuranceSecurityConfig
→ InsuranceSalaryAccountApi
→ WorkerSalaryAccountQueryService
→ WorkerSalaryAccountRepository
→ WorkerSalaryAccountResponse

- 보험용 전용 읽기 Repository의 JPA native query로 단일 SELECT를 실행한다.
- member의 ID·역할과 LEFT JOIN한 계좌의 회원 ID·은행 코드만 projection으로 가져온다.
- 회원 역할 확인과 계좌 상태를 같은 SELECT로 읽어 별도 조회 사이의 상태 차이를 줄인다.
- 계좌 암호문·식별정보·예금주는 DB에서 조회하지 않는다.
- 계좌가 없으면 registered=false, 있으면 은행 코드 형식을 확인해 registered=true로 응답한다.
- 기존 SalaryAccountService·SalaryAccountCrypto를 호출하지 않으므로 계좌 복호화 키가 필요하지 않다.
- 읽기 전용 트랜잭션이며 계좌나 회원 버전을 변경하지 않는다.
- 실제 MySQL 쿼리·projection·동시 변경 상황은 아직 실행 검증하지 않았다.

## 수동 확인 방법

1. 서버를 이 브랜치 코드로 실행하고 기존 FARM_API_SERVICE_TOKEN을 사용한다.
2. Apidog에서 GET http://127.0.0.1:18081/api/internal/insurance/workers/{workerId}/salary-account 요청을 만든다.
3. Authorization은 Bearer 토큰 한 개만 설정한다. 로그인·CSRF는 필요 없다.
4. 등록된 가상 근로자 ID로 200·registered=true·bankCode를 확인한다.
5. 근로자 본인 계좌 관리 API에서 계좌를 교체한 뒤 다시 GET해 은행 코드 변경을 확인한다.
6. 본인 계좌를 해제한 뒤 GET하면 200·registered=false·bankCode=null이어야 한다.
7. 없는 회원·고용주 ID는 404, ID 0·음수·잘못된 숫자는 400을 확인한다.
8. 토큰을 제거하거나 틀리게 보내면 401을 확인한다.
9. 모든 응답에서 no-store와 개인정보 비노출을 확인한다.

실제 workerId·토큰·계좌는 이 문서에 넣지 않는다. 포트는 실제 실행 설정에 맞춘다.
이 절은 수동 확인 절차이며 실행 성공 기록은 아니다.

## 검증 상태

2026-11-27 JDK 21·Gradle 9.9.1에서 compileJava compileTestJava --offline --no-daemon 통과: BUILD SUCCESSFUL, 21초. 테스트 태스크는 실행하지 않았다.
신규·기존 테스트, 서버·DB·실제 HTTP·마이그레이션·커밋·푸시는 실행하지 않는다.
후속 단위 검증은 등록·미등록·없는 회원·고용주·ID 오류·projection 인자·최소 응답·예외 변환을 포함한다.
실제 MySQL LEFT JOIN·projection·변경 후 조회, 서버 토큰 체인·세션 격리·CSRF 경로는 후속 통합 검증 대상이다.

## farm-demo 본인 조회 화면

- 화면: GET /demo/insurance-salary-account
- 데모 조회: GET /demo/api/insurance/salary-account
- 두 경로의 컨트롤러는 farm-demo에서만 등록된다.
- 조회는 근로자 세션을 요구하고 요청의 workerId 대신 FarmPrincipal.memberId를 사용한다.
- WorkerSalaryAccountQueryService를 그대로 호출하므로 현재 DB 역할과 실제 저장 상태를 읽는다.
- 별도 회원 ID·토큰 입력 칸이 없고 서버 전용 토큰을 HTML·JavaScript·응답에 넣지 않는다.
- GET 읽기 요청이므로 데모 조회에 CSRF를 요구하는 쓰기 작업은 없다. 계좌 변경은 기존 관리 화면의 CSRF 보호를 그대로 사용한다.
- 내부 보험 API의 Bearer 필터를 통과하는 데모가 아니다. 실제 토큰 인증·보험 서버 상호 호출은 별도 검증 대상이다.
- 정상·업무 오류 응답에는 no-store를 적용하고 오류 원문을 반환하지 않는다.

### 화면 흐름

로그인 화면의 근로자 링크
→ farm-insurance-salary-account.html
→ 데모용 근로자 세션 경로
→ WorkerSalaryAccountQueryService
→ WorkerSalaryAccountRepository
→ WorkerSalaryAccountResponse

실제 보험 서버의 흐름은 별도다.

보험 서버
→ ServiceTokenFilter / InsuranceSecurityConfig
→ InsuranceSalaryAccountApi
→ 같은 조회 서비스·Repository·응답

### 수동 확인

1. 원래 wonday/farm-api를 기존 .env와 farm-demo 프로필로 재시작한다.
2. localhost:18081/demo/login에서 근로자로 로그인하고 보험용 계좌 등록 여부 링크를 누른다.
3. localhost:18081/demo/insurance-salary-account에서 조회 버튼을 눌러 200·workerId·registered·bankCode를 확인한다.
4. 계좌 관리 링크는 기존 /demo/salary-account를 새 창으로 연다.
5. 가상 계좌 등록 후 보험 조회 창에서 다시 조회해 registered=true를 확인한다.
6. 다른 은행으로 교체하고 다시 조회해 은행 코드 변경을 확인한다.
7. 계좌 해제 후 다시 조회하면 200·registered=false·bankCode=null이다.
8. 계좌 관리 화면에서 바꾼 뒤 보험 조회 화면은 버튼으로 직접 새로 조회한다.
9. 같은 은행 안에서 계좌번호만 바꾸면 제공되는 세 필드는 같을 수 있다. 번호·예금주·버전을 추가 노출하지 않는다.
10. 다른 계정으로 로그인했거나 페이지에서 이탈하면 기존 표시를 정리한다. 조회 중 계정이 바뀌면 결과를 표시하지 않는다.

화면은 허용한 세 필드만 textContent로 표시한다. 오류를 미등록 성공으로 바꾸지 않으며, 중복 조회·페이지 이탈 후 응답 표시도 막는다.
이전 조회와 이번 조회의 등록 상태·은행 코드만 메모리에서 비교한다. 브라우저 저장소·URL·로그에는 남기지 않는다.
실제 계좌 편집에는 기존 계좌 암호화 키가 필요하지만 이 보험용 상태 조회 서비스 자체는 복호화하지 않는다.
이 절은 수동 확인 방법이며 실행 성공 기록은 아니다.


데모 추가 후 정적 확인(2026-11-27): compileJava compileTestJava --offline --no-daemon 통과(BUILD SUCCESSFUL, 15초). 새 보험용 계좌 화면과 로그인 화면의 인라인 JavaScript는 node --check 문법 확인 통과. 테스트·브라우저·실제 HTTP·서버·DB·커밋·푸시는 실행하지 않았다.
