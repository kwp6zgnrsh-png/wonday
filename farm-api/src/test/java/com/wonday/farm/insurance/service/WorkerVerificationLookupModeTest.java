package com.wonday.farm.insurance.service;

import static com.wonday.farm.member.identity.testsupport.IdentityTestKeys.NUMBER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Arrays;
import java.util.List;

import com.wonday.farm.insurance.dto.VerifiedWorkerResponse;
import com.wonday.farm.insurance.dto.WorkerVerificationRequest;
import com.wonday.farm.insurance.exception.InsuranceApiException;
import com.wonday.farm.insurance.repository.WorkerVerificationRepository;
import com.wonday.farm.member.identity.IdentityCrypto;
import com.wonday.farm.member.identity.IdentityLookupMode;
import com.wonday.farm.member.identity.testsupport.IdentityTestKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.data.jpa.repository.Query;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

// 기존 V1 근로자 확인 서비스의 LEGACY/ENCRYPTED 분기. Repository는 mock, IdentityCrypto는 테스트 키의 실제 객체.
// 쿼리의 WORKER·DOMESTIC·HMAC 조건은 @Query 선언 문자열과 대조만 한다. 실제 MySQL 필터·정규화·LIMIT 동작은 미검증.
@DisplayName("근로자 확인 서비스의 조회 모드 (WorkerVerificationService)")
class WorkerVerificationLookupModeTest {
	private static final String NAME = "근로자";
	private static final String PHONE = "01000000000";

	private final WorkerVerificationRepository repository = mock(WorkerVerificationRepository.class);
	private final IdentityCrypto crypto = IdentityTestKeys.crypto();

	@Nested
	@DisplayName("LEGACY (기본)")
	class Legacy {
		@Test
		@DisplayName("정규화한 이름·전화·번호로 findMatches만 호출하고 HMAC 조회·암호화 객체는 쓰지 않는다")
		void usesLegacyQuery() {
			IdentityCrypto unusedCrypto = mock(IdentityCrypto.class);
			var service = new WorkerVerificationService(repository, unusedCrypto, IdentityLookupMode.LEGACY);
			given(repository.findMatches(NAME, PHONE, NUMBER)).willReturn(List.of(match(5L)));

			VerifiedWorkerResponse response = service.verify(new WorkerVerificationRequest(" 근로자 ", "010-0000-0000", "111111-1111111"));

			assertThat(response.workerId()).isEqualTo(5L);
			verify(repository).findMatches(NAME, PHONE, NUMBER);
			verifyNoMoreInteractions(repository);
			verifyNoInteractions(unusedCrypto);
		}

		@Test
		@DisplayName("키가 없어도 LEGACY 조회는 동작한다")
		void worksWithoutKeys() {
			var service = new WorkerVerificationService(repository, new IdentityCrypto("", ""), IdentityLookupMode.LEGACY);
			given(repository.findMatches(NAME, PHONE, NUMBER)).willReturn(List.of(match(5L)));

			assertThat(service.verify(request()).workerId()).isEqualTo(5L);
		}
	}

	@Nested
	@DisplayName("ENCRYPTED")
	class Encrypted {
		private final WorkerVerificationService service =
			new WorkerVerificationService(repository, crypto, IdentityLookupMode.ENCRYPTED);

		@Test
		@DisplayName("정규화 번호의 HMAC으로 findDomesticByIdentityHmac만 호출한다 (원문·하이픈 포함 번호를 넘기지 않음)")
		void usesHmacQuery() {
			String hmac = crypto.blindIndex(NUMBER);
			given(repository.findDomesticByIdentityHmac(NAME, PHONE, hmac)).willReturn(List.of(match(5L)));

			VerifiedWorkerResponse response = service.verify(new WorkerVerificationRequest("근로자", "010 0000 0000", " 111111-1111111 "));

			assertThat(response).isEqualTo(new VerifiedWorkerResponse(5L, NAME, PHONE));
			verify(repository).findDomesticByIdentityHmac(NAME, PHONE, hmac);
			verify(repository, never()).findMatches(anyString(), anyString(), anyString());
			verifyNoMoreInteractions(repository);
		}

		@Test
		@DisplayName("결과 0건 404 WORKER_IDENTITY_NOT_MATCHED, 2건 409 WORKER_IDENTITY_AMBIGUOUS")
		void zeroAndMany() {
			given(repository.findDomesticByIdentityHmac(anyString(), anyString(), anyString())).willReturn(List.of());
			assertError(service, HttpStatus.NOT_FOUND, "WORKER_IDENTITY_NOT_MATCHED");

			given(repository.findDomesticByIdentityHmac(anyString(), anyString(), anyString())).willReturn(List.of(match(5L), match(6L)));
			assertError(service, HttpStatus.CONFLICT, "WORKER_IDENTITY_AMBIGUOUS");
		}

