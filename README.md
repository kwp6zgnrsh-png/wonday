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

#### 보험 상품 조회 API

로그인 전에도 상품을 조회할 수 있다. 로컬 웹에서는 다음 상대 경로를 호출한다. Vite가 보험 API(8080)의 `/api/products` 경로로 전달한다.

| 기능 | 로컬 웹 경로 | 보험 API 경로 | 응답 |
|---|---|---|---|
| 상품 목록 | GET /api/insurance/products | GET /api/products | 상품 요약 배열. 조회 대상이 없으면 빈 배열 |
| 상품 상세 | GET /api/insurance/products/{productId} | GET /api/products/{productId} | product 요약과 termsText, eligibleWorkGroups, stationSelectionRule, windowRule, indexRules, payoutRules. 조회 대상이 없으면 404 |

상품의 판매 상태는 `ACTIVE`로 사용한다. 약정의 판매 시작 시각은 포함하고 종료 시각은 제외하며, 종료 시각이 비어 있으면 계속 판매한다. 같은 상품에 현재 판매 가능한 버전이 여러 개면 versionNo가 가장 높은 하나를 반환한다. 판매 전·종료·약정 없는 상품은 목록에서 숨기고 상세 조회는 404로 응답한다.

목록의 각 항목과 상세의 `product`에는 id, productCode, name, description, productVersionId, versionNo, salesStartAt, salesEndAt, premiumTerms가 포함된다. premiumTerms와 각 규칙은 DB JSON 구조를 그대로 JSON으로 반환한다. 상품 규칙의 세부 필드는 상품 담당자가 정한다.

이번 구현에서 판매 기간의 DB 일시는 UTC로 해석한다. 프런트는 salesStartAt·salesEndAt을 표시할 때 UTC 기준으로 처리한다. 상품 등록·약정 데이터 입력은 별도 관리하며, 테스트의 예시 금액과 기준은 테스트용 값이다.

#### 보험 상품 가입·농업 플랫폼 연동

**선행 연동 작업:** 농업 담당자가 별도로 구현하는 `POST /api/internal/insurance/worker-verifications`가 필요하다. 이 경로는 서버용 Bearer 토큰으로 인증하고, 세션·CSRF 없이 근로자의 정규화된 이름·전화번호·주민등록번호를 확인해야 한다. 성공 시 workerId·name·phone, 불일치 시 404 `WORKER_IDENTITY_NOT_MATCHED`, 중복 시 409 `WORKER_IDENTITY_AMBIGUOUS`를 반환한다. 현재 저장소의 농업 서버에는 해당 API가 없어 보험 측 토큰 설정만으로 가입을 완료할 수 없다. 농업 구현과 양측 인증 규격을 맞춘 뒤 실제 보안 체인을 통과하는 가입 통합 검증이 필요하다. 보험 측 모의 응답 테스트는 이 통합 검증을 대신하지 않는다.

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

확인 성공 후 연락처·시연 주민등록번호, 농업 계정 연결, 계약을 하나의 트랜잭션으로 저장한다. 요청 본문에 보험 회원 ID를 받는 대신 로그인 세션의 회원을 사용한다. 연락처는 확인된 농업 서버 전화번호를 저장한다. 연동 동의 버전은 `farm-platform-v1`을 사용한다.

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

`worker/`에는 Python 기상 분석 코드와 로컬 데이터가 있다. STEP03 실행 코드는 `worker/code/step03/`에 있으며, [기상 분석 안내](docs/weather_analysis.md)와 [STEP03 사용법](docs/step03/README.md)을 참고한다.

## **종료와 문제 해결**

- **개발 종료** — 웹·API 터미널에서는 `Ctrl+C`, 저장소 루트에서는 `docker compose stop`을 사용한다. 다음 실행은 `docker compose up -d --wait`로 시작하며 로컬 데이터는 볼륨에 유지된다.
- **MySQL 포트 충돌** — 루트 `.env`의 `MYSQL_PORT=3307`처럼 비어 있는 포트를 지정하고 `docker compose up -d --wait`를 다시 실행한 뒤 두 API를 재시작한다. 컨테이너 내부 포트는 3306이다.
- **MySQL 인증 오류(1045)** — `MYSQL_PASSWORD`가 기존 볼륨의 `wonday` 계정 비밀번호와 같은지 확인한다. 이미 초기화한 볼륨에서는 `.env`의 값을 바꿔도 DB 계정 비밀번호가 자동 변경되지 않는다.
- **웹 포트 충돌** — 두 웹은 지정된 포트가 사용 중이면 오류를 표시한다. 이전에 띄운 같은 앱의 터미널을 종료한 뒤 다시 실행한다.
