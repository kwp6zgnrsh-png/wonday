# 회원 식별정보 저장 구조와 기존 근로자 확인 API

## 범위와 보존 기준

2026-11-26 전달된 멘토링 요구사항 중 식별정보 기반과 기존 V1 근로자 확인 API를 다룬다.
회원가입·V2 확인·급여 계좌·근무 완료·화면·`/api/auth/me` 응답 확장은 아직 구현하지 않는다.

- 원래 좌표 작업 공간은 그대로 두고 독립 worktree의 `member-identity` 브랜치에서 작성했다.
- 기준은 `origin/main` `8037e4b`다. 농업 기능 기준은 APP-01 PR #16을 포함한다.
- 회원 ID·로그인 ID·BCrypt 비밀번호·역할·전화번호·기존 주민번호 컬럼을 바꾸거나 삭제하지 않는다.
- 정부기관 인증이 아니라 등록 정보 대조다. 내외국인 구분은 명시적 입력이나 확인된 자료에 따르며 번호로 추정하지 않는다.
- 구현 단계에서 소스 컴파일을 확인했고, 이후 독립 단위 검증에서 관련 6개 클래스 120개가 모두 통과했다. 실제 DB 접근·스키마 적용·이관·서버 실행은 하지 않았다.

## 선행 좌표 V7과 배포 순서

**선행 좌표 기능은 별도 PR에서 V7을 사용한다. 충돌을 피하려고 이 기능은 V8을 사용한다.**

현재 이 worktree의 main에는 아직 V7 파일이 없다. 이 상태로 DB에 단독 적용하지 않는다.
좌표 PR merge 후 이 브랜치에 최신 main을 반영하고, V1~V8이 순서대로 존재하는지 확인한 뒤 배포한다.
V8을 먼저 적용하면 나중에 들어온 V7이 순서 밖 마이그레이션이 될 수 있다. 이를 outOfOrder 설정으로 우회하지 않는다.
기존 마이그레이션은 수정하지 않는다.

후속 통합 검증 시 `FlywayMigrationTests`의 최신 버전 기대값도 V7 반영 후 V8까지 맞춰야 한다.
이번에는 아직 없는 V7을 테스트 폴더에서 복사하거나 기존 통합 테스트를 약화하지 않았다.

## 새 회원 저장 필드

신규 `V8__add_member_identity_storage.sql`:

| 컬럼 | 형식 | 의미 |
| --- | --- | --- |
| nationality_type | VARCHAR(16) NULL | DOMESTIC / FOREIGN |
| identity_type | VARCHAR(32) NULL | RESIDENT_REGISTRATION_NUMBER / FOREIGN_REGISTRATION_NUMBER |
| identity_ciphertext | VARCHAR(256) NULL | 버전·nonce·인증 태그를 포함한 암호문 |
| identity_hmac | VARCHAR(64) NULL, UNIQUE | 정규화 번호의 HMAC-SHA256, 소문자 16진수 |

기존 행의 새 네 필드는 NULL로 남긴다. 새 필드는 모두 NULL이거나 완전한 묶음이어야 하며 국적·식별번호 종류 쌍은 CHECK로 제한한다.
주민번호 컬럼을 자동 UPDATE하거나 삭제하는 SQL은 없다. 기존 중복 번호가 있어도 자동 병합하지 않는다.

내외국인별 테이블은 나누지 않는다. 식별번호 종류는 `NationalityType.identityType()`으로 서버가 정한다.
중복 확인 HMAC에는 종류를 넣지 않아 동일한 정규화 번호의 중복을 두 종류에 걸쳐 차단한다.
이미 이관되지 않은 기존 데이터와의 중복은 이관 도구가 전체 자료를 확인한다.
새 회원가입을 연결하기 전에 이관과 조회 전환을 완료해야 한다.

## 암호화와 저장 흐름

`IdentityNumber`는 공백·하이픈 제거 후 ASCII 숫자 13자리만 허용한다.
실제 주민번호 체크섬이나 외국인 국적 판단은 하지 않는다.

`IdentityCrypto`:
- AES-256-GCM, 매 암호화마다 새로운 12바이트 nonce, 128비트 인증 태그.
- AAD에 형식 버전·회원 ID·식별번호 종류를 연결한다. 다른 회원이나 종류로 암호문을 옮겨 복호화하지 못하게 한다.
- 암호화 키와 다른 HMAC-SHA256 키를 사용한다.
- HMAC 입력에는 회원 식별정보 용도의 고정 도메인과 정규화 번호를 사용한다.
- 암호문은 `v1:`과 URL-safe Base64 형식이다. 암호문·HMAC에도 toString 노출 방지를 적용한다.
- 키·평문·암호문·HMAC을 로그나 예외 원인 체인에 넣지 않는다.
- 복호화 메서드는 명시적 이관 무결성 확인용이며 사용자 응답으로 번호를 반환하지 않는다.

