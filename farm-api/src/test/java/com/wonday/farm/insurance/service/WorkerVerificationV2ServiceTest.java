package com.wonday.farm.insurance.service;

import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.DOMESTIC_NUMBER_NORMALIZED;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.FOREIGN_NUMBER;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.FOREIGN_NUMBER_NORMALIZED;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.NAME;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.PHONE;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.PHONE_NORMALIZED;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.ZERO_NUMBER;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.ZERO_NUMBER_NORMALIZED;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.domestic;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.foreign;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.match;
import static com.wonday.farm.insurance.testsupport.WorkerVerificationV2TestData.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import com.wonday.farm.insurance.dto.VerifiedWorkerV2Response;
import com.wonday.farm.insurance.dto.WorkerVerificationV2Request;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityCryptoUnavailableException;
import com.wonday.farm.member.identity.NationalityType;
import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

// WorkerVerificationRepository는 mock이다. 실제 SQL(regexp_replace·binary 비교·정렬·limit 2·projection) 성공은 입증하지 않는다.
// IdentityCrypto는 테스트 전용 가상 키(IdentityTestKeys)의 실제 객체를 spy로 감싸 HMAC 계산 여부와 값을 본다.
@DisplayName("보험 V2 근로자 확인 서비스 (Repository mock)")
class WorkerVerificationV2ServiceTest {
	private WorkerVerificationRepository workers;
	private IdentityCrypto crypto;
	private WorkerVerificationV2Service service;

	@BeforeEach
	void setUp() {
		workers = mock(WorkerVerificationRepository.class);
		crypto = spy(IdentityTestKeys.crypto());
		service = new WorkerVerificationV2Service(workers, crypto);
	}

	private String hmac(String normalized) {
		return IdentityTestKeys.crypto().blindIndex(normalized);
	}

	private void returns(WorkerVerificationRepository.V2Match... rows) {
		given(workers.findByNationalityAndIdentityHmac(anyString(), anyString(), anyString(), anyString(), anyString()))
			.willReturn(List.of(rows));
	}

	private static InsuranceApiException failure(Runnable call) {
		return catchThrowableOfType(InsuranceApiException.class, call::run);
	}

	private static void assertInvalid(InsuranceApiException exception) {
		assertThat(exception).isNotNull();
		assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(exception.code()).isEqualTo("INVALID_REQUEST");
	}

	@Nested
	@DisplayName("정상 확인과 조회 인자")
	class Success {
		@Test
		@DisplayName("FOREIGN: 정규화 이름·전화, FOREIGN·FOREIGN_REGISTRATION_NUMBER, 정규화 번호 HMAC으로 조회하고 저장 정보로 응답")
		void foreignSuccess() {
			returns(match(101L, NAME, PHONE_NORMALIZED, "FOREIGN"));

			VerifiedWorkerV2Response response = service.verify(foreign());

			assertThat(response).isEqualTo(new VerifiedWorkerV2Response(101L, NAME, PHONE_NORMALIZED, NationalityType.FOREIGN));
			verify(workers).findByNationalityAndIdentityHmac(NAME, PHONE_NORMALIZED, "FOREIGN", "FOREIGN_REGISTRATION_NUMBER",
				hmac(FOREIGN_NUMBER_NORMALIZED));
			verifyNoMoreInteractions(workers);
		}

		@Test
		@DisplayName("DOMESTIC: DOMESTIC·RESIDENT_REGISTRATION_NUMBER와 HMAC으로 조회")
		void domesticSuccess() {
			returns(match(7L, NAME, PHONE_NORMALIZED, "DOMESTIC"));

			VerifiedWorkerV2Response response = service.verify(domestic());

			assertThat(response.nationalityType()).isEqualTo(NationalityType.DOMESTIC);
			assertThat(response.workerId()).isEqualTo(7L);
			verify(workers).findByNationalityAndIdentityHmac(NAME, PHONE_NORMALIZED, "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER",
				hmac(DOMESTIC_NUMBER_NORMALIZED));
			verifyNoMoreInteractions(workers);
		}

		@Test
		@DisplayName("같은 번호라도 국적에 따라 번호 종류만 바뀌고 HMAC은 같다 (종류는 서버가 결정)")
		void typeFromNationality() {
			returns();
			failure(() -> service.verify(request(NAME, PHONE, "DOMESTIC", FOREIGN_NUMBER)));
			failure(() -> service.verify(request(NAME, PHONE, "FOREIGN", FOREIGN_NUMBER)));

			String expected = hmac(FOREIGN_NUMBER_NORMALIZED);
			verify(workers).findByNationalityAndIdentityHmac(NAME, PHONE_NORMALIZED, "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER", expected);
			verify(workers).findByNationalityAndIdentityHmac(NAME, PHONE_NORMALIZED, "FOREIGN", "FOREIGN_REGISTRATION_NUMBER", expected);
		}

