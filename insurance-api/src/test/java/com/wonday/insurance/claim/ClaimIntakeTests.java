package com.wonday.insurance.claim;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.wonday.insurance.platform.PlatformClaimEvents;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {"insurance.claim-intake.enabled=true", "insurance.claim-intake.delay-ms=3600000",
	"insurance.farm.service-token=test-service-token"})
@AutoConfigureMockMvc
@Import(ClaimIntakeTests.FarmTestConfiguration.class)
class ClaimIntakeTests {
	@Autowired ClaimIntakeService intake;
	@Autowired ClaimIntakeScheduler scheduler;
	@Autowired ClaimRepository claims;
	@MockitoSpyBean PlatformClaimEvents source;
	@Autowired EntityManager entities;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired ObjectMapper json;
	@Autowired MockMvc mvc;
	@Autowired PasswordEncoder passwords;
	@Autowired FarmStub farm;
	@Autowired ApplicationContext context;
	@MockitoBean Clock clock;
	@MockitoSpyBean ClaimHistoryRepository history;
	private Account owner;
	private long linkId;
	private long contractId;
	private long versionId;
	private static final String PASSWORD = "Password!123";
	private static final Instant NOW = Instant.parse("2026-11-25T00:00:00Z");
	private static final OffsetDateTime CANCELLED = OffsetDateTime.parse("2026-11-24T07:00:00+09:00");
	private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-24T08:00:00+09:00");

	@BeforeEach
	void prepare() {
		farm.server().reset();
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		tx(() -> {
			for (String entity : List.of("ClaimHistory", "ClaimCase", "PlatformWorkEvent", "PlatformWorkCursor",
				"InsuranceContract", "PlatformLink", "ProductVersion", "InsuranceProduct", "Member")) {
				entities.createQuery("delete from " + entity).executeUpdate();
			}
			owner = account("WORKER");
			versionId = product("DEMO");
			linkId = link(owner.id(), 77);
			contractId = contract(owner.id(), versionId,
				LocalDateTime.of(2026, 11, 1, 0, 0), LocalDateTime.of(2026, 12, 1, 0, 0), "ACTIVE");
			return null;
		});
	}

	@AfterEach
	void verifyFarmRequests() {
		farm.server().verify();
	}