`Member.assignIdentity`는 해당 회원 ID에 결합된 저장 결과만 받으며 기존 식별정보를 덮어쓰지 않는다.
후속 회원가입은 회원 ID 생성 후 같은 트랜잭션에서 `crypto.protect`와 `assignIdentity`를 연결해야 한다.
DB UNIQUE 위반의 `IDENTITY_ALREADY_EXISTS` 응답 변환은 해당 가입 기능에서 연결한다.

참고한 표준:
- [JDK Cipher: GCM, AAD, IV 재사용 금지](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Cipher.html)
- [OWASP Cryptographic Storage](https://cheatsheetseries.owasp.org/cheatsheets/Cryptographic_Storage_Cheat_Sheet.html)

## 키 설정

| 환경변수 | 값 |
| --- | --- |
| FARM_IDENTITY_ENCRYPTION_KEY | 무작위 32바이트를 Base64로 인코딩한 값 |
| FARM_IDENTITY_HMAC_KEY | 앞 키와 다른 무작위 32바이트의 Base64 |
| FARM_IDENTITY_LOOKUP_MODE | LEGACY 또는 ENCRYPTED. 기본 LEGACY |

실제 키는 저장소·PR·화면·로그에 넣지 않는다. 서버 프로세스와 이관 명령에 동일한 키를 안전하게 제공한다.
키를 임의로 바꾸면 이전 암호문의 복호화나 기존 HMAC 조회를 할 수 없다. 키 회전·재암호화·재색인은 별도 계획이 필요하다.
애플리케이션은 키가 없어도 기존 로그인·공고 기능을 기동한다.
암호화 작업이나 ENCRYPTED 조회를 호출할 때 미설정·길이 오류·동일 키이면 안전하게 거절하며 V1은 503을 반환한다.

## 기존 V1 API의 단계적 전환

경로·요청·응답·서버 토큰·오류 코드 계약은 유지한다.

`POST /api/internal/insurance/worker-verifications`

1. **LEGACY (기본):** 기존 주민번호 컬럼 조회를 유지한다. 명시된 FOREIGN은 제외하고 새 필드가 NULL인 기존 행은 이관 전 호환 대상으로 읽기만 한다. 기존 값으로 nationality를 자동 채우지 않는다. 키 없이도 기존 대조를 유지한다.
2. **ENCRYPTED:** 입력의 정규화 번호를 HMAC으로 만들고 DOMESTIC + RESIDENT_REGISTRATION_NUMBER + 이름·전화번호·HMAC이 맞는 WORKER를 조회한다. 기존 평문 컬럼은 조회하지 않는다.

전환은 이관·검증 완료 후 환경변수로 명시한다. ENCRYPTED에서 키 오류가 생겨도 자동으로 LEGACY 조회로 후퇴하지 않는다.
V1에서 FOREIGN의 암호화 식별정보를 조회하는 기능은 제공하지 않는다. 외국인은 후속 V2 API에서 연결한다.
성공 시 `workerId`, `name`, `phone`만 반환하며 어떤 항목이 불일치했는지나 식별번호를 반환하지 않는다.
기존 중복 대조의 409 WORKER_IDENTITY_AMBIGUOUS 처리도 보존한다.

**이 구현 직후 기본 모드에서 모든 번호가 이미 암호화됐다고 해석하지 않는다.**
실제 이관과 조회 모드 전환은 아직 수행하지 않았다.

## 명시적 기존 내국인 이관 도구

`MemberIdentityMigrationCommand`는 웹 서버·전체 Spring 컨텍스트를 띄우지 않고 이관 전용 JPA 팩토리만 구성하는 명령이다.
서버 기동 시 자동 실행되는 ApplicationRunner나 공개 이관 API는 없다.

- `MemberIdentityMigrationService`: JPQL로 회원을 읽고 관리 상태 엔티티의 변경 감지·flush로 새 네 필드를 저장한다. 직접 JDBC Connection·Statement나 UPDATE SQL을 사용하지 않는다.
- `IdentityMigrationMember`: 이관 전용 member 매핑이다. 일반 앱의 엔티티 스캔 밖인 `com.wonday.migration.identity`에 두며 이관 팩토리만 스캔한다. 기존 번호 컬럼은 읽기 전용이고 역할·비밀번호·전화번호 등은 매핑하지 않아 이관으로 변경하지 않는다.
- `JpaTransactionManager`와 `TransactionTemplate`: 적용은 SERIALIZABLE·PESSIMISTIC_WRITE, 미리보기는 읽기 전용·REPEATABLE_READ와 명시적 rollbackOnly로 처리한다. flush·commit 실패도 전체 롤백으로 전파한다.
- 트랜잭션별 격리 수준을 적용할 수 있도록 Hibernate 연결을 팩토리 종료까지 유지한다. commit 실패 시에도 rollback을 시도하도록 설정한다. 실제 장애 상황의 DB 결과는 후속 통합 검증 대상이다.
- ORM 자동 DDL은 `validate`만 사용한다. Flyway는 `info()`로 이력만 확인하며 migrate·repair·clean은 호출하지 않는다.
- 별도 JavaExec 프로세스에서 프레임워크 로그를 차단하고 이관 명령의 정제된 결과만 출력해 SQL 예외·바인딩 값이 로그에 나가지 않게 한다.

준비:
1. 먼저 좌표 V7 merge, 브랜치 갱신, V7·V8의 순차 적용을 확인한다.
2. 원본 백업과 독립 DB 검증 후 작업한다. 다른 AI의 좌표 테스트 DB에 연결하지 않는다.
3. 자료로 직접 확인한 내국인 회원 ID만 선정한다. 데모 계정도 임의 국적·번호를 채우지 않는다.
4. 위 두 키와 `MYSQL_USER`, `MYSQL_PASSWORD`를 명령의 환경변수로 제공한다.
5. `FARM_IDENTITY_MIGRATION_JDBC_URL`을 명시적으로 지정한다. 예: `jdbc:mysql://127.0.0.1:3307/farm_db`. DB 인증정보는 URL에 넣지 않는다.
6. 이 명령은 전체 Spring 앱을 띄우지 않으므로 프로젝트 .env를 자동으로 읽지 않는다. JPA용 DataSource도 명시한 이관 환경변수만 사용한다.

미리보기 예시 (12·15는 실제 확인한 회원 ID로 교체):

```powershell
.\gradlew.bat migrateMemberIdentity -PconfirmedDomesticMemberIds=12,15
```

실제 적용은 별도 명시가 필요하다. 이번 작업에서는 실행하지 않았다.

```powershell
.\gradlew.bat migrateMemberIdentity -PconfirmedDomesticMemberIds=12,15 -PapplyIdentityMigration=true
```

동작:
- 기본은 미리보기이고 DB를 변경하지 않는다.
- V7·V8 적용 이력을 먼저 확인한다.
- 전체 기존 번호·암호화 저장값의 정규화·중복·일치 여부를 확인한다.
- 기존 암호문은 회원 ID·종류에 맞게 복호화해 저장 HMAC과도 일치하는지 확인한다.
- 중복·형식 오류·저장값 충돌·없는 회원·번호 누락은 원문 없이 회원 ID만 보고하고 중단한다.
- 선택 목록 중 완전히 미이관된 확인 내국인만 새 네 필드를 채운다.
- 기존에 완전히 이관된 내국인은 확인 후 건너뛰고 암호문을 다시 만들지 않는다.
- 적용 시 전체 회원 행을 잠그는 SERIALIZABLE 트랜잭션으로 검증·저장을 처리한다. 운영 적용은 쓰기 작업이 없는 시간대를 사용한다.
- 오류 발생 시 이번 이관을 전부 롤백한다. 기존 번호 컬럼·ID·역할·전화번호·비밀번호는 바꾸지 않는다.
- 결과는 선택·신규·이미 이관됨 개수만 출력한다.
- 완료 후 독립 검증을 거쳐 FARM_IDENTITY_LOOKUP_MODE=ENCRYPTED로 전환한다.
- 현재 공고·확정 약정·변경 기록·계좌 데이터는 다루지 않는다.

## 검증 상태와 후속 작업

- 이 변경은 별도 작업 공간에서 구현했고 원래 job-location 파일을 수정하지 않았다.
- 독립 단위 검증: 신규 93개와 기존 WorkerVerificationServiceTest 27개를 선택 실행해 총 120개가 통과했다. 실패·오류·건너뜀은 모두 0이다. [검증 보고서](verification/2026-11-26-member-identity/VERIFICATION_REPORT.md)에 실행 명령과 범위를 기록했다.
- 기존 WorkerVerificationServiceTest는 생성자 인자만 맞췄으며 LEGACY 테스트 의미를 바꾸지 않았다.
- 암호화 왕복·변조·nonce·HMAC, 모드별 조회, 이관 중복·멱등·트랜잭션 설정과 롤백 요청을 단위 검증했다. mock 기반 이관 테스트가 실제 DB 롤백을 입증하지는 않는다. 실제 MySQL 쿼리·제약·잠금·트랜잭션과 명령 실행은 후속 통합 검증 대상이다.
- JPA 전환 후에도 실제 DB·미리보기·이관 명령을 실행하지 않았다. 실제 격리 수준·잠금·롤백과 이관 전용 엔티티 스캔은 후속 통합 검증 대상이다.
- V7 merge 후 V8 통합, 버전 기대값 정리, 회원가입·V2·/me 응답 확장이 후속 범위다.
