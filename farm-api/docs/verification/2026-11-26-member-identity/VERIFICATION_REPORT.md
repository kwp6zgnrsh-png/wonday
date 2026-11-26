# 회원 식별정보 저장·근로자 확인·JPA 이관 단위 검증 보고서

- 검증일: 2026-11-26
- 작업 공간: `C:\Users\HCB\.codex\worktrees\member-identity\wonday`
- 브랜치: `member-identity`
  - HEAD는 `8037e4b`이고, 커밋되지 않은 변경이 있어요.
  - 이 변경은 다른 작업자의 것이라 보존했어요.
- 저장소 루트와 `farm-api`에는 `AGENTS.md`가 없어요.
- 원래 `C:\Users\HCB\Desktop\wonday`(job-location) 작업 공간은 수정하지 않았어요.
- 기존 로그인·공고·좌표 테스트 결과는 이번 기능의 근거로 사용하지 않았어요.

## 1. 요약

| 구분 | 테스트 | 성공 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|---|
| 신규 식별정보 테스트 (5개 클래스) | **93** | 93 | 0 | 0 | 0 |
| 기존 `WorkerVerificationServiceTest` (생성자만 바뀐 영향 테스트) | 27 | 27 | 0 | 0 | 0 |
| **이번 선택 실행 합계** | **120** | 120 | 0 | 0 | 0 |

- 운영 코드 결함은 찾지 못했어요.
- 실행 중 테스트 오류나 실패도 없었어요.
- 관찰 사항 4건은 8절에 정리했어요.

## 2. 추가한 테스트 파일과 목적

경로는 모두 `farm-api/src/test/java/com/wonday/farm/` 기준이에요.

| 파일 | 수 | 목적 |
|---|---|---|
| `member/identity/testsupport/IdentityTestKeys.java` | — | 테스트 전용 가상 키(0x11·0x22·0x33을 반복한 32바이트)와 가상 번호(`1111111111111`, `2222222222222`) |
| `member/identity/IdentityNumberTest.java` | 28 | 정규화 규칙, 거절 입력, 길이 상한, 국적별 번호 종류 |
| `member/identity/IdentityCryptoTest.java` | 25 | 왕복, nonce, HMAC, 변조·키·AAD 거절, 형식 거절, 키 설정 실패 시점, 비노출 |
| `member/identity/MemberIdentityStorageTest.java` | 15 | `StoredIdentity` 형식, `Member`와 `IdentityMigrationMember`의 연결·덮어쓰기 방지, 정적 매핑 |
| `insurance/service/WorkerVerificationLookupModeTest.java` | 10 | LEGACY·ENCRYPTED 분기, fallback 없음, 503, 0·1·2건 처리, 응답 형태, `@Query` 선언 대조 |
| `member/identity/migration/MemberIdentityMigrationServiceTest.java` | 15 | 사전 검사, 미리보기, 실제 적용 분기(mock), 전체 검증 실패 시 중단·롤백 요청 |

- 기존 테스트 파일은 수정하지 않았어요.
- 운영 코드, Gradle, CI, 마이그레이션, `.env`도 수정하지 않았어요. `.env`는 읽지도 않았어요.
- 키와 번호는 모두 테스트 전용 가상 값이에요.

## 3. 실행 명령·범위·시간

`build.gradle` 확인 결과는 다음과 같아요.

- `unitTest`는 `integration`과 `known-defect` 태그를 제외해요.
- `unitTest`에 자동으로 연결된 태스크는 없어요.
- `migrateMemberIdentity`는 별도 JavaExec 태스크예요. 이번에는 실행하지 않았어요.

`farm-api`에서 `JAVA_HOME=jdk-21.0.12.1+1`로 실행했어요.

```
./gradlew.bat --no-daemon unitTest \
  --tests 'com.wonday.farm.member.identity.IdentityNumberTest*' \
  --tests 'com.wonday.farm.member.identity.IdentityCryptoTest*' \
  --tests 'com.wonday.farm.member.identity.MemberIdentityStorageTest*' \
  --tests 'com.wonday.farm.member.identity.migration.MemberIdentityMigrationServiceTest*' \
  --tests 'com.wonday.farm.insurance.service.WorkerVerificationLookupModeTest*' \
  --tests 'com.wonday.farm.insurance.service.WorkerVerificationServiceTest*' \
  jacocoUnitTestReport
```

