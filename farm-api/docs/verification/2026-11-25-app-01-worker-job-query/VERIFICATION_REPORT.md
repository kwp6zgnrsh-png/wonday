# APP-01 근로자 공고 목록·상세 조회 API — 단위 테스트 독립 검증 보고서

- **검증일:** 2026-11-25
- **검증자:** Claude. 테스트 작성과 실행만 했다.
- **대상 브랜치:** `app-list`, HEAD `228603f`. APP-01 운영 코드는 아직 커밋되지 않은 작업 트리 상태다.
- **AGENTS.md:** 없다.
- **실행 범위:** APP-01 단위 테스트 3개 클래스만 선택해서 실행했다. 이 결과는 전체 테스트 통과나 프로젝트 전체 커버리지가 아니다.

**이번 범위에서 검증하지 않은 것:**
- 실제 MySQL 쿼리(정렬·EXISTS·집계·본인 지원 조회)
- 트랜잭션
- Security 필터·세션·CSRF·실제 401/403
- `/mine` 매핑 우선순위
- farm-demo 화면(JavaScript)

## 요약

| 항목 | 결과 |
|---|---|
| 신규 테스트 | 97개 (서비스 61, 컨트롤러·오류·JSON 20, 코드 사전 16) |
| 결과 | **97개 통과 · 0 실패 · 0 오류 · 0 건너뜀** |
| 실행 시간 | 약 36초 (Gradle 빌드 포함, `--no-daemon`) |
| 결함 | **없음** |
| 커버리지(선택 실행 기준) | 서비스 라인 100% · 분기 98.7%. 미실행 1분기는 현재 도달할 수 없는 분기다(6절) |

## 1. 작성·수정한 테스트 파일

모두 새로 만든 파일이다. 기존 테스트, 운영 코드, Gradle·CI 설정, 마이그레이션은 바꾸지 않았다.

| 파일 | 테스트 수 |
|---|---|
| `src/test/java/com/wonday/farm/job/service/WorkerJobPostingQueryServiceTest.java` | 61 |
| `src/test/java/com/wonday/farm/job/api/WorkerJobPostingApiTest.java` | 20 |
| `src/test/java/com/wonday/farm/reference/AgriculturalCodeCatalogTest.java` | 16 |

테스트 의도는 `@DisplayName`에 한국어로 적었다.

## 2. 실행 명령과 시간

JDK는 `JAVA_HOME=C:\Users\HCB\Desktop\woori-workspace\00.sw\jdk-21.0.12.1+1`를 사용했다.

```powershell
cd C:\Users\HCB\Desktop\wonday\farm-api
.\gradlew.bat unitTest `
  --tests 'com.wonday.farm.job.service.WorkerJobPostingQueryServiceTest*' `
  --tests 'com.wonday.farm.job.api.WorkerJobPostingApiTest*' `
  --tests 'com.wonday.farm.reference.AgriculturalCodeCatalogTest*' `
  jacocoUnitTestReport
```

- 결과: BUILD SUCCESSFUL. 약 36초가 걸렸다.
- 커버리지는 같은 실행에서 만든 `jacocoUnitTestReport`로 측정했다. 이를 위해 테스트를 다시 돌리지 않았다.
- **실행하지 않은 것:**
  - 전체 `test`·`unitTest`
  - `fullTest`, `integrationTest`
  - 90% 검증 태스크
  - `--rerun-tasks` 옵션

## 3. 테스트 수와 결과

| 클래스 | 테스트 | 성공 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|---|
| WorkerJobPostingQueryServiceTest | 61 | 61 | 0 | 0 | 0 |
| WorkerJobPostingApiTest | 20 | 20 | 0 | 0 | 0 |
| AgriculturalCodeCatalogTest | 16 | 16 | 0 | 0 | 0 |
| **합계** | **97** | **97** | **0** | **0** | **0** |

## 4. 정책별 검증 결과

### 서비스 테스트의 구성

