package com.wonday.insurance.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.jayway.jsonpath.JsonPath;
import com.wonday.insurance.platform.FarmPlatformClient;
import com.wonday.insurance.platform.FarmPlatformClient.VerifiedWorker;
import com.wonday.insurance.platform.PlatformVerificationException;
import com.wonday.insurance.member.IdentityCipher;
import com.wonday.insurance.member.IdentityType;
import com.wonday.insurance.member.NationalityType;
import com.wonday.insurance.platform.FarmPlatformProperties;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "insurance.farm.worker-verification-v2-enabled=true")
@AutoConfigureMockMvc
@Import(ContractApiTests.FixedClock.class)
class ContractApiTests {

	@Autowired MockMvc mvc;
	@Autowired EntityManager entities;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired ObjectMapper json;
	@Autowired Clock clock;
	@Autowired ContractService contracts;
	@Autowired IdentityCipher identityCipher;
	@Autowired FarmPlatformProperties farmProperties;
	@MockitoBean FarmPlatformClient farm;
	private long versionId;

	@BeforeEach
	void prepare() {
		assertThat(farmProperties.workerVerificationV2Enabled()).isTrue();
		when(clock.instant()).thenReturn(Instant.parse("2026-02-09T15:00:00Z"));
		when(farm.verify(any())).thenReturn(new VerifiedWorker(77, "테스트 근로자", "01000000000"));
		tx(() -> {
			for (String entity : new String[]{"InsuranceContract", "PlatformLink", "ProductVersion", "InsuranceProduct", "Member"}) {
				entities.createQuery("delete from " + entity).executeUpdate();
			}
			Object product = entity("product.InsuranceProduct");
			field(product, "productCode", "DEMO");
			field(product, "name", "분기 소득 보장");
			field(product, "description", "가상 상품");
			field(product, "status", "ACTIVE");
			entities.persist(product);
			Object version = entity("product.ProductVersion");
			field(version, "product", product);
			field(version, "versionNo", 1);
			field(version, "salesStartAt", LocalDateTime.of(2026, 1, 1, 0, 0));
			field(version, "termsText", "시연 약관");
			field(version, "premiumTerms", "{}");
			field(version, "eligibleWorkGroups", "[]");
			field(version, "stationSelectionRule", "{}");
			field(version, "windowRule", "{}");
			field(version, "indexRules", "{}");
			field(version, "payoutRules", "{}");
			entities.persist(version);
			entities.flush();
			versionId = (long) ReflectionTestUtils.getField(version, "id");
			return null;
		});
	}

