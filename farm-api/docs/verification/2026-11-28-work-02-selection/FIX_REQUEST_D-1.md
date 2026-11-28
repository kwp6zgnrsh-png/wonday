# 수정 요청 D-1: JSON이 아닌 형식의 선정·재확인 요청 415 응답 형식

- 근거: 같은 폴더 `VERIFICATION_REPORT.md` 5절
- 브랜치: `work-selection` (원래 wonday 폴더, 기준 HEAD `842ef8b`, 구현은 미커밋)
- 심각도: 낮음. 서비스가 호출되지 않아 저장 위험은 없고, 오류 응답 형식과 캐시 금지 헤더만 다르다.

## 1. 현상

다음 두 API에 `Content-Type: application/json`이 아닌 요청을 보내면 문제가 생긴다.
- `POST /api/job-postings/{jobPostingId}/schedules/{workScheduleId}/applications/{applicationId}/selection`
- `POST /api/me/applications/{applicationId}/condition-confirmation`

| | 기대 | 실제 |
|---|---|---|
| 상태 | 415 | 415 |
| 본문 | `{"code":"INVALID_REQUEST","message":"application/json 형식으로 요청해 주세요."}` | 오류 처리기를 거치지 않음 |
| `Cache-Control` | `no-store` | 없음(null) |

## 2. 원인

1. `WorkSelectionApi`와 `ConditionConfirmationApi`가 `@PostMapping(consumes = "application/json")`을 선언했다.
2. 형식이 맞지 않으면 핸들러를 고르기 전에 매핑 단계에서 `HttpMediaTypeNotSupportedException`이 발생한다. 이때 핸들러는 null이다.
3. `WorkSelectionErrorHandler`는 `@RestControllerAdvice(assignableTypes = {WorkSelectionApi.class, ConditionConfirmationApi.class})`로 범위가 정해져 있다. 핸들러가 없는 예외에는 적용되지 않는다. Spring `HandlerTypePredicate`는 null 핸들러 형식에 false를 반환한다.
4. 그래서 `DefaultHandlerExceptionResolver`가 기본 415를 보낸다. `WorkSelectionErrorHandler.mediaType()`은 도달하지 않는 코드가 된다.
5. 저장소에는 범위 없는 전역 advice가 없다. 실제 앱에서도 같은 경로로 처리될 것으로 본다.

## 3. 재현 테스트 (실패 상태로 보존 중, 수정·삭제하지 말 것)

- `farm-api/src/test/java/com/wonday/farm/work/api/WorkSelectionApiTest.java` → `mediaType()`
  - `text/plain`과 `application/x-www-form-urlencoded`가 모두 415 `INVALID_REQUEST`·no-store여야 한다.
- `farm-api/src/test/java/com/wonday/farm/application/api/ConditionConfirmationApiTest.java` → `mediaType()`
  - `text/plain`이 415 `INVALID_REQUEST`·no-store여야 한다.
- 두 테스트 모두 서비스가 호출되지 않아야 한다(`verifyNoInteractions(service)`).

## 4. 수정 방향 (택일, 구현 판단은 작성자)

- **A. `consumes` 제거 후 처리기로 판정**
  - `@PostMapping`에서 `consumes`를 빼면 JSON이 아닌 본문도 컨트롤러 매핑까지 도달한다.
  - 그러면 메시지 변환기가 `HttpMediaTypeNotSupportedException`을 던지고, 범위가 정해진 처리기 `mediaType()`가 처리한다.
  - 변경 범위가 가장 작다.
  - 확인할 점: 같은 경로의 다른 메서드와 매핑이 겹치지 않는지, 본문 없는 요청이 400 `INVALID_REQUEST`로 유지되는지.
- **B. 전역 처리**
  - 범위 없는 advice나 필터에서 두 경로의 415만 같은 형식(code·message·no-store)으로 응답한다.
  - 다른 기능의 415 응답에 영향이 없도록 경로를 한정해야 한다.

## 5. 수정 후 지켜야 할 것

- 기존 동작을 유지한다.
  - 잘못된 JSON·소수·숫자 문자열·범위 초과·누락 → 400 `INVALID_REQUEST`
  - 경로 ID 오류 → 400
  - principal 없음 → 403
  - 성공 201/200·no-store
  - 선정·재확인 GET → 405
- 오류 본문은 `code`·`message` 두 필드만 둔다. 내부 예외·SQL·개인정보를 노출하지 않는다.
- 실패 중인 재현 테스트의 기대값을 낮추거나 제외 태그를 붙이지 않는다.
- 운영 코드 외의 기존 보고서는 수정하지 않는다.
- 커밋·푸시는 사용자 지시에 따른다.

## 6. 재검증 명령 (선택 실행, 전체 테스트 금지)

```
cd C:\Users\HCB\Desktop\wonday\farm-api
.\gradlew.bat unitTest --tests "com.wonday.farm.work.api.WorkSelectionApiTest" --tests "com.wonday.farm.application.api.ConditionConfirmationApiTest"
```

기대 결과: 두 클래스 37개 모두 통과(WorkSelectionApiTest 25 + ConditionConfirmationApiTest 12).

standalone MockMvc는 Security 체인을 통과하지 않는다. 실제 서버의 415 응답 형태(Boot `/error` 포함)는 별도 통합 확인이 필요하다.