- 위 6개 클래스만 실행했어요. BUILD SUCCESSFUL, 약 24초(컴파일 포함)였어요.
- 실행하지 않은 것: 전체 단위 테스트, 통합 테스트, Testcontainers, DB, 서버, 이관 명령(미리보기 포함), Flyway
- 결과 파일(이 작업 공간 기준):
  - `farm-api/build/test-results/unitTest/`
  - `farm-api/build/reports/tests/unitTest/`
  - `farm-api/build/reports/jacoco/jacocoUnitTestReport/`

## 4. 항목별 결과

### 4.1 식별번호 정규화와 종류 — `IdentityNumberTest`

- **제거하는 문자:** ASCII 하이픈과 유니코드 공백(탭, NBSP, 전각 공백 포함)을 제거하고 13자리로 정규화해요.
- **체크섬은 요구하지 않아요.** `0000000000000`처럼 생년월일이나 체크섬과 무관한 번호도 형식만 맞으면 허용해요. 문서 계약과 같아요.
- **거절하는 입력:**
  - null, 빈 값, 공백만 있는 값, 12자리, 14자리
  - 문자가 섞인 값, 전각 숫자, 아랍-인도 숫자
  - `_`, `.`, en/em 대시, `+`, 폭 없는 공백(U+200B)
- **길이 상한:**
  - 100자 입력은 허용돼요. 하이픈 87개와 숫자 13개로 확인했어요.
  - 101자 이상과 10만 자 입력은 거절돼요.
- **오류 메시지:** 입력 번호가 들어가지 않아요.
- **국적별 번호 종류:** `DOMESTIC`은 `RESIDENT_REGISTRATION_NUMBER`, `FOREIGN`은 `FOREIGN_REGISTRATION_NUMBER`예요.

### 4.2 암호화와 HMAC — `IdentityCryptoTest`

**왕복과 nonce**

- 내국인과 외국인 모두 같은 회원 ID·종류로 복호화하면 정규화된 원문이 나와요.
- 암호문 형식은 `v1:` + URL-safe Base64(패딩 없음)예요.
  - 길이는 41바이트예요(nonce 12 + 평문 13 + 태그 16).
  - 암호문에 원문 숫자가 보이지 않아요.
- 같은 원문을 50회 암호화하면 암호문 50개와 nonce 50개가 모두 달라요. HMAC은 1개로 같아요.

**HMAC**

- 하이픈·공백만 다른 같은 번호는 같은 HMAC이에요. 다른 번호는 다른 HMAC이에요.
- 형식은 64자리 소문자 16진수예요.
- **현재 계약:** 같은 번호는 국적·종류·회원 ID가 달라도 HMAC이 같아요. 그래서 중복을 우회할 수 없어요.
- HMAC 키가 바뀌면 HMAC이 달라지고, 암호화 키만 바뀌면 HMAC은 그대로예요. 두 키가 분리되어 쓰인다는 뜻이에요.

**복호화 거절** (모두 고정 메시지의 `IdentityCryptoUnavailableException`이고, 원인 예외가 붙지 않아요)

- nonce·본문·태그 위치의 1비트 변조(6곳)
- 다른 암호화 키, 다른 회원 ID, 다른 식별번호 종류
- 잘못된 형식: null, `""`, `v1:`, `v2:`, `V1:`, 접두사 없음, 잘못된 문자, 40·42바이트, 256자 초과
- 회원 ID 0, 종류 null

**키 설정** (13가지 경우)

- 대상: 누락, null, 공백, Base64 오류, URL-safe 문자, 16·31·33·64바이트, 두 키 동일
- **생성 자체는 예외 없이 성공해요.**
- `requireConfigured`, `protect`, `blindIndex`, `decryptForMigration`을 호출하는 시점에 거절돼요.
- 정상 키는 `requireConfigured`를 통과해요.
- **판정 순서:** 미설정 상태에서도 잘못된 번호나 회원 ID는 `IllegalArgumentException`이에요. 입력 검사가 설정 검사보다 먼저예요.

**비노출**

- `StoredIdentity.toString()`은 `StoredIdentity[redacted]`예요.
- `IdentityCrypto.toString()`에 키(Base64·16진수)가 없어요.
- 예외 메시지에 번호·암호문·HMAC·키 문자열이 없고, 원인 예외와 suppressed 예외도 없어요.

이 테스트들은 공개 동작을 확인한 것이에요. AES-GCM과 HMAC의 암호학적 안전성이나 JDK 내부 구현을 입증하지 않아요.

