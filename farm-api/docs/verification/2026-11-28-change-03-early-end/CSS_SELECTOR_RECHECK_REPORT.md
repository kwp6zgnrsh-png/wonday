# CHANGE-03 PR 리뷰 수정 재검증 — 로그인 메뉴 CSS 선택자 검사

- 검증일: 2026-11-28
- 검증자: Claude (독립 검증, 테스트·운영 코드 미수정)
- 범위: `WorkEarlyEndDemoPageTest`, `WorkCancellationDemoPageTest` 두 클래스만
- 이 문서는 별도 재검증 기록이다. 같은 폴더의 `VERIFICATION_REPORT.md`(Java 220개·기존 59개·화면 17개)와 합산하지 않으며, 그 문서는 수정하지 않았다.

> 이번 결과는 선택한 두 단위 테스트 클래스의 결과일 뿐이다. 전체 CI 통과, 실제 브라우저 렌더링, 실제 Security 필터 체인 동작을 검증한 것이 아니다.

## 1. 작업 위치·상태

| 항목 | 값 |
|---|---|
| 작업 폴더 | `C:\Users\HCB\Desktop\wonday` (Gradle: `farm-api`) |
| 브랜치 | `work-early-end` (전환하지 않음) |
| HEAD | `50c812d` (merge: 최신 main의 근무 취소 기능 통합) |
| AGENTS.md | 저장소 루트·`farm-api` 모두 없음 |
| 보조 worktree | `C:\Users\HCB\.codex\worktrees\work-early-end\wonday` 사용하지 않음 |

추적 파일의 미커밋 변경은 아래 두 테스트 파일뿐이었다(staging 없음). 둘 다 요청에서 설명한 수정 대상이라 예상 밖 변경은 없었다. 그 밖에는 기존부터 있던 미추적 폴더·파일만 있고, 손대지 않았다.

- `farm-api/src/test/java/com/wonday/farm/demo/earlyend/WorkEarlyEndDemoPageTest.java`
- `farm-api/src/test/java/com/wonday/farm/demo/cancellation/WorkCancellationDemoPageTest.java`

`git diff HEAD -- src/main build.gradle .github` 결과는 비어 있다. 운영 코드·HTML·Gradle·CI 변경은 없다.

## 2. 실행 명령

Git Bash에서 실행했다(PowerShell 예시와 같은 task·필터). 실행 전에 두 클래스의 기존 결과 XML을 지워서 이번 실행으로 새로 생성된 XML만 읽도록 했다.

```
cd /c/Users/HCB/Desktop/wonday/farm-api
rm -f build/test-results/unitTest/TEST-com.wonday.farm.demo.earlyend.WorkEarlyEndDemoPageTest.xml \
      build/test-results/unitTest/TEST-com.wonday.farm.demo.cancellation.WorkCancellationDemoPageTest.xml
export JAVA_HOME=/c/Users/HCB/Desktop/woori-workspace/00.sw/jdk-21.0.12.1+1
./gradlew.bat unitTest --tests "com.wonday.farm.demo.earlyend.WorkEarlyEndDemoPageTest" \
  --tests "com.wonday.farm.demo.cancellation.WorkCancellationDemoPageTest" --console=plain
```

Gradle 출력: `compileJava`, `processResources`, `compileTestJava`, `unitTest` 실행, `BUILD SUCCESSFUL in 35s`, `4 actionable tasks: 4 executed`. `unitTest`는 UP-TO-DATE가 아니라 실제로 실행됐다. 결과 XML 두 개의 timestamp는 `2026-11-28T13:30:18Z`이다.

## 3. 결과 (결과 XML 기준, 1차 실행, 재실행 없음)

| 클래스 | 실행 | 성공 | 실패 | 오류 | 건너뜀 |
|---|---:|---:|---:|---:|---:|
| `com.wonday.farm.demo.earlyend.WorkEarlyEndDemoPageTest` | 3 | 3 | 0 | 0 | 0 |
| `com.wonday.farm.demo.cancellation.WorkCancellationDemoPageTest` | 3 | 3 | 0 | 0 | 0 |

