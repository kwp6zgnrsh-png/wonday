# 작업지 좌표 API · farm-demo 단위 검증 보고서

- 검증일: 2026-11-26
- 브랜치: `job-location`
  - HEAD는 `585f5b7`이고, 운영 코드와 기존 테스트에 커밋되지 않은 변경이 있어요.
  - 이 변경은 그대로 보존했어요.
- 검증자 역할: 독립 검증. 운영 코드·HTML·Gradle·CI·마이그레이션은 수정하지 않았어요.
- 저장소 루트와 `farm-api`에는 `AGENTS.md`가 없어요.
- 이번 결과는 이번 좌표 기능용 테스트만 선택 실행한 결과예요.
  - 이전 APP-01 결과나 전체 단위 테스트 결과를 사용하지 않았어요.
  - 사용자의 farm-demo 수동 확인도 개수와 결과에 넣지 않았어요(11절 참고).

## 1. 요약

| 구분 | 테스트 | 성공 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|---|
| 신규 좌표 기능 테스트 (8개 클래스) | **226** | 226 | 0 | 0 | 0 |
| 직접 영향받은 기존 단위 테스트 (13개 클래스, 별도 실행) | 331 | 331 | 0 | 0 | 0 |

- 결함은 1건 찾았어요(**D-1**).
  - 아주 작은 지수 표기 좌표(예: `1E-10000000`)는 형식 검사와 범위 검사를 모두 통과해요.
  - 그 뒤 엔티티 생성 단계의 `setScale(6)`에서 CPU를 몇 초 이상 사용해요.
  - 상세 내용은 7절에 있어요.
- 명세의 나머지 정책은 모두 기대대로 동작했어요.
  - 원본 범위 검사와 HALF_UP 반올림이 명세와 같아요.
  - 형식 오류와 검증 오류가 각각 400으로 분리되고, 오류 응답에 올바른 field가 들어가요.
  - 조회 응답의 좌표 연결과 null 처리, 보험 약정 좌표, demo API의 역할·오류 변환이 기대대로예요.

## 2. 작성 파일 (모두 신규, 테스트 전용)

경로는 모두 `farm-api/src/test/java/com/wonday/farm/` 기준이에요.

| 파일 | 테스트 수 | 내용 |
|---|---|---|
| `job/validation/WorkLocationValidatorTest.java` | 56 | 원본 범위, 양 끝 포함, HALF_UP, scale 6, 원본 불변, 오류 코드·field |
| `job/dto/JobLocationRequestTest.java` | 66 | Jackson 역직렬화와 Bean Validation을 분리 확인 |
| `job/service/JobLocationServiceTest.java` | 21 | 등록 서비스와 `JobPosting` 생성자의 좌표 저장·거절 |
| `job/service/JobLocationQueryTest.java` | 7 | 내 공고, 근로자 목록·상세의 좌표 연결과 JSON 형태 |
| `job/api/JobLocationApiTest.java` | 36 | 등록 HTTP 변환(standalone MockMvc + 실제 ErrorHandler·Validator) |
| `insurance/service/WorkAssignmentCoordinatesTest.java` | 14 | 보험 약정 상세의 agreedLatitude·agreedLongitude |
| `demo/assignment/DemoWorkAssignmentApiTest.java` | 21 | demo 약정 목록·상세 API와 오류 처리기 |
| `demo/JobLocationDemoPageTest.java` | 5 | 좌표 페이지 리소스, 로그인 링크, 정적 회귀 방지 |

- 기존 테스트 파일은 수정하지 않았어요.
- 임시 파일은 scratchpad에만 두었어요(측정용 `ScaleCost.java`, 문법 검사용 JS 추출본).
- 테스트 이름과 주석은 한국어로 작성했어요.

## 3. 실행 명령·시간

`farm-api`에서 `JAVA_HOME=jdk-21.0.12.1+1`로 실행했어요. `--rerun-tasks`는 사용하지 않았어요.

**신규 기능 테스트 + 커버리지 (최종 실행)**

```
./gradlew.bat --no-daemon unitTest \
  --tests 'com.wonday.farm.job.validation.WorkLocationValidatorTest*' \
  --tests 'com.wonday.farm.job.dto.JobLocationRequestTest*' \
  --tests 'com.wonday.farm.job.service.JobLocationServiceTest*' \
  --tests 'com.wonday.farm.job.service.JobLocationQueryTest*' \
  --tests 'com.wonday.farm.job.api.JobLocationApiTest*' \
  --tests 'com.wonday.farm.insurance.service.WorkAssignmentCoordinatesTest*' \
  --tests 'com.wonday.farm.demo.assignment.DemoWorkAssignmentApiTest*' \
  --tests 'com.wonday.farm.demo.JobLocationDemoPageTest*' \
  jacocoUnitTestReport
```