### 4.3 저장값과 회원 엔티티 — `MemberIdentityStorageTest`

**StoredIdentity**

- 다음은 모두 거절돼요.
  - 회원 ID 0·음수
  - 국적 null, 종류 null, 국적과 종류 불일치(양방향)
  - 암호문 null, `v2:` 접두사, 257자
  - HMAC null, 대문자, 63자·65자, 16진수가 아닌 문자
- 256자 암호문은 허용돼요(경계).

**Member.assignIdentity**

- 같은 회원 ID의 저장값을 네 필드에 연결해요.
- 연결 전후로 다음 필드가 그대로예요: ID, 로그인 ID, 비밀번호 해시, 이름, 역할, 전화번호
- 다음 경우는 `IllegalArgumentException`이고 필드가 바뀌지 않아요.
  - ID가 아직 없는 회원
  - 다른 회원 ID의 저장값
  - null
- 네 필드 중 하나라도 이미 있으면 `IllegalStateException`이고, 기존 값이 유지돼요(4가지).
- 두 번째 연결은 거절되고, 첫 연결 값이 유지돼요.
- DB에 알 수 없는 값(`domestic`, `RRN`)이 있으면 `Optional.empty`로 읽어요. 추측하지 않아요.
- `Member`에는 주민번호 원문 필드가 매핑되어 있지 않아요.

**IdentityMigrationMember (이관 전용 엔티티)**

- 같은 ID 검사, 네 필드 연결, 덮어쓰기 방지가 `Member`와 같아요.
- ID가 없거나 일부 필드만 있는 경우도 거절돼요.
- `toString()`은 가려져 있어요.
- **정적 확인:**
  - `resident_registration_number`는 `insertable=false`, `updatable=false`예요.
  - 필드는 `id, legacyNumber, nationality, type, ciphertext, hmac` 6개뿐이에요. 역할·비밀번호·전화·이름은 매핑하지 않아요.
  - 패키지가 `com.wonday.migration.identity`라서 `com.wonday.farm` 밖에 있어요.

V8의 NULL·CHECK·UNIQUE 제약이 실제 DB에 적용되는지는 검증하지 않았어요.

### 4.4 근로자 확인 서비스 — `WorkerVerificationLookupModeTest` + 기존 `WorkerVerificationServiceTest`

먼저 기존 테스트(27개)의 계약을 읽었어요. 입력 재검증과 0·1·2건 해석이에요. 수정 없이 그대로 통과했어요.

**LEGACY 모드**

- 정규화한 이름·전화·번호로 `findMatches`만 호출해요.
- HMAC 조회와 암호화 객체는 사용하지 않아요.
- 키가 없어도 동작해요.

**ENCRYPTED 모드**

- 정규화 번호의 HMAC으로 `findDomesticByIdentityHmac(name, phone, hmac)`만 호출해요. `findMatches`는 호출하지 않아요.
- 결과 0건은 404 `WORKER_IDENTITY_NOT_MATCHED`, 1건은 200, 2건은 409 `WORKER_IDENTITY_AMBIGUOUS`예요.
- 암호화 설정이 불완전하면(빈 키, 동일 키, 한쪽 누락) 503 `SERVICE_UNAVAILABLE`이에요.
  - 이때 Repository는 호출되지 않아요. **LEGACY로 자동 fallback하지 않아요.**
- 입력 형식 오류는 400 `INVALID_REQUEST`이고, HMAC 계산이나 조회를 하지 않아요.

**응답 형태**

- 두 모드 모두 응답 필드는 `workerId`, `name`, `phone` 3개예요.
- JSON은 정확히 `{"workerId":5,"name":"근로자","phone":"01000000000"}`이고, 번호와 HMAC이 없어요.

**쿼리 선언 대조** (`@Query` 문자열만 확인했어요)

- HMAC 조회의 조건:
  - WORKER, DOMESTIC, RESIDENT_REGISTRATION_NUMBER
  - 암호문 not null, `identity_hmac = :identityHmac`, `limit 2`
  - 원문 컬럼과 NULL 국적 허용 조건은 없어요.
- LEGACY 조회의 조건:
  - WORKER
  - 국적·종류가 NULL이거나 내국인
  - 기존 번호 컬럼으로 대조, `limit 2`
- mock 결과로는 실제 MySQL 필터, 정규화, LIMIT 동작을 증명할 수 없어요.

