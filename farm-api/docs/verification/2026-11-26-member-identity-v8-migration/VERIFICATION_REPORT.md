# V8 마이그레이션 통합 테스트 재검증 보고서 (FlywayMigrationTests)

- 검증일: 2026-11-26
- 작업 공간: `C:\Users\HCB\.codex\worktrees\member-identity\wonday`
- 브랜치: `member-identity`
- 상태:
  - HEAD는 `d15408c`예요.
  - `MERGE_HEAD`는 `3fb9038`이에요. origin/main을 `--no-commit --no-ff`로 병합하고 아직 커밋하지 않은 상태예요.
- 저장소 루트와 `farm-api`에는 `AGENTS.md`가 없어요.
- 대상은 `farm-api/src/test/java/com/wonday/farm/job/migration/FlywayMigrationTests.java`의 7개 테스트예요.
- 이 결과는 이 클래스만 실행한 결과예요.
  - 이전 단위 테스트 120개와 합치지 않았어요.
  - 실제 개발 DB, 실제 이관, JPA 이관의 잠금·롤백은 검증하지 않았어요.

## 1. 요약

| 클래스 | 테스트 | 성공 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|---|
| `FlywayMigrationTests` | **7** | 7 | 0 | 0 | 0 |

- 마이그레이션 결함과 테스트 코드 오류는 없었어요.
- V1~V8 적용 결과, 실행 개수, 기존 데이터 보존, V8의 NULL·CHECK·UNIQUE 제약이 테스트 기대대로 동작했어요.

## 2. 실행 전 확인

**태스크 연결** (`farm-api/build.gradle`)

- `integrationTest`는 `@Tag("integration")`만 포함하고 `known-defect`는 제외해요.
- `integrationTest`의 `dependsOn`은 없어요. `shouldRunAfter unitTest`가 있지만, 이것은 순서 지정일 뿐 `unitTest`를 실행시키지 않아요.
- `finalizedBy`가 걸린 태스크는 `fullTest`뿐이에요.
- `migrateMemberIdentity`는 별도 JavaExec 태스크라서 이 명령으로는 실행되지 않아요.
- 실제 실행 로그에도 컴파일 태스크(UP-TO-DATE)와 `:integrationTest`만 나왔어요. `unitTest`, `jacoco*`, `migrateMemberIdentity`, `bootRun`은 실행되지 않았어요.

**격리 설정** (`MySqlTestDatabase`)

- 정적 `MySQLContainer(mysql:8.4)`예요. `withReuse`가 없어서 JVM마다 새 컨테이너를 만들어요.
- 접속 주소는 컨테이너의 host와 mapped port로 만들어요. `.env`나 고정 포트를 사용하지 않아요.
- `FlywayMigrationTests`는 테스트마다 UUID가 붙은 새 스키마를 만들어요(`mig_*_xxxxxxxx`). Spring 컨텍스트는 쓰지 않아요.
- `~/.testcontainers.properties`에는 Docker 클라이언트 전략만 있고, reuse 설정은 없어요.

## 3. 실행 명령·환경·시간

```
cd farm-api
.\gradlew.bat --no-daemon integrationTest --tests "com.wonday.farm.job.migration.FlywayMigrationTests"
```

- **JDK:** OpenJDK 21.0.12.1 LTS (`jdk-21.0.12.1+1`)
- **MySQL 이미지:** `mysql:8.4` (`sha256:a81d0439…`). Testcontainers 2.0.5, Ryuk 0.14.0을 사용했어요.
- **Docker:** 서버 29.9.3 (linux)
- **소요 시간:** Gradle BUILD SUCCESSFUL 약 34초
  - 테스트 클래스 시간은 22.2초예요.
  - 첫 테스트(15.8초)에 컨테이너 기동 10.3초가 포함돼요. 나머지 테스트는 각각 약 1초였어요.
- **결과 파일:** `farm-api/build/test-results/integrationTest/TEST-com.wonday.farm.job.migration.FlywayMigrationTests.xml`

## 4. Testcontainers 격리 DB 사용 근거 (인증정보 생략)

- **테스트 로그:**
  - `Creating container for image: mysql:8.4`
  - `Container mysql:8.4 is starting: 8fc4eb92fc9d…`
  - `Container is started (JDBC URL: jdbc:mysql://localhost:1581/farm_db)`
  - Ryuk가 시작됐고, JVM 종료 시 정리한다고 나왔어요.