- 최종 실행: BUILD SUCCESSFUL, 약 22초. 테스트 컴파일을 포함한 첫 실행은 약 40초였어요.
- 결과 파일(docs로 복사하지 않았어요):
  - `farm-api/build/test-results/unitTest/` (XML 20개)
  - `farm-api/build/reports/tests/unitTest/`
  - `farm-api/build/reports/jacoco/jacocoUnitTestReport/` (HTML·XML)

**직접 영향받은 기존 단위 테스트 (별도 실행, 약 20초)**

선정 기준은 두 가지예요.

- 이번 변경에서 다른 AI가 생성자 인자나 기대값을 바꾼 기존 테스트
- 좌표 필드가 추가된 DTO·서비스를 직접 검증하는 기존 테스트

`@Tag("integration")`인 `FlywayMigrationTests`는 통합 테스트라서 제외했어요.

| 클래스 | 수 | 선정 이유 |
|---|---|---|
| `CreateJobPostingRequestJsonTest` | 58 | 요청 본문에 좌표 추가 |
| `CreateJobPostingRequestJsonNotationTest` | 23 | 요청 본문에 좌표 추가 |
| `CreateJobPostingRequestValidationTest` | 32 | 생성자에 좌표 인자 추가 |
| `JobPostingServiceTest` | 49 | 공용 fixture에 좌표 추가, 엔티티 생성 경로 |
| `JobPostingApiTest` | 1 | 공용 fixture |
| `JobPostingQueryServiceTest` | 24 | 생성자 변경, `/mine` 응답 필드 추가 |
| `JobPostingQueryServiceMissingCountsTest` | 1 | 생성자 변경 |
| `MyJobPostingApiTest` | 3 | `MyJobPostingsResponse` 필드 추가 |
| `WorkerJobPostingQueryServiceTest` | 61 | 근로자 응답 필드 추가 |
| `WorkerJobPostingApiTest` | 20 | 근로자 응답 생성자 변경 |
| `WorkAssignmentQueryServiceTest` | 25 | 약정 응답 필드와 503 조건 추가 |
| `InsuranceIntegrationApiTest` | 33 | 약정 응답 생성자 변경. 이름과 달리 태그 없는 단위 테스트예요 |
| `AuthDemoPageTest` | 1 | `AuthDemoPage`에 매핑 추가 |
| **합계** | **331** | 모두 통과 |

이 실행 뒤에 신규 기능 테스트를 다시 실행했기 때문에, 지금 `build/`의 결과와 커버리지는 신규 226개 실행 기준이에요.

## 4. 주요 정책별 결과

### 4.1 좌표 형식과 필수값 — `JobLocationRequestTest`

이 테스트는 실제 `CreateJobPostingRequest`를 기본 `JsonMapper`로 읽어요. Bean Validation은 별도의 `Validator`로 확인해요. 제목·일정 같은 나머지 필수값은 모두 정상이고, 좌표 원문만 바꿨어요.

- **JSON 숫자는 원문 그대로 읽어요.**
  - 정수, 소수, `-0`, 지수 표기(`3.7e1`, `9E1`, `1e-7`)를 BigDecimal로 읽어요.
  - 역직렬화 단계에서 반올림하지 않아요.
- **double을 거치지 않아요.**
  - `90.0000000000000001`과 `127.12345678901234567890`이 그대로 보존돼요.
  - 대조로, 같은 값을 double로 바꾸면 90이 되는 것도 확인했어요.
- **숫자가 아닌 값은 역직렬화 단계에서 거절해요.**
  - 대상: 숫자 문자열(`"37.1"`), 빈 문자열과 공백 문자열, boolean, 배열, 객체
  - 대상: `NaN`, `Infinity`, `+37`, `.5`, `37.`, `037`, `0x10`, `1e`
  - 위도와 경도 각각에서 확인했어요.
- **특수한 숫자 입력도 형식 오류로 처리돼요.**
  - 1000자를 넘는 숫자는 Jackson의 숫자 길이 제한으로 거절돼요.
  - `1e9999999999`처럼 int 범위를 넘는 지수도 형식 오류예요.
- **null과 누락은 역직렬화를 통과하고, Bean Validation이 거절해요.**
  - null, 한쪽만 입력, 둘 다 누락이 모두 해당 필드의 위반으로 나와요.
  - 둘 다 누락이면 위반이 2건이에요.
- **0은 누락으로 취급하지 않아요.** 0과 `-0`은 정상 값이에요.
- **경계값과 범위 위반**
  - 경계 `-90/90`, `-180/180`은 허용돼요.
  - `90.0000004`, `90.0000000000000001`, `9.00000004E1`, `1e1000`은 latitude 위반 1건이에요.
  - 경도 범위 위반은 longitude 위반 1건이에요.
  - 위도에 120을 넣으면 latitude만 위반이에요. 범위가 서로 뒤바뀌지 않아요.
