## Wonday

농업 플랫폼과 보험 서비스를 함께 개발하는 저장소다.

로컬에서는 MySQL과 RabbitMQ를 Docker Compose로 띄우고, 두 Spring Boot API와 두 React 웹은 각각 실행한다.

---

## **준비**

- Git, JDK 21, Node.js 26.13.1, npm, Docker Desktop

---

## **최초 설정**

```bash
git clone https://github.com/kwp6zgnrsh-png/wonday.git
cd wonday
```

루트에 로컬 환경 파일을 한 번 만들어야 한다**.**

macOS·Linux에서는 `cp .env.example .env`

Windows PowerShell에서는 `Copy-Item .env.example .env`를 실행한다.

| 루트 `.env` 항목 | 설정할 값 |
| --- | --- |
| `MYSQL_PORT` | 내 PC에서 접속할 포트. 기본값은 `3306` |
| `MYSQL_ROOT_PASSWORD` | 로컬 MySQL 관리자 비밀번호 |
| `MYSQL_PASSWORD` | 두 API가 사용하는 MySQL 계정 `wonday`의 비밀번호 |
| `RABBITMQ_PASSWORD` | 보험 API와 RabbitMQ 관리 화면에서 사용하는 계정 `wonday`의 비밀번호 |

첫 실행 전에 예시 비밀번호를 각자 정한 로컬 값으로 바꾼다.
Git에는 `.env.example`만 공유한다.

---

## nvm 설치

### Windows
```bash
# 1. NVM for Windows 설치
winget install --id CoreyButler.NVMforWindows --exact

#PowerShell을 닫고 관리자 권한으로 다시 열기

# 2. Node.js 26.13.1 설치 및 사용
nvm install 26.13.1
nvm use 26.13.1

# 3. Node.js와 npm 버전 확인
node -v   # v26.13.1
npm -v    # 11.20.0
```

### macOS
```bash
# 1. nvm 다운로드 및 설치
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash

# 2. 현재 터미널에서 nvm 불러오기
source "$HOME/.nvm/nvm.sh"

# 3. Node.js 26.13.1 다운로드 및 설치
nvm install 26.13.1

# 4. 사용 중인 Node.js 버전 확인
node -v       # v26.13.1
nvm current   # v26.13.1
```



## **실행 순서**

### **1. MySQL과 RabbitMQ**

저장소 루트에서 실행한다. `--wait`는 초기 DB 접속과 RabbitMQ 기동이 확인될 때까지 기다린다.

```bash
docker compose up -d --wait
docker compose ps
```

처음 생성된 MySQL 볼륨에는 `farm_db`와 `insurance_db`가 만들어진다. 

각 API를 처음 실행하면 Flyway가 담당 DB의 테이블을 생성한다. 

RabbitMQ 관리 화면은 localhost:15672에서 열 수 있다.

### **2. API 두 개**

각 행을 저장소 루트에서 연 별도 터미널에서 실행하거나,
IntelliJ에서 각 모듈의 Gradle 프로젝트를 열고 표에 나온 애플리케이션 클래스를 실행한다

| 서비스 | 실행 폴더 | macOS·Linux | Windows PowerShell | 접속 확인 |
| --- | --- | --- | --- | --- |
| 보험 API `InsuranceApiApplication` | `cd insurance-api` | `./gradlew bootRun` | `.\gradlew.bat bootRun` | localhost:8080 → 보험 API 실행 중 |
| 농업 API `FarmApiApplication` | `cd farm-api` | `./gradlew bootRun` | `.\gradlew.bat bootRun` | localhost:8081 → 플랫폼 API 실행 중 |

두 API는 저장소 루트 또는 해당 API 폴더를 작업 디렉터리로 실행하면 루트 `.env`를 읽는다.

### **3. 사용자 웹과 관리자 웹**

각 웹도 별도 터미널에서 실행한다. 

처음 클론했거나 `package-lock.json` 변경을 받은 경우 해당 폴더에서 `npm ci`를 실행한다.

