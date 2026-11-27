# APP-02 demo 지원 이력 조회 오류 수정 재검증 보고서

- 검증일: 2026-11-27
- 검증 담당: Claude (독립 검증, 운영 코드·테스트 코드 수정 없음)
- 이전 보고서(`2026-11-27-app-02-application`)는 덮어쓰지 않았습니다. 이번 결과는 이전 Java 310개·화면 18개 통과 기록과 **합산하지 않습니다.**

## 1. 실행 당시 위치와 미커밋 변경

| 항목 | 값 |
|---|---|
| 브랜치 | `app-apply` |
| HEAD | `ae13a7f` feat(farm): 날짜별 지원·재요청·재지원 API 구현 |
| AGENTS.md | 저장소 루트·`farm-api` 모두 없음 |

이번 수정과 관련된 미커밋 변경(작성자 변경, 검증 중 수정하지 않음):

| 파일 | 상태 | 내용 |
|---|---|---|
| `src/main/java/.../demo/application/DemoJobApplicationRepository.java` | M | `app.is_current` → `cast(app.is_current as signed) as currentFlag` (+주석 1줄) |
| `src/main/resources/demo/farm-job-apply.html` | M | 상세·이력 렌더링 분리, `historyFailure`, `detailNotice`, 이력 401/403 차단 |
| `docs/app-02-job-application-api.md` | M | 리뷰 보완 설명 추가 |
| `docs/verification/2026-11-27-app-02-application/job-apply-page.test.mjs` | M | 회귀 시나리오 6개 추가 |
| `src/test/java/.../demo/application/DemoJobApplicationHistoryMySqlTests.java` | ?? | 신규 MySQL 통합 테스트 3개 |

## 2. 원인 후보와 이번 확인 결과의 구분

- **원인 후보(작성자 설명, 이번에 확인하지 않음):** V12의 `is_current BOOLEAN`(MySQL에서 TINYINT(1))이 Connector/J 설정에 따라 Java Boolean으로 반환되어 `int getCurrentFlag()` projection과 맞지 않아 과거 500이 발생했을 수 있다.
  - 과거 500의 실제 예외 스택은 확보되지 않았습니다.
  - 수정 전 SQL(`app.is_current as currentFlag`)은 이번에 실행하지 않았습니다. 운영 코드를 되돌리거나 바꿀 수 없기 때문입니다. 따라서 수정 전 쿼리가 실제로 실패하는지는 **확인하지 않았습니다.**
- **이번에 확인한 것:** 수정 후 SQL이 실제 Repository·JPA·Connector/J·MySQL 8.4를 통과해 현재/과거 회차를 숫자 1·0으로 반환하고, 서비스가 true·false로 매핑한다.
- 따라서 "과거 모든 500의 원인이 확정됐다"고 볼 수 없습니다.

## 3. 실행 전 설정 확인

`farm-api/build.gradle`:

- `integrationTest`(70–80행)는 `includeTags 'integration'`, `excludeTags 'known-defect'`이며, `shouldRunAfter unitTest`만 있습니다. `dependsOn`·`finalizedBy`가 없어서 단위 테스트·커버리지 리포트·커버리지 기준 검사로 이어지지 않습니다.
- 커버리지 작업(`jacocoIntegrationTestReport`, `jacocoIntegrationTestCoverageVerification`, `fullTest`)은 `integrationTest`에 의존하는 쪽이며, 반대 방향의 연결은 없습니다.
- `--rerun-tasks`는 그래프에 포함된 작업만 다시 실행합니다. 실제로 실행된 작업은 4개(`4 actionable tasks: 4 executed`)였고, 컴파일·테스트 클래스 처리와 `integrationTest`뿐이었습니다.

테스트 클래스 설정:

- `@Tag("integration")`, `@ActiveProfiles("farm-demo")`, `@Import(MySqlTestDatabase.class)`, `@Transactional`(각 테스트 후 롤백).
- `spring.config.import=`를 빈 값으로 덮어써 `application.yaml`의 외부 설정 가져오기를 끕니다. `.env`는 읽지 않았습니다.
- `MySqlTestDatabase`는 `mysql:8.4` Testcontainers 컨테이너를 `@ServiceConnection`으로 연결합니다. 이 방식이 `application.yaml`의 기본 `127.0.0.1:3306` 데이터소스 설정을 대체합니다.
- 데모 비밀번호 프로퍼티는 테스트 전용 가짜 값입니다.

## 4. 실제 실행 명령

```
cd C:\Users\HCB\Desktop\wonday\farm-api
JAVA_HOME=.../jdk-21.0.12.1+1
./gradlew.bat integrationTest --tests "com.wonday.farm.demo.application.DemoJobApplicationHistoryMySqlTests" --rerun-tasks

node --test docs/verification/2026-11-27-app-02-application/job-apply-page.test.mjs   (Node v26.13.1)
```

## 5. MySQL 통합 테스트 결과