- **Bean Validation은 원본을 바꾸지 않아요.** 검증 후에도 8자리 원본이 그대로예요.

### 4.2 원본 범위 검사와 HALF_UP — `WorkLocationValidatorTest`

모든 값은 BigDecimal 문자열로 만들었어요.

- **허용:**
  - 위도 `-90`, `90`
  - 경도 `-180`, `180`
  - `89.9999999` → `90.000000`, `179.9999999` → `180.000000`
- **거절:**
  - `±90.0000004`, `±180.0000004`
  - `±90.0000000000000001`, `±180.0000000000000001`
  - `90.000001`, `91`, `1E+2`, `1E+1000`
  - 반올림하면 범위 안에 들어오는 값도 거절해요.
- **HALF_UP 명세 예시 6개가 모두 일치해요.**
  - `37.1234564` → `37.123456`
  - `37.1234565` → `37.123457`
  - `37.12345678` → `37.123457`
  - `-37.1234564` → `-37.123456`
  - `-37.1234565` → `-37.123457`
  - `127.9876544` → `127.987654`
- **추가로 확인한 반올림 경계:**
  - `37.1234565000000000001` → `37.123457`
  - `37.12345649999999999` → `37.123456`
  - `-127.9876545` → `-127.987655`
- **짧은 값과 작은 값:**
  - 정수, `0`, `-0`, 6자리 이하, `0.0000005` → `0.000001`, `0.0000004` → `0.000000`, `1E-20` → `0.000000`
  - 결과의 scale은 항상 6이에요.
- **원본은 바뀌지 않아요.** 전달한 원본 BigDecimal의 값과 scale(8)이 유지되고, 새 객체가 반환돼요.
- **오류 형식:** null과 범위 위반은 `INVALID_WORK_LOCATION`이고, field는 `latitude` 또는 `longitude`예요.

### 4.3 등록 서비스와 엔티티 — `JobLocationServiceTest`

Repository는 mock이고, 회원·공고·일정은 실제 객체예요.

- **저장값:**
  - `postings.save`에 전달된 `JobPosting`에 주소와 `37.123457`, `127.987654`(scale 6)가 함께 들어 있어요.
  - 위도와 경도의 순서가 유지돼요.
  - 경계·0·음수·짧은 소수도 확인했어요(6가지).
- **검증 실패 시 저장하지 않아요.**
  - Bean Validation을 거치지 않고 서비스를 직접 호출해도 엔티티 생성자에서 `INVALID_WORK_LOCATION`으로 거절돼요.
  - 이때 `postings.save`와 `schedules.saveAll`은 호출되지 않아요(8가지).
- **엔티티 생성자도 원본 범위를 검사해요.**
  - `90.0000004`와 `-180.0000004`는 거절돼요.
  - `89.9999999/-179.9999999`는 `90.000000/-180.000000`이 돼요.
- **기존 흐름이 유지돼요.**
  - 회원 조회 → 공고 저장 → 일정 저장 순서예요.
  - 소유자는 DB에서 조회한 세션 회원이고, 일정은 같은 공고를 참조해요.
  - 고용주가 아니면 AccessDenied이고 저장하지 않아요.
- **응답 계약이 그대로예요.** 응답 필드는 `jobPostingId, employerId, headcount, schedules`로 기존과 같아요.
- **고용주를 지정할 수 없어요.** 요청 DTO에 employer·owner·memberId 필드가 없어서, 클라이언트가 고용주를 지정할 수 없어요.
- **검증 순서:** 시간대 오류와 좌표 오류가 함께 있으면 기존 `INVALID_TIME_ZONE`이 먼저 나와요(관찰 O-2).
- **원본 불변:** 요청의 원본 BigDecimal은 저장 과정에서 바뀌지 않아요.

### 4.4 공고 조회의 좌표 응답 — `JobLocationQueryTest`

공고 3건으로 확인했어요. 좌표 있는 공고 2건과, 좌표를 null로 넣은 기존 공고 1건이에요.

- **내 공고(`/mine`):**
  - 공고마다 저장된 좌표 객체가 그대로 연결되고, 다른 공고의 좌표와 섞이지 않아요.
  - 좌표 없는 공고는 두 필드가 모두 null이고, 숨겨지지 않아요.
  - JSON에서 좌표는 숫자이고, 기존 공고는 명시적 null이에요.
  - `termsVersion`과 `workDate`가 유지돼요.
  - Posting 필드 목록은 기존 10개 뒤에 `latitude, longitude` 2개가 추가된 형태예요.
- **근로자 목록·상세:**
  - 같은 방식으로 좌표가 연결되고, 좌표 없는 공고도 목록에 나와요.
  - 세 공고 모두 `canApply=true`, `remainingSlots=2`예요. 좌표 유무가 노출이나 지원 가능 여부에 영향을 주지 않아요.
  - 상세에서도 `termsVersion=1`, 근무일, 주소가 유지돼요.
