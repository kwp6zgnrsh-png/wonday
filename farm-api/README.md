# 농업 API

## 공고와 여러 날짜 일정 등록

고용주로 로그인한 뒤 `POST /api/job-postings`에 JSON을 보낸다. 요청에는 `FARMSESSION` 쿠키와 현재 세션의 `X-CSRF-TOKEN` 헤더를 포함한다. 소유자 ID는 로그인 세션에서 가져온다.

```json
{
  "title": "사과 수확 근로자 모집",
  "description": "사과 수확과 운반 작업입니다.",
  "cropCode": "<팀에서 합의할 작목 코드>",
  "workTypeCode": "<팀에서 합의할 작업 유형 코드>",
  "workLocation": "충청북도 충주시 예시농장",
  "timeZone": "Asia/Seoul",
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
| 작업지 시간대 오류 | 400 | `INVALID_TIME_ZONE` |
| 근무일과 시작 일자의 불일치·종료 순서 오류 | 400 | `INVALID_WORK_TIME` |
| 휴게시간 한쪽 누락·순서·범위 오류 | 400 | `INVALID_BREAK_TIME` |
| DB 범위·정밀도 초과 또는 작업지에서 해석이 모호한 일시 | 400 | `INVALID_LOCAL_TIME` |
| JSON 역직렬화 오류 | 400 | `INVALID_REQUEST_BODY` |

시간은 작업지 시간대의 현지 일시로 받고 시작 날짜를 `workDate`로 사용한다. 종료는 시작 이후여야 하며, 날짜를 넘기는 근무도 입력할 수 있다. 일시 정밀도는 MySQL과 동일한 마이크로초까지 지원한다. 일광 절약 시간 전환으로 존재하지 않거나 두 번 존재하는 현지 일시는 거절한다.

V4는 공고의 `time_zone`과 일정의 `terms_version`을 추가한다. 기존 공고의 시간대는 실제 작업지 확인 후 보완해야 하므로 DB에서 NULL을 허용하고, 신규 API 등록에는 시간대를 필수로 받는다. 공통 정원·일급은 기존 `default_headcount`, `default_daily_wage` 컬럼에 매핑한다. 기존 행의 시간대 보완 후 DB의 NOT NULL 제약을 적용하는 후속 마이그레이션이 필요하다.

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

Docker를 켠 상태에서 데모 프로필을 설정하지 않은 터미널로 `farm-api` 폴더에서 `.\gradlew.bat test`를 실행한다. `test`는 단위 테스트와 통합 테스트를 모두 실행하고, 라인 커버리지가 기준에 못 미치면 빌드를 실패시킨다. 통합 테스트는 Testcontainers로 실행마다 새 MySQL 8.4 컨테이너를 띄우므로 로컬 개발 DB(`.env`의 `farm_db`)를 사용하지 않는다.

| 종류 | 구분 | 단독 실행 | 라인 기준 | 리포트 |
| --- | --- | --- | --- | --- |
| 단위 | `@Tag("integration")`이 없는 테스트, Spring 컨텍스트 없음 | `.\gradlew.bat unitTest` | 90% 이상 | `build/reports/jacoco/jacocoUnitTestReport/html/index.html` |
| 통합 | `@Tag("integration")` + `@SpringBootTest` | `.\gradlew.bat integrationTest` | 80% 이상 | `build/reports/jacoco/jacocoIntegrationTestReport/html/index.html` |
| 합산 | 두 실행 결과 | `.\gradlew.bat test` | — | `build/reports/jacoco/test/html/index.html` |

새 Spring 통합 테스트 클래스에는 `@Tag("integration")`과 `@Import(MySqlTestDatabase.class)`를 붙인다. 태그가 없으면 단위 테스트로 실행된다. 데모 프로필 테스트만 전용 인메모리 H2에서 계정을 등록한다.

운영 코드 결함을 재현하는 테스트에는 `@Tag("known-defect")`를 붙인다. 이 테스트는 결함이 고쳐지기 전까지 실패하므로 `test`에서 빠지며, `.\gradlew.bat knownDefectTest`로 따로 실행한다. 결함을 고친 뒤 태그를 지우면 일반 테스트로 실행된다. 두 측정 모두 `FarmApiApplication`을 제외하고, 단위 측정은 필터 체인 조립만 하는 `SecurityConfig`도 제외한다. 각 리포트 폴더 옆에 같은 이름의 XML 파일도 만들어진다.
