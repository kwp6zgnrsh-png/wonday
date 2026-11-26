# APP-01 근로자 공고 목록·상세

## 권한과 공통 기준

- 로그인한 근로자만 조회한다. 세션의 회원 ID와 현재 DB 역할을 확인한다.
- GET 조회는 세션 쿠키를 유지하며 CSRF 토큰은 필요하지 않다.
- 다른 근로자의 지원·회원 개인정보나 고용주의 로그인·연락처·주민번호를 반환하지 않는다.
- 코드·날짜별 조건은 공고의 현재 조회 정보다. 개인별 확정 약정 상세 API를 대체하지 않는다.
- 공고별 `latitude`·`longitude`는 저장된 작업지 좌표를 JSON 숫자로 제공한다. V7 이전 공고의 누락 좌표는 양쪽 모두 `null`이며, 주소로 자동 추정하지 않는다. 등록·정밀도 정책은 [좌표 API 문서](job-location-api.md)를 참고한다.
- 종료·취소 날짜의 조건을 현재 값으로 덮어쓰는 쓰기 동작은 없다. 후속 공통 조건 수정에서는 합의한 과거 조건 보존을 연결해야 한다.

## 목록

```http
GET /api/job-postings?page=0&size=20
```

- page: 기본 0, 0 이상 정수
- size: 기본 20, 1~100 정수
- 공고 ID 내림차순. 날짜는 근무일·일정 ID 오름차순.
- 해당 요청의 서버 시각에 아직 시작 전인 `OPEN` 날짜가 하나 이상이면 노출한다.
- 정원 충족이나 본인의 기존 지원 여부로 공고 자체를 숨기지 않는다.
- 시간대가 없거나 잘못됐거나 시작 시각을 단일 Instant로 해석할 수 없는 날짜는 목록의 모집 날짜에 포함하지 않는다. 다른 정상 날짜가 있으면 공고는 노출한다.
- 모든 날짜가 중단·마감·취소·시작 후인 공고는 목록에서 제외한다.
- 정원 충족 날짜의 상태를 자동 마감하지 않는다.

응답 예시(실행 결과 아님):

```json
{
  "jobPostings": [
    {
      "jobPostingId": 101,
      "title": "배추 수확 근로자 모집",
      "workLocation": "전라남도 해남군 예시농장",
      "latitude": 37.123457,
      "longitude": 127.123457,
      "cropCode": "VC02100100100102",
      "cropName": "노지가을배추",
      "workTypeCode": "0058",
      "workTypeName": "수확",
      "headcount": 3,
      "dailyWage": 120000,
      "recruitingWorkDates": ["2026-12-02"],
      "recruitingSchedules": [
        {
          "workScheduleId": 201,
          "workDate": "2026-12-02",
          "confirmedCount": 3,
          "remainingSlots": 0,
          "canApply": false,
          "unavailableReason": "CAPACITY_FULL"
        }
      ]
    }
  ],
  "page": 0,
  "size": 20,
  "hasNext": false
}
```

`recruitingWorkDates`는 날짜 요약 표시용이고 `recruitingSchedules`는 같은 날짜의 남은 자리·지원 가능 여부 표시용이다. 항상 같은 대상에서 생성한다. 빈 결과는 `200`과 빈 배열로 반환한다.

후보는 공고 ID 기준으로 100개씩 나눠 읽고, 저장 시간대를 Java에서 해석한 뒤 목록 노출 대상에 page를 적용한다. DB의 시간대 테이블이나 세션 시간대에 의존하지 않는다. 대상 공고를 size+1개까지 읽어 hasNext를 판단하고 전체 후보를 한 번에 메모리에 올리지 않는다. page는 요청 간 고정 스냅샷을 보장하는 커서가 아니다.

## 상세

```http
GET /api/job-postings/101
```

존재하는 공고는 현재 목록 노출 여부와 관계없이 `200`으로 조회한다. 일정이 없으면 `schedules: []`이다. 상세는 모든 상태와 지난 날짜를 반환한다.

```json
{
  "jobPostingId": 101,
  "title": "배추 수확 근로자 모집",
  "description": "배추 수확 작업입니다.",
  "workLocation": "전라남도 해남군 예시농장",
  "latitude": 37.123457,
  "longitude": 127.123457,
  "cropCode": "VC02100100100102",
  "cropName": "노지가을배추",
  "workTypeCode": "0058",
  "workTypeName": "수확",
  "timeZone": "GMT+9",
  "headcount": 3,
  "dailyWage": 120000,
  "schedules": [
    {
      "workScheduleId": 201,
      "workDate": "2026-12-02",
      "plannedStartAt": "2026-12-02T09:00:00",
      "plannedEndAt": "2026-12-02T17:00:00",
      "breakStartAt": null,
      "breakEndAt": null,
      "status": "OPEN",
      "termsVersion": 1,
      "confirmedCount": 1,
      "remainingSlots": 2,
      "canApply": true,
      "unavailableReason": null
    }
  ]
}
```

## 날짜별 지원 가능 여부

한 요청에서는 고정된 서버 시각을 사용한다. 시작 시각과 같거나 이후면 지원 불가다. 신규 공고는 `GMT+9`, 기존 공고의 유효한 시간대는 보존한다. 날짜·근무·휴게 응답은 저장된 현지 일시이며 오프셋을 임의로 붙이거나 마이크로초를 자르지 않는다.

검사 순서와 `unavailableReason`:

