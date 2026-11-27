# 보험용 급여 계좌 조회 — 보안 리뷰 보완 통합 테스트 재검증 결과 (2026-11-27)

## 1. 요약

- 작성된 두 통합 테스트 클래스를 **수정 없이** 실행했어요. **15개를 실행해 15개 통과**했고, 실패·오류·건너뜀은 0개예요.
  - `InsuranceSalaryAccountSecurityHttpTests`: 13개
  - `InsuranceSalaryAccountNoTokenHttpTests`: 2개
- 실행 환경:
  - `@SpringBootTest(RANDOM_PORT)`로 띄운 실제 애플리케이션과 실제 Spring Security 체인
  - 실제 폼 로그인과 실제 HTTP 요청
  - Testcontainers의 격리 MySQL 8.4와, 그 안에 자동 적용된 Flyway(V1~V10)
- 운영 코드 결함·테스트 오류·환경 문제는 없었어요.
- 운영 코드·보안 설정·Gradle·CI·테스트 코드·기존 보고서는 수정하지 않았어요.
- 아래 결과와 이번 15개를 합산하지 않았어요.
  - 기존 단위 테스트 138개
  - node 화면 테스트 11개
  - 사용자 수동 확인

## 2. 작업 위치

| 항목 | 값 |
|---|---|
| 경로 | `C:\Users\HCB\Desktop\wonday` (`farm-api`에서 실행) |
| 브랜치 | `insurance-salary-account` (변경·병합·리베이스 없음) |
| 기준 커밋 | `4d06256` |
| 대상 변경 | 미추적 테스트 3개(`InsuranceSalaryAccountHttpSupport`, `…SecurityHttpTests`, `…NoTokenHttpTests`)와 `SECURITY_REVIEW_TEST_PLAN.md` |
| AGENTS.md | 저장소 루트·`farm-api` 모두 없음 |
| `.codex` worktree | 사용하지 않음 |

실행 전에 다음 파일을 읽었어요.
- 검증 계획 `SECURITY_REVIEW_TEST_PLAN.md`
- 공통 지원 코드와 두 테스트 클래스
- `MySqlTestDatabase`
- `build.gradle`의 `integrationTest` 설정
  - `integration` 태그만 포함하고 `known-defect`는 제외해요.
  - 커버리지 finalizer가 없어요.

## 3. 실행 명령·시간

```
# farm-api, JAVA_HOME=C:\Users\HCB\Desktop\woori-workspace\00.sw\jdk-21.0.12.1+1
./gradlew.bat --no-daemon integrationTest --tests "com.wonday.farm.insurance.security.InsuranceSalaryAccountSecurityHttpTests" --tests "com.wonday.farm.insurance.security.InsuranceSalaryAccountNoTokenHttpTests"
```

| 클래스 | 실행 | 통과 | 실패 | 오류 | 건너뜀 | XML time |
|---|---|---|---|---|---|---|
| `InsuranceSalaryAccountSecurityHttpTests` | 13 | 13 | 0 | 0 | 0 | 3.420s |
| `InsuranceSalaryAccountNoTokenHttpTests` | 2 | 2 | 0 | 0 | 0 | 28.025s (컨테이너 기동 포함) |
| **합계** | **15** | **15** | 0 | 0 | 0 | BUILD SUCCESSFUL in 46s (전체 약 47초) |

- 실행하지 않은 것:
  - 전체 `test`·`unitTest`·`integrationTest`·`fullTest`·`build`·`check`
  - 커버리지 리포트·커버리지 검사
  - 별도 `bootRun`, 수동 마이그레이션, `migrateMemberIdentity`, 데이터 이관

### 두 클래스만 실행됐다는 근거 (결과 XML)

- `build/test-results/integrationTest/`를 실행 전에 비웠어요. 실행 후 그 안에는 다음 두 파일만 있어요.
  - `TEST-com.wonday.farm.insurance.security.InsuranceSalaryAccountNoTokenHttpTests.xml`
  - `TEST-com.wonday.farm.insurance.security.InsuranceSalaryAccountSecurityHttpTests.xml`
- testcase의 classname은 두 클래스뿐이에요(13 + 2).
- 매개변수 테스트도 모두 실행됐어요.
  - `realFarmLoginCookieCannotAuthenticateInternalApi`: `[1] role = EMPLOYER`, `[2] role = WORKER`
  - `missingOrWrongBearerIsRejectedByActualChain`: `[1] ""`, `[2] "Bearer wrong-service-token"`
  - `serviceTokenCannotReplaceFarmSession`: `/api/auth/me`, `/api/me/salary-account`, `/demo/api/insurance/salary-account`