- **읽기 전용:** 조회를 반복해도 엔티티의 좌표가 바뀌지 않고, save·saveAll 호출이 없어요.
- APP-01 정책 전체는 다시 작성하지 않았어요. 기존 `WorkerJobPostingQueryServiceTest`(61개)를 영향 범위로 실행해서 통과를 확인했어요.

### 4.5 보험 약정 상세의 좌표 — `WorkAssignmentCoordinatesTest`

- **정상 반환:**
  - 약정에 저장된 좌표를 같은 객체 그대로 반환해요.
  - 주소, 시작·휴게 시각(+09:00), 일급 매핑도 기존 그대로예요.
- **기존 약정(좌표 null):** 다른 필수 정보가 정상이면 두 필드가 null인 정상 응답이에요.
- **한쪽만 null:** 4가지 경우 모두 `503 SERVICE_UNAVAILABLE`이에요. 0을 한쪽에만 넣은 경우도 포함해요.
- **기존 검증 유지:**
  - 좌표가 null이어도 근무일·확정 시각 누락, 휴게 시각 한쪽 누락은 기존대로 503이에요.
  - 좌표가 정상이어도 휴게 시각 한쪽 누락은 503이에요.
  - 해석할 수 없는 시간대는 좌표가 있어도 기존 시간대 검증이 유지돼요.
- **조회 실패:** 다른 근로자의 근무나 없는 근무는 `404 WORK_ASSIGNMENT_NOT_FOUND`예요.
- **JSON:** 두 필드가 숫자 또는 명시적 null로 항상 존재해요.
- **반복 조회:**
  - 두 번 조회해도 응답이 같고, 약정 필드도 바뀌지 않아요.
  - 기존 null 약정은 여전히 null이에요.
  - 약정 Repository는 `findOwned` 2회만 호출돼요. 현재 공고를 조회하는 경로가 없어요.
- 확정 시 좌표 복사와 공고 수정 후 약정 보존은 기능이 아직 없어서 **검증하지 않았어요.**

### 4.6 등록 HTTP 변환 — `JobLocationApiTest`

실제 `JobPostingApi`, `JobPostingErrorHandler`, `LocalValidatorFactoryBean`을 쓰고, 서비스는 mock이에요.

- **정상 요청:**
  - 7가지 정상 요청이 모두 201이고, 기존 `jobPostingId`·`workScheduleId` 응답이 나와요.
  - 서비스에는 세션 회원 ID 42와 함께 원본 BigDecimal이 전달돼요.
  - 20자리 긴 소수와 `9E1` 표기도 문자열 표현까지 그대로 전달돼요.
- **형식 오류:**
  - 위도 9가지와 경도 5가지가 모두 `400 INVALID_REQUEST_BODY`예요.
  - 1000자를 넘는 숫자와 `±1e9999999999`도 500이 아니라 400이에요.
- **누락·null·범위 위반:**
  - 모두 `400 INVALID_JOB_POSTING`이에요.
  - 위도만 잘못되면 `field=latitude`, 경도만 잘못되면 `field=longitude`예요.
  - `90.0000004`는 반올림되어 통과하지 않고 거절돼요.
  - 필드가 아예 없는 경우도 그 필드 이름으로 거절돼요.
- **양쪽 모두 누락:** 400이고 field는 둘 중 하나예요. 어느 쪽이 먼저인지는 보장되지 않아요(관찰 O-1).
- **서비스 미호출:** 형식 오류와 Bean Validation 오류에서는 모두 서비스가 호출되지 않아요.

### 4.7 demo 전용 약정 API — `DemoWorkAssignmentApiTest`

- **목록:**
  - principal의 memberId 5로만 `findMine(5)`을 호출해요. 쿼리의 `workerId=99&memberId=99`는 무시돼요.
  - 필드(`assignmentId, agreedWorkDate, agreedWorkLocation, coordinatesMissing`)가 기대대로 나와요.
  - `coordinatesMissing`은 양쪽 null일 때만 true예요. 한쪽만 있거나 0이면 false예요.
  - 본인 약정이 없으면 빈 배열로 200을 반환하고, 가짜 약정을 만들지 않아요.
- **역할 확인:**
  - 요청마다 현재 DB 역할을 다시 조회해요. 두 번째 요청 전에 EMPLOYER로 바뀌면 403이에요.
  - 회원 없음, EMPLOYER, `ADMIN`, 소문자 `worker`는 목록과 상세 모두 `403 WORKER_ROLE_REQUIRED`예요.
  - 이때 약정 Repository와 조회 서비스는 호출되지 않아요.
- **상세:**
  - `WorkAssignmentQueryService.find(5, 31)`이 정확히 1회 호출되고, 목록 Repository는 호출되지 않아요.
  - 좌표는 JSON 숫자이고, null 약정은 명시적 null이에요.