이후에는 `npm run dev`로 시작한다.

| 서비스 | 실행 폴더 | 최초 설치 | 실행 | 접속 확인 |
| --- | --- | --- | --- | --- |
| 사용자 웹 | `cd user-web` | `npm ci` | `npm run dev` | localhost:5173 |
| 관리자 웹 | `cd admin-web` | `npm ci` | `npm run dev` | localhost:5174 |

#### 시간 기준과 보험 코드 주석

업무 날짜·한국 시간의 기준 표기는 `GMT+9`로 통일한다. DB 저장과 서로 다른 시각의 비교는 UTC(`GMT+0`)를 유지한다.

| 구분 | 기준 |
| --- | --- |
| 신규 작업지 시간대·약정 근무일·보장 3개월의 달력 계산 | `GMT+9` |
| 계약·사건·접수 시각의 DB 컬럼과 보험 API의 해당 일시 | UTC, MySQL `DATETIME(6)` 정밀도 |
| 농업 근무 상세·변경 기록 JSON | 수신한 작업지 시간대와 오프셋 보존. 신규 농업 약정은 `GMT+9`, 시각은 `+09:00` |
| 취소 허용 기간 | 약정 작업지 기준 근무 전날 00:00 이상, 근무 시작 시각 미만. 비교는 UTC로 변환 |

예를 들어 `2026-11-24T08:00:00+09:00`과 UTC `2026-11-23T23:00:00`은 같은 순간이다. 보험 API의 시간대 접미사 없는 계약·접수 시각은 UTC로 해석하고 화면에서 `GMT+9`로 변환한다. 원본 스냅샷과 이미 저장된 시각은 표기 통일을 이유로 다시 쓰지 않는다.

보험 코드 주석은 메서드의 책임과 트랜잭션·잠금·시간 변환이 필요한 이유를 설명한다. 중복·제외·실패 후 재시도처럼 다음 동작이 달라지는 지점에도 판단 근거를 남긴다. 단순 대입·조회 코드를 그대로 반복하는 주석은 추가하지 않는다.

#### 보험 상품 조회 API

로그인 전에도 상품을 조회할 수 있다. 로컬 웹에서는 다음 상대 경로를 호출한다. Vite가 보험 API(8080)의 `/api/products` 경로로 전달한다.

| 기능 | 로컬 웹 경로 | 보험 API 경로 | 응답 |
|---|---|---|---|
| 상품 목록 | GET /api/insurance/products | GET /api/products | 상품 요약 배열. 조회 대상이 없으면 빈 배열 |
| 상품 상세 | GET /api/insurance/products/{productId} | GET /api/products/{productId} | product 요약과 termsText, eligibleWorkGroups, stationSelectionRule, windowRule, indexRules, payoutRules. 조회 대상이 없으면 404 |

상품의 판매 상태는 `ACTIVE`로 사용한다. 약정의 판매 시작 시각은 포함하고 종료 시각은 제외하며, 종료 시각이 비어 있으면 계속 판매한다. 같은 상품에 현재 판매 가능한 버전이 여러 개면 versionNo가 가장 높은 하나를 반환한다. 판매 전·종료·약정 없는 상품은 목록에서 숨기고 상세 조회는 404로 응답한다.

목록의 각 항목과 상세의 `product`에는 id, productCode, name, description, productVersionId, versionNo, salesStartAt, salesEndAt, premiumTerms가 포함된다. premiumTerms와 각 규칙은 DB JSON 구조를 그대로 JSON으로 반환한다. 상품 규칙의 세부 필드는 상품 담당자가 정한다.

이번 구현에서 판매 기간의 DB 일시는 UTC로 해석한다. 프런트는 salesStartAt·salesEndAt을 표시할 때 UTC 기준으로 처리한다. 상품 등록·약정 데이터 입력은 별도 관리하며, 테스트의 예시 금액과 기준은 테스트용 값이다.

