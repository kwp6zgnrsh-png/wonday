# 보험 연동 내부 API — 단위 테스트 독립 검증 보고서

- 검증일: 2026-11-24
- **수정 재확인 완료:** DEFECT-8이 해결되어 단위 477개가 모두 통과한다. 자세한 내용은 10절에 있다.
- 브랜치: `insurance-integration` (HEAD `bbfee8f`)
- 검증자: Claude. 테스트 작성과 실행만 했고, 운영 코드는 바꾸지 않았다.
- 범위: **단위 테스트만** 다룬다. Spring 컨텍스트, 실제 DB·H2·Docker·Testcontainers·Flyway, 서버 기동, 통합 테스트는 실행하지 않았다.
- 기준: 이 보고서의 수치는 모두 이번에 직접 실행한 결과다. JOB-05의 226개 통과 결과는 근거로 쓰지 않았다.
- 레포 루트에 `AGENTS.md`가 없다. git 작업 트리는 깨끗한 상태(HEAD)에서 시작했다.

## 요약

| 항목 | 결과 |
|---|---|
| 신규 테스트 | **251개** (9개 클래스) |
| 전체 단위 테스트 | 477개 실행 · 476 통과 · **1 실패** · 0 제외 |
| 실패 1건 | **DEFECT-8** (아래 7절). 사용자 확인을 거쳐 결함으로 확정했고, 회귀 테스트로 남겨 두었다 |
| 단위 라인 커버리지 | **93.9%** (477/508) · 분기 99.5% (193/194) → 90% 기준 **충족** |
| `.\gradlew.bat test` | **실패.** DEFECT-8 회귀 테스트가 실패하는 것은 의도된 결과다. 커버리지 검증 태스크가 `unitTest`에 의존하므로 같은 실행에서는 검증 태스크까지 가지 않는다 |

## 1. 추가·수정한 파일 (모두 테스트 소스, 신규)

| 파일 | 테스트 수 |
|---|---|
| `src/test/java/com/wonday/farm/insurance/security/ServiceTokenFilterTest.java` | 33 |
| `src/test/java/com/wonday/farm/insurance/dto/WorkerVerificationRequestTest.java` | 30 |
| `src/test/java/com/wonday/farm/insurance/service/WorkerVerificationServiceTest.java` | 27 |
| `src/test/java/com/wonday/farm/insurance/service/InsuranceDataAccessTest.java` | 10 |
| `src/test/java/com/wonday/farm/insurance/service/InsuranceTimeTest.java` | 13 |
| `src/test/java/com/wonday/farm/insurance/service/WorkAssignmentQueryServiceTest.java` | 25 |
| `src/test/java/com/wonday/farm/change/service/WorkChangeRecorderTest.java` | 41 |
| `src/test/java/com/wonday/farm/insurance/service/WorkChangeQueryServiceTest.java` | 39 |
| `src/test/java/com/wonday/farm/insurance/api/InsuranceIntegrationApiTest.java` | 33 |
| `src/test/java/com/wonday/farm/insurance/testsupport/InsuranceFixtures.java` | (지원 코드, 가상 데이터) |

기존 테스트, 운영 코드, `build.gradle`, CI, 커버리지 기준과 제외 범위는 변경하지 않았다. 태그도 추가하지 않았다.

## 2. 테스트 시나리오

### 2.1 서버 토큰 필터 (`ServiceTokenFilterTest`, 33)
- **설정된 토큰이 비었거나 공백일 때:** `Bearer `(빈 토큰)를 포함해 모든 요청을 거부한다.
- **Authorization 헤더 이상:** 다음 경우 모두 401을 반환한다.
  - 헤더 누락, 빈 값
  - `Bearer`만 있는 값, 스킴 없이 토큰만 있는 값
  - `Basic`·`Token` 스킴
  - `Bearer`와 토큰 사이에 공백이 없거나 2칸인 값
  - 앞뒤 공백, 토큰 접두·접미 변형, 토큰 대소문자 변형
- **중복 헤더:** 두 값이 모두 맞아도 거부하고, 첫 값이 틀려도 거부한다.
- **세션 쿠키만 있는 요청:** `FARMSESSION` 쿠키만 있고 Bearer 토큰이 없으면 거부한다.
- **헤더 길이 경계:** 8192자는 통과하고, 8193자는 토큰이 맞아도 거부한다.
- **거부 응답:**
  - 상태 401, `application/json;charset=UTF-8`
  - 본문 필드는 정확히 `code`·`message` 두 개이고, code는 `INVALID_SERVICE_TOKEN`이다.
  - 후속 체인을 호출하지 않는다.
  - 제출한 토큰과 설정한 토큰을 본문에 노출하지 않는다.
  - 인증 정보를 남기지 않는다.
