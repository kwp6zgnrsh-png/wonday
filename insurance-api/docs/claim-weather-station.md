# 접수별 관측소 선정

## 고정 기준정보

- 경기 24개 관측지는 고정한다. V12가 관측소 코드·이름·위도·경도·주소를 최초 한 번 DB에 적재한다.
- 좌표는 2026-11-28 관측지점 상세정보 API의 실제 응답이다. 프로젝트의 24개 코드·이름 모두 일치하고 좌표 누락이 없음을 확인했다.
- 출처: https://www.data.go.kr/data/15073274/openapi.do (`Instl_La`, `Instl_Lo`, `Instl_Adres`). DB 정밀도는 소수점 6자리 반올림이다.
- 기존 관측소 행이 있으면 동일 ID를 유지한다. `metadata_synced_at`에는 최초 자료 확인 시각(UTC)을 기록한다.
- 관측소 정보용 서비스키·외부 호출·정기/수동 동기화는 사용하지 않는다. 기존 `POST /api/admin/weather/stations/sync`는 제거했다.
- 10분 기상자료 수집의 `WEATHER_API_SERVICE_KEY`는 그대로 사용한다.

## 선정·저장

1. 신규 자동 접수의 `claim_case.work_snapshot.agreedLatitude/agreedLongitude`를 읽는다.
2. DB의 경기 24개 중 Haversine 거리가 가장 짧은 관측소를 선택한다. 같은 거리면 관측소 코드 오름차순이다.
3. V13의 `claim_weather_station`에 접수 ID·관측소 ID·거리(m, 소수점 3자리)·규칙 버전·선정 시각(UTC)을 저장한다.

- 규칙 버전: `nearest-gyeonggi-haversine-v1`. 거리 비교는 반올림 전에 수행한다.
- 자동 접수·최초 처리 이력·관측소 선정은 같은 트랜잭션이다. DB 오류나 불완전한 카탈로그로 실패하면 전체 롤백되고 기존 접수 재시도 경로를 따른다.
- 좌표가 모두 없는 과거 약정은 접수를 유지하고 미선정으로 남긴다. 한쪽만 있거나 형식·범위가 잘못된 좌표는 오류다.
- 현재 농업 공고·후속 변경 이벤트·주소 검색으로 접수 좌표를 바꾸지 않는다.
- 접수 ID PK와 접수 행의 JPA `PESSIMISTIC_WRITE` 잠금으로 동시 최초 선정을 직렬화한다. 재요청은 저장된 결과를 반환한다.
- 기존 접수는 자동으로 일괄 소급 변경하지 않는다. 아래 관리자 API에서 `REVIEWING`이며 좌표가 있는 건만 최초 선정할 수 있다.
- 관측소 선정은 위험 판정이 아니다. 모델 종류·최대 허용 거리·기상 분석 시간 구간·금액 산정은 이 단계에서 결정하지 않는다.

## 관리자 API

기존 `INSURANCESESSION`과 현재 DB 관리자 권한을 확인한다. POST는 CSRF가 필요하며, 모든 응답은 `Cache-Control: no-store`다.
웹 경로는 `/api/insurance/admin/claims/{claimId}/weather-station`이다.

| 메서드 | 보험 서버 경로 | 동작 |
|---|---|---|
| GET | `/api/admin/claims/{claimId}/weather-station` | 선정 여부와 저장된 결과 조회 |
| POST | `/api/admin/claims/{claimId}/weather-station` | 최초 선정 또는 기존 결과 반환. 요청 본문 없음 |

미선정 조회 응답:

```json
{"selected": false, "selection": null}
```

POST 응답 예시(작업지 좌표가 가평 관측지와 같은 경우):

```json
{
  "claimCaseId": 123,
  "stationId": 20,
  "provider": "RDA",
  "stationCode": "477802A001",
  "name": "가평군 가평읍",
  "address": "경기도 가평군 가평읍 아랫마장길 59",
  "latitude": 37.846217,
  "longitude": 127.500637,
  "distanceMeters": 0.000,
  "selectionRule": "nearest-gyeonggi-haversine-v1",
  "selectedAt": "2026-11-28T15:51:38+09:00"
}
```

선정 시각은 DB UTC로 저장하고 응답은 GMT+9 `+09:00`이다. [시각 기준](api-time.md) 참고.

`stationId`는 DB 생성 ID이므로 환경별로 다를 수 있다. 외부 기상자료는 `stationCode`로 조회한다.
선정 후 GET은 `{"selected": true, "selection": 위_객체}`를 반환한다.
기존 관리자 접수 상세에도 `weatherStation` 필드가 추가되며, 미선정은 null이다. 조회 요청은 선정을 수행하지 않는다.

| HTTP | 오류 코드 |
|---|---|
| 400 | `INVALID_CLAIM_ID`: 잘못된 접수 ID |
| 401/403 | 기존 세션·현재 DB 관리자 권한·CSRF 검사 실패 |
| 404 | `CLAIM_NOT_FOUND`: 접수 없음 또는 계약·이벤트 소유 연결 불일치 |
| 409 | `WORK_COORDINATES_UNAVAILABLE`: 저장된 근무 좌표 없음 |
| 409 | `CLAIM_NOT_REVIEWING`: 미선정 접수가 심사 대기 상태가 아님 |
| 500 | `CLAIM_DATA_INVALID`: 잘못된 저장 스냅샷/좌표 |
| 503 | `STATION_METADATA_INCOMPLETE`: 고정 24개 DB 기준정보가 불완전함 |

DB 스키마 변경과 기준정보 적재는 앱 시작 시 Flyway가 실행한다. 실제 개발 DB에 적용하려면 이 브랜치의 서버를 해당 DB 설정으로 시작해야 한다.
