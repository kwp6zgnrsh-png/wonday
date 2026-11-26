# D-1 수정 재검증 보고서 (작업지 좌표 극소 지수 입력)

- 검증일: 2026-11-26
- 브랜치: `job-location`
  - HEAD는 `585f5b7`이고, 커밋되지 않은 변경이 있어요.
  - 다른 작업 공간 `member-identity`는 건드리지 않았어요.
- 저장소 루트와 `farm-api`에는 `AGENTS.md`가 없어요.
- 원래 보고서: [`2026-11-26-job-location`](../2026-11-26-job-location/VERIFICATION_REPORT.md)
  - 그 보고서의 신규 226개·기존 331개 통과는 **D-1 수정 전 결과**예요.
  - 이번 결과와 합치지 않았어요.

## 1. 검토한 수정

`WorkLocationValidator.normalize`는 다음 순서로 동작해요.

1. null과 원본 범위를 검사해요(기존과 같아요).
2. `value.abs()`가 `0.0000005`보다 작으면 상수 `0.000000`(scale 6)을 반환해요.
3. 그 밖의 값에는 기존 `setScale(6, HALF_UP)`을 적용해요.

코드 검토 결과는 다음과 같아요.

- **경계 처리:** 경계 `±0.0000005`는 비교식이 `< 0`이라 빠른 경로에 들어가지 않아요. 그래서 기존 HALF_UP 결과(`±0.000001`)가 그대로 유지돼요.
- **연산 비용:** 극소값의 `abs()`와 `compareTo`는 큰 거듭제곱을 만들지 않아요.
- **상수 공유:** 반환하는 상수는 공유 객체지만, BigDecimal은 불변이라 문제없어요.
- **남는 경로의 scale:** 절댓값이 `0.0000005` 이상인데 scale이 큰 값은 유효 숫자 개수도 그만큼 많아야 해요.
  - JSON 경로에서는 Jackson의 숫자 길이 제한(1000자) 때문에 scale이 약 1000 수준으로 제한돼요.
  - 이 판단은 코드와 수식으로만 한 것이고, 거대한 입력으로 실행하지는 않았어요.

운영 코드 수정이 필요한 결함은 이번에 발견하지 못했어요.

## 2. 변경·추가한 테스트 파일

**신규:** `farm-api/src/test/java/com/wonday/farm/job/validation/WorkLocationTinyExponentTest.java` (49개)

- 극소 지수 3종(`1E-10000000`, `-1E-10000000`, `0E-10000000`)을 확인해요.
  - 위도와 경도 모두 `0.000000`이고 scale은 6이에요.
  - 결과가 `-0.000000`이 아니에요.
  - 호출 1회가 200ms 안에 끝나요(`assertTimeout`).
  - 원본의 scale, unscaled 값, 문자열이 변하지 않아요.
- 명세의 HALF_UP 경계 6개에 지수 표기 경계 8개를 더해, 위도와 경도 각각에서 확인해요. 결과 scale은 항상 6이에요.
- 중간 크기 scale의 값 20개에서 결과가 참조값 `setScale(6, HALF_UP)`과 같은지 비교해요.
  - 대상은 `±0.0000004999999`, `1E-1000`, `0E-1000`, 일반 좌표 등이에요.
  - 빠른 경로와 기존 경로가 수학적으로 같은 결과를 내는지 보는 테스트예요.
- `0.0000005` 바로 아래·위로 500자리가 이어지는 긴 소수를 양수와 음수로 확인해요.
- JSON 입력 경로를 확인해요. 실제 `StrictCoordinateDeserializer`로 읽은 뒤 Bean Validation과 `JobPosting` 생성자의 정규화까지 이어서 봐요.
  - 극소 지수 JSON은 unscaled 값 ±1, scale 10,000,000의 원본 그대로 읽혀요.
  - Bean Validation을 통과하고, 엔티티에서 200ms 안에 `0.000000`이 돼요.
  - `0E-10000000`도 `0.000000`이에요. 누락으로 취급하지 않아요.
  - JSON으로 읽은 경계값 6개가 위도와 경도에 같은 결과로 저장돼요.
  - `±0.00000049999999999999999999`는 double로 바꾸면 `5.0E-7`이 되어 `0.000001`로 반올림될 값이에요. 이 값이 BigDecimal 원문 그대로 읽혀서 `0.000000`으로 저장되는 것을 확인했어요. 정밀도 손실이 없다는 뜻이에요.
