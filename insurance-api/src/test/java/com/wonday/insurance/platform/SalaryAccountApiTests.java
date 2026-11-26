package com.wonday.insurance.platform;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import com.wonday.insurance.member.MemberRole;
import com.wonday.insurance.member.NationalityType;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "insurance.farm.salary-account-query-enabled=true")
@AutoConfigureMockMvc
class SalaryAccountApiTests {

	private static final String PATH = "/api/platform/salary-account";
	private static final LocalDateTime CONSENTED_AT = LocalDateTime.of(2026, 11, 20, 0, 0);
	@Autowired MockMvc mvc;
	@Autowired EntityManager entities;
	@Autowired PlatformTransactionManager transactions;
	@Autowired PlatformLinkRepository links;
	@Autowired FarmPlatformProperties properties;
	@MockitoBean FarmSalaryAccountClient farm;

	@BeforeEach
	void prepare() {
		assertThat(properties.salaryAccountQueryEnabled()).isTrue();
		tx(() -> {
			links.deleteAllInBatch();
			entities.createQuery("delete from Member").executeUpdate();
			return null;
		});
		when(farm.find(anyLong())).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new SalaryAccountStatus(true, "020");
		});
	}

	@ParameterizedTest
	@CsvSource({"020, DOMESTIC", "004, FOREIGN"})
	void returnsOnlyTheOwnRegistrationStateWithoutAnOpenDatabaseTransaction(String bankCode, NationalityType nationality) throws Exception {
		Account own = account(MemberRole.WORKER, nationality);
		Account other = account();
		link(own, 123);
		link(other, 456);
		when(farm.find(123)).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return new SalaryAccountStatus(true, bankCode);
		});
		mvc.perform(get(PATH).session(own.session()).param("memberId", String.valueOf(other.id())).param("workerId", "456"))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.registered").value(true)).andExpect(jsonPath("$.bankCode").value(bankCode))
			.andExpect(jsonPath("$.workerId").doesNotExist()).andExpect(jsonPath("$.accountNumber").doesNotExist())
			.andExpect(jsonPath("$.accountHolderName").doesNotExist()).andExpect(jsonPath("$.eligible").doesNotExist());
		verify(farm).find(123);
		verify(farm, never()).find(456);
		assertThat(tx(() -> links.count())).isEqualTo(2);
	}

	@Test
	void missingSalaryAccountReturnsASuccessfulUnregisteredState() throws Exception {
		Account account = account();
		link(account, 123);
		when(farm.find(123)).thenReturn(new SalaryAccountStatus(false, null));
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(false)).andExpect(jsonPath("$.bankCode").isEmpty());
	}

	@Test
	void accountWithoutALinkDoesNotContactFarm() throws Exception {
		Account account = account();
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_REQUIRED"));
		verifyNoInteractions(farm);
	}

	@Test
	void revokedLinkDoesNotContactFarm() throws Exception {
		Account account = account();
		long id = link(account, 123);
		tx(() -> { links.findById(id).orElseThrow().revoke(CONSENTED_AT.plusDays(1)); return null; });
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_REQUIRED"));
		verifyNoInteractions(farm);
	}

	@Test
	void unverifiedLinkDoesNotContactFarm() throws Exception {
		Account account = account();
		tx(() -> {
			PlatformLink link = new PlatformLink(account.id(), 123, "farm-platform-v1", CONSENTED_AT);
			ReflectionTestUtils.setField(link, "workerId", null);
			links.saveAndFlush(link);
			return null;
		});
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_REQUIRED"));
		verifyNoInteractions(farm);
	}

	@Test
	void emptyConsentVersionCannotBeUsedForTheQuery() throws Exception {
		Account account = account();
		tx(() -> { links.saveAndFlush(new PlatformLink(account.id(), 123, "", CONSENTED_AT)); return null; });
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_REQUIRED"));
		verifyNoInteractions(farm);
	}

	@Test
	void anonymousAndAdministratorRequestsAreRejected() throws Exception {
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		Account admin = account(MemberRole.ADMIN, NationalityType.DOMESTIC);
		link(admin, 123);
		mvc.perform(get(PATH).session(admin.session())).andExpect(status().isForbidden());
		verifyNoInteractions(farm);
	}

	@Test
	void aChangedDatabaseRoleCannotReuseAnOldWorkerSession() throws Exception {
		Account account = account();
		link(account, 123);
		tx(() -> { entities.createNativeQuery("update member set role = 'ADMIN' where id = :id")
			.setParameter("id", account.id()).executeUpdate(); return null; });
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WORKER_ACCOUNT_REQUIRED"));
		verifyNoInteractions(farm);
	}

	@Test
	void revocationDuringTheRemoteCallSuppressesTheResult() throws Exception {
		Account account = account();
		long id = link(account, 123);
		when(farm.find(123)).thenAnswer(invocation -> {
			tx(() -> { links.findById(id).orElseThrow().revoke(CONSENTED_AT.plusDays(1)); return null; });
			return new SalaryAccountStatus(true, "020");
		});
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_CHANGED"))
			.andExpect(jsonPath("$.registered").doesNotExist()).andExpect(jsonPath("$.bankCode").doesNotExist());
	}

	@Test
	void replacementDuringTheRemoteCallRequiresRetryingWithTheNewLink() throws Exception {
		Account account = account();
		long id = link(account, 123);
		when(farm.find(123)).thenAnswer(invocation -> {
			tx(() -> {
				links.findById(id).orElseThrow().revoke(CONSENTED_AT.plusDays(1));
				links.flush();
				links.saveAndFlush(new PlatformLink(account.id(), 456, "farm-platform-v1", CONSENTED_AT.plusDays(2)));
				return null;
			});
			return new SalaryAccountStatus(true, "020");
		});
		when(farm.find(456)).thenReturn(new SalaryAccountStatus(true, "004"));
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_CHANGED"));
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.bankCode").value("004"));
		verify(farm).find(123);
		verify(farm).find(456);
	}

	@Test
	void consentVersionChangesDuringTheQueryAreDetected() throws Exception {
		Account account = account();
		long id = link(account, 123);
		when(farm.find(123)).thenAnswer(invocation -> {
			tx(() -> { entities.createNativeQuery("update platform_link set consent_version = 'farm-platform-v2' where id = :id")
				.setParameter("id", id).executeUpdate(); return null; });
			return new SalaryAccountStatus(true, "020");
		});
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PLATFORM_LINK_CHANGED"));
	}

	@Test
	void roleChangesDuringTheRemoteCallAreAlsoDetected() throws Exception {
		Account account = account();
		link(account, 123);
		when(farm.find(123)).thenAnswer(invocation -> {
			tx(() -> { entities.createNativeQuery("update member set role = 'ADMIN' where id = :id")
				.setParameter("id", account.id()).executeUpdate(); return null; });
			return new SalaryAccountStatus(true, "020");
		});
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("WORKER_ACCOUNT_REQUIRED"));
	}

	@Test
	void unavailableQueryCanBeRetriedWithoutSavingAnUnregisteredState() throws Exception {
		Account account = account();
		link(account, 123);
		when(farm.find(123)).thenThrow(new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
			"FARM_SERVICE_UNAVAILABLE", "Query unavailable")).thenReturn(new SalaryAccountStatus(true, "020"));
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("FARM_SERVICE_UNAVAILABLE"))
			.andExpect(jsonPath("$.registered").doesNotExist()).andExpect(header().string("Cache-Control", "no-store"));
		mvc.perform(get(PATH).session(account.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.registered").value(true));
		assertThat(tx(() -> links.count())).isEqualTo(1);
		assertThat(tx(() -> entities.createQuery("select m.phone from Member m where m.id = :id", String.class)
			.setParameter("id", account.id()).getSingleResult())).isNull();
	}

	@ParameterizedTest
	@ValueSource(ints = {404, 502, 503})
	void queryErrorsDoNotContainAFalseRegistrationResult(int status) throws Exception {
		Account account = account();
		link(account, 123);
		String code = switch (status) { case 404 -> "WORKER_NOT_FOUND"; case 502 -> "INVALID_PLATFORM_RESPONSE"; default -> "SALARY_ACCOUNT_QUERY_NOT_READY"; };
		when(farm.find(123)).thenThrow(new PlatformVerificationException(HttpStatus.valueOf(status), code, "Query failed"));
		mvc.perform(get(PATH).session(account.session())).andExpect(status().is(status))
			.andExpect(jsonPath("$.code").value(code)).andExpect(jsonPath("$.registered").doesNotExist());
	}

	private long link(Account account, long workerId) {
		return tx(() -> links.saveAndFlush(new PlatformLink(account.id(), workerId, "farm-platform-v1", CONSENTED_AT)).getId());
	}

	private Account account() throws Exception {
		return account(MemberRole.WORKER, NationalityType.DOMESTIC);
	}

	private Account account(MemberRole role, NationalityType nationality) throws Exception {
		String loginId = "salary-" + UUID.randomUUID();
		String response = mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"loginId":"%s","name":"TEST WORKER","password":"test-password","nationalityType":"%s"}
				""".formatted(loginId, nationality))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long memberId = ((Number) JsonPath.read(response, "$.id")).longValue();
		if (role == MemberRole.ADMIN) tx(() -> { entities.createNativeQuery("update member set role = 'ADMIN' where id = :id")
			.setParameter("id", memberId).executeUpdate(); return null; });
		MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("loginId", loginId).formField("password", "test-password"))
			.andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
		return new Account(memberId, session);
	}

	private <T> T tx(Supplier<T> action) {
		return new TransactionTemplate(transactions).execute(status -> action.get());
	}

	private record Account(long id, MockHttpSession session) {}
}