## 4. 실제 체인·HTTP·격리 DB 사용 근거 (XML system-out 로그)

- **격리 MySQL:**
  - `Creating container for image: mysql:8.4`(+ `testcontainers/ryuk:0.14.0`)
  - `Container mysql:8.4 started in PT8.22S`
  - DataSource URL: `jdbc:mysql://localhost:10902/farm_db` — 컨테이너의 임의 포트예요.
  - 공통 지원 코드의 `requireIsolatedDataSource`도 매 테스트마다 이 URL이 컨테이너 host:mapped port인지 확인한 뒤 fixture를 만들었어요.
- **Flyway:** 격리 DB에서 `Migrating schema farm_db to version "1 …"`부터 `"10 - add salary account version"`까지 적용됐어요.
  - `Successfully applied 10 migrations …, now at version v10`
  - 두 번째 컨텍스트는 같은 컨테이너를 공유하므로 `Schema farm_db is up to date`가 나왔어요.
- **실제 서버:** `Tomcat started on port 10977`(활성 프로필 `farm-demo`)과 `port 10988`(활성 프로필 `insurance-salary-security-test`, farm-demo 비활성). 두 개의 임의 포트 서버가 떴어요.
- **실제 보안 체인:** 테스트는 JDK `HttpClient`와 기존 `FarmAuthClient`로 임의 포트에 실제 HTTP 요청을 보냈어요.
  - mock principal·standalone MockMvc를 쓰지 않았어요.
  - `SecurityConfig`·`InsuranceSecurityConfig`·`ServiceTokenFilter`·로그인 필터도 mock하지 않았어요.
  - 실제 CSRF 발급 → 폼 로그인으로 `FARMSESSION`을 얻었어요.

### 개발 DB·실제 .env·이관 도구를 쓰지 않았다는 근거

- **DB:** 로그에 `localhost:3306`·`127.0.0.1:3306`·`.env` 문자열이 없어요(0건).
  - 차단용 대체 URL(`127.0.0.1:1/do_not_use_development_db`)로 연결을 시도한 흔적도 없어요. `@ServiceConnection`이 컨테이너 연결 정보로 대체했어요.
- **Docker:** 실행 전후 `docker ps -a` 목록이 같아요(`wonday-mysql-1` Up·healthy, `wonday-rabbitmq-1` Exited). 기존 개발용 MySQL 컨테이너는 중지·삭제·초기화되지 않았어요. 테스트 컨테이너는 Testcontainers가 정리했어요.
- **.env·키·토큰:** 실제 `.env`·키·서비스 토큰은 읽거나 출력하지 않았어요. 테스트 속성은 다음과 같아요.
  - `spring.config.import=`(빈 값)
  - 비어 있는 암호화 키
  - 테스트 전용 토큰 `insurance-salary-http-test-token`
  - 가상 비밀번호, UUID 로그인 ID 회원
- 이관 도구·수동 마이그레이션은 실행하지 않았어요.
- 이미 실행 중이던 사용자 서버·Docker 컨테이너는 건드리지 않았어요.

## 5. 상태 코드·no-store 확인 결과 (실제 체인 통과)

`noStore`는 응답의 `Cache-Control` 값에 `no-store`가 들어 있는지 확인해요.

### 실제 내부 API `GET /api/internal/insurance/workers/{workerId}/salary-account`

| 시나리오 | 결과 | 확인 내용 |
|---|---|---|
| 유효 Bearer, 등록 근로자(020) | **200**, no-store | 세 필드만(`workerId`·`registered:true`·`bankCode:"020"`). 계좌번호·예금주·암호문·마스킹 번호·`v1:`·토큰 없음. **`FARMSESSION` Set-Cookie 없음** |
| 유효 Bearer, 미등록 근로자 | **200**, no-store | `registered:false`, `bankCode:null` — 실제 LEFT JOIN 결과 |
| 유효 Bearer, 고용주(계좌 088 있음) | **404** `WORKER_NOT_FOUND`, no-store | `088`·`bankCode` 비노출 |
| 토큰 누락 | **401** `INVALID_SERVICE_TOKEN`, no-store | 토큰·`bankCode`·`registered` 비노출 |
| 잘못된 Bearer | **401** `INVALID_SERVICE_TOKEN`, no-store | 같음 |
| 서버 토큰 미설정(빈 설정) + 토큰 없음 / + 테스트 토큰 | **401** `INVALID_SERVICE_TOKEN`, no-store (둘 다) | NoToken 컨텍스트 |
| 실제 로그인한 근로자 `FARMSESSION`만 전송 | **401** `INVALID_SERVICE_TOKEN`, no-store | 이후 같은 세션의 `/api/auth/me`는 **200** (세션 유지) |
| 실제 로그인한 고용주 `FARMSESSION`만 전송 | **401** `INVALID_SERVICE_TOKEN`, no-store | 이후 `/api/auth/me` **200** |

