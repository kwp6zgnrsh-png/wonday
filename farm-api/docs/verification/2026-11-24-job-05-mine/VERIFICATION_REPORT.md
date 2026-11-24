# JOB-05 내 공고 조회 API 단위 테스트 검증 리포트

> **업데이트 (수정 재확인):** DEFECT-7이 수정되어 재확인을 마쳤다. 결과는 §9에 있다. 미해결 결함은 없고, 단위 테스트 226개가 모두 통과했다.

| 항목 | 값 |
| --- | --- |
| 검증일 | 2026-11-24 |
| 대상 API | `GET /api/job-postings/mine` |
| 브랜치 | `job-list` (전환하지 않음) |
| 기준 커밋 | `34131cb` `Merge pull request #4 … job-posting` |
| 검토한 미커밋 운영 코드 | 신규: `job/MyJobPostingApi.java`, `job/JobPostingQueryService.java`, `job/MyJobPostingsResponse.java` / 수정: `job/JobPostingRepository.java`, `job/WorkScheduleRepository.java`, `auth/SecurityConfig.java`, `README.md` |
| AGENTS.md | 저장소에 없음 |
| 검증 범위 | **단위 테스트만** (Spring 컨텍스트, DB, H2, Docker, Testcontainers, Flyway, 서버 기동 없음) |
| 운영 코드 | 수정하지 않음 (뮤테이션 확인 후 원본으로 복원, 바이트 단위 동일 확인) |

## 1. 결론

| 구분 | 결과 |
| --- | --- |
| 실행 | `./gradlew clean test` (단위 테스트만) → **성공** |
| 전체 단위 테스트 | **225개**: 성공 225, 실패 0, 건너뜀 0 |
| JOB-05 새 테스트 | **27개** (서비스 24, 컨트롤러 3) |
| JOB-05 대상 커버리지 | `JobPostingQueryService` 라인 100% (29/29), 분기 100% (6/6) / `MyJobPostingApi` 라인 100% (4/4) |
| 결함 | **1건 (DEFECT-7)**: 집계 결과에 없는 일정이 있으면 조회 전체가 NPE로 실패함. 검증 요청자가 결함으로 확정했고 수정안을 확인함 (§5) |
| 결함 재현 `./gradlew knownDefectTest` | 1개 실패 (의도된 결과). 수정안을 시험 적용하면 통과함 |
| 뮤테이션 확인 | 7건 중 7건을 단위 테스트가 잡아냄 |

## 2. 추가·수정한 파일

| 파일 | 내용 |
| --- | --- |
| `farm-api/src/test/java/com/wonday/farm/job/JobPostingQueryServiceTest.java` | 신규, 24개 (Mockito로 Repository mock) |
| `farm-api/src/test/java/com/wonday/farm/job/MyJobPostingApiTest.java` | 신규, 3개 |
| `farm-api/src/test/java/com/wonday/farm/job/JobPostingQueryServiceKnownDefectTest.java` | 신규, 1개 (DEFECT-7 재현, `known-defect`라 `test`에는 포함되지 않음) |
| `farm-api/docs/verification/2026-11-24-job-05-mine/` | 신규: 이 보고서와 단위 JaCoCo 사본 |

운영 코드·`build.gradle`·기존 테스트는 수정하지 않았다. 실패를 숨기는 `@Disabled`나 제외 태그도 추가하지 않았다.

## 3. 검증한 시나리오