#### 보험 회원 내·외국인 구분과 식별정보 저장

`POST /api/auth/signup`(웹: `/api/insurance/auth/signup`)에 `nationalityType`을 추가한다.
허용값은 `DOMESTIC`, `FOREIGN`이며 가입·본인 조회 응답에도 같은 필드를 반환한다.
기존 요청의 필드 생략 또는 null은 내국인 가입으로 유지하고, 알 수 없는 코드·숫자 순번은 400으로 거부한다.
계정 생성 때는 기존처럼 이름·로그인 ID·비밀번호를 받고 식별정보는 보험 상품 가입 단계에서 확인한다.
내·외국인 모두 WORKER 계정을 사용한다. Spring Security의 서버 세션과 `INSURANCESESSION` 쿠키, CSRF·BCrypt 인증은 유지한다.
본인 조회는 세션의 회원 ID로 DB를 읽으므로 세션에 내·외국인 속성을 추가하지 않는다.

보험 서버 실행 전 `INSURANCE_IDENTITY_ENCRYPTION_KEY`에 **무작위 32바이트의 Base64**를 설정해야 한다.
키가 없거나 형식이 잘못되면 시작을 중단한다. 아래 PowerShell 예시는 키를 화면에 출력하지 않고 현재 프로세스 환경에만 설정한다.
실제 운영은 비밀 설정에 보관하고 배포·재시작 시 같은 키를 전달한다. 테스트 리소스의 공개 키는 운영에 사용하지 않는다.

```powershell
$identityKeyBytes = [byte[]]::new(32)
$identityRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$identityRandom.GetBytes($identityKeyBytes)
$identityRandom.Dispose()
$env:INSURANCE_IDENTITY_ENCRYPTION_KEY = [Convert]::ToBase64String($identityKeyBytes)
```

V7은 회원 구분·식별번호 종류·암호문 컬럼을 추가하고, V8 Java 마이그레이션은 기존 시연 주민등록번호를
AES-256-GCM으로 암호화한 뒤 평문 컬럼을 NULL로 만든다. 이전 앱 인스턴스를 중단하고 DB 백업·키 설정 후 적용한다.
식별정보가 없는 기존 회원의 구분은 NULL로 유지하고, 기존 내국인 확인 성공 시 확정한다.
기존 값의 형식이 13자리 숫자로 정규화되지 않으면 이관을 중단하므로 원본을 확인해 명시적으로 정정해야 한다.
번호를 임의 생성하거나 잘못된 값을 조용히 버리지 않는다. MySQL의 DDL은 자동 커밋되므로 실패 이력을 확인하고
데이터 정정·필요한 Flyway 복구 후 다시 적용한다. 기존 V1~V6 파일은 수정하지 않는다.

새 가입도 평문을 저장하지 않으며 기존 평문 컬럼은 호환 확인을 위해 빈 상태로 남긴다.
암호화는 매번 새 nonce를 사용하고 회원 ID·식별번호 종류도 인증하므로 암호문 변조·다른 회원으로 복사하면 복호화가 실패한다.
키 자동 생성·매 재시작 재발급·다른 키로 임의 교체는 금지한다. 이전 키를 잃으면 기존 암호문을 복구할 수 없다.
번호·암호문·키는 회원 및 계약 API 응답에 포함하지 않는다.

이번 단계는 외국인 **계정 가입·로그인과 저장 구조 준비**까지다. 농업 V2 본인 확인은 다음 기능에서 연결한다.
그 전에는 외국인의 보험 상품 가입을 503 `FOREIGN_VERIFICATION_NOT_READY`로 거부하고 기존 농업 본인 확인 API를 호출하지 않는다.
기존 내국인 상품 가입·동의·계약·근무 수집·접수 흐름은 유지한다. 리워드·은행 앱·송금 기능은 이번 범위에 포함하지 않는다.

#### 보험 상품 가입·농업 플랫폼 연동