두 클래스를 합해 6개가 실행됐고 모두 통과했다. 결과 XML에 `<failure>`, `<error>`, `<skipped>` 요소는 없다. 두 파일 모두 `@Disabled`, `assume*`, `@Tag`가 없다. 실행된 테스트 이름은 다음과 같다.

- 조기 종료: 로그인 메뉴(`loginLink`), 화면 스크립트(`page`), SecurityConfig 선언(`security`)
- 취소: 로그인 메뉴(`loginLink`), 취소 화면 스크립트, SecurityConfig 선언

커버리지 task는 실행하지 않았다.

## 4. 소스 확인

### 4.1 운영 HTML의 현재 상태 (`src/main/resources/demo/farm-login.html`)

- 링크 두 개가 모두 있다.
  - 59행: `<a href="/demo/work-early-end" id="early-end-link" hidden>`
  - 62행: `<a href="/demo/work-cancellation" id="cancellation-link" hidden>`
- 표시 스크립트가 있다.
  - 117행: `#early-end-link`는 `account.role !== 'EMPLOYER'`이면 숨긴다.
  - 119행: `#cancellation-link`도 같다.
- CSS 31행에 링크 14개를 묶은 하나의 `display: block` 규칙이 있다. `#selection-link, #cancellation-link, #early-end-link`가 이 순서로 마지막에 온다. 요청서에는 세 선택자만 적혀 있었지만, 실제로는 다른 링크 11개가 앞에 있는 더 긴 목록이다. 새 검사는 이 경우도 통과한다.
- 34행의 `[hidden] { display: none !important; }`가 있어서, 기본 `hidden` 속성이 `display: block`보다 우선한다.

### 4.2 `loginLink()` 수정 내용 (두 파일 동일 구조)

1. `contains`로 링크의 정확한 `href`·`id`·`hidden` 태그 문자열과 `account.role !== 'EMPLOYER'` 표시 문장을 검사한다. 수정 전과 같은 문자열이다.
2. `<style ...>...</style>` 영역을 찾는다. 없으면 실패한다.
3. `선택자{본문}` 규칙 중 본문에 `display: block` 선언(공백 허용, `;` 또는 본문 경계로 끝남)이 있는 규칙만 고른다.
4. 그 규칙들의 선택자를 쉼표로 나누고 `strip()`한 목록에 `#early-end-link`(또는 `#cancellation-link`)가 정확히 같은 원소로 있는지 검사한다.

삭제·변경된 것은 연속 문자열 `"#selection-link, #early-end-link {"`(취소는 `"#selection-link, #cancellation-link {"`) 하나뿐이다. 다른 메서드(`page`, `security`)의 검사는 바뀌지 않았다. diff에서 메서드 사이의 빈 줄 하나가 없어졌는데, 이는 동작과 무관하다.

### 4.3 요청 확인 항목별 판단

| 확인 항목 | 판단 | 근거 |
|---|---|---|
| 취소·조기 종료 링크 보존 | 보존됨 | 4.1의 링크·스크립트·CSS. 두 테스트가 각 링크를 실제로 통과시켰다. |
| 선택자 순서·인접·마지막 위치 의존 | 남아 있지 않음 | 목록 원소 포함 여부만 본다. `src/test` 전체에서 `"selection-link, #"`, `"cancellation-link {"`, `"early-end-link {"` 같은 인접 문자열 검사는 검색되지 않았다. |
| 단순 문자열 존재 확인으로 약화 | 약화되지 않음, 오히려 강화됨 | 수정 전에는 `#selection-link, #early-end-link {` 문자열이 어디에 있든 통과했고, 규칙 본문은 보지 않았다. 지금은 `<style>` 안이어야 하고, 그 규칙에 `display: block`이 있어야 하며, 선택자가 정확히 일치해야 한다. 따라서 `#early-end-link:hover`나 `#early-end-link-old`는 일치로 보지 않는다. 주석·스크립트 문자열에만 있는 경우도 통과하지 않는다(style 밖). |
| 기본 숨김·고용주 표시 검사 | 유지됨 | 링크 태그의 `hidden`과 `account.role !== 'EMPLOYER'` 문장을 수정 전과 같은 정확한 문자열로 검사한다. |

