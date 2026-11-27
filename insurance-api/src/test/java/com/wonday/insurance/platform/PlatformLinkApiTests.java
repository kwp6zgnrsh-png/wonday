package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.wonday.insurance.member.IdentityCipher;
import com.wonday.insurance.member.IdentityType;
import com.wonday.insurance.member.NationalityType;
import com.wonday.insurance.platform.FarmPlatformClient.VerifiedWorker;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"insurance.farm.worker-verification-v2-enabled=true", "insurance.farm.salary-account-query-enabled=true"})
@AutoConfigureMockMvc
class PlatformLinkApiTests {

	private static final String ROOT = "/api/platform/link";
	private static final String VERSION = "farm-platform-v1";
	private static final Instant NOW = Instant.parse("2026-11-27T09:00:00.123456789Z");
	private static final LocalDateTime OLD = LocalDateTime.of(2026, 11, 20, 0, 0);
	@Autowired MockMvc mvc;
	@Autowired EntityManager entities;
	@Autowired PlatformTransactionManager transactions;
	@Autowired PlatformLinkRepository links;
	@Autowired PlatformAccountService accounts;
	@Autowired JdbcTemplate jdbc;
	@Autowired IdentityCipher cipher;
	@MockitoBean FarmPlatformClient farm;
	@MockitoBean FarmSalaryAccountClient salary;
	@MockitoBean Clock clock;