- **통과 처리:**
  - 체인을 1회 호출한다. 스킴 이름(`bearer`·`BEARER`)은 대소문자를 구분하지 않는다.
  - principal은 `insurance-service`이고 credentials는 null이다. 권한은 `INSURANCE_SERVICE` 하나뿐이다.
  - 세션 쿠키가 함께 있어도 principal은 서비스 주체다.
- **SecurityContext 정리:** 요청이 정상 종료될 때와 체인에서 예외가 날 때 모두 비운다.
- **캐시 헤더:** 성공과 실패 모두 `Cache-Control: no-store`이다.
- **한계:** 필터 하나만 직접 실행했다. 두 Security 체인의 실제 연결과 분리, 체인의 401·403 핸들러, `denyAll`, 헤더 작성 필터와의 상호작용은 증명하지 않았다(8절).

### 2.2 근로자 본인 확인 (`WorkerVerificationRequestTest` 30, `WorkerVerificationServiceTest` 27)
- **정규화:**
  - 이름은 앞뒤 공백만 제거한다.
  - 전화번호와 주민번호는 하이픈과 공백을 제거한다. 유니코드 공백(U+3000, U+00A0)도 포함된다.
  - 다른 문자는 제거하지 않는다. 앞자리 0은 보존한다. null은 null로 유지한다.
- **JSON 입력:** 숫자, 소수, 지수, 불리언, 배열, 객체는 세 필드 모두 거부한다. null이나 누락된 필드는 Bean Validation에서 처리한다.
- **Bean Validation:**
  - 정규화 후 빈 값이 되면 `@NotBlank`에 걸린다.
  - 이름은 100자까지 허용하고 101자는 거부한다.
  - `@Size`는 하이픈을 제거한 뒤 적용된다.
- **서비스 재검증:**
  - 전화번호: ASCII 숫자 10~11자리만 허용한다. 9자리, 12자리, 문자 포함, 전각 숫자, `+82` 형식, 괄호는 거부한다.
  - 주민번호: ASCII 숫자 13자리만 허용한다. 12자리, 14자리, 전각 숫자, 밑줄은 거부한다.
  - 이름: 빈 값, 제어문자(NUL, 줄바꿈, DEL), 101자를 거부한다.
  - Bean Validation을 우회한 null 값도 거부한다.
  - 거부할 때는 저장소를 호출하지 않는다.
- **저장소 조회:**
  - 정규화된 값으로 조회한다.
  - 0건이면 404 `WORKER_IDENTITY_NOT_MATCHED`를 반환한다. 메시지로 어느 항목이 달랐는지 알 수 없다.
  - 1건이면 저장소가 돌려준 workerId, 이름, 전화번호를 그대로 반환한다.
  - 2건이면 409 `WORKER_IDENTITY_AMBIGUOUS`를 반환한다.
- **노출 방지:**
  - 응답 레코드의 구성 요소는 `workerId, name, phone` 세 개뿐이다. 주민번호와 항목별 대조 결과가 없다.
  - 요청과 응답의 `toString()`은 `[redacted]`이고 원문을 포함하지 않는다.
  - 예외 메시지에 입력값이 없다.
- 트랜잭션은 `readOnly`이다.

### 2.3 확정 근무 상세 (`InsuranceDataAccessTest` 10, `InsuranceTimeTest` 13, `WorkAssignmentQueryServiceTest` 25)
- **ID 검증:** ID가 0 이하이면 400을 반환하고 아무것도 조회하지 않는다.
- **근로자 확인:** 근로자가 없거나, EMPLOYER이거나, 지원하지 않는 역할 코드(`ADMIN`, 소문자, 뒤 공백, 빈 값)이면 404 `WORKER_NOT_FOUND`이다. 이때 근무를 조회하지 않는다.
- **소유 확인:**
  - `findOwned(workerId, assignmentId)`를 두 ID로 정확히 호출한다.
  - 결과가 없으면 404 `WORK_ASSIGNMENT_NOT_FOUND`이다.
  - 응답의 workerId는 요청한 근로자다.
- **필드 매핑:**
  - 날짜, 시간대 원문, 작업지, 작목·작업 코드, 일급을 저장값 그대로 매핑한다.
  - 시간대 원문은 변환하지 않는다. 예를 들어 `GMT+09:00`도 그대로 반환한다.