### 4.5 JPA 이관 서비스 — `MemberIdentityMigrationServiceTest`

EntityManager, TypedQuery, PlatformTransactionManager는 mock이에요. 트랜잭션 상태는 `SimpleTransactionStatus`를 썼어요. 회원 행은 실제 `IdentityMigrationMember` 객체이고, 암호화는 테스트 키의 실제 `IdentityCrypto`예요.

**사전 검사**

- 회원 ID 목록이 null, 빈 목록, 0, 음수, null 원소를 포함하면 거절해요.
- 키 설정이 불완전하면 `IdentityCryptoUnavailableException`이에요.
- 두 경우 모두 트랜잭션과 EntityManager를 사용하지 않아요.

**미리보기**

- 트랜잭션 정의는 `readOnly=true`, `REPEATABLE_READ`예요.
- `setLockMode`, 엔티티 변경, `flush`가 없어요.
- `status.isRollbackOnly()=true` 상태로 `commit(status)`를 요청해요. TransactionTemplate의 동작이고, 실제 관리자가 롤백해요.
- 집계는 `Result(선택 2, 신규 1, 이미 이관됨 1, false)`예요.

**실제 적용 분기 (mock 단위)**

- 트랜잭션 정의는 `readOnly=false`, `SERIALIZABLE`이에요.
- 호출 순서: `setLockMode(PESSIMISTIC_WRITE)` → `getResultList` → `flush` → `commit`
  - flush 시점에는 이미 엔티티가 변경되어 있어요.
- 선택된 미이관 내국인만 새 필드를 채워요.
  - 저장값: DOMESTIC, RESIDENT_REGISTRATION_NUMBER, 정규화 번호의 HMAC
  - 복호화하면 원문과 같아요.
  - 기존 번호 값은 그대로예요.
- 이미 이관된 행은 암호문과 HMAC이 같은 객체로 유지돼요. 다시 만들지 않아요.
- 선택하지 않은 행은 변경되지 않아요.
- 같은 대상에 두 번째로 적용하면 `신규 0 / 이미 이관됨 1`이고, 암호문이 그대로예요(멱등).
- `flush` 예외와 조회 예외는 그대로 전파되고, `rollback(status)`이 요청돼요. commit은 없어요.
  - Java 객체의 값이 자동으로 복원된다고 기대하지 않았어요.

**전체 검증 실패** (모두 `MemberIdentityMigrationException`, 원인 예외 없음)

중단 조건은 다음과 같아요.

- 정규화 후 같은 기존 번호를 가진 두 회원(선택 여부와 무관)
- 기존 번호와 다른 회원의 저장 번호가 같은 경우
- 같은 회원의 기존 번호와 복호화 번호가 다른 경우
- 저장값 이상 8가지
  - 암호문 누락, HMAC 누락, 종류 불일치, 알 수 없는 국적 값
  - HMAC 불일치, 대문자 HMAC, 암호문 변조, 다른 회원 ID의 암호문
- 선택되지 않은 회원의 기존 번호 형식 오류
- 존재하지 않는 회원
- 기존 번호가 없거나 공백인 선택 회원
- 기존 외국인 회원을 선택한 경우. 외국인 정보는 그대로 유지돼요.
- 미리보기에서도 같은 검증 실패가 나면 중단해요.

모든 실패에서 공통으로 확인한 것:

- `flush`와 `commit`이 없고, `rollback`이 요청돼요.
- 앞서 검증을 통과한 선택 회원도 변경되지 않아요.
- 메시지에는 회원 ID만 있어요. 번호, 하이픈이 들어간 번호, HMAC은 없어요.

## 5. 명령·설정 정적 검토 (실행하지 않았어요)