- **실행 중 `docker ps`:** Testcontainers 레이블이 붙은 새 컨테이너 2개가 있었어요.
  - `vigorous_elbakyan` (mysql:8.4)
  - `testcontainers-ryuk-…`
- **실행 후:** Testcontainers 레이블 컨테이너가 0개였어요. Ryuk가 정리했어요.
- **개발용 컨테이너 `wonday-mysql-1`:** 실행 전후가 같았어요.
  - ID `e68b40921a29`, 포트 3307, 시작 시각 `2026-11-26T00:45:11Z`, 재시작 0회
  - 테스트 DB는 다른 컨테이너(`8fc4eb92…`, 포트 1581)였어요.
- 개발 DB, 공유 DB, 운영 DB에는 접속하지 않았고, `.env`와 비밀키는 읽지 않았어요.

## 5. 항목별 결과

### 5.1 버전 목록과 실행 개수

| 테스트 | 결과 |
|---|---|
| `emptyDatabaseAppliesV1ThroughV8` | migrate 성공, `migrationsExecuted = 8`, 적용 버전이 정확히 `1~8`, 실패 상태 없음 |
| `existingV3DataIsPreservedAndUpgradedByV8` | V3까지 적용 → 가상 데이터 입력 → 업그레이드. `migrationsExecuted = 5`(V4~V8), 최종 버전이 정확히 `1~8` |

- 마이그레이션 파일: `V1`~`V8`이 모두 있어요. V7은 origin/main 병합으로 들어온 좌표 마이그레이션이에요.

### 5.2 컬럼과 기존 데이터 보존

- **기존 컬럼 검증 유지:**
  - `job_posting.time_zone`
  - `work_schedule.terms_version` (NOT NULL, 기본값 1)
  - `work_assignment.agreed_work_date`, `agreed_time_zone`, `confirmed_at_utc`
- **V8 컬럼:**
  - `nationality_type varchar(16)`, `identity_type varchar(32)`, `identity_ciphertext varchar(256)`, `identity_hmac varchar(64)`
  - 네 컬럼 모두 NULL을 허용해요.
  - 암호문과 HMAC 컬럼의 정렬 규칙은 `ascii_bin`이에요.
  - 빈 DB와 업그레이드 DB 모두 같은 결과예요.
- **업그레이드 후 회원 데이터:**
  - 다음이 그대로 유지돼요: 회원 ID, `password_hash`, 이름, 전화, 역할(EMPLOYER), 기존 식별번호 `111111-1111111`(가상 값)
  - 새 네 필드는 모두 NULL이에요. 국적을 추측하지 않고, 이관도 하지 않았어요.
- **업그레이드 후 공고·일정 데이터:**
  - 공고의 제목과 정원이 유지되고, `time_zone`은 NULL이에요.
  - 일정의 날짜와 상태가 유지되고, `terms_version`은 1이에요.

### 5.3 NULL·CHECK·UNIQUE

| 테스트 | 확인 내용 |
|---|---|
| `identityStorageAllowsLegacyNullsAndCompleteDomesticAndForeignValues` | 네 필드가 모두 NULL인 회원 2명을 허용해요(nullable UNIQUE). 내국인·외국인 전체 묶음 저장을 허용하고, 4행 모두 저장값 그대로 조회돼요 |
| `identityCheckRejectsEveryPartiallyPopulatedCombination` | 네 필드 중 일부만 채운 14개 조합(mask 1~14)이 모두 `ck_member_identity_storage` 위반 메시지로 거절돼요. 회원 수는 0이에요 |
| `identityCheckRejectsMismatchedTypesAndInvalidStorageValues` | 8가지 잘못된 값이 모두 `ck_member_identity_storage` 위반으로 거절돼요(아래 목록). 회원 수는 0이에요 |
| `identityHmacUniqueRejectsDuplicatesAcrossNationalityAndPreservesOriginal` | 내국인 행과 같은 HMAC을 가진 외국인 행이 `uq_member_identity_hmac` 위반으로 거절돼요. 회원 수는 1이고, 원래 행의 값이 그대로예요 |
| `identityCheckRejectsPartialUpdateAndPreservesStoredValues` | 정상 묶음에서 `identity_hmac`만 NULL로 바꾸는 UPDATE가 CHECK 위반으로 거절돼요. 기존 네 값이 그대로예요 |

`identityCheckRejectsMismatchedTypesAndInvalidStorageValues`에서 거절된 8가지는 다음과 같아요.

