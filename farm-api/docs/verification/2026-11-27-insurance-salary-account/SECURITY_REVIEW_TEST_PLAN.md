# 보험용 계좌 조회 PR 보안 리뷰 보완 — 실행 대기

## 리뷰 판단

- 지적은 실제 Spring Security 체인을 거치는 통합 검증 누락이다. 운영 코드의 권한 우회가 재현됐다는 판정은 아니다.
- 기존 138개 단위 테스트·Node 11개 결과는 그대로 보존한다.
- standalone MockMvc·필터 단위 호출을 실제 체인 검증으로 바꾸어 표현하지 않는다.
- 사용자 지시에 따라 테스트를 작성하고 소스 컴파일까지만 확인한다. 테스트·서버·Docker·DB·커밋·푸시는 실행하지 않는다.
- 기본 단위 테스트·CI·커버리지 설정은 변경하지 않는다. 새 테스트는 integration 태그로 별도 선택 실행한다.

## 추가 파일

- insurance/security/InsuranceSalaryAccountHttpSupport.java: 실제 로그인·HTTP·격리 DB fixture 공통 지원
- insurance/security/InsuranceSalaryAccountSecurityHttpTests.java: farm-demo 활성, 정상 서버 토큰과 앱 세션 경계
- insurance/security/InsuranceSalaryAccountNoTokenHttpTests.java: 서버 토큰 미설정, farm-demo 비활성 경계

모든 경로는 farm-api/src/test/java/com/wonday/farm 아래다.
@SpringBootTest(RANDOM_PORT)의 실제 애플리케이션을 사용하며, SecurityConfig·InsuranceSecurityConfig·ServiceTokenFilter·로그인 필터를 mock하거나 우회하지 않는다.
기존 FarmAuthClient로 CSRF를 발급받고 실제 폼 로그인을 거쳐 FARMSESSION 쿠키를 얻는다.
쿠키만 있는 내부 요청은 별도 JDK HttpClient에 Cookie 헤더로 재전송한다.
principal 직접 주입이나 standaloneSetup은 사용하지 않는다. Repository·조회 서비스도 mock하지 않는다.

## 작성한 검증 시나리오

- 정확한 /api/internal/insurance/workers/{workerId}/salary-account 요청
- 유효 Bearer의 등록·미등록 200과 정확한 세 응답 필드
- 정상 내부 호출이 FARMSESSION을 만들지 않음
- 토큰 누락·오류·미설정(빈 설정) 401, INVALID_SERVICE_TOKEN, no-store
- 실제 근로자·고용주 로그인 쿠키만 보낸 내부 요청 401
- 내부 요청의 거절 뒤에도 기존 농업 앱 세션은 유지됨
- 서버 토큰만으로 /api/auth/me·본인 계좌 관리·demo 조회 세션 인증을 대신할 수 없음
- 유효 Bearer라도 고용주 계좌는 404
- demo 익명 조회 401, 고용주 조회 403
- 근로자 demo 조회는 다른 workerId·memberId 쿼리를 넣어도 본인 정보만 제공
- farm-demo 활성 시 공개 HTML 200 및 토큰 비노출
- farm-demo 비활성 컨텍스트에는 데모 컨트롤러가 없고, 실제 페이지·조회 요청도 차단됨
- 성공·401·403·404 응답의 no-store 및 민감 응답 필드 비노출

## DB·설정 격리

- 기존 MySqlTestDatabase의 Testcontainers MySQL 8.4를 사용한다.
- spring.config.import를 빈 값으로 덮어써 실제 .env 파일을 읽지 않는다.
- DataSource의 기본 대체 URL은 개발 DB 대신 127.0.0.1:1의 차단용 주소다.
- @ServiceConnection이 실제 연결 정보를 제공하며 fixture 생성 전 URL이 컨테이너의 임의 포트인지 확인한다.
- 실제 키 대신 비어 있는 암호화 설정을 사용한다. 이 조회 API는 복호화하지 않으므로 키가 필요하지 않다.
- 실제 사용자 대신 UUID 로그인 ID·가상 예금주·테스트용 암호문 fixture를 사용한다.
- fixture 계좌는 조회 전용 테스트를 위한 값이며 계좌 복호화·실계좌 유효성 검증용이 아니다.
- 각 테스트가 만든 계좌·회원만 삭제한다. 공용 개발 DB·전체 테이블 삭제·실제 이관은 사용하지 않는다.

## 이후 독립 재검증

Docker가 실행된 환경에서 farm-api 디렉터리에서 아래 두 클래스만 선택 실행한다.

```powershell
.\gradlew.bat --no-daemon integrationTest --tests "com.wonday.farm.insurance.security.InsuranceSalaryAccountSecurityHttpTests" --tests "com.wonday.farm.insurance.security.InsuranceSalaryAccountNoTokenHttpTests"
```

- 전체 test·unitTest·integrationTest·fullTest를 무선택 실행하지 않는다.
- 별도 bootRun·개발 DB·이관 도구·마이그레이션 명령은 실행하지 않는다.
- 테스트 실행 중에는 프레임워크가 임의 포트의 테스트 서버와 격리 MySQL을 띄우고, 그 DB에 Flyway를 적용한다.
- Docker·컨테이너·컨텍스트 준비 실패를 성공이나 무시된 테스트로 취급하지 않는다.
- XML/HTML과 실행 수·성공·실패·오류·건너뜀, 실제 선택 범위와 설정 보존 여부를 보고한다.
- 실패를 숨기려고 assertion·권한·태그·CI를 바꾸지 않는다.
- 실행 결과 확보 전에는 리뷰를 검증 완료로 답변하지 않는다.

이 문서는 테스트 추가 계획과 코드 범위를 기록한 것이며, 통합 테스트 통과 보고서가 아니다.


## 작성 후 컴파일 확인

2026-11-27 JDK 21·Gradle 9.9.1에서 compileTestJava --offline --no-daemon 통과: BUILD SUCCESSFUL, 18초. 실행된 것은 소스 컴파일이며 통합 테스트·테스트 서버·Docker·DB는 실행하지 않았다. 통과/실패 테스트 수치는 아직 없다.