- 실행 중 1건이 실패했는데, 제 테스트 실수였어요.
  - `-179.9999995`를 위도에도 적용해서, 운영 코드가 정상적으로 범위 거절을 한 경우였어요.
  - 범위 안의 값만 위도에 적용하도록 고쳤어요. 기대값은 완화하지 않았어요.

**수정하지 않은 파일:** `WorkLocationValidatorTest.java`

- 이전 검증에서 제가 만든 파일인데, 이후 다른 쪽에서 D-1 회귀 테스트 9개를 추가했어요.
  - `tinyExponentCoordinates`: 3개, `@Timeout(5)`
  - `halfMicroBoundaryForBothCoordinates`: 6개
- 이 파일은 고치지 않고 그대로 실행만 했어요. 지금 65개예요.

운영 코드, 문서, Gradle 설정은 수정하지 않았어요.

## 3. 실행 명령과 범위

먼저 Gradle 설정을 확인했어요.

- `unitTest`는 `integration`과 `known-defect` 태그를 제외해요.
- `unitTest`에 자동으로 연결된 태스크는 없어요.
- `jacocoUnitTestReport`는 직접 지정했을 때만 실행돼요.

`farm-api`에서 `JAVA_HOME=jdk-21.0.12.1+1`로 실행했어요.

```
./gradlew.bat --no-daemon unitTest \
  --tests 'com.wonday.farm.job.validation.WorkLocationValidatorTest*' \
  --tests 'com.wonday.farm.job.validation.WorkLocationTinyExponentTest*' \
  --tests 'com.wonday.farm.job.dto.JobLocationRequestTest*' \
  jacocoUnitTestReport
```

- 실행 범위는 위 3개 클래스뿐이에요. 전체 단위 테스트, 통합 테스트, 서버, DB는 실행하지 않았어요.
- 최종 실행은 BUILD SUCCESSFUL, 약 16초였어요.
- 결과 파일:
  - `farm-api/build/test-results/unitTest/`
  - `farm-api/build/reports/tests/unitTest/`
  - `farm-api/build/reports/jacoco/jacocoUnitTestReport/`

## 4. 결과 (수정 후, 이번 실행)

| 클래스 | 테스트 | 성공 | 실패 | 오류 | 건너뜀 |
|---|---|---|---|---|---|
| `WorkLocationTinyExponentTest` (신규) | 49 | 49 | 0 | 0 | 0 |
| `WorkLocationValidatorTest` (기존 + D-1 회귀 9개) | 65 | 65 | 0 | 0 | 0 |
| `JobLocationRequestTest` (기존 JSON 테스트) | 66 | 66 | 0 | 0 | 0 |
| **합계** | **180** | **180** | 0 | 0 | 0 |

**측정 시간** (JUnit XML의 테스트 시간, 단위 테스트 안의 메서드 호출 기준)

- `1E-10000000`: 17ms (위도·경도 2회 호출, 첫 실행 포함)
- `-1E-10000000`: 2ms
- 기존 클래스의 D-1 회귀 테스트: 0~10ms
- 수정 전에는 같은 입력의 `setScale` 한 번이 약 8,318ms였어요.

**클래스별 커버리지** (이번 3개 클래스 실행 기준)

- `WorkLocationValidator`: 라인 10/10, 분기 8/8
- `StrictCoordinateDeserializer`: 라인 3/4, 분기 4/4
  - 빠진 1줄은 return 안에서 예외가 나는 줄을 JaCoCo가 미실행으로 표시하는 알려진 현상이에요.

## 5. D-1 수정 효과와 HALF_UP 경계

**극소 지수 입력**

- `1E-10000000`, `-1E-10000000`, `0E-10000000` 모두 위도와 경도에서 `0.000000`(scale 6)이에요.
- 지연이 없어졌어요(수 ms).
- 원본 BigDecimal은 변하지 않아요.