- **시각 표현:**
  - 현지 약정 시각은 벽시계 시각을 유지하고 `+09:00`만 붙인다.
  - UTC로 저장된 확정 시각은 같은 순간의 `+09:00` 시각으로 바꾼다. 마이크로초까지 보존한다.
- **휴게:** 휴게가 없으면 시작·종료 모두 null이다.
- **누락·오류 데이터는 코드 계약대로 503 `SERVICE_UNAVAILABLE`:**
  - 날짜, 확정 시각, 시간대, 시작, 종료 중 하나라도 null인 경우
  - 휴게 시작·종료 중 한쪽만 null인 경우
  - 알 수 없는 시간대인 경우
- **InsuranceTime:**
  - 시간대가 null, 빈 값, 공백, 잘못된 값이면 503이다.
  - 서머타임 경계(공백 구간과 중복 구간)도 503이다. GMT+9에서는 도달하지 않는 분기다.
- **읽기 전용:**
  - 조회 전후 엔티티 스냅샷이 같다.
  - 저장소 호출은 `findOwned` 하나뿐이다.
  - 트랜잭션은 `readOnly`이다.

### 2.4 변경 최초 저장과 사유 보완 (`WorkChangeRecorderTest`, 41)
- 고정 Clock을 사용했다: `2026-11-30T01:23:45.123456789Z`가 마이크로초 절삭되어 `...45.123456`이 된다.
- **고용주와 소유자 확인:**
  - 행위자가 없거나, WORKER이거나, `ADMIN` 역할이면 AccessDenied이다.
  - 다른 고용주의 근무도 AccessDenied이다.
  - 근무가 없으면 404이다.
  - 이 모든 경우 스트림, 변경, 버전 저장소를 건드리지 않는다. 보완(revise)도 같은 소유 확인을 거친다.
- **유형과 시각 검증:**
  - 유형 누락, 종료 시각 없는 조기 종료, 시각을 지정한 취소는 400이다.
  - 마이크로초보다 정밀한 종료 시각도 400이다.
- **사유 검증:**
  - 사유 누락은 400이다.
  - WEATHER가 아닌 사유에 기상 세부 사유를 붙이면 400이다.
  - 기상 세부 사유 4종은 모두 허용한다. WEATHER에 세부 사유가 없어도 허용한다(V5 CHECK 제약과 같다).
  - 설명은 UTF-8 기준 65535바이트까지 허용한다. 한글 21845자는 허용하고 21846자와 ASCII 65536자는 거부한다.
- **발생 시각:**
  - 취소는 서버 시각이 발생·기록·수정 시각이 된다.
  - 조기 종료는 전달된 실제 종료 시각을 UTC로 바꾼 값이 발생 시각이다. 예: `15:00:00.000001+09:00` → `06:00:00.000001`.
- **최초 저장:**
  - 모든 필드가 저장되고 `sourceVersion`은 1이다.
  - 버전 행은 스트림의 다음 순번(4 → 5), 근로자, changeId, 약정 시간대를 담는다.
- **호출 순서:** 다음 순서를 `inOrder`로 확인했다.
  1. 역할 확인
  2. 소유 확인
  3. `ensureExists`
  4. `lockWorker`
  5. 중복 확인
  6. `findOwned`
  7. `saveAndFlush`
  8. 버전 저장
- **중복 최초 기록:** 409 `WORK_CHANGE_ALREADY_EXISTS`를 반환한다. 저장하지 않고 순번도 소비하지 않는다.
- **503 처리:** 잠금 대상 스트림 없음, 잠금 후 근무 사라짐, 약정 시간대 누락이나 오류이면 503이고 저장하지 않는다.
- **사유 보완:**
  - 버전 2를 추가한다. changeId, 유형, effectiveAt, recordedAt은 유지하고 사유, 설명, updatedAt(Clock 값)은 새 값을 반영한다.
  - 현재 변경 행이 갱신된다(dirty checking). `saveAndFlush`는 다시 호출하지 않는다.
  - **이전 `WorkChangeVersion` 객체는 바뀌지 않는다.**
  - 두 번 보완하면 sourceVersion은 2, 3으로, 순번은 5, 6으로 올라간다.
  - 변경 기록이 없으면 404 `WORK_CHANGE_NOT_FOUND`이고 순번을 소비하지 않는다.
  - 잘못된 보완 사유는 400이고 변경 행은 그대로다.