| 필수 항목 | 테스트 (`JobPostingQueryServiceTest$…`) | 결과 |
| --- | --- | --- |
| **1. 사용자 식별과 역할** | | |
| 세션 회원 ID가 서비스로 전달됨 | `MyJobPostingApiTest.passesSessionMemberIdToServiceAndReturnsItsResult` | ✅ |
| 서비스가 그 회원의 현재 DB 역할을 확인함 | `Authorization.checksRoleOfTheSessionMemberId` | ✅ |
| 회원 없음 / WORKER / 지원하지 않는 역할 코드 → 거부 | `rejectsMissingMember…`, `rejectsWorker`, `rejectsUnsupportedRoleCode` | ✅ `AccessDeniedException` |
| 세션 발급 후 DB 역할이 WORKER로 바뀐 경우 거부 | `rejectsMemberWhoseRoleChangedToWorkerAfterSessionWasIssued` | ✅ |
| 거부 시 공고·일정 조회를 하지 않음 | 위 4개 모두 `verifyNoInteractions(postings, schedules)` | ✅ |
| 요청값으로 조회 대상 고용주를 고를 수 없음 | `MyJobPostingApiTest.endpointHasNoRequestParameterForChoosingTheEmployer` (인자는 `@AuthenticationPrincipal` 하나뿐) | ✅ |
| **2. 본인 공고 조회 범위** | | |
| 공고·일정·집계 조회 3개 모두 세션 회원 ID 사용, 다른 호출 없음 | `OwnershipScope.usesSessionMemberIdForPostingsSchedulesAndCounts` | ✅ |
| 응답이 본인 공고 조회 결과 그대로(순서 포함) | `responseContainsExactlyThePostingsReturnedForTheEmployerInTheirOrder` | ✅ |
| 섞여 들어온 일정이 정확한 공고에만 붙음 | `interleavedSchedulesAreAttachedOnlyToTheirOwnPosting` | ✅ |
| 공고 목록에 없는 공고의 일정은 응답에 안 나옴 | `scheduleOfAPostingNotInTheOwnedListIsNotShown` | ✅ |
| **3. 빈 결과** | | |
| 공고 없음 → `jobPostings: []`, null·예외 없음, 일정 조회 생략 | `EmptyResults.noPostingsReturnsEmptyListAndSkipsScheduleQueries` | ✅ |
| 공고는 있으나 일정 없음 → `schedules: []` | `postingWithoutSchedulesHasEmptyScheduleList`, `postingsExistButNoScheduleAnywhere` | ✅ |
| **4. 응답 필드** | | |
| 공고 10개·일정 11개 계약 필드가 저장값대로 매핑 (마이크로초 포함) | `FieldMapping.everyPostingAndScheduleFieldIsMappedFromStoredValues` | ✅ |
| 휴게 둘 다 null | `nullBreaksAreReturnedAsNull` | ✅ |
| 기존 공고의 timeZone null 유지 (GMT+9로 채우지 않음) | `legacyPostingWithNullTimeZoneKeepsNull` | ✅ |
| OPEN·CLOSED·REMOVED와 termsVersion을 그대로 반환 (남은 자리가 있어도 상태 변경 없음) | `storedStatusAndTermsVersionAreReturnedAsIs` | ✅ |
| 코드값을 변환 없이 반환 | `codesAreReturnedWithoutTranslation` | ✅ |
| **5. 날짜별 집계와 남은 자리** | | |
| 지원 0·확정 0 / 지원만 있음 / 일부 확정 / 정원만큼 확정(0) / 정원 초과 집계(0, 음수 아님) — 같은 공고의 날짜별 독립 계산 | `CountsAndRemainingSlots.eachScheduleIsCalculatedIndependently` | ✅ 3, 3, 2, 0, 0 |
| 공고별 정원 차이 반영 | `eachPostingUsesItsOwnHeadcount` | ✅ |
| 집계값이 일정 ID로 연결되고, 반환 순서가 바뀌어도 결과가 같음 | `countsAreMatchedByScheduleIdRegardlessOfReturnOrder` | ✅ |
| long 집계값을 int로 자르지 않음 (`Integer.MAX_VALUE + 5`) | `longCountsAreNotTruncatedToInt` | ✅ |
| 정원 `Integer.MAX_VALUE` − 확정을 long으로 계산 | `largeHeadcountMinusConfirmedIsComputedInLong` | ✅ |
| **6. 조회 중 데이터 변경 방지** | | |
| 조회 메서드 4개 외 호출 없음 (save·delete·flush 없음) | `ReadOnly.queryDoesNotSaveOrDeleteAnything` | ✅ |
| 엔티티 상태·정원·약관 버전이 바뀌지 않음 (재모집·자리 복원 없음) | 같은 테스트 | ✅ |
| `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` 선언 | `readOnlyRepeatableReadTransactionIsDeclared` | ✅ 선언만 확인 (실제 동작은 §6) |

