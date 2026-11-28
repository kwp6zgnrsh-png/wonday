# 기상 수집 작업 이력·수동 재시도

## 범위

- 기존 원본 수집 API를 사용할 때 관측소·관측일별 작업과 각 실행 이력을 함께 기록한다.
- 자동 기상 수집·자동 작업 재시도 스케줄러는 추가하지 않는다. 관리자가 실패한 작업을 선택해서 동기적으로 재수집한다.
- 농업 취소 목록의 매일 18시 조회, 모델 입력 가공·관측 시간 구간·RabbitMQ 전송은 변경하지 않는다.

## API

모든 경로는 보험 서버 기준이다. 웹에서는 `/api/insurance/admin/weather/...`를 사용한다.
ADMIN 세션·현재 DB 권한이 필요하고 POST에는 CSRF 헤더가 필요하다. 응답은 `Cache-Control: no-store`다.

| 메서드·경로 | 동작 |
|---|---|
| `POST /api/admin/weather/collections` | 기존 관측소·날짜 수집. 성공 응답 형식은 유지. 선택적인 `Idempotency-Key` 지원 |
| `GET /api/admin/weather/collection-jobs` | 작업 목록. `state`, `stationCode`, `date`, `beforeId`, `limit` 필터 |
| `GET /api/admin/weather/collection-jobs/{id}` | 현재 상태·횟수·최근 오류·최근 성공 원본 ID·실행 기한 조회 |
| `GET /api/admin/weather/collection-jobs/{id}/attempts` | 과거 실패를 포함한 해당 작업의 모든 실행 이력. `beforeId`, `limit` 지원 |
| `POST /api/admin/weather/collection-jobs/{id}/retry` | FAILED 또는 실행 기한이 만료된 작업 재시도. `Idempotency-Key` 필수 |

목록은 ID 내림차순이며 기본 20개, 최대 100개다. `hasMore=true`이면 `nextBeforeId`를 다음 요청의 `beforeId`로 전달한다.
`state=FAILED`는 현재 실패 상태인 작업을 조회한다. 복구된 작업의 과거 실패는 `/attempts`에서 계속 조회할 수 있다.

수집 요청:

```http
POST /api/admin/weather/collections
Content-Type: application/json
Idempotency-Key: 63ae2b24-b8c8-43cd-861d-d719a389e58f
```

```json
{"stationCode":"477802A001","date":"2026-11-25"}
```

수집 실패 응답 예시(503):

```json
{
  "code": "WEATHER_SERVICE_UNAVAILABLE",
  "message": "Weather provider is temporarily unavailable",
  "jobId": 21,
  "attemptId": 33
}
```

재시도 요청은 본문 없이 작업 ID와 새 요청 키를 지정한다.

```http
POST /api/admin/weather/collection-jobs/21/retry
Idempotency-Key: 91f3094c-4724-4e65-b450-3d138626aab4
```

성공하면 기존 수집 API와 같은 원본 수집 메타데이터를 반환한다.

## 상태·실행 이력

| 상태 | 의미 |
|---|---|
| RUNNING | 외부 API 호출 전에 실행 시작을 DB에 커밋함 |
| SUCCEEDED | 모든 페이지를 검증해 원본을 저장하거나 동일 원본을 재사용함 |
| FAILED | 허용된 오류 코드와 종료 시각을 기록함 |

- 성공 후에도 이전 FAILED 실행을 지우거나 성공으로 덮어쓰지 않는다.
- `attemptCount`는 전체 수집 실행 횟수다. 기존 클라이언트의 HTTP 통신 최대 3회 시도는 각 실행 내부에서 수행되며, 별도 실행 횟수로 세지 않는다.
- `lastCollectionId`는 마지막 성공 원본이다. 이후 수집이 실패하더라도 이전 원본을 삭제하지 않는다.
- `SUCCEEDED`에는 제공처의 데이터 없음(`EMPTY`) 응답도 포함한다. 모델 입력 적합성·관측 구간 완전성·위험 판정을 뜻하지 않는다.
- 관측일 `date`는 GMT+9이며, 실행·저장 시각은 DB UTC DATETIME(6)로 유지하고 응답은 ISO 8601 GMT+9 `+09:00`으로 표시한다.
- 인증·요청 검증에 실패하면 수집 실행을 만들거나 원격 API를 호출하지 않는다.

