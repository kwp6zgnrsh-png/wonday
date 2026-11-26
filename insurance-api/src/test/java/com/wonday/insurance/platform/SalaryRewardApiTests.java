package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

@SpringBootTest(properties = "insurance.farm.salary-account-query-enabled=true")
@AutoConfigureMockMvc
class SalaryRewardApiTests {

	private static final String ROOT = "/api/rewards/salary-account";
	private static final Instant NOW = Instant.parse("2026-11-26T12:00:00.123456789Z");
	private static final LocalDateTime CONSENTED = LocalDateTime.of(2026, 11, 20, 0, 0);
	@Autowired MockMvc mvc;
	@Autowired EntityManager entities;
	@Autowired PlatformTransactionManager transactions;
	@Autowired PlatformLinkRepository links;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean FarmSalaryAccountClient farm;
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
		when(farm.find(anyLong())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new SalaryAccountStatus(true, "020");
		});
	}

	@ParameterizedTest
	@CsvSource({"true,020,ELIGIBLE,WOORI_SALARY_ACCOUNT", "true,004,INELIGIBLE,OTHER_BANK", "false,,INELIGIBLE,NO_SALARY_ACCOUNT"})
	void persistsTheDecisionEvidenceAndRuleVersion(boolean registered, String bank, String result, String reason) throws Exception {
		Account own = account();
		link(own, 123);
		when(farm.find(123)).thenReturn(new SalaryAccountStatus(registered, bank));
		String response = mvc.perform(check(own, key())).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.result").value(result)).andExpect(jsonPath("$.reasonCode").value(reason))
			.andExpect(jsonPath("$.ruleVersion").value("salary-woori-v1"))
			.andExpect(jsonPath("$.checkedAt").value("2026-11-26T12:00:00.123456Z"))
			.andExpect(jsonPath("$.memberId").doesNotExist()).andExpect(jsonPath("$.platformWorkerId").doesNotExist())
			.andExpect(jsonPath("$.accountNumber").doesNotExist()).andExpect(jsonPath("$.requestKey").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		long id = number(response, "$.id");
		var row = jdbc.queryForMap("select * from salary_reward_check where id = ?", id);
		assertThat(row.get("member_id")).isEqualTo(own.id());
		assertThat(row.get("platform_worker_id")).isEqualTo(123L);
		assertThat(row.get("consent_version")).isEqualTo("farm-platform-v1");
		assertThat(row.get("result")).isEqualTo(result);
		assertThat(row.get("bank_code")).isEqualTo(bank);
		assertThat(row.get("source_error_code")).isNull();
	}

	@Test
	void duplicateKeysReturnTheOriginalDecisionAndNewKeysCreateNewHistory() throws Exception {
		Account own = account(); link(own, 123);
		String key = key();
		String first = mvc.perform(check(own, key)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		when(farm.find(123)).thenReturn(new SalaryAccountStatus(true, "004"));
		mvc.perform(check(own, key.toUpperCase(Locale.ROOT))).andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(number(first, "$.id"))).andExpect(jsonPath("$.result").value("ELIGIBLE"));
		verify(farm).find(123);
		mvc.perform(check(own, key())).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("INELIGIBLE"));
		assertThat(count()).isEqualTo(2);
		mvc.perform(get(ROOT + "/latest").session(own.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.bankCode").value("004"));
	}

	@Test
	void failureHistoryIsPreservedAndRetryUsesANewKey() throws Exception {
		Account own = account(); link(own, 123);
		String failedKey = key();
		when(farm.find(123)).thenThrow(new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
			"FARM_SERVICE_UNAVAILABLE", "upstream-private-message")).thenReturn(new SalaryAccountStatus(true, "020"));
		String first = mvc.perform(check(own, failedKey)).andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value("UNAVAILABLE")).andExpect(jsonPath("$.registered").isEmpty())
			.andExpect(jsonPath("$.bankCode").isEmpty()).andExpect(jsonPath("$.sourceErrorCode").value("FARM_SERVICE_UNAVAILABLE"))
			.andReturn().getResponse().getContentAsString();
		assertThat(first).doesNotContain("upstream-private-message");
		mvc.perform(check(own, failedKey)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(number(first, "$.id")));
		verify(farm).find(123);
		mvc.perform(check(own, key())).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ELIGIBLE"));
		assertThat(count()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select result from salary_reward_check where id = ?", String.class, number(first, "$.id")))
			.isEqualTo("UNAVAILABLE");
	}

	@ParameterizedTest
	@ValueSource(strings = {"WORKER_NOT_FOUND", "INVALID_PLATFORM_RESPONSE", "SALARY_ACCOUNT_QUERY_NOT_READY"})
	void expectedProviderErrorsAreRecordedAsUnavailable(String error) throws Exception {
		Account own = account(); link(own, 123);
		HttpStatus status = switch (error) { case "WORKER_NOT_FOUND" -> HttpStatus.NOT_FOUND; case "INVALID_PLATFORM_RESPONSE" -> HttpStatus.BAD_GATEWAY; default -> HttpStatus.SERVICE_UNAVAILABLE; };
		when(farm.find(123)).thenThrow(new PlatformVerificationException(status, error, "private-message"));
		mvc.perform(check(own, key())).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("UNAVAILABLE"))
			.andExpect(jsonPath("$.sourceErrorCode").value(error)).andExpect(jsonPath("$.registered").isEmpty());
	}

	@Test
	void latestUnavailableDoesNotFallBackToAnOlderEligibleResult() throws Exception {
		Account own = account(); link(own, 123);
		mvc.perform(check(own, key())).andExpect(status().isOk());
		when(farm.find(123)).thenThrow(new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
			"FARM_SERVICE_UNAVAILABLE", "Unavailable"));
		mvc.perform(check(own, key())).andExpect(status().isOk());
		mvc.perform(get(ROOT + "/latest").session(own.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.result").value("UNAVAILABLE"));
		assertThat(count()).isEqualTo(2);
	}

	@Test
	void historyIsOwnedPaginatedAndReadableAfterRevocation() throws Exception {
		Account own = account(); long linkId = link(own, 123);
		for (int i = 0; i < 3; i++) mvc.perform(check(own, key())).andExpect(status().isOk());
		Account other = account(); link(other, 456);
		mvc.perform(check(other, key())).andExpect(status().isOk());
		String first = mvc.perform(get(ROOT + "/checks").session(own.session()).param("limit", "2").param("memberId", String.valueOf(other.id())))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString();
		long before = number(first, "$.nextBeforeId");
		mvc.perform(get(ROOT + "/checks").session(own.session()).param("limit", "2").param("beforeId", String.valueOf(before)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasMore").value(false));
		tx(() -> { links.findById(linkId).orElseThrow().revoke(CONSENTED.plusDays(1)); return null; });
		clearInvocations(farm);
		mvc.perform(get(ROOT + "/latest").session(own.session())).andExpect(status().isOk());
		mvc.perform(get(ROOT + "/checks").session(own.session())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3));
		verifyNoInteractions(farm);
	}

	@Test
	void theSameKeyIsScopedToEachMember() throws Exception {
		Account one = account(); Account two = account(); link(one, 123); link(two, 456);
		String sameKey = key();
		String first = mvc.perform(check(one, sameKey)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		String second = mvc.perform(check(two, sameKey)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(number(first, "$.id")).isNotEqualTo(number(second, "$.id"));
		assertThat(count()).isEqualTo(2);
	}

	@Test
	void noLinkOrRevokedLinkCreatesNoHistory() throws Exception {
		Account own = account();
		mvc.perform(check(own, key())).andExpect(status().isConflict());
		long id = link(own, 123);
		tx(() -> { links.findById(id).orElseThrow().revoke(CONSENTED.plusDays(1)); return null; });
		mvc.perform(check(own, key())).andExpect(status().isConflict());
		assertThat(count()).isZero(); verifyNoInteractions(farm);
	}

	@Test
	void revocationDuringAProviderFailureIsNotRecordedAsAnAuthorizedCheck() throws Exception {
		Account own = account(); long id = link(own, 123);
		when(farm.find(123)).thenAnswer(invocation -> {
			tx(() -> { links.findById(id).orElseThrow().revoke(CONSENTED.plusDays(1)); return null; });
			throw new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE, "FARM_SERVICE_UNAVAILABLE", "Unavailable");
		});
		mvc.perform(check(own, key())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLATFORM_LINK_CHANGED"));
		assertThat(count()).isZero();
	}

	@Test
	void aLinkRevokedAfterObservationIsRecheckedAtTheSaveBoundary() throws Exception {
		Account own = account(); long id = link(own, 123);
		AtomicBoolean once = new AtomicBoolean();
		when(clock.instant()).thenAnswer(invocation -> {
			if (once.compareAndSet(false, true)) tx(() -> { links.findById(id).orElseThrow().revoke(CONSENTED.plusDays(1)); return null; });
			return NOW;
		});
		mvc.perform(check(own, key())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLATFORM_LINK_CHANGED"));
		assertThat(count()).isZero();
	}

	@Test
	void rolesAreRecheckedWhenSavingAndReadingHistory() throws Exception {
		Account own = account(); link(own, 123);
		AtomicBoolean once = new AtomicBoolean();
		when(clock.instant()).thenAnswer(invocation -> {
			if (once.compareAndSet(false, true)) tx(() -> { entities.createNativeQuery("update member set role = 'ADMIN' where id = :id")
				.setParameter("id", own.id()).executeUpdate(); return null; });
			return NOW;
		});
		mvc.perform(check(own, key())).andExpect(status().isForbidden());
		mvc.perform(get(ROOT + "/latest").session(own.session())).andExpect(status().isForbidden());
		mvc.perform(get(ROOT + "/checks").session(own.session())).andExpect(status().isForbidden());
		assertThat(count()).isZero();
	}

	@Test
	void emptyHistoryAndInvalidRequestInputsAreHandledWithoutCallingFarm() throws Exception {
		Account own = account();
		mvc.perform(get(ROOT + "/latest").session(own.session())).andExpect(status().isNotFound());
		mvc.perform(get(ROOT + "/checks").session(own.session())).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		for (String bad : new String[]{"wrong", "1-1-1-1-1", " " + key()}) mvc.perform(check(own, bad)).andExpect(status().isBadRequest());
		mvc.perform(post(ROOT + "/checks").session(own.session()).with(csrf())).andExpect(status().isBadRequest());
		mvc.perform(get(ROOT + "/checks").session(own.session()).param("limit", "101")).andExpect(status().isBadRequest());
		mvc.perform(get(ROOT + "/checks").session(own.session()).param("beforeId", "0")).andExpect(status().isBadRequest());
		assertThat(count()).isZero(); verifyNoInteractions(farm);
	}

	@Test
	void authenticationRoleAndCsrfAreRequired() throws Exception {
		mvc.perform(get(ROOT + "/checks")).andExpect(status().isUnauthorized());
		mvc.perform(get(ROOT + "/latest")).andExpect(status().isUnauthorized());
		mvc.perform(post(ROOT + "/checks").with(csrf()).header("Idempotency-Key", key())).andExpect(status().isUnauthorized());
		Account own = account();
		mvc.perform(post(ROOT + "/checks").session(own.session()).header("Idempotency-Key", key())).andExpect(status().isForbidden());
		tx(() -> { entities.createNativeQuery("update member set role = 'ADMIN' where id = :id").setParameter("id", own.id()).executeUpdate(); return null; });
		mvc.perform(check(own, key())).andExpect(status().isForbidden());
		assertThat(count()).isZero(); verifyNoInteractions(farm);
	}

	@Test
	void concurrentSameKeyRequestsCreateOneHistoryRow() throws Exception {
		Account own = account(); link(own, 123);
		String requestKey = key();
		String csrfJson = mvc.perform(get("/api/auth/csrf").session(own.session())).andReturn().getResponse().getContentAsString();
		String header = JsonPath.read(csrfJson, "$.headerName");
		String token = JsonPath.read(csrfJson, "$.token");
		CountDownLatch entered = new CountDownLatch(2);
		CountDownLatch release = new CountDownLatch(1);
		when(farm.find(123)).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			entered.countDown();
			assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
			return new SalaryAccountStatus(true, "020");
		});
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Long> action = () -> {
				String body = mvc.perform(post(ROOT + "/checks").session(own.session()).header(header, token)
					.header("Idempotency-Key", requestKey).with(request -> {
						request.setRequestedSessionId(own.session().getId()); request.setRequestedSessionIdValid(true); return request;
					})).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
				return number(body, "$.id");
			};
			Future<Long> one = pool.submit(action); Future<Long> two = pool.submit(action);
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			release.countDown();
			assertThat(one.get(20, TimeUnit.SECONDS)).isEqualTo(two.get(20, TimeUnit.SECONDS));
			assertThat(count()).isEqualTo(1); verify(farm, times(2)).find(123);
		} finally {
			release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void databaseFailureRollsBackTheAttemptAndAllowsRetryWithTheSameKey() throws Exception {
		Account own = account(); link(own, 123);
		String requestKey = key();
		// 오류 주입: 실제 클라이언트 검증을 우회한 잘못된 내부 데이터도 DB가 차단해야 한다.
		when(farm.find(123)).thenReturn(new SalaryAccountStatus(true, "ABC"));
		assertThatThrownBy(() -> mvc.perform(check(own, requestKey))).hasRootCauseInstanceOf(SQLException.class);
		assertThat(count()).isZero();
		when(farm.find(123)).thenReturn(new SalaryAccountStatus(true, "020"));
		mvc.perform(check(own, requestKey)).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("ELIGIBLE"));
		assertThat(count()).isEqualTo(1);
	}

	@Test
	void physicalConstraintsRejectWrongOwnershipAndDuplicateKeys() throws Exception {
		Account own = account(); link(own, 123);
		long id = number(mvc.perform(check(own, key())).andReturn().getResponse().getContentAsString(), "$.id");
		Account other = account(); long otherLink = link(other, 456);
		assertSqlError(() -> jdbc.update("update salary_reward_check set platform_link_id = ? where id = ?", otherLink, id), 1452);
		assertSqlError(() -> jdbc.update("update salary_reward_check set bank_code = '004' where id = ?", id), 3819);
		assertSqlError(() -> jdbc.update("""
			insert into salary_reward_check (member_id,platform_link_id,platform_worker_id,consent_version,request_key,
			result,reason_code,registered,bank_code,source_error_code,rule_version,checked_at,recorded_at)
			select member_id,platform_link_id,platform_worker_id,consent_version,request_key,
			result,reason_code,registered,bank_code,source_error_code,rule_version,checked_at,recorded_at from salary_reward_check where id = ?
			""", id), 1062);
		assertThat(count()).isEqualTo(1);
	}

	private static void assertSqlError(Runnable action, int code) {
		assertThatThrownBy(action::run).rootCause().isInstanceOfSatisfying(SQLException.class,
			exception -> assertThat(exception.getErrorCode()).isEqualTo(code));
	}

	private long count() { return jdbc.queryForObject("select count(*) from salary_reward_check", Long.class); }
	private static String key() { return UUID.randomUUID().toString(); }
	private static long number(String json, String path) { return ((Number) JsonPath.read(json, path)).longValue(); }
	private MockHttpServletRequestBuilder check(Account account, String key) {
		return post(ROOT + "/checks").session(account.session()).with(csrf()).header("Idempotency-Key", key);
	}
	private long link(Account account, long workerId) {
		return tx(() -> links.saveAndFlush(new PlatformLink(account.id(), workerId, "farm-platform-v1", CONSENTED)).getId());
	}
	private Account account() throws Exception {
		String login = "reward-" + UUID.randomUUID();
		String json = mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"" + login + "\",\"name\":\"TEST WORKER\",\"password\":\"test-password\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("loginId", login).formField("password", "test-password"))
			.andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
		return new Account(number(json, "$.id"), session);
	}
	private <T> T tx(Supplier<T> action) { return new TransactionTemplate(transactions).execute(status -> action.get()); }
	private record Account(long id, MockHttpSession session) {}
}