기존 농업 서버의 `POST /api/internal/insurance/worker-verifications`로 내국인 근로자를 확인한다. 서버용 Bearer 토큰으로 인증하며 세션·CSRF 없이 정규화된 이름·전화번호·주민등록번호를 대조한다. 성공 시 workerId·name·phone, 불일치 시 404 `WORKER_IDENTITY_NOT_MATCHED`, 중복 시 409 `WORKER_IDENTITY_AMBIGUOUS`를 반환한다. 외국인 지원용 V2 경로로의 전환은 별도 기능이다.

보험 회원가입과 로그인 후 `POST /api/contracts`로 상품에 가입한다. 로컬 웹의 경로는 `/api/insurance/contracts`다. 기존 인증과 동일하게 세션 쿠키와 CSRF 헤더를 보낸다.

```json
{
  "productVersionId": 7,
  "termsAccepted": true,
  "platformConsentAccepted": true,
  "identity": {
    "name": "김가상",
    "phone": "010-0000-0000",
    "residentRegistrationNumber": "000000-0000000"
  }
}
```

두 동의가 모두 있어야 가입할 수 있다. 상품 상세가 제공한 productVersionId를 사용한다. 고객 정보는 시연용 가상 정보로 준비한다. 이름 앞뒤 공백, 전화번호·주민등록번호의 하이픈과 공백을 정리한 뒤 합의한 농업 API에 전달한다.