## 중복 요청·동시 실행

- 같은 관측소·날짜에는 활성 실행 하나만 허용한다. 다른 관리자가 실행해도 진행 중이면 409를 반환한다.
- 요청 키는 표준 UUID이며 회원별로 구분한다. 다른 작업 또는 COLLECT/RETRY 간에 같은 키를 재사용하면 409다.
- 동일 키 재전송: 실행 중이면 409, 성공했으면 같은 원본, 실패했으면 같은 실패 응답을 반환한다. 외부 API를 다시 호출하지 않는다.
- 실패 후 실제로 다시 수집하려면 새 키를 사용한다. 키는 시도할 때 생성하고, 통신 재전송에는 같은 키를 유지한다.
- 기존 수집 API에서 키를 생략하면 매번 새 실행 요청으로 취급한다. 성공한 날짜의 정정 원본을 다시 확인하는 기능도 유지한다.
- 동일 원본 SHA-256은 기존 `weather_collection`을 재사용하고, 정정 원본은 새 기록으로 보관한다.
- 외부 호출 중에는 DB 트랜잭션·회원/작업 행 잠금을 유지하지 않는다. 성공 원본과 성공 이력은 하나의 트랜잭션으로 확정한다.
- 저장 직전 현재 관리자 권한을 다시 확인한다. 권한이 바뀌면 원본 저장을 취소하고 실패 이력만 남긴다.

## 서버 중단·기한 만료

```dotenv
WEATHER_COLLECTION_RUN_TIMEOUT=15m
```

허용 범위는 1초~1일이다. 기본은 실행 시작 후 15분이며 작업 시작 시의 기한을 DB에 저장한다.

- 서버가 중단되거나 DB 장애로 종료 이력을 기록하지 못하면 RUNNING이 남을 수 있다.
- 기한이 지나면 조회 응답의 `retryable=true`가 된다. 조회 자체가 상태를 변경하거나 자동 재시도하지는 않는다.
- 새 키로 재시도하면 이전 실행을 `WEATHER_COLLECTION_RUN_EXPIRED` 실패로 종료하고 새 실행을 시작한다.
- 기한이 만료된 동일 키를 재전송하면 기존 실행의 만료 실패를 반환한다. 새 실행에는 새 키가 필요하다.
- 실행 번호를 저장 시점에 확인하므로 이전 실행이 늦게 돌아와도 새 실행 결과·원본을 덮어쓰지 못한다.
- 기한은 DB 저장 권한을 제한하는 장치다. 이미 보낸 원격 요청을 취소하거나 서버 프로세스를 강제로 종료하지 않는다.

## 오류·DB

- 400: `INVALID_WEATHER_REQUEST_KEY`, `INVALID_WEATHER_JOB_QUERY`, 기존 관측소·날짜 검증 오류
- 401/403: 세션·CSRF·현재 관리자 권한. 실행 도중 권한 변경은 `WEATHER_COLLECTION_ACCESS_CHANGED`
- 404: `WEATHER_COLLECTION_JOB_NOT_FOUND`
- 409: `WEATHER_COLLECTION_IN_PROGRESS`, `WEATHER_COLLECTION_NOT_RETRYABLE`, `WEATHER_IDEMPOTENCY_KEY_REUSED`, `WEATHER_COLLECTION_RUN_EXPIRED`, `WEATHER_COLLECTION_RUN_SUPERSEDED`
- 502: 기존 제공처 거부·잘못된 응답
- 503: 기존 제공처 장애·키 미설정, `WEATHER_COLLECTION_STORAGE_UNAVAILABLE`
- 500: 예상하지 못한 실패는 `WEATHER_COLLECTION_FAILED` 고정 메시지로 반환

예외 메시지·원격 URL·인증키·응답 원문은 작업/실행 이력에 저장하지 않는다. 오류 코드는 허용 목록만 사용한다.
DB 전체 장애 등으로 실패 갱신도 불가능하면 최초 RUNNING 기록이 남으므로, DB 복구 후 기한 만료 재시도로 처리한다.

V11은 `weather_collection_job`과 `weather_collection_attempt`를 추가한다. V10 이전 원본은 변경하지 않고, 과거에 기록하지 않은 실행 이력을 추측해 채우지 않는다.