		@Test
		@DisplayName("응답은 요청값이 아니라 조회된 행의 이름·전화를 사용한다")
		void responseFromStoredRow() {
			returns(match(101L, "STORED NAME", "0101112222", "FOREIGN"));

			var response = service.verify(foreign());
			assertThat(response.name()).isEqualTo("STORED NAME");
			assertThat(response.phone()).isEqualTo("0101112222");
		}

		@Test
		@DisplayName("이름 앞뒤 공백(유니코드 포함)만 제거하고 대소문자·내부 공백은 보존")
		void nameNormalization() {
			returns();
			failure(() -> service.verify(request(" 　 Test Worker\t ", PHONE, "FOREIGN", FOREIGN_NUMBER)));
			verify(workers).findByNationalityAndIdentityHmac(org.mockito.ArgumentMatchers.eq("Test Worker"), anyString(), anyString(),
				anyString(), anyString());
		}

		@Test
		@DisplayName("전화·번호의 공백·하이픈(유니코드 공백 포함) 제거, 앞자리 0 보존")
		void phoneAndNumberNormalization() {
			returns();
			failure(() -> service.verify(request(NAME, " 010 - 0000　-0000 ", "DOMESTIC", " 000101 - 0000000 ")));
			verify(workers).findByNationalityAndIdentityHmac(NAME, "01000000000", "DOMESTIC", "RESIDENT_REGISTRATION_NUMBER",
				hmac(ZERO_NUMBER_NORMALIZED));
			verify(crypto).blindIndex(ZERO_NUMBER_NORMALIZED);
		}

		@Test
		@DisplayName("전화 10자리·11자리 모두 허용")
		void phoneLengths() {
			returns(match(1L, NAME, "0101234567", "FOREIGN"));
			assertThat(service.verify(request(NAME, "010-123-4567", "FOREIGN", FOREIGN_NUMBER)).workerId()).isEqualTo(1L);
			assertThat(service.verify(request(NAME, "010-1234-5678", "FOREIGN", FOREIGN_NUMBER)).workerId()).isEqualTo(1L);
		}

		@Test
		@DisplayName("원문 식별번호(하이픈 포함·정규화 모두)는 조회 인자로 전달되지 않는다")
		void rawNumberNeverPassed() {
			returns();
			failure(() -> service.verify(request(NAME, PHONE, "FOREIGN", ZERO_NUMBER)));

			var captor = org.mockito.ArgumentCaptor.forClass(String.class);
			verify(workers).findByNationalityAndIdentityHmac(captor.capture(), captor.capture(), captor.capture(), captor.capture(),
				captor.capture());
			assertThat(captor.getAllValues()).doesNotContain(ZERO_NUMBER, ZERO_NUMBER_NORMALIZED)
				.allSatisfy(value -> assertThat(value).doesNotContain("0001010000000"));
			assertThat(captor.getAllValues().get(4)).matches("[0-9a-f]{64}");
		}

		@Test
		@DisplayName("V1 조회 메서드(findMatches·findDomesticByIdentityHmac)는 성공·실패 어느 경우에도 호출하지 않는다 (fallback 없음)")
		void noV1() {
			returns();
			failure(() -> service.verify(foreign()));
			returns(match(101L, NAME, PHONE_NORMALIZED, "FOREIGN"));
			service.verify(foreign());
			willThrow(new IdentityCryptoUnavailableException()).given(crypto).blindIndex(anyString());
			failure(() -> service.verify(foreign()));

			verify(workers, never()).findMatches(any(), any(), any());
			verify(workers, never()).findDomesticByIdentityHmac(any(), any(), any());
		}

		@Test
		@DisplayName("서비스는 V2 Repository·IdentityCrypto만 의존하고 V1 서비스·조회 모드 설정을 받지 않는다")
		void dependencies() {
			List<Class<?>> types = Arrays.stream(WorkerVerificationV2Service.class.getDeclaredFields()).<Class<?>>map(f -> f.getType()).toList();
			assertThat(types).containsExactlyInAnyOrder(WorkerVerificationRepository.class, IdentityCrypto.class);
			assertThat(WorkerVerificationV2Service.class.getConstructors()).hasSize(1);
			assertThat(WorkerVerificationV2Service.class.getConstructors()[0].getParameterTypes())
				.containsExactly(WorkerVerificationRepository.class, IdentityCrypto.class);
		}

		@Test
		@DisplayName("verify는 readOnly 트랜잭션으로 선언돼 있다 (선언만 확인, 실제 트랜잭션 동작은 미검증)")
		void readOnlyDeclared() throws Exception {
			Method method = WorkerVerificationV2Service.class.getMethod("verify", WorkerVerificationV2Request.class);
			assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
		}
	}

