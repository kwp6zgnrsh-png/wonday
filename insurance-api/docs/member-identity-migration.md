# 보험 회원 식별정보 수동 이관

## 역할

| 처리 | 실행 위치 |
|---|---|
| 테이블·컬럼·제약조건 변경 | 기존 Flyway SQL |
| 과거 평문 주민번호의 암호화·평문 제거 | 명시적인 `migrateMemberIdentity` Java 명령 |
| 신규 주민번호·외국인등록번호 저장 | 기존 온라인 저장 기능, AES-256-GCM 유지 |
| 서버 시작 | 평문 잔여 여부만 검사. 개인정보 자동 이관 없음 |

명령·서비스는 Spring 빈이 아니다. 웹 서버·전체 애플리케이션 컨텍스트·농업 API·스케줄러를 실행하지 않는다.
이관 전용 JPA 엔티티는 `com.wonday.migration.insurance.identity`에 두어 일반 보험 앱의 스캔에서 제외한다.

## V8 이력 호환

- 과거 V8은 기존 평문 번호를 서버 시작 시 암호화했다. 이제 V8은 데이터 작업 없는 호환 마커로만 유지한다.
- 버전 `8`, 설명 `encrypt legacy member identity`, 유형 `JDBC`, 기존 클래스명, 체크섬 `1`은 그대로 유지한다.
- `@Component`를 제거하고 별도 설정에서 마커만 명시적으로 등록한다. 암호화 도구를 마커에 주입하지 않는다.
- 이미 V8을 적용한 DB는 기존 이력과 암호문을 유지하고, 미적용 DB는 마커를 기록한 뒤 이후 SQL 버전을 적용한다.
- Flyway 완료 후 시작 검사를 수행한다. 평문이 있으면 서버 시작이 중단되지만 V8 이력은 실패 상태가 되지 않는다.
- 명령은 Flyway 이력을 조회만 한다. `migrate`, `repair`, `clean`, 체크섬·버전·성공 상태의 수정은 하지 않는다.
- 과거 실행에서 이미 실패한 Flyway 이력이 있다면 담당자가 원인을 확인해 별도로 복구한다. 이 도구는 해당 이력을 숨기거나 고치지 않는다.

## 실행 순서

1. 이전 보험 앱·배치를 중단하고 DB를 백업한다. 기존 암호문이 있는 DB는 기존 암호화 키를 그대로 사용한다.
2. 보험 V7 SQL이 정상 적용되어 있어야 한다. 새 서버를 한 번 시작하면 SQL·호환 마커가 적용되고, 평문이 있으면 시작 검사에서 중단된다.
3. 아래 환경변수를 실행 프로세스에 전달한다. Java 명령은 `.env`·Spring의 `application.yaml`을 자동으로 읽지 않는다.
4. 직접 내국인임을 확인한 회원 ID만 지정해서 미리보기를 실행한다.
5. 결과 확인 후 같은 ID에 적용 옵션을 지정한다. 모든 평문 대상의 이관이 끝나면 보험 서버를 다시 시작한다.

| 환경변수 | 값 |
|---|---|
| `INSURANCE_IDENTITY_MIGRATION_JDBC_URL` | 대상 MySQL의 전체 JDBC URL, 필수. 농업 DB URL을 사용하지 않음 |
| `MYSQL_USER` | 이관 DB 계정, 생략 시 `wonday` |
| `MYSQL_PASSWORD` | 이관 DB 비밀번호, 필수 |
| `INSURANCE_IDENTITY_ENCRYPTION_KEY` | 보험 앱과 같은 32바이트 Base64 키, 필수 |

```powershell
cd insurance-api
$env:INSURANCE_IDENTITY_MIGRATION_JDBC_URL = "jdbc:mysql://127.0.0.1:3306/insurance_db"

# 기본: 미리보기, DB 변경 없음
.\gradlew.bat migrateMemberIdentity -PconfirmedDomesticMemberIds=11,12

# 실제 반영
.\gradlew.bat migrateMemberIdentity -PconfirmedDomesticMemberIds=11,12 -PapplyIdentityMigration=true
```

성공 출력은 선택·신규·이미 이관됨 건수다. 오류 안내에는 확인할 회원 ID가 포함될 수 있다.
번호·암호문·키·비밀번호·접속 URL은 출력하지 않는다.
ID는 실제 확인한 목록으로 교체한다. 빈 목록, 중복·잘못된 ID, 알 수 없는 CLI 옵션은 거부한다.
동일 main 클래스를 직접 실행할 때는 `--confirmed-domestic-member-ids=11,12`와 선택적인 `--apply`를 사용한다.

## 검증·저장 규칙

- 선택한 회원만 읽는다. 존재하지 않는 회원, 번호 미확보·잘못된 형식, 외국인, 일관되지 않은 저장 정보는 전체 선택 건을 실패시킨다.
- 기존 번호의 하이픈·공백을 제거한 뒤 숫자 13자리인지 검사한다. 국적을 추측하거나 번호를 생성하지 않는다.
- 이미 이관된 내국인은 복호화·형식을 검증하고 기존 암호문을 유지한다. 키 불일치·변조도 같은 오류로 중단한다.
- AES-256-GCM 형식과 회원 ID·식별번호 종류를 포함한 AAD는 온라인 저장과 동일하다. 새로운 키나 암호문 포맷을 만들지 않는다.
- 미리보기는 읽기 전용 트랜잭션 후 롤백한다.
- 반영은 선택 회원을 ID순으로 `PESSIMISTIC_WRITE` 잠금하고, 전체 검증 후 JPA 변경 감지로 저장한다.
- 암호문 저장과 `resident_registration_number = NULL`을 한 트랜잭션에서 처리한다. 저장·flush·commit 실패는 전체 롤백한다.
- 이름·전화번호·비밀번호·역할·비선택 회원·Flyway 이력은 변경하지 않는다.
- 일부 ID만 이관하고 나머지 평문이 남아 있으면 서버 시작 검사는 계속 차단한다. 미확인 회원은 별도 확인 후 다음 명령에서 처리한다.

현재 실행 도구의 범위는 확인된 기존 내국인의 평문 주민번호다. 외국인등록번호의 과거 데이터 이관·키 교체·국적 정정은 포함하지 않는다.