- **저장소:** mock이다. 저장소 계약을 흉내 내는 가짜 응답을 쓴다.
  - 후보 공고는 ID 내림차순으로, `beforeId`보다 작은 것만, 요청한 크기만큼 돌려준다.
  - 일정은 공고 ID 내림차순 → 근무일 → 일정 ID 순으로 돌려준다.
  - 가짜 후보 응답은 SQL의 EXISTS 조건을 흉내 내지 않고 모든 공고를 돌려준다. 그래서 노출 판정은 서비스의 필터링만으로 검증된다.
- **도메인 객체:** 회원·공고·일정은 실제 도메인 객체를 쓴다. 공고마다 고용주가 다르다.
- **코드 사전:** 실제 classpath JSON을 읽는다.
- **시각:** `Clock.fixed`로 고정했다. 기준은 `2026-11-25T00:00:00Z`(GMT+9 09:00)이다.

### 1) 근로자 역할 — `WorkerRole` (5)
- 현재 DB 역할이 WORKER이면 목록과 상세를 조회할 수 있다.
- EMPLOYER, 회원 없음, `ADMIN` 역할은 목록·상세 모두 `AccessDeniedException`이다. 공고·일정 저장소는 호출하지 않는다.
- 매 호출마다 회원을 다시 조회한다. 첫 호출이 성공한 뒤 DB 역할을 EMPLOYER로 바꾸면 거부된다(`findById` 2회).
- 서로 다른 고용주 두 명의 공고가 함께 목록에 나온다. 즉 '내 공고'로 범위를 좁히지 않는다.
- 클래스에 `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`가 붙어 있다.
- **한계:** 실제 세션·Security 필터의 401/403은 이번 범위가 아니다.

### 2) 목록 노출 — `ListVisibility` (6)
- **날짜 필터링:** 한 공고에 6개 상태가 섞여 있어도 시작 전 OPEN 날짜(13, 16)만 모집 날짜에 들어간다.
  - 6개 상태: 시작 후 OPEN, PAUSED, OPEN, CLOSED, REMOVED, OPEN
- **두 필드의 일치:** `recruitingWorkDates`와 `recruitingSchedules`의 날짜가 같은 순서로 일치한다.
- **공고 제외:** 다음 공고는 목록에서 빠진다.
  - 모든 날짜가 중단·마감·취소·시작 후인 공고
  - 일정이 없는 공고
- **공고를 숨기지 않는 경우:** 정원이 찼거나(`CAPACITY_FULL`) 본인이 지원한(`ALREADY_APPLIED`) 공고도 목록에 남는다.
- **빈 결과:** `[]`, `hasNext=false`, page 0, size 20.
- **후보 조회 인자:**
  - beforeId는 첫 배치에서 `null`이다.
  - 상태는 `OPEN`이다.
  - 하한은 서버 시각에서 18시간 1초를 뺀 뒤 초 단위로 자른 값이다. 예: `…00:00:00.987654321Z` → `2026-11-24T05:59:59`
  - 페이지 요청은 `PageRequest.of(0, 100)`이다.

### 3) 시작 시각과 시간대 — `StartTimeAndZone` (14)
- **시작 경계:**
  - 시작 1나노초 전에는 지원할 수 있고, 목록에도 들어간다.
  - 시작과 정확히 같은 시각, 1나노초 후, 1시간 후에는 `SCHEDULE_STARTED`이고 목록에서 빠진다.