변형 HTML로 검사가 실제로 실패하는지 확인하는 변이 실행은 하지 않았다. 운영 HTML 수정과 다른 실행이 금지 범위였기 때문이다. 위 판단은 정규식과 로직을 읽고 내린 것이다.

## 5. 관찰 사항 (결함 아님, 수정 요청 없음)

- **C-1 (정적 검사의 한계):** 같은 링크를 `display: block` 규칙 뒤에 `display: none` 등으로 덮어쓰는 별도 규칙이 추가돼도 이 검사는 잡지 못한다. 수정 전 검사도 잡지 못했으므로 이번 수정으로 생긴 약화는 아니다. `[hidden] { display: none !important; }` 규칙의 존재도 이 두 클래스에서는 검사하지 않는다(수정 전과 동일).
- **C-2 (파서 가정):** 규칙 정규식 `([^{}]+)\{([^{}]*)\}`는 중첩 블록(`@media { ... }`)이나 선택자 앞의 CSS 주석을 고려하지 않는다. 링크 규칙이 `@media` 안으로 옮겨지거나 `/* 메뉴 */ #early-end-link`처럼 주석이 붙으면 거짓 실패가 날 수 있다. 거짓 통과 방향은 아니다. 현재 HTML에는 둘 다 없다. `display: block !important`처럼 적으면 블록 선언으로 인식하지 않아 역시 거짓 실패한다.
- **C-3 (첫 `<style>`만 검사):** 현재 `<style>`은 하나다. 앞으로 여러 개가 되면 첫 번째만 본다.

모두 테스트를 엄격하게 만드는 방향이거나 수정 전과 같은 한계여서 결함으로 분류하지 않았다. FIX_REQUEST는 작성하지 않았다.

## 6. 변경된 파일

- 이번 검증에서 새로 만든 파일: `farm-api/docs/verification/2026-11-28-change-03-early-end/CSS_SELECTOR_RECHECK_REPORT.md` (이 문서) 하나.
- 두 테스트 파일은 검증 대상일 뿐이며 Claude가 수정하지 않았다. 운영 코드, HTML, Gradle, CI, 기존 `VERIFICATION_REPORT.md`, 화면 `.mjs`도 수정하지 않았다.
- `build/` 아래 결과 XML은 Gradle 실행으로 다시 생성됐다.
- `git add`, 커밋, 푸시, 병합, 브랜치 전환, GitHub 쓰기는 하지 않았다.

## 7. 미검증 범위

- 전체 `unitTest`·`test`·`fullTest`·`integrationTest`·`build`/`check`, 그리고 다른 테스트 클래스. 예를 들어 같은 `farm-login.html`을 검사하는 `WorkSelectionDemoPageTest`, `WorkCompletionDemoPageTest` 등은 이번에 실행하지 않았다.
- Node 화면 테스트(`work-early-end-page.test.mjs` 등)
- 실제 브라우저에서 CSS가 적용되는지, 고용주·근로자 로그인 시 링크가 실제로 보이고 숨는지
- 실제 Security 필터 체인(익명 401, CSRF 403, `hasRole`, 세션 격리). `security()`는 `SecurityConfig` 소스 문자열만 검사한다.
- 서버, DB, Docker, farm-demo 프로필의 실제 활성화
- 변형 HTML로 새 검사가 실제로 실패하는지 확인하는 변이 실행