	@BeforeEach
	void prepare() {
		tx(() -> {
			entities.createQuery("delete from SalaryRewardCheck").executeUpdate();
			links.deleteAllInBatch();
			entities.createQuery("delete from Member").executeUpdate();
			return null;
		});
		when(clock.instant()).thenReturn(NOW);
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			WorkerIdentity identity = invocation.getArgument(0);
			return verified(123, identity);
		}).when(farm).verify(any());
		when(salary.find(anyLong())).thenReturn(new SalaryAccountStatus(true, "020"));
	}

	@Test
	void missingConnectionReturnsOnlyOwnUnlinkedStatusWithoutRemoteLookup() throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		Account other = account(NationalityType.DOMESTIC);
		savedLink(other, 456, VERSION);
		mvc.perform(get(ROOT).session(own.session()).param("memberId", String.valueOf(other.id())))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.linked").value(false)).andExpect(jsonPath("$.consentRequired").value(true))
			.andExpect(jsonPath("$.requiredConsentVersion").value(VERSION))
			.andExpect(jsonPath("$.consentVersion").isEmpty()).andExpect(jsonPath("$.consentedAt").isEmpty());
		verifyNoInteractions(farm, salary);
	}

	@ParameterizedTest
	@EnumSource(NationalityType.class)
	void registrationStoresVerifiedIdentityAndConsentWithoutCreatingAContract(NationalityType nationality) throws Exception {
		Account own = account(nationality);
		String response = mvc.perform(register(own, payload(nationality))).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.linked").value(true)).andExpect(jsonPath("$.consentRequired").value(false))
			.andExpect(jsonPath("$.requiredConsentVersion").value(VERSION)).andExpect(jsonPath("$.consentVersion").value(VERSION))
			.andExpect(jsonPath("$.consentedAt").value("2026-11-27T09:00:00.123456Z"))
			.andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain("workerId", "memberId", "identityNumber", "phone", "ciphertext", "0000000000000", "9001015000000");
		var member = jdbc.queryForMap("select * from member where id = ?", own.id());
		String encrypted = (String) member.get("identity_number_ciphertext");
		assertThat(encrypted).startsWith("v1:");
		assertThat(cipher.decrypt(own.id(), IdentityType.forNationality(nationality), encrypted))
			.isEqualTo(nationality == NationalityType.DOMESTIC ? "0000000000000" : "9001015000000");
		assertThat(member.get("phone")).isEqualTo("01000000000");
		assertThat(member.get("resident_registration_number")).isNull();
		assertThat(jdbc.queryForObject("select platform_worker_id from platform_link where member_id = ?", Long.class, own.id())).isEqualTo(123);
		assertThat(count("insurance_contract")).isZero();
		clearInvocations(farm, salary);
		mvc.perform(get(ROOT).session(own.session())).andExpect(status().isOk()).andExpect(content().json(response));
		verifyNoInteractions(farm, salary);
	}

	@Test
	void duplicateRegistrationPreservesOriginalConsentAndConnection() throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		String first = mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString();
		long id = jdbc.queryForObject("select id from platform_link where member_id = ?", Long.class, own.id());
		when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().isOk()).andExpect(content().json(first));
		assertThat(count("platform_link")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select id from platform_link where member_id = ?", Long.class, own.id())).isEqualTo(id);
	}

	@Test
	void linkingEnablesSalaryAndRewardChecksBeforeAnyInsuranceEnrollment() throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		mvc.perform(get("/api/platform/salary-account").session(own.session())).andExpect(status().isConflict());
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().isOk());
		mvc.perform(get("/api/platform/salary-account").session(own.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(true)).andExpect(jsonPath("$.bankCode").value("020"));
		mvc.perform(post("/api/rewards/salary-account/checks").session(own.session()).with(csrf())
			.header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value("ELIGIBLE"));
		assertThat(count("insurance_contract")).isZero();
		assertThat(count("salary_reward_check")).isEqualTo(1);
	}

	@Test
	void outdatedConsentIsRenewedWithoutDeletingPreviousConsentOrRewardEvidence() throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		long original = savedLink(own, 123, "old-consent-v0");
		mvc.perform(get(ROOT).session(own.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.linked").value(true)).andExpect(jsonPath("$.consentRequired").value(true))
			.andExpect(jsonPath("$.consentVersion").value("old-consent-v0"));
		mvc.perform(post("/api/rewards/salary-account/checks").session(own.session()).with(csrf())
			.header("Idempotency-Key", UUID.randomUUID())).andExpect(status().isOk());
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().isOk())
			.andExpect(jsonPath("$.consentRequired").value(false));
		assertThat(count("platform_link")).isEqualTo(2);
		assertThat(jdbc.queryForObject("select revoked_at from platform_link where id = ?", LocalDateTime.class, original)).isNotNull();
		assertThat(jdbc.queryForObject("select consent_version from platform_link where id = ?", String.class, original)).isEqualTo("old-consent-v0");
		assertThat(jdbc.queryForObject("select platform_link_id from salary_reward_check", Long.class)).isEqualTo(original);
	}

	@ParameterizedTest
	@ValueSource(strings = {"revoked", "unverified"})
	void revokedAndUnverifiedLinksDoNotCountAsConnectedAndCanBeRegisteredAgain(String previous) throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		long old = savedLink(own, 123, VERSION);
		if (previous.equals("revoked")) jdbc.update("update platform_link set revoked_at = ? where id = ?", OLD.plusDays(1), old);
		else jdbc.update("update platform_link set platform_worker_id = null where id = ?", old);
		mvc.perform(get(ROOT).session(own.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.linked").value(false)).andExpect(jsonPath("$.consentVersion").isEmpty());
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(true));
		assertThat(count("platform_link")).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from platform_link where member_id = ? and revoked_at is null", Long.class, own.id())).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(NationalityType.class)
	void nationalityMismatchIsRejectedBeforeRemoteVerification(NationalityType nationality) throws Exception {
		Account own = account(nationality);
		NationalityType other = nationality == NationalityType.DOMESTIC ? NationalityType.FOREIGN : NationalityType.DOMESTIC;
		mvc.perform(register(own, payload(other))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NATIONALITY_TYPE_MISMATCH"));
		assertNoLocalWrites(own); verifyNoInteractions(farm);
	}

	@Test
	void legacyMemberNationalityIsEstablishedOnlyAfterSuccessfulVerification() throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		jdbc.update("update member set nationality_type = null where id = ?", own.id());
		mvc.perform(register(own, payload(NationalityType.FOREIGN))).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select nationality_type from member where id = ?", String.class, own.id())).isEqualTo("FOREIGN");
	}

	@ParameterizedTest
	@CsvSource({"role,ADMIN,403", "nationality_type,FOREIGN,409"})
	void memberAttributesAreRecheckedAfterRemoteVerification(String column, String changed, int status) throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			if (column.equals("role")) jdbc.update("update member set role = ? where id = ?", changed, own.id());
			else jdbc.update("update member set nationality_type = ? where id = ?", changed, own.id());
			return verified(123, invocation.getArgument(0));
		}).when(farm).verify(any());
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().is(status));
		assertNoLocalWrites(own);
	}

	@ParameterizedTest
	@CsvSource({"400,INVALID_REQUEST", "404,WORKER_IDENTITY_NOT_MATCHED", "409,WORKER_IDENTITY_AMBIGUOUS",
		"502,INVALID_PLATFORM_RESPONSE", "503,FARM_SERVICE_UNAVAILABLE", "503,FOREIGN_VERIFICATION_NOT_READY"})
	void upstreamFailuresDoNotChangeContactOrConsent(int status, String code) throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		doThrow(new PlatformVerificationException(HttpStatus.valueOf(status), code, "Verification failed")).when(farm).verify(any());
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().is(status))
			.andExpect(jsonPath("$.code").value(code)).andExpect(header().string("Cache-Control", "no-store"));
		assertNoLocalWrites(own);
	}

	@Test
	void retryAfterVerificationFailureCreatesOneConnection() throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		doThrow(new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
			"FARM_SERVICE_UNAVAILABLE", "Unavailable")).doAnswer(invocation -> verified(123, invocation.getArgument(0)))
			.when(farm).verify(any());
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().isServiceUnavailable());
		assertNoLocalWrites(own);
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().isOk());
		assertThat(count("platform_link")).isEqualTo(1);
	}

	@Test
	void accountConflictsRollbackContactWritesAndPreserveExistingConnection() throws Exception {
		Account owner = account(NationalityType.DOMESTIC); long old = savedLink(owner, 123, VERSION);
		Account other = account(NationalityType.DOMESTIC);
		mvc.perform(register(other, payload(NationalityType.DOMESTIC))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_CONFLICT"));
		assertNoContactWrites(other);
		doAnswer(invocation -> verified(456, invocation.getArgument(0))).when(farm).verify(any());
		mvc.perform(register(owner, payload(NationalityType.DOMESTIC))).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_CONFLICT"));
		assertNoContactWrites(owner);
		assertThat(count("platform_link")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select revoked_at from platform_link where id = ?", LocalDateTime.class, old)).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {"no-consent", "no-version", "old-version", "no-identity", "bad-number", "bad-nationality"})
	void invalidRequestsAreRejectedBeforeRemoteCalls(String invalid) throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		String body = switch (invalid) {
			case "no-consent" -> payload(NationalityType.DOMESTIC).replace("\"platformConsentAccepted\":true", "\"platformConsentAccepted\":false");
			case "no-version" -> payload(NationalityType.DOMESTIC).replace("\"consentVersion\":\"" + VERSION + "\",", "");
			case "old-version" -> payload(NationalityType.DOMESTIC).replace(VERSION, "old-consent-v0");
			case "no-identity" -> "{\"platformConsentAccepted\":true,\"consentVersion\":\"" + VERSION + "\"}";
			case "bad-number" -> payload(NationalityType.DOMESTIC).replace("0000000000000", "wrong");
			default -> payload(NationalityType.DOMESTIC).replace("DOMESTIC", "UNKNOWN");
		};
		mvc.perform(register(own, body)).andExpect(status().is(invalid.equals("old-version") ? 409 : 400));
		assertNoLocalWrites(own); verifyNoInteractions(farm);
	}

	@Test
	void authenticationCurrentRoleAndCsrfAreRequired() throws Exception {
		mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
		mvc.perform(post(ROOT).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(payload(NationalityType.DOMESTIC)))
			.andExpect(status().isUnauthorized());
		Account own = account(NationalityType.DOMESTIC);
		mvc.perform(post(ROOT).session(own.session()).contentType(MediaType.APPLICATION_JSON).content(payload(NationalityType.DOMESTIC)))
			.andExpect(status().isForbidden());
		jdbc.update("update member set role = 'ADMIN' where id = ?", own.id());
		mvc.perform(get(ROOT).session(own.session())).andExpect(status().isForbidden());
		mvc.perform(register(own, payload(NationalityType.DOMESTIC))).andExpect(status().isForbidden());
		assertNoLocalWrites(own); verifyNoInteractions(farm);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void overlappingRegistrationsKeepOneOwnerAndOneActiveConnection(boolean differentMembers) throws Exception {
		Account own = account(NationalityType.DOMESTIC);
		Account other = differentMembers ? account(NationalityType.DOMESTIC) : own;
		var request = new PlatformLinkRequest(true, VERSION, identity(NationalityType.DOMESTIC));
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			entered.countDown(); assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
			return verified(123, invocation.getArgument(0));
		}).when(farm).verify(any());
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> one = pool.submit(() -> attempt(own.id(), request));
			Future<Integer> two = pool.submit(() -> attempt(other.id(), request));
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			List<Integer> results = List.of(one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS));
			assertThat(results).containsExactlyInAnyOrder(200, differentMembers ? 409 : 200);
			assertThat(count("platform_link")).isEqualTo(1);
			assertThat(count("insurance_contract")).isZero();
			if (differentMembers) {
				long loser = results.getFirst() == 409 ? own.id() : other.id();
				assertNoContactWrites(new Account(loser, null));
			}
		} finally {
			release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private int attempt(long memberId, PlatformLinkRequest request) {
		try { accounts.register(memberId, request); return 200; }
		catch (PlatformVerificationException exception) { return exception.getStatus().value(); }
	}
	private void assertNoLocalWrites(Account account) {
		assertThat(count("platform_link")).isZero();
		assertNoContactWrites(account);
	}
	private void assertNoContactWrites(Account account) {
		var row = jdbc.queryForMap("select phone,identity_number_ciphertext from member where id = ?", account.id());
		assertThat(row.get("phone")).isNull(); assertThat(row.get("identity_number_ciphertext")).isNull();
	}
	private long count(String table) {
		return switch (table) {
			case "platform_link" -> jdbc.queryForObject("select count(*) from platform_link", Long.class);
			case "salary_reward_check" -> jdbc.queryForObject("select count(*) from salary_reward_check", Long.class);
			default -> jdbc.queryForObject("select count(*) from insurance_contract", Long.class);
		};
	}
	private long savedLink(Account account, long worker, String version) {
		return tx(() -> links.saveAndFlush(new PlatformLink(account.id(), worker, version, OLD)).getId());
	}
	private static VerifiedWorker verified(long id, WorkerIdentity identity) {
		return new VerifiedWorker(id, identity.name(), identity.phone(), identity.nationalityType());
	}
	private static WorkerIdentity identity(NationalityType nationality) {
		return new WorkerIdentity("TEST WORKER", "010-0000-0000", nationality,
			nationality == NationalityType.DOMESTIC ? "0000000000000" : "9001015000000");
	}
	private static String payload(NationalityType nationality) {
		return """
			{"platformConsentAccepted":true,"consentVersion":"farm-platform-v1",
			"identity":{"name":"TEST WORKER","phone":"010-0000-0000","nationalityType":"%s","identityNumber":"%s"}}
			""".formatted(nationality, identity(nationality).identityNumber());
	}
	private MockHttpServletRequestBuilder register(Account account, String body) {
		return post(ROOT).session(account.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
	}
	private Account account(NationalityType nationality) throws Exception {
		String login = "link-" + UUID.randomUUID();
		String response = mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"" + login + "\",\"name\":\"Insurance worker\",\"password\":\"test-password\",\"nationalityType\":\"" + nationality + "\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("loginId", login).formField("password", "test-password"))
			.andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
		return new Account(((Number) JsonPath.read(response, "$.id")).longValue(), session);
	}
	private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
	private record Account(long id, MockHttpSession session) {}
}