- **Instant 비교:** GMT+9 09:00은 UTC 00:00으로 비교한다. UTC 08:59는 시작 후이고, 전날 UTC 23:59는 시작 전이다.
- **JVM 기본 시간대 무관:** JVM 기본 시간대를 `Pacific/Kiritimati`(+14), `Pacific/Pago_Pago`(-11), `UTC`로 바꿔도 판정이 같다. 테스트 후 원래 값으로 복원했다.
- **기존 시간대 유지:** 기존 공고의 `UTC` 시간대를 GMT+9로 바꾸지 않는다. 그 시간대 기준으로 판정하고, 상세의 `timeZone`도 `UTC`다.
- **시간대 오류:** null, 빈 값, 공백, `Seoul`, `GMT+99`, `Mars/Olympus`이면 상세는 `TIME_ZONE_UNAVAILABLE`로 날짜를 유지하고, 목록에서는 빠진다.
- **시작 시각 해석 불가:** America/New_York의 DST 누락(02:30)과 DST 중복(01:30), 시작 시각 null은 `SCHEDULE_START_TIME_UNAVAILABLE`이다. 목록에서는 빠진다.
- 해석할 수 없는 날짜가 있어도 같은 공고의 정상 날짜는 목록에 남는다.
- **기준 시각:** 한 요청에서 `clock.instant()`를 정확히 1회만 호출한다(목록·상세 각각). 두 번째 호출 값을 먼 미래로 줘도 결과가 바뀌지 않는다.
- **마이크로초 경계:** 시작이 `09:00:00.000001`이면 정각 `00:00:00Z`에는 지원할 수 있고, `00:00:00.000001Z`에는 `SCHEDULE_STARTED`다.

### 4) 페이지 처리 — `Paging` (16)
- **입력 검증:**
  - page가 -1이나 `Integer.MIN_VALUE`이면 `INVALID_PAGINATION`, field=`page`이다.
  - size가 0, -1, 101, `Integer.MAX_VALUE`이면 `INVALID_PAGINATION`, field=`size`이다.
  - 두 경우 모두 400이고, 공고·일정 저장소를 호출하지 않는다.
- size 1과 100은 허용한다.
- **반환 개수와 hasNext:** 노출 공고가 4개일 때
  - size 5이면 4개, `hasNext=false`
  - size 4이면 4개, `hasNext=false`
  - size 3이면 3개, `hasNext=true`
- **페이지 이어짐:** 공고 5개를 size 2로 나누면 `[5,4]` → `[3,2]` → `[1]`(`hasNext=false`) → 범위 밖 `[]`이다. 중복과 누락이 없다.
- **필터링 후 페이지:** 노출에서 빠지는 짝수 ID가 섞여 있어도, 필터링한 뒤의 순서 기준으로 `[5,3]` → `[1]`로 나뉜다.
- **배치 이어 읽기:** 노출 공고가 150개일 때
  - page 0, size 100은 150~51번(100개)을 돌려주고 `hasNext=true`다.
  - page 1은 50~1번(50개)을 돌려주고 `hasNext=false`다.
  - 두 번째 배치는 `beforeId=51`로 호출한다(`inOrder`로 확인).
- **불필요한 조회 없음:**
  - 첫 배치에서 size+1개를 채우면 후보·일정 조회는 각 1회뿐이다.
  - 배치가 100개 미만이면 1회 조회로 끝난다.
  - 후보가 정확히 100개이면 빈 다음 배치를 1회 더 읽고 끝낸다(`beforeId=1`).
- **offset 오버플로 없음:** `page=Integer.MAX_VALUE`, size 100이면 빈 결과와 `hasNext=false`다. int 곱셈이었다면 음수 offset이 되어 첫 페이지가 반환되는 경우다.
- **한계:** 가짜 저장소가 정렬된 후보를 돌려준다는 계약을 전제로 한 검증이다. 실제 SQL의 `order by posting.id desc`, `beforeId` 조건, EXISTS 조건은 실행하지 않았다(미실행 통합 검증).

### 5) 상세 조회 — `Detail` (8)
- **목록 노출 여부와 무관:** 전부 지난 날짜·마감·취소·중단인 공고도 상세를 돌려준다. 이 공고는 목록에는 없다. 상세에는 4개 일정이 모두 남고, 각 사유가 함께 나온다.
- **일정 없음:** `schedules=[]`이다. 집계와 본인 지원은 조회하지 않는다.
- **잘못된 ID:** ID가 0, -1, `Long.MIN_VALUE`이면 `INVALID_REQUEST`, field=`jobPostingId`이고 저장소를 호출하지 않는다.
- **없는 공고:** 존재하지 않는 양수 ID는 404 `JOB_POSTING_NOT_FOUND`이다.
- **값 보존:**
  - 공고의 제목, 설명, 장소, 시간대, 정원, 일급을 그대로 돌려준다.
  - 일정의 ID, 날짜, 근무 시작·종료(`.000001`, `.999999`), 휴게(`.123456`), 상태, 버전 7을 그대로 돌려준다.
  - 휴게가 없는 일정은 시작·종료가 모두 null이다.
