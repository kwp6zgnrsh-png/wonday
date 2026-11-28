package com.wonday.insurance.weather;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class WeatherCollectionJobTests {
	private static final String ROOT="/api/admin/weather/collection-jobs", COLLECT="/api/admin/weather/collections", CODE="477802A001";
	private static final LocalDate DATE=LocalDate.of(2026,11,25);
	private static final Instant NOW=Instant.parse("2026-11-27T09:00:00.123456789Z");
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManager entities;
	@Autowired PlatformTransactionManager transactions;
	@Autowired WeatherCollectionJobRepository jobs;
	@Autowired WeatherCollectionAttemptRepository attempts;
	@MockitoSpyBean WeatherCollectionRepository originals;
	@Autowired WeatherCollectionService collecting;
	@Autowired WeatherCollectionJobWriter writer;
	@Autowired ObjectMapper json;
	@MockitoBean RdaWeatherClient provider;
	@MockitoBean Clock clock;
	private final AtomicReference<Instant> time=new AtomicReference<>();

	@BeforeEach void prepare() {
		new TransactionTemplate(transactions).executeWithoutResult(status -> {
			attempts.deleteAllInBatch(); jobs.deleteAllInBatch(); originals.deleteAllInBatch();
			entities.createQuery("delete from Member").executeUpdate();
		});
		time.set(NOW); when(clock.instant()).thenAnswer(call -> time.get());
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return batch("<weather/>");
		}).when(provider).observations(anyString(),any());
	}

	@Test void failureIsDurableAndListsOnlySafeErrorMetadata() throws Exception {
		Account admin=account(true);
		failProvider();
		String error=mvc.perform(collect(admin,DATE,null)).andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value("WEATHER_SERVICE_UNAVAILABLE"))
			.andReturn().getResponse().getContentAsString();
		long jobId=number(error,"$.jobId"), attemptId=number(error,"$.attemptId");
		assertThat(originals.count()).isZero();
		assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(WeatherCollectionJob.State.FAILED);
		assertThat(attempts.findById(attemptId).orElseThrow().getFinishedAt().toString()).isEqualTo("2026-11-27T09:00:00.123456");
		mvc.perform(get(ROOT).session(admin.session()).param("state","FAILED").param("stationCode",CODE).param("date",DATE.toString()))
			.andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
			.andExpect(jsonPath("$.items[0].id").value(jobId)).andExpect(jsonPath("$.items[0].attemptCount").value(1))
			.andExpect(jsonPath("$.items[0].createdAt").value("2026-11-27T18:00:00.123456+09:00"))
			.andExpect(jsonPath("$.items[0].updatedAt").value("2026-11-27T18:00:00.123456+09:00"))
			.andExpect(jsonPath("$.items[0].retryable").value(true)).andExpect(jsonPath("$.items[0].leaseExpiresAt").isEmpty());
		mvc.perform(get(ROOT+"/"+jobId+"/attempts").session(admin.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].id").value(attemptId)).andExpect(jsonPath("$.items[0].state").value("FAILED"))
			.andExpect(jsonPath("$.items[0].startedAt").value("2026-11-27T18:00:00.123456+09:00"))
			.andExpect(jsonPath("$.items[0].finishedAt").value("2026-11-27T18:00:00.123456+09:00"))
			.andExpect(jsonPath("$.items[0].errorCode").value("WEATHER_SERVICE_UNAVAILABLE"));
	}

	@Test void successfulRetryKeepsTheFailureAndReplayingTheKeyDoesNotFetchAgain() throws Exception {
		Account admin=account(true); long jobId=failedJob(admin);
		doReturn(batch("<recovered/>")).when(provider).observations(CODE,DATE);
		String key=UUID.randomUUID().toString();
		String response=mvc.perform(retry(admin,jobId,key)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		long collectionId=number(response,"$.id");
		mvc.perform(retry(admin,jobId,key)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(collectionId));
		verify(provider,times(2)).observations(CODE,DATE);
		assertThat(originals.count()).isEqualTo(1); assertThat(attempts.count()).isEqualTo(2);
		mvc.perform(get(ROOT+"/"+jobId).session(admin.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("SUCCEEDED")).andExpect(jsonPath("$.attemptCount").value(2))
			.andExpect(jsonPath("$.lastCollectionId").value(collectionId)).andExpect(jsonPath("$.retryable").value(false));
		mvc.perform(get(ROOT+"/"+jobId+"/attempts").session(admin.session())).andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].state").value("SUCCEEDED")).andExpect(jsonPath("$.items[1].state").value("FAILED"));
	}

	@Test void failedRetryIsAlsoIdempotentAndANewKeyStartsANewAttempt() throws Exception {
		Account admin=account(true); long jobId=failedJob(admin); String key=UUID.randomUUID().toString();
		String first=mvc.perform(retry(admin,jobId,key)).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
		mvc.perform(retry(admin,jobId,key)).andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.attemptId").value(number(first,"$.attemptId")));
		verify(provider,times(2)).observations(CODE,DATE);
		mvc.perform(retry(admin,jobId,UUID.randomUUID().toString())).andExpect(status().isServiceUnavailable());
		assertThat(attempts.count()).isEqualTo(3);
	}

	@Test void retryKeysMustBeCanonicalAndCannotBeReusedForAnotherJobOrOperation() throws Exception {
		Account admin=account(true); String key=UUID.randomUUID().toString(); failProvider();
		String initial=mvc.perform(collect(admin,DATE,key)).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString();
		long jobId=number(initial,"$.jobId");
		for(String invalid:List.of("bad","1-1-1-1-1","", "secret-key-in-input")) {
			mvc.perform(retry(admin,jobId,invalid)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_WEATHER_REQUEST_KEY"));
		}
		mvc.perform(retry(admin,jobId,null)).andExpect(status().isBadRequest());
		mvc.perform(retry(admin,jobId,key)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEATHER_IDEMPOTENCY_KEY_REUSED"));
		mvc.perform(collect(admin,DATE.minusDays(1),key)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEATHER_IDEMPOTENCY_KEY_REUSED"))
			.andExpect(jsonPath("$.jobId").doesNotExist());
		assertThat(jobs.count()).isEqualTo(1); assertThat(attempts.count()).isEqualTo(1); verify(provider,times(1)).observations(anyString(),any());
	}

	@Test void optionalInitialKeyIsNormalizedAndCanReplaySuccessAndFailure() throws Exception {
		Account admin=account(true); String key=UUID.randomUUID().toString();
		String result=mvc.perform(collect(admin,DATE,key.toUpperCase())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		mvc.perform(collect(admin,DATE,key)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(number(result,"$.id")));
		failProvider(); String failureKey=UUID.randomUUID().toString();
		mvc.perform(collect(admin,DATE,failureKey)).andExpect(status().isServiceUnavailable());
		mvc.perform(collect(admin,DATE,failureKey)).andExpect(status().isServiceUnavailable());
		assertThat(attempts.count()).isEqualTo(2); verify(provider,times(2)).observations(CODE,DATE);
		assertThat(jobs.findAll().getFirst().getLastCollectionId()).isEqualTo(number(result,"$.id"));
	}

	@Test void onlyFailedJobsCanRetryAndAuthorizationAndCsrfRemainRequired() throws Exception {
		Account admin=account(true), worker=account(false);
		collecting.collect(admin.id(),CODE,DATE); long id=jobs.findAll().getFirst().getId();
		mvc.perform(retry(admin,id,UUID.randomUUID().toString())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEATHER_COLLECTION_NOT_RETRYABLE"));
		mvc.perform(get(ROOT)).andExpect(status().isUnauthorized());
		for(String path:List.of(ROOT,ROOT+"/"+id,ROOT+"/"+id+"/attempts")) mvc.perform(get(path).session(worker.session())).andExpect(status().isForbidden());
		mvc.perform(retry(worker,id,UUID.randomUUID().toString())).andExpect(status().isForbidden());
		mvc.perform(post(ROOT+"/"+id+"/retry").session(admin.session()).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isForbidden());
		mvc.perform(get(ROOT+"/999999").session(admin.session())).andExpect(status().isNotFound());
		mvc.perform(retry(admin,999999,UUID.randomUUID().toString())).andExpect(status().isNotFound());
		jdbc.update("update member set role='WORKER' where id=?",admin.id());
		mvc.perform(get(ROOT).session(admin.session())).andExpect(status().isForbidden());
		mvc.perform(retry(admin,id,UUID.randomUUID().toString())).andExpect(status().isForbidden());
		verify(provider,times(1)).observations(CODE,DATE);
	}

	@Test void parallelRetriesFromDifferentAdministratorsHaveOneRemoteCall() throws Exception {
		Account one=account(true),two=account(true); long jobId=failedJob(one); clearInvocations(provider);
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		doAnswer(call -> { entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return batch("<only-once/>"); }).when(provider).observations(CODE,DATE);
		ExecutorService pool=Executors.newFixedThreadPool(2);
		try {
			var first=pool.submit(() -> collecting.retry(one.id(),jobId,UUID.randomUUID().toString()));
			assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
			var second=pool.submit(() -> catchThrowable(() -> collecting.retry(two.id(),jobId,UUID.randomUUID().toString())));
			assertThat(second.get(5,TimeUnit.SECONDS)).isInstanceOfSatisfying(WeatherException.class,error -> assertThat(error.getCode()).isEqualTo("WEATHER_COLLECTION_IN_PROGRESS"));
			verify(provider,times(1)).observations(CODE,DATE);
			release.countDown(); assertThat(first.get(20,TimeUnit.SECONDS).id()).isPositive(); assertThat(attempts.count()).isEqualTo(2);
		} finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
	}

	@Test void inProgressReplayDoesNotCreateAnotherAttempt() throws Exception {
		Account admin=account(true); long jobId=failedJob(admin); String key=UUID.randomUUID().toString();
		CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
		doAnswer(call -> { entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return batch("<same-key/>"); }).when(provider).observations(CODE,DATE);
		ExecutorService pool=Executors.newSingleThreadExecutor();
		try {
			var first=pool.submit(() -> collecting.retry(admin.id(),jobId,key));
			assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
			mvc.perform(retry(admin,jobId,key)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEATHER_COLLECTION_IN_PROGRESS"));
			release.countDown(); long id=first.get(20,TimeUnit.SECONDS).id();
			mvc.perform(retry(admin,jobId,key)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
			assertThat(attempts.count()).isEqualTo(2); verify(provider,times(2)).observations(CODE,DATE);
		} finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
	}

	@Test void interruptedRunCanRetryAfterDeadlineAndOldKeyReplaysItsFailure() throws Exception {
		Account admin=account(true); String key=UUID.randomUUID().toString();
		var abandoned=writer.collect(admin.id(),CODE,DATE,key);
		time.set(NOW.plusSeconds(16*60));
		mvc.perform(get(ROOT+"/"+abandoned.jobId()).session(admin.session())).andExpect(status().isOk()).andExpect(jsonPath("$.retryable").value(true));
		mvc.perform(collect(admin,DATE,key)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WEATHER_COLLECTION_RUN_EXPIRED"));
		mvc.perform(retry(admin,abandoned.jobId(),UUID.randomUUID().toString())).andExpect(status().isOk());
		assertThat(attempts.findById(abandoned.attemptId()).orElseThrow().getErrorCode()).isEqualTo("WEATHER_COLLECTION_RUN_EXPIRED");
		assertThat(jobs.findById(abandoned.jobId()).orElseThrow().getAttemptCount()).isEqualTo(2);
		verify(provider,times(1)).observations(CODE,DATE);
	}

	@Test void lateCompletionCannotOverwriteANewerRetry() throws Exception {
		Account admin=account(true); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1); AtomicBoolean first=new AtomicBoolean(true);
		doAnswer(call -> {
			if(first.getAndSet(false)) { entered.countDown(); assertThat(release.await(15,TimeUnit.SECONDS)).isTrue(); return batch("<old/>"); }
			return batch("<new/>");
		}).when(provider).observations(CODE,DATE);
		ExecutorService pool=Executors.newSingleThreadExecutor();
		try {
			var old=pool.submit(() -> catchThrowable(() -> collecting.collect(admin.id(),CODE,DATE)));
			assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue(); long jobId=jobs.findAll().getFirst().getId(); time.set(NOW.plusSeconds(16*60));
			long latest=collecting.retry(admin.id(),jobId,UUID.randomUUID().toString()).id();
			release.countDown();
			assertThat(old.get(20,TimeUnit.SECONDS)).isInstanceOfSatisfying(WeatherException.class,error -> assertThat(error.getCode()).isEqualTo("WEATHER_COLLECTION_RUN_SUPERSEDED"));
			assertThat(originals.count()).isEqualTo(1); assertThat(originals.findById(latest).orElseThrow().getPayloadPagesJson()).contains("<new/>");
			assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(WeatherCollectionJob.State.SUCCEEDED);
		} finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue(); }
	}

	@Test void roleRevocationDuringFetchStillRecordsTheFailureWithoutSavingSource() throws Exception {
		Account admin=account(true);
		doAnswer(call -> { jdbc.update("update member set role='WORKER' where id=?",admin.id()); return batch("<not-saved/>"); }).when(provider).observations(CODE,DATE);
		mvc.perform(collect(admin,DATE,null)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("WEATHER_COLLECTION_ACCESS_CHANGED"));
		assertThat(originals.count()).isZero(); assertThat(attempts.findAll().getFirst().getErrorCode()).isEqualTo("WEATHER_COLLECTION_ACCESS_CHANGED");
		assertThat(jobs.findAll().getFirst().getState()).isEqualTo(WeatherCollectionJob.State.FAILED);
	}

	@Test void rawSourceAndSuccessHistoryRollBackTogetherWhenSuccessUpdateFails() throws Exception {
		Account admin=account(true);
		jdbc.execute("create trigger reject_weather_success before update on weather_collection_job for each row begin if new.state='SUCCEEDED' then signal sqlstate '45000' set message_text='Synthetic storage failure'; end if; end");
		try {
			mvc.perform(collect(admin,DATE,null)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WEATHER_COLLECTION_STORAGE_UNAVAILABLE"));
			assertThat(originals.count()).isZero(); assertThat(jobs.findAll().getFirst().getState()).isEqualTo(WeatherCollectionJob.State.FAILED);
			assertThat(attempts.findAll().getFirst().getCollectionId()).isNull();
		} finally { jdbc.execute("drop trigger reject_weather_success"); }
	}

	@Test void unavailableFailureStorageLeavesADurableRunThatCanBeRecoveredLater() throws Exception {
		Account admin=account(true);
		doAnswer(call -> {
			jdbc.execute("create trigger reject_weather_updates before update on weather_collection_job for each row signal sqlstate '45000' set message_text='Synthetic outage'");
			throw WeatherException.unavailable();
		}).when(provider).observations(CODE,DATE);
		try {
			mvc.perform(collect(admin,DATE,null)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WEATHER_COLLECTION_STORAGE_UNAVAILABLE"));
			assertThat(jobs.findAll().getFirst().getState()).isEqualTo(WeatherCollectionJob.State.RUNNING);
		} finally { jdbc.execute("drop trigger reject_weather_updates"); }
		time.set(NOW.plusSeconds(16*60)); doReturn(batch("<recovered-after-outage/>")).when(provider).observations(CODE,DATE);
		mvc.perform(retry(admin,jobs.findAll().getFirst().getId(),UUID.randomUUID().toString())).andExpect(status().isOk());
		assertThat(attempts.count()).isEqualTo(2);
	}

	@Test void arbitraryProviderExceptionCannotLeakItsMessageIntoResponseOrHistory() throws Exception {
		Account admin=account(true); String secret="private-serviceKey-in-error";
		doThrow(new IllegalStateException("https://provider.test?serviceKey="+secret)).when(provider).observations(CODE,DATE);
		String response=mvc.perform(collect(admin,DATE,null)).andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString();
		assertThat(response).doesNotContain(secret,"provider.test");
		assertThat(attempts.findAll().getFirst().getErrorCode()).isEqualTo("WEATHER_COLLECTION_FAILED");
		assertThat(mvc.perform(get(ROOT).session(admin.session())).andReturn().getResponse().getContentAsString()).doesNotContain(secret);
	}

	@Test void historyUsesBoundedKeysetPaginationAndRejectsInvalidQueries() throws Exception {
		Account admin=account(true); failProvider();
		for(int day=0;day<3;day++) mvc.perform(collect(admin,DATE.minusDays(day),null)).andExpect(status().isServiceUnavailable());
		String first=mvc.perform(get(ROOT).session(admin.session()).param("limit","2").param("state","FAILED"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString();
		mvc.perform(get(ROOT).session(admin.session()).param("limit","2").param("beforeId",Long.toString(number(first,"$.nextBeforeId"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.hasMore").value(false));
		long jobId=jobs.findAll().stream().filter(job -> job.getObservationDate().equals(DATE)).findFirst().orElseThrow().getId();
		mvc.perform(collect(admin,DATE,null)).andExpect(status().isServiceUnavailable()); mvc.perform(collect(admin,DATE,null)).andExpect(status().isServiceUnavailable());
		String history=mvc.perform(get(ROOT+"/"+jobId+"/attempts").session(admin.session()).param("limit","2"))
			.andExpect(status().isOk()).andExpect(jsonPath("$.hasMore").value(true)).andReturn().getResponse().getContentAsString();
		mvc.perform(get(ROOT+"/"+jobId+"/attempts").session(admin.session()).param("beforeId",Long.toString(number(history,"$.nextBeforeId"))))
			.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
		for(String[] query:new String[][]{{"limit","0"},{"limit","101"},{"beforeId","0"},{"state","UNKNOWN"},{"date","2026-11-28"},{"stationCode","unknown"}})
			mvc.perform(get(ROOT).session(admin.session()).param(query[0],query[1])).andExpect(status().isBadRequest());
	}

	@Test void sourceUniqueConflictIsRecoveredOutsideTheFailedTransaction() throws Exception {
		Account admin=account(true); String pages=json.writeValueAsString(List.of("<weather/>"));
		String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pages.getBytes(StandardCharsets.UTF_8)));
		jdbc.update("insert into weather_collection (station_code,observation_date,observation_count,payload_sha256,payload_pages_json,collected_at) values (?,?,1,?,?,'2026-11-27 08:00:00')",CODE,DATE,hash,pages);
		long existing=originals.findAll().getFirst().getId();
		var real=mockingDetails(originals).getMockCreationSettings().getDefaultAnswer(); AtomicBoolean first=new AtomicBoolean(true);
		doAnswer(call -> first.getAndSet(false)?java.util.Optional.empty():real.answer(call))
			.when(originals).findByStationCodeAndObservationDateAndPayloadSha256(CODE,DATE,hash);
		mvc.perform(collect(admin,DATE,null)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(existing));
		assertThat(originals.count()).isEqualTo(1); assertThat(jobs.findAll().getFirst().getState()).isEqualTo(WeatherCollectionJob.State.SUCCEEDED);
	}

	private long failedJob(Account admin) throws Exception {
		failProvider();
		return number(mvc.perform(collect(admin,DATE,null)).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString(),"$.jobId");
	}
	private void failProvider() { doThrow(WeatherException.unavailable()).when(provider).observations(anyString(),any()); }
	private static RdaWeatherClient.Batch batch(String source) { return new RdaWeatherClient.Batch(List.of(source),1); }
	private MockHttpServletRequestBuilder collect(Account account,LocalDate date,String key) {
		var request=post(COLLECT).session(account.session()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"stationCode\":\""+CODE+"\",\"date\":\""+date+"\"}");
		return key==null?request:request.header("Idempotency-Key",key);
	}
	private MockHttpServletRequestBuilder retry(Account account,long id,String key) {
		var request=post(ROOT+"/"+id+"/retry").session(account.session()).with(csrf()); return key==null?request:request.header("Idempotency-Key",key);
	}
	private Account account(boolean admin) throws Exception {
		String login="weather-jobs-"+UUID.randomUUID();
		long id=number(mvc.perform(post("/api/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\""+login+"\",\"name\":\"Job fixture\",\"password\":\"test-password\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(),"$.id");
		if(admin) jdbc.update("update member set role='ADMIN' where id=?",id);
		var session=(MockHttpSession)mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_FORM_URLENCODED).formField("loginId",login).formField("password","test-password"))
			.andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
		return new Account(id,session);
	}
	private static long number(String json,String path) { return ((Number)JsonPath.read(json,path)).longValue(); }
	private record Account(long id,MockHttpSession session) {}
}
