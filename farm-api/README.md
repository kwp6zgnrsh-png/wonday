# 농업 API

## 보험 서버 연동

본인 확인, 개인별 근무 변경 목록, 확정 약정 상세 API의 사용 방법과 후속 연결 사항은 [보험 연동 API 문서](docs/insurance-integration-api.md)를 참고한다. 서버 전용 Bearer 토큰을 사용하며 농업 앱의 로그인 세션과 분리한다. 팀 합의에 따라 보험 서버가 동의를 관리하고 농업 서버는 서버 토큰과 근로자·근무 소유 관계를 확인한다. 새 V5는 아직 DB에 적용하지 않았다.

## 공고와 여러 날짜 일정 등록

고용주로 로그인한 뒤 `POST /api/job-postings`에 JSON을 보낸다. 요청에는 `FARMSESSION` 쿠키와 현재 세션의 `X-CSRF-TOKEN` 헤더를 포함한다. 소유자 ID는 로그인 세션에서 가져온다.

```json
{
  "title": "사과 수확 근로자 모집",
  "description": "사과 수확과 운반 작업입니다.",
  "cropCode": "<팀에서 합의할 작목 코드>",
  "workTypeCode": "<팀에서 합의할 작업 유형 코드>",
  "workLocation": "충청북도 충주시 예시농장",
  "timeZone": "GMT+9",
  "headcount": 2,
  "dailyWage": 120000,
  "schedules": [
    {
      "workDate": "2026-11-30",
      "plannedStartAt": "2026-11-30T08:00:00",
      "plannedEndAt": "2026-11-30T16:00:00",
      "breakStartAt": "2026-11-30T12:00:00",
      "breakEndAt": "2026-11-30T13:00:00"
    },
    {
      "workDate": "2026-12-02",
      "plannedStartAt": "2026-12-02T08:00:00",
      "plannedEndAt": "2026-12-02T12:00:00",
      "breakStartAt": null,
      "breakEndAt": null
    }
  ]
}
```

작목·작업 유형 코드는 팀 공통 목록이 미정이다. 현재는 필수 여부와 DB 길이만 확인하고 입력된 문자열을 저장한다. 예시의 자리표시자는 팀에서 합의한 코드로 교체한다.

성공 응답은 `201`이며 `jobPostingId`, 세션에서 확인한 `employerId`, 날짜별 공통 `headcount`, `schedules` 배열의 `workScheduleId`·`workDate`·`status`를 반환한다. 새 일정의 상태는 `OPEN`, `terms_version`은 1이다. 날짜 전체를 검증한 뒤 공고와 모든 일정을 같은 트랜잭션으로 저장한다.

| 오류 | 상태 코드 | 응답의 code |
| --- | --- | --- |
| 로그인 세션 없음 | 401 | 기존 인증 진입점 응답 |
| 유효한 CSRF 토큰을 가진 근로자의 등록 요청 | 403 | `EMPLOYER_ROLE_REQUIRED` |
| 필수 항목·길이·양수 조건 오류 | 400 | `INVALID_JOB_POSTING` |
| 중복 근무일 | 400 | `DUPLICATE_WORK_DATE` |
| 작업지 시간대가 `GMT+9`와 다름 | 400 | `INVALID_TIME_ZONE` |
| 근무일과 시작 일자의 불일치·종료 순서 오류 | 400 | `INVALID_WORK_TIME` |
| 휴게시간 한쪽 누락·순서·범위 오류 | 400 | `INVALID_BREAK_TIME` |
| DB 범위·정밀도 초과 또는 작업지에서 해석이 모호한 일시 | 400 | `INVALID_LOCAL_TIME` |
| JSON 역직렬화 오류 | 400 | `INVALID_REQUEST_BODY` |

팀 합의에 따라 `timeZone`은 정확히 `GMT+9`만 허용하며 저장 시 같은 표기를 유지한다. 기존에 저장된 시간대 값은 그대로 보존한다. 시간은 해당 시간대의 현지 일시로 받고 시작 날짜를 `workDate`로 사용한다. 종료는 시작 이후여야 하며, 날짜를 넘기는 근무도 입력할 수 있다. 일시 정밀도는 MySQL과 동일한 마이크로초까지 지원한다.