### 뮤테이션 확인

| # | 일부러 넣은 버그 | 잡은 테스트 |
| --- | --- | --- |
| Q1 | 현재 DB 역할 확인 제거 | `Authorization` (3) |
| Q2 | 남은 자리의 0 하한 제거 | `CountsAndRemainingSlots` (2) |
| Q3 | 확정 인원을 int로 자름 | `CountsAndRemainingSlots` (1) |
| Q4 | 일정을 공고 ID가 아닌 일정 ID로 묶음 | `OwnershipScope`, `EmptyResults`, `FieldMapping`, `CountsAndRemainingSlots` (11) |
| Q5 | 공고가 없을 때의 조기 반환 제거 | `EmptyResults` (1) |
| Q6 | null 시간대를 GMT+9로 채움 | `FieldMapping` (1) |
| Q7 | 상태를 항상 OPEN으로 반환 | `FieldMapping` (1) |

## 4. 실행 명령과 결과

```powershell
cd farm-api
$env:JAVA_HOME = "<JDK 21 경로>"
.\gradlew.bat clean test     # build.gradle 설정상 unitTest + 단위 JaCoCo + 90% 기준만 실행
.\gradlew.bat knownDefectTest   # DEFECT-7 재현 (현재 known-defect 태그는 이 단위 테스트 1개뿐이라 통합 테스트는 실행되지 않음)
```

| 항목 | 값 |
| --- | --- |
| 환경 | Windows 11, Temurin 21.0.12.1, Gradle 9.9.1, Spring Boot 4.1.2 |
| 실행된 작업 | `unitTest`, `jacocoUnitTestReport`, `jacocoUnitTestCoverageVerification`, 그리고 `knownDefectTest` (단위 재현 테스트 1개만 포함). `integrationTest`, `fullTest`는 실행하지 않음 |
| 결함 재현 | 1개: 현재 코드에서 실패 1 (NPE). 수정안을 시험 적용하면 통과 1 |
| 전체 | 225개: 성공 225 / 실패 0 / 건너뜀 0 |
| 이 중 JOB-05 | 27개: 성공 27 / 실패 0 / 건너뜀 0 |

### 커버리지 (단위 JaCoCo)

측정 제외 범위와 기준은 바꾸지 않았다: `FarmApiApplication`, `SecurityConfig*` 제외, 라인 90%.

| 구분 | 라인 | 분기 |
| --- | --- | --- |
| **프로젝트 전체 (단위)** | 97.2% (239/246) | 98.6% (73/74) |
| job 패키지 | 95.7% (155/162) | 98.1% (53/54) |
| **JOB-05: `JobPostingQueryService`** | **100% (29/29)** | **100% (6/6)** |
| **JOB-05: `MyJobPostingApi`** | **100% (4/4)** | — |
| **JOB-05: `MyJobPostingsResponse` (+`Posting`, `Schedule`)** | 100% (3/3) | — |
| JOB-05: `SecurityConfig`의 `/mine` 접근 규칙·403 응답 | **단위 측정 제외 대상이라 측정·검증하지 않음** | |
| JOB-05: Repository 쿼리 (`findByEmployer_IdOrderByIdDesc`, `findOwnedSchedules`, `countOwnedSchedules`) | 인터페이스·쿼리 문자열이라 단위로 실행되지 않음 | |

프로젝트 전체에서 빠진 부분은 이전 검증과 같다: 역직렬화기의 예외를 던지는 줄 7개(JaCoCo의 측정 한계)와, GMT+9 고정으로 실행될 수 없게 된 DST 분기 1개. JOB-05 코드와는 무관하다.