- 트랜잭션은 `READ_COMMITTED`이다.
- **한계:** 단위 테스트로는 실제 DB 행 잠금, 커밋 순서, 롤백, 유니크 제약을 증명하지 않는다.

### 2.5 변경 목록과 커서 (`WorkChangeQueryServiceTest`, 39)
- **limit:**
  - 1, 100, 500을 허용하고 `limit+1`개를 조회한다.
  - 0, -1, 501, `Integer.MAX_VALUE`, `Integer.MIN_VALUE`는 400이고 아무것도 조회하지 않는다.
- **근로자 확인:** 근로자가 아니면 404이고 스트림을 만들지 않는다. ID가 0이면 400이다.
- **잠금 순서:** 잠금(`ensureExists` → `lockWorker`) 후에 커서와 버전을 조회한다. 스트림이 없으면 503이다.
- **트랜잭션:** `READ_COMMITTED`이고 readOnly가 아니다. 커서를 영속화하기 때문이다.
- **커서 없이 첫 요청:**
  - 위치 0부터 조회한다.
  - 결과가 비어도 UUID 형식의 nextCursor를 반환한다. 이 커서는 위치 0으로 저장된다.
  - 같은 근로자·같은 위치의 커서가 이미 있으면 재사용하고 새로 저장하지 않는다.
- **페이지:**
  - 결과 수가 limit보다 많으면 limit개만 반환하고 hasMore는 true이다. 정확히 limit개면 false이다.
  - 다음 위치는 마지막으로 반환한 항목의 순번이다. 순번에 빈틈이 있어도 같다.
  - 커서는 저장된 위치부터 이어서 조회한다.
  - 501행 중 500행만 반환한다.
- **빈 페이지 이후 신규 기록:** 순서가 있는 mock 응답으로 확인했다. 빈 폴링에서 같은 커서를 재사용하고, 신규 기록이 생긴 뒤 같은 커서로 조회하면 새 항목이 나온다. 실제 DB에서 누락이 없다는 증거는 아니다.
- **재조회 시 내용 유지:** v1 이후 원본 사유가 보완되어도 v1 응답은 원래 사유(WEATHER/RAIN/"비")를 유지한다. 같은 요청을 다시 해도 같은 항목이 나온다.
- **잘못된 커서는 400 `INVALID_CURSOR`:**
  - 형식 오류 9종: 빈 값, 대문자 UUID, 하이픈 없음, 뒤 공백, SQL 문자열 등. 이 경우 DB를 조회하지 않는다.
  - 존재하지 않는 커서
  - 다른 근로자의 커서. 이 경우 버전을 조회하지 않는다.
  - 마지막 순번을 넘는 위치. 마지막 순번과 같은 위치는 허용한다.
- **항목 매핑:**
  - sourceVersion은 문자열(`"2"`)이다.
  - 유형, 사유, 기상 세부 사유, 설명을 매핑한다.
  - 발생·기록·수정 시각은 UTC 값을 `+09:00`으로 바꾼다. 마이크로초까지 보존한다.
- **필터링 없음:** 유형 2종 × 사유 3종의 모든 조합을 걸러내지 않고 반환한다.
- 버전에 저장된 시간대가 잘못되었으면 503이다.

### 2.6 컨트롤러·오류 응답·직렬화 (`InsuranceIntegrationApiTest`, 33, standalone MockMvc)
- **요청 전달:**
  - 본인 확인 본문은 정규화된 DTO로 서비스에 전달된다.
  - 경로의 두 ID, cursor, limit도 서비스에 전달된다. cursor가 없으면 null, limit 기본값은 100이다.
- **400 `INVALID_REQUEST`:**
  - 숫자 전화번호, 공백 필드, 읽을 수 없는 본문 5종
  - 숫자가 아니거나 범위를 넘는 경로 ID와 limit
  - 이 경우 서비스를 호출하지 않는다. 본문에 입력값, 주민번호, 필드명을 노출하지 않는다.
- **서비스 오류:** 409와 404, `INVALID_CURSOR`는 서비스가 정한 code를 유지한다.
- **503 `SERVICE_UNAVAILABLE`:** 다음 6종의 예외에서 반환하고, 예외 메시지(JDBC URL, SQL)를 노출하지 않는다.
  - `DataAccessResourceFailure`
  - `QueryTimeout`
  - `CannotAcquireLock`
  - `PessimisticLockingFailure`
  - `CannotCreateTransaction`
  - `TransactionTimedOut`