- **오류 변환:**
  - 서비스의 404 `WORK_ASSIGNMENT_NOT_FOUND`와 503 `SERVICE_UNAVAILABLE`이 그대로 변환돼요.
  - `abc`, `1.5`, 20자리 숫자, `-` 같은 잘못된 ID는 `400 INVALID_REQUEST`이고 서비스가 호출되지 않아요.
  - 0은 서비스로 전달되어 서비스의 400이 그대로 나가요.
  - 일반 예외와 `BadSqlGrammarException`은 `500 INTERNAL_SERVER_ERROR`예요.
  - `DataAccessResourceFailure`, `QueryTimeout`(Transient), `CannotCreateTransaction`은 503이에요.
  - 목록과 상세 모두 같은 결과예요.
- **노출 없음:** 오류 본문에 예외 메시지 속 `SECRET`, `select`, `jdbc:`, `password`, 예외 클래스명이 나오지 않아요.
- **캐시 금지:** 정상, 403, 404, 503, 400, 500 응답 모두 `Cache-Control: no-store`예요.
- **프로필 선언:** API, 오류 처리기, Repository에 `@Profile("farm-demo")`이 선언된 것만 확인했어요. 실제 빈 등록과 경로 차단은 미검증이에요.

### 4.8 demo 페이지 — `JobLocationDemoPageTest` + 정적 검토

**Java 단위 테스트로 확인한 것**

- `jobLocationPage()`는 `demo/farm-job-location.html`이라는 실제 classpath 리소스를 반환하고, 읽을 수 있어요.
- 매핑 경로는 `/demo/job-location`, 응답 타입은 `text/html;charset=UTF-8`이에요.
- 로그인 HTML에 `<a href="/demo/job-location" id="location-link">` 링크가 있어요.
- 기존 demo 페이지 4개 리소스가 모두 존재해요.
- 정적 회귀 방지 검사: 스크립트에 아래 문자열이 없어요.
  - `innerHTML`, `localStorage`, `sessionStorage`, `document.cookie`, `Authorization`
  - `/api/internal/insurance`, `parseFloat`, `Number($('latitude')`
  - **문자열이 없다는 것만 확인했을 뿐, 브라우저 동작을 검증한 것은 아니에요.**

**문법 검사:** 스크립트 블록(214줄)을 scratchpad로 추출해서 `node --check`(v26.13.1)로 검사했고 통과했어요. 브라우저와 HTTP는 실행하지 않았어요.

**정적 검토 결과 (코드를 읽고 판단했고, 실행하지 않았어요)**

| 항목 | 판단 | 근거 |
|---|---|---|
| POST 직전 CSRF 발급 | 설계상 충족 | 제출 처리에서 `csrfHeaders()`로 `/api/auth/csrf`를 호출한 뒤 `headerName: token` 헤더로 POST |
| 토큰 비노출 | 설계상 충족 | CSRF 요청을 `visible=false`로 보내서 응답 칸·요청 기록에 쓰지 않음. 요청 본문 칸에는 body만 표시. 브라우저 저장소를 쓰지 않음 |
| 보험 토큰 비전달 | 설계상 충족 | Authorization·Bearer 헤더를 쓰지 않고, 보험 내부 경로를 호출하지 않음 |
| 좌표 원문 전달 | 설계상 충족 | `jsonNumber()`가 JSON 숫자 정규식에 맞으면 원문을 그대로 넣고, 아니면 `JSON.stringify` 문자열로 보냄. Number로 변환하지 않아서 `90.0000000000000001`이 그대로 전송됨 |
| 201과 후속 GET 구분 | 설계상 충족 | `status !== 201`일 때만 등록 실패로 처리. 후속 `loadPosting` 실패는 "등록은 성공, 조회만 다시" 안내 |
| 자동 재시도 없음 | 설계상 충족 | POST 네트워크 오류는 catch 후 "저장됐을 수 있음, 재시도하지 않음"을 안내하고 다시 보내지 않음 |
| 실제 응답 표시 | 설계상 충족 | 좌표 필드가 숫자·null이 아니면 오류. `coordinateText`는 null을 "null · 미등록"으로 표시. 응답 원문도 함께 표시 |
| 계정 변경·인증 오류 시 이전 결과 삭제 | 설계상 충족, 단 O-3 | 계정 id·role이 바뀌거나 401/403이 오면 `clearPrivateData()` |
| textContent 표시 | 설계상 충족 | `node()`와 모든 결과 칸이 `textContent`를 사용 |

## 5. DB 마이그레이션 V7 (읽기 검토만)