- **읽기만 함:**
  - 조회 전후 일정의 상태, 버전, 날짜, 시작 시각이 같다.
  - 저장소 호출은 조회 메서드뿐이다(`verifyNoMoreInteractions`). save나 delete 호출은 없다.
- **개인정보 없음:** 응답 레코드 3종의 구성 요소 목록을 정확히 확인했다. 로그인 ID, 전화번호, 주민번호, 고용주, 다른 지원자 필드가 없다.

### 6) 확정 인원과 남은 자리 — `ConfirmedAndRemaining` (4)
- **일정별 연결:** 공고 2개·날짜 3개에서 집계가 일정 ID별로 정확히 연결된다. 집계 행이 없는 날짜(12)는 `confirmedCount=0`이다.
- **남은 자리:** 정원 3일 때 확정 인원별 결과는 다음과 같다.

| 확정 인원 | 남은 자리 | 사유 |
|---|---|---|
| 2 | 1 | 없음(지원 가능) |
| 3 | 0 | `CAPACITY_FULL` |
| 7 | 0 | `CAPACITY_FULL` |
| `Long.MAX_VALUE` | 0 | `CAPACITY_FULL` |

- **지원 대기 무관:** PENDING 지원이 있어도 남은 자리는 줄지 않는다.
- **정원이 찬 경우:** `canApply=false`, `CAPACITY_FULL`이다. OPEN 상태는 그대로이고 목록에도 남는다.
- **한계:** 집계 SQL 정책은 **실행해서 검증하지 않았다.** 서비스가 받은 집계 결과를 어떻게 처리하는지만 검증했다.
  - 집계 SQL 정책: 확정 근무 행만 집계하고, 변경 없음·EARLY_ENDED는 포함하고, CANCELLED는 제외하고, SELECTED만 있는 지원은 집계하지 않는다.

### 7) 본인 지원 이력과 지원 불가 사유 — `OwnApplication` (5)
- **지원 상태별 판정:** 다른 조건이 정상이고 빈자리가 있을 때 결과는 다음과 같다.

| 본인 지원 상태 | 결과 |
|---|---|
| 지원 없음 | 지원 가능 |
| PENDING | `ALREADY_APPLIED` |
| SELECTED, 확정 근무 없음 | `ALREADY_CONFIRMED` |
| NOT_SELECTED | 지원 가능 |
| 확정 근무 있음, 변경 없음 | `ALREADY_CONFIRMED` |
| 확정 근무 있음, EARLY_ENDED | `ALREADY_CONFIRMED` |
| 확정 근무 있음, CANCELLED | 지원 가능 |
| 알 수 없는 상태(`WITHDRAWN`) | `APPLICATION_STATUS_UNAVAILABLE` |

- `canApply=true`이면 `unavailableReason=null`이다.
- **목록과 상세의 일치:** 같은 날짜 3개(정상, 정원 충족, PENDING)에 대해 목록과 상세의 `canApply`, 사유, 남은 자리가 같다.
- **조회 인자:**
  - 본인 지원 조회는 `findWorkerApplications(5, 대상 일정 ID들)`로 호출한다.
  - 집계 조회는 `countWorkerScheduleAssignments(대상 일정 ID들)`로 호출한다.
  - 상세는 공고의 전체 일정 `[11, 12]`를, 목록은 모집 대상 날짜 `[11]`만 대상으로 한다.
- **사유 우선순위:** 아래 날짜 순서로 확인했다.

| 일정 조건 | 결과 사유 |
|---|---|
| 시작 후 + 정원 충족 | `SCHEDULE_STARTED` |
| 취소 + 정원 충족 | `RECRUITMENT_CANCELLED` |
| 정원 충족 + 본인 확정 | `CAPACITY_FULL` |
| 중단 + 본인 지원 | `RECRUITMENT_PAUSED` |
| 마감 | `RECRUITMENT_CLOSED` |
| 시간대 오류 + 정원 충족 | `TIME_ZONE_UNAVAILABLE` |