	@Nested
	@DisplayName("입력 오류 400 — HMAC 계산·조회 없음")
	class Invalid {
		static Stream<Arguments> invalidRequests() {
			String n = FOREIGN_NUMBER;
			return Stream.of(
				Arguments.of("이름 null", request(null, PHONE, "FOREIGN", n)),
				Arguments.of("이름 빈 값", request("", PHONE, "FOREIGN", n)),
				Arguments.of("이름 공백만", request("  \t ", PHONE, "FOREIGN", n)),
				Arguments.of("이름 유니코드 공백만", request("　 ", PHONE, "FOREIGN", n)),
				Arguments.of("이름 내부 제어 문자 TAB", request("TEST\tWORKER", PHONE, "FOREIGN", n)),
				Arguments.of("이름 NUL", request("TEST\u0000", PHONE, "FOREIGN", n)),
				Arguments.of("이름 DEL", request("TEST\u007F", PHONE, "FOREIGN", n)),
				Arguments.of("이름 C1 제어", request("TEST\u0085X", PHONE, "FOREIGN", n)),
				Arguments.of("이름 101자", request("A".repeat(101), PHONE, "FOREIGN", n)),
				Arguments.of("전화 null", request(NAME, null, "FOREIGN", n)),
				Arguments.of("전화 9자리", request(NAME, "010-123-456", "FOREIGN", n)),
				Arguments.of("전화 12자리", request(NAME, "010-1234-56789", "FOREIGN", n)),
				Arguments.of("전화 문자 포함", request(NAME, "010-1234-567a", "FOREIGN", n)),
				Arguments.of("전화 +82", request(NAME, "+82-10-1234-5678", "FOREIGN", n)),
				Arguments.of("전화 괄호", request(NAME, "(010)1234-5678", "FOREIGN", n)),
				Arguments.of("전화 전각 숫자", request(NAME, "０１０１２３４５６７８", "FOREIGN", n)),
				Arguments.of("전화 아라비아-인도 숫자", request(NAME, "٠١٠١٢٣٤٥٦٧٨", "FOREIGN", n)),
				Arguments.of("전화 공백만", request(NAME, "　", "FOREIGN", n)),
				Arguments.of("전화 101자", request(NAME, " ".repeat(90) + "01012345678", "FOREIGN", n)),
				Arguments.of("국적 null", request(NAME, PHONE, null, n)),
				Arguments.of("국적 소문자", request(NAME, PHONE, "foreign", n)),
				Arguments.of("국적 앞뒤 공백", request(NAME, PHONE, " FOREIGN", n)),
				Arguments.of("국적 기타", request(NAME, PHONE, "KOREAN", n)),
				Arguments.of("국적 빈 값", request(NAME, PHONE, "", n)),
				Arguments.of("국적 17자", request(NAME, PHONE, "FOREIGNFOREIGNFOR", n)),
				Arguments.of("번호 null", request(NAME, PHONE, "FOREIGN", null)),
				Arguments.of("번호 12자리", request(NAME, PHONE, "FOREIGN", "900101-500000")),
				Arguments.of("번호 14자리", request(NAME, PHONE, "FOREIGN", "900101-50000000")),
				Arguments.of("번호 문자 포함", request(NAME, PHONE, "FOREIGN", "900101-500000X")),
				Arguments.of("번호 전각 숫자", request(NAME, PHONE, "FOREIGN", "９００１０１５０００００００")),
				Arguments.of("번호 다른 구분자 en dash", request(NAME, PHONE, "FOREIGN", "900101–5000000")),
				Arguments.of("번호 점 구분", request(NAME, PHONE, "FOREIGN", "900101.5000000")),
				Arguments.of("번호 101자", request(NAME, PHONE, "FOREIGN", " ".repeat(88) + "9001015000000")),
				Arguments.of("번호 빈 값", request(NAME, PHONE, "FOREIGN", "")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidRequests")
		@DisplayName("잘못된 입력은 400이고 HMAC 계산·조회가 실행되지 않는다")
		void rejected(String label, WorkerVerificationV2Request request) {
			assertInvalid(failure(() -> service.verify(request)));
			verify(crypto, never()).blindIndex(any());
			verifyNoInteractions(workers);
		}

		@Test
		@DisplayName("요청 자체가 null이면 400")
		void nullRequest() {
			assertInvalid(failure(() -> service.verify(null)));
			verifyNoInteractions(workers);
		}

		@ParameterizedTest(name = "[{0}]")
		@ValueSource(strings = {"A", "가", "TEST WORKER", "Ünïcödé 名前"})
		@DisplayName("100자 이내 정상 이름(유니코드 포함)은 조회까지 간다")
		void validNames(String name) {
			returns();
			failure(() -> service.verify(request(name, PHONE, "FOREIGN", FOREIGN_NUMBER)));
			verify(workers).findByNationalityAndIdentityHmac(org.mockito.ArgumentMatchers.eq(name), anyString(), anyString(), anyString(),
				anyString());
		}

		@Test
		@DisplayName("원문 100자 경계: 100자 이름·전화·번호 원문은 허용 범위 안에서 처리된다")
		void boundaries() {
			returns();
			String name = "A".repeat(100);
			String phone = " ".repeat(89) + "01012345678";
			String number = " ".repeat(87) + "9001015000000";
			assertThat(phone).hasSize(100);
			assertThat(number).hasSize(100);

			var exception = failure(() -> service.verify(request(name, phone, "FOREIGN", number)));
			assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
		}
	}

	@Nested
	@DisplayName("업무 결과·오류 코드")
	class Outcomes {
		@Test
		@DisplayName("조회 0건: 404 WORKER_IDENTITY_NOT_MATCHED, 메시지에 입력값 없음")
		void none() {
			returns();
			var exception = failure(() -> service.verify(foreign()));
			assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(exception.code()).isEqualTo("WORKER_IDENTITY_NOT_MATCHED");
			assertThat(exception.getMessage()).doesNotContain(NAME, PHONE_NORMALIZED, FOREIGN_NUMBER_NORMALIZED);
		}

		@Test
		@DisplayName("조회 2건: 409 WORKER_IDENTITY_AMBIGUOUS, 어떤 workerId도 노출하지 않음")
		void two() {
			returns(match(101L, NAME, PHONE_NORMALIZED, "FOREIGN"), match(102L, NAME, PHONE_NORMALIZED, "FOREIGN"));
			var exception = failure(() -> service.verify(foreign()));
			assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
			assertThat(exception.code()).isEqualTo("WORKER_IDENTITY_AMBIGUOUS");
			assertThat(exception.getMessage()).doesNotContain("101", "102");
		}

		@Test
		@DisplayName("암호화 설정 예외(키 미설정): 503, 조회 실행 안 함")
		void cryptoUnconfigured() {
			var unconfigured = spy(new IdentityCrypto("", ""));
			var unconfiguredService = new WorkerVerificationV2Service(workers, unconfigured);

			var exception = failure(() -> unconfiguredService.verify(foreign()));
			assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(exception.code()).isEqualTo("SERVICE_UNAVAILABLE");
			verifyNoInteractions(workers);
		}

		@Test
		@DisplayName("암호화 키가 같은 값이어도(설정 불가) 503, 조회 없음")
		void sameKeys() {
			var invalid = new IdentityCrypto(IdentityTestKeys.ENCRYPTION_KEY, IdentityTestKeys.ENCRYPTION_KEY);
			var exception = failure(() -> new WorkerVerificationV2Service(workers, invalid).verify(foreign()));
			assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			verifyNoInteractions(workers);
		}

		static Stream<Arguments> unusableRows() {
			return Stream.of(
				Arguments.of("국적 null", match(101L, NAME, PHONE_NORMALIZED, null)),
				Arguments.of("국적 해석 불가", match(101L, NAME, PHONE_NORMALIZED, "KOREAN")),
				Arguments.of("국적 소문자", match(101L, NAME, PHONE_NORMALIZED, "foreign")),
				Arguments.of("요청과 다른 국적", match(101L, NAME, PHONE_NORMALIZED, "DOMESTIC")),
				Arguments.of("workerId 0", match(0L, NAME, PHONE_NORMALIZED, "FOREIGN")),
				Arguments.of("workerId 음수", match(-1L, NAME, PHONE_NORMALIZED, "FOREIGN")));
		}

		@ParameterizedTest(name = "{0}")
		@MethodSource("unusableRows")
		@DisplayName("해석할 수 없는 조회 행은 503 SERVICE_UNAVAILABLE (성공으로 처리하지 않음)")
		void unusable(String label, WorkerVerificationRepository.V2Match row) {
			returns(row);
			var exception = failure(() -> service.verify(foreign()));
			assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(exception.code()).isEqualTo("SERVICE_UNAVAILABLE");
		}

		@Test
		@DisplayName("Repository 예외는 그대로 전파된다 (HTTP 변환은 오류 처리기 몫)")
		void repositoryException() {
			var failure = new org.springframework.dao.DataAccessResourceFailureException("db down");
			given(workers.findByNationalityAndIdentityHmac(anyString(), anyString(), anyString(), anyString(), anyString()))
				.willThrow(failure);
			assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.verify(foreign()))).isSameAs(failure);
		}
	}
}