- `git status`와 `git diff`로 보면 `db/migration`에서 신규 파일은 `V7__add_work_location_coordinates.sql` 하나이고, V1~V6에는 변경이 없어요.
- `job_posting.latitude/longitude`와 `work_assignment.agreed_latitude/agreed_longitude`는 모두 `DECIMAL(9,6) NULL`이에요.
- 두 테이블 모두 CHECK 제약이 있어요.
  - 좌표 쌍이 "둘 다 NULL" 또는 "둘 다 NOT NULL이고 범위 안"이어야 해요.
  - 범위는 `BETWEEN -90 AND 90`, `-180 AND 180`이에요.
- 기존 행을 채우는 UPDATE는 없어요.
- MySQL을 실행하지 않았기 때문에, 아래 항목은 검증했다고 볼 수 없어요.
  - 마이그레이션 적용
  - CHECK 제약 동작(MySQL 8.0.16 이상에서 강제됨)
  - 저장 정밀도
  - 기존 데이터 보존
- `FlywayMigrationTests`의 V7 기대값 수정은 통합 테스트라서 실행하지 않았어요.

## 6. 관련 클래스 커버리지 (신규 226개 선택 실행 기준)

`build/reports/jacoco/jacocoUnitTestReport/jacocoUnitTestReport.xml`에서 클래스별로 계산했어요. 전체 프로젝트 커버리지는 측정하지 않았어요. 90% 기준 검사와 측정 제외 설정 변경도 하지 않았어요.

| 클래스 | 라인 | 분기 | 미실행 경로 |
|---|---|---|---|
| `WorkLocationValidator` | 6/6 (100%) | 6/6 (100%) | — |
| `StrictCoordinateDeserializer` | 3/4 (75%) | 4/4 (100%) | 15행 `return (BigDecimal) context.handleUnexpectedToken(...)`. 실제로 실행되지만, return 안에서 예외가 나는 줄을 JaCoCo가 미실행으로 표시하는 알려진 현상이에요. 분기는 100%예요 |
| `JobPosting` | 13/13 (100%) | — | — |
| `DemoWorkAssignmentApi` | 16/16 (100%) | 6/6 (100%) | — |
| `DemoWorkAssignmentErrorHandler` | 7/7 (100%) | — | — |
| `AuthDemoPage` | 5/5 (100%) | — | — |
| `WorkAssignmentQueryService` | 21/21 (100%) | 19/22 (86.4%) | `assignmentId <= 0`, 휴게 시각 null 삼항식. 기존 경로이고 `WorkAssignmentQueryServiceTest`가 담당해요 |
| `JobPostingService` | 26/28 (92.9%) | 8/10 (80%) | 작업 내용 65535바이트 초과, 근무일 중복. 기존 경로이고 `JobPostingServiceTest`가 담당해요 |
| `JobPostingQueryService` | 30/31 (96.8%) | 6/10 (60%) | 역할 거절, 빈 목록, 집계 누락. 기존 경로예요 |
| `WorkerJobPostingQueryService` | 90/107 (84.1%) | 33/76 (43.4%) | 페이지 처리, 지원 불가 사유, 시간대 같은 APP-01 경로. 이번 범위 밖이고 기존 테스트가 담당해요 |

- 이번에 변경된 좌표 라인(조회 서비스 3곳의 `getLatitude()/getLongitude()` 연결, 보험 503 조건의 좌표 항, 엔티티 정규화)은 모두 실행됐어요.
- 아래 세 클래스의 낮은 수치는 이번 선택 실행에 기존 정책 테스트가 빠졌기 때문이에요. 결함을 뜻하지 않아요.
  - `WorkerJobPostingQueryService`
  - `JobPostingQueryService`
  - `JobPostingService`

## 7. 발견한 결함

### D-1. 아주 작은 지수 표기 좌표가 검증을 통과한 뒤 정규화 단계에서 과도한 CPU를 사용함

- **재현 조건:**
  - 고용주 세션으로 `"latitude": 1E-10000000`(12자) 같은 정상 범위의 아주 작은 지수 값을 등록 요청에 보내요.
  - 경도도 마찬가지예요.
- **경로:**
  1. `StrictCoordinateDeserializer`가 JSON 숫자로 받아 그대로 읽어요. scale은 10,000,000이에요.
  2. Bean Validation `@DecimalMin/@DecimalMax`는 값이 범위 안이라 통과해요. 이 비교는 즉시 끝나요.
  3. `JobPostingService.create` 안에서 회원 조회와 기존 검증을 거쳐요.
  4. `new JobPosting(...)` → `WorkLocationValidator.normalize` → `value.setScale(6, HALF_UP)`가 실행돼요.
  5. `setScale`이 내부적으로 10^(scale−6) 크기의 BigInteger를 만들어요.
