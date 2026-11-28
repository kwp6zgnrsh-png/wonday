# 관리자 접수 조회 API

## 범위·권한

- 기존 접수·계약·이벤트·처리 이력 테이블을 읽는 조회 API다. DB 구조를 변경하지 않는다.
- ADMIN 세션과 현재 DB의 관리자 권한을 모두 검사한다. 세션에 ADMIN이 남아 있어도 DB 권한이 바뀌면 거부한다.
- 기존 `INSURANCESESSION`을 사용하며 응답은 `Cache-Control: no-store`다.
- 근로자는 기존 `/api/claims`에서 본인 건만 조회한다. 관리자 조회 경로와 권한을 공유하지 않는다.
- 이 단계는 목록·상세·이력 조회다. 승인·반려·상태 변경, 기상 분석 요청·모델 결과·금액 산정·지급 처리는 후속 범위다.

## 경로

| 기능 | 보험 서버 | 웹 프록시 |
|---|---|---|
| 전체 접수 목록 | `GET /api/admin/claims` | `GET /api/insurance/admin/claims` |
| 접수 상세 | `GET /api/admin/claims/{claimId}` | `GET /api/insurance/admin/claims/{claimId}` |
| 처리 이력 | `GET /api/admin/claims/{claimId}/history` | `GET /api/insurance/admin/claims/{claimId}/history` |

GET만 제공한다. 승인·반려 등을 위한 POST·PATCH 경로는 제공하지 않는다.

## 목록·페이지 조회

```http
GET /api/admin/claims?status=REVIEWING&memberId=3&limit=20
```

| 매개변수 | 규칙 |
|---|---|
| `status` | 선택. 저장된 상태 코드와 일치 검색. 대문자로 시작하는 영문 대문자·숫자·밑줄, 최대 40자 |
| `memberId` | 선택. 보험 회원 ID, 양의 정수. 농업 근로자 ID와 구분 |
| `beforeId` | 선택. 이전 응답의 `nextBeforeId`, 양의 정수 |
| `limit` | 기본 20, 허용 1~100 |

현재 자동 접수 상태는 `REVIEWING`이다. 필터는 새로운 승인 상태나 전이 규칙을 정의하지 않으며,
형식이 유효해도 저장된 접수가 없는 상태 코드는 빈 목록을 반환한다.

접수 ID 내림차순이다. `hasMore=true`이면 `nextBeforeId`를 다음 요청의 `beforeId`로 전달한다.
다음 페이지에서도 같은 상태·회원 필터를 유지한다. 전체 테이블의 건수 집계는 수행하지 않는다.

```json
{
  "items": [{
    "id": 101,
    "memberId": 3,
    "memberName": "시연 근로자",
    "contractId": 42,
    "productName": "시연 보험",
    "productVersionNo": 1,
    "assignmentId": 7001,
    "status": "REVIEWING",
    "openedVia": "AUTO",
    "openedAt": "2026-11-28T03:00:00.123456Z",
    "updatedAt": "2026-11-28T03:00:00.123456Z"
  }],
  "hasMore": false,
  "nextBeforeId": null
}
```

`memberName`은 현재 보험 계정의 이름이다. 목록에서는 약정·이벤트 JSON 원문을 읽거나 반환하지 않는다.

## 상세 응답

`GET /api/admin/claims/101`은 아래 네 객체를 반환한다.

| 객체 | 필드 |
|---|---|
| `claim` | 목록의 접수 요약과 동일 |
| `contract` | `id`, `productVersionId`, `productCode`, `productName`, `productVersionNo`, `status`, `acceptedAt`, `coverageFrom`, `coverageTo`, `termsText` |
| `intakeSource` | `eventId`, `changeId`, `sourceVersion`, `changeType`, `reasonCode`, `weatherReasonCode`, `reasonDetail`, `occurredAt`, `sourceUpdatedAt`, `receivedAt` |
| `work` | `assignmentId`, `workerId`, `confirmedAt`, `agreedWorkDate`, `agreedTimeZone`, `agreedWorkLocation`, `agreedCropCode`, `agreedWorkTypeCode`, `agreedStartAt`, `agreedEndAt`, `agreedBreakStartAt`, `agreedBreakEndAt`, `agreedDailyWage`, `agreedLatitude`, `agreedLongitude` |