리포트:
- HTML: `C:\Users\HCB\Desktop\wonday\farm-api\docs\verification\2026-11-24-job-05-mine\jacoco\html-unit\index.html` (JOB-05 클래스는 `com.wonday.farm.job` 패키지)
- XML: `C:\Users\HCB\Desktop\wonday\farm-api\docs\verification\2026-11-24-job-05-mine\jacoco\unit.xml`
- 빌드 원본: `C:\Users\HCB\Desktop\wonday\farm-api\build\reports\jacoco\jacocoUnitTestReport\` (다음 `clean` 때 삭제됨)

## 5. 결함

### DEFECT-7 집계 결과에 없는 일정이 있으면 내 공고 조회 전체가 실패한다

처음에는 계약에 정해져 있지 않아 판정 요청(P-1)으로 올렸다. 검증 요청자가 "집계가 없으면 0명으로 처리"하는 방어를 적용하기로 결정해 결함으로 등록했다 (2026-11-24).

| 항목 | 내용 |
| --- | --- |
| 재현 테스트 | `farm-api/src/test/java/com/wonday/farm/job/JobPostingQueryServiceKnownDefectTest.java` (`@Tag("known-defect")`, 단위) |
| 재현 조건 | 일정 조회(`findOwnedSchedules`)는 일정 11·12번을 반환하고, 집계 조회(`countOwnedSchedules`)는 11번만 반환함 |
| 현재 결과 | `NullPointerException: Cannot invoke "…ScheduleCounts.getConfirmedCount()" because "counts" is null` → 내 공고 조회 전체가 HTTP 500 (예상) |
| 기대 결과 | 12번 일정은 지원 0, 확정 0, 남은 자리 = 정원으로 표시하고, 11번 일정은 영향받지 않음 |
| 원인 | `JobPostingQueryService.findMine`이 `countsBySchedule.get(schedule.getId())`의 결과를 null 확인 없이 사용함 |
| 발생 가능성 | 현재 집계 쿼리는 `LEFT JOIN`과 `GROUP BY schedule.id`로 모든 일정을 반환하고, 두 조회가 같은 REPEATABLE_READ 트랜잭션에서 실행된다. 그래서 지금 당장 발생할 가능성은 낮다. 그러나 집계 쿼리의 `LEFT JOIN`이 `JOIN`으로 바뀌는 등 변경이 생기면 **지원자가 0명인 일정을 가진 모든 고용주의 조회가 500**이 된다 |

**수정 제안 (3줄).** 검증자가 시험 적용해서 재현 테스트와 기존 단위 테스트 225개가 모두 통과하는 것을 확인한 뒤 원래대로 되돌렸다.

```diff
 			var counts = countsBySchedule.get(schedule.getId());