	@Test
	void schedulerCreatesClaimHistoryAndProcessingProgressForStoredEvent() {
		long eventId = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		scheduler.run();
		assertThat(claims.count()).isEqualTo(1);
		ClaimCase claim = claims.findAll().getFirst();
		assertThat(claim.getContractId()).isEqualTo(contractId);
		assertThat(claim.getAssignmentId()).isEqualTo(7001);
		assertThat(claim.getTriggerEventId()).isEqualTo(eventId);
		assertThat(claim.getStatus()).isEqualTo("REVIEWING");
		assertThat(claim.getOpenedVia()).isEqualTo("AUTO");
		assertThat(json.readTree(claim.getWorkSnapshot()).get("agreedDailyWage").asLong()).isEqualTo(120000);
		assertThat(claim.getOpenedAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
		assertThat(history.count()).isEqualTo(1);
		assertThat(result(eventId)).isEqualTo("RECEIVED");
		assertThat(source.pendingIds()).isEmpty();
	}

	@Test
	void pollingAndIntakeSchedulersConnectThroughPersistedEvents() {
		ObjectNode change = json.createObjectNode().put("changeId", 9001).put("sourceVersion", "1")
			.put("assignmentId", 7001).put("changeType", "CANCELLED").put("reasonCode", "WEATHER")
			.put("weatherReasonCode", "RAIN").put("reasonDetail", "기상 사유 취소")
			.put("effectiveAt", CANCELLED.toString()).put("recordedAt", CANCELLED.toString())
			.put("updatedAt", CANCELLED.toString());
		ObjectNode page = json.createObjectNode().put("workerId", 77).put("nextCursor", "complete").put("hasMore", false);
		page.putArray("items").add(change);
		farm.server().expect(requestTo("http://farm.test/api/internal/insurance/workers/77/work-changes?limit=100"))
			.andRespond(withSuccess(json.writeValueAsString(page), MediaType.APPLICATION_JSON));
		farm.server().expect(requestTo("http://farm.test/api/internal/insurance/workers/77/work-assignments/7001"))
			.andRespond(withSuccess(json.writeValueAsString(snapshot(7001, START)), MediaType.APPLICATION_JSON));

		ReflectionTestUtils.invokeMethod(context.getBean("workChangePollingService"), "pollPage", linkId);
		scheduler.run();

		assertThat(claims.count()).isEqualTo(1);
		assertThat(history.count()).isEqualTo(1);
		assertThat(source.pendingIds()).isEmpty();
	}

	@ParameterizedTest
	@CsvSource({"EARLY_ENDED,WEATHER,NOT_CANCELLED", "CANCELLED,FARM_CIRCUMSTANCES,NON_WEATHER_REASON",
		"CANCELLED,OTHER,NON_WEATHER_REASON"})
	void excludesNonCoveredChangeTypesAndReasons(String type, String reason, String expected) {
		long eventId = event(9001, "1", 7001, CANCELLED, null, type, reason);
		assertThat(intake.process(eventId)).isEqualTo(expected);
		assertThat(claims.count()).isZero();
		assertThat(history.count()).isZero();
		assertThat(result(eventId)).isEqualTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = {"2026-11-23T23:59:59+09:00", "2026-11-24T08:00:00+09:00",
		"2026-11-24T08:00:00.000001+09:00"})
	void excludesPreviousLocalDayAndCancellationAtOrAfterStart(String time) {
		long eventId = event(9001, "1", 7001, OffsetDateTime.parse(time), snapshot(7001, START), "CANCELLED", "WEATHER");
		assertThat(intake.process(eventId)).isEqualTo("NOT_SAME_DAY_BEFORE_START");
		assertThat(claims.count()).isZero();
	}

	@ParameterizedTest
	@CsvSource({"0,1,RECEIVED", "-1,0,NO_COVERED_CONTRACT", "1,100,NO_COVERED_CONTRACT", "-100,1,RECEIVED"})
	void coverageIncludesStartAndExcludesEnd(long fromMicros, long toMicros, String expected) {
		LocalDateTime occurred = utc(CANCELLED);
		tx(() -> {
			Object contract = entities.find(type("contract.InsuranceContract"), contractId);
			field(contract, "coverageFrom", occurred.plus(fromMicros, ChronoUnit.MICROS));
			field(contract, "coverageTo", occurred.plus(toMicros, ChronoUnit.MICROS));
			return null;
		});
		long eventId = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		assertThat(intake.process(eventId)).isEqualTo(expected);
	}

	@Test
	void expiredContractStillCoversLateReceivedCancellation() {
		when(clock.instant()).thenReturn(Instant.parse("2027-02-01T00:00:00Z"));
		tx(() -> {
			field(entities.find(type("contract.InsuranceContract"), contractId), "status", "EXPIRED");
			Object version = entities.find(type("product.ProductVersion"), versionId);
			field(ReflectionTestUtils.getField(version, "product"), "status", "INACTIVE");
			return null;
		});
		long eventId = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		assertThat(intake.process(eventId)).isEqualTo("RECEIVED");
	}

	@Test
	void cancelledContractDoesNotCreateClaim() {
		tx(() -> {
			field(entities.find(type("contract.InsuranceContract"), contractId), "status", "CANCELLED");
			return null;
		});
		long eventId = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		assertThat(intake.process(eventId)).isEqualTo("NO_COVERED_CONTRACT");
		assertThat(claims.count()).isZero();
	}

	@Test
	void usesWorkLocationTimeZone() {
		OffsetDateTime start = OffsetDateTime.parse("2026-11-24T08:00:00-08:00");
		ObjectNode work = snapshot(7001, start).put("agreedTimeZone", "America/Los_Angeles");
		long eventId = event(9001, "1", 7001, start.minusHours(1), work, "CANCELLED", "WEATHER");
		assertThat(intake.process(eventId)).isEqualTo("RECEIVED");
	}

	@Test
	void repeatedEventAndNewVersionKeepOriginalIntakeEvidenceAndFinalStatus() {
		long first = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		intake.process(first);
		assertThat(intake.process(first)).isEqualTo("ALREADY_PROCESSED");
		ClaimCase original = claims.findAll().getFirst();
		tx(() -> {
			field(claims.findById(original.getId()).orElseThrow(), "status", "PAID");
			return null;
		});
		long revised = event(9001, "2", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		assertThat(intake.process(revised)).isEqualTo("ALREADY_RECEIVED");
		assertThat(claims.count()).isEqualTo(1);
		assertThat(history.count()).isEqualTo(1);
		ClaimCase stored = claims.findById(original.getId()).orElseThrow();
		assertThat(stored.getTriggerEventId()).isEqualTo(first);
		assertThat(stored.getStatus()).isEqualTo("PAID");
	}

	@Test
	void separateAssignmentsAndProductsCreateSeparateClaims() {
		tx(() -> {
			contract(owner.id(), product("SECOND"), LocalDateTime.of(2026, 11, 1, 0, 0),
				LocalDateTime.of(2026, 12, 1, 0, 0), "ACTIVE");
			return null;
		});
		intake.process(event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER"));
		intake.process(event(9002, "1", 7002, CANCELLED, snapshot(7002, START), "CANCELLED", "WEATHER"));
		assertThat(claims.count()).isEqualTo(4);
		assertThat(history.count()).isEqualTo(4);
	}

	@Test
	void revokedLinkIsExcludedButRenewedConsentForSameOwnerWorks() {
		tx(() -> {
			field(entities.find(type("platform.PlatformLink"), linkId), "revokedAt", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
			entities.flush();
			return null;
		});
		long skipped = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		assertThat(intake.process(skipped)).isEqualTo("INACTIVE_LINK");
		tx(() -> { link(owner.id(), 77); return null; });
		long eventId = event(9002, "1", 7002, CANCELLED, snapshot(7002, START), "CANCELLED", "WEATHER");
		assertThat(intake.process(eventId)).isEqualTo("RECEIVED");
	}

	@Test
	void anotherMemberLinkCannotTakeOwnershipOfHistoricalEvents() {
		tx(() -> {
			field(entities.find(type("platform.PlatformLink"), linkId), "revokedAt", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
			entities.flush();
			link(account("WORKER").id(), 77);
			return null;
		});
		long eventId = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		assertThat(intake.process(eventId)).isEqualTo("INACTIVE_LINK");
		assertThat(claims.count()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"workerId", "assignmentId", "agreedTimeZone", "agreedDailyWage"})
	void invalidStoredSnapshotRemainsPending(String field) {
		ObjectNode work = snapshot(7001, START);
		switch (field) {
			case "workerId", "assignmentId" -> work.put(field, 999999);
			case "agreedTimeZone" -> work.put(field, "invalid-zone");
			case "agreedDailyWage" -> work.put(field, 0);
			default -> throw new IllegalArgumentException(field);
		}
		long eventId = event(9001, "1", 7001, CANCELLED, work, "CANCELLED", "WEATHER");
		assertThatThrownBy(() -> intake.process(eventId)).isInstanceOf(RuntimeException.class);
		assertThat(result(eventId)).isNull();
		assertThat(source.pendingIds()).contains(eventId);
		assertThat(claims.count()).isZero();
	}

	@Test
	void historyFailureRollsBackClaimAndProcessingProgressThenRetries() {
		long eventId = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		doThrow(new IllegalStateException("temporary storage failure")).when(history).saveAndFlush(any());
		scheduler.run();
		assertThat(claims.count()).isZero();
		assertThat(history.count()).isZero();
		assertThat(result(eventId)).isEqualTo("RETRY_PENDING");
		assertThat(source.pendingIds()).isEmpty();
		reset(history);
		when(clock.instant()).thenReturn(NOW.plusSeconds(61));
		scheduler.run();
		assertThat(claims.count()).isEqualTo(1);
		assertThat(history.count()).isEqualTo(1);
		assertThat(result(eventId)).isEqualTo("RECEIVED");
	}

	@Test
	void legacyEventLoadsAndStoresMissingSnapshot() {
		farm.server().expect(requestTo("http://farm.test/api/internal/insurance/workers/77/work-assignments/7001"))
			.andRespond(withSuccess(json.writeValueAsString(snapshot(7001, START)), MediaType.APPLICATION_JSON));
		long eventId = event(9001, "1", 7001, CANCELLED, null, "CANCELLED", "WEATHER");
		assertThat(intake.process(eventId)).isEqualTo("RECEIVED");
		String storedSnapshot = tx(() -> entities.createQuery(
			"select e.workSnapshot from PlatformWorkEvent e where e.id = :id", String.class)
			.setParameter("id", eventId).getSingleResult());
		assertThat(json.readTree(storedSnapshot).get("assignmentId").asLong()).isEqualTo(7001);
	}

	@Test
	void failedLegacyLookupDoesNotBlockOtherEventsAndRetriesAfterDelay() {
		String endpoint = "http://farm.test/api/internal/insurance/workers/77/work-assignments/7001";
		farm.server().expect(requestTo(endpoint)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		farm.server().expect(requestTo(endpoint))
			.andRespond(withSuccess(json.writeValueAsString(snapshot(7001, START)), MediaType.APPLICATION_JSON));
		long retry = event(9001, "1", 7001, CANCELLED, null, "CANCELLED", "WEATHER");
		long valid = event(9002, "1", 7002, CANCELLED, snapshot(7002, START), "CANCELLED", "WEATHER");
		scheduler.run();
		assertThat(result(retry)).isEqualTo("RETRY_PENDING");
		assertThat(result(valid)).isEqualTo("RECEIVED");
		assertThat(claims.count()).isEqualTo(1);
		when(clock.instant()).thenReturn(NOW.plusSeconds(61));
		scheduler.run();
		assertThat(result(retry)).isEqualTo("RECEIVED");
		assertThat(claims.count()).isEqualTo(2);
	}

	@Test
	void existingManualClaimIsReused() {
		long eventId = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		long manualId = tx(() -> {
			ClaimCase manual = new ClaimCase(contractId, 7001, eventId,
				json.writeValueAsString(snapshot(7001, START)), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
			field(manual, "openedVia", "MANUAL");
			return claims.saveAndFlush(manual).getId();
		});
		assertThat(intake.process(eventId)).isEqualTo("ALREADY_RECEIVED");
		assertThat(claims.count()).isEqualTo(1);
		assertThat(claims.findById(manualId).orElseThrow().getOpenedVia()).isEqualTo("MANUAL");
		assertThat(history.count()).isZero();
	}

	@Test
	void concurrentVersionsCreateOneClaimAndOneHistory() throws Exception {
		long first = event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		long revised = event(9001, "2", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER");
		CountDownLatch inside = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch secondAttempt = new CountDownLatch(1);
		AtomicInteger attempts = new AtomicInteger();
		PlatformClaimEvents sourceSpy = AopTestUtils.getUltimateTargetObject(source);
		var sourceAnswer = mockingDetails(sourceSpy).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			if (attempts.incrementAndGet() == 2) secondAttempt.countDown();
			return sourceAnswer.answer(invocation);
		}).when(sourceSpy).lockPending(anyLong());
		var realAnswer = mockingDetails(history).getMockCreationSettings().getDefaultAnswer();
		doAnswer(invocation -> {
			inside.countDown();
			assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
			return realAnswer.answer(invocation);
		}).when(history).saveAndFlush(any());
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			try {
				Future<String> one = pool.submit(() -> intake.process(first));
				assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
				Future<String> two = pool.submit(() -> intake.process(revised));
				assertThat(secondAttempt.await(10, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> two.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
				verify(sourceSpy, times(1)).verifiedWork(any());
				release.countDown();
				assertThat(one.get(15, TimeUnit.SECONDS)).isEqualTo("RECEIVED");
				assertThat(two.get(15, TimeUnit.SECONDS)).isEqualTo("ALREADY_RECEIVED");
			} finally {
				release.countDown();
			}
		}
		assertThat(claims.count()).isEqualTo(1);
		assertThat(history.count()).isEqualTo(1);
	}

	@Test
	void ownListAndDetailReturnSnapshotsAndDenyOtherAccounts() throws Exception {
		intake.process(event(9001, "1", 7001, CANCELLED, snapshot(7001, START), "CANCELLED", "WEATHER"));
		long claimId = claims.findAll().getFirst().getId();
		MockHttpSession session = login(owner);
		mvc.perform(get("/api/claims").session(session)).andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].status").value("REVIEWING"))
			.andExpect(jsonPath("$[0].productName").value("기상 보험 DEMO"));
		mvc.perform(get("/api/claims/{id}", claimId).session(session)).andExpect(status().isOk())
			.andExpect(jsonPath("$.claim.id").value(claimId)).andExpect(jsonPath("$.intakeSource.changeId").value(9001))
			.andExpect(jsonPath("$.work.assignmentId").value(7001)).andExpect(jsonPath("$.work.agreedDailyWage").value(120000))
			.andExpect(jsonPath("$.work.agreedTimeZone").value("GMT+9"));
		tx(() -> {
			field(entities.find(type("platform.PlatformLink"), linkId), "revokedAt", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
			return null;
		});
		mvc.perform(get("/api/claims/{id}", claimId).session(session)).andExpect(status().isOk());
		Account other = tx(() -> account("WORKER"));
		MockHttpSession otherSession = login(other);
		mvc.perform(get("/api/claims").session(otherSession)).andExpect(status().isOk()).andExpect(content().json("[]"));
		mvc.perform(get("/api/claims/{id}", claimId).session(otherSession)).andExpect(status().isNotFound());
		mvc.perform(get("/api/claims")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/claims/{id}", claimId)).andExpect(status().isUnauthorized());
		MockHttpSession admin = login(tx(() -> account("ADMIN")));
		mvc.perform(get("/api/claims").session(admin)).andExpect(status().isForbidden());
		mvc.perform(post("/api/claims").session(session).with(csrf())).andExpect(status().isForbidden());
		for (String id : List.of("0", "-1", "abc", "9223372036854775808")) {
			mvc.perform(get("/api/claims/" + id).session(session)).andExpect(status().isBadRequest());
		}
	}

	private long event(long changeId, String version, long assignmentId, OffsetDateTime occurred,
		ObjectNode snapshot, String type, String reason) {
		return tx(() -> {
			Object event = entity("platform.PlatformWorkEvent");
			field(event, "platformLinkId", linkId);
			field(event, "sourceChangeId", changeId);
			field(event, "sourceVersion", version);
			field(event, "assignmentId", assignmentId);
			field(event, "eventType", type);
			field(event, "reasonCode", reason);
			field(event, "reasonDetail", "작업 취소");
			field(event, "occurredAt", utc(occurred));
			field(event, "sourceUpdatedAt", utc(occurred));
			field(event, "receivedAt", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
			field(event, "intakeNextAttemptAt", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
			field(event, "workSnapshot", snapshot == null ? null : json.writeValueAsString(snapshot));
			entities.persist(event);
			entities.flush();
			return (Long) ReflectionTestUtils.getField(event, "id");
		});
	}

	private ObjectNode snapshot(long assignmentId, OffsetDateTime start) {
		ObjectNode work = json.createObjectNode();
		work.put("assignmentId", assignmentId).put("workerId", 77);
		work.put("confirmedAt", start.minusDays(3).toString());
		work.put("agreedWorkDate", start.toLocalDate().toString()).put("agreedTimeZone", "GMT+9");
		work.put("agreedWorkLocation", "시연 농장").put("agreedCropCode", "CABBAGE").put("agreedWorkTypeCode", "HARVEST");
		work.put("agreedStartAt", start.toString()).put("agreedEndAt", start.plusHours(9).toString());
		work.putNull("agreedBreakStartAt").putNull("agreedBreakEndAt");
		work.put("agreedDailyWage", 120000);
		return work;
	}

	private long product(String code) {
		Object product = entity("product.InsuranceProduct");
		field(product, "productCode", code);
		field(product, "name", "기상 보험 " + code);
		field(product, "description", "시연 상품");
		field(product, "status", "ACTIVE");
		entities.persist(product);
		Object version = entity("product.ProductVersion");
		field(version, "product", product);
		field(version, "versionNo", 1);
		field(version, "salesStartAt", LocalDateTime.of(2026, 1, 1, 0, 0));
		field(version, "termsText", "시연 약관");
		for (String field : List.of("premiumTerms", "stationSelectionRule", "windowRule", "indexRules", "payoutRules")) {
			field(version, field, "{}");
		}
		field(version, "eligibleWorkGroups", "[]");
		entities.persist(version);
		entities.flush();
		return (Long) ReflectionTestUtils.getField(version, "id");
	}

	private Account account(String role) {
		Object member = entity("member.Member");
		String loginId = UUID.randomUUID().toString();
		field(member, "loginId", loginId);
		field(member, "name", "시연 회원");
		field(member, "passwordHash", passwords.encode(PASSWORD));
		try {
			Object value = java.util.Arrays.stream(member.getClass().getDeclaredField("role").getType().getEnumConstants())
				.filter(item -> ((Enum<?>) item).name().equals(role)).findFirst().orElseThrow();
			field(member, "role", value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
		entities.persist(member);
		entities.flush();
		return new Account((Long) ReflectionTestUtils.getField(member, "id"), loginId);
	}

	private long link(long memberId, long workerId) {
		Object link = entity("platform.PlatformLink");
		field(link, "memberId", memberId);
		field(link, "workerId", workerId);
		field(link, "consentVersion", "farm-platform-v1");
		field(link, "consentedAt", LocalDateTime.of(2026, 11, 1, 0, 0));
		entities.persist(link);
		entities.flush();
		return (Long) ReflectionTestUtils.getField(link, "id");
	}

	private long contract(long memberId, long versionId, LocalDateTime from, LocalDateTime to, String status) {
		Object contract = entity("contract.InsuranceContract");
		field(contract, "memberId", memberId);
		field(contract, "productVersionId", versionId);
		field(contract, "acceptedAt", from);
		field(contract, "coverageFrom", from);
		field(contract, "coverageTo", to);
		field(contract, "status", status);
		entities.persist(contract);
		entities.flush();
		return (Long) ReflectionTestUtils.getField(contract, "id");
	}

	private MockHttpSession login(Account account) throws Exception {
		return (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
			.contentType(MediaType.APPLICATION_FORM_URLENCODED).param("loginId", account.loginId()).param("password", PASSWORD))
			.andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
	}

	private String result(long eventId) {
		return tx(() -> entities.createQuery("select e.intakeResult from PlatformWorkEvent e where e.id = :id", String.class)
			.setParameter("id", eventId).getSingleResult());
	}

	private static LocalDateTime utc(OffsetDateTime value) {
		return value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.MICROS);
	}

	private <T> T tx(Supplier<T> action) {
		return new TransactionTemplate(transactionManager).execute(status -> action.get());
	}

	private static Class<?> type(String name) {
		try {
			return Class.forName("com.wonday.insurance." + name);
		} catch (ClassNotFoundException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static Object entity(String name) {
		try {
			return BeanUtils.instantiateClass(type(name).getDeclaredConstructor());
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void field(Object object, String name, Object value) {
		ReflectionTestUtils.setField(object, name, value);
	}

	record Account(long id, String loginId) {}

	record FarmStub(RestClient client, MockRestServiceServer server) {}

	@TestConfiguration
	static class FarmTestConfiguration {
		@Bean
		FarmStub farmStub() {
			RestClient.Builder builder = RestClient.builder().baseUrl("http://farm.test");
			MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
			return new FarmStub(builder.build(), server);
		}

		@Bean
		@Primary
		RestClient claimTestFarmRestClient(FarmStub farm) {
			return farm.client();
		}
	}
}