1. `PAUSED` → `RECRUITMENT_PAUSED`, `CLOSED` → `RECRUITMENT_CLOSED`, `REMOVED` → `RECRUITMENT_CANCELLED`
2. 그 외 해석 불가 상태 → `SCHEDULE_STATUS_UNAVAILABLE`
3. 시간대 누락·오류 → `TIME_ZONE_UNAVAILABLE`, 시작 시각 누락·DST 해석 불가 → `SCHEDULE_START_TIME_UNAVAILABLE`
4. 시작 시각 도달 → `SCHEDULE_STARTED`
5. 남은 자리 0 → `CAPACITY_FULL`
6. 본인 확정 근무가 남음 → `ALREADY_CONFIRMED`
7. 본인 지원이 `PENDING` → `ALREADY_APPLIED`, 확정 근무 없이 `SELECTED` 표시만 있어도 `ALREADY_CONFIRMED`
8. 본인 지원이 없거나 `NOT_SELECTED`, 또는 개인 확정 근무가 시작 전 `CANCELLED`이고 다른 조건을 만족하면 지원 가능
9. 그 밖의 지원 상태 → `APPLICATION_STATUS_UNAVAILABLE`

`confirmedCount`는 변경 기록이 없는 확정 근무와 `EARLY_ENDED`를 포함하고 `CANCELLED`는 제외한다. `remainingSlots = max(headcount - confirmedCount, 0)`이며 집계 누락은 0으로 처리한다. 지원 대기 인원은 정원에서 차감하지 않는다. 기존 대기 지원을 미선정으로 바꾸거나 자동 선정하는 동작도 없다.

`canApply`는 현재 시점의 안내이며 실제 지원 저장·재지원·선정 때 다시 검사해야 한다. 재지원 저장과 회차·약정 이력 연결은 후속 APP-02~04·WORK 기능의 범위다. 별도 모집 마감 시각이나 변경 응답 기한(24시간·시작 12시간 전)을 이번 판단에 추가하지 않는다.

## 오류

| HTTP | 코드 | 조건 |
| --- | --- | --- |
| 400 | `INVALID_PAGINATION` | page 음수, size가 1~100 범위 밖 |
| 400 | `INVALID_REQUEST` | ID 양수 위반, 경로·페이지 정수 형식 오류 |
| 401 | 기존 인증 처리 | 세션 없음 |
| 403 | `WORKER_ROLE_REQUIRED` | 고용주 또는 현재 DB 역할이 근로자가 아님 |
| 404 | `JOB_POSTING_NOT_FOUND` | 존재하지 않는 공고 |

기존 고용주 전용 `/mine`은 더 구체적인 규칙으로 먼저 검사한다. 보험 서버 전용 토큰은 이 근로자 조회의 세션 인증을 대신하지 않는다.

## farm-demo에서 확인

1. `farm-demo` 프로필로 서버를 다시 실행한다. 기존 18081 포트 설정을 사용한다면 `http://127.0.0.1:18081/demo/login`을 연다.
2. 기존 데모 근로자 `demo-worker`와 해당 계정의 비밀번호로 로그인한다. 이미 고용주로 로그인했다면 먼저 로그아웃한다.
3. **공고 목록 · 상세 보기**로 `/demo/worker-jobs`에 이동한다.
4. 목록에서 코드·이름·일급·날짜별 남은 자리·지원 가능 사유를 확인하고 **상세 보기**를 누른다.
5. 상세에서 모든 일정의 상태, 근무·휴게시간, 확정 인원·남은 자리, 지원 불가 사유를 확인한다.
6. 목록에서 제외된 공고는 공고 ID를 직접 입력해 조회한다. `/demo/worker-jobs?jobPostingId=101` 형식의 링크도 사용할 수 있다.
7. 아래 API 응답 영역에서 실제 상태코드·오류·JSON 원문·최근 조회 요청을 확인한다.

페이지당 10·20·50개와 이전·다음 이동을 제공한다. 공고가 없으면 빈 결과를 그대로 표시하며 데이터를 자동 생성하지 않는다. 기존 미등록 코드 이름은 '미등록 코드'와 코드 원문으로 표시한다. 근무 일시는 JavaScript Date로 변환하지 않아 날짜·초·소수점 이하를 그대로 표시한다.

고용주로 이 화면에 직접 접근하면 실제 API 요청의 `403 WORKER_ROLE_REQUIRED`를 확인할 수 있다. 미로그인 상태는 로그인 안내와 실제 인증 확인 응답을 표시한다. 세션 쿠키는 브라우저가 전송하며 이 화면은 GET 조회만 하므로 CSRF 토큰을 발급·전송하지 않는다. 실제 지원 등록 버튼은 제공하지 않는다.

## 실행·검증 범위

이번 구현은 읽기 API·코드 사전·농업 서버의 farm-demo 화면·문서다. DB 마이그레이션, 데이터 보정, user-web 등 프론트 모듈, 지원·선정 쓰기 API는 추가하지 않았다.

테스트는 사용자 요청에 따라 실행하지 않는다. 후속 단위 검증에서는 상태·시작 경계·코드 이름과 미등록 null·페이지 경계·정원 충족 노출·취소 반영 집계·본인 지원 이력·DB 역할 변경·DTO 및 오류 응답을 확인해야 한다. 실제 MySQL 쿼리·정렬·projection 변환·동일 스냅샷과 Security 필터는 별도 통합 검증 대상이다.
