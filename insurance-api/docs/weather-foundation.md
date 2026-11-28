# 관측소 기준정보·기상 원본 수집

## 범위

- 보험 서버가 관측소 기준정보·거리 계산·기상 원본 수집을 담당한다.
- 기상 자료는 날짜를 명시해서 수집한다. 자동 배치 시각·심사 구간·강수량 차분·모델별 집계·RabbitMQ 전송은 아직 연결하지 않는다.
- `weather_collection`에 10분 API의 모든 응답 항목을 XML 원문으로 보존한다. 특정 모델의 열만 골라 저장하지 않는다.
- 기존 `weather_observation`의 수치 컬럼에는 아직 변환·적재하지 않는다. `rn` 등의 의미·단위·결측 처리는 모델 입력 계약과 맞춘 후 적용한다.

## 출처·설정

| 용도 | 공식 자료 |
|---|---|
| 10분 기상자료 | https://www.data.go.kr/data/15078057/openapi.do |
| 관측소 주소·좌표 | https://www.data.go.kr/data/15073274/openapi.do |
| 경기 24개 코드·이름 확인 출처 | https://weather.rda.go.kr/weather/obsrDataDwnld.do |

`src/main/resources/weather/gyeonggi-stations.csv`는 2026-11-27 공식 다운로드 화면의 경기 24개 목록을 고정한 프로젝트 범위다.
코드는 문자열로 보관해 앞의 0을 유지한다. 주소·좌표는 이 파일에서 추측하거나 생성하지 않는다.

```dotenv
WEATHER_API_SERVICE_KEY=일반_인증키_Decoding_권장
WEATHER_STATION_API_SERVICE_KEY=관측지점_API의_승인된_키
```

관측지점 키를 생략하면 기상 키를 사용한다. 두 API는 활용신청·인증 결과가 다를 수 있다.
코드는 일반 키의 `+`, `/`, `=`를 보존하고, Encoding 키라면 한 번만 해제한 뒤 쿼리 값으로 인코딩한다.
인증키가 포함된 요청 URL·원격 예외를 로그·오류 응답·DB 수집 메타데이터에 기록하지 않는다. 외부 HTTP 디버그 로그에도 주의한다.

## API

모든 경로는 보험 서버 기준이다. 웹 프록시는 앞에 `/insurance`를 붙인다.
예: `/api/admin/weather/collections` → `/api/insurance/admin/weather/collections`.
기존 `INSURANCESESSION`을 사용하고 POST에는 CSRF 헤더가 필요하다. 응답은 `Cache-Control: no-store`다.

| 메서드·경로 | 권한 | 동작 |
|---|---|---|
| `GET /api/weather/stations` | WORKER·ADMIN | 경기 24개 목록, 좌표 확보 여부·주소·위경도 조회 |
| `GET /api/weather/stations/nearest?latitude=37.1&longitude=127.1` | WORKER·ADMIN | 24개 중 최근접 관측소·거리(m)·선정 규칙 버전 반환 |
| `POST /api/admin/weather/stations/sync` | ADMIN | 공식 관측지점 API의 전체 페이지에서 대상 24개를 확인해 일괄 갱신 |
| `POST /api/admin/weather/collections` | ADMIN | 관측소·날짜의 10분 원본을 수집하고 보관 |
| `GET /api/admin/weather/collections?stationCode=477802A001&date=2026-11-25` | ADMIN | 해당 관측소·날짜의 최근 수집 기록 최대 100개 |
| `GET /api/admin/weather/collections/{id}` | ADMIN | 수집 메타데이터와 XML 원문 페이지 조회 |

수집 요청:

```json
{"stationCode":"477802A001","date":"2026-11-25"}
```

수집 응답 예시:

```json
{
  "id": 1,
  "stationCode": "477802A001",
  "observationDate": "2026-11-25",
  "state": "RECEIVED",
  "observationCount": 144,
  "payloadSha256": "원본_페이지_JSON의_SHA256",
  "collectedAt": "2026-11-27T01:00:00Z"
}
```

- `date`는 GMT+9 기준 관측일이며 미래 날짜는 거부한다. 저장 시각은 UTC DATETIME(6), 응답은 ISO 8601 Z다.
- 요청은 `getWeatherTenMinList3`의 `date`, `obsr_Spot_Cd`, `Page_No`, `Page_Size=144`를 사용한다.
- 모든 페이지의 결과코드·페이지 번호·전체 건수·관측소·관측일·중복 시각을 확인한 뒤 저장한다. 알 수 없는 기상 열도 원문에 남는다.
- 통신 오류·HTTP 429/5xx는 최대 3회 요청한다. 인증·매개변수 거부와 잘못된 XML은 무제한 재시도하지 않는다.
- 조회·페이지 검증 실패 시 일부 페이지만 저장하지 않는다. 같은 요청으로 다시 시도할 수 있다.
- 수집 실행의 실패 원인·횟수·과거 이력은 별도 작업 테이블에 기록한다. 관리자 조회·재시도와 요청 키 사용은 [수집 이력·재시도 규격](weather-collection-retry.md)을 따른다.
- 같은 관측소·날짜·원본 해시는 한 건만 저장한다. 원본이 정정되면 새 수집 기록을 추가하고 이전 원문을 유지한다.
- `EMPTY`는 제공처의 데이터 없음 결과다. `RECEIVED`도 수신 성공만 뜻하며, 하루 전체 확보·센서 정상·모델 입력 적격성을 보장하는 상태는 아니다.
- 좌표가 없어도 기상 수집은 가능하다. 최근접 계산은 대상 24개 좌표가 모두 동기화된 뒤에만 가능하며, 미확보 좌표를 0이나 주소 추정값으로 채우지 않는다.
- 관측소 동기화는 코드순으로 원자적 upsert하며, 서로 다른 관리자의 최초 동시 요청도 같은 UNIQUE 키를 갱신한다. 전체 카탈로그는 한 트랜잭션으로 저장한다.
- 거리는 Haversine 구면 거리이며 동일 거리에서는 코드 오름차순으로 선택한다. 규칙 버전은 `nearest-gyeonggi-haversine-v1`이다.
- 선정 조회는 후보와 계산 근거를 반환한다. 접수 건에 선정 근거를 확정 저장하거나 모델 지원 여부·최대 허용 거리를 판단하는 심사 단계는 후속 범위다.

## 오류·DB

400: `UNSUPPORTED_WEATHER_STATION`, `INVALID_WEATHER_DATE`, `INVALID_WORK_COORDINATES`.
401/403: 기존 세션 인증·현재 DB 회원 역할·CSRF 검사.
404: `WEATHER_COLLECTION_NOT_FOUND`.
502: `INVALID_WEATHER_RESPONSE`, `WEATHER_PROVIDER_REJECTED`, `INCOMPLETE_STATION_RESPONSE`.
503: `WEATHER_API_NOT_CONFIGURED`, `WEATHER_SERVICE_UNAVAILABLE`, `STATION_METADATA_INCOMPLETE`.

V10은 기존 `weather_station`에 주소·사용 여부·동기화 시각을 추가한다. 기존 행은 임의로 활성화하지 않는다.
새 `weather_collection`은 날짜·코드·원문·건수·SHA256·수집 시각을 보관하며, UNIQUE로 동일 원본의 동시 중복을 막는다.