-			long confirmed = counts.getConfirmedCount();
+			long applicants = counts == null ? 0 : counts.getApplicantCount();
+			long confirmed = counts == null ? 0 : counts.getConfirmedCount();
 			var summary = new MyJobPostingsResponse.Schedule(schedule.getId(), schedule.getWorkDate(),
 				schedule.getPlannedStartAt(), schedule.getPlannedEndAt(), schedule.getBreakStartAt(),
 				schedule.getBreakEndAt(), schedule.getStatus(), schedule.getTermsVersion(),
-				counts.getApplicantCount(), confirmed, Math.max((long) posting.getHeadcount() - confirmed, 0));
+				applicants, confirmed, Math.max((long) posting.getHeadcount() - confirmed, 0));
```

수정 후에는 `JobPostingQueryServiceKnownDefectTest`에서 `@Tag("known-defect")`를 지우면 일반 단위 테스트로 실행된다.

## 6. 단위 테스트로 확인하지 못한 범위 (별도 검증 필요)

아래 항목은 mock 기반 단위 테스트로 **검증하지 않았다.** 통과했다고 보지 않는다.

| 항목 | 이유 / 코드 검토 소견 |
| --- | --- |
| 실제 JPA 쿼리가 다른 고용주의 데이터를 제외하는지 | 소유자 조건(`employer.id = :employerId`, `posting.employer_id = :employerId`)은 세 쿼리 모두에 있음을 코드로 확인했다. 실행 검증은 하지 않음 |
| JOIN으로 지원·확정 인원이 중복 집계되지 않는지 | 집계 쿼리는 `count(distinct application.id)`, `count(distinct assignment.id)`를 쓰고, `work_assignment.application_id`는 UNIQUE다. 코드상 중복 방지가 되어 있지만 실행 검증은 하지 않음 |
| DB의 실제 정렬 결과 | 공고는 `id DESC`, 일정은 `work_date, id`. 단위 테스트는 저장소가 준 순서를 서비스가 유지하는지만 확인함 |
| REPEATABLE_READ 트랜잭션의 실제 동작 | 어노테이션 선언만 확인함 |
| 실제 Security 필터 체인의 401·403, 쿠키·세션 | `SecurityConfig`에서 `GET /api/job-postings/mine`은 `hasRole("EMPLOYER")`이고, 권한이 없으면 403 `EMPLOYER_ROLE_REQUIRED` JSON을 주도록 추가되어 있다(코드 검토). 단위 측정 제외 대상이라 실행하지 않음 |
| 서비스가 던진 `AccessDeniedException`의 HTTP 응답 | 등록 API용 `JobPostingErrorHandler`는 `assignableTypes = JobPostingApi.class`라서 `MyJobPostingApi`에는 적용되지 않는다. 역할이 바뀐 계정의 403 응답은 Spring Security의 예외 변환 필터에 의존하므로, 실제 응답(상태·본문)은 통합 테스트로 확인해야 한다 |
| JSON 직렬화 형식 | `LocalDateTime`·`LocalDate`·enum의 JSON 표현, 빈 목록의 `[]` 직렬화는 확인하지 않음 |

### 작성자의 수동 확인 (이번 자동 테스트와 별개)

작성자가 Apidog에서 확인했다고 전달한 항목이다. **이번 검증에서 다시 실행하지 않았고, 위 자동 테스트 결과에 포함하지 않았다.**

- 고용주의 본인 공고 조회
- 서로 다른 고용주 계정의 공고 분리
- 근로자의 조회 접근 거부

## 7. 실행하지 않은 것 (확인)

- `integrationTest`, `fullTest`, `knownDefectTest` 등 통합 테스트를 호출하는 작업: **실행하지 않음**
- Spring 컨텍스트, DB, Docker를 쓰는 새 테스트: **작성하지 않음** (기존 통합 테스트는 그대로 보존)
- 브랜치 전환, 커밋, 푸시: **하지 않음**
- 운영 코드, API 계약, 커버리지 기준, 측정 제외 범위: **변경하지 않음**

## 8. PR에 붙여 넣을 요약

```markdown
### JOB-05 단위 테스트 검증 (2026-11-24)

- 범위: `GET /api/job-postings/mine` 서비스·컨트롤러 (Repository mock, Spring·DB 없음)
- 결과: `./gradlew test` 통과 — 전체 단위 225개 (JOB-05 신규 27개), 실패 0, 건너뜀 0
- 커버리지: JobPostingQueryService 라인 100% / 분기 100%, MyJobPostingApi 라인 100% (프로젝트 전체 단위 라인 97.2%)
- 확인한 것: 세션 회원 ID 사용, 현재 DB 역할 재확인(역할 변경 포함), 거부 시 조회 없음, 공고–일정 연결, 빈 결과, 전 필드 매핑, timeZone null 유지, 상태 그대로 반환, 일정별 남은 자리(0 하한·long 계산·순서 무관), 데이터 변경 없음
- 뮤테이션 확인 7건 모두 테스트가 잡아냄
- **결함 1건 (DEFECT-7)**: 집계 결과에 없는 일정이 있으면 조회 전체가 NPE(500). 지금은 발생 가능성이 낮지만, 집계 쿼리가 바뀌면 지원자 0명인 일정만 있어도 500이 됨
  → 수정안(3줄): 집계가 없으면 지원 0·확정 0으로 처리. 시험 적용 시 재현 테스트와 기존 단위 225개 모두 통과 (`./gradlew knownDefectTest`)
