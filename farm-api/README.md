# 농업 API 로그인

루트 README의 DB 설정을 마친 후, 데모 계정을 만들려면 로컬 개발 DB에 연결된 PowerShell에서 다음과 같이 실행한다. 두 비밀번호는 예시이며, 각자 정한 값으로 바꾼다.

```powershell
cd farm-api
$env:SPRING_PROFILES_ACTIVE = 'farm-demo'
$env:FARM_DEMO_EMPLOYER_PASSWORD = 'local-employer-demo'
$env:FARM_DEMO_WORKER_PASSWORD = 'local-worker-demo'
$env:SERVER_ADDRESS = '127.0.0.1'
.\gradlew.bat bootRun
```

`demo-employer`는 `EMPLOYER`, `demo-worker`는 `WORKER`다. `farm-demo` 프로필로 시작하면 최초 실행 시 비밀번호를 BCrypt로 해시하여 저장한다. 같은 로그인 ID가 이미 있으면 기존 계정과 비밀번호를 그대로 사용한다. 프로필을 끄더라도 이미 만들어진 계정은 DB에 남으므로 전용 로컬 개발 DB에서 실행한다.

브라우저에서 `http://127.0.0.1:8081/demo/login`을 열고 데모 아이디와 비밀번호를 입력한다. 로그인하면 서버가 반환한 계정 역할에 따라 ‘고용주님, 반갑습니다.’ 또는 ‘근로자님, 반갑습니다.’가 표시된다. 로그아웃한 뒤 다른 계정으로 로그인할 수 있다. `SERVER_PORT=18081`로 실행했다면 주소에도 `18081`을 사용한다. 데모 화면은 `farm-demo` 프로필로 실행할 때 제공된다.

| 요청 | 처리 |
| --- | --- |
| `GET /api/auth/csrf` | `headerName`, `token`을 받는다. |
| `POST /api/auth/login` | `application/x-www-form-urlencoded`로 `loginId`, `password`를 보내고 반환받은 이름과 값으로 CSRF 헤더를 설정한다. 성공은 `204`, 자격 증명 오류는 `401`이다. |
| `GET /api/auth/me` | 세션의 계정 ID, 이름, 역할을 받는다. 로그인하지 않았으면 `401`이다. |
| `POST /api/auth/logout` | 로그인 후 새로 발급받은 CSRF 토큰을 헤더에 보내면 현재 세션이 종료된다. 성공은 `204`다. |

요청 사이에 `FARMSESSION` 쿠키를 전달한다. 로그인과 로그아웃 이후에는 다음 `POST` 전에 CSRF 토큰을 다시 받는다. 다른 역할을 확인하려면 로그아웃 후 새 토큰을 받아 다시 로그인한다.