- 접수의 최초 `trigger_event_id`와 `claim_case.work_snapshot`을 기준으로 읽는다. 나중에 받은 변경 버전으로 바꾸지 않는다.
- 현재 농업 서버나 현재 구인 공고를 다시 조회하지 않는다. 농업 서버의 가용성과 무관하게 저장된 근거를 확인한다.
- DB의 접수·계약·이벤트·이력 시각은 UTC `Z`로 반환한다.
- `work`의 날짜·시간대·시각은 저장된 약정 형식을 유지한다. 예: `GMT+9`, `2026-11-25T08:00:00+09:00`.
- 좌표가 없는 이전 약정은 위도·경도를 null로 반환한다. 현재 주소나 다른 좌표로 보완하지 않는다.
- 원본 JSON이 없는 이전 이벤트의 `weatherReasonCode`는 null이다. 미확보 사유를 `OTHER` 등으로 추측하지 않는다.
- 동의 철회·계약 만료 후에도 과거 접수를 조회한다. 계약과 이벤트 소유자가 일치하지 않는 접수는 조합해서 반환하지 않는다.
- 계약의 표시 상태는 기존 계약 조회와 같이 ACTIVE인 경우 보장 기간에 따라 PENDING·EXPIRED로 표시한다. DB 상태나 보상 자격을 다시 판정·변경하지 않는다.

## 처리 이력

```http
GET /api/admin/claims/101/history?limit=20
```

`beforeId`, `limit` 규칙과 `items`, `hasMore`, `nextBeforeId` 구조는 목록과 같다. 이력 ID 내림차순으로 해당 접수의 이력만 반환한다.

각 항목은 `id`, `action`, `fromStatus`, `toStatus`, `reason`, `actorId`, `assessmentId`, `createdAt`을 제공한다.
현재 자동 접수 이력의 `action`은 `AUTO_RECEIVED`, `toStatus`는 `REVIEWING`이며 자동 처리의 `actorId`는 null이다.
저장된 이력이 없으면 빈 목록을 반환하고 승인·심사 이력을 생성하지 않는다.

## 노출·오류

- 주민번호·외국인등록번호·암호문·비밀번호·전화번호 컬럼은 응답에 포함하지 않는다.
- 약정은 명시적인 DTO 필드만 반환하고 이벤트 원본도 세부 기상 사유만 골라 읽는다. 알 수 없는 추가 JSON 필드를 전달하지 않는다.
- 약정의 필수 값·시각 형식·소유 연결·좌표 쌍이나 이벤트 원본의 형태가 잘못되면 상세 조회는 고정 오류로 중단한다. 원문과 예외 원인 체인을 응답에 포함하지 않는다.
- 상세 스냅샷이 잘못돼도 접수 요약·처리 이력은 별도로 조회할 수 있다.

| 상태 | 오류 |
|---|---|
| 400 | `INVALID_CLAIM_QUERY`: 페이지·필터 범위/형식 오류 |
| 400 | `INVALID_CLAIM_ID`: 양의 정수가 아닌 ID·선행 0·Long 범위 초과 |
| 401 | 로그인 필요 또는 보험 회원 미존재 |
| 403 | 관리자 권한 없음 |
| 404 | `CLAIM_NOT_FOUND`: 없거나 계약·이벤트 연결이 일치하지 않는 접수 |
| 500 | `CLAIM_DATA_INVALID`: 저장된 상세 근거 확인 필요 |

```json
{"code":"CLAIM_NOT_FOUND","message":"Claim not found"}
```