서버 설정에는 `FARM_API_BASE_URL`(로컬 기본값 http://127.0.0.1:8081)과 `FARM_API_SERVICE_TOKEN`을 사용한다. 인증 토큰이 설정돼야 확인 요청을 전송할 수 있다. 보험 서버는 `POST /api/internal/insurance/worker-verifications`에 Bearer 토큰과 identity JSON을 보내고, 농업 서버의 workerId·name·phone 응답을 확인한다. 이름과 전화번호가 요청과 일치하고 workerId가 양수여야 성공으로 처리한다. 연결·응답 시간 제한은 각각 3초다.

확인 성공 후 연락처·식별번호 종류·암호문, 농업 계정 연결, 계약을 하나의 트랜잭션으로 저장한다. 요청 본문에 보험 회원 ID를 받는 대신 로그인 세션의 회원을 사용한다. 연락처는 확인된 농업 서버 전화번호를 저장한다. 연동 동의 버전은 `farm-platform-v1`을 사용한다.

보장은 가입 즉시 시작하고, 한국 시각의 같은 시점에서 3개월 뒤 종료한다. 2월 10일 가입하면 5월 10일까지이며, 말일에 해당 날짜가 없으면 해당 월의 말일을 적용한다. 응답의 acceptedAt·coverageFrom·coverageTo는 UTC 일시다. 가입 성공은 201과 계약 상세 및 Location 헤더를 반환한다. 보험 API의 Location은 `/api/contracts/{id}`이며, 개발 웹 프록시는 이를 `/api/insurance/contracts/{id}`로 변환하므로 웹에서 해당 헤더 경로로 상세를 조회할 수 있다.

| 실패 상황 | 보험 API 응답 |
|---|---|
| 로그인 세션 없음 | 401 |
| 근로자 권한 없음 또는 CSRF 누락 | 403 |
| 동의 누락·입력 형식 오류 | 400 |
| 농업 정보 불일치 | 404 및 WORKER_IDENTITY_NOT_MATCHED |
| 농업 정보 중복 | 409 및 WORKER_IDENTITY_AMBIGUOUS |
| 다른 보험 회원에 이미 연결된 농업 계정 | 409 |
| 다른 농업 계정으로 연결 변경 시도 | 409 |
| 같은 상품의 유효 계약이 이미 있음 | 409 |
| 판매 종료 또는 선택한 버전이 최신 판매 가능 버전과 달라짐 | 409 |
| 상품 버전 없음 | 404 |
| 농업 서버 인증 오류·서버 오류·통신 실패·시간 초과 | 503 및 FARM_SERVICE_UNAVAILABLE |
| 농업 서버의 성공 응답이 잘못된 JSON·필드 자료형·미지원 콘텐츠 형식이거나 필수값 누락·요청 불일치 | 502 및 INVALID_PLATFORM_RESPONSE |

기존 연결의 근로자 ID와 동의 버전이 같으면 연결을 재사용한다. 상품 가입 시에는 농업 API로 입력 정보를 다시 검증한다. 같은 회원의 동시 가입은 회원 행 잠금으로 직렬화하고, 같은 상품의 두 번째 가입을 409로 처리한다. 실패 시 전체 로컬 저장을 롤백한다. 가입 성공 응답을 받지 못하고 재시도했을 때 409가 오면 내 보험 목록에서 기존 계약을 확인할 수 있다.

`GET /api/contracts`는 본인의 계약 목록, `GET /api/contracts/{contractId}`는 본인의 계약 상세를 반환한다. 상세에는 가입 당시 약정 버전의 약관이 포함된다. 다른 회원의 계약은 404로 응답한다. 조회 시각이 보장 종료에 도달하면 상태를 EXPIRED로 표시한다.

계약 Location의 개발 프록시 회귀 검사는 저장소 루트에서 `node scripts/test_insurance_contract_proxy.mjs`로 실행한다. 별도 개발 서버나 DB 없이 임시 HTTP 서버와 두 웹의 실제 Vite 설정을 사용해, 가입 응답의 Location을 따라가 계약 JSON을 받는지 검사한다. 이 검사는 실제 농업 서버와의 가입 통합 검증과 별개다.

#### 근무 변경 기록 주기 조회

보험 서버는 활성 농업 계정 연결을 대상으로 근로자별 변경 목록과 확정 근무 상세를 조회한다. 농업 서버에는 다음 두 API가 필요하다.

- `GET /api/internal/insurance/workers/{workerId}/work-changes?cursor=...&limit=100`
- `GET /api/internal/insurance/workers/{workerId}/work-assignments/{assignmentId}`

본인 확인과 같은 `FARM_API_BASE_URL`, `FARM_API_SERVICE_TOKEN`을 사용한다. `FARM_WORK_POLLING_ENABLED=true`로 활성화하면 **GMT+9 기준 매일 18:00에 한 회차** 실행한다. 기본값은 비활성이다. 서버 기본 시간대와 무관하게 cron `0 0 18 * * *`, 시간대 `GMT+9`를 적용하며, 기존 `FARM_WORK_POLLING_DELAY_MS` 설정은 사용하지 않는다. 서버 시작 직후의 즉시 조회는 없으며, 18시에 서버가 중단돼 있었다면 다음 실행은 다음 날 18시다.

한 회차에 유효한 연동 근로자를 순회하며 근로자별로 여러 API 페이지를 조회할 수 있다. `insurance.farm.polling.page-size`는 1~500(기본 100), `max-pages-per-worker`는 기본 10이다. 한 근로자의 페이지 한도에 도달하면 다음 근로자를 처리하고 다음 날 18시에 저장한 커서부터 계속한다. 당일 날짜로 기록을 제한하지 않으므로 이번 회차에서 읽지 못한 변경(18시 이후 발생한 기록 포함)도 다음 회차에 이어서 가져온다.

최초 요청은 커서를 생략한다. 변경 기록은 `platform_work_event`에 `changeId + sourceVersion` 단위로 저장하며 원본 변경 JSON과 확정 근무 JSON을 보관한다. 사유 보완으로 버전이 바뀌면 별도 기록으로 저장한다. 전날 취소, 농장 사정, 조기 종료도 수집한다. 시각 컬럼은 UTC로 변환하고 원본 JSON에는 시간대 오프셋을 유지한다.

농업 확정 근무 상세의 약정 좌표는 다음 필드로 수신한다. 주소·좌표는 농업 플랫폼에서 확정한 약정 정보이며, 보험 서버는 지도 API를 호출해 다시 계산하지 않는다.

| 필드 | JSON 타입 | 유효 범위 |
| --- | --- | --- |
| `agreedLatitude` | 숫자 또는 null | -90 이상 90 이하 |
| `agreedLongitude` | 숫자 또는 null | -180 이상 180 이하 |

좌표는 `BigDecimal`로 받고, 문자열·빈 문자열·불리언·배열·객체는 좌표로 변환하지 않는다. 위도·경도 중 하나만 있거나 범위를 벗어나면 `INVALID_PLATFORM_RESPONSE`로 해당 페이지를 실패시키고 커서를 유지한다. 기존 API·약정과의 호환을 위해 두 필드가 모두 생략되거나 null인 응답도 허용한다. 유효한 0은 누락값으로 취급하지 않는다.

수신 좌표는 기존 `platform_work_event.work_snapshot` JSON에 저장하고 최초 접수의 `claim_case.work_snapshot`에도 보관한다. 보상 상세 API의 `work`에서 이 약정 좌표를 조회할 수 있으며 좌표용 DB 컬럼은 추가하지 않는다. 기존 스냅샷에 좌표가 없으면 그대로 보존하고 수집·접수를 계속한다. 현재 공고 좌표로 보완하거나 0으로 대체하지 않으며, 후속 변경 버전도 기존 접수의 최초 좌표를 덮어쓰지 않는다. 관측소 선정에 전달할 약정 데이터에는 좌표를 제공하되, 좌표가 없는 경우에는 미확보로 구분할 수 있도록 null을 유지한다. 관측소 목록 등록·거리 계산·기상자료 조회는 후속 개발 범위다.

페이지의 모든 상세 조회와 기록 저장이 성공해야 `platform_work_cursor`의 근로자별 커서도 함께 커밋한다. 실패하면 해당 페이지 전체를 롤백하고 다음 날 18시에 마지막 성공 커서로 재시도한다. 실패한 근로자 이후의 다른 근로자는 계속 처리한다. 인증 오류와 잘못된 커서도 저장 위치를 유지하며 로그를 남기므로 농업 서버의 설정·응답을 수정한 뒤 다음 정기 실행에서 재시도할 수 있다.

동일 근로자의 동시 조회는 활성 연결 행의 DB 잠금으로 직렬화한다. 동의 갱신 시에도 근로자별 커서를 이어서 사용한다. 같은 ID·버전에 서로 다른 내용이 오면 처리를 실패시켜 커서 전진을 막는다. 수집된 기록은 이후 보상 접수 기능에서 사용한다.

V4 이전 원본 JSON이 없는 이벤트도 기존 컬럼의 변경 유형·사유·상세 설명·발생 시각·수정 시각을 비교한다. 시각은 UTC 및 마이크로초 정밀도로 비교하며 충돌하면 해당 페이지를 롤백한다. 기존 컬럼에 없는 `recordedAt`·`weatherReasonCode`는 미확인으로 취급하며 `source_payload`를 NULL로 유지한다.

#### 자동 보상 접수와 내 보상 조회

보험 서버는 수집된 근무 변경 기록에서 자동 접수 조건을 확인한다. `CANCELLED`·`WEATHER`인 기록 중 **약정 작업지 기준 근무 전날 00:00부터 예정 근무 시작 직전까지** 발생한 취소를 대상으로 한다. 신규 업무 시간대는 `GMT+9`다. 예를 들어 11월 25일 08:00 근무라면 11월 24일 00:00은 포함하고 11월 25일 08:00은 제외한다. 직전 24시간으로 제한하지 않으며, 실제 취소 발생 시각으로 판단하므로 매일 18시 수집 시점이나 지연 수신에 따라 허용 기간이 바뀌지 않는다. 이틀 전 취소·시작 시각 이후 취소는 `OUTSIDE_CANCELLATION_WINDOW`, 농장 사정·기타 사유·조기 종료는 각각의 제외 결과를 기록한다.

V6는 이전 시간 조건으로 `NOT_SAME_DAY_BEFORE_START` 처리한 이벤트를 판정 대기 상태로 되돌린다. 다음 자동 접수 실행에서 새 기준으로 다시 확인하며, 전날 취소 외에 이전에 같은 코드로 제외했던 기록도 함께 재판정한다. 기존 접수·심사·지급 상태와 최초 근거·이력·원본 JSON·수집 커서는 변경하지 않는다. 시간 조건 외의 사유로 제외된 기록도 유지한다.

취소 발생 시각에 보장 중인 계약을 조회하며 보장 시작은 포함하고 종료는 제외한다. `ACTIVE` 또는 `EXPIRED` 계약을 확인하므로 계약 만료 후 늦게 수신된 기록도 취소 당시 보장 기간으로 접수할 수 있다. 상품별 상세 보장 조건과 기상 기준은 후속 산정 단계에서 확인한다. 적용 계약이 여러 개면 계약마다 보상 건을 생성한다.

V5는 `platform_work_event`에 `intake_processed_at`, `intake_result`, `intake_next_attempt_at`과 조회 인덱스를 추가한다. 기존 기록도 처리 대상에 포함한다. 자동 접수는 `CLAIM_INTAKE_ENABLED` 기본값 `true`, `CLAIM_INTAKE_DELAY_MS` 기본값 `60000`으로 실행한다. 최초 실행도 이 시간이 지난 뒤 시작하며, 한 회차에 처리 시각이 도래한 최대 100건을 다음 시도 시각·ID 순으로 처리한다.

각 이벤트의 처리 결과, `claim_case`, 최초 `claim_history`를 같은 트랜잭션으로 저장한다. 저장이나 원본 확인에 실패하면 롤백하고 1분 후 재시도하도록 기록한다. 다른 이벤트는 계속 처리한다. 연결 및 계약 행 잠금과 기존 계약·근무 고유 제약으로 중복 접수를 방지한다. 동의 갱신 후에도 같은 회원·농업 근로자의 활성 연결을 확인하며, 다른 회원으로 재연결된 과거 원본은 신규 접수에서 제외한다.

신규 건은 `opened_via=AUTO`, `status=REVIEWING`으로 생성한다. 기존 자동·수동 접수 건이나 보완 버전이 있으면 기존 보상 건의 상태와 최초 접수 근거를 보관한다. 새 버전은 근무 변경 수집 이력에 남는다. 확정 근무 조건인 `work_snapshot`이 NULL인 기존 이벤트는 농업 상세 API로 검증한 뒤 근무 조건을 함께 보관한다.

| 기능 | 로컬 웹 경로 | 보험 API 경로 |
|---|---|---|
| 내 보상 목록 | GET /api/insurance/claims | GET /api/claims |
| 내 보상 상세 | GET /api/insurance/claims/{claimId} | GET /api/claims/{claimId} |

세션으로 로그인한 근로자 본인의 내역을 제공한다. 목록은 최신 ID 순 배열이며 결과가 없으면 빈 배열이다. 상세는 `claim`에 계약·상품명·근무 ID·상태·접수 경로·시각, `intakeSource`에 최초 접수 원본 변경·사유·발생 시각, `work`에 확정 근무 JSON을 반환한다. 다른 회원의 상세는 404, 잘못된 ID는 400, 미로그인은 401, 관리자 계정은 403으로 응답한다. 보상 건의 시각 필드는 UTC이며 근무 JSON은 원본 오프셋과 작업지 시간대를 보관한다.

개발 웹 경로 검증은 기존 `node scripts/test_insurance_contract_proxy.mjs`에 보상 목록·상세 검사를 추가했다.

#### 보험 API 테스트 환경

보험 API 테스트는 실행할 때마다 `insurance_test_<임의 UUID>` 스키마를 생성한다. 개발 데이터와 분리된 테스트 MySQL 인스턴스를 권장한다. `MYSQL_HOST`·`MYSQL_PORT`로 서버를 지정하고, 새 테스트 스키마를 생성할 수 있는 계정을 `MYSQL_TEST_USER`·`MYSQL_TEST_PASSWORD`로 전달한 뒤 보험 모듈에서 `./gradlew test`를 실행한다. 이 값이 없으면 기존 MySQL 접속 계정을 사용하며 스키마 생성 권한이 필요하다. CI의 테스트 계정은 작업마다 제공되는 임시 MySQL에만 적용한다. 로컬에 생성된 테스트 스키마는 검증 후 정리할 수 있다.

상품의 UTC 판매 시각은 Hibernate의 Java Time 직접 매핑으로 전달한다. 조회 시각은 DB DATETIME(6)에 맞춰 마이크로초 단위로 잘라 사용한다. 상품 ID는 0을 제외한 양의 10진 정수 표기를 허용한다. 상태 비교는 대소문자·악센트·후행 공백을 구분해 정확한 `ACTIVE`만 공개한다.

#### 로컬 보험 인증 API

사용자 웹(5173)과 관리자 웹(5174)에서는 보험 인증 요청에 상대 경로 `/api/insurance/auth/`를 사용한다. Vite 개발 서버가 이 경로를 보험 API의 `http://127.0.0.1:8080/api/auth/`로 전달한다. 운영 환경에서 웹과 API를 같은 출처로 제공할 때는 해당 환경에 설정된 보험 인증 경로를 사용한다.

1. `GET /api/insurance/auth/csrf` 응답의 `headerName`과 `token`을 이후 POST 요청 헤더에 넣는다.
2. `POST /api/insurance/auth/signup`에는 `loginId`, `name`, `password`를 JSON으로 보낸다.
3. `POST /api/insurance/auth/login`에는 `loginId`와 `password`를 `application/x-www-form-urlencoded` 본문으로 보낸다. 로그인 URL에는 쿼리 문자열을 붙이지 않는다.
4. 로그인 후 `GET /api/insurance/auth/me`로 세션의 회원을 확인한다. 다시 POST 할 때는 새 CSRF 토큰을 사용한다.

브라우저 스모크 테스트는 위 세 서버를 실행한 상태에서 저장소 루트에서 실행한다. 테스트가 두 웹에서 각각 시연용 보험 계정을 생성하므로 로컬 개발 DB를 사용한다.

```bash
python -m pip install playwright
python -m playwright install chromium
python scripts/test_insurance_auth_browser.py
```

`worker/`에는 Python 기상 분석 코드와 로컬 데이터가 있다. STEP03 실행 코드는 `worker/code/step03/`에 있으며, [기상 분석 안내](worker/docs/weather_analysis.md)와 [STEP03 사용법](worker/docs/step03/README.md)을 참고한다.

## **종료와 문제 해결**

- **개발 종료** — 웹·API 터미널에서는 `Ctrl+C`, 저장소 루트에서는 `docker compose stop`을 사용한다. 다음 실행은 `docker compose up -d --wait`로 시작하며 로컬 데이터는 볼륨에 유지된다.
- **MySQL 포트 충돌** — 루트 `.env`의 `MYSQL_PORT=3307`처럼 비어 있는 포트를 지정하고 `docker compose up -d --wait`를 다시 실행한 뒤 두 API를 재시작한다. 컨테이너 내부 포트는 3306이다.
- **MySQL 인증 오류(1045)** — `MYSQL_PASSWORD`가 기존 볼륨의 `wonday` 계정 비밀번호와 같은지 확인한다. 이미 초기화한 볼륨에서는 `.env`의 값을 바꿔도 DB 계정 비밀번호가 자동 변경되지 않는다.
- **웹 포트 충돌** — 두 웹은 지정된 포트가 사용 중이면 오류를 표시한다. 이전에 띄운 같은 앱의 터미널을 종료한 뒤 다시 실행한다.