| 항목 | 결과 | 근거 |
|---|---|---|
| 기본이 미리보기 | 충족 | `--apply`가 없으면 `apply=false`. Gradle은 `-PapplyIdentityMigration=true`일 때만 `--apply`를 넘김 |
| 회원 ID 명시 요구 | 충족 | 목록이 없거나, 형식이 `[1-9][0-9]{0,18}`가 아니거나, 중복·옵션 반복·알 수 없는 옵션이면 거절 |
| 이관 대상 URL 명시 요구 | 충족 | `FARM_IDENTITY_MIGRATION_JDBC_URL`이 `jdbc:mysql://`로 시작해야 함. 비밀번호가 없으면 거절 |
| JPA validate만 사용 | 충족 | `hibernate.hbm2ddl.auto=validate`, `setGenerateDdl(false)`. 일반 앱도 `ddl-auto: validate` |
| Flyway 이력 확인만 | 충족 | `Flyway...load().info().applied()`만 호출. 소스 전체에 `migrate()`, `repair()`, `clean()`, `baseline()`, `outOfOrder` 호출이 없음 |
| 엔티티 스캔 분리 | 충족 | 일반 앱은 `@SpringBootApplication`(`com.wonday.farm`) 기본 스캔이고 `@EntityScan`이 없음. 이관 팩토리는 `setPackagesToScan("com.wonday.migration.identity")` |
| JDBC 직접 UPDATE 없음 | 충족 | `src/main/java`에 `JdbcTemplate`, `createStatement`, `prepareStatement`, `createNativeQuery`, `executeUpdate`, `UPDATE member`가 없음 |
| 자동 실행 경로 없음 | 충족 | 식별정보 관련 `ApplicationRunner`·`CommandLineRunner`가 없음. 기존 `DemoAccounts`의 runner는 데모 계정용이고 이번 기능과 무관 |
| 로그·출력 | 설계상 충족 | `main`이 logback을 끄고, 정제된 메시지만 출력하고 `System.exit(1)`. 예외 원문은 출력하지 않음 |

`MemberIdentityMigrationCommand`는 커버리지 0%예요. 지시에 따라 `main`과 private 메서드를 실행하지 않았기 때문이에요.

## 6. 관련 클래스 커버리지 (이번 6개 클래스 선택 실행 기준)

`build/reports/jacoco/jacocoUnitTestReport/jacocoUnitTestReport.xml`에서 클래스별로 계산했어요. 전체 프로젝트 커버리지가 아니에요. 90% 기준 검사나 설정 변경은 하지 않았어요.

| 클래스 | 라인 | 분기 | 미실행 |
|---|---|---|---|
| `IdentityNumber` | 5/5 (100%) | 6/6 | — |
| `IdentityCrypto` | 61/65 (93.8%) | 42/42 (100%) | 64–65, 81–82행: `protect`·`blindIndex`의 `GeneralSecurityException` catch. JDK 표준 알고리즘에서는 정상적으로 발생시킬 수 없어요 |
| `StoredIdentity` | 7/7 | 16/16 | — |
| `NationalityType` | 3/3 | 2/2 | — |
| `IdentityCryptoUnavailableException` | 2/2 | — | — |
| `Member` | 24/26 (92.3%) | 14/14 | 49–50행: JPA용 protected 기본 생성자 |
| `WorkerVerificationService` | 24/24 | 20/20 | — |
| `MemberIdentityMigrationService` | 68/68 | 60/60 | — |
| `MemberIdentityMigrationException` | 1/1 | — | — |
| `IdentityMigrationMember` | 17/17 | 14/16 (87.5%) | 조건식의 일부 단락 평가 조합 |
| `MemberIdentityMigrationCommand` | 0/76 | 0/44 | 실행하지 않았어요(5절 정적 검토) |

## 7. 미검증 영역

- **실제 DB와 V8:**
  - V8 적용, NULL 묶음·국적-종류 CHECK·UNIQUE(`identity_hmac`) 제약
  - ascii_bin 정렬 규칙
  - 기존 행이 NULL로 보존되는지
  - V7 다음에 V8이 순서대로 적용되는지
- **Repository 쿼리의 실제 동작:**
  - `findMatches`와 `findDomesticByIdentityHmac`의 MySQL 필터
  - `regexp_replace` 정규화, 대소문자 구분, `limit 2`
- **JPA와 트랜잭션:**
  - 실제 `JpaTransactionManager`의 격리 수준 적용과 읽기 전용 처리
  - PESSIMISTIC_WRITE 잠금, 동시성
  - flush·commit 실패 시 DB 전체 롤백
  - `DELAYED_ACQUISITION_AND_HOLD`, `rollbackOnCommitFailure`
  - 이관 팩토리의 엔티티 스캔, `hbm2ddl validate`의 실제 성공·실패
- **명령 실행:**
  - `MemberIdentityMigrationCommand`의 옵션 파싱, 환경변수 처리, Flyway 이력 판정, 로그 차단, 종료 코드
  - Gradle `migrateMemberIdentity` 태스크
  - 실제 미리보기와 적용