**HALF_UP 경계** (위도와 경도에서 같은 결과)

| 입력 | 결과 |
|---|---|
| `0.000000499999999999` | `0.000000` |
| `-0.000000499999999999` | `0.000000` |
| `0.0000005` | `0.000001` |
| `-0.0000005` | `-0.000001` |
| `0.000000500000000001` | `0.000001` |
| `-0.000000500000000001` | `-0.000001` |

- `5E-7`, `-5E-7`, `4.99999999E-7`, 500자리 긴 소수 같은 표기 차이에도 같은 결과예요.
- 20개 값에서 기존 `setScale(6, HALF_UP)`과 결과가 같아요.

**기존 정책 유지** (`WorkLocationValidatorTest` 65개)

- 일반 좌표의 양수·음수 HALF_UP 결과가 그대로예요.
- 정수와 0도 scale 6으로 정리돼요.
- 위도 ±90, 경도 ±180 양 끝을 허용해요.
- `90.0000004`와 `180.0000004`는 반올림 전에 거절돼요. 원본 범위를 먼저 검사하는 순서가 유지돼요.
- null 거절의 오류 형식(`INVALID_WORK_LOCATION`과 latitude/longitude field)이 그대로예요.

**JSON 경로**

- 실제 역직렬화기가 문자열이나 double을 거치지 않고 원문 BigDecimal을 엔티티 정규화까지 전달해요.

## 6. 실행하지 않은 영역과 남은 이슈

- **실행하지 않은 입력:** `1E-999999999` 같은 더 큰 지수는 실행하지 않았어요. 수정 후 경로는 코드상 같은 빠른 분기를 타지만, 측정하지는 않았어요.
- **HTTP 경로:**
  - 실제 HTTP 요청 처리 시간, Spring Boot ObjectMapper 설정, Security와 CSRF는 검증하지 않았어요.
  - 이번 수정 뒤에 `JobLocationApiTest`(standalone MockMvc)는 다시 실행하지 않았어요. 이 테스트는 서비스를 mock으로 두기 때문에 정규화를 실행하지 않아요.
- **DB:** 실제 DB 저장, DECIMAL(9,6) 정밀도, V7 CHECK 제약은 미검증이에요.
- **다시 실행하지 않은 테스트:** 수정 전 보고서의 나머지 신규 클래스 5개와 기존 영향 테스트 331개는 이번에 다시 실행하지 않았어요. 그래서 "수정 후 전체 통과"라고 표현하지 않아요.
- **문서 문구:** `docs/job-location-api.md` 41행에 "수정 후 재검증이 필요하다"는 문구가 있어요. 이 보고서로 재검증했지만, 문서 갱신은 작성자에게 맡겼어요.
- **남은 이슈:** 숫자 길이와 소수 자릿수에 대한 별도 상한은 여전히 없어요(이번 수정의 의도된 범위예요). JSON 경로에서는 Jackson의 1000자 제한이 사실상 상한 역할을 해요.

## 7. PR 본문용 짧은 검증 요약

```
D-1 수정 독립 재검증 (WorkLocationValidator 극소값 빠른 경로)
- 선택 실행 3개 클래스 180개 전부 통과
  (WorkLocationTinyExponentTest 49 신규 / WorkLocationValidatorTest 65 / JobLocationRequestTest 66)
- 1E-10000000·-1E-10000000·0E-10000000 → 위도·경도 모두 0.000000(scale 6), 원본 불변
  단위 테스트 기준 호출 시간 수 ms (수정 전 setScale 약 8.3초)
- HALF_UP 경계(±0.000000499999999999 → 0.000000, ±0.0000005·±0.000000500000000001 → ±0.000001) 위도·경도 동일
  20개 값에서 기존 setScale(6, HALF_UP) 결과와 일치
- 실제 역직렬화기 → Bean Validation → JobPosting 정규화 경로에서 문자열·double 경유 없는 원본 유지 확인
- WorkLocationValidator 라인 10/10, 분기 8/8 (이번 선택 실행 기준)
- 미검증: 실제 HTTP 처리 시간, DB 저장, 전체·통합 테스트 재실행
- 운영 코드 변경 없음, 커밋 없음
```