정원과 일급에는 따옴표 없는 JSON 정수를 입력한다. 소수와 숫자 문자열은 `400 INVALID_REQUEST_BODY`로 거절한다. 일시는 `2026-11-30T08:00:00`처럼 시간대 접미사 없이 입력하며, `Z`나 `+09:00`이 붙은 일시도 같은 오류로 거절한다. 휴게시간 오류의 `field`는 누락된 시작·종료 항목 또는 근무 범위를 벗어난 항목을 가리킨다.

날짜·일시는 JSON 문자열로 입력한다. `[2026,11,30]` 같은 배열은 거절하며, 날짜와 시간 사이에는 대문자 `T`를 사용한다. 소문자 `t`가 포함된 일시도 `400 INVALID_REQUEST_BODY`로 거절한다.

V4는 공고의 `time_zone`과 일정의 `terms_version`을 추가한다. 기존 공고의 시간대는 실제 작업지 확인 후 보완해야 하므로 DB에서 NULL을 허용하고, 신규 API 등록에는 시간대를 필수로 받는다. 공통 정원·일급은 기존 `default_headcount`, `default_daily_wage` 컬럼에 매핑한다. 기존 행의 시간대 보완 후 DB의 NOT NULL 제약을 적용하는 후속 마이그레이션이 필요하다.

## 내 공고와 날짜별 모집 현황 조회 (JOB-05)

고용주로 로그인한 후 `GET /api/job-postings/mine`을 호출한다. 소유자 ID는 세션에서 가져오며 요청 파라미터로 지정하지 않는다. `FARMSESSION` 쿠키를 유지하고 호출하면 된다. 성공은 `200`이며 본인 공고가 없으면 `{"jobPostings":[]}`를 반환한다. 미인증 요청은 `401`, 근로자는 `403 EMPLOYER_ROLE_REQUIRED`를 받는다.

응답의 `jobPostings`에는 공고 ID·제목·설명·작목·작업 유형·작업지·시간대·일일 정원·일급과 `schedules`가 들어간다. 공고는 ID 내림차순, 각 공고의 일정은 근무일·일정 ID 오름차순으로 제공한다. 기존 공고의 시간대가 비어 있으면 `timeZone`은 null이다.

각 일정에는 ID·근무일·예정 근무 및 휴게 일시·모집 상태·일정 버전과 아래 집계값을 제공한다.

| 응답 필드 | 계산 기준 |
| --- | --- |
| `applicantCount` | 해당 일정의 총 지원 행 수. PENDING·SELECTED·NOT_SELECTED 모두 포함 |
| `confirmedCount` | 해당 일정의 지원에 연결된 확정 근무 생성 건수 |
| `remainingSlots` | `max(공고 headcount - confirmedCount, 0)` |

남은 자리는 확정 근무 생성 수 기준의 통계값이다. 모집 상태가 `CLOSED` 또는 `REMOVED`이면 자리가 남아 있어도 신규 지원을 허용하는 의미가 없다. 확정 후 취소된 자리의 재모집 가능 여부는 팀 합의 대기 중이며, 이번 API는 그 여부를 판정하거나 모집 상태를 바꾸지 않는다. 현재 스키마에는 개인별 `work_change`가 없으므로 취소 건의 별도 집계는 해당 기능과 정책 확정 후 연결한다.

고용주 조건은 모든 공고·일정·집계 쿼리에 적용한다. JPA 저장소의 네이티브 집계로 지원과 확정 근무를 한 번에 읽으며, 공고마다 개별 집계 쿼리를 실행하지 않는다. MySQL의 REPEATABLE_READ 읽기 트랜잭션에서 공고·일정·집계에 같은 스냅샷을 사용한다.

## 농업 API 로그인

루트 README의 DB 설정을 마친 후, 데모 계정을 만들려면 로컬 개발 DB에 연결된 PowerShell에서 다음과 같이 실행한다. 두 비밀번호는 예시이며, 각자 정한 값으로 바꾼다.

```powershell
cd farm-api
$env:SPRING_PROFILES_ACTIVE = 'farm-demo'
$env:FARM_DEMO_EMPLOYER_PASSWORD = 'local-employer-demo'
$env:FARM_DEMO_WORKER_PASSWORD = 'local-worker-demo'
$env:SERVER_ADDRESS = '127.0.0.1'
.\gradlew.bat bootRun
```

`demo-employer`는 `EMPLOYER`, `demo-worker`는 `WORKER`다. `farm-demo` 프로필로 시작하면 최초 실행 시 비밀번호를 BCrypt로 해시하여 저장한다. 같은 로그인 ID가 이미 있으면 기존 계정과 비밀번호를 그대로 사용한다. 프로필을 끄더라도 이미 만들어진 계정은 DB에 남으므로 전용 로컬 개발 DB에서 실행한다.