- **500 `INTERNAL_SERVER_ERROR`:** 예상하지 못한 예외와 `DataIntegrityViolation`에서 반환한다. 메시지와 스택 정보를 노출하지 않는다.
- **오류 본문 형식:** 모든 오류 본문의 필드는 정확히 `code`·`message`이다. 단, 아래 DEFECT-8은 예외다.
- **JSON 표현:**
  - 날짜는 `"2026-11-30"` 형식이다.
  - 시각은 `+09:00`을 붙이고 마이크로초를 보존한다(`"2026-11-20T10:02:03.456789+09:00"`).
  - 휴게가 없으면 키가 있고 값이 `null`이다.
  - 빈 목록은 `[]`이고 nextCursor가 있다.
  - sourceVersion은 JSON 문자열이다. enum은 이름으로 표현한다.
  - 기상 세부 사유와 설명이 없으면 `null`이다.
  - workerId와 일급은 정수다.
- **한계:** 기본 JsonMapper 기준이다. Spring Boot가 구성한 매퍼와 Security 체인은 검증하지 않았다.

## 3. 실행 명령과 결과

JDK는 `JAVA_HOME=C:\Users\HCB\Desktop\woori-workspace\00.sw\jdk-21.0.12.1+1`를 사용했다.

| 명령 | 결과 |
|---|---|
| `.\gradlew.bat clean unitTest jacocoUnitTestReport` | 477 실행 · 476 통과 · 1 실패 · 0 skipped → `unitTest FAILED`. 이 때문에 리포트 태스크는 실행되지 않았다 |
| `.\gradlew.bat test` | 같은 결과(477/1 실패) → BUILD FAILED. 커버리지 검증 태스크까지 가지 않았다 |
| `.\gradlew.bat jacocoUnitTestReport jacocoUnitTestCoverageVerification -x unitTest` | 위 실행이 남긴 `build/jacoco/unitTest.exec`로 리포트를 만들고 90% 기준을 검사했다 → **BUILD SUCCESSFUL** |

마지막 명령은 테스트를 다시 실행하지 않는다. 실패한 1건을 포함한 477개 전체 실행의 측정 데이터를 사용한다. 측정 대상과 기준은 바꾸지 않았다.

`integrationTest`, `fullTest`, `knownDefectTest`는 실행하지 않았다.

## 4. 커버리지 (단위, 측정 제외 범위는 기존 설정 그대로)

**전체:** 라인 **93.9% (477/508)**, 분기 **99.5% (193/194)**

| 클래스 | 라인 | 분기 |
|---|---|---|
| WorkChangeRecorder | 100% (49/49) | 100% (32/32) |
| WorkChangeQueryService | 100% (33/33) | 100% (16/16) |
| WorkAssignmentQueryService | 100% (20/20) | 100% (16/16) |
| WorkerVerificationService | 100% (15/15) | 100% (18/18) |
| InsuranceTime | 100% (9/9) | 100% (10/10) |
| InsuranceDataAccess | 100% (7/7) | 100% (4/4) |
| WorkerVerificationRequest | 100% (7/7) | 100% (4/4) |
| InsuranceIntegrationApi | 100% (8/8) | - |
| InsuranceErrorHandler | 100% (6/6) | - |
| WorkChange / WorkChangeVersion / ChangeCursor / ChangeStream | 100% | - |
| ServiceTokenFilter | 92.0% (23/25) | 100% (14/14) |
| IdentityStringDeserializer | 75.0% (3/4) | 100% (2/2) |
| **InsuranceSecurityConfig** | **0% (0/19)** | - |
| **InsuranceIntegrationConfig** | **0% (0/2)** | - |

### 미실행 라인과 이유
- **`InsuranceSecurityConfig` (19줄):** `HttpSecurity`로 필터 체인을 조립하는 코드다. 실제 체인을 만들어야 의미 있게 실행된다. mock `HttpSecurity`로 줄만 통과시키는 테스트는 아무것도 증명하지 못해 만들지 않았다. 기존 `auth/SecurityConfig*`는 단위 측정에서 제외되어 있지만, 이 클래스는 제외 패턴에 포함되지 않는다. 제외 범위는 바꾸지 않았다.
- **`InsuranceIntegrationConfig` (2줄):** `Clock.systemUTC()` 빈 선언이다. 컨텍스트 기동 시에만 실행된다.
- **`ServiceTokenFilter` (2줄):** `NoSuchAlgorithmException` catch 블록이다. SHA-256은 Java 표준상 항상 제공되므로 실행되지 않는다.
- **`IdentityStringDeserializer` (1줄):** `return (String) context.handleUnexpectedToken(...)`이다. 이 줄은 실제로 실행되고 예외를 던진다(비문자열 거부 테스트 17건). 호출 도중 예외가 나면 JaCoCo가 줄을 미실행으로 표시하는 측정상 한계다.