- **HTTP 계층:**
  - 보험 서버 토큰 인증, 컨트롤러·오류 응답 JSON, Spring 프로퍼티 바인딩
  - 예: `FARM_IDENTITY_LOOKUP_MODE` 오타 시 기동 실패 여부
- **아직 없는 기능:** 회원가입의 `protect`·`assignIdentity` 연결, UNIQUE 위반 응답 변환, V2 확인, `/me` 확장

## 8. 관찰 사항 (결함 아님)

- **O-1.** 이관 도구는 선택 여부와 관계없이 DB 전체에서 중복 번호나 형식 오류가 하나라도 있으면 전체를 중단해요.
  - 문서의 "병합 없이 중단" 정책과 같아요.
  - 실제 데이터에 오류 행이 있으면, 그 행을 정리하기 전까지 아무도 이관할 수 없어요.
- **O-2.** 미리보기에서도 선택한 미이관 회원에 대해 `protect`(암호화)를 실행해요. 결과는 버리고 저장하지 않아요. 키가 실제로 동작하는지 확인하는 효과가 있어요.
- **O-3.** 정적 판단이에요. `requireMigrations`는 V7이 `SUCCESS` 상태여야 통과해요. V7 파일이 없는 지금 브랜치에서는 DB에 V7이 적용돼 있어도 Flyway가 그 이력을 `SUCCESS`가 아닌 상태(MISSING 계열)로 표시할 가능성이 커요. 그러면 명령은 V7 병합 전까지 거절돼요. 문서의 "V7 병합 후 실행" 순서와 같고, 실행해서 확인하지는 않았어요.
- **O-4.** 일반 앱의 `Member`가 새 네 컬럼을 매핑하고 `ddl-auto: validate`를 쓰기 때문에, 이 브랜치의 앱은 V8이 적용되지 않은 DB에서 스키마 검증 단계에서 기동에 실패할 수 있어요. 정적 판단이고 실행하지 않았어요. 배포 순서를 지켜야 한다는 뜻이에요.

## 9. 보존 확인

- 운영 코드, Gradle, CI, 마이그레이션, 문서(이 보고서 제외)는 수정하지 않았어요.
  - `git status`의 기존 변경 8건과 신규 경로들이 그대로예요.
- 기존 테스트 파일은 수정하지 않았어요.
- 커밋, 푸시, PR, 브랜치 변경, 병합, rebase를 하지 않았어요.
- 다음은 실행하지 않았어요: 이관 명령(미리보기 포함), Flyway, DB, Testcontainers, 서버
- `.env`는 읽지 않았어요.
- job-location 작업 공간은 수정하지 않았어요.

## 10. PR 본문용 짧은 검증 요약

```
회원 식별정보 저장·V1 근로자 확인·JPA 이관 독립 단위 검증 (member-identity)
- 선택 실행 6개 클래스 120개 전부 통과
  (신규 93: IdentityNumber 28 / IdentityCrypto 25 / 저장값·엔티티 15 / 조회 모드 10 / 이관 서비스 15,
   기존 WorkerVerificationServiceTest 27)
- 확인 범위:
  - 정규화(공백·하이픈 제거, ASCII 13자리, 100자 상한)
  - AES-GCM 왕복·nonce, 변조·키·회원 ID·종류(AAD)·형식 거절
  - HMAC 동일성과 종류 무관 중복 계약
  - 키 오류 시 생성은 성공하고 사용 시 거절, 비노출
  - Member·이관 엔티티의 ID 일치·덮어쓰기 방지
  - LEGACY/ENCRYPTED 분기, 키 오류 503·fallback 없음, 0/1/2건 404/200/409, 응답 3필드
  - 이관: 사전 검사, 미리보기(읽기 전용·rollbackOnly·무변경), 적용 분기(SERIALIZABLE·PESSIMISTIC_WRITE·flush)
  - 이관: 중복·불일치·변조·외국인·누락 시 무변경 중단과 rollback 요청, 멱등
- 결함 없음
- 클래스별 커버리지(이번 선택 실행 기준):
  - 이관 서비스·조회 서비스는 라인·분기 100%
  - IdentityCrypto는 분기 100%
  - 이관 명령은 실행 금지로 0%(정적 검토)
- 미검증: 실제 MySQL(V8 제약·쿼리 필터), JPA 트랜잭션·잠금·롤백, 이관 명령 실행, HTTP·보안 계층
- 운영 코드 변경 없음, 커밋 없음
```