브라우저에서 `http://127.0.0.1:8081/demo/login`을 열고 데모 아이디와 비밀번호를 입력한다. 로그인하면 서버가 반환한 계정 역할에 따라 ‘고용주님, 반갑습니다.’ 또는 ‘근로자님, 반갑습니다.’가 표시된다. 로그아웃한 뒤 다른 계정으로 로그인할 수 있다. `SERVER_PORT=18081`로 실행했다면 주소에도 `18081`을 사용한다. 데모 화면은 `farm-demo` 프로필로 실행할 때 제공된다.

| 요청 | 처리 |
| --- | --- |
| `GET /api/auth/csrf` | `headerName`, `token`을 받는다. |
| `POST /api/auth/login` | `application/x-www-form-urlencoded` 본문에 `loginId`, `password`를 보내고 CSRF 헤더를 설정한다. URL에 쿼리 문자열이 있으면 `400`, 본문 형식이 다르면 `415`다. 성공은 `204`, 자격 증명 오류나 UTF-8 기준 72바이트를 넘는 비밀번호는 `401`이다. |
| `GET /api/auth/me` | 세션의 계정 ID, 이름, 역할을 받는다. 로그인하지 않았으면 `401`이다. |
| `POST /api/auth/logout` | 로그인 후 새로 발급받은 CSRF 토큰을 헤더에 보내면 현재 세션이 종료된다. 성공은 `204`다. |

요청 사이에 `FARMSESSION` 쿠키를 전달한다. 로그인과 로그아웃 이후에는 다음 `POST` 전에 CSRF 토큰을 다시 받는다. 다른 역할을 확인하려면 로그아웃 후 새 토큰을 받아 다시 로그인한다.

## 테스트와 커버리지

기능 구현 단계에는 `.\gradlew.bat test`가 **단위 테스트·단위 JaCoCo 리포트·90% 라인 커버리지 검사**를 실행한다. CI의 farm-api 단계는 `unitTest jacocoUnitTestReport jacocoUnitTestCoverageVerification bootJar`로 같은 검증과 JAR 빌드를 실행한다. 통합 테스트는 기능이 갖춰진 뒤 Docker를 켜고 `.\gradlew.bat fullTest`로 실행한다. 통합 테스트는 Testcontainers로 실행마다 새 MySQL 8.4 컨테이너를 띄우므로 로컬 개발 DB(`.env`의 `farm_db`)를 사용하지 않는다.

| 명령 | 실행 범위 | 라인 기준 | 리포트 |
| --- | --- | --- | --- |
| `.\gradlew.bat test` | 단위: `@Tag("integration")`이 없는 테스트, 단위 리포트·기준 검사 포함 | 90% 이상 | `build/reports/jacoco/jacocoUnitTestReport/html/index.html` |
| `.\gradlew.bat unitTest` | 단위 테스트만 | 검사 없음 | `build/reports/tests/unitTest/index.html` |
| `.\gradlew.bat integrationTest` | 통합: `@Tag("integration")` + `@SpringBootTest` (Docker 필요) | 80% 이상 (`fullTest`에서 검사) | `build/reports/jacoco/jacocoIntegrationTestReport/html/index.html` |
| `.\gradlew.bat fullTest` | 단위 + 통합, 커버리지 기준 둘 다 (Docker 필요) | 90% / 80% | 위 두 리포트와 합산 `build/reports/jacoco/test/html/index.html` |

새 Spring 통합 테스트 클래스에는 `@Tag("integration")`과 `@Import(MySqlTestDatabase.class)`를 붙인다. 태그가 없으면 단위 테스트로 실행된다. 데모 프로필 테스트만 전용 인메모리 H2에서 계정을 등록한다.

운영 코드 결함을 재현하는 테스트에는 `@Tag("known-defect")`를 붙인다. 이 테스트는 결함이 고쳐지기 전까지 실패하므로 `test`에서 빠지며, `.\gradlew.bat knownDefectTest`로 따로 실행한다. 결함을 고친 뒤 태그를 지우면 일반 테스트로 실행된다. 두 측정 모두 `FarmApiApplication`을 제외하고, 단위 측정은 필터 체인 조립만 하는 `SecurityConfig`도 제외한다. 각 리포트 폴더 옆에 같은 이름의 XML 파일도 만들어진다.