- **실측** (scratchpad의 단독 Java 프로그램, `-Xmx512m`, 운영 코드와 같은 `setScale(6, HALF_UP)` 호출):

  | 입력 | 결과 | 시간 |
  |---|---|---|
  | `1E-10000` | 0.000000 | 12 ms |
  | `1E-100000` | 0.000000 | 35 ms |
  | `1E-1000000` | 0.000000 | 281 ms |
  | `1E-10000000` | 0.000000 | **8,318 ms** |

  - 입력의 scale이 10배 늘어날 때 시간은 약 30배 늘었어요.
  - `1E-999999999`(이것도 12자)는 메모리 부족이나 매우 긴 지연이 예상돼서 **실행하지 않았어요.**
- **확인한 부분:** `JobLocationRequestTest.tinyExponentPassesValidationCheaply`에서 `1E-999999999`가 역직렬화와 Bean Validation을 즉시 통과하고 scale이 999,999,999인 것을 확인했어요(통과 테스트).
- **기대값:** 명세에 정해져 있지 않아요. 범위 안의 정상 좌표이므로 0.000000으로 저장하거나, 비정상 정밀도로 보고 400으로 거절하는 쪽이 자연스러워요. 어느 쪽이든 한 요청이 수 초 이상 CPU를 점유해서는 안 돼요.
- **실제값:** 12바이트 입력 하나로 요청 스레드가 약 8초(1E-10000000) 이상 CPU를 사용해요. 이 동안 트랜잭션도 열려 있어요. 더 작은 지수에서는 OOM이나 무한에 가까운 지연이 예상돼요.
- **영향 범위:**
  - 고용주 로그인과 CSRF가 필요해서 익명 공격은 아니에요.
  - farm-demo 화면의 `jsonNumber()` 정규식도 지수 표기를 허용하기 때문에 화면에서 그대로 보낼 수 있어요.
  - 일반 소수 표기는 Jackson의 숫자 길이 제한(1000자) 때문에 scale이 약 1000으로 제한되어 문제가 없어요.
- **원인:** 범위 검사는 값의 크기만 보고, 정밀도(scale)의 상한은 보지 않아요. 그 상태에서 `setScale`로 정밀도를 줄이는 비용이 scale에 따라 초선형으로 늘어나요.
- **수정 제안 (운영 코드는 수정하지 않았어요, 택1):**
  1. `StrictCoordinateDeserializer`에서 `value.scale()`이 상한(예: 1000, 일반 표기의 최대치와 같은 수준)을 넘거나 음수 scale이 과도하면 형식 오류로 거절해요. 이 경우 400 `INVALID_REQUEST_BODY`가 돼요.
  2. `WorkLocationValidator.normalize`에서 `value.abs()`가 `0.0000005`보다 작으면 바로 `0.000000`(부호 규칙 포함)을 반환하고, 그 외에는 `value.round(new MathContext(precision 상한))` 뒤에 `setScale`해요.
- **테스트 위치:** 재현은 위 scratchpad 측정으로 했고, 테스트 스위트에는 실패하는 재현 테스트를 **추가하지 않았어요.**
  - 사용자 결정(2026-11-26): 이번에는 보고만 하고 재현 테스트는 추가하지 않아요.
  - 기대 동작(0 저장인지 400 거절인지)을 팀이 정한 뒤 아래 형태로 추가하는 것을 제안해요.

  ```java
  // WorkLocationValidatorTest에 추가 제안 (기대 동작 결정 후)
  assertTimeoutPreemptively(Duration.ofMillis(500),
      () -> WorkLocationValidator.normalizeLatitude(new BigDecimal("1E-10000000")));
  ```

## 8. 관찰 사항 (결함 아님)

- **O-1.** 좌표가 양쪽 모두 누락되면 `MethodArgumentNotValidException`의 첫 FieldError가 응답 field가 돼요. 그래서 `latitude`와 `longitude` 중 어느 쪽이 나올지는 보장되지 않아요. 기존 오류 처리 방식과 같아요.
- **O-2.** 서비스는 기존 시간대·일정 검증을 먼저 하고, 엔티티 생성 단계에서 좌표를 검사해요. 그래서 시간대와 좌표가 함께 잘못되면 `INVALID_TIME_ZONE`이 먼저 나와요. HTTP 경로에서는 Bean Validation이 좌표를 먼저 거절하므로 이 순서는 서비스를 직접 호출할 때만 드러나요.
- **O-3.** demo 화면에서 등록 201 뒤의 재조회 GET이 401/403이면, `request()`가 `clearPrivateData()`를 호출해요.
  - 이때 등록 결과 칸("등록 성공 · 공고 ID"), 공고 ID 입력칸, 요청 기록(201 행 포함)이 지워져요.
  - 성공한 공고 ID는 상단 안내 문구에만 남아요("공고 N 등록은 성공했습니다…").
  - 문서의 "성공한 공고 ID를 유지"는 안내 문구 수준에서만 충족돼요. 정적 검토 결과이고 실행하지 않았어요.