## 5. 90% 기준

라인 93.9%로 **기준 충족**(`jacocoUnitTestCoverageVerification` 통과). 다만 `.\gradlew.bat test`는 DEFECT-8 회귀 테스트가 실패해서 **현재 실패**한다. DEFECT-8을 수정하면 한 번의 `test` 실행으로 통과할 것이다(6절 수정안 검증 참고).

## 6. JaCoCo·테스트 리포트 경로

- 빌드 산출물(다음 빌드 때 덮어씀):
  - `farm-api/build/reports/jacoco/jacocoUnitTestReport/html/index.html`
  - `farm-api/build/reports/jacoco/jacocoUnitTestReport/jacocoUnitTestReport.xml`
  - `farm-api/build/reports/tests/unitTest/index.html`
- 보존본(이 폴더):
  - `jacoco/html/index.html`
  - `jacoco/unit.xml`
  - `test-results/html/index.html`

## 7. 발견한 결함

### DEFECT-8: JSON이 아닌 Content-Type은 400 `INVALID_REQUEST`가 아니라 본문 없는 415로 응답

- **사용자 확인(2026-11-24):** 올바른 동작은 "400 INVALID_REQUEST(code·message)"이다.
- **재현 테스트:** `InsuranceIntegrationApiTest.ErrorResponses.unsupportedContentTypeIsInvalidRequestInCodeMessageFormat`
- **재현 조건:**
  ```http
  POST /api/internal/insurance/worker-verifications
  Content-Type: text/plain

  {"name":"데모","phone":"01000000000","residentRegistrationNumber":"0000000000000"}
  ```
  - 기대: `400 {"code":"INVALID_REQUEST","message":"..."}`
  - 실제: `415`이고 code·message 본문이 없다(`expected: 400 but was: 415`)
- **원인:**
  - `@PostMapping(consumes = "application/json")` 조건 때문에 Spring이 핸들러를 고르는 단계에서 `HttpMediaTypeNotSupportedException`을 던진다.
  - 이 시점에는 핸들러가 정해지지 않았다.
  - `InsuranceErrorHandler`는 `@RestControllerAdvice(basePackageClasses = InsuranceIntegrationApi.class)`로 범위가 정해져 있어 이 예외에 적용되지 않는다.
  - 그래서 핸들러에 선언된 `HttpMediaTypeNotSupportedException → INVALID_REQUEST` 매핑은 실행될 수 없다.
- **수정 제안(1줄):** `InsuranceIntegrationApi`에서 다음과 같이 바꾼다.
  ```java
  // 변경 전
  @PostMapping(value = "/worker-verifications", consumes = "application/json")
  // 변경 후
  @PostMapping("/worker-verifications")
  ```
  이렇게 하면 요청이 컨트롤러까지 오고, `@RequestBody`를 읽는 단계에서 같은 예외가 난다. 이때는 핸들러가 정해져 있으므로 advice가 적용되어 400 `INVALID_REQUEST`가 된다.
- **수정안 검증:** 운영 파일을 임시로 위와 같이 바꾸고 `InsuranceIntegrationApiTest` 33개를 실행했다. **33개 모두 통과**했다. 다른 오류와 정상 요청에는 영향이 없었다. 실행 후 원본을 복원했고 `cmp`로 바이트 단위 동일함을 확인했다. git에도 운영 코드 변경이 없다.
- **같은 원인으로 추정되는 경우(참고, 실행하지 않았고 결함 판정 아님):** `Accept: application/xml`로 GET을 보내면 같은 단계에서 406이 날 것으로 추정한다. 그렇다면 이 응답도 code·message 형식이 아닐 것이다. 이 경우는 테스트로 실행하지 않았다. 핸들러에 선언된 예외도 아니어서 결함으로 분류하지 않았다. 실제 앱에서 Security 체인과 `/error` 처리를 거쳤을 때 본문은 통합 검증에서 확인해야 한다.

### 테스트가 결함을 잡는지 확인한 임시 변조 (모두 원본 복원과 `cmp` 동일 확인)