- **쓰기 없음:** 재지원 저장이나 자동 선정은 일어나지 않는다. 서비스에 쓰기 저장소 호출이 없다(5번 항목의 `verifyNoMoreInteractions`).

### 8) 코드·이름 사전 — `AgriculturalCodeCatalogTest` (16), 서비스 `CodeNames` (2)
- 실제 classpath의 `reference/crop-codes.json`과 `reference/work-type-codes.json`을 읽는다. 외부 Excel, Downloads 경로, 인터넷에 의존하지 않는다.
- **알려진 코드:** `VC02100100100102`는 노지가을배추, `0058`은 수확이다.
- **정규화 없음:**
  - 작업 코드 `58`, `058`, `00058`, 앞뒤 공백이 붙은 값은 이름이 null이다. 선행 0을 포함한 원문으로만 찾는다.
  - 작목 코드도 소문자, 한 자리 짧거나 긴 값, 앞 공백이 붙은 값은 null이다.
- **미등록·null 코드:** null, 빈 값, `UNKNOWN`, `9999`는 두 사전 모두 이름이 null이다. 두 사전을 섞어 조회하지도 않는다.
- **리소스 전체 일치:** 원본 JSON의 작목 242개·작업 75개 항목이 사전에 그대로 들어 있다. 작업 코드는 모두 4자리 숫자 문자열이다.
- **목록·상세 연동:** 코드 원문과 이름을 함께 돌려준다. 미등록 기존 코드(`LEGACY-CROP`, `58`)도 조회를 거부하지 않는다. 코드는 그대로 두고 이름만 null이다.
- 작목별 작업 조합 제한은 이번 범위가 아니다.

### 9) 컨트롤러·오류·JSON — `WorkerJobPostingApiTest` (20, standalone MockMvc)
- **요청 전달:**
  - principal의 회원 ID(5)를 서비스에 전달한다.
  - page·size를 생략하면 기본값 0·20을 전달하고, 명시하면 그 값(3·100)을 전달한다.
  - 쿼리에 `workerId=99`나 `memberId=99`를 붙여도 principal ID를 대신하지 않는다. 목록과 상세 모두 같다.
- **정상 JSON:**
  - 목록의 최상위 키는 `jobPostings`, `page`, `size`, `hasNext`이다.
  - `hasNext`는 boolean, `headcount`는 int, `dailyWage`는 정수다.
  - 작업 코드 `"0058"`은 문자열로 선행 0이 보존된다. 날짜는 `"2026-12-02"` 형식이다.
  - 지원 불가 사유 문자열이 나오고, 사유가 없으면 키는 있고 값이 null이다.
  - 빈 목록은 `[]`이다.
- **상세 JSON:**
  - 현지 일시는 `"2026-12-02T09:00:00.000001"`처럼 마이크로초를 보존하고, 오프셋이나 `Z`를 붙이지 않는다.
  - 휴게가 없으면 null이다.
  - 상태는 enum 이름(`"REMOVED"`), `termsVersion`은 int, `canApply`는 boolean이다.
  - 일정이 없으면 `schedules=[]`이다.
- **개인정보 키 없음:** 응답 본문에 `loginId`, `phone`, `residentRegistrationNumber`, `employer`, `passwordHash`, `workerId` 키가 없다.
- **오류 변환:**

| 입력 | 결과 |
|---|---|
| page가 `abc`, `1.5`, int 초과(양·음) | 400 `INVALID_REQUEST`, field=`page` |
| size가 `x`, int 초과 | 400 `INVALID_REQUEST`, field=`size` |
| ID가 `abc`, `1.5`, long 초과 | 400 `INVALID_REQUEST`, field=`jobPostingId` |
| 음수 page | 서비스로 전달되고, 서비스의 `INVALID_PAGINATION`이 400, field=`page`로 변환된다 |
| 서비스 도메인 예외(400 `INVALID_REQUEST` 등, 404 `JOB_POSTING_NOT_FOUND`) | 지정한 상태·code·message·field 그대로 |
| 서비스 `AccessDeniedException` | 403 `WORKER_ROLE_REQUIRED`. 내부 메시지는 노출하지 않는다 |

