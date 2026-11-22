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

#### Node 버전 맞추기

wonday 디렉토리로 이동해 아래 명령어를 실행한다.

| 윈도우(**PowerShell**), 맥 OS | 결과 |
| --- | --- |
| `node -v` | 26.13.1 버전은 아래 생략 |
| `nvm install 26.13.1` | 26.13.1 설치 |
| `nvm use 26.13.1` | 26.13.1 사용 |
| `node -v` | 26.13.1 버전 확인 |

---

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

`worker/`는 Python 분석 워커를 위한 자리다. 현재 실행할 워커 코드는 없다.

## **종료와 문제 해결**

- **개발 종료** — 웹·API 터미널에서는 `Ctrl+C`, 저장소 루트에서는 `docker compose stop`을 사용한다. 다음 실행은 `docker compose up -d --wait`로 시작하며 로컬 데이터는 볼륨에 유지된다.
- **MySQL 포트 충돌** — 루트 `.env`의 `MYSQL_PORT=3307`처럼 비어 있는 포트를 지정하고 `docker compose up -d --wait`를 다시 실행한 뒤 두 API를 재시작한다. 컨테이너 내부 포트는 3306이다.
- **MySQL 인증 오류(1045)** — `MYSQL_PASSWORD`가 기존 볼륨의 `wonday` 계정 비밀번호와 같은지 확인한다. 이미 초기화한 볼륨에서는 `.env`의 값을 바꿔도 DB 계정 비밀번호가 자동 변경되지 않는다.
- **웹 포트 충돌** — 두 웹은 지정된 포트가 사용 중이면 오류를 표시한다. 이전에 띄운 같은 앱의 터미널을 종료한 뒤 다시 실행한다.