- 미검증(통합 필요): 실제 쿼리의 소유자 필터·중복 집계·정렬, REPEATABLE_READ 동작, Security 401/403, 역할 변경 계정의 403 응답 본문
- Apidog 수동 확인(작성자)은 이번 자동 결과와 별개

상세: `farm-api/docs/verification/2026-11-24-job-05-mine/VERIFICATION_REPORT.md`
```

## 9. 수정 재확인 (DEFECT-7)

| 항목 | 결과 |
| --- | --- |
| 운영 코드 변경 | `JobPostingQueryService.java`만 변경됨. 제안한 3줄 수정과 동일하고 주석 1줄이 추가됨. 다른 운영 파일(`SecurityConfig`, Repository, API, 응답 레코드)은 첫 검증 이후 바뀌지 않음 |
| 재현 테스트 처리 | 작성 AI가 `JobPostingQueryServiceKnownDefectTest`를 `JobPostingQueryServiceMissingCountsTest`로 이름을 바꾸고 `known-defect` 태그를 제거함. **검증 내용(단언)은 원본과 동일함을 확인함** |
| 나머지 테스트 | `JobPostingQueryServiceTest`, `MyJobPostingApiTest`는 검증자가 작성한 뒤 수정되지 않음 |
| 남은 제외 장치 | 테스트 코드 전체에 `known-defect` 태그와 `@Disabled`가 없음 |
| `./gradlew clean test` | **성공**: 단위 226개 (기존 225 + DEFECT-7 회귀 1), 실패 0, 건너뜀 0 |
| 회귀 테스트가 수정을 지키는지 | 수정 전 코드로 잠시 되돌리면 `MissingCountsTest`가 실패하고, 수정본으로 복원하면 통과함 (복원 후 바이트 단위 동일 확인) |
| **DEFECT-7** | ✅ **해결** |

| 커버리지 (단위) | 라인 | 분기 | 수정 전 |
| --- | --- | --- | --- |
| 프로젝트 전체 | 97.2% (240/247) | 98.7% (77/78) | 97.2% / 98.6% |
| `JobPostingQueryService` | **100% (30/30)** | **100% (10/10)** | 100% (29/29) / 100% (6/6) |
| `MyJobPostingApi` | 100% (4/4) | — | 같음 |

새로 생긴 분기 4개(`counts == null`의 참·거짓 × 2)는 모두 실행됐다.

재확인 리포트:
- HTML: `C:\Users\HCB\Desktop\wonday\farm-api\docs\verification\2026-11-24-job-05-mine\jacoco-recheck\html-unit\index.html`
- XML: `C:\Users\HCB\Desktop\wonday\farm-api\docs\verification\2026-11-24-job-05-mine\jacoco-recheck\unit.xml`
- 수정 전 리포트(`jacoco/`)는 그대로 보존함

§6의 단위 테스트로 확인하지 못한 범위는 그대로 남아 있다 (통합 검증 필요). 통합 테스트, 커밋, 푸시는 하지 않았다.

### PR에 붙여 넣을 재확인 요약

```markdown
### JOB-05 DEFECT-7 수정 재확인 (2026-11-24)

- 수정: 집계가 없는 일정은 지원 0·확정 0으로 처리 (제안한 3줄 수정과 동일)
- 회귀 테스트 `JobPostingQueryServiceMissingCountsTest`가 기본 단위 테스트에 포함됨 (수정 전 코드에서는 실패, 수정본에서는 통과 확인)
- `./gradlew test` 통과 — 단위 226개, 실패 0 / JobPostingQueryService 라인·분기 100%
- 미해결 결함 없음. 통합 검증 항목(쿼리 소유자 필터·중복 집계·정렬, Security 401/403)은 그대로 남음
```