- **O-4.** demo 목록의 `coordinatesMissing`은 한쪽만 null인 행을 false로 표시해요. 화면에는 "좌표 확인"으로 나와요. V7 CHECK가 이런 행을 막고, 상세에서는 503이 되므로 실제 영향은 작아요.
- **O-5.** JSON 형태(숫자 또는 null)는 기본 `JsonMapper`와 standalone MockMvc의 기본 변환기로 확인했어요. Spring Boot 애플리케이션의 실제 ObjectMapper 설정(예: BigDecimal 표기 옵션)은 검증하지 않았어요.

## 9. 미검증 범위

- **실제 DB:**
  - 공고 INSERT와 DECIMAL(9,6) 저장·반올림 정밀도
  - V7 적용, CHECK 제약, 기존 행 NULL 보존
  - 등록 트랜잭션 롤백
  - `findOwned`의 소유 필터 SQL
  - demo 목록 SQL(`app.worker_id` 필터, `id desc`, `LIMIT 100`, projection 변환)
- **Security:**
  - 실제 로그인 세션과 CSRF 필터
  - `SecurityConfig`의 `/demo/job-location` permitAll과 `/demo/api/work-assignments/**`의 `hasRole("WORKER")`
  - 보험 서버 토큰 인증 체인
- **프로필:** `farm-demo` 프로필별 빈 등록과 비데모 프로필에서의 경로 차단. 선언만 확인했어요.
- **브라우저와 실제 HTTP:**
  - farm-demo 화면의 CSRF 자동 처리, 화면 갱신, 오류 표시
  - 계정 전환 시 결과 삭제
  - `node --check`와 문자열 검사는 동작 검증이 아니에요.
- **아직 없는 기능:** 신규 선정·확정 시 약정에 좌표를 복사하는 기능과 공고 위치 수정 API는 아직 없어서 검증 대상이 아니에요.
- **통합 테스트:** `FlywayMigrationTests` 등 통합 테스트는 실행하지 않았어요.

## 10. 보존 확인

- 운영 코드, HTML, Gradle, CI, 마이그레이션은 수정하지 않았어요.
  - `git status --short farm-api/src/main`은 작업 전과 같은 18건이에요(수정 13건, 신규 5건).
- 기존 테스트 파일은 수정하지 않았어요. 다른 AI의 기존 미커밋 변경을 보존했어요.
- 커밋, 푸시, PR, 브랜치 전환을 하지 않았어요.
- 테스트 제외, known-defect 태그, 기대값 완화를 하지 않았어요.
- HTML·XML 산출물은 `build/`에만 있어요.

## 11. 사용자 수동 확인 (별도, 자동 결과에 포함하지 않음)

사용자는 farm-demo에서 다음을 확인했다고 보고했어요. 이번 독립 자동 테스트의 개수와 결과에는 포함하지 않았어요.

- 좌표 저장·조회
- 소수점 6자리 반올림
- 원본 범위 초과 오류
- 기존 약정의 두 좌표 null 응답

## 12. PR 요약

```
작업지 좌표 API · farm-demo 독립 단위 검증 (job-location)

- 신규 단위 테스트 8개 클래스 226개 선택 실행: 전부 통과
  (WorkLocationValidator 56 / 요청 역직렬화·Bean Validation 66 / 등록 서비스·엔티티 21 /
   조회 좌표 7 / 등록 HTTP 36 / 보험 약정 좌표 14 / demo 약정 API 21 / demo 페이지 5)
- 직접 영향받은 기존 단위 테스트 13개 클래스 331개 별도 실행: 전부 통과
- 검증 정책:
  - 원본 범위(양 끝 포함) 검사 후 HALF_UP 6자리 반올림, 90.0000004 거절
  - 숫자 문자열·boolean·배열·객체는 400 INVALID_REQUEST_BODY
  - 누락·null·범위 위반은 400 INVALID_JOB_POSTING(+field)
  - 조회 좌표 연결과 기존 공고 null
  - 보험 약정 좌표 한쪽 null은 503
  - demo API의 세션 근로자 ID 사용, 역할 재확인, 오류 변환, no-store
- 결함 D-1: 1E-10000000 같은 아주 작은 지수 좌표가 검증을 통과한 뒤 setScale에서 약 8초 CPU를 사용함
  (scratchpad에서 실측, 수정 방향 제안. 스위트에는 실패 테스트를 추가하지 않음)
- 커버리지(클래스별):
  - WorkLocationValidator, JobPosting, DemoWorkAssignmentApi는 라인·분기 100%
  - StrictCoordinateDeserializer는 분기 100%
- 미검증: 실제 DB·V7 적용·CHECK, Security·CSRF·프로필별 빈, 브라우저 동작, 확정 시 좌표 복사(미구현)
- 운영 코드·마이그레이션 변경 없음, 커밋 없음
```