- 국적·종류 불일치 (양방향)
- 알 수 없는 국적, 알 수 없는 종류
- 소문자 국적, 소문자 종류
- 빈 암호문
- 63자 HMAC

메시지에 제약 이름이 들어 있는지 확인했기 때문에, 단순 SQL 문법 오류가 아니라 지정한 제약의 위반이라는 것을 구분할 수 있어요.

이 테스트들은 가상 암호문 문자열(`v1:synthetic…`)과 반복 문자 HMAC으로 DB 제약만 확인했어요. 실제 암호화 왕복이나 JPA 이관 도구를 검증한 것으로 확대해서 해석하면 안 돼요.

## 6. 남은 결함과 미검증 영역

**결함:** 없어요. 실패·오류가 없어서 환경 문제, 테스트 문제, 마이그레이션 결함으로 나눌 항목도 없어요.

**관찰 사항 (결함 아님, 테스트 범위 참고)**

- **O-1. 형식 검사는 애플리케이션 책임이에요.** DB CHECK는 HMAC 길이(64), 빈 암호문 금지, 국적·종류 쌍만 강제해요.
  - 16진수 소문자 여부와 `v1:` 접두사는 강제하지 않아요. 애플리케이션의 `StoredIdentity`가 검사해요(이전 단위 검증에서 확인).
  - 65자 이상 HMAC과 257자 이상 암호문은 CHECK가 아니라 VARCHAR 길이에서 막혀요. 이번 테스트에는 이 경우가 없어요.
- **O-2.** `emptyDatabaseAppliesV1ThroughV8`은 V7 좌표 컬럼과 CHECK를 따로 단언하지 않아요. 이번 요청 범위 밖이에요.
- **O-3.** 업그레이드 테스트의 기존 회원은 EMPLOYER 1명이에요. 기존 WORKER 행이나 `phone`이 NULL인 행의 보존은 별도로 확인하지 않았어요.

**미검증 영역**

- 실제 개발·공유·운영 DB에 V7·V8을 적용하는 것과 그 DB의 기존 데이터
- JPA 이관 도구(`migrateMemberIdentity`)의 미리보기·적용, SERIALIZABLE·PESSIMISTIC_WRITE 잠금, 동시성, 실제 롤백
- 애플리케이션 기동 시 `ddl-auto: validate`와 V8 스키마가 맞는지
- 다른 통합 테스트와 전체 테스트(실행 금지)

## 7. 병합·스테이징 상태 보존

- 실행 전후로 `git status --porcelain=v2`가 같았어요.
- staged diff와 unstaged diff의 SHA-256도 실행 전후로 같았어요(scratchpad에 비교 기록).
- `MERGE_HEAD`(`3fb9038`)와 병합 진행 상태를 유지했어요.
- 다음 git 작업은 하지 않았어요: `add`, `restore`, `reset`, `clean`, `stash`, 병합 중단·완료, 브랜치 변경, rebase, 커밋, 푸시
- 테스트 소스, 운영 소스, 마이그레이션, Gradle은 변경하지 않았어요.
- 새로 만든 파일은 이 보고서 하나뿐이에요(미추적 상태).
- 원래 `C:\Users\HCB\Desktop\wonday` 작업 공간은 건드리지 않았어요.

## 8. PR 리뷰 답변용 짧은 요약

```
V8 마이그레이션 테스트 재검증 결과 (FlywayMigrationTests만 실행)
- 실행: integrationTest --tests FlywayMigrationTests
  (JDK 21.0.12.1, Testcontainers 격리 mysql:8.4, 약 34초)
- 결과: 7개 전부 통과 (실패·오류·건너뜀 0)
- 빈 DB: V1~V8 8개 적용, 버전 목록 정확히 1~8
- V3 업그레이드: V4~V8 5개 추가 적용, 기존 회원(ID·해시·이름·전화·역할·기존 번호)·공고·일정 보존
  새 식별정보 4컬럼은 NULL 유지
- V8 제약:
  - nullable UNIQUE 다중 NULL 허용, 내국인·외국인 완전 묶음 저장
  - 부분 입력 14조합·종류 불일치·대소문자·빈 암호문·HMAC 길이 오류는 ck_member_identity_storage로 거절
  - 국적이 달라도 같은 HMAC은 uq_member_identity_hmac로 거절
  - 부분 UPDATE 거절 후 기존 값 유지
- 개발 DB 컨테이너는 변경 없음. 실제 이관·JPA 잠금·롤백은 범위 밖(미검증)
```