| 항목 | 값 |
|---|---|
| 회차 | 1회 실행 |
| 결과 | **실행 3 · 통과 3 · 실패 0 · 오류 0 · 건너뜀 0** |
| 빌드 | `BUILD SUCCESSFUL in 2m 12s` |
| 결과 XML | `build/test-results/integrationTest`에 이 클래스 파일 1개만 있음 (다른 클래스 실행 없음) |

| 테스트 | 시간 |
|---|---|
| `actualProjectionMapsBothCurrentAndPastFlagsToNumbers()` | 2.475s |
| `actualQueryLimitsHistoryToOwnWorkerAndRequestedPosting()` | 0.14s |
| `actualQueryReturnsEmptyHistoryForWorkerWithoutApplications()` | 0.092s |

격리 DB 사용 근거(결과 XML의 로그):

- `Connected to docker`, `Creating container for image`, `Container mysql:8.4 started in PT22.4378369S`. Ryuk(`testcontainers/ryuk:0.14.0`)가 실행 후 정리합니다.
- JDBC URL은 `jdbc:mysql://localhost:5749/farm_db`(4회 기록)입니다. 무작위 매핑 포트이며, 개발 DB 기본 포트 3306은 로그에 나오지 않습니다.
- 새 컨테이너 스키마에 `Successfully applied 12 migrations to schema farm_db, now at version v12`가 기록됐습니다. 이것은 Flyway가 빈 격리 DB에 V1–V12를 적용한 기록이며, 개발 DB 마이그레이션이 아닙니다.
- 활성 프로필은 `"farm-demo"`입니다.
- 참고: 실행 후 `docker ps`에 별도의 `mysql:8.4` 컨테이너 1개가 보였습니다(`Up 6 hours`). 이번 실행 전부터 있던 컨테이너이며, 조회·접속·변경하지 않았습니다.

테스트별 확인 내용:

| 확인 항목 | 근거 | 결과 |
|---|---|---|
| 실제 Repository·JPA·Connector/J·MySQL 통과 | `@SpringBootTest` + 실제 `DemoJobApplicationRepository.findOwnHistory` 호출 | 통과 |
| 현재/과거 회차 `getCurrentFlag()` = 1·0 | `containsExactly(1, 0)` | 통과 |
| 서비스 응답 `current` = true·false | `service.history(...)` → `containsExactly(true, false)` | 통과 |
| 회차·상태·ID·정렬 보존 | ID 순서 `current, past`(id desc), 회차 `2, 1`, 상태 `PENDING, NOT_SELECTED` | 통과 |
| 다른 근로자·다른 공고 지원 제외 | 같은 일정의 다른 근로자 지원과, 본인의 다른 공고 지원을 만든 뒤 본인 1건만 반환 | 통과 |
| 본인 지원 없으면 빈 목록 | 다른 근로자 지원만 있을 때 `isEmpty()` | 통과 |

이 테스트의 한계:

- 과거 회차의 `NOT_SELECTED`는 `ReflectionTestUtils`로 설정했고 `retireForReapplication()`으로 현재 표시를 해제했습니다. 실제 선정 흐름이나 `JobApplicationService.apply` 재지원 경로로 만든 데이터가 아닙니다.
- 배정(`work_assignment`)·변경(`work_change`)이 있는 행의 LEFT JOIN 결과(`assignmentId`, `changeType`)와 500건 제한은 이번 테스트에 포함되지 않았습니다.
- `@Transactional` 안에서 flush 후 같은 연결로 조회했습니다. 커밋된 데이터를 다른 연결에서 읽는 경우는 확인하지 않았습니다.
- HTTP·Security 체인을 거치지 않았습니다. 서비스 빈을 직접 호출했습니다.

## 6. 화면 회귀 테스트 결과 (가짜 DOM·가짜 fetch, 별도 집계)

| 항목 | 값 |
|---|---|
| 회차 | 1회 실행 |
| 결과 | **실행 24 · 통과 24 · 실패 0 · 건너뜀 0 · 취소 0 · todo 0** |
| 시간 | 약 207ms |
| 구성 | 기존 18개 + 추가 6개. 실행 결과도 `tests 24`이며 예상 개수와 일치합니다. |

스크립트는 `src/main/resources/demo/farm-job-apply.html`의 인라인 스크립트 원문을 `node:vm`에서 실행합니다. 수정된 화면 코드가 그대로 대상입니다.

중점 확인 항목과 근거 테스트:

| 확인 항목 | 근거 테스트 | 결과 |
|---|---|---|
| 상세 200·이력 500에서 날짜 3개 활성, 신규 지원 POST 201 | 추가 1 | 통과 |
| 이력 실패를 '본인 지원 이력이 없습니다'로 표시하지 않음, `history-note` error=true | 추가 1 | 통과 |
| 이후 이력 조회 성공 시 오류 안내 해제(`history-note`·`notice` error=false) | 추가 1 (POST 뒤 재조회 성공) | 통과 |
| POST 201 후 이력만 500 → 접수 결과(9001)·재전송 버튼·날짜 3개 유지, 다른 날짜 선택 가능 | 추가 2 | 통과 |
| 같은 키 재전송 → HTTP 200, 첫 키와 동일, 가짜 서버 지원 1건(중복 없음) | 추가 2 | 통과 |
| 이력 통신 실패(TypeError)·`applications: null`·`[null]` → 별도 오류, 날짜 유지, POST 없음 | 추가 3 | 통과 |
| 이력 401·403 → 날짜 0개, 지원 비활성, 준비 키 '없음', POST 없음 | 추가 4 | 통과 |
| 이력 응답 대기 중 계정 변경(id 99) → 이전 데이터 미표시, 키 '없음' | 추가 5 | 통과 |
| 상세 GET 500 → 이전 공고·선택 제거, HTTP 500 안내 | 추가 6 | 통과 |
| 기존 CSRF·요청 키·버전 충돌 409·재지원 2회차·pagehide·텍스트 안전 표시 | 기존 18개 | 모두 통과 |

이 테스트의 한계:

- 가짜 DOM과 가짜 fetch를 쓰므로 실제 브라우저 렌더링·이벤트·쿠키·CSRF 쿠키 처리와는 다릅니다.
- 서버 응답은 테스트가 정한 값이며, 실제 Spring 서버의 응답이 아닙니다.
- 추가 4는 이력의 401·403 처리만 확인합니다. 그 뒤 로그인 화면 이동 등은 확인하지 않았습니다.

## 7. 실패 분석

- 실패·오류·건너뜀이 없습니다. 테스트 코드 문제나 환경 문제도 없었습니다.
- 기능 결함은 발견하지 않았습니다.

관찰 사항(결함 아님, 정책 확인 권장):

- **R-1:** 화면은 이력 응답이 401·403이 아니고 200도 아닌 경우(404·409·5xx 등)를 모두 '보조 장애'로 처리하고 날짜 선택을 유지합니다. 추가 테스트는 500만 확인했습니다. 다른 상태코드에도 같은 처리가 의도인지는 정책 확인이 필요합니다.
- **R-2:** `CAST(... AS SIGNED)` 결과는 MySQL에서 BIGINT(Java Long)입니다. 이를 `int getCurrentFlag()`로 받는 변환이 실제로 동작함은 이번 실행으로 확인했습니다. 다만 Connector/J의 `tinyInt1isBit` 등 다른 접속 설정에서의 동작은 확인하지 않았습니다.

## 8. 기존 데이터·회차·소유자·인증 경계

- **기존 데이터:** 테스트는 격리 컨테이너 안에서 트랜잭션 롤백으로 끝났습니다. 개발 DB와 기존 행에는 접근하지 않았습니다. 기존 데이터 보존이나 V12 적용 결과는 검증하지 않았습니다.
- **회차:** 과거 1회차(is_current 0)와 현재 2회차(1)가 모두 조회되고 순서·회차가 유지되는 것을 확인했습니다(격리 DB, 테스트용 데이터).
- **소유자 범위:** 다른 근로자·다른 공고의 지원이 섞이지 않고, 본인 지원이 없으면 빈 목록을 반환하는 것을 확인했습니다(격리 DB).
- **화면 인증 경계:** 이력 401·403과 계정 변경 시 상세·선택·키가 지워지는 것을 확인했습니다. 가짜 DOM 기준입니다.

## 9. 미검증 범위

- 과거 500이 발생한 특정 공고·데이터의 재현, 실제 예외 스택
- 수정 전 쿼리의 실패 여부(실행하지 않음)
- HTTP 요청·Spring Security 전체 체인(익명 401, CSRF 차단, 세션, 역할 검사)
- 실제 브라우저 렌더링과 사용자 조작
- 개발 DB 설정(Connector/J URL 옵션 포함)에서의 동작, 개발 DB의 V12 상태·기존 데이터
- 배정·변경 JOIN 컬럼, 500건 제한, 동시 요청
- 전체 단위·통합 테스트, 커버리지 기준(지시대로 실행하지 않음)

## 10. 실행 전후 상태와 파일 변경

- 브랜치는 `app-apply`, HEAD는 `ae13a7f`로 실행 전후가 같습니다.
- `git status --porcelain -uall` 출력이 실행 전후 동일했습니다.
- `git diff` 해시가 실행 전후 동일했습니다.
- sha256 해시 비교 결과, 301개 파일이 모두 변경 없음입니다. 대상은 `src/`(main·test), Gradle 파일, `gradle/`, `.github`, 이전 검증 폴더입니다.
- 이번에 새로 만든 파일은 이 보고서 1개입니다. Gradle이 만드는 `build/` 산출물은 git 추적 대상이 아닙니다.
- 커밋·푸시·브랜치 변경·stash·reset을 하지 않았습니다. `.env`, 개발 DB, 이관 도구, 실제 개인정보·암호화 키를 사용하지 않았습니다.