- 형식 오류(앞의 세 줄)일 때는 서비스를 호출하지 않는다.
- 모든 오류 본문의 필드는 정확히 `code`, `message`, `field` 세 개다.
- **현재 동작(참고):** `page=`·`size=`처럼 빈 문자열을 보내면 Spring이 기본값(0·20)으로 처리해 200을 반환한다.
- **한계:**
  - standalone MockMvc에 principal을 직접 넣은 것이라 실제 인증이 아니다.
  - Security 필터, 세션, CSRF, `SecurityConfig`의 WORKER 제한, `/api/job-postings/mine`(MyJobPostingApi)과 `/{jobPostingId}`의 실제 매핑 우선순위는 검증하지 않았다.
  - 기본 `JsonMapper` 기준이다.

## 5. 결함

**발견한 결함 없음.** 실패한 테스트도 없다. 기대값을 바꾸거나 테스트를 제외하지 않았다.

**관찰 사항 (결함 아님, 참고용):**
- **도달할 수 없는 분기:** `WorkerJobPostingQueryService` 155행의 `SCHEDULE_STATUS_UNAVAILABLE`이다. 현재 상태 4종(OPEN, PAUSED, CLOSED, REMOVED)은 모두 그 앞에서 처리되므로 이 분기에 도달하지 않는다. 문서 사유표 2번에 해당하며, 앞으로 상태가 추가될 때를 대비한 방어 코드다.
- **큰 page 값의 비용:** page를 커서가 아닌 offset으로 처리한다. 그래서 큰 page 요청은 앞쪽 후보 배치를 모두 다시 읽는다. 예를 들어 page 100, size 100이면 노출 공고 약 10,000개 분량을 읽는다. 결과는 맞다. 문서에도 "page는 고정 스냅샷 커서가 아니다"라고 적혀 있다.
- **빈 page·size:** 빈 문자열은 기본값으로 처리된다(Spring의 동작).
- **역할 확인 순서:** 역할 확인이 페이지 값 검증보다 먼저 실행된다. 그래서 EMPLOYER가 잘못된 page로 요청하면 400이 아니라 403을 받는다.

## 6. 커버리지 (이번 선택 실행 기준, 프로젝트 전체 아님)

측정 대상은 위 3개 클래스 실행으로 만든 JaCoCo 데이터이고, APP-01 대상 클래스만 표시했다.

| 클래스 | 라인 | 분기 |
|---|---|---|
| WorkerJobPostingQueryService (+ Context) | 100% (107/107) | 98.7% (75/76) |
| WorkerJobPostingApi | 100% (3/3) | - |
| WorkerJobPostingErrorHandler (+ ErrorResponse) | 100% (7/7) | - |
| WorkerJobPostingException | 100% (8/8) | - |
| WorkerJobPostingsResponse / WorkerJobPostingResponse (+ 내부 레코드) | 100% | - |
| AgriculturalCodeCatalog | 83.3% (15/18) | 71.4% (10/14) |

**미실행 부분:**
- **서비스 155행 분기:** 위 관찰 사항의 도달할 수 없는 분기다.
- **`AgriculturalCodeCatalog`의 미실행 부분:** 리소스 형식 오류와 읽기 실패 시 `IllegalStateException`을 던지는 경로(30~32행, 37~38행)다.
  - 리소스 경로가 고정된 private 메서드라서, 리소스를 바꾸지 않고는 실행할 수 없다.
  - 운영 리소스를 변경하지 않는다는 조건에 따라 실행하지 않았다.

## 7. 리포트 경로

HTML 리포트는 docs에 복사하지 않았다. 아래 경로는 다음 빌드 때 덮어써진다.

- 테스트 결과 HTML: `farm-api/build/reports/tests/unitTest/index.html`
- 테스트 결과 XML: `farm-api/build/test-results/unitTest/`
- JaCoCo HTML: `farm-api/build/reports/jacoco/jacocoUnitTestReport/html/index.html`
- JaCoCo XML: `farm-api/build/reports/jacoco/jacocoUnitTestReport/jacocoUnitTestReport.xml`