### 서버 토큰으로 농업 세션을 대신할 수 없음 (Bearer만, 쿠키 없음)

| 경로 | 결과 |
|---|---|
| `/api/auth/me` | **401**, no-store |
| `/api/me/salary-account` | **401**, no-store |
| `/demo/api/insurance/salary-account` | **401**, no-store |

### demo (farm-demo 활성 컨텍스트)

| 시나리오 | 결과 |
|---|---|
| 익명 `GET /demo/api/insurance/salary-account` | **401**, no-store |
| 익명 `GET /demo/insurance-salary-account` (화면) | **200**, no-store. 본문에 "보험용 급여 계좌 조회"가 있고 서비스 토큰은 없음 |
| 고용주 세션 조회 | **403**, no-store. `bankCode`·`registered` 필드 없음 |
| 근로자 세션 + `?workerId={다른 근로자}&memberId={다른 근로자}` | **200**, no-store. **본인** workerId와 본인 은행(020)만, 다른 근로자 은행(088)은 없음 |

### farm-demo 비활성 컨텍스트 (`insurance-salary-security-test` 프로필)

- `DemoInsuranceSalaryAccountApi` 빈이 **등록되지 않음**(`getBeansOfType` 결과 비어 있음)
- 실제 로그인한 근로자 세션으로 요청한 결과
  - 화면 `/demo/insurance-salary-account`: **403**
  - 조회 `/demo/api/insurance/salary-account`: **403**, no-store, `bankCode`·`registered` 비노출
- 기본 `denyAll`이 막은 결과예요. 기대값은 404로 바꾸지 않고 작성된 403 그대로 검증했어요.

## 6. 실패·결함 분류

- **실패:** 없음
- **운영 코드 결함:** 발견하지 못했어요.
- **테스트 오류:** 없음 (작성된 테스트를 수정 없이 실행했어요)
- **환경 문제:** 없음 (Docker 29.9.3 서버가 실행 중이었고, 컨테이너가 정상 기동했어요)

## 7. 결과 위치 (docs로 복사하지 않음)

- XML: `farm-api/build/test-results/integrationTest/TEST-com.wonday.farm.insurance.security.InsuranceSalaryAccount{SecurityHttpTests,NoTokenHttpTests}.xml`
- HTML: `farm-api/build/reports/tests/integrationTest/index.html`

## 8. 보존 확인

- 실행 전후로 `src`(운영·테스트 코드 전체)·`build.gradle`·`settings.gradle`·`gradle/`·`.github` 파일 246개의 SHA-256이 같아요.
- git 추적 diff와 staged diff 해시도 같아요.
- 이번에 새로 만든 파일은 이 보고서 하나예요.
- 기존 검증 보고서와 `SECURITY_REVIEW_TEST_PLAN.md`는 수정하지 않았어요.
- 커밋·푸시·PR·리뷰 답변 게시는 하지 않았어요.

## 9. 여전히 검증하지 않은 범위

- **실제 배포 환경:** 실제 운영·스테이징 설정과 실제 `FARM_API_SERVICE_TOKEN`, 리버스 프록시·TLS·헤더 전달
- **보험 서버 연동:** 실제 보험 서버 클라이언트의 호출·재시도·동의 처리
- **CSRF:** 이번 경로는 모두 GET이라 쓰기 요청의 CSRF 차단은 이번 범위가 아니에요. 계좌 관리 PUT·DELETE의 실제 CSRF 차단도 이번 클래스에서 검증하지 않았어요.
- **데이터 상황:** 동시 계좌 변경 중 조회, 대량 데이터, 실제 이관 데이터
- **응답 헤더:** 토큰 필터 외의 응답 헤더 정책 전체(no-store 외), 세션 고정·만료 정책
- **Bearer 변형:** 이번에는 누락·오류·미설정만 실행했어요. 대소문자 변형·중복 Authorization 헤더 등은 기존 `ServiceTokenFilterTest`(필터 단위)의 범위예요.
- **프로필 비활성 + 익명:** 로그인한 요청의 403만 실행했어요. 익명 요청의 상태는 이 클래스에서 실행하지 않았어요.