| 변조 | 실패한 테스트 |
|---|---|
| 토큰 필터의 중복 헤더 검사 제거 | `duplicateAuthorizationHeadersAreRejectedEvenWhenBothAreValid` |
| 커서의 근로자 일치 필터 제거 | `anotherWorkersCursorIsRejected` |
| 버전 저장 시 스트림 순번 대신 상수 1 사용 | `savesAVersionWithTheNextStreamSequenceAndAgreedTimeZone` |

### 관찰 사항 (결함 아님 — 정책 미정이거나 현재 계약)
- **조기 종료 시각 범위:** 조기 종료의 실제 종료 시각이 약정 시간 안에 있는지, 미래 시각은 아닌지 검사하지 않는다. 실제 작업 시작 판단 정책이 미정이고 호출자 책임으로 문서화되어 있어 판단하지 않았다.
- **같은 값으로 보완:** 사유를 같은 값으로 보완해도 새 버전이 생긴다.
- **누락 약정의 상태 코드:** 약정 누락이나 잘못 저장된 시간대는 503 `SERVICE_UNAVAILABLE`로 응답한다. 문서의 계약과 같다. 다만 재시도해도 해결되지 않는 데이터 문제라는 점은 팀이 참고할 만하다.
- **공백 2칸 토큰:** `Bearer  token`처럼 공백이 2칸이면 거부한다. RFC 6750은 1칸 이상을 허용하지만 서버 간 계약에서는 문제가 될 가능성이 낮다.
- **Java와 DB의 정규화 차이 가능성:**
  - Java의 `strip()`은 U+00A0을 공백으로 보지 않아 이름 앞뒤의 U+00A0을 제거하지 않는다.
  - 번호 정규화는 `(?U)\s` 때문에 U+00A0을 제거한다.
  - DB 쪽 `[[:space:]]`가 어떻게 동작하는지와 맞는지는 통합 검증 대상이다.

## 8. 실제 MySQL·Security 통합 검증으로 남은 항목

- **Security 체인:**
  - 보험 체인과 농업 체인이 실제로 분리되는지
  - 보험 경로에서 세션 쿠키를 무시하는지, CSRF가 꺼지는지
  - 농업 API의 세션·CSRF가 그대로 유지되는지
  - 등록되지 않은 메서드와 경로가 `denyAll` → 403 `SERVICE_ACCESS_DENIED`가 되는지
  - 인증 실패 시 체인의 401 진입점이 동작하는지
  - 체인 헤더 필터와 `Cache-Control: no-store`의 최종값
- **`/error` 처리:** 415·406 같은 Spring 기본 오류의 실제 응답 본문과, 그때 어느 체인을 타는지
- **`WorkerVerificationRepository`:**
  - MySQL `regexp_replace` 정규화(유니코드 공백 포함)
  - `cast(... as binary)`로 대소문자와 역할을 구분하는지
  - WORKER 필터, `LIMIT 2`
  - projection 매핑, 주민번호를 반환하지 않는지
- **`WorkAssignmentRepository`:** `findOwned`의 `application.worker_id` JOIN 필터, `findOwnership`의 고용주 연결
- **스트림:**
  - `ensureExists`의 `ON DUPLICATE KEY`
  - `lockWorker`의 `PESSIMISTIC_WRITE` 대기
  - 동시 저장과 폴링 시 커밋 역전에도 누락이 없는지
- **저장 제약과 롤백:**
  - 순번과 버전 유니크 제약
  - 롤백 시 순번·버전·변경 행이 함께 취소되는지
  - `work_change.assignment_id` 유니크 제약이 동시 최초 기록을 막는지
- **커서:** `uq_cursor_worker_position`과 커서 재사용의 동시성
- **V5 마이그레이션:** 적용, CHECK 제약, `ddl-auto: validate` 통과 여부
- **Boot 직렬화:** Spring Boot가 구성한 JsonMapper에서 날짜·시각 형식이 단위 테스트와 같은지
- **로그 노출:** 실제 로그에 토큰과 주민번호가 남지 않는지
- **동의·철회:** 보험 서버의 검증 범위라 이 범위에서 제외한다.

## 9. 확인 사항

- 운영 코드, API 계약, `build.gradle`, CI, 커버리지 기준과 제외 범위를 **변경하지 않았다**. 수정안과 변조 확인은 임시로만 바꿨고 모두 원본으로 복원했다(`cmp` 동일, `git status`에 운영 코드 변경 없음).
- 테스트 비활성화나 known-defect 등 제외 태그 추가는 **하지 않았다**. DEFECT-8 테스트는 실패하는 상태로 기본 스위트에 남아 있다.
- 통합 테스트(`integrationTest`, `fullTest`)는 **실행하지 않았다**. 기존 통합 테스트 코드는 그대로다.
- 브랜치 전환, 커밋, 푸시는 **하지 않았다**.