	@Test
	void enrollmentAtomicallySavesContactLinkAndThreeMonthContract() throws Exception {
		Account account = account();
		String result = mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true)))
			.andExpect(status().isCreated())
			.andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern("/api/contracts/[1-9][0-9]*")))
			.andExpect(jsonPath("$.coverageFrom").value("2026-02-10T00:00:00+09:00"))
			.andExpect(jsonPath("$.acceptedAt").value("2026-02-10T00:00:00+09:00"))
			.andExpect(jsonPath("$.coverageTo").value("2026-05-10T00:00:00+09:00"))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.termsText").value("시연 약관"))
			.andExpect(jsonPath("$.residentRegistrationNumber").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(result, "$.id")).longValue();
		tx(() -> {
			assertThat(entities.find(InsuranceContract.class, id).getAcceptedAt()).isEqualTo(LocalDateTime.of(2026, 2, 9, 15, 0));
			assertThat(entities.find(InsuranceContract.class, id).getCoverageTo()).isEqualTo(LocalDateTime.of(2026, 5, 9, 15, 0));
			assertThat(entities.createQuery("select m.phone from Member m where m.id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult()).isEqualTo("01000000000");
			String ciphertext = entities.createQuery("select m.identityNumberCiphertext from Member m where m.id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult();
			assertThat(ciphertext).startsWith("v1:").doesNotContain("0000000000000");
			assertThat(identityCipher.decrypt(account.id(), IdentityType.RESIDENT_REGISTRATION_NUMBER, ciphertext))
				.isEqualTo("0000000000000");
			assertThat(entities.createNativeQuery("select resident_registration_number from member where id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult()).isNull();
			assertThat(count("PlatformLink")).isEqualTo(1);
			return null;
		});
		mvc.perform(get("/api/contracts").session(account.session()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].coverageFrom").value("2026-02-10T00:00:00+09:00"));
		mvc.perform(get("/api/contracts/{id}", id).session(account.session()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.id").value((int) id))
			.andExpect(jsonPath("$.acceptedAt").value("2026-02-10T00:00:00+09:00"));
		Account other = account();
		mvc.perform(get("/api/contracts/{id}", id).session(other.session())).andExpect(status().isNotFound());
		mvc.perform(get("/api/contracts").session(other.session())).andExpect(jsonPath("$.length()").value(0));
		when(clock.instant()).thenReturn(Instant.parse("2026-05-09T15:00:00Z"));
		mvc.perform(get("/api/contracts/{id}", id).session(account.session()))
			.andExpect(jsonPath("$.status").value("EXPIRED"));
	}

	@Test
	void foreignMemberEnrollsWithEncryptedForeignIdentityAndExistingSession() throws Exception {
		Account account = account(NationalityType.FOREIGN);
		when(farm.verify(any())).thenReturn(new VerifiedWorker(77, "TEST WORKER", "01000000000", NationalityType.FOREIGN));
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(v2Payload(NationalityType.FOREIGN).replace("테스트 근로자", " TEST WORKER ")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.identityNumber").doesNotExist())
			.andExpect(jsonPath("$.identityNumberCiphertext").doesNotExist());
		verify(farm).verify(argThat(identity -> identity.nationalityType() == NationalityType.FOREIGN
			&& identity.name().equals("TEST WORKER") && identity.phone().equals("01000000000")));
		tx(() -> {
			String ciphertext = entities.createQuery("select m.identityNumberCiphertext from Member m where m.id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult();
			assertThat(identityCipher.decrypt(account.id(), IdentityType.FOREIGN_REGISTRATION_NUMBER, ciphertext))
				.isEqualTo("9001015000000");
			assertThat(entities.createQuery("select m.identityType from Member m where m.id = :id", IdentityType.class)
				.setParameter("id", account.id()).getSingleResult()).isEqualTo(IdentityType.FOREIGN_REGISTRATION_NUMBER);
			assertThat(entities.createNativeQuery("select resident_registration_number from member where id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult()).isNull();
			assertThat(count("InsuranceContract")).isEqualTo(1);
			assertThat(count("PlatformLink")).isEqualTo(1);
			return null;
		});
		mvc.perform(get("/api/auth/me").session(account.session()))
			.andExpect(status().isOk()).andExpect(jsonPath("$.nationalityType").value("FOREIGN"));
	}

	@Test
	void domesticMemberCanEnrollUsingCanonicalIdentityFields() throws Exception {
		Account account = account();
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(v2Payload(NationalityType.DOMESTIC)))
			.andExpect(status().isCreated());
		verify(farm).verify(argThat(identity -> identity.nationalityType() == NationalityType.DOMESTIC
			&& identity.identityNumber().equals("0000000000000")));
	}

	@Test
	void nationalityMismatchIsRejectedBeforeCallingFarm() throws Exception {
		for (NationalityType registered : NationalityType.values()) {
			Account account = account(registered);
			NationalityType requested = registered == NationalityType.DOMESTIC ? NationalityType.FOREIGN : NationalityType.DOMESTIC;
			mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(v2Payload(requested)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NATIONALITY_TYPE_MISMATCH"));
		}
		verifyNoInteractions(farm);
		assertThat(tx(() -> count("InsuranceContract"))).isZero();
		assertThat(tx(() -> count("PlatformLink"))).isZero();
	}

	@Test
	void memberNationalityIsRecheckedAfterRemoteVerificationUnderTheMemberLock() throws Exception {
		Account account = account();
		when(farm.verify(any())).thenAnswer(invocation -> {
			tx(() -> {
				entities.createNativeQuery("update member set nationality_type = 'FOREIGN' where id = :id")
					.setParameter("id", account.id()).executeUpdate();
				return null;
			});
			return new VerifiedWorker(77, "테스트 근로자", "01000000000");
		});
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(v2Payload(NationalityType.DOMESTIC)))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NATIONALITY_TYPE_MISMATCH"));
		assertNoEnrollmentState(account);
		assertThat(tx(() -> entities.createQuery("select m.nationalityType from Member m where m.id = :id", NationalityType.class)
			.setParameter("id", account.id()).getSingleResult())).isEqualTo(NationalityType.FOREIGN);
	}

	@ParameterizedTest
	@ValueSource(ints = {404, 503})
	void failedForeignVerificationSavesNoContactLinkOrContract(int status) throws Exception {
		Account account = account(NationalityType.FOREIGN);
		when(farm.verify(any())).thenThrow(new PlatformVerificationException(HttpStatus.valueOf(status),
			status == 404 ? "WORKER_IDENTITY_NOT_MATCHED" : "FARM_SERVICE_UNAVAILABLE", "Verification failed"));
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(v2Payload(NationalityType.FOREIGN)))
			.andExpect(status().is(status));
		assertNoEnrollmentState(account);
	}

	@Test
	void unclassifiedMemberAdoptsNationalityOnlyAfterVerificationAndCommit() throws Exception {
		Account account = account();
		tx(() -> {
			entities.createNativeQuery("update member set nationality_type = null where id = :id")
				.setParameter("id", account.id()).executeUpdate();
			return null;
		});
		when(farm.verify(any())).thenThrow(new PlatformVerificationException(HttpStatus.SERVICE_UNAVAILABLE,
			"FARM_SERVICE_UNAVAILABLE", "Verification failed"))
			.thenReturn(new VerifiedWorker(77, "테스트 근로자", "01000000000", NationalityType.FOREIGN));
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(v2Payload(NationalityType.FOREIGN)))
			.andExpect(status().isServiceUnavailable());
		assertThat(tx(() -> entities.createQuery("select m.nationalityType from Member m where m.id = :id", NationalityType.class)
			.setParameter("id", account.id()).getSingleResult())).isNull();
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(v2Payload(NationalityType.FOREIGN)))
			.andExpect(status().isCreated());
		mvc.perform(get("/api/auth/me").session(account.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.nationalityType").value("FOREIGN"));
	}

	@Test
	void foreignLocalFailureRollsBackEncryptedContactAndConsent() throws Exception {
		Account account = account(NationalityType.FOREIGN);
		when(farm.verify(any())).thenAnswer(invocation -> {
			tx(() -> {
				entities.createQuery("update InsuranceProduct p set status = 'CLOSED'").executeUpdate();
				return null;
			});
			return new VerifiedWorker(77, "테스트 근로자", "01000000000", NationalityType.FOREIGN);
		});
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(v2Payload(NationalityType.FOREIGN)))
			.andExpect(status().isConflict());
		assertNoEnrollmentState(account);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"\"identityNumber\":\"9001015000000\"",
		"\"nationalityType\":\"FOREIGN\",\"identityNumber\":9001015000000",
		"\"nationalityType\":\"FOREIGN\",\"identityNumber\":\"9001015000000\",\"residentRegistrationNumber\":null"
	})
	void invalidCanonicalIdentityIsRejectedBeforeAnyVerification(String fields) throws Exception {
		Account account = account(NationalityType.FOREIGN);
		String body = "{\"productVersionId\":" + versionId + ",\"termsAccepted\":true,\"platformConsentAccepted\":true,"
			+ "\"identity\":{\"name\":\"TEST WORKER\",\"phone\":\"01000000000\"," + fields + "}}";
		String response = mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
			.andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain("9001015000000");
		verifyNoInteractions(farm);
		assertNoEnrollmentState(account);
	}

	private void assertNoEnrollmentState(Account account) {
		tx(() -> {
			assertThat(count("InsuranceContract")).isZero();
			assertThat(count("PlatformLink")).isZero();
			assertThat(entities.createQuery("select m.phone from Member m where m.id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult()).isNull();
			assertThat(entities.createQuery("select m.identityNumberCiphertext from Member m where m.id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult()).isNull();
			return null;
		});
	}

	@Test
	void consentIsRequiredBeforeCallingFarm() throws Exception {
		Account account = account();
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(false, true))).andExpect(status().isBadRequest());
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, false))).andExpect(status().isBadRequest());
		verifyNoInteractions(farm);
		assertThat(tx(() -> count("InsuranceContract"))).isZero();
	}

	@Test
	void verificationFailureCreatesNoLocalState() throws Exception {
		Account account = account();
		when(farm.verify(any())).thenThrow(new PlatformVerificationException(HttpStatus.NOT_FOUND,
			"WORKER_IDENTITY_NOT_MATCHED", "No matching worker"));
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true)))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("WORKER_IDENTITY_NOT_MATCHED"));
		tx(() -> {
			assertThat(count("InsuranceContract")).isZero();
			assertThat(count("PlatformLink")).isZero();
			assertThat(entities.createQuery("select m.phone from Member m where m.id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult()).isNull();
			return null;
		});
	}

	@Test
	void duplicateAndCrossAccountLinksRollback() throws Exception {
		Account first = account();
		Account second = account();
		mvc.perform(post("/api/contracts").session(first.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true))).andExpect(status().isCreated());
		mvc.perform(post("/api/contracts").session(first.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true))).andExpect(status().isConflict());
		mvc.perform(post("/api/contracts").session(second.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true))).andExpect(status().isConflict());
		tx(() -> {
			assertThat(count("InsuranceContract")).isEqualTo(1);
			assertThat(count("PlatformLink")).isEqualTo(1);
			assertThat(entities.createQuery("select m.phone from Member m where m.id = :id", String.class)
				.setParameter("id", second.id()).getSingleResult()).isNull();
			return null;
		});
	}

	@Test
	void unavailableVersionAndInvalidIdentityCannotCreateContracts() throws Exception {
		Account account = account();
		String unavailable = payload(true, true).replace("\"productVersionId\":" + versionId, "\"productVersionId\":9223372036854775807");
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(unavailable)).andExpect(status().isNotFound());
		String invalid = payload(true, true).replace("000000-0000000", "wrong");
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
		verifyNoInteractions(farm);
	}

	@Test
	void concurrentEnrollmentForSameAccountCreatesOneContract() throws Exception {
		Account account = account();
		ContractEnrollmentRequest request = json.readValue(payload(true, true), ContractEnrollmentRequest.class);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> action = () -> {
				try { contracts.enroll(account.id(), request); return 201; }
				catch (org.springframework.web.server.ResponseStatusException exception) {
					return exception.getStatusCode().value();
				}
			};
			var results = pool.invokeAll(java.util.List.of(action, action));
			assertThat(java.util.List.of(results.get(0).get(), results.get(1).get()))
				.containsExactlyInAnyOrder(201, 409);
			assertThat(tx(() -> count("InsuranceContract"))).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void threeMonthCoverageUsesKoreanMonthEnd() throws Exception {
		Account account = account();
		when(clock.instant()).thenReturn(Instant.parse("2026-01-30T15:00:00Z"));
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true)))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.coverageTo").value("2026-04-30T00:00:00+09:00"));
	}

	@Test
	void productIsRecheckedAfterFarmVerificationAndLocalWritesRollback() throws Exception {
		Account account = account();
		when(farm.verify(any())).thenAnswer(invocation -> {
			tx(() -> {
				entities.createQuery("update InsuranceProduct p set p.status = 'CLOSED'").executeUpdate();
				return null;
			});
			return new VerifiedWorker(77, "테스트 근로자", "01000000000");
		});
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true))).andExpect(status().isConflict());
		tx(() -> {
			assertThat(count("InsuranceContract")).isZero();
			assertThat(count("PlatformLink")).isZero();
			assertThat(entities.createQuery("select m.phone from Member m where m.id = :id", String.class)
				.setParameter("id", account.id()).getSingleResult()).isNull();
			return null;
		});
	}

	@Test
	void enrollmentReusesConnectionRegisteredBeforePurchasingInsurance() throws Exception {
		Account account = account();
		String identity = json.writeValueAsString(json.readTree(v2Payload(NationalityType.DOMESTIC)).get("identity"));
		String linked = mvc.perform(post("/api/platform/link").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content("""
				{"platformConsentAccepted":true,"consentVersion":"farm-platform-v1","identity":%s}
				""".formatted(identity)))
			.andExpect(status().isOk()).andExpect(jsonPath("$.linked").value(true))
			.andReturn().getResponse().getContentAsString();
		assertThat(tx(() -> count("InsuranceContract"))).isZero();
		when(clock.instant()).thenReturn(Instant.parse("2026-02-09T16:00:00Z"));
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(v2Payload(NationalityType.DOMESTIC)))
			.andExpect(status().isCreated());
		mvc.perform(get("/api/platform/link").session(account.session())).andExpect(status().isOk())
			.andExpect(content().json(linked));
		assertThat(tx(() -> count("PlatformLink"))).isEqualTo(1);
		assertThat(tx(() -> count("InsuranceContract"))).isEqualTo(1);
	}

	@Test
	void existingLinkIsReusedForAnotherProduct() throws Exception {
		Account account = account();
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true))).andExpect(status().isCreated());
		tx(() -> {
			Object product = entity("product.InsuranceProduct");
			field(product, "productCode", "SECOND");
			field(product, "name", "다른 상품");
			field(product, "description", "가상 상품");
			field(product, "status", "ACTIVE");
			entities.persist(product);
			Object version = entity("product.ProductVersion");
			field(version, "product", product);
			field(version, "versionNo", 1);
			field(version, "salesStartAt", LocalDateTime.of(2026, 1, 1, 0, 0));
			field(version, "termsText", "다른 약관");
			for (String field : new String[]{"premiumTerms", "stationSelectionRule", "windowRule", "indexRules", "payoutRules"}) {
				field(version, field, "{}");
			}
			field(version, "eligibleWorkGroups", "[]");
			entities.persist(version);
			entities.flush();
			versionId = (long) ReflectionTestUtils.getField(version, "id");
			return null;
		});
		mvc.perform(post("/api/contracts").session(account.session()).with(csrf())
			.contentType(MediaType.APPLICATION_JSON).content(payload(true, true))).andExpect(status().isCreated());
		tx(() -> {
			assertThat(count("InsuranceContract")).isEqualTo(2);
			assertThat(count("PlatformLink")).isEqualTo(1);
			return null;
		});
	}

	@Test
	void endpointsRequireAuthenticationAndCsrf() throws Exception {
		mvc.perform(get("/api/contracts")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/contracts").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content(payload(true, true))).andExpect(status().isUnauthorized());
		Account account = account();
		mvc.perform(post("/api/contracts").session(account.session()).contentType(MediaType.APPLICATION_JSON)
			.content(payload(true, true))).andExpect(status().isForbidden());
	}

	private String payload(boolean terms, boolean consent) {
		return """
			{"productVersionId":%d,"termsAccepted":%s,"platformConsentAccepted":%s,
			"identity":{"name":"테스트 근로자","phone":"010-0000-0000","residentRegistrationNumber":"000000-0000000"}}
			""".formatted(versionId, terms, consent);
	}

	private Account account() throws Exception {
		return account(NationalityType.DOMESTIC);
	}

	private String v2Payload(NationalityType nationality) {
		return """
			{"productVersionId":%d,"termsAccepted":true,"platformConsentAccepted":true,
			"identity":{"name":"테스트 근로자","phone":"010-0000-0000","nationalityType":"%s","identityNumber":"%s"}}
			""".formatted(versionId, nationality, nationality == NationalityType.DOMESTIC ? "0000000000000" : "9001015000000");
	}

	private Account account(NationalityType nationality) throws Exception {
		String loginId = "enroll-" + UUID.randomUUID();
		String response = mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\""+loginId+"\",\"name\":\"보험 회원\",\"password\":\"test-password\",\"nationalityType\":\""+nationality+"\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		MockHttpSession session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("loginId", loginId)
			.formField("password", "test-password")).andExpect(status().isNoContent())
			.andReturn().getRequest().getSession(false);
		return new Account(((Number)JsonPath.read(response, "$.id")).longValue(), session);
	}

	private long count(String entity) {
		return entities.createQuery("select count(e) from " + entity + " e", Long.class).getSingleResult();
	}

	private <T> T tx(Supplier<T> action) {
		return new TransactionTemplate(transactionManager).execute(status -> action.get());
	}

	private static Object entity(String name) {
		try { return BeanUtils.instantiateClass(Class.forName("com.wonday.insurance." + name)); }
		catch (ClassNotFoundException exception) { throw new IllegalStateException(exception); }
	}

	private static void field(Object entity, String name, Object value) {
		ReflectionTestUtils.setField(entity, name, value);
	}

	private record Account(long id, MockHttpSession session) {}

	@TestConfiguration
	static class FixedClock {
		@Bean @Primary Clock enrollmentClock() { return mock(Clock.class); }
	}
}
