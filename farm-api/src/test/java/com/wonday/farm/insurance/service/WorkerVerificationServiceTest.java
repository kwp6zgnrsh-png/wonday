package com.wonday.farm.insurance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;

import com.wonday.farm.insurance.dto.WorkerVerificationRequest;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityLookupMode;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

// 본인 확인 서비스의 입력 재검증과 조회 결과 해석을 검증한다. Repository는 mock이므로
// 실제 MySQL의 정규화(regexp_replace)·WORKER 필터·대소문자 구분·LIMIT 2는 여기서 증명하지 않는다 (통합 검증 대상).
class WorkerVerificationServiceTest {
	private static final String RRN = "0000000000000";

	private final WorkerVerificationRepository repository = mock(WorkerVerificationRepository.class);
	private final WorkerVerificationService service = new WorkerVerificationService(repository, mock(IdentityCrypto.class), IdentityLookupMode.LEGACY);

	@Nested
	class InputValidation {
		@ParameterizedTest(name = "phone={0}")
		@ValueSource(strings = {"", "010000000", "010000000000", "010-0000-000a", "０１０００００００００", "+821000000000",
			"(010)00000000"})
		void rejectsPhoneThatIsNotTenOrElevenAsciiDigits(String phone) {
			assertInvalid(new WorkerVerificationRequest("데모", phone, RRN));
		}

		@ParameterizedTest(name = "rrn={0}")
		@ValueSource(strings = {"", "000000000000", "00000000000000", "000000-000000a", "０００００００００００００",
			"000000_0000000"})
		void rejectsResidentNumberThatIsNotThirteenAsciiDigits(String residentNumber) {
			assertInvalid(new WorkerVerificationRequest("데모", "01000000000", residentNumber));
		}

		@ParameterizedTest(name = "name={0}")
		@ValueSource(strings = {"", "   ", "데모\u0000", "데\n모", "데모\u007f"})
		void rejectsBlankNameOrNameWithControlCharacters(String name) {
			assertInvalid(new WorkerVerificationRequest(name, "01000000000", RRN));
		}

		@Test
		void rejectsNameLongerThan100Characters() {
			assertInvalid(new WorkerVerificationRequest("가".repeat(101), "01000000000", RRN));
		}

		@Test
		void rejectsNullFieldsEvenWhenBeanValidationWasBypassed() {
			assertInvalid(new WorkerVerificationRequest(null, "01000000000", RRN));
			assertInvalid(new WorkerVerificationRequest("데모", null, RRN));
			assertInvalid(new WorkerVerificationRequest("데모", "01000000000", null));
		}

		@Test
		void acceptsBoundaryLengths() {
			given(repository.findMatches(anyString(), anyString(), anyString())).willReturn(List.of(match(1L)));

			service.verify(new WorkerVerificationRequest("가".repeat(100), "0100000000", RRN));
			service.verify(new WorkerVerificationRequest("가", "01000000000", RRN));
		}

		private void assertInvalid(WorkerVerificationRequest request) {
			assertThatThrownBy(() -> service.verify(request)).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
				assertThat(error.status()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(error.code()).isEqualTo("INVALID_REQUEST");
			});
			verifyNoInteractions(repository);
		}
	}

	@Nested
	class Lookup {
		@Test
		void queriesWithNormalizedValues() {
			given(repository.findMatches(anyString(), anyString(), anyString())).willReturn(List.of(match(7L)));

			service.verify(new WorkerVerificationRequest("  데모 근로자 ", "010-0000 0000", "000000-0000000"));

			verify(repository).findMatches("데모 근로자", "01000000000", "0000000000000");
		}

		@Test
		void noMatchIs404WithoutRevealingWhichFieldDiffered() {
			given(repository.findMatches(anyString(), anyString(), anyString())).willReturn(List.of());

			assertThatThrownBy(() -> service.verify(valid())).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
				assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
				assertThat(error.code()).isEqualTo("WORKER_IDENTITY_NOT_MATCHED");
				assertThat(error.getMessage()).doesNotContain("전화").doesNotContain("주민").doesNotContain("이름");
			});
		}

		@Test
		void singleMatchReturnsWorkerIdNameAndPhoneFromRepository() {
			given(repository.findMatches(anyString(), anyString(), anyString())).willReturn(List.of(match(7L)));

			var response = service.verify(valid());

			assertThat(response.workerId()).isEqualTo(7L);
			assertThat(response.name()).isEqualTo("저장된 이름 7");
			assertThat(response.phone()).isEqualTo("0100000007");
		}

		@Test
		void twoMatchesAre409() {
			given(repository.findMatches(anyString(), anyString(), anyString())).willReturn(List.of(match(7L), match(8L)));

			assertThatThrownBy(() -> service.verify(valid())).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
				assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
				assertThat(error.code()).isEqualTo("WORKER_IDENTITY_AMBIGUOUS");
			});
		}

		@Test
		void errorMessagesNeverContainTheSubmittedValues() {
			given(repository.findMatches(anyString(), anyString(), anyString())).willReturn(List.of());

			assertThatThrownBy(() -> service.verify(new WorkerVerificationRequest("홍길동", "01012345678", "9001011234567")))
				.satisfies(error -> assertThat(error.getMessage())
					.doesNotContain("홍길동").doesNotContain("01012345678").doesNotContain("9001011234567"));
		}

		@Test
		void isReadOnlyTransaction() throws Exception {
			var verify = WorkerVerificationService.class.getMethod("verify", WorkerVerificationRequest.class);

			assertThat(verify.getAnnotation(Transactional.class).readOnly()).isTrue();
		}
	}

	private static WorkerVerificationRequest valid() {
		return new WorkerVerificationRequest("데모", "01000000000", RRN);
	}

	private static WorkerVerificationRepository.Match match(long id) {
		return new WorkerVerificationRepository.Match() {
			public long getWorkerId() { return id; }
			public String getName() { return "저장된 이름 " + id; }
			public String getPhone() { return "010000000" + id; }
		};
	}
}