## 8. 단위 테스트로 확인하지 못한 범위

- **`findRecruitmentCandidates`(JPQL):**
  - `order by posting.id desc`
  - `beforeId` 조건
  - OPEN이면서 시작 하한 이후인 일정이 있는 공고만 고르는 EXISTS 조건
  - `Pageable` 적용
- **`findForWorkerPostings`:** 정렬(공고 ID 내림차순, 근무일·ID 오름차순)과 `join fetch`
- **`countWorkerScheduleAssignments`(네이티브):**
  - CANCELLED 제외, 변경 없음·EARLY_ENDED 포함
  - SELECTED만 있고 확정 근무 행이 없는 지원은 0으로 집계
  - 여러 지원과 확정 근무가 있을 때 `count(distinct)` 결과
- **`findWorkerApplications`(네이티브):**
  - 본인 지원만 반환하고 다른 지원자 정보는 조회하지 않음
  - 확정 근무와 변경 기록을 LEFT JOIN한 결과
  - `uq_application_schedule_worker` 제약으로 일정당 본인 지원이 1행인지
- **트랜잭션:** `REPEATABLE_READ` 읽기 전용 트랜잭션에서 배치 사이의 일관성
- **Security:**
  - `SecurityConfig`의 GET `/api/job-postings`·`/{id}` WORKER 제한
  - 익명 401, EMPLOYER 403 JSON
  - 세션 principal 연결
  - `/mine`과의 매핑 우선순위
- **Spring 환경:**
  - Spring Boot가 구성한 JsonMapper의 날짜·일시 형식
  - 코드 사전 빈 생성 시 리소스 로드 실패가 애플리케이션 기동에 미치는 영향
- **브라우저:** farm-demo `/demo/worker-jobs` 화면

## 9. 작업 상태 확인

- **브랜치·커밋:** `app-list`, HEAD `228603f`
- **기존 미커밋 변경(작성자 작업, 보존함):**
  - 수정: `README.md`, `SecurityConfig.java`, `AuthDemoPage.java`, `JobPostingRepository.java`, `WorkScheduleRepository.java`, `demo/farm-login.html`
  - 신규: APP-01 서비스·API·DTO·예외·처리기, `reference/` 코드·리소스, `demo/farm-worker-jobs.html`, 문서 2개
- **내가 추가한 것:** 테스트 파일 3개와 이 보고서. 모두 커밋하지 않았다.
- **하지 않은 것:**
  - 운영 코드·Gradle·CI·마이그레이션 수정
  - 통합 테스트
  - 커밋, 푸시, PR, 브랜치 전환

## PR 본문용 요약

> **APP-01 근로자 공고 목록·상세 단위 검증 (Claude, 2026-11-25)**
> - 신규 단위 테스트 97개(서비스 61, 컨트롤러·오류·JSON 20, 코드 사전 16)를 추가했다. 이 3개 클래스만 선택 실행했고 97개 모두 통과했다. 전체 테스트 결과가 아니다.
> - 다음을 검증했다.
>   - 근로자 역할 재확인
>   - 시작 전 OPEN 날짜만 노출
>   - 시작 시각 경계(나노초·마이크로초, GMT+9, JVM 기본 시간대 무관)
>   - 시간대·DST 해석 불가 처리
>   - 필터링 후 페이지 처리, 100개 배치 이어 읽기, offset 오버플로 없음
>   - 상세 값 보존
>   - 남은 자리 계산
>   - 본인 지원 이력별 판정과 사유 우선순위
>   - 코드 원문 보존과 이름 매핑
> - 서비스 커버리지는 라인 100%, 분기 98.7%다. 미실행 1분기는 현재 도달할 수 없는 상태 분기다.
> - 발견한 결함은 없다.
> - 실제 SQL(정렬·EXISTS·집계·본인 지원), Security·세션, 트랜잭션은 미실행 통합 검증 범위다.