		@Test
		@DisplayName("암호화 설정 사용 불가면 503 SERVICE_UNAVAILABLE이고 LEGACY 조회로 자동 후퇴하지 않는다 (Repository 호출 없음)")
		void noFallback() {
			for (IdentityCrypto broken : List.of(new IdentityCrypto("", ""),
					new IdentityCrypto(IdentityTestKeys.ENCRYPTION_KEY, IdentityTestKeys.ENCRYPTION_KEY),
					new IdentityCrypto(IdentityTestKeys.ENCRYPTION_KEY, ""))) {
				var unavailable = new WorkerVerificationService(repository, broken, IdentityLookupMode.ENCRYPTED);
				assertError(unavailable, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE");
			}
			verifyNoInteractions(repository);
		}

		@Test
		@DisplayName("입력 형식 오류는 기존대로 400 INVALID_REQUEST이며 HMAC 계산·조회를 하지 않는다")
		void invalidInput() {
			IdentityCrypto spy = mock(IdentityCrypto.class);
			var guarded = new WorkerVerificationService(repository, spy, IdentityLookupMode.ENCRYPTED);

			assertThatThrownBy(() -> guarded.verify(new WorkerVerificationRequest(NAME, PHONE, "11111-1111111")))
				.isInstanceOfSatisfying(InsuranceApiException.class, error -> assertThat(error.code()).isEqualTo("INVALID_REQUEST"));
			verifyNoInteractions(spy, repository);
		}
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(IdentityLookupMode.class)
	@DisplayName("두 모드 모두 성공 응답은 workerId·name·phone만 담고 번호·암호문·HMAC·항목별 일치 여부가 없다")
	void responseShape(IdentityLookupMode mode) {
		var service = new WorkerVerificationService(repository, crypto, mode);
		given(repository.findMatches(anyString(), anyString(), anyString())).willReturn(List.of(match(5L)));
		given(repository.findDomesticByIdentityHmac(anyString(), anyString(), anyString())).willReturn(List.of(match(5L)));

		VerifiedWorkerResponse response = service.verify(request());
		String json = JsonMapper.builder().build().writeValueAsString(response);

		assertThat(Arrays.stream(VerifiedWorkerResponse.class.getRecordComponents()).map(c -> c.getName()))
			.containsExactly("workerId", "name", "phone");
		assertThat(json).isEqualTo("{\"workerId\":5,\"name\":\"근로자\",\"phone\":\"01000000000\"}");
		assertThat(json).doesNotContain(NUMBER, crypto.blindIndex(NUMBER));
	}

	@Nested
	@DisplayName("쿼리 선언 대조 (정적, MySQL 동작 미검증)")
	class QueryDeclarations {
		@Test
		@DisplayName("HMAC 조회: WORKER·DOMESTIC·RESIDENT_REGISTRATION_NUMBER·암호문 존재·HMAC 일치·limit 2, 원문 컬럼 미사용")
		void hmacQuery() throws Exception {
			String sql = query("findDomesticByIdentityHmac");

			assertThat(sql).contains("cast(m.role as binary) = cast('WORKER' as binary)",
				"cast(m.nationality_type as binary) = cast('DOMESTIC' as binary)",
				"cast(m.identity_type as binary) = cast('RESIDENT_REGISTRATION_NUMBER' as binary)",
				"m.identity_ciphertext is not null", "m.identity_hmac = :identityHmac", "limit 2")
				.doesNotContain("resident_registration_number")
				.doesNotContain("nationality_type is null");
		}

		@Test
		@DisplayName("LEGACY 조회: WORKER, 국적·종류가 NULL이거나 내국인일 때만, 기존 번호 컬럼으로 대조")
		void legacyQuery() throws Exception {
			String sql = query("findMatches");

			assertThat(sql).contains("cast(m.role as binary) = cast('WORKER' as binary)",
				"(m.nationality_type is null or cast(m.nationality_type as binary) = cast('DOMESTIC' as binary))",
				"(m.identity_type is null or cast(m.identity_type as binary) = cast('RESIDENT_REGISTRATION_NUMBER' as binary))",
				"regexp_replace(m.resident_registration_number, '[-[:space:]]', '') = :residentNumber", "limit 2")
				.doesNotContain("identity_hmac");
		}

		private String query(String method) throws Exception {
			return Arrays.stream(WorkerVerificationRepository.class.getDeclaredMethods())
				.filter(candidate -> candidate.getName().equals(method)).findFirst().orElseThrow()
				.getAnnotation(Query.class).value();
		}
	}

	private static WorkerVerificationRequest request() {
		return new WorkerVerificationRequest(NAME, PHONE, NUMBER);
	}

	private static void assertError(WorkerVerificationService service, HttpStatus status, String code) {
		assertThatThrownBy(() -> service.verify(request())).isInstanceOfSatisfying(InsuranceApiException.class, error -> {
			assertThat(error.status()).isEqualTo(status);
			assertThat(error.code()).isEqualTo(code);
			assertThat(error.getMessage()).doesNotContain(NUMBER);
		});
	}

	private static WorkerVerificationRepository.Match match(long id) {
		return new WorkerVerificationRepository.Match() {
			@Override public long getWorkerId() { return id; }
			@Override public String getName() { return NAME; }
			@Override public String getPhone() { return PHONE; }
		};
	}
}