## PR 본문용 요약

> **보험 연동 내부 API 단위 검증 (Claude, 2026-11-24)**
> - 신규 단위 테스트 251개를 추가했다: 토큰 필터, 본인 확인, 확정 근무 상세, 변경 저장·보완, 변경 목록·커서, 컨트롤러·오류·JSON.
> - 전체 단위 테스트는 477개이고 1개가 실패한다. 실패한 1개는 DEFECT-8 회귀 테스트다.
> - 단위 라인 커버리지는 93.9%(분기 99.5%)로 90% 기준을 충족한다.
> - **DEFECT-8:** `POST /worker-verifications`에 JSON이 아닌 Content-Type을 보내면 400 `INVALID_REQUEST`가 아니라 본문 없는 415로 응답한다.
>   - 수정안: `consumes = "application/json"`을 제거한다(1줄).
>   - 수정안을 임시로 적용하면 API 테스트 33개가 모두 통과한다.
> - 실제 MySQL 쿼리, 잠금·커밋 순서, Security 체인 분리는 통합 검증으로 남아 있다(보고서 8절).

## 10. 수정 재확인 (DEFECT-8)

이번 재확인은 DEFECT-8 수정만 대상으로 한다.

### 운영 코드 변경
`InsuranceIntegrationApi.java`의 `git diff HEAD`는 정확히 1줄이다. 7절에서 제안한 수정과 같다.
```diff
-	@PostMapping(value = "/worker-verifications", consumes = "application/json")
+	@PostMapping("/worker-verifications")
```

### 테스트 상태
- DEFECT-8 회귀 테스트와 내가 작성한 신규 테스트 9개 파일은 마지막 실행 뒤 수정되지 않았다(파일 수정 시각 기준).
- 신규 테스트에 `@Tag`·`@Disabled`가 없다.

### 같은 변경 묶음에 포함된 커버리지 정책 변경
`build.gradle`, CI, README에서 `test`의 90% 자동 차단이 빠졌다. 사용자에 따르면 팀이 기준이 너무 빡빡하다고 판단해 뺀 것이다. 따라서 결함이나 이상으로 보지 않는다.
- 90% 검사는 `jacocoUnitTestCoverageVerification`으로 직접 실행할 수 있다.
- `fullTest`는 여전히 단위 90%·통합 80% 기준을 검사한다.

### 실행 결과

| 명령 | 결과 |
|---|---|
| `.\gradlew.bat clean test` | 477 실행 · **477 통과** · 0 실패 · 0 skipped → BUILD SUCCESSFUL |
| `.\gradlew.bat unitTest jacocoUnitTestReport jacocoUnitTestCoverageVerification` | 90% 검사 **통과** |
| 단위 커버리지 | 라인 **93.9%** (477/508) · 분기 99.5% (193/194). 수정 전과 같다. `InsuranceIntegrationApi`는 100% (8/8) |

### 회귀 테스트가 수정을 지키는지
수정 전 코드(`consumes = "application/json"`)로 임시로 되돌리고 테스트를 실행했다.
- 수정 전 코드: `unsupportedContentTypeIsInvalidRequestInCodeMessageFormat`가 **실패**했다(33개 중 1개).
- 수정본으로 복원: `cmp`로 바이트 단위 동일함을 확인했다. 이어서 위 전체 실행에서 통과했다.

### 남은 검증
- 실제 앱에서 Security 체인을 거쳤을 때 415·400 응답은 통합 검증 대상이다(8절 그대로).
- `Accept: application/xml` 요청이 406으로 응답할지는 여전히 추정이고, 이번에도 실행하지 않았다.

### 리포트 위치
수정 후 커버리지 리포트는 `jacoco-recheck/`에 있다. 수정 전 리포트는 `jacoco/`에 그대로 두었다.

### PR 본문용 요약 (재확인)
> - DEFECT-8 수정을 확인했다.
>   - `consumes` 제한 1줄을 제거해, JSON이 아닌 Content-Type이 400 `INVALID_REQUEST`(code·message)로 응답한다.
>   - 수정 전 코드로 되돌리면 회귀 테스트가 실패하고, 복원하면 통과한다.
> - 단위 테스트 477개가 모두 통과한다. 라인 93.9%, 분기 99.5%이고, 90% 명시 검사를 통과한다.
