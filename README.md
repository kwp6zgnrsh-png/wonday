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

`worker/`는 Python 분석 워커를 위한 자리다. 현재 실행할 워커 코드는 없다.

## **종료와 문제 해결**

- **개발 종료** — 웹·API 터미널에서는 `Ctrl+C`, 저장소 루트에서는 `docker compose stop`을 사용한다. 다음 실행은 `docker compose up -d --wait`로 시작하며 로컬 데이터는 볼륨에 유지된다.
- **MySQL 포트 충돌** — 루트 `.env`의 `MYSQL_PORT=3307`처럼 비어 있는 포트를 지정하고 `docker compose up -d --wait`를 다시 실행한 뒤 두 API를 재시작한다. 컨테이너 내부 포트는 3306이다.
- **MySQL 인증 오류(1045)** — `MYSQL_PASSWORD`가 기존 볼륨의 `wonday` 계정 비밀번호와 같은지 확인한다. 이미 초기화한 볼륨에서는 `.env`의 값을 바꿔도 DB 계정 비밀번호가 자동 변경되지 않는다.
- **웹 포트 충돌** — 두 웹은 지정된 포트가 사용 중이면 오류를 표시한다. 이전에 띄운 같은 앱의 터미널을 종료한 뒤 다시 실행한다.